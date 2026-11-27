package com.example.lucy.vision

import android.content.Context
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.Turno
import com.example.lucy.core.LucyMemoryPort
import com.example.lucy.core.LucyModuleHealth
import com.example.lucy.core.LucyVisionOutcome
import com.example.lucy.core.LucyVisionPort
import com.example.lucy.core.NoOpLucyMemoryPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Implementación modular de [LucyVisionPort] respaldada por [LucyVisionModelGateway] (Firebase AI Logic / Gemini)
 * y cruce de solo lectura contra [LavanderiaRepository].
 *
 * Garantías de seguridad operativa:
 * - Solo analiza y propone candidatos: nunca escribe en Room ni realiza marcaciones.
 * - Nunca modifica trabajadores, empresas, copias de seguridad ni modelos OCR locales.
 * - Nunca trata una coincidencia visual como identidad confirmada.
 */
class FirebaseLucyVisionPort(
    private val repository: LavanderiaRepository,
    private val gateway: LucyVisionModelGateway,
    private val imageLoader: LucyVisionImageLoader = AndroidLucyVisionImageLoader(),
    private val config: LucyVisionConfig = LucyVisionConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val memoryPort: LucyMemoryPort = NoOpLucyMemoryPort
) : LucyVisionPort {

    override val providerName: String
        get() = gateway.providerName

    override suspend fun healthCheck(): LucyModuleHealth {
        return try {
            val status = gateway.checkStatus()
            when {
                !status.isConfigured -> LucyModuleHealth.Unavailable(status.statusMessage)
                !status.isNetworkAvailable -> LucyModuleHealth.Degraded(status.statusMessage)
                else -> LucyModuleHealth.Ready
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            LucyModuleHealth.Unavailable("Fallo nativo en Lucy Vision: ${le.message ?: le::class.java.simpleName}")
        } catch (e: Exception) {
            LucyModuleHealth.Unavailable("Error al verificar Lucy Vision: ${e.message ?: e::class.java.simpleName}")
        }
    }

    override suspend fun extractPlanillaLines(imagePath: String): LucyVisionOutcome {
        val turnoActual = try {
            withContext(ioDispatcher) { repository.turnoActivo.first() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (e: Exception) {
            return LucyVisionOutcome.Failed("No se pudo leer el turno activo: ${e.message ?: "error"}")
        }

        return when (val res = analyzePlanilla(imagePath, turnoActual, empresaSugerida = null)) {
            is LucyVisionOutcome.PlanillaAnalyzed -> {
                val lines = res.analysis.trabajadoresDetectados.map { it.datosReconocidos.textoCrudoFila }
                if (lines.isEmpty() && !res.analysis.esDocumentoOperativoValido) {
                    LucyVisionOutcome.Failed(
                        res.analysis.notasIlegibilidad.firstOrNull() ?: res.analysis.resumenGeneral
                    )
                } else {
                    LucyVisionOutcome.LinesExtracted(
                        lines = lines,
                        providerName = res.providerName
                    )
                }
            }
            is LucyVisionOutcome.LinesExtracted -> res
            is LucyVisionOutcome.NotAvailable -> res
            is LucyVisionOutcome.Failed -> res
        }
    }

    override suspend fun analyzePlanilla(
        imagePath: String,
        turnoContexto: Turno,
        empresaSugerida: EmpresaOficial?
    ): LucyVisionOutcome {
        val analisis = analizarPlanillaDetallada(
            imagePath = imagePath,
            turnoContexto = turnoContexto,
            empresaSugerida = empresaSugerida
        )
        return when (analisis.estadoDocumento) {
            LucyVisionDocumentStatus.NO_CONFIGURADO ->
                LucyVisionOutcome.NotAvailable(analisis.mensajeErrorUsuario ?: analisis.resumenGeneral)
            LucyVisionDocumentStatus.SIN_CONEXION,
            LucyVisionDocumentStatus.TIMEOUT,
            LucyVisionDocumentStatus.CUOTA_O_FACTURACION,
            LucyVisionDocumentStatus.ERROR_APP_CHECK,
            LucyVisionDocumentStatus.RESPUESTA_INVALIDA,
            LucyVisionDocumentStatus.ERROR_PROVEEDOR ->
                LucyVisionOutcome.Failed(analisis.mensajeErrorUsuario ?: analisis.resumenGeneral)
            LucyVisionDocumentStatus.LEGIBLE,
            LucyVisionDocumentStatus.PARCIALMENTE_LEGIBLE,
            LucyVisionDocumentStatus.ILEGIBLE ->
                LucyVisionOutcome.PlanillaAnalyzed(
                    analysis = analisis,
                    providerName = analisis.motorUtilizado
                )
        }
    }

    suspend fun analizarPlanillaDetallada(
        imagePath: String,
        turnoContexto: Turno,
        empresaSugerida: EmpresaOficial? = null
    ): LucyVisionPlanillaAnalysis = withContext(ioDispatcher) {
        try {
            val status = gateway.checkStatus()
            if (!status.isConfigured) {
                return@withContext errorAnalysis(
                    turnoContexto = turnoContexto,
                    status = LucyVisionDocumentStatus.NO_CONFIGURADO,
                    mensaje = status.statusMessage,
                    motor = status.activeModelName
                )
            }
            if (!status.isNetworkAvailable) {
                return@withContext errorAnalysis(
                    turnoContexto = turnoContexto,
                    status = LucyVisionDocumentStatus.SIN_CONEXION,
                    mensaje = status.statusMessage,
                    motor = status.activeModelName
                )
            }

            val payload = when (
                val loaded = imageLoader.loadPreparedImage(
                    imagePath = imagePath,
                    maxDimPx = config.maxImageDimPx,
                    jpegQuality = config.jpegQuality,
                    maxBytes = config.maxPayloadBytes
                )
            ) {
                is LucyVisionImageLoadResult.Loaded -> loaded.payload
                is LucyVisionImageLoadResult.Failed -> {
                    return@withContext errorAnalysis(
                        turnoContexto = turnoContexto,
                        status = LucyVisionDocumentStatus.ILEGIBLE,
                        mensaje = loaded.reason,
                        motor = gateway.providerName
                    )
                }
            }

            val rawResult = gateway.analyzeSheetImage(
                payload = payload,
                request = LucyVisionRequestContext(
                    turno = turnoContexto,
                    empresaSugerida = empresaSugerida,
                    preprocessingSummary = payload.preprocessingSummary,
                    segmentedRowsHint = payload.segmentedRowsHint
                )
            )

            when (rawResult) {
                is LucyVisionRawGatewayResult.Failure -> {
                    errorAnalysis(
                        turnoContexto = turnoContexto,
                        status = rawResult.status,
                        mensaje = rawResult.reason,
                        motor = rawResult.modelName
                    )
                }
                is LucyVisionRawGatewayResult.Success -> {
                    when (val parsed = LucyVisionResponseParser.parseStructuredJson(rawResult.rawJson)) {
                        is LucyVisionJsonParseResult.Invalid -> {
                            errorAnalysis(
                                turnoContexto = turnoContexto,
                                status = LucyVisionDocumentStatus.RESPUESTA_INVALIDA,
                                mensaje = parsed.reason,
                                motor = rawResult.modelName
                            )
                        }
                        is LucyVisionJsonParseResult.Valid -> {
                            // Lectura estricta de solo lectura del maestro del turno correspondiente
                            val trabajadoresTurno = repository.observeTrabajadoresConEstado(turnoContexto).first()
                            val correcciones = memoryPort.obtenerCorreccionesAprendidas(turnoContexto)
                            LucyVisionMatcher.correlacionarConMaestro(
                                parsed = parsed.sheet,
                                turnoContexto = turnoContexto,
                                empresaSugerida = empresaSugerida,
                                trabajadoresTurno = trabajadoresTurno,
                                motorUtilizado = "Firebase AI Logic (${rawResult.modelName})",
                                correccionesAprendidas = correcciones
                            )
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            errorAnalysis(
                turnoContexto = turnoContexto,
                status = LucyVisionDocumentStatus.ERROR_PROVEEDOR,
                mensaje = "Fallo nativo aislado en Lucy Vision: ${le.message ?: le::class.java.simpleName}",
                motor = gateway.providerName
            )
        } catch (e: Exception) {
            errorAnalysis(
                turnoContexto = turnoContexto,
                status = LucyVisionDocumentStatus.ERROR_PROVEEDOR,
                mensaje = "Error controlado en Lucy Vision: ${e.message ?: e::class.java.simpleName}",
                motor = gateway.providerName
            )
        }
    }

    private fun errorAnalysis(
        turnoContexto: Turno,
        status: LucyVisionDocumentStatus,
        mensaje: String,
        motor: String
    ): LucyVisionPlanillaAnalysis {
        return LucyVisionPlanillaAnalysis(
            turnoContexto = turnoContexto,
            estadoDocumento = status,
            motorUtilizado = motor,
            esDocumentoOperativoValido = false,
            resumenGeneral = "${status.etiqueta}: $mensaje",
            trabajadoresDetectados = emptyList(),
            posiblesDuplicados = emptyList(),
            dudasGlobales = listOf(mensaje),
            notasIlegibilidad = if (status == LucyVisionDocumentStatus.ILEGIBLE) listOf(mensaje) else emptyList(),
            requiereRevisionHumana = true,
            esResultadoPropuestoNoConfirmado = true,
            mensajeErrorUsuario = mensaje
        )
    }

    companion object {
        fun createForApp(
            context: Context,
            repository: LavanderiaRepository,
            config: LucyVisionConfig = LucyVisionConfig(),
            memoryPort: LucyMemoryPort = NoOpLucyMemoryPort
        ): FirebaseLucyVisionPort {
            return FirebaseLucyVisionPort(
                repository = repository,
                gateway = FirebaseAiLucyVisionGateway(context = context, config = config),
                imageLoader = AndroidLucyVisionImageLoader(),
                config = config,
                memoryPort = memoryPort
            )
        }
    }
}
