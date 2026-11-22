package com.example.domain.model

import androidx.compose.runtime.Immutable

enum class NivelConfianzaOcr {
    MEMORIA_CONFIRMADA,
    COINCIDENCIA_EXACTA_UNICA,
    DUDOSA_PENDIENTE
}

@Immutable
data class CandidatoPendienteOcr(
    val trabajador: TrabajadorConEstado,
    val puntajeSimilitud: Double
)

@Immutable
data class DeteccionAutoOcr(
    val textoOcrOriginal: String,
    val textoOcrNormalizado: String,
    val trabajador: TrabajadorConEstado,
    val viaMemoria: Boolean
)

@Immutable
data class DeteccionPendienteOcr(
    val textoOcrOriginal: String,
    val textoOcrNormalizado: String,
    val candidatos: List<CandidatoPendienteOcr>,
    val motivo: String
)

data class ResultadoAnalisisOcr(
    val autoConfirmados: List<DeteccionAutoOcr>,
    val pendientes: List<DeteccionPendienteOcr>,
    val lineasDescartadasRuido: Int
)

@Immutable
data class PendienteDescargaUi(
    val registroId: String,
    val textoOriginal: String,
    val textoNormalizado: String,
    val motivo: String,
    val candidatosSugeridos: List<CandidatoPendienteOcr>
)

@Immutable
data class AjusteFotoPlanilla(
    val rotacionGrados: Int = 0,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f
) {
    val tieneRecortePersonalizado: Boolean
        get() = cropLeft > 0.01f || cropTop > 0.01f || cropRight < 0.99f || cropBottom < 0.99f

    val tieneAjustes: Boolean
        get() = ((rotacionGrados % 360) + 360) % 360 != 0 || tieneRecortePersonalizado
}

@Immutable
data class FotoLoteDescargaUi(
    val id: String,
    val etiqueta: String,
    val estado: EstadoFotoDescarga,
    val mensajeDetalle: String,
    val archivoTrabajoPath: String? = null,
    val ajuste: AjusteFotoPlanilla = AjusteFotoPlanilla(),
    val filasDetectadas: Int = 0,
    val filasLegibles: Int = 0,
    val filasIlegibles: Int = 0,
    val identificadosEnFotoCount: Int = 0,
    val pendientesEnFotoCount: Int = 0
)

enum class EstadoFotoDescarga {
    PROCESADA_OK,
    ERROR_ILEGIBLE
}

@Immutable
data class PrevisualizacionConciliacion(
    val turno: Turno,
    val empresa: EmpresaOficial,
    val totalTrabajadoresEmpresa: Int,
    val confirmadosEnFotosCount: Int,
    val pasaranANoBajoDesdeManualOVoz: List<TrabajadorConEstado>,
    val totalNoBajoFinal: Int
)

data class ResumenEscaneoFoto(
    val autoMarcadosNuevos: Int,
    val yaConfirmadosEnLote: Int,
    val nuevosPendientes: Int,
    val pendientesRepetidos: Int
)

data class ResultadoConciliacionDescarga(
    val turno: Turno,
    val empresa: EmpresaOficial,
    val totalSiBajo: Int,
    val totalNoBajo: Int,
    val revertidosANoBajo: Int
)
