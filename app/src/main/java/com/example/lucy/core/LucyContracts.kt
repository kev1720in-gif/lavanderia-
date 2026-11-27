package com.example.lucy.core

import androidx.compose.runtime.Immutable
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.lucy.memory.LucyCampoMaestroEditado
import com.example.lucy.memory.LucyCorreccionAprendida
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyNotaMemoria
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.memory.LucyRegistroCambioMaestro
import com.example.lucy.memory.LucyResumenMemoria
import com.example.lucy.memory.LucyTipoCorreccionAprendida
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.example.lucy.vision.LucyVisionPlanillaAnalysis
import com.example.lucy.voice.LucyVoiceOption
import java.util.UUID

enum class LucyModuleId {
    CORE,
    VISION,
    VOICE,
    MEMORY
}

enum class LucyExecutionMode {
    OFFLINE_ONLY,
    HYBRID_CLOUD_OPTIONAL
}

enum class LucyOperationOrigin {
    TEXT,
    VOICE,
    VISION,
    SYSTEM
}

@Immutable
sealed interface LucyModuleHealth {
    data object Ready : LucyModuleHealth
    data object NotConfigured : LucyModuleHealth
    data class Degraded(val reason: String) : LucyModuleHealth
    data class Unavailable(val reason: String) : LucyModuleHealth
}

/**
 * Puertos desacoplados para los módulos restantes (Etapas 2, 3 y 4).
 * Permiten que LUCY CORE opere sin depender de OpenCV, ONNX Runtime, SpeechRecognizer o servicios externos.
 */
sealed interface LucyVisionOutcome {
    data class LinesExtracted(
        val lines: List<String>,
        val providerName: String
    ) : LucyVisionOutcome

    data class PlanillaAnalyzed(
        val analysis: LucyVisionPlanillaAnalysis,
        val providerName: String
    ) : LucyVisionOutcome

    data class NotAvailable(val reason: String) : LucyVisionOutcome
    data class Failed(val reason: String) : LucyVisionOutcome
}

interface LucyVisionPort {
    val providerName: String
    suspend fun healthCheck(): LucyModuleHealth
    suspend fun extractPlanillaLines(imagePath: String): LucyVisionOutcome

    suspend fun analyzePlanilla(
        imagePath: String,
        turnoContexto: Turno,
        empresaSugerida: EmpresaOficial? = null
    ): LucyVisionOutcome = extractPlanillaLines(imagePath)
}

interface LucyVoicePort {
    suspend fun healthCheck(): LucyModuleHealth
    suspend fun speakIfEnabled(text: String)
    suspend fun applyPreferences(preferencias: LucyPreferenciasConversacion) = Unit
    suspend fun stopSpeaking() = Unit
    fun availableVoiceOptions(): List<LucyVoiceOption> = emptyList()
    suspend fun selectPreferredVoice(voiceId: String) = Unit
    suspend fun selectVoiceStyle(style: LucyEstiloVoz) = Unit
    suspend fun selectSpeechSpeed(speed: LucyVelocidadRespuesta) = Unit
}

enum class LucyEstadoEscrituraMemoria {
    GUARDADO,
    ACTUALIZADO,
    YA_EXISTIA,
    NO_DISPONIBLE,
    ERROR
}

data class LucyResultadoEscrituraMemoria(
    val estado: LucyEstadoEscrituraMemoria,
    val detalle: String = ""
)

data class LucyResultadoEliminacionMemoria(
    val eliminados: Int,
    val detalle: String = "",
    val huboError: Boolean = false
)

enum class LucyTipoOlvidoMemoria {
    ULTIMA_PREFERENCIA,
    TODAS_PREFERENCIAS,
    NOTA_O_RECUERDO,
    TODO_TURNO
}

interface LucyMemoryPort {
    suspend fun healthCheck(): LucyModuleHealth
    suspend fun recordInteraction(turno: Turno, userInput: String, assistantResponse: String)
    suspend fun resolveLastReferencedWorkerId(turno: Turno): String?
    suspend fun rememberReferencedWorker(turno: Turno, trabajadorId: String)

    // Extensión Etapa 2 (compatibilidad conservada mediante valores por defecto)
    suspend fun obtenerPreferencias(): LucyPreferenciasConversacion = LucyPreferenciasConversacion()

