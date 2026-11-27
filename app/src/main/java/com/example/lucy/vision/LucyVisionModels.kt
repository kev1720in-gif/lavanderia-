package com.example.lucy.vision

import androidx.compose.runtime.Immutable
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import java.util.UUID

@Immutable
data class LucyVisionConfig(
    val modelName: String = DEFAULT_MODEL_NAME,
    val fallbackModelName: String = FALLBACK_MODEL_NAME,
    val timeoutMs: Long = 60_000L,
    val maxImageDimPx: Int = 1800,
    val jpegQuality: Int = 85,
    val maxPayloadBytes: Int = 4 * 1024 * 1024
) {
    companion object {
        const val DEFAULT_MODEL_NAME = "gemini-3.8-flash"
        const val FALLBACK_MODEL_NAME = "gemini-3.5-flash-lite"
    }
}

@Immutable
enum class LucyVisionTextOriginType(val etiqueta: String) {
    IMPRESO("Impreso"),
    MANUSCRITO("Manuscrito"),
    MIXTO("Mixto"),
    INDETERMINADO("No determinado");

    companion object {
        fun fromRaw(raw: String?): LucyVisionTextOriginType = when (raw?.trim()?.uppercase()) {
            "IMPRESO", "PRINTED" -> IMPRESO
            "MANUSCRITO", "HANDWRITTEN" -> MANUSCRITO
            "MIXTO", "MIXED" -> MIXTO
            else -> INDETERMINADO
        }
    }
}

@Immutable
enum class LucyVisionDataOrigin(val etiqueta: String) {
    RECONOCIDO_EN_IMAGEN("Reconocido literalmente en la imagen"),
    INFERIDO_POR_CONTEXTO("Inferido por el contexto activo"),
    INFERIDO_POR_MAESTRO("Inferido por coincidencia con el maestro"),
    NO_DETERMINADO("No determinado")
}

@Immutable
enum class LucyVisionConfidenceLevel(val etiqueta: String) {
    ALTA("Alta"),
    MEDIA("Media"),
    BAJA("Baja");

    companion object {
        fun fromScore(score: Float): LucyVisionConfidenceLevel = when {
            score >= 0.80f -> ALTA
            score >= 0.55f -> MEDIA
            else -> BAJA
        }
    }
}

@Immutable
enum class LucyVisionMarkingStatus(val etiqueta: String) {
    SI_BAJO("SÍ BAJÓ"),
    NO_BAJO("NO BAJÓ"),
    INDETERMINADO("No determinado en la imagen")
}

@Immutable
enum class LucyVisionDocumentStatus(val etiqueta: String) {
    LEGIBLE("Planilla legible"),
    PARCIALMENTE_LEGIBLE("Planilla parcialmente legible (con dudas)"),
    ILEGIBLE("Imagen ilegible o no corresponde a una planilla válida"),
    SIN_CONEXION("Sin conexión a Internet"),
    NO_CONFIGURADO("Servicio de visión en la nube no configurado"),
    TIMEOUT("Tiempo de espera agotado"),
    CUOTA_O_FACTURACION("Cuota agotada o plan de facturación requerido"),
    ERROR_APP_CHECK("Fallo de verificación App Check o autorización Firebase"),
    RESPUESTA_INVALIDA("Respuesta estructurada inválida del modelo"),
    ERROR_PROVEEDOR("Error controlado del proveedor de visión")
}

@Immutable
enum class LucyVisionMatchStatus(val etiqueta: String) {
    COINCIDENCIA_CANDIDATA_UNICA("Coincidencia única propuesta (requiere confirmación)"),
    AMBIGUA_MULTIPLE("Múltiples coincidencias probables (ambigua)"),
    SIN_COINCIDENCIA("Sin coincidencia en el maestro del turno"),
    NOMBRE_INCOMPLETO("Nombre o apellido incompleto para identificar")
}

