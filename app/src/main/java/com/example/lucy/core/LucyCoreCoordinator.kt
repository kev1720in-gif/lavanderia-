package com.example.lucy.core

import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.BuscadorTrabajadores
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.lucy.memory.LucyCampoMaestroEditado
import com.example.lucy.memory.LucyCorreccionAprendida
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyMemoryStorageSerializer
import com.example.lucy.memory.LucyNotaMemoria
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.memory.LucyRegistroCambioMaestro
import com.example.lucy.memory.LucyTipoCorreccionAprendida
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.example.lucy.vision.LucyVisionDocumentStatus
import com.example.lucy.vision.LucyVisionMatchStatus
import com.example.lucy.vision.LucyVisionMatcher
import com.example.lucy.vision.LucyVisionPlanillaAnalysis
import com.example.lucy.vision.LucyVisionTextOriginType
import com.example.lucy.voice.AndroidLucyVoicePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Núcleo modular independiente de LUCY.
 * - Coordina intenciones operativas contra datos reales de Lavandería Control (Turno A y Turno B).
 * - Aísla fallos de módulos externos (Vision/Voice/Memory) mediante SupervisorJob + captura de Throwable.
 * - No depende de OpenCV, ONNX Runtime, Firebase ni conectividad externa para operar.
 */
