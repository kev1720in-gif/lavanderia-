package com.example.data.backup

import com.example.data.local.entities.ComparacionEntity
import com.example.data.local.entities.ConfiguracionEntity
import com.example.data.local.entities.CorreccionOcrEntity
import com.example.data.local.entities.EmpresaEntity
import com.example.data.local.entities.MarcacionEntity
import com.example.data.local.entities.RegistroRecepcionEntity
import com.example.data.local.entities.SesionEntity
import com.example.data.local.entities.TrabajadorEntity
import com.example.data.local.entities.TurnoEntity

const val BACKUP_FORMAT_MIN_SUPPORTED = 1
const val BACKUP_FORMAT_VERSION = 2

data class BackupResumen(
    val totalTrabajadores: Int,
    val totalTurnoA: Int,
    val totalTurnoB: Int,
    val totalEmpresas: Int,
    val totalTurnos: Int,
    val totalSesiones: Int,
    val totalMarcaciones: Int,
    val totalRecepciones: Int,
    val totalComparaciones: Int,
    val totalConfiguraciones: Int,
    val totalCorreccionesOcr: Int = 0
)

data class BackupSnapshot(
    val backupFormatVersion: Int = BACKUP_FORMAT_VERSION,
    val appName: String = "Lavandería Control",
    val generadoEn: Long,
    val generadoEnIso: String,
    val resumen: BackupResumen,
    val turnos: List<TurnoEntity>,
    val empresas: List<EmpresaEntity>,
    val trabajadores: List<TrabajadorEntity>,
    val sesiones: List<SesionEntity>,
    val marcaciones: List<MarcacionEntity>,
    val registrosRecepcion: List<RegistroRecepcionEntity>,
    val comparaciones: List<ComparacionEntity>,
    val configuracion: List<ConfiguracionEntity>,
    val correccionesOcr: List<CorreccionOcrEntity> = emptyList()
)

data class BackupValidado(
    val snapshot: BackupSnapshot,
    val resumen: BackupResumen
)

sealed class ResultadoValidacionBackup {
    data class Valido(val validado: BackupValidado) : ResultadoValidacionBackup()
    data class Invalido(val motivo: String) : ResultadoValidacionBackup()
}

sealed class ResultadoExportacionBackup {
    data class Exito(
        val nombreArchivo: String,
        val resumen: BackupResumen
    ) : ResultadoExportacionBackup()

    data class Error(val mensaje: String) : ResultadoExportacionBackup()
}

sealed class ResultadoRestauracionBackup {
    data class Exito(
        val resumen: BackupResumen,
        val rutaRespaldoPreventivo: String?
    ) : ResultadoRestauracionBackup()

    data class Error(
        val mensaje: String,
        val baseDatosIntacta: Boolean = true
    ) : ResultadoRestauracionBackup()
}
