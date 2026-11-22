package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entities.ComparacionEntity
import com.example.data.local.entities.ConfiguracionEntity
import com.example.data.local.entities.CorreccionOcrEntity
import com.example.data.local.entities.EmpresaEntity
import com.example.data.local.entities.MarcacionEntity
import com.example.data.local.entities.RegistroRecepcionEntity
import com.example.data.local.entities.SesionEntity
import com.example.data.local.entities.TrabajadorEntity
import com.example.data.local.entities.TurnoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TurnoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(turnos: List<TurnoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(turnos: List<TurnoEntity>)

    @Query("SELECT * FROM turnos ORDER BY id ASC")
    suspend fun getAllOnce(): List<TurnoEntity>

    @Query("SELECT COUNT(*) FROM turnos")
    suspend fun count(): Int
}

@Dao
interface EmpresaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(empresas: List<EmpresaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(empresas: List<EmpresaEntity>)

    @Update
    suspend fun updateAll(empresas: List<EmpresaEntity>)

    @Query("SELECT * FROM empresas ORDER BY id ASC")
    fun observeAll(): Flow<List<EmpresaEntity>>

    @Query("SELECT * FROM empresas ORDER BY id ASC")
    suspend fun getAllOnce(): List<EmpresaEntity>

    @Query("SELECT COUNT(*) FROM empresas")
    suspend fun count(): Int
}

@Dao
interface TrabajadorDao {
    @Query(
        "SELECT * FROM trabajadores WHERE turnoId = :turnoId " +
            "ORDER BY primerApellido COLLATE NOCASE ASC, segundoApellido COLLATE NOCASE ASC, nombre COLLATE NOCASE ASC"
    )
    fun observeByTurno(turnoId: String): Flow<List<TrabajadorEntity>>

    @Query("SELECT * FROM trabajadores WHERE turnoId = :turnoId")
    suspend fun getByTurnoOnce(turnoId: String): List<TrabajadorEntity>

    @Query("SELECT * FROM trabajadores ORDER BY turnoId ASC, primerApellido COLLATE NOCASE ASC, nombre COLLATE NOCASE ASC")
    suspend fun getAllOnce(): List<TrabajadorEntity>

