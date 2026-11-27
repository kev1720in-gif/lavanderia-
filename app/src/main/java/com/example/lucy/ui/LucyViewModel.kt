package com.example.lucy.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.lucy.LucyAppContainer
import com.example.lucy.LucyDependenciesBundle
import com.example.lucy.core.LucyConversationEntry
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyModuleHealth
import com.example.lucy.core.LucyModuleId
import com.example.lucy.core.LucyOperationOrigin
import com.example.lucy.core.LucyPendingConfirmation
import com.example.lucy.core.LucyResponse
import com.example.lucy.core.LucyTurnContext
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.example.lucy.voice.AndroidLucyVoicePort
import com.example.lucy.voice.LucySttStartResult
import com.example.lucy.voice.LucySttState
import com.example.lucy.voice.LucyTtsState
import com.example.lucy.voice.LucyVoiceUiStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

@Immutable
enum class LucyOperationalStatus(val etiqueta: String) {
    LISTA("Lista"),
    ESCUCHANDO("Escuchando…"),
    PROCESANDO("Procesando…"),
    RESPONDIENDO("Respondiendo en voz…"),
    ERROR("Atención")
}

@Immutable
data class LucyCoreUiState(
    val turnoActivo: Turno = Turno.A,
    val historialTurnoActivo: List<LucyConversationEntry> = emptyList(),
    val confirmacionPendiente: LucyPendingConfirmation? = null,
    val candidatosDesambiguacion: List<TrabajadorConEstado> = emptyList(),
    val accionDesambiguacionSiBajo: Boolean = true,
    val procesando: Boolean = false,
    val saludModulos: Map<LucyModuleId, LucyModuleHealth> = emptyMap(),
    val ultimoErrorRecuperable: String? = null,
    val voiceStatus: LucyVoiceUiStatus = LucyVoiceUiStatus(),
    val estadoOperativo: LucyOperationalStatus = LucyOperationalStatus.LISTA,
    val fotoSeleccionada: LucySelectedPhotoPreview? = null,
    val validandoFoto: Boolean = false,
    val empresaContextoSeleccionada: EmpresaOficial? = null,
    val streamingReplyText: String? = null
)