/**
 * Datos literalmente observados en la fotografía (sin deducciones ni suposiciones).
 */
@Immutable
data class LucyVisionObservedWorkerData(
    val filaNumero: Int? = null,
    val nombreDetectado: String,
    val apellidosDetectados: String? = null,
    val apellidoPaternoDetectado: String? = null,
    val apellidoMaternoDetectado: String? = null,
    val nombrePilaDetectado: String? = null,
    val tipoEscritura: LucyVisionTextOriginType = LucyVisionTextOriginType.INDETERMINADO,
    val empresaEscritaEnImagen: String? = null,
    val pabellonEscritoEnImagen: String? = null,
    val habitacionEscritaEnImagen: String? = null,
    val marcaVisualLiteral: String? = null,
    val anotacionesFila: String? = null,
    val tieneMarcasManuales: Boolean = false,
    val textoCrudoFila: String
)

/**
 * Propuestas inferidas por Lucy Vision al cruzar lo observado con el contexto y el maestro.
 * Ninguna propuesta aplica cambios en Room ni confirma identidad por sí sola.
 */
@Immutable
data class LucyVisionInferredWorkerData(
    val empresaProbable: EmpresaOficial? = null,
    val origenEmpresa: LucyVisionDataOrigin = LucyVisionDataOrigin.NO_DETERMINADO,
    val pabellonInferido: String? = null,
    val origenPabellon: LucyVisionDataOrigin = LucyVisionDataOrigin.NO_DETERMINADO,
    val estadoMarcacionPropuesto: LucyVisionMarkingStatus = LucyVisionMarkingStatus.INDETERMINADO,
    val origenEstadoMarcacion: LucyVisionDataOrigin = LucyVisionDataOrigin.NO_DETERMINADO,
    val coincidenciaProbable: LucyVisionWorkerMatchProposal
)

@Immutable
data class LucyVisionWorkerMatchCandidate(
    val trabajador: TrabajadorConEstado,
    val puntajeSimilitud: Double,
    val motivo: String
)

@Immutable
data class LucyVisionWorkerMatchProposal(
    val estado: LucyVisionMatchStatus,
    val candidatoPrincipal: LucyVisionWorkerMatchCandidate? = null,
    val candidatos: List<LucyVisionWorkerMatchCandidate> = emptyList(),
    val esIdentidadConfirmada: Boolean = false
)

@Immutable
data class LucyVisionWorkerDetection(
    val indiceEnAnalisis: Int,
    val nombreDetectado: String,
    val apellidosDetectados: String? = null,
    val apellidoPaternoDetectado: String? = null,
    val apellidoMaternoDetectado: String? = null,
    val nombrePilaDetectado: String? = null,
    val tipoEscritura: LucyVisionTextOriginType = LucyVisionTextOriginType.INDETERMINADO,
    val esDudosoOIlegible: Boolean = false,
    val empresaProbable: EmpresaOficial? = null,
    val origenEmpresa: LucyVisionDataOrigin = LucyVisionDataOrigin.NO_DETERMINADO,
    val pabellon: String? = null,
    val origenPabellon: LucyVisionDataOrigin = LucyVisionDataOrigin.NO_DETERMINADO,
    val habitacion: String? = null,
    val estadoMarcacion: LucyVisionMarkingStatus = LucyVisionMarkingStatus.INDETERMINADO,
    val origenMarcacion: LucyVisionDataOrigin = LucyVisionDataOrigin.NO_DETERMINADO,
    val anotacionesFila: String? = null,
    val tieneMarcasManuales: Boolean = false,
    val confianzaReconocimiento: Float,
    val nivelConfianza: LucyVisionConfidenceLevel = LucyVisionConfidenceLevel.fromScore(confianzaReconocimiento),
    val coincidenciaProbable: LucyVisionWorkerMatchProposal,
    val esPosibleDuplicadoEnPlanilla: Boolean = false,
    val detalleDuplicado: String? = null,
    val observacionesYDudas: List<String> = emptyList(),
    val datosReconocidos: LucyVisionObservedWorkerData,
    val datosInferidos: LucyVisionInferredWorkerData
) {
    /**
     * Representación tabular para revisión humana: APELLIDOS | NOMBRE | HABITACIÓN | PABELLÓN
     */
    val formatoTablaRevision: String
        get() {
            val apellidos = apellidosDetectados?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(apellidoPaternoDetectado, apellidoMaternoDetectado)
                    .joinToString(" ")
                    .trim()
                    .takeIf { it.isNotBlank() }
                ?: nombreDetectado.ifBlank { "Ilegible" }
            val nombre = nombrePilaDetectado?.takeIf { it.isNotBlank() } ?: "—"
            val hab = datosReconocidos.habitacionEscritaEnImagen?.takeIf { it.isNotBlank() }
                ?: habitacion?.takeIf { it.isNotBlank() }
                ?: "—"
            val pab = datosReconocidos.pabellonEscritoEnImagen?.takeIf { it.isNotBlank() }
                ?: pabellon?.takeIf { it.isNotBlank() }
                ?: "—"
            return "$apellidos | $nombre | $hab | $pab"
        }
}