    @Query("SELECT * FROM trabajadores WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TrabajadorEntity?

    @Query("SELECT COUNT(*) FROM trabajadores WHERE turnoId = :turnoId")
    fun observeCountByTurno(turnoId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM trabajadores WHERE esDatoPrueba = 1")
    fun observeTestCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trabajadores WHERE esDatoPrueba = 0")
    fun observeRealCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(trabajador: TrabajadorEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(trabajadores: List<TrabajadorEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoreConflicts(trabajadores: List<TrabajadorEntity>)

    @Update
    suspend fun update(trabajador: TrabajadorEntity)

    @Query("DELETE FROM trabajadores WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM trabajadores WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM trabajadores")
    suspend fun deleteAll(): Int
}

@Dao
interface SesionDao {
    @Query("SELECT * FROM sesiones WHERE turnoId = :turnoId AND estado = 'ABIERTA' ORDER BY creadaEn DESC LIMIT 1")
    fun observeOpenSessionByTurno(turnoId: String): Flow<SesionEntity?>

    @Query("SELECT * FROM sesiones WHERE turnoId = :turnoId AND estado = 'ABIERTA' ORDER BY creadaEn DESC LIMIT 1")
    suspend fun getOpenSessionByTurno(turnoId: String): SesionEntity?

    @Query("SELECT * FROM sesiones ORDER BY creadaEn ASC")
    suspend fun getAllOnce(): List<SesionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sesion: SesionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sesiones: List<SesionEntity>)

    @Query("DELETE FROM sesiones WHERE turnoId = :turnoId")
    suspend fun deleteByTurno(turnoId: String)

    @Query("DELETE FROM sesiones")
    suspend fun deleteAll(): Int
}

@Dao
interface MarcacionDao {
    @Query("SELECT * FROM marcaciones WHERE sesionId = :sesionId AND turnoId = :turnoId")
    fun observeBySesionAndTurno(sesionId: String, turnoId: String): Flow<List<MarcacionEntity>>

    @Query("SELECT * FROM marcaciones WHERE sesionId = :sesionId AND turnoId = :turnoId")
    suspend fun getBySesionAndTurnoOnce(sesionId: String, turnoId: String): List<MarcacionEntity>

    @Query("SELECT * FROM marcaciones WHERE sesionId = :sesionId AND trabajadorId = :trabajadorId LIMIT 1")
    suspend fun getBySesionAndTrabajador(sesionId: String, trabajadorId: String): MarcacionEntity?

    @Query("SELECT * FROM marcaciones ORDER BY marcadoEn ASC")
    suspend fun getAllOnce(): List<MarcacionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(marcacion: MarcacionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(marcaciones: List<MarcacionEntity>)

    @Query(
        "UPDATE marcaciones SET " +
            "nombreCompletoSnapshot = :nombreCompletoSnapshot, " +
            "primerApellidoSnapshot = :primerApellidoSnapshot, " +
            "segundoApellidoSnapshot = :segundoApellidoSnapshot, " +
            "habitacionSnapshot = :habitacionSnapshot, " +
            "pabellonSnapshot = :pabellonSnapshot, " +
            "empresaIdSnapshot = :empresaIdSnapshot, " +
            "empresaNombreSnapshot = :empresaNombreSnapshot " +
            "WHERE sesionId = :sesionId AND trabajadorId = :trabajadorId"
    )
    suspend fun updateSnapshotForWorker(
        sesionId: String,
        trabajadorId: String,
        nombreCompletoSnapshot: String,
        primerApellidoSnapshot: String,
        segundoApellidoSnapshot: String,
        habitacionSnapshot: String,
        pabellonSnapshot: String,
        empresaIdSnapshot: Int,
        empresaNombreSnapshot: String
    )

    @Query("DELETE FROM marcaciones WHERE turnoId = :turnoId")
    suspend fun deleteByTurno(turnoId: String): Int

    @Query("DELETE FROM marcaciones")
    suspend fun deleteAll(): Int
}

@Dao
interface RegistroRecepcionDao {
    @Query("SELECT * FROM registros_recepcion WHERE sesionId = :sesionId AND turnoId = :turnoId ORDER BY creadoEn DESC")
    fun observeBySesionAndTurno(sesionId: String, turnoId: String): Flow<List<RegistroRecepcionEntity>>

    @Query("SELECT * FROM registros_recepcion WHERE sesionId = :sesionId AND turnoId = :turnoId ORDER BY creadoEn ASC")
    suspend fun getBySesionAndTurnoOnce(sesionId: String, turnoId: String): List<RegistroRecepcionEntity>

    @Query("SELECT * FROM registros_recepcion WHERE sesionId = :sesionId AND turnoId = :turnoId AND empresaIdSnapshot = :empresaId ORDER BY creadoEn ASC")
    suspend fun getBySesionTurnoAndEmpresaOnce(sesionId: String, turnoId: String, empresaId: Int): List<RegistroRecepcionEntity>

    @Query("SELECT * FROM registros_recepcion ORDER BY creadoEn ASC")
    suspend fun getAllOnce(): List<RegistroRecepcionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(registro: RegistroRecepcionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(registros: List<RegistroRecepcionEntity>)

    @Query("DELETE FROM registros_recepcion WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM registros_recepcion WHERE sesionId = :sesionId AND turnoId = :turnoId AND empresaIdSnapshot = :empresaId")
    suspend fun deleteBySesionTurnoAndEmpresa(sesionId: String, turnoId: String, empresaId: Int): Int

    @Query("DELETE FROM registros_recepcion WHERE turnoId = :turnoId")
    suspend fun deleteByTurno(turnoId: String): Int

    @Query("DELETE FROM registros_recepcion")
    suspend fun deleteAll(): Int
}

@Dao
interface ComparacionDao {
    @Query("SELECT * FROM comparaciones ORDER BY calculadoEn ASC")
    suspend fun getAllOnce(): List<ComparacionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(comparaciones: List<ComparacionEntity>)

    @Query("DELETE FROM comparaciones WHERE turnoId = :turnoId")
    suspend fun deleteByTurno(turnoId: String): Int

    @Query("DELETE FROM comparaciones")
    suspend fun deleteAll(): Int
}

@Dao
interface ConfiguracionDao {
    @Query("SELECT valor FROM configuracion WHERE clave = :clave LIMIT 1")
    fun observeValor(clave: String): Flow<String?>

    @Query("SELECT valor FROM configuracion WHERE clave = :clave LIMIT 1")
    suspend fun getValor(clave: String): String?

    @Query("SELECT * FROM configuracion ORDER BY clave ASC")
    suspend fun getAllOnce(): List<ConfiguracionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setValor(configuracion: ConfiguracionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configuraciones: List<ConfiguracionEntity>)

    @Query("DELETE FROM configuracion")
    suspend fun deleteAll(): Int
}

@Dao
interface CorreccionOcrDao {
    @Query("SELECT * FROM correcciones_ocr WHERE turnoId = :turnoId AND empresaId = :empresaId ORDER BY actualizadoEn DESC")
    fun observeByTurnoAndEmpresa(turnoId: String, empresaId: Int): Flow<List<CorreccionOcrEntity>>

    @Query("SELECT * FROM correcciones_ocr WHERE turnoId = :turnoId AND empresaId = :empresaId ORDER BY actualizadoEn DESC")
    suspend fun getByTurnoAndEmpresaOnce(turnoId: String, empresaId: Int): List<CorreccionOcrEntity>

    @Query("SELECT * FROM correcciones_ocr WHERE turnoId = :turnoId AND empresaId = :empresaId AND textoOcrNormalizado = :textoNormalizado LIMIT 1")
    suspend fun getByClaveExacta(turnoId: String, empresaId: Int, textoNormalizado: String): CorreccionOcrEntity?

    @Query("SELECT * FROM correcciones_ocr ORDER BY actualizadoEn ASC")
    suspend fun getAllOnce(): List<CorreccionOcrEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(correccion: CorreccionOcrEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(correcciones: List<CorreccionOcrEntity>)

    @Query("DELETE FROM correcciones_ocr WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM correcciones_ocr")
    suspend fun deleteAll(): Int
}