    suspend fun actualizarVelocidadRespuesta(velocidad: LucyVelocidadRespuesta): LucyResultadoEscrituraMemoria =
        LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.NO_DISPONIBLE)

    suspend fun actualizarEstiloRespuesta(estilo: LucyEstiloRespuesta): LucyResultadoEscrituraMemoria =
        LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.NO_DISPONIBLE)

    suspend fun actualizarVozPreferida(vozId: String): LucyResultadoEscrituraMemoria =
        LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.NO_DISPONIBLE)

    suspend fun actualizarEstiloVoz(estiloVoz: LucyEstiloVoz): LucyResultadoEscrituraMemoria =
        LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.NO_DISPONIBLE)

    suspend fun guardarInstruccionPermanente(texto: String): LucyResultadoEscrituraMemoria =
        LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.NO_DISPONIBLE)

    suspend fun recordarEmpresa(turno: Turno, empresa: EmpresaOficial) = Unit

    suspend fun resolveLastReferencedEmpresa(turno: Turno): EmpresaOficial? = null

    suspend fun guardarNotaOperativa(
        turnoDestino: Turno,
        texto: String,
        empresa: EmpresaOficial? = null
    ): LucyResultadoEscrituraMemoria =
        LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.NO_DISPONIBLE)

    suspend fun consultarNotasOperativas(
        turno: Turno,
        empresa: EmpresaOficial? = null,
        query: String? = null
    ): List<LucyNotaMemoria> = emptyList()

    suspend fun olvidarUltimaPreferencia(): LucyResultadoEliminacionMemoria =
        LucyResultadoEliminacionMemoria(0)

    suspend fun olvidarTodasPreferencias(): LucyResultadoEliminacionMemoria =
        LucyResultadoEliminacionMemoria(0)

    suspend fun olvidarNotas(
        turno: Turno,
        empresa: EmpresaOficial? = null,
        referenciaTexto: String? = null,
        soloUltimaSiSinFiltro: Boolean = true
    ): LucyResultadoEliminacionMemoria = LucyResultadoEliminacionMemoria(0)

    suspend fun limpiarContextoTurno(turno: Turno): LucyResultadoEliminacionMemoria =
        LucyResultadoEliminacionMemoria(0)

    suspend fun obtenerResumenMemoria(): LucyResumenMemoria? = null

    suspend fun guardarCorreccionAprendida(
        correccion: LucyCorreccionAprendida
    ): LucyResultadoEscrituraMemoria =
        LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.NO_DISPONIBLE)

    suspend fun obtenerCorreccionesAprendidas(
        turno: Turno,
        tipo: LucyTipoCorreccionAprendida? = null
    ): List<LucyCorreccionAprendida> = emptyList()

    suspend fun olvidarCorreccionesAprendidas(turno: Turno): LucyResultadoEliminacionMemoria =
        LucyResultadoEliminacionMemoria(0)

    suspend fun registrarCambioMaestro(
        cambio: LucyRegistroCambioMaestro
    ): LucyResultadoEscrituraMemoria =
        LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.NO_DISPONIBLE)

    suspend fun obtenerHistorialCambiosMaestro(
        turno: Turno,
        trabajadorId: String? = null
    ): List<LucyRegistroCambioMaestro> = emptyList()
}

object NoOpLucyVisionPort : LucyVisionPort {
    override val providerName: String = "Sin proveedor configurado"
    override suspend fun healthCheck(): LucyModuleHealth = LucyModuleHealth.NotConfigured
    override suspend fun extractPlanillaLines(imagePath: String): LucyVisionOutcome =
        LucyVisionOutcome.NotAvailable("Módulo Lucy Vision aún no habilitado en esta etapa.")
    override suspend fun analyzePlanilla(
        imagePath: String,
        turnoContexto: Turno,
        empresaSugerida: EmpresaOficial?
    ): LucyVisionOutcome =
        LucyVisionOutcome.NotAvailable("Módulo Lucy Vision no configurado.")
}

object NoOpLucyVoicePort : LucyVoicePort {
    override suspend fun healthCheck(): LucyModuleHealth = LucyModuleHealth.NotConfigured
    override suspend fun speakIfEnabled(text: String) = Unit
}

object NoOpLucyMemoryPort : LucyMemoryPort {
    override suspend fun healthCheck(): LucyModuleHealth = LucyModuleHealth.NotConfigured
    override suspend fun recordInteraction(turno: Turno, userInput: String, assistantResponse: String) = Unit
    override suspend fun resolveLastReferencedWorkerId(turno: Turno): String? = null
    override suspend fun rememberReferencedWorker(turno: Turno, trabajadorId: String) = Unit
}