@Immutable
data class LucyVisionDuplicateGroup(
    val motivo: String,
    val claveNormalizada: String,
    val indicesFilasDetectadas: List<Int>,
    val trabajadorCandidatoId: String? = null
)

@Immutable
data class LucyVisionPlanillaAnalysis(
    val analisisId: String = UUID.randomUUID().toString(),
    val turnoContexto: Turno,
    val estadoDocumento: LucyVisionDocumentStatus,
    val motorUtilizado: String,
    val esDocumentoOperativoValido: Boolean,
    val tipoEscrituraDocumento: LucyVisionTextOriginType = LucyVisionTextOriginType.INDETERMINADO,
    val empresaDocumentoReconocida: String? = null,
    val empresaDocumentoInferida: EmpresaOficial? = null,
    val resumenGeneral: String,
    val trabajadoresDetectados: List<LucyVisionWorkerDetection> = emptyList(),
    val posiblesDuplicados: List<LucyVisionDuplicateGroup> = emptyList(),
    val dudasGlobales: List<String> = emptyList(),
    val notasIlegibilidad: List<String> = emptyList(),
    val requiereRevisionHumana: Boolean = true,
    val esResultadoPropuestoNoConfirmado: Boolean = true,
    val mensajeErrorUsuario: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val empresaFiltroAplicada: EmpresaOficial? = null,
    val parsedSheet: LucyVisionParsedSheet? = null
)

/**
 * Representación intermedia devuelta por el parser JSON antes de cruzar con Room.
 */
data class LucyVisionParsedSheet(
    val isReadable: Boolean,
    val legibilityStatus: String,
    val documentTextType: LucyVisionTextOriginType = LucyVisionTextOriginType.INDETERMINADO,
    val detectedCompanyText: String?,
    val documentNotes: List<String>,
    val rows: List<LucyVisionParsedRow>
)

data class LucyVisionParsedRow(
    val rowNumber: Int?,
    val rawText: String,
    val detectedName: String,
    val detectedLastNames: String? = null,
    val detectedPaternalLastName: String? = null,
    val detectedMaternalLastName: String? = null,
    val detectedFirstName: String? = null,
    val textOriginType: LucyVisionTextOriginType = LucyVisionTextOriginType.INDETERMINADO,
    val detectedCompanyText: String?,
    val detectedPavilion: String?,
    val detectedRoom: String?,
    val visualMarkingText: String?,
    val rowAnnotations: String? = null,
    val hasManualMarks: Boolean = false,
    val interpretedMarkingStatus: LucyVisionMarkingStatus,
    val confidence: Float,
    val ambiguityNotes: List<String>,
    val isIncompleteOrIllegible: Boolean
)
