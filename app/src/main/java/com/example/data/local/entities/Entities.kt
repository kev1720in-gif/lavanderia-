package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "turnos")
data class TurnoEntity(
    @PrimaryKey val id: String,
    val nombre: String
)

@Entity(tableName = "empresas")
data class EmpresaEntity(
    @PrimaryKey val id: Int,
    val codigo: String,
    val nombre: String,
    val colorHex: Long
)

@Entity(
    tableName = "trabajadores",
    foreignKeys = [
        ForeignKey(
            entity = TurnoEntity::class,
            parentColumns = ["id"],
            childColumns = ["turnoId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EmpresaEntity::class,
            parentColumns = ["id"],
            childColumns = ["empresaId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["turnoId"]),
        Index(value = ["empresaId"]),
        Index(value = ["turnoId", "primerApellido", "nombre"])
    ]
)
data class TrabajadorEntity(
    @PrimaryKey val id: String,
    val turnoId: String,
    val empresaId: Int,
    val codigoInterno: String,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String,
    val habitacion: String,
    val pabellon: String,
    val esDatoPrueba: Boolean,
    val creadoEn: Long,
    val actualizadoEn: Long
)

@Entity(
    tableName = "sesiones",
    foreignKeys = [
        ForeignKey(
            entity = TurnoEntity::class,
            parentColumns = ["id"],
            childColumns = ["turnoId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["turnoId", "estado"])
    ]
)
data class SesionEntity(
    @PrimaryKey val id: String,
    val turnoId: String,
    val fechaIso: String,
    val estado: String, // ABIERTA | FINALIZADA
    val creadaEn: Long,
    val finalizadaEn: Long? = null
)

@Entity(
    tableName = "marcaciones",
    foreignKeys = [
        ForeignKey(
            entity = SesionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sesionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrabajadorEntity::class,
            parentColumns = ["id"],
            childColumns = ["trabajadorId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["sesionId"]),
        Index(value = ["turnoId"]),
        Index(value = ["trabajadorId"]),
        Index(value = ["sesionId", "trabajadorId"], unique = true)
    ]
)
data class MarcacionEntity(
    @PrimaryKey val id: String,
    val sesionId: String,
    val turnoId: String,
    val trabajadorId: String?,
    val nombreCompletoSnapshot: String,
    val primerApellidoSnapshot: String,
    val segundoApellidoSnapshot: String,
    val habitacionSnapshot: String,
    val pabellonSnapshot: String,
    val empresaIdSnapshot: Int,
    val empresaNombreSnapshot: String,
    val siBajo: Boolean,
    val esDatoPrueba: Boolean,
    val origen: String, // MANUAL | VOZ | FOTO
    val marcadoEn: Long
)

@Entity(
    tableName = "registros_recepcion",
    foreignKeys = [
        ForeignKey(
            entity = SesionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sesionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrabajadorEntity::class,
            parentColumns = ["id"],
            childColumns = ["trabajadorId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["sesionId"]),
        Index(value = ["turnoId"]),
        Index(value = ["trabajadorId"])
    ]
)
data class RegistroRecepcionEntity(
    @PrimaryKey val id: String,
    val sesionId: String,
    val turnoId: String,
    val trabajadorId: String?,
    val textoOriginal: String,
    val nombreConfirmadoSnapshot: String,
    val empresaIdSnapshot: Int?,
    val empresaNombreSnapshot: String?,
    val confirmado: Boolean,
    val origen: String,
    val creadoEn: Long
)

@Entity(
    tableName = "comparaciones",
    foreignKeys = [
        ForeignKey(
            entity = SesionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sesionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrabajadorEntity::class,
            parentColumns = ["id"],
            childColumns = ["trabajadorId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["sesionId"]),
        Index(value = ["turnoId"]),
        Index(value = ["trabajadorId"])
    ]
)
data class ComparacionEntity(
    @PrimaryKey val id: String,
    val sesionId: String,
    val turnoId: String,
    val trabajadorId: String?,
    val empresaIdSnapshot: Int?,
    val empresaNombreSnapshot: String,
    val nombreCompletoSnapshot: String,
    val habitacionSnapshot: String,
    val pabellonSnapshot: String,
    val categoria: String, // COINCIDENCIA | NO_APARECE_EN_DESCARGA | ADICIONAL
    val calculadoEn: Long
)

@Entity(tableName = "configuracion")
data class ConfiguracionEntity(
    @PrimaryKey val clave: String,
    val valor: String
)

@Entity(
    tableName = "correcciones_ocr",
    foreignKeys = [
        ForeignKey(
            entity = TurnoEntity::class,
            parentColumns = ["id"],
            childColumns = ["turnoId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EmpresaEntity::class,
            parentColumns = ["id"],
            childColumns = ["empresaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrabajadorEntity::class,
            parentColumns = ["id"],
            childColumns = ["trabajadorId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["turnoId", "empresaId", "textoOcrNormalizado"], unique = true),
        Index(value = ["trabajadorId"]),
        Index(value = ["turnoId", "empresaId"])
    ]
)
data class CorreccionOcrEntity(
    @PrimaryKey val id: String,
    val turnoId: String,
    val empresaId: Int,
    val textoOcrNormalizado: String,
    val textoOcrOriginal: String,
    val trabajadorId: String,
    val vecesUsada: Int,
    val creadoEn: Long,
    val actualizadoEn: Long
)