@Immutable
data class LucyTurnContext(
    val turnoActivo: Turno,
    val empresaActiva: EmpresaOficial? = null
)

enum class LucyConversationalSubtipo {
    SALUDO,
    DESPEDIDA,
    AGRADECIMIENTO,
    ESTADO_ASISTENTE,
    IDENTIDAD_ASISTENTE,
    CHARLA_GENERAL
}

enum class LucyDetalleTrabajadorCampo {
    HABITACION,
    PABELLON,
    EMPRESA,
    EMPRESA_VERIFICACION,
    UBICACION_COMPLETA
}

sealed interface LucyIntent {
    data class ConversacionGeneral(
        val subtipo: LucyConversationalSubtipo,
        val textoOriginal: String
    ) : LucyIntent

    data class ConsultarConversacionReciente(
        val pideUltimaPregunta: Boolean = false,
        val pideResumenCharla: Boolean = false,
        val pideContinuacion: Boolean = false,
        val textoOriginal: String
    ) : LucyIntent

    data class RecuperarContextoActual(
        val textoOriginal: String
    ) : LucyIntent

    data class ConsultarDetalleTrabajadorAnterior(
        val campo: LucyDetalleTrabajadorCampo,
        val textoOriginal: String,
        val empresaConsulta: EmpresaOficial? = null
    ) : LucyIntent

    data class AclaracionAmbigua(
        val textoOriginal: String,
        val esPregunta: Boolean = false,
        val motivo: String = ""
    ) : LucyIntent

    data class ResumenTurno(
        val turnoSolicitado: Turno? = null,
        val empresa: EmpresaOficial? = null,
        val usarEmpresaAnterior: Boolean = false
    ) : LucyIntent

    data class ConsultarEmpresas(
        val turnoSolicitado: Turno? = null,
        val empresa: EmpresaOficial? = null
    ) : LucyIntent

    data class ListarTrabajadores(
        val turnoSolicitado: Turno? = null,
        val empresa: EmpresaOficial? = null,
        val usarEmpresaAnterior: Boolean = false
    ) : LucyIntent

    data class ConsultarPendientes(
        val turnoSolicitado: Turno? = null,
        val empresa: EmpresaOficial? = null,
        val usarEmpresaAnterior: Boolean = false
    ) : LucyIntent

    data class BuscarTrabajador(
        val query: String,
        val turnoSolicitado: Turno? = null,
        val empresa: EmpresaOficial? = null,
        val usarEmpresaAnterior: Boolean = false,
        val usarTrabajadorAnterior: Boolean = false,
        val esMencionPasiva: Boolean = false
    ) : LucyIntent

    data class ConsultarEstadoTrabajador(
        val query: String,
        val turnoSolicitado: Turno? = null,
        val empresa: EmpresaOficial? = null,
        val usarEmpresaAnterior: Boolean = false,
        val usarTrabajadorAnterior: Boolean = false,
        val respuestaBreve: Boolean = false
    ) : LucyIntent

    data class SolicitarMarcacion(
        val query: String,
        val marcarComoSiBajo: Boolean,
        val empresa: EmpresaOficial? = null,
        val turnoSolicitado: Turno? = null,
        val usarEmpresaAnterior: Boolean = false,
        val usarTrabajadorAnterior: Boolean = false
    ) : LucyIntent

    data class MarcacionMultiple(
        val nombresTexto: String,
        val empresa: EmpresaOficial? = null,
        val turnoSolicitado: Turno? = null,
        val usarEmpresaAnterior: Boolean = false,
        val esContinuacion: Boolean = false,
        val incluirUbicacion: Boolean = false
    ) : LucyIntent

    data class MarcarTrabajadorEnContexto(
        val marcarComoSiBajo: Boolean,
        val textoOriginal: String
    ) : LucyIntent

    data class ResolverDudaPendiente(
        val textoRespuesta: String,
        val empresa: EmpresaOficial? = null,
        val turnoSolicitado: Turno? = null,
        val indiceOpcion: Int? = null,
        val dejarPendiente: Boolean = false,
        val forzarMarcacion: Boolean = false
    ) : LucyIntent