class LucyViewModel(
    private val repository: LavanderiaRepository,
    val coordinator: LucyCoreCoordinator = LucyCoreCoordinator(repository),
    private val voicePort: AndroidLucyVoicePort? = null,
    private val appContext: Context? = null,
    private val onReleaseExtra: (() -> Unit)? = null
) : ViewModel() {

    private val historialPorTurnoFlow = MutableStateFlow<Map<Turno, List<LucyConversationEntry>>>(emptyMap())
    private val confirmacionPorTurnoFlow = MutableStateFlow<Map<Turno, LucyPendingConfirmation?>>(emptyMap())
    private val candidatosPorTurnoFlow = MutableStateFlow<Map<Turno, List<TrabajadorConEstado>>>(emptyMap())
    private val accionDesambiguacionPorTurnoFlow = MutableStateFlow<Map<Turno, Boolean>>(emptyMap())
    private val operacionesEnCursoFlow = MutableStateFlow(0)
    private val ultimoErrorPorTurnoFlow = MutableStateFlow<Map<Turno, String?>>(emptyMap())

    private val fotoSeleccionadaFlow = MutableStateFlow<LucySelectedPhotoPreview?>(null)
    private val validandoFotoFlow = MutableStateFlow(false)
    private val empresaContextoFlow = MutableStateFlow<EmpresaOficial?>(null)
    private val streamingReplyPorTurnoFlow = MutableStateFlow<Map<Turno, String?>>(emptyMap())

    private val operationMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var activeOperationJob: Job? = null

    @Volatile
    private var activeVoiceJob: Job? = null

    @Volatile
    private var lastVoicePrefJob: Job? = null

    @Volatile
    private var cleared: Boolean = false

    private val voiceStatusSource = voicePort?.voiceStatus ?: flowOf(LucyVoiceUiStatus())

    init {
        viewModelScope.launch {
            runCatching { coordinator.refrescarSaludModulos() }
        }
    }

    private val extrasCoreFlow = combine(
        confirmacionPorTurnoFlow,
        candidatosPorTurnoFlow,
        accionDesambiguacionPorTurnoFlow,
        operacionesEnCursoFlow.map { it > 0 },
        ultimoErrorPorTurnoFlow
    ) { confirmaciones, candidatos, accionesSiBajo, procesando, errores ->
        ExtrasUi(
            confirmaciones = confirmaciones,
            candidatos = candidatos,
            accionesSiBajo = accionesSiBajo,
            procesando = procesando,
            errores = errores
        )
    }

    private val extrasMediaVozFlow = combine(
        voiceStatusSource,
        fotoSeleccionadaFlow,
        validandoFotoFlow,
        empresaContextoFlow,
        streamingReplyPorTurnoFlow
    ) { voz, foto, validandoFoto, empresaCtx, streamingMap ->
        ExtrasMediaVozUi(
            voiceStatus = voz,
            fotoSeleccionada = foto,
            validandoFoto = validandoFoto,
            empresaContexto = empresaCtx,
            streamingByTurn = streamingMap
        )
    }

    val uiState: StateFlow<LucyCoreUiState> = combine(
        repository.turnoActivo,
        historialPorTurnoFlow,
        coordinator.saludModulos,
        extrasCoreFlow,
        extrasMediaVozFlow
    ) { turnoActivo, historialMap, salud, extras, mediaVoz ->
        val errorTurno = extras.errores[turnoActivo]
        val estadoOp = resolverEstadoOperativo(
            procesando = extras.procesando || mediaVoz.validandoFoto,
            sttState = mediaVoz.voiceStatus.sttState,
            ttsState = mediaVoz.voiceStatus.ttsState,
            errorTurno = errorTurno
        )
        LucyCoreUiState(
            turnoActivo = turnoActivo,
            historialTurnoActivo = historialMap[turnoActivo].orEmpty(),
            confirmacionPendiente = extras.confirmaciones[turnoActivo],
            candidatosDesambiguacion = extras.candidatos[turnoActivo].orEmpty(),
            accionDesambiguacionSiBajo = extras.accionesSiBajo[turnoActivo] ?: true,
            procesando = extras.procesando,
            saludModulos = salud,
            ultimoErrorRecuperable = errorTurno,
            voiceStatus = mediaVoz.voiceStatus,
            estadoOperativo = estadoOp,
            fotoSeleccionada = mediaVoz.fotoSeleccionada,
            validandoFoto = mediaVoz.validandoFoto,
            empresaContextoSeleccionada = mediaVoz.empresaContexto,
            streamingReplyText = mediaVoz.streamingByTurn[turnoActivo]
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = LucyCoreUiState()
    )

    fun refrescarEstadoModulos() {
        viewModelScope.launch {
            runCatching { coordinator.refrescarSaludModulos() }
        }
    }

    fun establecerEmpresaContexto(empresa: EmpresaOficial?) {
        empresaContextoFlow.value = empresa
    }

    fun enviarConsulta(
        texto: String,
        empresaActiva: EmpresaOficial? = empresaContextoFlow.value,
        origen: LucyOperationOrigin = LucyOperationOrigin.TEXT
    ) {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return

        ejecutarOperacionSerializada { turnoActual ->
            ultimoErrorPorTurnoFlow.update { it - turnoActual }
            confirmacionPorTurnoFlow.update { it - turnoActual }
            candidatosPorTurnoFlow.update { it - turnoActual }
            streamingReplyPorTurnoFlow.update { it - turnoActual }

            agregarEntrada(
                turnoActual,
                LucyConversationEntry(
                    turno = turnoActual,
                    esUsuario = true,
                    texto = limpio,
                    origen = origen
                )
            )

            val respuesta = coordinator.procesarEntrada(
                entrada = limpio,
                contexto = LucyTurnContext(
                    turnoActivo = turnoActual,
                    empresaActiva = empresaActiva
                ),
                origen = origen,
                hablarAlFinalizar = false,
                onPartialText = { partial ->
                    if (partial.isNotBlank()) {
                        streamingReplyPorTurnoFlow.update { it + (turnoActual to partial) }
                    }
                }
            )

            streamingReplyPorTurnoFlow.update { it - turnoActual }
            registrarRespuestaEnTurno(respuesta)
            if (origen == LucyOperationOrigin.VOICE || respuesta.texto.isNotBlank()) {
                lanzarReproduccionVozNoBloqueante(respuesta.texto, explicit = false)
            }
        }
    }

    fun cancelarOperacionActual() {
        val job = activeOperationJob
        if (job != null && job.isActive) {
            job.cancel()
        }
        activeOperationJob = null
        streamingReplyPorTurnoFlow.value = emptyMap()
        operacionesEnCursoFlow.value = 0
        viewModelScope.launch {
            val turnoActual = runCatching { repository.turnoActivo.first() }.getOrDefault(Turno.A)
            ultimoErrorPorTurnoFlow.update { it + (turnoActual to "Consulta cancelada por el usuario.") }
        }
    }

    fun actualizarEstadoPermisoMicrofono(granted: Boolean) {
        viewModelScope.launch {
            voicePort?.refreshAvailability()
            runCatching { coordinator.refrescarSaludModulos() }
            if (!granted) {
                val turnoActual = runCatching { repository.turnoActivo.first() }.getOrDefault(Turno.A)
                ultimoErrorPorTurnoFlow.update {
                    it + (turnoActual to "Permiso de micrófono denegado. Puedes continuar usando Lucy escribiendo o adjuntando planillas.")
                }
            }
        }
    }

    fun iniciarEscuchaVoz(preferOffline: Boolean = false) {
        val port = voicePort ?: run {
            viewModelScope.launch {
                val turnoActual = runCatching { repository.turnoActivo.first() }.getOrDefault(Turno.A)
                ultimoErrorPorTurnoFlow.update {
                    it + (turnoActual to "El módulo de voz no está conectado en esta instancia.")
                }
            }
            return
        }

        viewModelScope.launch {
            val turnoActual = runCatching { repository.turnoActivo.first() }.getOrDefault(Turno.A)
            ultimoErrorPorTurnoFlow.update { it - turnoActual }
            val result = port.startUserInitiatedListening(
                preferOffline = preferOffline,
                onFinalTranscript = { transcript ->
                    if (!cleared && transcript.isNotBlank()) {
                        enviarConsulta(
                            texto = transcript,
                            empresaActiva = empresaContextoFlow.value,
                            origen = LucyOperationOrigin.VOICE
                        )
                    }
                }
            )
            when (result) {
                is LucySttStartResult.Started,
                is LucySttStartResult.AlreadyListening -> Unit
                is LucySttStartResult.PermissionDenied -> {
                    ultimoErrorPorTurnoFlow.update {
                        it + (turnoActual to "Necesito permiso de micrófono para escucharte.")
                    }
                }
                is LucySttStartResult.ServiceUnavailable -> {
                    ultimoErrorPorTurnoFlow.update {
                        it + (turnoActual to result.reason)
                    }
                }
                is LucySttStartResult.Failed -> {
                    ultimoErrorPorTurnoFlow.update {
                        it + (turnoActual to result.reason)
                    }
                }
            }
            runCatching { coordinator.refrescarSaludModulos() }
        }
    }

    fun detenerEscuchaVoz() {
        viewModelScope.launch {
            voicePort?.stopListening()
        }
    }

    fun cancelarEscuchaVoz() {
        viewModelScope.launch {
            voicePort?.cancelListening()
        }
    }

    fun alternarSalidaVoz() {
        val port = voicePort ?: return
        val nuevoEstado = !port.isVoiceOutputEnabled()
        establecerSalidaVoz(nuevoEstado)
    }

    fun establecerSalidaVoz(enabled: Boolean) {
        val port = voicePort ?: return
        port.setVoiceOutputEnabled(enabled)
        if (!enabled) {
            viewModelScope.launch {
                port.stopSpeaking()
            }
        }
    }

    fun detenerLecturaVoz() {
        activeVoiceJob?.cancel()
        activeVoiceJob = null
        viewModelScope.launch {
            voicePort?.stopSpeaking()
        }
    }

    fun leerMensajeEnVoz(texto: String) {
        if (texto.isBlank()) return
        lanzarReproduccionVozNoBloqueante(texto, explicit = true)
    }

    fun seleccionarVozPreferida(voiceId: String) {
        val prevPrefJob = lastVoicePrefJob
        lastVoicePrefJob = viewModelScope.launch {
            prevPrefJob?.join()
            voicePort?.selectPreferredVoice(voiceId)
        }
    }

    fun seleccionarEstiloVoz(estiloVoz: LucyEstiloVoz) {
        val prevPrefJob = lastVoicePrefJob
        lastVoicePrefJob = viewModelScope.launch {
            prevPrefJob?.join()
            voicePort?.selectVoiceStyle(estiloVoz)
        }
    }

    fun seleccionarVelocidadVoz(velocidad: LucyVelocidadRespuesta) {
        val prevPrefJob = lastVoicePrefJob
        lastVoicePrefJob = viewModelScope.launch {
            prevPrefJob?.join()
            voicePort?.selectSpeechSpeed(velocidad)
        }
    }

    fun probarVozActual() {
        leerMensajeEnVoz("Hola, soy Lucy. Así suena mi voz.")
    }

    private fun lanzarReproduccionVozNoBloqueante(texto: String, explicit: Boolean) {
        val port = voicePort ?: return
        if (!explicit && !port.isVoiceOutputEnabled()) return
        activeVoiceJob?.cancel()
        activeVoiceJob = viewModelScope.launch {
            lastVoicePrefJob?.join()
            if (explicit) {
                val ok = port.speakExplicitly(texto, forceRepeat = true)
                if (!ok) {
                    val turnoActual = runCatching { repository.turnoActivo.first() }.getOrDefault(Turno.A)
                    ultimoErrorPorTurnoFlow.update {
                        it + (turnoActual to "No se pudo reproducir la respuesta en voz (verifica soporte TTS en español).")
                    }
                }
            } else {
                port.speakIfEnabled(texto)
            }
        }
    }

    fun seleccionarFotografiaUri(context: Context, uri: Uri) {
        val appCtx = context.applicationContext
        viewModelScope.launch {
            validandoFotoFlow.value = true
            val turnoActual = runCatching { repository.turnoActivo.first() }.getOrDefault(Turno.A)
            ultimoErrorPorTurnoFlow.update { it - turnoActual }
            try {
                when (val res = LucyPhotoIntake.prepareFromUri(appCtx, uri)) {
                    is LucyPhotoIntakeResult.Validated -> {
                        val anterior = fotoSeleccionadaFlow.value
                        if (anterior != null && anterior.tempFilePath != res.preview.tempFilePath) {
                            LucyPhotoIntake.deleteTempFileIfOwned(appCtx, anterior.tempFilePath)
                        }
                        fotoSeleccionadaFlow.value = res.preview
                    }
                    is LucyPhotoIntakeResult.Invalid -> {
                        ultimoErrorPorTurnoFlow.update { it + (turnoActual to res.reason) }
                    }
                }
            } finally {
                validandoFotoFlow.value = false
            }
        }
    }

    fun seleccionarFotografiaArchivo(
        context: Context,
        archivo: File,
        nombreMostrar: String = archivo.name
    ) {
        val appCtx = context.applicationContext
        viewModelScope.launch {
            validandoFotoFlow.value = true
            val turnoActual = runCatching { repository.turnoActivo.first() }.getOrDefault(Turno.A)
            ultimoErrorPorTurnoFlow.update { it - turnoActual }
            try {
                when (val res = LucyPhotoIntake.prepareFromExistingFile(appCtx, archivo, nombreMostrar)) {
                    is LucyPhotoIntakeResult.Validated -> {
                        val anterior = fotoSeleccionadaFlow.value
                        if (anterior != null && anterior.tempFilePath != res.preview.tempFilePath) {
                            LucyPhotoIntake.deleteTempFileIfOwned(appCtx, anterior.tempFilePath)
                        }
                        fotoSeleccionadaFlow.value = res.preview
                    }
                    is LucyPhotoIntakeResult.Invalid -> {
                        LucyPhotoIntake.deleteTempFileIfOwned(appCtx, archivo.absolutePath)
                        ultimoErrorPorTurnoFlow.update { it + (turnoActual to res.reason) }
                    }
                }
            } finally {
                validandoFotoFlow.value = false
            }
        }
    }

    fun adjuntarFotoDesdeCamara(context: Context, rutaArchivo: String) {
        val archivo = File(rutaArchivo)
        if (!archivo.exists() || archivo.length() <= 0L) {
            LucyPhotoIntake.deleteTempFileIfOwned(context.applicationContext, rutaArchivo)
            reportarErrorCamara("No se guardó ninguna fotografía desde la cámara.")
            return
        }
        seleccionarFotografiaArchivo(
            context = context,
            archivo = archivo,
            nombreMostrar = "Planilla cámara (${archivo.name})"
        )
    }

    fun descartarArchivoCamaraFallido(context: Context, rutaArchivo: String?) {
        if (!rutaArchivo.isNullOrBlank()) {
            LucyPhotoIntake.deleteTempFileIfOwned(context.applicationContext, rutaArchivo)
        }
    }

    fun reportarErrorCamara(mensaje: String) {
        if (mensaje.isBlank()) return
        viewModelScope.launch {
            val turnoActual = runCatching { repository.turnoActivo.first() }.getOrDefault(Turno.A)
            ultimoErrorPorTurnoFlow.update { it + (turnoActual to mensaje) }
        }
    }

    fun limpiarFotoSeleccionada() {
        val anterior = fotoSeleccionadaFlow.value
        fotoSeleccionadaFlow.value = null
        if (anterior != null && appContext != null) {
            LucyPhotoIntake.deleteTempFileIfOwned(appContext, anterior.tempFilePath)
        }
    }

    fun analizarFotografiaSeleccionada() {
        val foto = fotoSeleccionadaFlow.value ?: return
        ejecutarOperacionSerializada { turnoActual ->
            ultimoErrorPorTurnoFlow.update { it - turnoActual }
            confirmacionPorTurnoFlow.update { it - turnoActual }
            candidatosPorTurnoFlow.update { it - turnoActual }

            agregarEntrada(
                turnoActual,
                LucyConversationEntry(
                    turno = turnoActual,
                    esUsuario = true,
                    texto = "Analizar planilla adjunta: ${foto.displayName} (${foto.widthPx}×${foto.heightPx}, ${foto.sizeLabel})",
                    origen = LucyOperationOrigin.VISION
                )
            )

            val respuesta = coordinator.analizarPlanillaConLucyVision(
                imagePath = foto.tempFilePath,
                contexto = LucyTurnContext(
                    turnoActivo = turnoActual,
                    empresaActiva = empresaContextoFlow.value
                ),
                origen = LucyOperationOrigin.VISION,
                hablarAlFinalizar = false
            )

            registrarRespuestaEnTurno(respuesta)
            if (!respuesta.esErrorRecuperable) {
                limpiarFotoSeleccionada()
            }
            if (respuesta.texto.isNotBlank()) {
                lanzarReproduccionVozNoBloqueante(respuesta.texto, explicit = false)
            }
        }
    }

    fun seleccionarCandidatoDesambiguacion(
        trabajador: TrabajadorConEstado,
        marcarComoSiBajo: Boolean = accionDesambiguacionPorTurnoFlow.value[trabajador.turno] ?: true
    ) {
        ejecutarOperacionSerializada { turnoActual ->
            if (trabajador.turno != turnoActual) {
                ultimoErrorPorTurnoFlow.update {
                    it + (turnoActual to "El candidato pertenece a ${trabajador.turno.nombre}, pero el turno activo es ${turnoActual.nombre}.")
                }
                return@ejecutarOperacionSerializada
            }
            val estadoLabel = if (marcarComoSiBajo) "SÍ BAJÓ" else "NO BAJÓ"
            val confirmacion = LucyPendingConfirmation(
                turno = trabajador.turno,
                trabajador = trabajador,
                marcarComoSiBajo = marcarComoSiBajo,
                descripcion = "Marcar como $estadoLabel a ${trabajador.nombreCompleto} (${trabajador.empresa.nombre}, ${trabajador.ubicacionTexto}) en ${trabajador.turno.nombre}"
            )
            candidatosPorTurnoFlow.update { it - trabajador.turno }
            confirmacionPorTurnoFlow.update { it + (trabajador.turno to confirmacion) }
            agregarEntrada(
                trabajador.turno,
                LucyConversationEntry(
                    turno = trabajador.turno,
                    esUsuario = false,
                    texto = "¿Confirmas ${confirmacion.descripcion}?",
                    response = LucyResponse(
                        turnoContexto = trabajador.turno,
                        texto = "¿Confirmas ${confirmacion.descripcion}?",
                        trabajadoresRelacionados = listOf(trabajador),
                        confirmacionRequerida = confirmacion
                    )
                )
            )
        }
    }

    fun prepararConfirmacionDesdeVision(
        trabajador: TrabajadorConEstado,
        marcarComoSiBajo: Boolean
    ) {
        seleccionarCandidatoDesambiguacion(
            trabajador = trabajador,
            marcarComoSiBajo = marcarComoSiBajo
        )
    }

    fun confirmarAccionPendiente() {
        ejecutarOperacionSerializada { turnoActual ->
            val pendiente = confirmacionPorTurnoFlow.value[turnoActual] ?: return@ejecutarOperacionSerializada
            confirmacionPorTurnoFlow.update { it - pendiente.turno }
            val respuesta = coordinator.confirmarAccionPendiente(pendiente, hablarAlFinalizar = false)
            registrarRespuestaEnTurno(respuesta)
            if (respuesta.texto.isNotBlank()) {
                lanzarReproduccionVozNoBloqueante(respuesta.texto, explicit = false)
            }
        }
    }

    fun cancelarAccionPendiente() {
        ejecutarOperacionSerializada { turnoActual ->
            val pendiente = confirmacionPorTurnoFlow.value[turnoActual] ?: return@ejecutarOperacionSerializada
            confirmacionPorTurnoFlow.update { it - pendiente.turno }
            candidatosPorTurnoFlow.update { it - pendiente.turno }
            agregarEntrada(
                pendiente.turno,
                LucyConversationEntry(
                    turno = pendiente.turno,
                    esUsuario = false,
                    texto = "Acción cancelada. No se modificó a ${pendiente.trabajador.nombreCompleto}."
                )
            )
        }
    }

    fun limpiarHistorialTurnoActivo() {
        ejecutarOperacionSerializada { turnoActual ->
            historialPorTurnoFlow.update { actual -> actual - turnoActual }
            confirmacionPorTurnoFlow.update { it - turnoActual }
            candidatosPorTurnoFlow.update { it - turnoActual }
            ultimoErrorPorTurnoFlow.update { it - turnoActual }
            coordinator.limpiarContextoConversacional(turnoActual)
        }
    }

    fun limpiarError() {
        viewModelScope.launch {
            val turnoActual = runCatching { repository.turnoActivo.first() }.getOrDefault(Turno.A)
            ultimoErrorPorTurnoFlow.update { it - turnoActual }
        }
    }

    private fun ejecutarOperacionSerializada(
        bloque: suspend (Turno) -> Unit
    ) {
        operacionesEnCursoFlow.update { it + 1 }
        val job = viewModelScope.launch {
            var turnoOperacion: Turno? = null
            try {
                operationMutex.withLock {
                    val turnoActual = repository.turnoActivo.first()
                    turnoOperacion = turnoActual
                    bloque(turnoActual)
                }
            } catch (ce: CancellationException) {
                val turnoCancel = turnoOperacion
                if (turnoCancel != null) {
                    streamingReplyPorTurnoFlow.update { it - turnoCancel }
                }
                throw ce
            } catch (vme: VirtualMachineError) {
                throw vme
            } catch (e: Exception) {
                val turnoError = turnoOperacion ?: runCatching { repository.turnoActivo.first() }.getOrNull()
                if (turnoError != null) {
                    streamingReplyPorTurnoFlow.update { it - turnoError }
                    ultimoErrorPorTurnoFlow.update {
                        it + (turnoError to (e.message ?: "Error controlado en Lucy"))
                    }
                }
            } finally {
                operacionesEnCursoFlow.update { (it - 1).coerceAtLeast(0) }
            }
        }
        activeOperationJob = job
    }

    private fun registrarRespuestaEnTurno(respuesta: LucyResponse) {
        val turnoMsg = respuesta.turnoContexto
        confirmacionPorTurnoFlow.update { it + (turnoMsg to respuesta.confirmacionRequerida) }
        candidatosPorTurnoFlow.update { it + (turnoMsg to respuesta.candidatosDesambiguacion) }
        accionDesambiguacionPorTurnoFlow.update { it + (turnoMsg to respuesta.accionDesambiguacionSiBajo) }
        if (respuesta.esErrorRecuperable) {
            ultimoErrorPorTurnoFlow.update { it + (turnoMsg to respuesta.texto) }
        }
        agregarEntrada(
            turnoMsg,
            LucyConversationEntry(
                turno = turnoMsg,
                esUsuario = false,
                texto = respuesta.texto,
                response = respuesta
            )
        )
    }

    private fun agregarEntrada(turno: Turno, entry: LucyConversationEntry) {
        historialPorTurnoFlow.update { actual ->
            val lista = actual[turno].orEmpty() + entry
            actual + (turno to lista.takeLast(60))
        }
    }

    private fun resolverEstadoOperativo(
        procesando: Boolean,
        sttState: LucySttState,
        ttsState: LucyTtsState,
        errorTurno: String?
    ): LucyOperationalStatus = when {
        sttState is LucySttState.Listening -> LucyOperationalStatus.ESCUCHANDO
        procesando -> LucyOperationalStatus.PROCESANDO
        ttsState is LucyTtsState.Speaking -> LucyOperationalStatus.RESPONDIENDO
        !errorTurno.isNullOrBlank() || sttState is LucySttState.Error -> LucyOperationalStatus.ERROR
        else -> LucyOperationalStatus.LISTA
    }

    override fun onCleared() {
        cleared = true
        coordinator.shutdown()
        val fotoActual = fotoSeleccionadaFlow.value
        fotoSeleccionadaFlow.value = null
        if (fotoActual != null && appContext != null) {
            LucyPhotoIntake.deleteTempFileIfOwned(appContext, fotoActual.tempFilePath)
        }
        runCatching { onReleaseExtra?.invoke() }
        val port = voicePort
        if (port != null) {
            cleanupScope.launch {
                try {
                    port.release()
                } finally {
                    cleanupScope.cancel()
                }
            }
        } else {
            cleanupScope.cancel()
        }
        super.onCleared()
    }

    private data class ExtrasUi(
        val confirmaciones: Map<Turno, LucyPendingConfirmation?>,
        val candidatos: Map<Turno, List<TrabajadorConEstado>>,
        val accionesSiBajo: Map<Turno, Boolean>,
        val procesando: Boolean,
        val errores: Map<Turno, String?>
    )

    private data class ExtrasMediaVozUi(
        val voiceStatus: LucyVoiceUiStatus,
        val fotoSeleccionada: LucySelectedPhotoPreview?,
        val validandoFoto: Boolean,
        val empresaContexto: EmpresaOficial?,
        val streamingByTurn: Map<Turno, String?>
    )

    class Factory(
        private val repository: LavanderiaRepository,
        private val appContext: Context? = null,
        private val coordinatorFactory: ((LavanderiaRepository) -> LucyCoreCoordinator)? = null,
        private val bundleFactory: ((LavanderiaRepository) -> LucyDependenciesBundle)? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (bundleFactory != null) {
                val bundle = bundleFactory.invoke(repository)
                return LucyViewModel(
                    repository = repository,
                    coordinator = bundle.coordinator,
                    voicePort = bundle.voicePort,
                    appContext = appContext?.applicationContext
                ) as T
            }
            if (appContext != null && coordinatorFactory == null) {
                val bundle = LucyAppContainer.createDependencies(appContext.applicationContext, repository)
                return LucyViewModel(
                    repository = repository,
                    coordinator = bundle.coordinator,
                    voicePort = bundle.voicePort,
                    appContext = appContext.applicationContext
                ) as T
            }
            val coordinator = coordinatorFactory?.invoke(repository) ?: LucyCoreCoordinator(repository)
            return LucyViewModel(
                repository = repository,
                coordinator = coordinator,
                appContext = appContext?.applicationContext
            ) as T
        }
    }
}