class LucyCoreCoordinator(
    private val repository: LavanderiaRepository,
    private val visionPort: LucyVisionPort = NoOpLucyVisionPort,
    private val voicePort: LucyVoicePort = NoOpLucyVoicePort,
    private val memoryPort: LucyMemoryPort = NoOpLucyMemoryPort,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val geminiBrainGateway: LucyGeminiBrainGateway = NoOpLucyGeminiBrainGateway
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        actualizarSalud(
            LucyModuleId.CORE,
            LucyModuleHealth.Degraded(throwable.message ?: "Error interno controlado en Lucy Core")
        )
    }

    private val coordinatorScope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)
    private val actionMutex = Mutex()

    private val historialConversacionalPorTurno = ConcurrentHashMap<Turno, ArrayDeque<LucyTurnExchange>>()
    private val ultimoAnalisisVisionPorTurno = ConcurrentHashMap<Turno, LucyVisionPlanillaAnalysis>()
    private val batchMarkingContextPorTurno = ConcurrentHashMap<Turno, LucyBatchContext>()
    private val pendingDoubtsPorTurno = ConcurrentHashMap<Turno, MutableList<LucyPendingDoubt>>()
    private val ultimoContextoCorreccionPorTurno = ConcurrentHashMap<Turno, LucyCorrectionContext>()
    private val historyLock = Any()

    private val _saludModulos = MutableStateFlow<Map<LucyModuleId, LucyModuleHealth>>(
        mapOf(
            LucyModuleId.CORE to LucyModuleHealth.Ready,
            LucyModuleId.VISION to LucyModuleHealth.NotConfigured,
            LucyModuleId.VOICE to LucyModuleHealth.NotConfigured,
            LucyModuleId.MEMORY to LucyModuleHealth.NotConfigured
        )
    )
    val saludModulos: StateFlow<Map<LucyModuleId, LucyModuleHealth>> = _saludModulos.asStateFlow()

    fun limpiarContextoConversacional(turno: Turno) {
        synchronized(historyLock) {
            historialConversacionalPorTurno.remove(turno)
            ultimoAnalisisVisionPorTurno.remove(turno)
            batchMarkingContextPorTurno.remove(turno)
            pendingDoubtsPorTurno.remove(turno)
            ultimoContextoCorreccionPorTurno.remove(turno)
        }
    }

    suspend fun refrescarSaludModulos(): Map<LucyModuleId, LucyModuleHealth> {
        val visionHealth = evaluarModuloSeguro(LucyModuleId.VISION) { visionPort.healthCheck() }
        val voiceHealth = evaluarModuloSeguro(LucyModuleId.VOICE) { voicePort.healthCheck() }
        val memoryHealth = evaluarModuloSeguro(LucyModuleId.MEMORY) { memoryPort.healthCheck() }
        if (voicePort !is AndroidLucyVoicePort) {
            aplicarPreferenciasVozSinFallo(obtenerPreferenciasSeguras())
        }

        _saludModulos.update { actual ->
            actual + mapOf(
                LucyModuleId.CORE to LucyModuleHealth.Ready,
                LucyModuleId.VISION to visionHealth,
                LucyModuleId.VOICE to voiceHealth,
                LucyModuleId.MEMORY to memoryHealth
            )
        }
        return _saludModulos.value
    }

    suspend fun procesarEntrada(
        entrada: String,
        contexto: LucyTurnContext? = null,
        origen: LucyOperationOrigin = LucyOperationOrigin.TEXT,
        hablarAlFinalizar: Boolean = true,
        onPartialText: ((String) -> Unit)? = null
    ): LucyResponse {
        val inicioEnvioMs = System.currentTimeMillis()
        val contextoResuelto = contexto ?: LucyTurnContext(turnoActivo = repository.turnoActivo.first())
        return ejecutarProtegido(contextoResuelto.turnoActivo) {
            val (hayBatchActivo, hayDudasPendientes, hayAnalisisVisionActivo) = synchronized(historyLock) {
                Triple(
                    batchMarkingContextPorTurno[contextoResuelto.turnoActivo] != null,
                    !pendingDoubtsPorTurno[contextoResuelto.turnoActivo].isNullOrEmpty(),
                    ultimoAnalisisVisionPorTurno[contextoResuelto.turnoActivo]?.parsedSheet != null
                )
            }

            val intent = LucyIntentParserOffline.parse(
                entrada = entrada,
                turnoActivo = contextoResuelto.turnoActivo,
                hayContextoMarcacionLote = hayBatchActivo,
                hayDudasPendientes = hayDudasPendientes,
                hayAnalisisVisionActivo = hayAnalisisVisionActivo
            )

            // Ruta rápida local para operaciones simples y deterministas que no requieren esperar a la nube
            if (esIntentFastPathLocal(intent, hayBatchActivo, hayDudasPendientes)) {
                val respuestaLocal = ejecutarIntent(intent, contextoResuelto, origen)
                val totalMs = (System.currentTimeMillis() - inicioEnvioMs).coerceAtLeast(0L)
                val respuestaConTraza = respuestaLocal.copy(
                    performanceTrace = LucyPerformanceTrace(
                        sendTimestampMs = inicioEnvioMs,
                        firstResponseLatencyMs = totalMs,
                        totalResponseLatencyMs = totalMs,
                        usedLocalFastPath = true,
                        modelUsed = "local-fast-path"
                    )
                )
                registrarIntercambioConversacional(
                    turno = respuestaConTraza.turnoContexto,
                    entrada = entrada,
                    intent = intent,
                    respuesta = respuestaConTraza,
                    contexto = contextoResuelto
                )
                registrarMemoriaSinFallo(respuestaConTraza.turnoContexto, entrada, respuestaConTraza.texto)
                if (hablarAlFinalizar && (origen == LucyOperationOrigin.VOICE || respuestaConTraza.texto.isNotBlank())) {
                    hablarRespuestaSinFallo(respuestaConTraza.texto)
                }
                return@ejecutarProtegido respuestaConTraza
            }

            val usarGemini = runCatching { geminiBrainGateway.isAvailable() }.getOrDefault(false)
            if (usarGemini) {
                val respuestaGemini = intentarProcesarConGeminiBrain(
                    entrada = entrada,
                    contexto = contextoResuelto,
                    origen = origen,
                    hayBatchActivo = hayBatchActivo,
                    inicioEnvioMs = inicioEnvioMs,
                    onPartialText = onPartialText
                )
                if (respuestaGemini != null) {
                    registrarMemoriaSinFallo(respuestaGemini.turnoContexto, entrada, respuestaGemini.texto)
                    if (hablarAlFinalizar && (origen == LucyOperationOrigin.VOICE || respuestaGemini.texto.isNotBlank())) {
                        hablarRespuestaSinFallo(respuestaGemini.texto)
                    }
                    return@ejecutarProtegido respuestaGemini
                }
            }

            val respuesta = ejecutarIntent(intent, contextoResuelto, origen)
            val totalMs = (System.currentTimeMillis() - inicioEnvioMs).coerceAtLeast(0L)
            val respuestaConTraza = respuesta.copy(
                performanceTrace = LucyPerformanceTrace(
                    sendTimestampMs = inicioEnvioMs,
                    firstResponseLatencyMs = totalMs,
                    totalResponseLatencyMs = totalMs,
                    usedLocalFastPath = false,
                    modelUsed = "offline-fallback"
                )
            )
            registrarIntercambioConversacional(
                turno = respuestaConTraza.turnoContexto,
                entrada = entrada,
                intent = intent,
                respuesta = respuestaConTraza,
                contexto = contextoResuelto
            )
            registrarMemoriaSinFallo(respuestaConTraza.turnoContexto, entrada, respuestaConTraza.texto)
            if (hablarAlFinalizar && (origen == LucyOperationOrigin.VOICE || respuestaConTraza.texto.isNotBlank())) {
                hablarRespuestaSinFallo(respuestaConTraza.texto)
            }
            respuestaConTraza
        }
    }

    private fun esIntentFastPathLocal(
        intent: LucyIntent,
        hayBatchActivo: Boolean,
        hayDudasPendientes: Boolean
    ): Boolean = when (intent) {
        is LucyIntent.ConversacionGeneral ->
            intent.subtipo != LucyConversationalSubtipo.CHARLA_GENERAL ||
                (!hayBatchActivo && !hayDudasPendientes)
        is LucyIntent.ConfigurarPreferenciaConversacion ->
            intent.instruccionPermanente == null
        is LucyIntent.Ayuda,
        is LucyIntent.CambiarTurno,
        is LucyIntent.ResumenTurno,
        is LucyIntent.ConsultarEmpresas,
        is LucyIntent.ListarTrabajadores,
        is LucyIntent.ConsultarPendientes,
        is LucyIntent.ConsultarMemoria,
        is LucyIntent.RecuperarContextoActual,
        is LucyIntent.ConsultarConversacionReciente,
        is LucyIntent.ConsultarHistorialCambios -> true
        else -> false
    }

    suspend fun ejecutarIntent(
        intent: LucyIntent,
        contexto: LucyTurnContext,
        origen: LucyOperationOrigin = LucyOperationOrigin.TEXT
    ): LucyResponse = ejecutarProtegido(contexto.turnoActivo) {
        when (intent) {
            is LucyIntent.ConversacionGeneral -> responderConversacionGeneral(
                intent = intent,
                turnoActivo = contexto.turnoActivo
            )

            is LucyIntent.ConsultarConversacionReciente -> responderConversacionReciente(
                intent = intent,
                turnoActivo = contexto.turnoActivo
            )

            is LucyIntent.RecuperarContextoActual -> responderContextoActual(
                contexto = contexto
            )

            is LucyIntent.ConsultarDetalleTrabajadorAnterior -> responderDetalleTrabajadorAnterior(
                intent = intent,
                turnoActivo = contexto.turnoActivo
            )

            is LucyIntent.AclaracionAmbigua -> responderAclaracionAmbigua(
                intent = intent,
                turnoActivo = contexto.turnoActivo
            )

            is LucyIntent.Ayuda -> responderAyuda(contexto.turnoActivo)

            is LucyIntent.CambiarTurno -> {
                if (intent.nuevoTurno == contexto.turnoActivo) {
                    LucyResponse(
                        turnoContexto = contexto.turnoActivo,
                        texto = "Ya estás trabajando en ${intent.nuevoTurno.nombre}."
                    )
                } else {
                    actionMutex.withLock {
                        repository.setTurnoActivo(intent.nuevoTurno)
                    }
                    val total = repository.observeConteoTurno(intent.nuevoTurno).first()
                    LucyResponse(
                        turnoContexto = intent.nuevoTurno,
                        texto = "Turno cambiado a ${intent.nuevoTurno.nombre} ($total trabajadores en maestro)."
                    )
                }
            }

            is LucyIntent.FiltrarPlanillaVisionEmpresa -> recorrelacionarPlanillaVisionPorEmpresa(
                empresaTexto = intent.empresaTexto,
                empresaExplicita = intent.empresa,
                turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
            )

            is LucyIntent.ResumenTurno -> {
                val turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
                val empresaResuelta = resolverEmpresaParaOperacion(
                    empresaExplicita = intent.empresa,
                    usarAnterior = intent.usarEmpresaAnterior,
                    contexto = contexto,
                    turnoObjetivo = turnoObjetivo
                )
                if (intent.usarEmpresaAnterior && empresaResuelta == null) {
                    LucyResponse(
                        turnoContexto = turnoObjetivo,
                        texto = "No hay una empresa reciente recordada en ${turnoObjetivo.nombre}. Indica el nombre de la empresa.",
                        esErrorRecuperable = true
                    )
                } else {
                    if (intent.empresa != null) {
                        recordarEmpresaSinFallo(turnoObjetivo, intent.empresa)
                    }
                    resolverResumen(
                        turnoObjetivo = turnoObjetivo,
                        empresa = empresaResuelta
                    )
                }
            }

            is LucyIntent.ConsultarEmpresas -> {
                val turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
                if (intent.empresa != null) {
                    recordarEmpresaSinFallo(turnoObjetivo, intent.empresa)
                }
                resolverEmpresas(
                    turnoObjetivo = turnoObjetivo,
                    empresa = intent.empresa
                )
            }

            is LucyIntent.ListarTrabajadores -> {
                val turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
                val empresaResuelta = if (intent.empresa != null || intent.usarEmpresaAnterior) {
                    resolverEmpresaParaOperacion(
                        empresaExplicita = intent.empresa,
                        usarAnterior = intent.usarEmpresaAnterior,
                        contexto = contexto,
                        turnoObjetivo = turnoObjetivo
                    )
                } else null
                if (intent.usarEmpresaAnterior && empresaResuelta == null) {
                    LucyResponse(
                        turnoContexto = turnoObjetivo,
                        texto = "No hay una empresa reciente recordada en ${turnoObjetivo.nombre}. Indica el nombre de la empresa.",
                        esErrorRecuperable = true
                    )
                } else {
                    if (intent.empresa != null) {
                        recordarEmpresaSinFallo(turnoObjetivo, intent.empresa)
                    }
                    resolverListadoTrabajadores(
                        turnoObjetivo = turnoObjetivo,
                        empresa = empresaResuelta
                    )
                }
            }

            is LucyIntent.ConsultarPendientes -> {
                val turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
                val empresaResuelta = resolverEmpresaParaOperacion(
                    empresaExplicita = intent.empresa,
                    usarAnterior = intent.usarEmpresaAnterior,
                    contexto = contexto,
                    turnoObjetivo = turnoObjetivo
                )
                if (intent.usarEmpresaAnterior && empresaResuelta == null) {
                    LucyResponse(
                        turnoContexto = turnoObjetivo,
                        texto = "No hay una empresa reciente recordada en ${turnoObjetivo.nombre}. Indica el nombre de la empresa.",
                        esErrorRecuperable = true
                    )
                } else {
                    if (intent.empresa != null) {
                        recordarEmpresaSinFallo(turnoObjetivo, intent.empresa)
                    }
                    resolverPendientes(
                        turnoObjetivo = turnoObjetivo,
                        empresa = empresaResuelta
                    )
                }
            }

            is LucyIntent.BuscarTrabajador -> {
                val turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
                val empresaResuelta = if (intent.empresa != null || intent.usarEmpresaAnterior) {
                    resolverEmpresaParaOperacion(
                        empresaExplicita = intent.empresa,
                        usarAnterior = intent.usarEmpresaAnterior,
                        contexto = contexto,
                        turnoObjetivo = turnoObjetivo
                    )
                } else null

                if (intent.usarEmpresaAnterior && empresaResuelta == null) {
                    LucyResponse(
                        turnoContexto = turnoObjetivo,
                        texto = "No hay una empresa reciente recordada en ${turnoObjetivo.nombre}.",
                        esErrorRecuperable = true
                    )
                } else {
                    if (intent.empresa != null) {
                        recordarEmpresaSinFallo(turnoObjetivo, intent.empresa)
                    }
                    resolverBusqueda(
                        query = intent.query,
                        turnoObjetivo = turnoObjetivo,
                        empresa = empresaResuelta,
                        usarTrabajadorAnterior = intent.usarTrabajadorAnterior,
                        esMencionPasiva = intent.esMencionPasiva
                    )
                }
            }

            is LucyIntent.ConsultarEstadoTrabajador -> {
                val turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
                val empresaResuelta = if (intent.empresa != null || intent.usarEmpresaAnterior) {
                    resolverEmpresaParaOperacion(
                        empresaExplicita = intent.empresa,
                        usarAnterior = intent.usarEmpresaAnterior,
                        contexto = contexto,
                        turnoObjetivo = turnoObjetivo
                    )
                } else null

                if (intent.usarEmpresaAnterior && empresaResuelta == null) {
                    LucyResponse(
                        turnoContexto = turnoObjetivo,
                        texto = "No hay una empresa reciente recordada en ${turnoObjetivo.nombre}.",
                        esErrorRecuperable = true
                    )
                } else {
                    if (intent.empresa != null) {
                        recordarEmpresaSinFallo(turnoObjetivo, intent.empresa)
                    }
                    resolverEstadoTrabajador(
                        query = intent.query,
                        turnoObjetivo = turnoObjetivo,
                        empresa = empresaResuelta,
                        usarTrabajadorAnterior = intent.usarTrabajadorAnterior,
                        respuestaBreve = intent.respuestaBreve
                    )
                }
            }

            is LucyIntent.SolicitarMarcacion -> {
                val empresaResuelta = resolverEmpresaParaOperacion(
                    empresaExplicita = intent.empresa,
                    usarAnterior = intent.usarEmpresaAnterior,
                    contexto = contexto,
                    turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
                )
                if (intent.usarEmpresaAnterior && empresaResuelta == null) {
                    LucyResponse(
                        turnoContexto = contexto.turnoActivo,
                        texto = "No hay una empresa reciente recordada en ${contexto.turnoActivo.nombre}.",
                        esErrorRecuperable = true
                    )
                } else {
                    prepararConfirmacionMarcacion(
                        query = intent.query,
                        marcarComoSiBajo = intent.marcarComoSiBajo,
                        turnoActivo = contexto.turnoActivo,
                        empresa = empresaResuelta,
                        turnoSolicitado = intent.turnoSolicitado,
                        usarTrabajadorAnterior = intent.usarTrabajadorAnterior
                    )
                }
            }

            is LucyIntent.MarcacionMultiple -> ejecutarMarcacionMultiple(
                intent = intent,
                contexto = contexto,
                origen = origen
            )

            is LucyIntent.MarcarTrabajadorEnContexto -> ejecutarMarcarTrabajadorEnContexto(
                intent = intent,
                turnoActivo = contexto.turnoActivo,
                origen = origen
            )

            is LucyIntent.ResolverDudaPendiente -> ejecutarResolverDudaPendiente(
                intent = intent,
                contexto = contexto,
                origen = origen
            )

            is LucyIntent.ActualizarDatoTrabajador -> ejecutarActualizacionDatoMaestro(
                intent = intent,
                contexto = contexto,
                origen = origen
            )

            is LucyIntent.ConsultarHistorialCambios -> ejecutarConsultarHistorialCambios(
                intent = intent,
                turnoActivo = contexto.turnoActivo
            )

            is LucyIntent.RegistrarCorreccionUsuario -> ejecutarRegistrarCorreccionUsuario(
                intent = intent,
                contexto = contexto,
                origen = origen
            )

            is LucyIntent.ConflictoTurno -> responderConflictoTurno(
                turnoActivo = contexto.turnoActivo,
                turnoSolicitado = intent.turnoSolicitado,
                query = intent.query
            )

            is LucyIntent.ConfigurarPreferenciaConversacion -> ejecutarConfiguracionPreferencia(
                intent = intent,
                turnoContexto = contexto.turnoActivo
            )

            is LucyIntent.GuardarNotaOperativa -> ejecutarGuardarNotaOperativa(
                intent = intent,
                contexto = contexto
            )

            is LucyIntent.ConsultarMemoria -> ejecutarConsultarMemoria(
                intent = intent,
                contexto = contexto
            )

            is LucyIntent.OlvidarMemoria -> ejecutarOlvidarMemoria(
                intent = intent,
                contexto = contexto
            )

            is LucyIntent.NoComprendido -> LucyResponse(
                turnoContexto = contexto.turnoActivo,
                texto = "No entendí la solicitud en ${contexto.turnoActivo.nombre}. Escribe o di \"ayuda\" para ver ejemplos, o indica un apellido, habitación o empresa."
            )
        }
    }

    /**
     * Ejecuta una marcación únicamente después de confirmación explícita del usuario.
     * Garantiza idempotencia y respeto estricto al turno de la confirmación.
     */
    suspend fun confirmarAccionPendiente(
        confirmacion: LucyPendingConfirmation,
        hablarAlFinalizar: Boolean = true
    ): LucyResponse = ejecutarProtegido(confirmacion.turno) {
        actionMutex.withLock {
            val turnoActivoReal = repository.turnoActivo.first()
            if (turnoActivoReal != confirmacion.turno) {
                return@withLock LucyResponse(
                    turnoContexto = turnoActivoReal,
                    texto = "No se ejecutó la marcación: la confirmación pertenece a ${confirmacion.turno.nombre}, pero el turno activo actual es ${turnoActivoReal.nombre}. Cambia a ${confirmacion.turno.nombre} si deseas aplicarla.",
                    esErrorRecuperable = true
                )
            }

            val trabajadoresActuales = repository.observeTrabajadoresConEstado(confirmacion.turno).first()
            val trabajadorActual = trabajadoresActuales.firstOrNull { it.id == confirmacion.trabajador.id }
                ?: return@withLock LucyResponse(
                    turnoContexto = confirmacion.turno,
                    texto = "El trabajador ${confirmacion.trabajador.nombreCompleto} ya no figura en el maestro de ${confirmacion.turno.nombre}.",
                    esErrorRecuperable = true
                )

            val estadoObjetivo = confirmacion.marcarComoSiBajo
            val etiquetaEstado = if (estadoObjetivo) "SÍ BAJÓ" else "NO BAJÓ"

            if (trabajadorActual.siBajo == estadoObjetivo) {
                recordarTrabajadorSinFallo(confirmacion.turno, trabajadorActual.id)
                recordarEmpresaSinFallo(confirmacion.turno, trabajadorActual.empresa)
                return@withLock LucyResponse(
                    turnoContexto = confirmacion.turno,
                    texto = "${trabajadorActual.nombreCompleto} ya figuraba como $etiquetaEstado en ${confirmacion.turno.nombre}. No fue necesario modificar nada.",
                    trabajadoresRelacionados = listOf(trabajadorActual)
                )
            }

            repository.alternarMarcacionSiBajo(trabajadorActual)
            val verificado = repository.observeTrabajadoresConEstado(confirmacion.turno)
                .first()
                .firstOrNull { it.id == trabajadorActual.id }
            if (verificado == null || verificado.siBajo != estadoObjetivo) {
                return@withLock LucyResponse(
                    turnoContexto = confirmacion.turno,
                    texto = "Se intentó actualizar a ${trabajadorActual.nombreCompleto} en ${confirmacion.turno.nombre}, pero no se pudo verificar el estado final $etiquetaEstado en la base local.",
                    esErrorRecuperable = true
                )
            }

            recordarTrabajadorSinFallo(confirmacion.turno, verificado.id)
            recordarEmpresaSinFallo(confirmacion.turno, verificado.empresa)
            val respuestaConfirmada = LucyResponse(
                turnoContexto = confirmacion.turno,
                texto = "Confirmado y verificado en ${confirmacion.turno.nombre}: ${verificado.nombreCompleto} quedó marcado como $etiquetaEstado.",
                trabajadoresRelacionados = listOf(verificado)
            )
            agregarExchangeEnBuffer(
                confirmacion.turno,
                LucyTurnExchange(
                    userText = "Confirmación manual: ${confirmacion.descripcion}",
                    assistantText = respuestaConfirmada.texto,
                    topicSummary = "Marcación confirmada de ${verificado.nombreCompleto} como $etiquetaEstado",
                    isQuestion = false,
                    isMetaConversationQuery = false,
                    referencedWorkerName = verificado.nombreCompleto,
                    referencedCompany = verificado.empresa,
                    actionType = "Confirmación de marcación"
                )
            )
            if (hablarAlFinalizar) {
                hablarRespuestaSinFallo(respuestaConfirmada.texto)
            }
            respuestaConfirmada
        }
    }

    /**
     * Ejecuta una operación de un módulo externo (p. ej. Vision) garantizando que incluso un
     * `java.lang.Error` (como `UnsatisfiedLinkError`) quede contenido en ese módulo y jamás cierre Lucy Core ni la app.
     */
    suspend fun ejecutarLecturaPlanillaAislada(
        imagePath: String,
        turnoContexto: Turno
    ): LucyVisionOutcome {
        return try {
            val resultado = withContext(ioDispatcher) {
                visionPort.extractPlanillaLines(imagePath)
            }
            actualizarSaludDesdeVisionOutcome(resultado)
            resultado
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            val motivo = "${le::class.java.simpleName}: ${le.message ?: "fallo nativo/JNI en módulo Vision"}"
            actualizarSalud(LucyModuleId.VISION, LucyModuleHealth.Unavailable(motivo))
            LucyVisionOutcome.Failed(
                "Lucy Vision no pudo procesar la imagen en $turnoContexto ($motivo). Lucy Core y Voz siguen operativos."
            )
        } catch (e: Exception) {
            val motivo = "${e::class.java.simpleName}: ${e.message ?: "fallo en módulo Vision"}"
            actualizarSalud(LucyModuleId.VISION, LucyModuleHealth.Unavailable(motivo))
            LucyVisionOutcome.Failed(
                "Lucy Vision no pudo procesar la imagen en $turnoContexto ($motivo). Lucy Core y Voz siguen operativos."
            )
        }
    }

    /**
     * Analiza una fotografía de planilla mediante Lucy Vision de forma estrictamente informativa.
     * Solo propone candidatos y explica dudas; jamás modifica trabajadores ni realiza marcaciones.
     */
    suspend fun analizarPlanillaConLucyVision(
        imagePath: String,
        contexto: LucyTurnContext? = null,
        origen: LucyOperationOrigin = LucyOperationOrigin.VISION,
        hablarAlFinalizar: Boolean = true
    ): LucyResponse {
        val contextoResuelto = contexto ?: LucyTurnContext(turnoActivo = repository.turnoActivo.first())
        val turno = contextoResuelto.turnoActivo
        return try {
            val outcome = withContext(ioDispatcher) {
                visionPort.analyzePlanilla(
                    imagePath = imagePath,
                    turnoContexto = turno,
                    empresaSugerida = contextoResuelto.empresaActiva
                )
            }
            actualizarSaludDesdeVisionOutcome(outcome)

            val respuesta = when (outcome) {
                is LucyVisionOutcome.PlanillaAnalyzed -> {
                    synchronized(historyLock) {
                        ultimoAnalisisVisionPorTurno[turno] = outcome.analysis
                    }
                    interpretarAnalisisVision(outcome.analysis)
                }
                is LucyVisionOutcome.LinesExtracted -> {
                    val texto = if (outcome.lines.isEmpty()) {
                        "Lucy Vision (${outcome.providerName}) no encontró líneas legibles en la fotografía."
                    } else {
                        "Lucy Vision leyó ${outcome.lines.size} línea(s) en ${turno.nombre} (requiere revisión humana antes de aplicar cambios):\n" +
                            outcome.lines.take(8).joinToString("\n") { "• $it" }
                    }
                    LucyResponse(
                        turnoContexto = turno,
                        texto = texto,
                        modoEjecucion = LucyExecutionMode.HYBRID_CLOUD_OPTIONAL
                    )
                }
                is LucyVisionOutcome.NotAvailable -> LucyResponse(
                    turnoContexto = turno,
                    texto = outcome.reason,
                    esErrorRecuperable = true
                )
                is LucyVisionOutcome.Failed -> LucyResponse(
                    turnoContexto = turno,
                    texto = outcome.reason,
                    esErrorRecuperable = true
                )
            }

            agregarExchangeEnBuffer(
                turno,
                LucyTurnExchange(
                    userText = "Análisis visual de fotografía de planilla",
                    assistantText = respuesta.texto,
                    topicSummary = "Lectura de planilla por fotografía en ${turno.nombre}",
                    isQuestion = false,
                    isMetaConversationQuery = false,
                    referencedWorkerName = respuesta.trabajadoresRelacionados.firstOrNull()?.nombreCompleto,
                    referencedCompany = respuesta.visionAnalysis?.empresaDocumentoInferida ?: contextoResuelto.empresaActiva,
                    actionType = "Lectura de planilla (Vision)"
                )
            )
            registrarMemoriaSinFallo(turno, "Análisis visual de planilla", respuesta.texto)
            if (hablarAlFinalizar && (origen == LucyOperationOrigin.VOICE || respuesta.texto.isNotBlank())) {
                hablarRespuestaSinFallo(respuesta.texto)
            }
            respuesta
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            val motivo = "${le::class.java.simpleName}: ${le.message ?: "fallo nativo en módulo Vision"}"
            actualizarSalud(LucyModuleId.VISION, LucyModuleHealth.Unavailable(motivo))
            LucyResponse(
                turnoContexto = turno,
                texto = "Lucy Vision encontró un error técnico controlado ($motivo). Los datos reales siguen intactos.",
                esErrorRecuperable = true
            )
        } catch (e: Exception) {
            val motivo = "${e::class.java.simpleName}: ${e.message ?: "error en módulo Vision"}"
            actualizarSalud(LucyModuleId.VISION, LucyModuleHealth.Degraded(motivo))
            LucyResponse(
                turnoContexto = turno,
                texto = "No se pudo completar el análisis visual ($motivo). Los datos reales siguen intactos.",
                esErrorRecuperable = true
            )
        }
    }

    private fun actualizarSaludDesdeVisionOutcome(outcome: LucyVisionOutcome) {
        when (outcome) {
            is LucyVisionOutcome.LinesExtracted ->
                actualizarSalud(LucyModuleId.VISION, LucyModuleHealth.Ready)
            is LucyVisionOutcome.PlanillaAnalyzed -> {
                val estado = outcome.analysis.estadoDocumento
                val health = when (estado) {
                    LucyVisionDocumentStatus.LEGIBLE,
                    LucyVisionDocumentStatus.PARCIALMENTE_LEGIBLE,
                    LucyVisionDocumentStatus.ILEGIBLE -> LucyModuleHealth.Ready
                    LucyVisionDocumentStatus.SIN_CONEXION,
                    LucyVisionDocumentStatus.TIMEOUT,
                    LucyVisionDocumentStatus.CUOTA_O_FACTURACION,
                    LucyVisionDocumentStatus.RESPUESTA_INVALIDA ->
                        LucyModuleHealth.Degraded(outcome.analysis.resumenGeneral)
                    LucyVisionDocumentStatus.NO_CONFIGURADO,
                    LucyVisionDocumentStatus.ERROR_APP_CHECK,
                    LucyVisionDocumentStatus.ERROR_PROVEEDOR ->
                        LucyModuleHealth.Unavailable(outcome.analysis.resumenGeneral)
                }
                actualizarSalud(LucyModuleId.VISION, health)
            }
            is LucyVisionOutcome.NotAvailable ->
                actualizarSalud(LucyModuleId.VISION, LucyModuleHealth.Unavailable(outcome.reason))
            is LucyVisionOutcome.Failed ->
                actualizarSalud(LucyModuleId.VISION, LucyModuleHealth.Degraded(outcome.reason))
        }
    }

    private fun interpretarAnalisisVision(
        analysis: LucyVisionPlanillaAnalysis,
        filtroEmpresa: EmpresaOficial? = analysis.empresaFiltroAplicada
    ): LucyResponse {
        val relacionados = analysis.trabajadoresDetectados
            .mapNotNull { it.coincidenciaProbable.candidatoPrincipal?.trabajador }
            .distinctBy { it.id }

        val texto = buildString {
            append(analysis.resumenGeneral)
            if (analysis.tipoEscrituraDocumento != LucyVisionTextOriginType.INDETERMINADO) {
                append("\nEscritura detectada: ${analysis.tipoEscrituraDocumento.etiqueta}.")
            }
            if (filtroEmpresa != null) {
                val coincidentes = analysis.trabajadoresDetectados.filter {
                    it.coincidenciaProbable.estado == LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA
                }
                val ambiguas = analysis.trabajadoresDetectados.filter {
                    it.coincidenciaProbable.estado == LucyVisionMatchStatus.AMBIGUA_MULTIPLE
                }
                val sinCoincidencia = analysis.trabajadoresDetectados.filter {
                    it.coincidenciaProbable.estado == LucyVisionMatchStatus.SIN_COINCIDENCIA ||
                        it.coincidenciaProbable.estado == LucyVisionMatchStatus.NOMBRE_INCOMPLETO
                }
                append("\nFiltro estricto aplicado: solo personal de ${filtroEmpresa.nombre} en ${analysis.turnoContexto.nombre}.")
                append("\n• Coincidencias en ${filtroEmpresa.nombre}: ${coincidentes.size}")
                if (coincidentes.isNotEmpty()) {
                    coincidentes.take(15).forEach { det ->
                        val cand = det.coincidenciaProbable.candidatoPrincipal?.trabajador
                        append("\n  - ${det.nombreDetectado} → ${cand?.nombreCompleto ?: "—"} (${cand?.ubicacionTexto ?: "sin ubicación"})")
                    }
                }
                append("\n• Ambiguas en ${filtroEmpresa.nombre}: ${ambiguas.size}")
                if (ambiguas.isNotEmpty()) {
                    ambiguas.take(10).forEach { det ->
                        val opts = det.coincidenciaProbable.candidatos.take(3).joinToString(" / ") { it.trabajador.nombreCompleto }
                        append("\n  - ${det.nombreDetectado} → opciones en ${filtroEmpresa.nombre}: $opts")
                    }
                }
                append("\n• Sin coincidencia en ${filtroEmpresa.nombre}: ${sinCoincidencia.size}")
                if (sinCoincidencia.isNotEmpty()) {
                    sinCoincidencia.take(10).forEach { det ->
                        append("\n  - ${det.nombreDetectado} (${det.coincidenciaProbable.estado.etiqueta})")
                    }
                }
            }
            val muestra = analysis.trabajadoresDetectados.take(25)
            if (muestra.isNotEmpty()) {
                append("\nResultados estructurados (APELLIDOS | NOMBRE | HABITACIÓN | PABELLÓN):")
                muestra.forEach { det ->
                    val emp = det.empresaProbable?.nombre ?: "Empresa sin determinar"
                    val matchTxt = when (det.coincidenciaProbable.estado) {
                        LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA ->
                            "candidato: ${det.coincidenciaProbable.candidatoPrincipal?.trabajador?.nombreCompleto}"
                        LucyVisionMatchStatus.AMBIGUA_MULTIPLE ->
                            "ambiguo (${det.coincidenciaProbable.candidatos.size} candidatos)"
                        LucyVisionMatchStatus.NOMBRE_INCOMPLETO -> "nombre incompleto"
                        LucyVisionMatchStatus.SIN_COINCIDENCIA -> "sin coincidencia en maestro"
                    }
                    val origenTxt = if (det.tipoEscritura != LucyVisionTextOriginType.INDETERMINADO) {
                        "${det.tipoEscritura.etiqueta} · "
                    } else ""
                    val dudosoTxt = if (det.esDudosoOIlegible) " · DUDOSO" else ""
                    append("\n• ${det.formatoTablaRevision} [$origenTxt$emp · ${det.estadoMarcacion.etiqueta} · $matchTxt$dudosoTxt]")
                }
                if (analysis.trabajadoresDetectados.size > muestra.size) {
                    append("\n… y ${analysis.trabajadoresDetectados.size - muestra.size} fila(s) más en la tarjeta inferior.")
                }
            }
            if (analysis.dudasGlobales.isNotEmpty()) {
                append("\nDudas: ${analysis.dudasGlobales.joinToString("; ")}")
            }
        }

        return LucyResponse(
            turnoContexto = analysis.turnoContexto,
            texto = texto,
            trabajadoresRelacionados = relacionados,
            visionAnalysis = analysis,
            esErrorRecuperable = !analysis.esDocumentoOperativoValido,
            modoEjecucion = LucyExecutionMode.HYBRID_CLOUD_OPTIONAL
        )
    }

    private suspend fun recorrelacionarPlanillaVisionPorEmpresa(
        empresaTexto: String,
        empresaExplicita: EmpresaOficial?,
        turnoObjetivo: Turno
    ): LucyResponse {
        val analisisActual = synchronized(historyLock) {
            ultimoAnalisisVisionPorTurno[turnoObjetivo]
        } ?: return LucyResponse(
            turnoContexto = turnoObjetivo,
            texto = "No hay ninguna fotografía de planilla analizada activa en ${turnoObjetivo.nombre}. Analiza primero una foto para poder filtrarla por empresa.",
            esErrorRecuperable = true
        )

        val parsed = analisisActual.parsedSheet ?: return LucyResponse(
            turnoContexto = turnoObjetivo,
            texto = "La planilla actual en ${turnoObjetivo.nombre} no conserva las filas crudas para refiltrar por empresa. Vuelve a analizar la fotografía.",
            esErrorRecuperable = true
        )

        val empresaResuelta = empresaExplicita ?: resolverEmpresaVisionEstricta(empresaTexto)
        if (empresaResuelta == null) {
            val nombreMostrado = empresaTexto.trim().ifBlank { "indicada" }
            val catalogo = EmpresaOficial.todas.joinToString(", ") { it.nombre }
            return LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "La empresa \"$nombreMostrado\" no existe en el catálogo oficial ni en el maestro de ${turnoObjetivo.nombre}. No se buscó en otras empresas ni se marcó ningún trabajador. Empresas válidas: $catalogo.",
                esErrorRecuperable = true
            )
        }

        val trabajadoresTurno = repository.observeTrabajadoresConEstado(turnoObjetivo).first()
        val correcciones = obtenerCorreccionesAprendidasSeguras(turnoObjetivo)
        val reanalizado = LucyVisionMatcher.correlacionarConMaestro(
            parsed = parsed,
            turnoContexto = turnoObjetivo,
            empresaSugerida = empresaResuelta,
            trabajadoresTurno = trabajadoresTurno,
            motorUtilizado = analisisActual.motorUtilizado,
            correccionesAprendidas = correcciones,
            strictCompanyFilter = true
        )
        synchronized(historyLock) {
            ultimoAnalisisVisionPorTurno[turnoObjetivo] = reanalizado
        }
        recordarEmpresaSinFallo(turnoObjetivo, empresaResuelta)
        return interpretarAnalisisVision(reanalizado, filtroEmpresa = empresaResuelta)
    }

    private fun resolverEmpresaVisionEstricta(raw: String?): EmpresaOficial? {
        val clean = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return EmpresaOficial.buscarExactaPorNombre(clean)
            ?: LucyVisionMatcher.resolverEmpresaLiteral(clean)
    }

    fun shutdown() {
        coordinatorScope.cancel()
    }

    private fun responderConversacionGeneral(
        intent: LucyIntent.ConversacionGeneral,
        turnoActivo: Turno
    ): LucyResponse {
        val texto = when (intent.subtipo) {
            LucyConversationalSubtipo.SALUDO ->
                "¡Hola! Estoy lista para ayudarte en ${turnoActivo.nombre}. ¿Qué necesitas revisar o consultar?"
            LucyConversationalSubtipo.DESPEDIDA ->
                "Hasta luego. Aquí estaré en ${turnoActivo.nombre} cuando necesites revisar trabajadores, empresas o planillas."
            LucyConversationalSubtipo.AGRADECIMIENTO ->
                "Con gusto. Dime si necesitas revisar otro trabajador, empresa o planilla en ${turnoActivo.nombre}."
            LucyConversationalSubtipo.ESTADO_ASISTENTE ->
                "Todo bien por aquí, operando en ${turnoActivo.nombre}. ¿En qué te ayudo?"
            LucyConversationalSubtipo.IDENTIDAD_ASISTENTE ->
                "Soy Lucy, tu asistente de Lavandería Control en ${turnoActivo.nombre}. Puedo consultar trabajadores, empresas, recuerdos del turno y analizar fotografías de planillas para revisión humana."
            LucyConversationalSubtipo.CHARLA_GENERAL ->
                "Entendido. Sigo atenta en ${turnoActivo.nombre} por si quieres hacer otra consulta."
        }
        return LucyResponse(turnoContexto = turnoActivo, texto = texto)
    }

    private suspend fun responderConversacionReciente(
        intent: LucyIntent.ConsultarConversacionReciente,
        turnoActivo: Turno
    ): LucyResponse {
        val snapshot = synchronized(historyLock) {
            historialConversacionalPorTurno[turnoActivo].orEmpty().toList()
        }
        val reales = snapshot.filter { !it.isMetaConversationQuery }

        if (reales.isEmpty()) {
            val notas = consultarNotasSeguras(turnoActivo, null, null)
            val texto = if (notas.isNotEmpty()) {
                val muestra = notas.take(3).joinToString("; ") { "\"${it.texto}\"" }
                "No tengo preguntas o mensajes anteriores en esta conversación de ${turnoActivo.nombre}, pero sí hay notas guardadas en memoria: $muestra."
            } else {
                "Todavía no hemos conversado sobre nada previo en esta sesión de ${turnoActivo.nombre}. Puedes consultarme por un trabajador, una empresa, un resumen o adjuntar una planilla."
            }
            return LucyResponse(turnoContexto = turnoActivo, texto = texto)
        }

        if (intent.pideUltimaPregunta) {
            val ultimaPregunta = reales.lastOrNull { it.isQuestion } ?: reales.last()
            val etiqueta = if (ultimaPregunta.isQuestion) "Tu última pregunta" else "Lo último que me indicaste"
            val texto = "$etiqueta en ${turnoActivo.nombre} fue: \"${ultimaPregunta.userText}\". " +
                "Te respondí: ${resumenCorto(ultimaPregunta.assistantText)}"
            return LucyResponse(turnoContexto = turnoActivo, texto = texto)
        }

        if (intent.pideContinuacion) {
            val recientes = reales.takeLast(3)
            val texto = if (recientes.size == 1) {
                val ex = recientes.first()
                "Lo último en ${turnoActivo.nombre} fue \"${ex.userText}\" y te respondí: ${resumenCorto(ex.assistantText)} " +
                    "No hubo más acciones después; dime si quieres continuar con ese tema."
            } else {
                buildString {
                    append("Esto fue lo que ocurrió en los últimos pasos en ${turnoActivo.nombre}:")
                    recientes.forEachIndexed { idx, ex ->
                        append("\n${idx + 1}. Tú: \"${ex.userText}\" → ${resumenCorto(ex.assistantText)}")
                    }
                }
            }
            return LucyResponse(turnoContexto = turnoActivo, texto = texto)
        }

        if (intent.pideResumenCharla) {
            val recientes = reales.takeLast(4)
            val texto = buildString {
                append("En ${turnoActivo.nombre} estábamos conversando sobre:")
                recientes.forEach { ex ->
                    append("\n• ${ex.topicSummary} (\"${ex.userText}\")")
                }
            }
            return LucyResponse(turnoContexto = turnoActivo, texto = texto)
        }

        // "¿Qué te dije hace un rato?"
        val recientes = reales.takeLast(2)
        val texto = if (recientes.size == 1) {
            val ex = recientes.first()
            "Hace un rato en ${turnoActivo.nombre} me dijiste: \"${ex.userText}\". " +
                "Sobre eso te respondí: ${resumenCorto(ex.assistantText)}"
        } else {
            buildString {
                append("Hace un rato en ${turnoActivo.nombre} me comentaste:")
                recientes.forEach { ex ->
                    append("\n• \"${ex.userText}\" → ${resumenCorto(ex.assistantText)}")
                }
            }
        }
        return LucyResponse(turnoContexto = turnoActivo, texto = texto)
    }

    private suspend fun responderContextoActual(contexto: LucyTurnContext): LucyResponse {
        val turno = contexto.turnoActivo
        val snapshot = synchronized(historyLock) {
            historialConversacionalPorTurno[turno].orEmpty().toList()
        }
        val reales = snapshot.filter { !it.isMetaConversationQuery }
        val ultimoReal = reales.lastOrNull()
        val visionReciente = synchronized(historyLock) { ultimoAnalisisVisionPorTurno[turno] }

        val empresaReciente = contexto.empresaActiva
            ?: ultimoReal?.referencedCompany
            ?: resolverUltimaEmpresaSegura(turno)
        val workerId = resolverUltimoTrabajadorSeguro(turno)
        val trabajadorReciente = if (workerId != null) {
            repository.observeTrabajadoresConEstado(turno).first().firstOrNull { it.id == workerId }
        } else null

        if (ultimoReal == null && empresaReciente == null && trabajadorReciente == null && visionReciente == null) {
            return LucyResponse(
                turnoContexto = turno,
                texto = "En ${turno.nombre} aún no estábamos revisando ningún trabajador, empresa ni planilla en esta sesión. Dime qué quieres revisar."
            )
        }

        val detalles = mutableListOf<String>()
        ultimoReal?.let {
            detalles.add("Último tema: ${it.topicSummary} (\"${it.userText}\")")
        }
        trabajadorReciente?.let {
            val estado = if (it.siBajo) "SÍ BAJÓ" else "NO BAJÓ"
            detalles.add("Trabajador reciente: ${it.nombreCompleto} (${it.empresa.nombre}, ${it.ubicacionTexto}, $estado)")
        }
        empresaReciente?.let { emp ->
            if (trabajadorReciente?.empresa != emp) {
                detalles.add("Empresa en contexto: ${emp.nombre}")
            }
        }
        visionReciente?.let { vis ->
            detalles.add("Planilla revisada por foto: ${vis.trabajadoresDetectados.size} fila(s) (${vis.estadoDocumento.etiqueta})")
        }

        val texto = buildString {
            append("En ${turno.nombre} estábamos revisando:")
            detalles.forEach { d -> append("\n• $d") }
        }
        return LucyResponse(
            turnoContexto = turno,
            texto = texto,
            trabajadoresRelacionados = listOfNotNull(trabajadorReciente)
        )
    }

    private suspend fun responderDetalleTrabajadorAnterior(
        intent: LucyIntent.ConsultarDetalleTrabajadorAnterior,
        turnoActivo: Turno
    ): LucyResponse {
        val workerId = resolverUltimoTrabajadorSeguro(turnoActivo)
            ?: return LucyResponse(
                turnoContexto = turnoActivo,
                texto = "¿De qué trabajador me hablas? Dime primero su nombre o apellido en ${turnoActivo.nombre}."
            )
        val trabajador = repository.observeTrabajadoresConEstado(turnoActivo).first()
            .firstOrNull { it.id == workerId }
            ?: return LucyResponse(
                turnoContexto = turnoActivo,
                texto = "El trabajador consultado anteriormente ya no figura en ${turnoActivo.nombre}.",
                esErrorRecuperable = true
            )

        val texto = when (intent.campo) {
            LucyDetalleTrabajadorCampo.HABITACION ->
                "Habitación ${trabajador.habitacionMayusculas.ifBlank { "sin dato" }}, pabellón ${trabajador.pabellonMayusculas.ifBlank { "sin dato" }}."
            LucyDetalleTrabajadorCampo.PABELLON ->
                "Pabellón ${trabajador.pabellonMayusculas.ifBlank { "sin dato" }}, habitación ${trabajador.habitacionMayusculas.ifBlank { "sin dato" }}."
            LucyDetalleTrabajadorCampo.EMPRESA ->
                "Es de ${trabajador.empresa.nombre}."
            LucyDetalleTrabajadorCampo.EMPRESA_VERIFICACION -> {
                val empresaPreguntada = intent.empresaConsulta ?: resolverUltimaEmpresaSegura(turnoActivo)
                when {
                    empresaPreguntada == null -> "Es de ${trabajador.empresa.nombre}."
                    trabajador.empresa == empresaPreguntada -> "Sí."
                    else -> "No, es de ${trabajador.empresa.nombre}."
                }
            }
            LucyDetalleTrabajadorCampo.UBICACION_COMPLETA ->
                "${trabajador.nombreCompleto}: ${trabajador.ubicacionTexto} · ${trabajador.empresa.nombre}."
        }
        return LucyResponse(
            turnoContexto = turnoActivo,
            texto = texto,
            trabajadoresRelacionados = listOf(trabajador)
        )
    }

    private fun responderAclaracionAmbigua(
        intent: LucyIntent.AclaracionAmbigua,
        turnoActivo: Turno
    ): LucyResponse {
        val texto = when {
            intent.motivo == "no_eliminar_trabajadores" ->
                "Desde Lucy no puedo eliminar trabajadores ni registros del maestro. Para editar o quitar personas usa la pestaña Trabajadores."
            intent.esPregunta ->
                "No estoy segura de cómo ayudarte con esa pregunta en ${turnoActivo.nombre}. ¿Quieres buscar a un trabajador por nombre o habitación, consultar una empresa, ver qué conversamos recién o analizar una planilla?"
            else ->
                "No entendí bien \"${intent.textoOriginal}\" en ${turnoActivo.nombre}. ¿Podrías aclararme si buscas un trabajador, quieres ver una empresa/resumen o recordar algo de nuestra charla?"
        }
        return LucyResponse(
            turnoContexto = turnoActivo,
            texto = texto
        )
    }

    private fun registrarIntercambioConversacional(
        turno: Turno,
        entrada: String,
        intent: LucyIntent,
        respuesta: LucyResponse,
        contexto: LucyTurnContext
    ) {
        val limpio = entrada.trim()
        if (limpio.isBlank()) return

        val esMeta = intent is LucyIntent.ConsultarConversacionReciente ||
            intent is LucyIntent.RecuperarContextoActual
        val esPregunta = limpio.contains('?') || limpio.contains('¿') ||
            intent is LucyIntent.ConsultarEstadoTrabajador ||
            intent is LucyIntent.ConsultarPendientes ||
            intent is LucyIntent.ResumenTurno ||
            intent is LucyIntent.ConsultarEmpresas ||
            intent is LucyIntent.ConsultarMemoria ||
            intent is LucyIntent.ConsultarDetalleTrabajadorAnterior ||
            (intent is LucyIntent.AclaracionAmbigua && intent.esPregunta)

        val accion = when (intent) {
            is LucyIntent.ConversacionGeneral -> "Conversación"
            is LucyIntent.ConsultarConversacionReciente -> "Consulta de historial reciente"
            is LucyIntent.RecuperarContextoActual -> "Recuperación de contexto"
            is LucyIntent.ConsultarDetalleTrabajadorAnterior -> "Seguimiento de trabajador"
            is LucyIntent.AclaracionAmbigua -> "Solicitud de aclaración"
            is LucyIntent.CambiarTurno -> "Cambio de turno"
            is LucyIntent.ResumenTurno -> "Resumen de turno/empresa"
            is LucyIntent.ConsultarEmpresas -> "Consulta de empresas"
            is LucyIntent.ListarTrabajadores -> "Listado de trabajadores"
            is LucyIntent.ConsultarPendientes -> "Consulta de pendientes"
            is LucyIntent.BuscarTrabajador -> "Búsqueda de trabajador"
            is LucyIntent.ConsultarEstadoTrabajador -> "Consulta de estado"
            is LucyIntent.SolicitarMarcacion -> "Solicitud de marcación"
            is LucyIntent.MarcacionMultiple -> "Marcación múltiple (SÍ SUBIÓ)"
            is LucyIntent.MarcarTrabajadorEnContexto -> "Marcación directa en contexto"
            is LucyIntent.ResolverDudaPendiente -> "Resolución de duda pendiente"
            is LucyIntent.ActualizarDatoTrabajador -> "Actualización de dato maestro"
            is LucyIntent.ConsultarHistorialCambios -> "Consulta de historial de cambios"
            is LucyIntent.RegistrarCorreccionUsuario -> "Aprendizaje de corrección"
            is LucyIntent.ConflictoTurno -> "Conflicto de turno"
            is LucyIntent.ConfigurarPreferenciaConversacion -> "Preferencia de conversación"
            is LucyIntent.GuardarNotaOperativa -> "Guardar nota operativa"
            is LucyIntent.ConsultarMemoria -> "Consulta de memoria"
            is LucyIntent.OlvidarMemoria -> "Olvidar recuerdo"
            is LucyIntent.FiltrarPlanillaVisionEmpresa -> "Filtro de planilla por empresa (Vision)"
            is LucyIntent.Ayuda -> "Ayuda"
            is LucyIntent.NoComprendido -> "Entrada no comprendida"
        }

        val workerRef = respuesta.trabajadoresRelacionados.firstOrNull()
        val empRef = when (intent) {
            is LucyIntent.ResumenTurno -> intent.empresa ?: contexto.empresaActiva
            is LucyIntent.ConsultarEmpresas -> intent.empresa ?: contexto.empresaActiva
            is LucyIntent.ListarTrabajadores -> intent.empresa ?: contexto.empresaActiva
            is LucyIntent.ConsultarPendientes -> intent.empresa ?: contexto.empresaActiva
            is LucyIntent.BuscarTrabajador -> intent.empresa ?: workerRef?.empresa ?: contexto.empresaActiva
            is LucyIntent.ConsultarEstadoTrabajador -> intent.empresa ?: workerRef?.empresa ?: contexto.empresaActiva
            is LucyIntent.SolicitarMarcacion -> intent.empresa ?: workerRef?.empresa ?: contexto.empresaActiva
            is LucyIntent.FiltrarPlanillaVisionEmpresa ->
                respuesta.visionAnalysis?.empresaFiltroAplicada ?: intent.empresa ?: contexto.empresaActiva
            is LucyIntent.MarcacionMultiple -> intent.empresa
                ?: synchronized(historyLock) { batchMarkingContextPorTurno[turno]?.empresa }
                ?: workerRef?.empresa
                ?: contexto.empresaActiva
            else -> workerRef?.empresa ?: contexto.empresaActiva
        }

        val topic = buildString {
            append(accion)
            workerRef?.let { append(" (${it.nombreCompleto})") }
            if (workerRef == null && empRef != null) {
                append(" (${empRef.nombre})")
            }
        }

        val exchange = LucyTurnExchange(
            userText = limpio,
            assistantText = respuesta.texto,
            topicSummary = topic,
            isQuestion = esPregunta,
            isMetaConversationQuery = esMeta,
            referencedWorkerName = workerRef?.nombreCompleto,
            referencedCompany = empRef,
            actionType = accion
        )

        agregarExchangeEnBuffer(turno, exchange)
        if (intent is LucyIntent.CambiarTurno && contexto.turnoActivo != turno) {
            agregarExchangeEnBuffer(contexto.turnoActivo, exchange)
        }
    }

    private fun agregarExchangeEnBuffer(turno: Turno, exchange: LucyTurnExchange) {
        synchronized(historyLock) {
            val deque = historialConversacionalPorTurno.getOrPut(turno) { ArrayDeque() }
            deque.addLast(exchange)
            while (deque.size > MAX_EXCHANGES_POR_TURNO) {
                deque.removeFirst()
            }
        }
    }

    private fun resumenCorto(texto: String): String {
        val singleLine = texto.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= 160) singleLine else singleLine.take(157).trimEnd() + "…"
    }

    private data class LucyTurnExchange(
        val userText: String,
        val assistantText: String,
        val topicSummary: String,
        val isQuestion: Boolean,
        val isMetaConversationQuery: Boolean,
        val referencedWorkerName: String?,
        val referencedCompany: EmpresaOficial?,
        val actionType: String
    )

    companion object {
        private const val MAX_EXCHANGES_POR_TURNO = 30
    }

    private data class LucyBatchContext(
        val empresa: EmpresaOficial?,
        val actualizadoEnMs: Long = System.currentTimeMillis()
    )

    private enum class LucyDoubtType {
        AMBIGUO,
        OTRA_EMPRESA,
        NO_ENCONTRADO
    }

    private data class LucyPendingDoubt(
        val nombreSolicitado: String,
        val tipo: LucyDoubtType,
        val empresaObjetivo: EmpresaOficial?,
        val candidatos: List<TrabajadorConEstado> = emptyList(),
        val empresasReales: List<EmpresaOficial> = emptyList()
    )

    private data class LucyCorrectionContext(
        val textoErroneo: String,
        val tipo: LucyTipoCorreccionAprendida,
        val empresaObjetivo: EmpresaOficial? = null
    )

    private suspend fun responderAyuda(turnoActivo: Turno): LucyResponse {
        val prefs = obtenerPreferenciasSeguras()
        val texto = if (prefs.estilo == LucyEstiloRespuesta.CONCISA) {
            "Lucy (${turnoActivo.nombre}): usa \"resumen\", \"buscar <nombre/hab>\", \"marca a <nombre>\", \"estos subieron de <empresa>: ...\", \"recuerda que...\"."
        } else {
            buildString {
                appendLine("Soy Lucy en ${turnoActivo.nombre}. Puedo ayudarte con:")
                appendLine("• Resúmenes y empresas: \"resumen\", \"consultar empresas\" o \"resumen Komatsu\"")
                appendLine("• Trabajadores y búsqueda: \"ver trabajadores\", \"buscar Pérez\", \"hab 108\" o \"¿bajó Rojas?\"")
                appendLine("• Pendientes: \"quiénes faltan de Finning\"")
                appendLine("• Marcación individual: \"marca a Pérez González\" o \"desmarca a Rojas\"")
                appendLine("• Marcación múltiple: \"estos subieron de Komatsu: Sebastián Morales, Pedro González...\"")
                appendLine("• Planillas por foto: adjunta una fotografía y toca Analizar planilla")
                appendLine("• Memoria y preferencias: \"recuerda que...\", \"qué te había dicho sobre...\", \"olvida ese recuerdo\"")
                append("• Turno: \"cambia a Turno B\" o \"cambia a Turno A\"")
            }
        }
        return LucyResponse(turnoContexto = turnoActivo, texto = texto)
    }

    private suspend fun resolverEmpresas(
        turnoObjetivo: Turno,
        empresa: EmpresaOficial?
    ): LucyResponse {
        val todos = repository.observeTrabajadoresConEstado(turnoObjetivo).first()
        if (empresa != null) {
            val grupo = todos.filter { it.empresa == empresa }
            val siBajo = grupo.count { it.siBajo }
            val noBajo = grupo.size - siBajo
            val texto = if (grupo.isEmpty()) {
                "${empresa.nombre} no tiene trabajadores registrados en ${turnoObjetivo.nombre}."
            } else {
                "${empresa.nombre} en ${turnoObjetivo.nombre}: ${grupo.size} trabajadores ($siBajo SÍ BAJÓ · $noBajo pendientes)."
            }
            return LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = texto,
                trabajadoresRelacionados = grupo.take(6)
            )
        }

        val lineas = EmpresaOficial.todas.map { emp ->
            val grupo = todos.filter { it.empresa == emp }
            val si = grupo.count { it.siBajo }
            "• ${emp.nombre}: $si/${grupo.size} bajaron"
        }
        val texto = buildString {
            append("Empresas oficiales en ${turnoObjetivo.nombre} (${todos.size} trabajadores en total):")
            lineas.forEach { append("\n$it") }
        }
        return LucyResponse(
            turnoContexto = turnoObjetivo,
            texto = texto
        )
    }

    private suspend fun resolverListadoTrabajadores(
        turnoObjetivo: Turno,
        empresa: EmpresaOficial?
    ): LucyResponse {
        val todos = repository.observeTrabajadoresConEstado(turnoObjetivo).first()
        val filtrados = (if (empresa != null) todos.filter { it.empresa == empresa } else todos)
            .sortedWith(
                compareBy(
                    { it.searchIndex.normPrimerApellido },
                    { it.searchIndex.normSegundoApellido },
                    { it.searchIndex.normNombre }
                )
            )
        val ambito = empresa?.let { "${it.nombre} en ${turnoObjetivo.nombre}" } ?: turnoObjetivo.nombre
        if (filtrados.isEmpty()) {
            return LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "No hay trabajadores registrados en $ambito."
            )
        }

        val muestra = filtrados.take(10)
        val extra = filtrados.size - muestra.size
        val texto = buildString {
            append("Trabajadores en $ambito (${filtrados.size}):")
            muestra.forEach { t ->
                val estado = if (t.siBajo) "SÍ BAJÓ" else "NO BAJÓ"
                append("\n• ${t.nombreCompleto} · ${t.ubicacionTexto} · ${t.empresa.nombre} [$estado]")
            }
            if (extra > 0) {
                append("\n… y $extra más. Indica un apellido o habitación para filtrar.")
            }
        }
        return LucyResponse(
            turnoContexto = turnoObjetivo,
            texto = texto,
            trabajadoresRelacionados = muestra
        )
    }

    private suspend fun resolverResumen(
        turnoObjetivo: Turno,
        empresa: EmpresaOficial?
    ): LucyResponse {
        val todos = repository.observeTrabajadoresConEstado(turnoObjetivo).first()
        val filtrados = if (empresa != null) todos.filter { it.empresa == empresa } else todos
        val total = filtrados.size
        val siBajo = filtrados.count { it.siBajo }
        val noBajo = total - siBajo

        if (total == 0) {
            val ambito = empresa?.let { "${it.nombre} en ${turnoObjetivo.nombre}" } ?: turnoObjetivo.nombre
            return LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "No hay trabajadores registrados para $ambito."
            )
        }

        val prefs = obtenerPreferenciasSeguras()
        val texto = if (empresa != null) {
            "${empresa.nombre} (${turnoObjetivo.nombre}): $siBajo SÍ BAJÓ · $noBajo NO BAJÓ · $total en maestro."
        } else if (prefs.estilo == LucyEstiloRespuesta.CONCISA) {
            "${turnoObjetivo.nombre}: $siBajo/$total bajaron ($noBajo pendientes)."
        } else {
            val detalleEmpresas = EmpresaOficial.todas.mapNotNull { emp ->
                val grupo = todos.filter { it.empresa == emp }
                if (grupo.isEmpty()) null
                else "${emp.nombre}: ${grupo.count { it.siBajo }}/${grupo.size}"
            }.joinToString(" | ")
            buildString {
                append("${turnoObjetivo.nombre}: $siBajo SÍ BAJÓ · $noBajo NO BAJÓ · $total en maestro.")
                if (detalleEmpresas.isNotBlank()) {
                    append("\nPor empresa: $detalleEmpresas")
                }
            }
        }

        return LucyResponse(
            turnoContexto = turnoObjetivo,
            texto = texto
        )
    }

    private suspend fun resolverPendientes(
        turnoObjetivo: Turno,
        empresa: EmpresaOficial?
    ): LucyResponse {
        val todos = repository.observeTrabajadoresConEstado(turnoObjetivo).first()
        val pendientes = todos.filter { t ->
            !t.siBajo && (empresa == null || t.empresa == empresa)
        }
        val ambito = empresa?.let { "${it.nombre} en ${turnoObjetivo.nombre}" } ?: turnoObjetivo.nombre

        if (pendientes.isEmpty()) {
            return LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "Todos los trabajadores registrados en $ambito ya están marcados como SÍ BAJÓ (o no hay registros)."
            )
        }

        val muestra = pendientes.take(8)
        val extra = pendientes.size - muestra.size
        val texto = buildString {
            append("Faltan ${pendientes.size} trabajador(es) por bajar en $ambito:")
            muestra.forEach { t ->
                append("\n• ${t.nombreCompleto} (${t.ubicacionTexto} · ${t.empresa.nombre})")
            }
            if (extra > 0) {
                append("\n… y $extra más.")
            }
        }

        return LucyResponse(
            turnoContexto = turnoObjetivo,
            texto = texto,
            trabajadoresRelacionados = muestra
        )
    }

    private suspend fun resolverBusqueda(
        query: String,
        turnoObjetivo: Turno,
        empresa: EmpresaOficial?,
        usarTrabajadorAnterior: Boolean = false,
        esMencionPasiva: Boolean = false
    ): LucyResponse = withContext(defaultDispatcher) {
        val baseTotal = repository.observeTrabajadoresConEstado(turnoObjetivo).first()
        if (usarTrabajadorAnterior && query.isBlank()) {
            val idReciente = resolverUltimoTrabajadorSeguro(turnoObjetivo)
                ?: return@withContext LucyResponse(
                    turnoContexto = turnoObjetivo,
                    texto = "No hay un trabajador reciente recordado en ${turnoObjetivo.nombre}.",
                    esErrorRecuperable = true
                )
            val encontrado = baseTotal.firstOrNull { it.id == idReciente }
                ?: return@withContext LucyResponse(
                    turnoContexto = turnoObjetivo,
                    texto = "El trabajador recordado anteriormente ya no figura en el maestro de ${turnoObjetivo.nombre}.",
                    esErrorRecuperable = true
                )
            val estado = if (encontrado.siBajo) "SÍ BAJÓ" else "NO BAJÓ"
            return@withContext LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "${encontrado.nombreCompleto} · ${encontrado.ubicacionTexto} · ${encontrado.empresa.nombre} [$estado] en ${turnoObjetivo.nombre}.",
                trabajadoresRelacionados = listOf(encontrado)
            )
        }

        val base = if (empresa != null) baseTotal.filter { it.empresa == empresa } else baseTotal
        val coincidencias = LucySafeBatchMatcher.buscarIndividualTolerante(
            query = query,
            universo = base,
            correccionesAprendidas = obtenerCorreccionesAprendidasSeguras(turnoObjetivo)
        )

        if (coincidencias.isEmpty()) {
            synchronized(historyLock) {
                ultimoContextoCorreccionPorTurno[turnoObjetivo] = LucyCorrectionContext(
                    textoErroneo = query,
                    tipo = LucyTipoCorreccionAprendida.NOMBRE,
                    empresaObjetivo = empresa
                )
            }
            val ambito = empresa?.let { "${it.nombre} en ${turnoObjetivo.nombre}" } ?: turnoObjetivo.nombre
            return@withContext LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = if (esMencionPasiva) {
                    "No encontré a \"$query\" en $ambito."
                } else {
                    "No encontré trabajadores para \"$query\" en $ambito."
                }
            )
        }

        if (coincidencias.size == 1) {
            val unico = coincidencias.first()
            recordarTrabajadorSinFallo(turnoObjetivo, unico.id)
            recordarEmpresaSinFallo(turnoObjetivo, unico.empresa)
            synchronized(historyLock) {
                ultimoContextoCorreccionPorTurno[turnoObjetivo] = LucyCorrectionContext(
                    textoErroneo = query,
                    tipo = LucyTipoCorreccionAprendida.NOMBRE,
                    empresaObjetivo = unico.empresa
                )
            }
            if (esMencionPasiva) {
                return@withContext LucyResponse(
                    turnoContexto = turnoObjetivo,
                    texto = "Lo tengo.",
                    trabajadoresRelacionados = listOf(unico)
                )
            }
        }

        val muestra = coincidencias.take(6)
        val texto = if (esMencionPasiva) {
            "Hay ${coincidencias.size} coincidencias para \"$query\" en ${turnoObjetivo.nombre}: " +
                muestra.joinToString(" / ") { "${it.nombreCompleto} (${it.empresa.nombre})" } +
                ". ¿Cuál necesitas?"
        } else {
            buildString {
                append("Encontré ${coincidencias.size} coincidencia(s) en ${turnoObjetivo.nombre}:")
                muestra.forEach { t ->
                    val estado = if (t.siBajo) "SÍ BAJÓ" else "NO BAJÓ"
                    append("\n• ${t.nombreCompleto} · ${t.ubicacionTexto} · ${t.empresa.nombre} [$estado]")
                }
                if (coincidencias.size > muestra.size) {
                    append("\n… y ${coincidencias.size - muestra.size} más.")
                }
            }
        }

        LucyResponse(
            turnoContexto = turnoObjetivo,
            texto = texto,
            trabajadoresRelacionados = muestra
        )
    }

    private suspend fun resolverEstadoTrabajador(
        query: String,
        turnoObjetivo: Turno,
        empresa: EmpresaOficial?,
        usarTrabajadorAnterior: Boolean = false,
        respuestaBreve: Boolean = false
    ): LucyResponse = withContext(defaultDispatcher) {
        val baseTotal = repository.observeTrabajadoresConEstado(turnoObjetivo).first()
        if (usarTrabajadorAnterior && query.isBlank()) {
            val idReciente = resolverUltimoTrabajadorSeguro(turnoObjetivo)
                ?: return@withContext LucyResponse(
                    turnoContexto = turnoObjetivo,
                    texto = if (respuestaBreve) {
                        "¿De qué trabajador me hablas?"
                    } else {
                        "No hay un trabajador reciente recordado en ${turnoObjetivo.nombre}."
                    },
                    esErrorRecuperable = true
                )
            val t = baseTotal.firstOrNull { it.id == idReciente }
                ?: return@withContext LucyResponse(
                    turnoContexto = turnoObjetivo,
                    texto = "El trabajador recordado anteriormente ya no figura en el maestro de ${turnoObjetivo.nombre}.",
                    esErrorRecuperable = true
                )
            val textoEstado = if (respuestaBreve) {
                if (t.siBajo) "Sí." else "No."
            } else {
                val estado = if (t.siBajo) "SÍ BAJÓ" else "NO BAJÓ"
                "${t.nombreCompleto} (${t.empresa.nombre}, ${t.ubicacionTexto}) está marcado como $estado en ${turnoObjetivo.nombre}."
            }
            return@withContext LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = textoEstado,
                trabajadoresRelacionados = listOf(t)
            )
        }

        val base = if (empresa != null) baseTotal.filter { it.empresa == empresa } else baseTotal
        val coincidencias = LucySafeBatchMatcher.buscarIndividualTolerante(
            query = query,
            universo = base,
            correccionesAprendidas = obtenerCorreccionesAprendidasSeguras(turnoObjetivo)
        )

        if (coincidencias.isEmpty()) {
            return@withContext LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "No encontré a \"$query\" en ${turnoObjetivo.nombre}."
            )
        }

        if (coincidencias.size == 1) {
            val t = coincidencias.first()
            val estado = if (t.siBajo) "SÍ BAJÓ" else "NO BAJÓ"
            recordarTrabajadorSinFallo(turnoObjetivo, t.id)
            recordarEmpresaSinFallo(turnoObjetivo, t.empresa)
            return@withContext LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "${t.nombreCompleto} (${t.empresa.nombre}, ${t.ubicacionTexto}) está marcado como $estado en ${turnoObjetivo.nombre}.",
                trabajadoresRelacionados = listOf(t)
            )
        }

        val muestra = coincidencias.take(5)
        val texto = buildString {
            append("Hay ${coincidencias.size} coincidencias para \"$query\" en ${turnoObjetivo.nombre}:")
            muestra.forEach { t ->
                val estado = if (t.siBajo) "SÍ BAJÓ" else "NO BAJÓ"
                append("\n• ${t.nombreCompleto} (${t.empresa.nombre}, ${t.ubicacionTexto}): $estado")
            }
        }

        LucyResponse(
            turnoContexto = turnoObjetivo,
            texto = texto,
            trabajadoresRelacionados = muestra
        )
    }

    private suspend fun prepararConfirmacionMarcacion(
        query: String,
        marcarComoSiBajo: Boolean,
        turnoActivo: Turno,
        empresa: EmpresaOficial?,
        turnoSolicitado: Turno? = null,
        usarTrabajadorAnterior: Boolean = false
    ): LucyResponse {
        if (turnoSolicitado != null && turnoSolicitado != turnoActivo) {
            return responderConflictoTurno(
                turnoActivo = turnoActivo,
                turnoSolicitado = turnoSolicitado,
                query = query
            )
        }
        return withContext(defaultDispatcher) {
            val baseTotal = repository.observeTrabajadoresConEstado(turnoActivo).first()
            val coincidencias = if (usarTrabajadorAnterior && query.isBlank()) {
                val idReciente = resolverUltimoTrabajadorSeguro(turnoActivo)
                    ?: return@withContext LucyResponse(
                        turnoContexto = turnoActivo,
                        texto = "No hay un trabajador reciente recordado en ${turnoActivo.nombre} para marcar.",
                        esErrorRecuperable = true
                    )
                val encontrado = baseTotal.firstOrNull { it.id == idReciente }
                if (encontrado == null) {
                    return@withContext LucyResponse(
                        turnoContexto = turnoActivo,
                        texto = "El trabajador recordado anteriormente ya no figura en el maestro de ${turnoActivo.nombre}.",
                        esErrorRecuperable = true
                    )
                }
                listOf(encontrado)
            } else {
                val base = if (empresa != null) baseTotal.filter { it.empresa == empresa } else baseTotal
                LucySafeBatchMatcher.buscarIndividualTolerante(
                    query = query,
                    universo = base,
                    correccionesAprendidas = obtenerCorreccionesAprendidasSeguras(turnoActivo)
                )
            }

            if (coincidencias.isEmpty()) {
                val ambito = empresa?.let { "${it.nombre} (${turnoActivo.nombre})" } ?: turnoActivo.nombre
                return@withContext LucyResponse(
                    turnoContexto = turnoActivo,
                    texto = "No encontré a \"$query\" en $ambito para modificar su marcación."
                )
            }

            if (coincidencias.size > 1) {
                val candidatos = coincidencias.take(5)
                return@withContext LucyResponse(
                    turnoContexto = turnoActivo,
                    texto = "Encontré ${coincidencias.size} trabajadores para \"$query\" en ${turnoActivo.nombre}. Selecciona el trabajador exacto antes de marcar:",
                    candidatosDesambiguacion = candidatos,
                    accionDesambiguacionSiBajo = marcarComoSiBajo
                )
            }

            val unico = coincidencias.first()
            recordarTrabajadorSinFallo(turnoActivo, unico.id)
            recordarEmpresaSinFallo(turnoActivo, unico.empresa)
            val accionTexto = if (marcarComoSiBajo) "SÍ BAJÓ" else "NO BAJÓ"
            if (unico.siBajo == marcarComoSiBajo) {
                return@withContext LucyResponse(
                    turnoContexto = turnoActivo,
                    texto = "${unico.nombreCompleto} ya figura como $accionTexto en ${turnoActivo.nombre}.",
                    trabajadoresRelacionados = listOf(unico)
                )
            }

            val confirmacion = LucyPendingConfirmation(
                turno = turnoActivo,
                trabajador = unico,
                marcarComoSiBajo = marcarComoSiBajo,
                descripcion = "Marcar como $accionTexto a ${unico.nombreCompleto} (${unico.empresa.nombre}, ${unico.ubicacionTexto}) en ${turnoActivo.nombre}"
            )

            return@withContext LucyResponse(
                turnoContexto = turnoActivo,
                texto = "¿Confirmas ${confirmacion.descripcion}?",
                trabajadoresRelacionados = listOf(unico),
                confirmacionRequerida = confirmacion
            )
        }
    }

    private suspend fun ejecutarMarcacionMultiple(
        intent: LucyIntent.MarcacionMultiple,
        contexto: LucyTurnContext,
        origen: LucyOperationOrigin
    ): LucyResponse {
        val turnoObjetivo = contexto.turnoActivo
        if (intent.turnoSolicitado != null && intent.turnoSolicitado != turnoObjetivo) {
            return responderConflictoTurno(
                turnoActivo = turnoObjetivo,
                turnoSolicitado = intent.turnoSolicitado,
                query = intent.nombresTexto
            )
        }

        val batchPrevio = synchronized(historyLock) { batchMarkingContextPorTurno[turnoObjetivo] }
        val empresaObjetivo: EmpresaOficial? = when {
            intent.empresa != null -> intent.empresa
            intent.esContinuacion -> batchPrevio?.empresa ?: contexto.empresaActiva ?: resolverUltimaEmpresaSegura(turnoObjetivo)
            intent.usarEmpresaAnterior -> batchPrevio?.empresa ?: contexto.empresaActiva ?: resolverUltimaEmpresaSegura(turnoObjetivo)
            else -> null
        }

        if (intent.usarEmpresaAnterior && !intent.esContinuacion && empresaObjetivo == null) {
            return LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "No hay una empresa reciente recordada en ${turnoObjetivo.nombre}. Indica la empresa al dictar los nombres.",
                esErrorRecuperable = true
            )
        }

        return actionMutex.withLock {
            val turnoReal = repository.turnoActivo.first()
            if (intent.turnoSolicitado != null && intent.turnoSolicitado != turnoReal) {
                return@withLock responderConflictoTurno(
                    turnoActivo = turnoReal,
                    turnoSolicitado = intent.turnoSolicitado,
                    query = intent.nombresTexto
                )
            }

            val maestroTurno = repository.observeTrabajadoresConEstado(turnoReal).first()
            val correcciones = obtenerCorreccionesAprendidasSeguras(turnoReal)
            val matchResult = withContext(defaultDispatcher) {
                LucySafeBatchMatcher.resolverLote(
                    nombresTexto = intent.nombresTexto,
                    empresaObjetivo = empresaObjetivo,
                    maestroTurnoActivo = maestroTurno,
                    correccionesAprendidas = correcciones
                )
            }

            synchronized(historyLock) {
                batchMarkingContextPorTurno[turnoReal] = LucyBatchContext(empresa = empresaObjetivo)
            }
            if (empresaObjetivo != null) {
                recordarEmpresaSinFallo(turnoReal, empresaObjetivo)
            }

            val origenDb = if (origen == LucyOperationOrigin.VOICE) "VOZ" else "LUCY"
            val trabajadoresParaMarcar = matchResult.identificados.map { it.trabajador }
            val resultadoRepo = if (trabajadoresParaMarcar.isNotEmpty()) {
                repository.marcarTrabajadoresSiBajoEnLote(
                    turno = turnoReal,
                    trabajadores = trabajadoresParaMarcar,
                    origen = origenDb
                )
            } else {
                com.example.data.repository.ResultadoMarcacionLote(emptyList(), emptyList())
            }

            val todosAfectados = resultadoRepo.recienMarcados + resultadoRepo.yaEstabanMarcados
            if (todosAfectados.size == 1) {
                recordarTrabajadorSinFallo(turnoReal, todosAfectados.first().id)
            }

            val nuevasDudas = construirDudasDesdeResultado(matchResult, empresaObjetivo)
            synchronized(historyLock) {
                val cola = pendingDoubtsPorTurno.getOrPut(turnoReal) { mutableListOf() }
                if (!intent.esContinuacion) {
                    cola.clear()
                }
                cola.addAll(nuevasDudas)
                val dudaInicial = cola.firstOrNull()
                if (dudaInicial != null) {
                    ultimoContextoCorreccionPorTurno[turnoReal] = LucyCorrectionContext(
                        textoErroneo = dudaInicial.nombreSolicitado,
                        tipo = if (origen == LucyOperationOrigin.VOICE) {
                            LucyTipoCorreccionAprendida.PRONUNCIACION_VOZ
                        } else {
                            LucyTipoCorreccionAprendida.NOMBRE
                        },
                        empresaObjetivo = empresaObjetivo
                    )
                } else if (matchResult.identificados.size == 1) {
                    val unicoMatch = matchResult.identificados.first()
                    ultimoContextoCorreccionPorTurno[turnoReal] = LucyCorrectionContext(
                        textoErroneo = unicoMatch.nombreSolicitado,
                        tipo = if (origen == LucyOperationOrigin.VOICE) {
                            LucyTipoCorreccionAprendida.PRONUNCIACION_VOZ
                        } else {
                            LucyTipoCorreccionAprendida.NOMBRE
                        },
                        empresaObjetivo = unicoMatch.trabajador.empresa
                    )
                }
            }

            val respuestaTexto = construirRespuestaMarcacionMultiple(
                turno = turnoReal,
                empresaObjetivo = empresaObjetivo,
                recienMarcados = resultadoRepo.recienMarcados,
                yaEstabanMarcados = resultadoRepo.yaEstabanMarcados,
                matchResult = matchResult,
                incluirUbicacion = intent.incluirUbicacion
            )

            LucyResponse(
                turnoContexto = turnoReal,
                texto = respuestaTexto,
                trabajadoresRelacionados = todosAfectados,
                esErrorRecuperable = todosAfectados.isEmpty() &&
                    (matchResult.ambiguos.isNotEmpty() || matchResult.otrasEmpresas.isNotEmpty() || matchResult.noEncontrados.isNotEmpty())
            )
        }
    }

    private fun construirDudasDesdeResultado(
        matchResult: LucyBatchMatchResult,
        empresaObjetivo: EmpresaOficial?
    ): List<LucyPendingDoubt> {
        val dudas = mutableListOf<LucyPendingDoubt>()
        matchResult.ambiguos.forEach { amb ->
            dudas.add(
                LucyPendingDoubt(
                    nombreSolicitado = amb.nombreSolicitado,
                    tipo = LucyDoubtType.AMBIGUO,
                    empresaObjetivo = empresaObjetivo,
                    candidatos = amb.candidatos
                )
            )
        }
        matchResult.otrasEmpresas.forEach { otra ->
            dudas.add(
                LucyPendingDoubt(
                    nombreSolicitado = otra.nombreSolicitado,
                    tipo = LucyDoubtType.OTRA_EMPRESA,
                    empresaObjetivo = otra.empresaEsperada ?: empresaObjetivo,
                    candidatos = otra.candidatos,
                    empresasReales = otra.empresasReales
                )
            )
        }
        matchResult.noEncontrados.forEach { noEnc ->
            dudas.add(
                LucyPendingDoubt(
                    nombreSolicitado = noEnc.nombreSolicitado,
                    tipo = LucyDoubtType.NO_ENCONTRADO,
                    empresaObjetivo = empresaObjetivo
                )
            )
        }
        return dudas
    }

    private fun construirRespuestaMarcacionMultiple(
        turno: Turno,
        empresaObjetivo: EmpresaOficial?,
        recienMarcados: List<TrabajadorConEstado>,
        yaEstabanMarcados: List<TrabajadorConEstado>,
        matchResult: LucyBatchMatchResult,
        incluirUbicacion: Boolean
    ): String {
        val empTexto = empresaObjetivo?.let { " de ${it.nombre}" }.orEmpty()
        val ambito = empresaObjetivo?.let { "${it.nombre} (${turno.nombre})" } ?: turno.nombre
        val partes = mutableListOf<String>()

        if (recienMarcados.isNotEmpty()) {
            val sustantivo = if (recienMarcados.size == 1) "trabajador" else "trabajadores"
            partes.add("Listo. Marqué ${recienMarcados.size} $sustantivo$empTexto como SÍ SUBIÓ.")
        } else if (yaEstabanMarcados.isNotEmpty() &&
            matchResult.ambiguos.isEmpty() &&
            matchResult.otrasEmpresas.isEmpty() &&
            matchResult.noEncontrados.isEmpty()
        ) {
            val yaTxt = if (yaEstabanMarcados.size == 1) {
                "${yaEstabanMarcados.first().nombreCompleto} ya estaba marcado como SÍ SUBIÓ."
            } else {
                "Esos ${yaEstabanMarcados.size} trabajadores$empTexto ya estaban marcados como SÍ SUBIÓ."
            }
            partes.add(yaTxt)
        }

        if (yaEstabanMarcados.isNotEmpty() && recienMarcados.isNotEmpty()) {
            val yaTxt = if (yaEstabanMarcados.size == 1) {
                "${yaEstabanMarcados.first().nombreCompleto} ya estaba marcado."
            } else {
                "${yaEstabanMarcados.size} ya estaban marcados."
            }
            partes.add(yaTxt)
        } else if (yaEstabanMarcados.isNotEmpty() && recienMarcados.isEmpty() &&
            (matchResult.ambiguos.isNotEmpty() || matchResult.otrasEmpresas.isNotEmpty() || matchResult.noEncontrados.isNotEmpty())
        ) {
            val yaTxt = if (yaEstabanMarcados.size == 1) {
                "${yaEstabanMarcados.first().nombreCompleto} ya estaba marcado como SÍ SUBIÓ."
            } else {
                "${yaEstabanMarcados.size} trabajadores ya estaban marcados como SÍ SUBIÓ."
            }
            partes.add(yaTxt)
        }

        val totalDudas = matchResult.ambiguos.size + matchResult.otrasEmpresas.size + matchResult.noEncontrados.size
        if (totalDudas > 0) {
            val detallesDudas = mutableListOf<String>()
            matchResult.ambiguos.forEach { amb ->
                val empresasDistintas = amb.candidatos.map { it.empresa.nombre }.distinct()
                val opciones = if (empresasDistintas.size > 1) {
                    amb.candidatos.joinToString(" o ") { "${it.nombreCompleto} (${it.empresa.nombre})" }
                } else {
                    amb.candidatos.joinToString(" o ") { it.nombreCompleto }
                }
                detallesDudas.add(
                    "${amb.nombreSolicitado} (${amb.candidatos.size} coincidencias: $opciones)"
                )
            }
            matchResult.otrasEmpresas.forEach { otra ->
                val empReales = otra.empresasReales.joinToString(" / ") { it.nombre }
                val empEsp = otra.empresaEsperada ?: empresaObjetivo
                detallesDudas.add(
                    "${otra.nombreSolicitado} pertenece a $empReales${empEsp?.let { " (no a ${it.nombre})" } ?: ""}"
                )
            }
            matchResult.noEncontrados.forEach { noEnc ->
                detallesDudas.add("No encontré a ${noEnc.nombreSolicitado} en $ambito")
            }
            val prefijo = if (totalDudas == 1) "Tengo 1 duda:" else "Tengo $totalDudas dudas:"
            partes.add("$prefijo ${detallesDudas.joinToString("; ")}. ¿Cuál confirmo o los dejo pendientes?")
        }

        if (partes.isEmpty()) {
            partes.add("No identifiqué nombres válidos para marcar en $ambito.")
        }

        if (incluirUbicacion && (recienMarcados + yaEstabanMarcados).isNotEmpty()) {
            val detalle = (recienMarcados + yaEstabanMarcados).joinToString("; ") { t ->
                "${t.nombreCompleto} (${t.ubicacionTexto})"
            }
            partes.add("Ubicación: $detalle.")
        }

        return partes.joinToString(" ")
    }

    private suspend fun ejecutarMarcarTrabajadorEnContexto(
        intent: LucyIntent.MarcarTrabajadorEnContexto,
        turnoActivo: Turno,
        origen: LucyOperationOrigin
    ): LucyResponse {
        return actionMutex.withLock {
            val turnoReal = repository.turnoActivo.first()
            val workerId = resolverUltimoTrabajadorSeguro(turnoReal)
                ?: return@withLock LucyResponse(
                    turnoContexto = turnoReal,
                    texto = "¿A quién quieres que marque? Dime primero su nombre.",
                    esErrorRecuperable = true
                )
            val trabajadorActual = repository.observeTrabajadoresConEstado(turnoReal).first()
                .firstOrNull { it.id == workerId }
                ?: return@withLock LucyResponse(
                    turnoContexto = turnoReal,
                    texto = "Ese trabajador ya no figura en ${turnoReal.nombre}.",
                    esErrorRecuperable = true
                )

            val origenDb = if (origen == LucyOperationOrigin.VOICE) "VOZ" else "LUCY"
            if (intent.marcarComoSiBajo) {
                repository.marcarTrabajadoresSiBajoEnLote(
                    turno = turnoReal,
                    trabajadores = listOf(trabajadorActual),
                    origen = origenDb
                )
            } else if (trabajadorActual.siBajo) {
                repository.alternarMarcacionSiBajo(trabajadorActual)
            }

            val verificado = repository.observeTrabajadoresConEstado(turnoReal).first()
                .firstOrNull { it.id == trabajadorActual.id }
            if (verificado == null || verificado.siBajo != intent.marcarComoSiBajo) {
                return@withLock LucyResponse(
                    turnoContexto = turnoReal,
                    texto = "No se pudo verificar la marcación de ${trabajadorActual.nombreCompleto} en ${turnoReal.nombre}.",
                    esErrorRecuperable = true
                )
            }

            recordarTrabajadorSinFallo(turnoReal, verificado.id)
            recordarEmpresaSinFallo(turnoReal, verificado.empresa)
            LucyResponse(
                turnoContexto = turnoReal,
                texto = if (intent.marcarComoSiBajo) "Listo." else "Listo, quedó como NO BAJÓ.",
                trabajadoresRelacionados = listOf(verificado)
            )
        }
    }

    private suspend fun ejecutarResolverDudaPendiente(
        intent: LucyIntent.ResolverDudaPendiente,
        contexto: LucyTurnContext,
        origen: LucyOperationOrigin
    ): LucyResponse {
        val turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
        if (intent.turnoSolicitado != null && intent.turnoSolicitado != contexto.turnoActivo) {
            return responderConflictoTurno(
                turnoActivo = contexto.turnoActivo,
                turnoSolicitado = intent.turnoSolicitado,
                query = intent.textoRespuesta
            )
        }

        return actionMutex.withLock {
            val turnoReal = repository.turnoActivo.first()
            val maestroTurno = repository.observeTrabajadoresConEstado(turnoReal).first()
            val duda = synchronized(historyLock) {
                pendingDoubtsPorTurno[turnoReal]?.firstOrNull()
            } ?: return@withLock LucyResponse(
                turnoContexto = turnoReal,
                texto = "No tengo dudas pendientes por resolver en ${turnoReal.nombre}."
            )

            if (intent.dejarPendiente) {
                val restantes = synchronized(historyLock) {
                    val cola = pendingDoubtsPorTurno[turnoReal]
                    if (cola != null && cola.isNotEmpty()) cola.removeAt(0)
                    cola?.toList().orEmpty()
                }
                return@withLock LucyResponse(
                    turnoContexto = turnoReal,
                    texto = formatearRespuestaDudaResuelta(
                        prefijo = "Dejé pendiente a ${duda.nombreSolicitado}.",
                        restantes = restantes
                    )
                )
            }

            val elegido: TrabajadorConEstado?
            val posiblesRestantes: List<TrabajadorConEstado>

            when {
                intent.forzarMarcacion && duda.candidatos.size == 1 -> {
                    elegido = duda.candidatos.first()
                    posiblesRestantes = emptyList()
                }
                intent.indiceOpcion != null -> {
                    elegido = duda.candidatos.getOrNull(intent.indiceOpcion)
                    posiblesRestantes = if (elegido == null) duda.candidatos else emptyList()
                }
                intent.empresa != null -> {
                    val filtrados = duda.candidatos.filter { it.empresa == intent.empresa }
                    elegido = if (filtrados.size == 1) filtrados.first() else null
                    posiblesRestantes = filtrados
                }
                else -> {
                    val origenBusqueda = if (duda.candidatos.isNotEmpty()) duda.candidatos else maestroTurno
                    val coincidencias = LucySafeBatchMatcher.buscarIndividualTolerante(
                        query = intent.textoRespuesta,
                        universo = origenBusqueda,
                        correccionesAprendidas = obtenerCorreccionesAprendidasSeguras(turnoReal)
                    )
                    val filtrados = if (coincidencias.isNotEmpty()) {
                        coincidencias
                    } else {
                        LucySafeBatchMatcher.resolverLote(
                            nombresTexto = intent.textoRespuesta,
                            empresaObjetivo = null,
                            maestroTurnoActivo = origenBusqueda
                        ).let { res ->
                            res.identificados.map { it.trabajador } + res.ambiguos.flatMap { it.candidatos }
                        }
                    }
                    elegido = if (filtrados.size == 1) filtrados.first() else null
                    posiblesRestantes = filtrados
                }
            }

            if (elegido == null) {
                if (posiblesRestantes.size > 1) {
                    synchronized(historyLock) {
                        val cola = pendingDoubtsPorTurno[turnoReal]
                        if (cola != null && cola.isNotEmpty()) {
                            cola[0] = duda.copy(candidatos = posiblesRestantes)
                        }
                    }
                    val lista = posiblesRestantes.joinToString(" o ") { "${it.nombreCompleto} (${it.empresa.nombre})" }
                    return@withLock LucyResponse(
                        turnoContexto = turnoReal,
                        texto = "Sigo con ${posiblesRestantes.size} coincidencias para ${duda.nombreSolicitado}: $lista. ¿Cuál?",
                        trabajadoresRelacionados = posiblesRestantes
                    )
                }
                return@withLock LucyResponse(
                    turnoContexto = turnoReal,
                    texto = "No encontré esa opción para \"${duda.nombreSolicitado}\". Dime el nombre completo o di \"déjalo pendiente\".",
                    esErrorRecuperable = true
                )
            }

            val trabajadorActual = maestroTurno.firstOrNull { it.id == elegido.id } ?: elegido
            val origenDb = if (origen == LucyOperationOrigin.VOICE) "VOZ" else "LUCY"
            repository.marcarTrabajadoresSiBajoEnLote(
                turno = turnoReal,
                trabajadores = listOf(trabajadorActual),
                origen = origenDb
            )
            recordarTrabajadorSinFallo(turnoReal, trabajadorActual.id)
            recordarEmpresaSinFallo(turnoReal, duda.empresaObjetivo ?: trabajadorActual.empresa)

            if (duda.tipo == LucyDoubtType.NO_ENCONTRADO ||
                TextoNormalizer.normalizar(duda.nombreSolicitado) != TextoNormalizer.normalizar(trabajadorActual.nombreCompleto)
            ) {
                guardarCorreccionSinFallo(
                    LucyCorreccionAprendida(
                        tipo = if (origen == LucyOperationOrigin.VOICE) {
                            LucyTipoCorreccionAprendida.PRONUNCIACION_VOZ
                        } else {
                            LucyTipoCorreccionAprendida.NOMBRE
                        },
                        turno = turnoReal,
                        empresaCodigo = trabajadorActual.empresa.codigo,
                        textoErroneo = duda.nombreSolicitado,
                        claveErroneaNorm = LucyMemoryStorageSerializer.normalizarClaveCorreccion(duda.nombreSolicitado),
                        trabajadorId = trabajadorActual.id,
                        valorCorregido = trabajadorActual.nombreCompleto
                    )
                )
            }

            val restantes = synchronized(historyLock) {
                val cola = pendingDoubtsPorTurno[turnoReal]
                if (cola != null && cola.isNotEmpty()) cola.removeAt(0)
                cola?.toList().orEmpty()
            }

            LucyResponse(
                turnoContexto = turnoReal,
                texto = formatearRespuestaDudaResuelta(
                    prefijo = "Listo. Marqué a ${trabajadorActual.nombreCompleto} (${trabajadorActual.empresa.nombre}) como SÍ SUBIÓ.",
                    restantes = restantes
                ),
                trabajadoresRelacionados = listOf(trabajadorActual)
            )
        }
    }

    private fun formatearRespuestaDudaResuelta(
        prefijo: String,
        restantes: List<LucyPendingDoubt>
    ): String {
        if (restantes.isEmpty()) return prefijo
        val siguiente = restantes.first()
        val detalleSig = when (siguiente.tipo) {
            LucyDoubtType.AMBIGUO ->
                "${siguiente.nombreSolicitado} (${siguiente.candidatos.joinToString(" o ") { "${it.nombreCompleto} (${it.empresa.nombre})" }})"
            LucyDoubtType.OTRA_EMPRESA ->
                "${siguiente.nombreSolicitado} (es de ${siguiente.empresasReales.joinToString("/") { it.nombre }})"
            LucyDoubtType.NO_ENCONTRADO ->
                "No encontré a ${siguiente.nombreSolicitado}"
        }
        return "$prefijo Siguiente duda (${restantes.size} pendiente(s)): $detalleSig."
    }

    private suspend fun ejecutarActualizacionDatoMaestro(
        intent: LucyIntent.ActualizarDatoTrabajador,
        contexto: LucyTurnContext,
        origen: LucyOperationOrigin
    ): LucyResponse {
        val turnoSolicitado = intent.turnoSolicitado ?: contexto.turnoActivo
        if (intent.turnoSolicitado != null && intent.turnoSolicitado != contexto.turnoActivo) {
            return responderConflictoTurno(
                turnoActivo = contexto.turnoActivo,
                turnoSolicitado = intent.turnoSolicitado,
                query = intent.nombreTrabajador.ifBlank { "trabajador en contexto" }
            )
        }

        return actionMutex.withLock {
            val turnoReal = repository.turnoActivo.first()
            val maestroTurno = repository.observeTrabajadoresConEstado(turnoReal).first()

            val objetivo: TrabajadorConEstado = if (intent.usarTrabajadorAnterior && intent.nombreTrabajador.isBlank()) {
                val idReciente = resolverUltimoTrabajadorSeguro(turnoReal)
                    ?: return@withLock LucyResponse(
                        turnoContexto = turnoReal,
                        texto = "¿A qué trabajador te refieres para actualizar su dato en ${turnoReal.nombre}?",
                        esErrorRecuperable = true
                    )
                maestroTurno.firstOrNull { it.id == idReciente }
                    ?: return@withLock LucyResponse(
                        turnoContexto = turnoReal,
                        texto = "El trabajador recordado ya no figura en ${turnoReal.nombre}.",
                        esErrorRecuperable = true
                    )
            } else {
                val matches = LucySafeBatchMatcher.buscarIndividualTolerante(
                    query = intent.nombreTrabajador,
                    universo = maestroTurno,
                    correccionesAprendidas = obtenerCorreccionesAprendidasSeguras(turnoReal)
                )
                when {
                    matches.isEmpty() -> return@withLock LucyResponse(
                        turnoContexto = turnoReal,
                        texto = "No encontré a \"${intent.nombreTrabajador}\" en ${turnoReal.nombre}.",
                        esErrorRecuperable = true
                    )
                    matches.size > 1 -> {
                        val opciones = matches.take(4).joinToString(" o ") { "${it.nombreCompleto} (${it.empresa.nombre})" }
                        return@withLock LucyResponse(
                            turnoContexto = turnoReal,
                            texto = "Encontré ${matches.size} coincidencias para \"${intent.nombreTrabajador}\": $opciones. Dime el nombre completo para actualizar.",
                            trabajadoresRelacionados = matches.take(4)
                        )
                    }
                    else -> matches.first()
                }
            }

            val nuevaEmpresa = intent.nuevaEmpresa ?: objetivo.empresa
            val nuevaHab = intent.nuevaHabitacion?.trim()?.takeIf { it.isNotBlank() } ?: objetivo.habitacion
            val nuevoPab = intent.nuevoPabellon?.trim()?.takeIf { it.isNotBlank() } ?: objetivo.pabellon

            val cambios = mutableListOf<Triple<LucyCampoMaestroEditado, String, String>>()
            if (nuevaHab.uppercase() != objetivo.habitacionMayusculas) {
                cambios.add(Triple(LucyCampoMaestroEditado.HABITACION, objetivo.habitacionMayusculas, nuevaHab.uppercase()))
            }
            if (nuevoPab.uppercase() != objetivo.pabellonMayusculas) {
                cambios.add(Triple(LucyCampoMaestroEditado.PABELLON, objetivo.pabellonMayusculas, nuevoPab.uppercase()))
            }
            if (nuevaEmpresa != objetivo.empresa) {
                cambios.add(Triple(LucyCampoMaestroEditado.EMPRESA, objetivo.empresa.nombre, nuevaEmpresa.nombre))
            }

            if (cambios.isEmpty()) {
                recordarTrabajadorSinFallo(turnoReal, objetivo.id)
                recordarEmpresaSinFallo(turnoReal, objetivo.empresa)
                return@withLock LucyResponse(
                    turnoContexto = turnoReal,
                    texto = "${objetivo.nombreCompleto} ya tenía ese dato registrado en ${turnoReal.nombre}.",
                    trabajadoresRelacionados = listOf(objetivo)
                )
            }

            repository.actualizarTrabajador(
                trabajadorId = objetivo.id,
                empresa = nuevaEmpresa,
                nombre = objetivo.nombre,
                primerApellido = objetivo.primerApellido,
                segundoApellido = objetivo.segundoApellido,
                habitacion = nuevaHab,
                pabellon = nuevoPab
            )

            val actualizado = repository.observeTrabajadoresConEstado(turnoReal).first()
                .firstOrNull { it.id == objetivo.id }
                ?: return@withLock LucyResponse(
                    turnoContexto = turnoReal,
                    texto = "No se pudo verificar el cambio en la base de datos local.",
                    esErrorRecuperable = true
                )

            val origenRegistro = if (origen == LucyOperationOrigin.VOICE) "VOZ" else "LUCY"
            val ahora = System.currentTimeMillis()
            cambios.forEach { (campo, ant, nuevo) ->
                registrarCambioMaestroSinFallo(
                    LucyRegistroCambioMaestro(
                        trabajadorId = actualizado.id,
                        trabajadorNombre = actualizado.nombreCompleto,
                        turno = turnoReal,
                        campo = campo,
                        valorAnterior = ant,
                        valorNuevo = nuevo,
                        fechaMs = ahora,
                        origen = origenRegistro
                    )
                )
            }

            recordarTrabajadorSinFallo(turnoReal, actualizado.id)
            recordarEmpresaSinFallo(turnoReal, actualizado.empresa)

            LucyResponse(
                turnoContexto = turnoReal,
                texto = "Actualizado.",
                trabajadoresRelacionados = listOf(actualizado)
            )
        }
    }

    private suspend fun ejecutarConsultarHistorialCambios(
        intent: LucyIntent.ConsultarHistorialCambios,
        turnoActivo: Turno
    ): LucyResponse {
        val turnoObjetivo = intent.turnoSolicitado ?: turnoActivo
        val historial = obtenerHistorialCambiosSeguros(turnoObjetivo)
        val filtrado = if (intent.nombreTrabajador.isNullOrBlank()) {
            historial
        } else {
            val normFiltro = TextoNormalizer.normalizar(intent.nombreTrabajador)
            historial.filter { TextoNormalizer.normalizar(it.trabajadorNombre).contains(normFiltro) }
        }

        if (filtrado.isEmpty()) {
            return LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "No hay cambios de datos registrados en ${turnoObjetivo.nombre}."
            )
        }

        val texto = buildString {
            append("Cambios realizados en ${turnoObjetivo.nombre}:")
            filtrado.take(10).forEach { c ->
                val ant = c.valorAnterior.ifBlank { "sin dato" }
                append("\n• ${c.trabajadorNombre}: ${c.campo.etiqueta} de $ant a ${c.valorNuevo}")
            }
        }
        return LucyResponse(
            turnoContexto = turnoObjetivo,
            texto = texto
        )
    }

    private suspend fun ejecutarRegistrarCorreccionUsuario(
        intent: LucyIntent.RegistrarCorreccionUsuario,
        contexto: LucyTurnContext,
        origen: LucyOperationOrigin
    ): LucyResponse {
        val turnoReal = repository.turnoActivo.first()
        val maestroTurno = repository.observeTrabajadoresConEstado(turnoReal).first()

        val matches = LucySafeBatchMatcher.buscarIndividualTolerante(
            query = intent.textoCorregido,
            universo = maestroTurno,
            correccionesAprendidas = emptyList()
        )
        if (matches.isEmpty()) {
            return LucyResponse(
                turnoContexto = turnoReal,
                texto = "No encontré a \"${intent.textoCorregido}\" en ${turnoReal.nombre} para guardar la corrección.",
                esErrorRecuperable = true
            )
        }
        if (matches.size > 1) {
            val opciones = matches.take(4).joinToString(" o ") { "${it.nombreCompleto} (${it.empresa.nombre})" }
            return LucyResponse(
                turnoContexto = turnoReal,
                texto = "Encontré ${matches.size} coincidencias para \"${intent.textoCorregido}\": $opciones. Indícame el nombre completo exacto.",
                trabajadoresRelacionados = matches.take(4)
            )
        }

        val trabajador = matches.first()
        var textoErroneo = intent.textoErroneo
        var tipoFinal = intent.tipoSugerido

        if (intent.filaVision != null) {
            val vision = synchronized(historyLock) { ultimoAnalisisVisionPorTurno[turnoReal] }
            val fila = vision?.trabajadoresDetectados?.firstOrNull {
                it.datosReconocidos.filaNumero == intent.filaVision || it.indiceEnAnalisis == intent.filaVision
            }
            if (fila != null && fila.nombreDetectado.isNotBlank()) {
                textoErroneo = fila.nombreDetectado
            }
            tipoFinal = LucyTipoCorreccionAprendida.LECTURA_VISION
        }

        val dudaActual = synchronized(historyLock) { pendingDoubtsPorTurno[turnoReal]?.firstOrNull() }
        if (textoErroneo.isNullOrBlank()) {
            val ctxErr = synchronized(historyLock) { ultimoContextoCorreccionPorTurno[turnoReal] }
            textoErroneo = dudaActual?.nombreSolicitado ?: ctxErr?.textoErroneo
            if (ctxErr != null) {
                tipoFinal = ctxErr.tipo
            }
        }

        if (textoErroneo.isNullOrBlank()) {
            return LucyResponse(
                turnoContexto = turnoReal,
                texto = "Indícame qué nombre o lectura errónea quieres que asocie con ${trabajador.nombreCompleto}.",
                esErrorRecuperable = true
            )
        }

        guardarCorreccionSinFallo(
            LucyCorreccionAprendida(
                tipo = tipoFinal,
                turno = turnoReal,
                empresaCodigo = trabajador.empresa.codigo,
                textoErroneo = textoErroneo,
                claveErroneaNorm = LucyMemoryStorageSerializer.normalizarClaveCorreccion(textoErroneo),
                trabajadorId = trabajador.id,
                valorCorregido = trabajador.nombreCompleto
            )
        )
        recordarTrabajadorSinFallo(turnoReal, trabajador.id)
        recordarEmpresaSinFallo(turnoReal, trabajador.empresa)

        // Si había una duda pendiente sobre ese nombre, marcarlo ahora mismo y sacar la duda de la cola
        var marcoDuda = false
        var restantes: List<LucyPendingDoubt> = emptyList()
        if (dudaActual != null &&
            LucyMemoryStorageSerializer.normalizarClaveCorreccion(dudaActual.nombreSolicitado) ==
            LucyMemoryStorageSerializer.normalizarClaveCorreccion(textoErroneo)
        ) {
            val origenDb = if (origen == LucyOperationOrigin.VOICE) "VOZ" else "LUCY"
            repository.marcarTrabajadoresSiBajoEnLote(
                turno = turnoReal,
                trabajadores = listOf(trabajador),
                origen = origenDb
            )
            marcoDuda = true
            restantes = synchronized(historyLock) {
                val cola = pendingDoubtsPorTurno[turnoReal]
                if (cola != null && cola.isNotEmpty()) cola.removeAt(0)
                cola?.toList().orEmpty()
            }
        }

        val msgBase = if (marcoDuda) {
            "Aprendido: \"$textoErroneo\" es ${trabajador.nombreCompleto}. También quedó marcado como SÍ SUBIÓ."
        } else {
            "Aprendido: cuando aparezca \"$textoErroneo\" corresponde a ${trabajador.nombreCompleto}."
        }
        return LucyResponse(
            turnoContexto = turnoReal,
            texto = formatearRespuestaDudaResuelta(msgBase, restantes),
            trabajadoresRelacionados = listOf(trabajador)
        )
    }

    private fun responderConflictoTurno(
        turnoActivo: Turno,
        turnoSolicitado: Turno,
        query: String
    ): LucyResponse {
        return LucyResponse(
            turnoContexto = turnoActivo,
            texto = "Estás en ${turnoActivo.nombre}, pero pediste modificar a \"$query\" en ${turnoSolicitado.nombre}. Por seguridad no se aplicará sobre el turno actual; cambia primero a ${turnoSolicitado.nombre} (por ejemplo: \"cambia a ${turnoSolicitado.nombre}\").",
            esErrorRecuperable = true
        )
    }

    private suspend fun evaluarModuloSeguro(
        moduleId: LucyModuleId,
        check: suspend () -> LucyModuleHealth
    ): LucyModuleHealth {
        return try {
            check()
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            val motivo = le.message ?: le::class.java.simpleName
            LucyModuleHealth.Unavailable("Fallo nativo aislado en $moduleId: $motivo")
        } catch (e: Exception) {
            val motivo = e.message ?: e::class.java.simpleName
            LucyModuleHealth.Unavailable("Fallo aislado en $moduleId: $motivo")
        }
    }

    private suspend fun ejecutarProtegido(
        turnoFallback: Turno,
        bloque: suspend () -> LucyResponse
    ): LucyResponse {
        return try {
            val res = withContext(ioDispatcher) { bloque() }
            actualizarSalud(LucyModuleId.CORE, LucyModuleHealth.Ready)
            res
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (e: Exception) {
            val detalle = e.message ?: e::class.java.simpleName
            actualizarSalud(LucyModuleId.CORE, LucyModuleHealth.Degraded(detalle))
            LucyResponse(
                turnoContexto = turnoFallback,
                texto = "Ocurrió un error controlado al procesar la solicitud ($detalle). Los datos reales permanecen intactos.",
                esErrorRecuperable = true
            )
        }
    }

    private suspend fun ejecutarConfiguracionPreferencia(
        intent: LucyIntent.ConfigurarPreferenciaConversacion,
        turnoContexto: Turno
    ): LucyResponse {
        return try {
            val mensajes = mutableListOf<String>()
            var huboError = false

            intent.velocidad?.let { vel ->
                val res = memoryPort.actualizarVelocidadRespuesta(vel)
                if (res.estado == LucyEstadoEscrituraMemoria.ERROR || res.estado == LucyEstadoEscrituraMemoria.NO_DISPONIBLE) {
                    huboError = true
                }
                mensajes.add(res.detalle.ifBlank { "Velocidad: ${vel.etiqueta}." })
            }
            intent.estilo?.let { estilo ->
                val res = memoryPort.actualizarEstiloRespuesta(estilo)
                if (res.estado == LucyEstadoEscrituraMemoria.ERROR || res.estado == LucyEstadoEscrituraMemoria.NO_DISPONIBLE) {
                    huboError = true
                }
                mensajes.add(res.detalle.ifBlank { "Estilo: ${estilo.etiqueta}." })
            }
            intent.estiloVoz?.let { estiloVoz ->
                val res = memoryPort.actualizarEstiloVoz(estiloVoz)
                if (res.estado == LucyEstadoEscrituraMemoria.ERROR || res.estado == LucyEstadoEscrituraMemoria.NO_DISPONIBLE) {
                    huboError = true
                }
                mensajes.add(res.detalle.ifBlank { "Estilo de voz: ${estiloVoz.etiqueta}." })
            }
            intent.instruccionPermanente?.let { instr ->
                val res = memoryPort.guardarInstruccionPermanente(instr)
                if (res.estado == LucyEstadoEscrituraMemoria.ERROR || res.estado == LucyEstadoEscrituraMemoria.NO_DISPONIBLE) {
                    huboError = true
                }
                mensajes.add(res.detalle.ifBlank { "Instrucción guardada." })
            }

            if (huboError) {
                actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded("Fallo al guardar preferencias"))
                LucyResponse(
                    turnoContexto = turnoContexto,
                    texto = mensajes.firstOrNull { it.isNotBlank() } ?: "No se pudieron guardar las preferencias de memoria.",
                    esErrorRecuperable = true
                )
            } else {
                actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Ready)
                aplicarPreferenciasVozSinFallo(obtenerPreferenciasSeguras())
                LucyResponse(
                    turnoContexto = turnoContexto,
                    texto = mensajes.joinToString(" ").ifBlank { "Preferencia actualizada." }
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(le.message ?: "Error nativo en Memory"))
            LucyResponse(
                turnoContexto = turnoContexto,
                texto = "No pude guardar la preferencia por un error controlado en memoria.",
                esErrorRecuperable = true
            )
        } catch (e: Exception) {
            actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(e.message ?: "Error en Memory"))
            LucyResponse(
                turnoContexto = turnoContexto,
                texto = "No pude guardar la preferencia por un error controlado en memoria.",
                esErrorRecuperable = true
            )
        }
    }

    private suspend fun ejecutarGuardarNotaOperativa(
        intent: LucyIntent.GuardarNotaOperativa,
        contexto: LucyTurnContext
    ): LucyResponse {
        val turnoDestino = intent.turnoDestino ?: contexto.turnoActivo
        val empresaResuelta = intent.empresa ?: if (intent.usarEmpresaAnterior) {
            contexto.empresaActiva ?: resolverUltimaEmpresaSegura(contexto.turnoActivo)
        } else {
            null
        }

        if (intent.usarEmpresaAnterior && empresaResuelta == null) {
            return LucyResponse(
                turnoContexto = contexto.turnoActivo,
                texto = "No sé a qué empresa te refieres; indica el nombre de la empresa o consúltala primero en ${contexto.turnoActivo.nombre}.",
                esErrorRecuperable = true
            )
        }

        val textoFinal = if (empresaResuelta != null && intent.usarEmpresaAnterior) {
            intent.texto.replace(
                Regex("\\b(?:la\\s+empresa\\s+anterior|esa\\s+empresa|esta\\s+empresa|la\\s+misma\\s+empresa)\\b", RegexOption.IGNORE_CASE),
                empresaResuelta.nombre
            )
        } else {
            intent.texto
        }

        return try {
            val res = memoryPort.guardarNotaOperativa(
                turnoDestino = turnoDestino,
                texto = textoFinal,
                empresa = empresaResuelta
            )
            if (res.estado == LucyEstadoEscrituraMemoria.ERROR || res.estado == LucyEstadoEscrituraMemoria.NO_DISPONIBLE) {
                actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(res.detalle.ifBlank { "Error al guardar nota" }))
                LucyResponse(
                    turnoContexto = contexto.turnoActivo,
                    texto = res.detalle.ifBlank { "No pude guardar el recuerdo." },
                    esErrorRecuperable = true
                )
            } else {
                if (empresaResuelta != null && turnoDestino == contexto.turnoActivo) {
                    recordarEmpresaSinFallo(turnoDestino, empresaResuelta)
                }
                actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Ready)
                val prefijoEmpresa = empresaResuelta?.let { " (${it.nombre})" } ?: ""
                val sufijoEstado = when (res.estado) {
                    LucyEstadoEscrituraMemoria.ACTUALIZADO -> " [actualizado]"
                    LucyEstadoEscrituraMemoria.YA_EXISTIA -> " [ya estaba guardado]"
                    else -> ""
                }
                LucyResponse(
                    turnoContexto = contexto.turnoActivo,
                    texto = "Guardado en ${turnoDestino.nombre}$prefijoEmpresa$sufijoEstado: \"$textoFinal\"."
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(le.message ?: "Error nativo en Memory"))
            LucyResponse(
                turnoContexto = contexto.turnoActivo,
                texto = "Ocurrió un error controlado al guardar el recuerdo.",
                esErrorRecuperable = true
            )
        } catch (e: Exception) {
            actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(e.message ?: "Error en Memory"))
            LucyResponse(
                turnoContexto = contexto.turnoActivo,
                texto = "Ocurrió un error controlado al guardar el recuerdo.",
                esErrorRecuperable = true
            )
        }
    }

    private suspend fun ejecutarConsultarMemoria(
        intent: LucyIntent.ConsultarMemoria,
        contexto: LucyTurnContext
    ): LucyResponse {
        val turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
        if (intent.soloPreferencias) {
            val prefs = obtenerPreferenciasSeguras()
            val texto = buildString {
                append("Preferencias activas: velocidad ${prefs.velocidad.etiqueta}, estilo ${prefs.estilo.etiqueta}, voz ${prefs.estiloVoz.etiqueta}.")
                if (prefs.instruccionesPermanentes.isEmpty()) {
                    append(" Sin instrucciones permanentes adicionales.")
                } else {
                    append("\nInstrucciones permanentes:")
                    prefs.instruccionesPermanentes.forEach { instr ->
                        append("\n• ${instr.texto}")
                    }
                }
            }
            return LucyResponse(turnoContexto = turnoObjetivo, texto = texto)
        }

        val empresaResuelta = intent.empresa ?: if (intent.usarEmpresaAnterior) {
            contexto.empresaActiva ?: resolverUltimaEmpresaSegura(turnoObjetivo)
        } else {
            null
        }
        if (intent.usarEmpresaAnterior && empresaResuelta == null) {
            return LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "No hay una empresa reciente recordada en ${turnoObjetivo.nombre}.",
                esErrorRecuperable = true
            )
        }

        val notas = consultarNotasSeguras(
            turno = turnoObjetivo,
            empresa = empresaResuelta,
            query = intent.query
        )
        if (notas.isEmpty()) {
            val ambito = empresaResuelta?.let { "${it.nombre} en ${turnoObjetivo.nombre}" } ?: turnoObjetivo.nombre
            return LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "No tengo recuerdos guardados sobre $ambito."
            )
        }

        val ambito = empresaResuelta?.let { "${it.nombre} (${turnoObjetivo.nombre})" } ?: turnoObjetivo.nombre
        val texto = buildString {
            append("Recuerdos en $ambito:")
            notas.forEach { nota ->
                val tagEmp = if (empresaResuelta == null && nota.empresaCodigo != null) {
                    EmpresaOficial.porCodigo(nota.empresaCodigo)?.nombre?.let { " [$it]" } ?: ""
                } else ""
                append("\n• ${nota.texto}$tagEmp")
            }
        }
        return LucyResponse(turnoContexto = turnoObjetivo, texto = texto)
    }

    private suspend fun ejecutarOlvidarMemoria(
        intent: LucyIntent.OlvidarMemoria,
        contexto: LucyTurnContext
    ): LucyResponse {
        val turnoObjetivo = intent.turnoSolicitado ?: contexto.turnoActivo
        return try {
            val res = when (intent.tipo) {
                LucyTipoOlvidoMemoria.ULTIMA_PREFERENCIA -> memoryPort.olvidarUltimaPreferencia()
                LucyTipoOlvidoMemoria.TODAS_PREFERENCIAS -> memoryPort.olvidarTodasPreferencias()
                LucyTipoOlvidoMemoria.TODO_TURNO -> memoryPort.limpiarContextoTurno(turnoObjetivo)
                LucyTipoOlvidoMemoria.NOTA_O_RECUERDO -> {
                    val empresaResuelta = intent.empresa ?: if (intent.usarEmpresaAnterior) {
                        contexto.empresaActiva ?: resolverUltimaEmpresaSegura(turnoObjetivo)
                    } else {
                        null
                    }
                    if (intent.usarEmpresaAnterior && empresaResuelta == null) {
                        return LucyResponse(
                            turnoContexto = turnoObjetivo,
                            texto = "No hay una empresa reciente recordada en ${turnoObjetivo.nombre} para olvidar.",
                            esErrorRecuperable = true
                        )
                    }
                    memoryPort.olvidarNotas(
                        turno = turnoObjetivo,
                        empresa = empresaResuelta,
                        referenciaTexto = intent.referenciaTexto,
                        soloUltimaSiSinFiltro = true
                    )
                }
            }
            if (res.huboError) {
                actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(res.detalle.ifBlank { "Error al olvidar" }))
            } else {
                actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Ready)
                if (intent.tipo == LucyTipoOlvidoMemoria.ULTIMA_PREFERENCIA ||
                    intent.tipo == LucyTipoOlvidoMemoria.TODAS_PREFERENCIAS
                ) {
                    aplicarPreferenciasVozSinFallo(obtenerPreferenciasSeguras())
                }
            }
            LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = res.detalle.ifBlank { "Operación de olvido completada." },
                esErrorRecuperable = res.huboError
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(le.message ?: "Error nativo en Memory"))
            LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "Ocurrió un error controlado al eliminar el recuerdo.",
                esErrorRecuperable = true
            )
        } catch (e: Exception) {
            actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(e.message ?: "Error en Memory"))
            LucyResponse(
                turnoContexto = turnoObjetivo,
                texto = "Ocurrió un error controlado al eliminar el recuerdo.",
                esErrorRecuperable = true
            )
        }
    }

    private suspend fun resolverEmpresaParaOperacion(
        empresaExplicita: EmpresaOficial?,
        usarAnterior: Boolean,
        contexto: LucyTurnContext,
        turnoObjetivo: Turno
    ): EmpresaOficial? {
        if (empresaExplicita != null) return empresaExplicita
        if (usarAnterior) {
            return contexto.empresaActiva ?: resolverUltimaEmpresaSegura(turnoObjetivo)
        }
        return contexto.empresaActiva
    }

    private suspend fun obtenerPreferenciasSeguras(): LucyPreferenciasConversacion {
        return try {
            memoryPort.obtenerPreferencias()
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            LucyPreferenciasConversacion()
        } catch (_: Exception) {
            LucyPreferenciasConversacion()
        }
    }

    private suspend fun resolverUltimaEmpresaSegura(turno: Turno): EmpresaOficial? {
        return try {
            memoryPort.resolveLastReferencedEmpresa(turno)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolverUltimoTrabajadorSeguro(turno: Turno): String? {
        return try {
            memoryPort.resolveLastReferencedWorkerId(turno)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun consultarNotasSeguras(
        turno: Turno,
        empresa: EmpresaOficial?,
        query: String?
    ): List<LucyNotaMemoria> {
        return try {
            memoryPort.consultarNotasOperativas(turno, empresa, query)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(le.message ?: "Error en Memory"))
            emptyList()
        } catch (e: Exception) {
            actualizarSalud(LucyModuleId.MEMORY, LucyModuleHealth.Degraded(e.message ?: "Error en Memory"))
            emptyList()
        }
    }

    private suspend fun registrarMemoriaSinFallo(
        turno: Turno,
        entrada: String,
        respuesta: String
    ) {
        try {
            memoryPort.recordInteraction(turno, entrada, respuesta)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            actualizarSalud(
                LucyModuleId.MEMORY,
                LucyModuleHealth.Degraded(le.message ?: "Error nativo controlado en Memory")
            )
        } catch (e: Exception) {
            actualizarSalud(
                LucyModuleId.MEMORY,
                LucyModuleHealth.Degraded(e.message ?: "Error controlado en Memory")
            )
        }
    }

    private suspend fun recordarTrabajadorSinFallo(turno: Turno, trabajadorId: String) {
        try {
            memoryPort.rememberReferencedWorker(turno, trabajadorId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            // La memoria auxiliar nunca debe interrumpir consultas operativas
        } catch (_: Exception) {
            // La memoria auxiliar nunca debe interrumpir consultas operativas
        }
    }

    private suspend fun recordarEmpresaSinFallo(turno: Turno, empresa: EmpresaOficial) {
        try {
            memoryPort.recordarEmpresa(turno, empresa)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            // La memoria auxiliar nunca debe interrumpir consultas operativas
        } catch (_: Exception) {
            // La memoria auxiliar nunca debe interrumpir consultas operativas
        }
    }

    private suspend fun obtenerCorreccionesAprendidasSeguras(turno: Turno): List<LucyCorreccionAprendida> {
        return try {
            memoryPort.obtenerCorreccionesAprendidas(turno)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun guardarCorreccionSinFallo(correccion: LucyCorreccionAprendida) {
        try {
            memoryPort.guardarCorreccionAprendida(correccion)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            // La memoria auxiliar nunca debe bloquear marcaciones
        } catch (_: Exception) {
            // La memoria auxiliar nunca debe bloquear marcaciones
        }
    }

    private suspend fun registrarCambioMaestroSinFallo(cambio: LucyRegistroCambioMaestro) {
        try {
            memoryPort.registrarCambioMaestro(cambio)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            // La bitácora auxiliar nunca debe bloquear actualizaciones confirmadas
        } catch (_: Exception) {
            // La bitácora auxiliar nunca debe bloquear actualizaciones confirmadas
        }
    }

    private suspend fun obtenerHistorialCambiosSeguros(turno: Turno): List<LucyRegistroCambioMaestro> {
        return try {
            memoryPort.obtenerHistorialCambiosMaestro(turno)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun aplicarPreferenciasVozSinFallo(preferencias: LucyPreferenciasConversacion) {
        try {
            voicePort.applyPreferences(preferencias)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            actualizarSalud(
                LucyModuleId.VOICE,
                LucyModuleHealth.Degraded(le.message ?: "Error nativo controlado en Voice")
            )
        } catch (e: Exception) {
            actualizarSalud(
                LucyModuleId.VOICE,
                LucyModuleHealth.Degraded(e.message ?: "Error controlado en Voice")
            )
        }
    }

    private suspend fun hablarRespuestaSinFallo(texto: String) {
        if (texto.isBlank()) return
        try {
            voicePort.speakIfEnabled(texto)
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            actualizarSalud(
                LucyModuleId.VOICE,
                LucyModuleHealth.Degraded(le.message ?: "Error nativo controlado en Voice")
            )
        } catch (e: Exception) {
            actualizarSalud(
                LucyModuleId.VOICE,
                LucyModuleHealth.Degraded(e.message ?: "Error controlado en Voice")
            )
        }
    }

    private fun actualizarSalud(modulo: LucyModuleId, salud: LucyModuleHealth) {
        _saludModulos.update { actual -> actual + (modulo to salud) }
    }

    private suspend fun intentarProcesarConGeminiBrain(
        entrada: String,
        contexto: LucyTurnContext,
        origen: LucyOperationOrigin,
        hayBatchActivo: Boolean,
        inicioEnvioMs: Long = System.currentTimeMillis(),
        onPartialText: ((String) -> Unit)? = null
    ): LucyResponse? = coroutineScope {
        val limpio = entrada.trim()
        if (limpio.isBlank()) return@coroutineScope null

        val snapshotHistorial = synchronized(historyLock) {
            historialConversacionalPorTurno[contexto.turnoActivo].orEmpty().takeLast(5).toList()
        }
        val recentHistory = snapshotHistorial.map {
            LucyGeminiBrainExchange(
                userText = it.userText,
                assistantText = it.assistantText
            )
        }
        val dudasResumen = synchronized(historyLock) {
            pendingDoubtsPorTurno[contexto.turnoActivo]
                .orEmpty()
                .map { "${it.nombreSolicitado} (${it.tipo.name})" }
        }

        // Preparación de contexto en paralelo para no sumar latencia antes de enviar a Gemini
        val prefsDeferred = async { obtenerPreferenciasSeguras() }
        val empresaDeferred = async {
            contexto.empresaActiva ?: resolverUltimaEmpresaSegura(contexto.turnoActivo)
        }
        val workerIdDeferred = async { resolverUltimoTrabajadorSeguro(contexto.turnoActivo) }

        val prefs = prefsDeferred.await()
        val ultimaEmpresaNombre = empresaDeferred.await()?.nombre
        val ultimoWorkerId = workerIdDeferred.await()
        val ultimoTrabajadorResumen = if (ultimoWorkerId != null) {
            repository.observeTrabajadoresConEstado(contexto.turnoActivo).first()
                .firstOrNull { it.id == ultimoWorkerId }
                ?.let { w ->
                    val estado = if (w.siBajo) "SÍ BAJÓ" else "NO BAJÓ"
                    "${w.nombreCompleto} (${w.empresa.nombre}, ${w.ubicacionTexto}, $estado)"
                }
        } else null

        val sideEffects = LucyGeminiToolSideEffects(initialTurno = contexto.turnoActivo)
        val executor = LucyGeminiToolsBridge.createToolExecutor(
            ops = crearGeminiCoordinatorOps(),
            userInputOriginal = limpio,
            contexto = contexto,
            origen = origen,
            sideEffects = sideEffects
        )

        val visionActiva = synchronized(historyLock) {
            ultimoAnalisisVisionPorTurno[contexto.turnoActivo]
        }
        val hayPlanillaVisionActiva = visionActiva?.parsedSheet != null
        val planillaVisionResumen = if (visionActiva != null && visionActiva.parsedSheet != null) {
            val filtroActual = visionActiva.empresaFiltroAplicada?.nombre
                ?: visionActiva.empresaDocumentoInferida?.nombre
                ?: "Sin filtro fijo"
            val nombresEjemplo = visionActiva.trabajadoresDetectados
                .take(6)
                .joinToString(", ") { it.nombreDetectado }
            "${visionActiva.trabajadoresDetectados.size} fila(s) extraída(s) (${visionActiva.estadoDocumento.etiqueta}, empresa/filtro actual: $filtroActual; nombres: $nombresEjemplo)"
        } else null

        var firstResponseMs = -1L
        val outcome = geminiBrainGateway.generateTurnReply(
            LucyGeminiBrainRequest(
                userInput = limpio,
                turnoActivo = contexto.turnoActivo,
                empresaActiva = contexto.empresaActiva,
                recentHistory = recentHistory,
                preferences = prefs,
                hayBatchActivo = hayBatchActivo,
                dudasPendientesResumen = dudasResumen,
                ultimoTrabajadorResumen = ultimoTrabajadorResumen,
                ultimaEmpresaNombre = ultimaEmpresaNombre,
                hayPlanillaVisionActiva = hayPlanillaVisionActiva,
                planillaVisionResumen = planillaVisionResumen,
                toolExecutor = executor,
                onPartialText = onPartialText,
                onFirstToken = { ttft ->
                    if (firstResponseMs < 0L) {
                        firstResponseMs = ttft
                    }
                }
            )
        )

        when (outcome) {
            is LucyGeminiBrainOutcome.FallbackToOffline -> null
            is LucyGeminiBrainOutcome.Success -> {
                val textoFinal = outcome.replyText.ifBlank {
                    sideEffects.lastDirectResponseText.orEmpty()
                }
                if (textoFinal.isBlank()) return@coroutineScope null

                val trabajadores = sideEffects.trabajadoresRelacionados.toList()
                if (trabajadores.size == 1) {
                    recordarTrabajadorSinFallo(sideEffects.turnoFinal, trabajadores.first().id)
                }
                val empresaFinal = sideEffects.referencedCompany
                    ?: sideEffects.visionAnalysis?.empresaFiltroAplicada
                    ?: trabajadores.firstOrNull()?.empresa
                    ?: contexto.empresaActiva
                if (empresaFinal != null) {
                    recordarEmpresaSinFallo(sideEffects.turnoFinal, empresaFinal)
                }

                val totalMs = (System.currentTimeMillis() - inicioEnvioMs).coerceAtLeast(0L)
                val effectiveFirst = when {
                    outcome.firstTokenLatencyMs > 0L -> outcome.firstTokenLatencyMs
                    firstResponseMs >= 0L -> firstResponseMs
                    else -> totalMs
                }

                val respuesta = LucyResponse(
                    turnoContexto = sideEffects.turnoFinal,
                    texto = textoFinal,
                    trabajadoresRelacionados = trabajadores,
                    candidatosDesambiguacion = sideEffects.candidatosDesambiguacion.toList(),
                    accionDesambiguacionSiBajo = sideEffects.accionDesambiguacionSiBajo,
                    confirmacionRequerida = sideEffects.confirmacionRequerida,
                    visionAnalysis = sideEffects.visionAnalysis,
                    esErrorRecuperable = sideEffects.esErrorRecuperable,
                    modoEjecucion = LucyExecutionMode.HYBRID_CLOUD_OPTIONAL,
                    performanceTrace = LucyPerformanceTrace(
                        sendTimestampMs = inicioEnvioMs,
                        firstResponseLatencyMs = effectiveFirst,
                        totalResponseLatencyMs = totalMs,
                        usedLocalFastPath = false,
                        modelUsed = outcome.modelName
                    )
                )

                val exchange = LucyTurnExchange(
                    userText = limpio,
                    assistantText = textoFinal,
                    topicSummary = buildString {
                        append(sideEffects.lastActionType)
                        trabajadores.firstOrNull()?.let { append(" (${it.nombreCompleto})") }
                        if (trabajadores.isEmpty() && empresaFinal != null) {
                            append(" (${empresaFinal.nombre})")
                        }
                    },
                    isQuestion = limpio.contains('?') || limpio.contains('¿'),
                    isMetaConversationQuery = false,
                    referencedWorkerName = trabajadores.firstOrNull()?.nombreCompleto,
                    referencedCompany = empresaFinal,
                    actionType = sideEffects.lastActionType
                )
                agregarExchangeEnBuffer(sideEffects.turnoFinal, exchange)
                if (sideEffects.turnoFinal != contexto.turnoActivo) {
                    agregarExchangeEnBuffer(contexto.turnoActivo, exchange)
                }
                respuesta
            }
        }
    }

    private fun crearGeminiCoordinatorOps(): LucyGeminiCoordinatorOps = object : LucyGeminiCoordinatorOps {
        override suspend fun buscarTrabajador(
            query: String,
            turno: Turno,
            empresa: EmpresaOficial?,
            usarAnterior: Boolean
        ): LucyResponse = resolverBusqueda(
            query = query,
            turnoObjetivo = turno,
            empresa = empresa,
            usarTrabajadorAnterior = usarAnterior,
            esMencionPasiva = false
        )

        override suspend fun consultarEstadoTrabajador(
            query: String,
            turno: Turno,
            empresa: EmpresaOficial?,
            usarAnterior: Boolean
        ): LucyResponse = resolverEstadoTrabajador(
            query = query,
            turnoObjetivo = turno,
            empresa = empresa,
            usarTrabajadorAnterior = usarAnterior,
            respuestaBreve = false
        )

        override suspend fun resumenTurno(turno: Turno, empresa: EmpresaOficial?): LucyResponse =
            resolverResumen(turnoObjetivo = turno, empresa = empresa)

        override suspend fun listarPendientes(turno: Turno, empresa: EmpresaOficial?): LucyResponse =
            resolverPendientes(turnoObjetivo = turno, empresa = empresa)

        override suspend fun listarEmpresas(turno: Turno, empresa: EmpresaOficial?): LucyResponse =
            resolverEmpresas(turnoObjetivo = turno, empresa = empresa)

        override suspend fun listarTrabajadores(turno: Turno, empresa: EmpresaOficial?): LucyResponse =
            resolverListadoTrabajadores(turnoObjetivo = turno, empresa = empresa)

        override suspend fun marcarSubieronLote(
            nombresTexto: String,
            empresa: EmpresaOficial?,
            esContinuacion: Boolean,
            incluirUbicacion: Boolean,
            contexto: LucyTurnContext,
            origen: LucyOperationOrigin
        ): LucyResponse = ejecutarMarcacionMultiple(
            intent = LucyIntent.MarcacionMultiple(
                nombresTexto = nombresTexto,
                empresa = empresa,
                esContinuacion = esContinuacion,
                incluirUbicacion = incluirUbicacion
            ),
            contexto = contexto,
            origen = origen
        )

        override suspend fun prepararMarcacionIndividual(
            query: String,
            marcarSiBajo: Boolean,
            empresa: EmpresaOficial?,
            usarAnterior: Boolean,
            contexto: LucyTurnContext
        ): LucyResponse = prepararConfirmacionMarcacion(
            query = query,
            marcarComoSiBajo = marcarSiBajo,
            turnoActivo = contexto.turnoActivo,
            empresa = empresa,
            usarTrabajadorAnterior = usarAnterior
        )

        override suspend fun resolverDudaPendiente(
            respuesta: String,
            empresa: EmpresaOficial?,
            dejarPendiente: Boolean,
            forzarMarcacion: Boolean,
            contexto: LucyTurnContext,
            origen: LucyOperationOrigin
        ): LucyResponse = ejecutarResolverDudaPendiente(
            intent = LucyIntent.ResolverDudaPendiente(
                textoRespuesta = respuesta,
                empresa = empresa,
                dejarPendiente = dejarPendiente,
                forzarMarcacion = forzarMarcacion
            ),
            contexto = contexto,
            origen = origen
        )

        override suspend fun actualizarDatoTrabajador(
            nombreTrabajador: String,
            usarAnterior: Boolean,
            nuevaHab: String?,
            nuevoPab: String?,
            nuevaEmpresa: EmpresaOficial?,
            textoOriginal: String,
            contexto: LucyTurnContext,
            origen: LucyOperationOrigin
        ): LucyResponse = ejecutarActualizacionDatoMaestro(
            intent = LucyIntent.ActualizarDatoTrabajador(
                nombreTrabajador = nombreTrabajador,
                usarTrabajadorAnterior = usarAnterior,
                nuevaHabitacion = nuevaHab,
                nuevoPabellon = nuevoPab,
                nuevaEmpresa = nuevaEmpresa,
                textoOriginal = textoOriginal
            ),
            contexto = contexto,
            origen = origen
        )

        override suspend fun cambiarTurno(nuevoTurno: Turno, contexto: LucyTurnContext): LucyResponse =
            ejecutarIntent(
                intent = LucyIntent.CambiarTurno(nuevoTurno),
                contexto = contexto,
                origen = LucyOperationOrigin.SYSTEM
            )

        override suspend fun configurarPreferencias(
            velocidad: LucyVelocidadRespuesta?,
            estilo: LucyEstiloRespuesta?,
            estiloVoz: LucyEstiloVoz?,
            instruccion: String?,
            turno: Turno
        ): LucyResponse = ejecutarConfiguracionPreferencia(
            intent = LucyIntent.ConfigurarPreferenciaConversacion(
                velocidad = velocidad,
                estilo = estilo,
                estiloVoz = estiloVoz,
                instruccionPermanente = instruccion
            ),
            turnoContexto = turno
        )

        override suspend fun guardarNota(
            texto: String,
            empresa: EmpresaOficial?,
            turno: Turno,
            contexto: LucyTurnContext
        ): LucyResponse = ejecutarGuardarNotaOperativa(
            intent = LucyIntent.GuardarNotaOperativa(
                texto = texto,
                empresa = empresa,
                turnoDestino = turno
            ),
            contexto = contexto
        )

        override suspend fun consultarMemoria(
            empresa: EmpresaOficial?,
            query: String?,
            soloPreferencias: Boolean,
            turno: Turno,
            contexto: LucyTurnContext
        ): LucyResponse = ejecutarConsultarMemoria(
            intent = LucyIntent.ConsultarMemoria(
                empresa = empresa,
                soloPreferencias = soloPreferencias,
                query = query,
                turnoSolicitado = turno
            ),
            contexto = contexto
        )

        override suspend fun olvidarMemoria(
            tipo: LucyTipoOlvidoMemoria,
            empresa: EmpresaOficial?,
            referencia: String?,
            turno: Turno,
            contexto: LucyTurnContext
        ): LucyResponse = ejecutarOlvidarMemoria(
            intent = LucyIntent.OlvidarMemoria(
                tipo = tipo,
                empresa = empresa,
                referenciaTexto = referencia,
                turnoSolicitado = turno
            ),
            contexto = contexto
        )

        override suspend fun consultarContextoCharla(
            tipo: String,
            turno: Turno,
            contexto: LucyTurnContext
        ): LucyResponse = when (tipo) {
            "ultima_pregunta" -> responderConversacionReciente(
                intent = LucyIntent.ConsultarConversacionReciente(
                    pideUltimaPregunta = true,
                    textoOriginal = "ultima_pregunta"
                ),
                turnoActivo = turno
            )
            "resumen_charla" -> responderConversacionReciente(
                intent = LucyIntent.ConsultarConversacionReciente(
                    pideResumenCharla = true,
                    textoOriginal = "resumen_charla"
                ),
                turnoActivo = turno
            )
            "historial_cambios" -> ejecutarConsultarHistorialCambios(
                intent = LucyIntent.ConsultarHistorialCambios(textoOriginal = "historial_cambios"),
                turnoActivo = turno
            )
            else -> responderContextoActual(contexto.copy(turnoActivo = turno))
        }

        override suspend fun filtrarPlanillaVisionPorEmpresa(
            empresaNombre: String,
            turno: Turno,
            contexto: LucyTurnContext
        ): LucyResponse = recorrelacionarPlanillaVisionPorEmpresa(
            empresaTexto = empresaNombre,
            empresaExplicita = resolverEmpresaVisionEstricta(empresaNombre),
            turnoObjetivo = turno
        )
    }
}