    data class ActualizarDatoTrabajador(
        val nombreTrabajador: String,
        val usarTrabajadorAnterior: Boolean = false,
        val nuevaHabitacion: String? = null,
        val nuevoPabellon: String? = null,
        val nuevaEmpresa: EmpresaOficial? = null,
        val habitacionAnteriorEsperada: String? = null,
        val turnoSolicitado: Turno? = null,
        val textoOriginal: String
    ) : LucyIntent

    data class ConsultarHistorialCambios(
        val nombreTrabajador: String? = null,
        val turnoSolicitado: Turno? = null,
        val textoOriginal: String
    ) : LucyIntent

    data class RegistrarCorreccionUsuario(
        val textoCorregido: String,
        val textoErroneo: String? = null,
        val tipoSugerido: LucyTipoCorreccionAprendida = LucyTipoCorreccionAprendida.NOMBRE,
        val filaVision: Int? = null,
        val textoOriginal: String
    ) : LucyIntent

    data class ConflictoTurno(
        val turnoActivo: Turno,
        val turnoSolicitado: Turno,
        val query: String,
        val marcarComoSiBajo: Boolean
    ) : LucyIntent

    data class ConfigurarPreferenciaConversacion(
        val velocidad: LucyVelocidadRespuesta? = null,
        val estilo: LucyEstiloRespuesta? = null,
        val estiloVoz: LucyEstiloVoz? = null,
        val instruccionPermanente: String? = null
    ) : LucyIntent

    data class GuardarNotaOperativa(
        val texto: String,
        val empresa: EmpresaOficial? = null,
        val usarEmpresaAnterior: Boolean = false,
        val turnoDestino: Turno? = null
    ) : LucyIntent

    data class ConsultarMemoria(
        val empresa: EmpresaOficial? = null,
        val usarEmpresaAnterior: Boolean = false,
        val turnoSolicitado: Turno? = null,
        val soloPreferencias: Boolean = false,
        val query: String? = null
    ) : LucyIntent

    data class OlvidarMemoria(
        val tipo: LucyTipoOlvidoMemoria,
        val empresa: EmpresaOficial? = null,
        val usarEmpresaAnterior: Boolean = false,
        val turnoSolicitado: Turno? = null,
        val referenciaTexto: String? = null
    ) : LucyIntent

    data class CambiarTurno(
        val nuevoTurno: Turno
    ) : LucyIntent

    data class FiltrarPlanillaVisionEmpresa(
        val empresaTexto: String,
        val empresa: EmpresaOficial? = null,
        val turnoSolicitado: Turno? = null,
        val textoOriginal: String
    ) : LucyIntent

    data object Ayuda : LucyIntent

    data class NoComprendido(
        val textoOriginal: String
    ) : LucyIntent
}

@Immutable
data class LucyPendingConfirmation(
    val id: String = UUID.randomUUID().toString(),
    val turno: Turno,
    val trabajador: TrabajadorConEstado,
    val marcarComoSiBajo: Boolean,
    val descripcion: String
)

@Immutable
data class LucyPerformanceTrace(
    val sendTimestampMs: Long,
    val firstResponseLatencyMs: Long,
    val totalResponseLatencyMs: Long,
    val usedLocalFastPath: Boolean = false,
    val modelUsed: String? = null
)

@Immutable
data class LucyResponse(
    val id: String = UUID.randomUUID().toString(),
    val turnoContexto: Turno,
    val texto: String,
    val trabajadoresRelacionados: List<TrabajadorConEstado> = emptyList(),
    val candidatosDesambiguacion: List<TrabajadorConEstado> = emptyList(),
    val accionDesambiguacionSiBajo: Boolean = true,
    val confirmacionRequerida: LucyPendingConfirmation? = null,
    val visionAnalysis: LucyVisionPlanillaAnalysis? = null,
    val esErrorRecuperable: Boolean = false,
    val modoEjecucion: LucyExecutionMode = LucyExecutionMode.OFFLINE_ONLY,
    val timestampMs: Long = System.currentTimeMillis(),
    val performanceTrace: LucyPerformanceTrace? = null
)

@Immutable
data class LucyConversationEntry(
    val id: String = UUID.randomUUID().toString(),
    val turno: Turno,
    val esUsuario: Boolean,
    val texto: String,
    val origen: LucyOperationOrigin = LucyOperationOrigin.TEXT,
    val response: LucyResponse? = null,
    val timestampMs: Long = System.currentTimeMillis()
)
