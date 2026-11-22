package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.backup.BACKUP_FORMAT_MIN_SUPPORTED
import com.example.data.backup.BACKUP_FORMAT_VERSION
import com.example.data.backup.BackupResumen
import com.example.data.backup.BackupSerializer
import com.example.data.backup.BackupSnapshot
import com.example.data.backup.ResultadoRestauracionBackup
import com.example.data.backup.ResultadoValidacionBackup
import com.example.data.local.LavanderiaDatabase
import com.example.data.local.entities.ConfiguracionEntity
import com.example.data.local.entities.CorreccionOcrEntity
import com.example.data.local.entities.EmpresaEntity
import com.example.data.local.entities.MarcacionEntity
import com.example.data.local.entities.RegistroRecepcionEntity
import com.example.data.local.entities.SesionEntity
import com.example.data.local.entities.TrabajadorEntity
import com.example.data.local.entities.TurnoEntity
import kotlin.coroutines.cancellation.CancellationException
import com.example.domain.model.AccionImportacion
import com.example.domain.model.AvisoDuplicado
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.FilaImportacionConfirmada
import com.example.domain.model.OcrNombreMatcher
import com.example.domain.model.PrevisualizacionConciliacion
import com.example.domain.model.ResultadoAnalisisOcr
import com.example.domain.model.ResultadoConciliacionDescarga
import com.example.domain.model.ResultadoImportacion
import com.example.domain.model.ResumenEscaneoFoto
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LavanderiaRepository(
    private val database: LavanderiaDatabase
) {
    private val turnoDao = database.turnoDao()
    private val empresaDao = database.empresaDao()
    private val trabajadorDao = database.trabajadorDao()
    private val sesionDao = database.sesionDao()
    private val marcacionDao = database.marcacionDao()
    private val recepcionDao = database.registroRecepcionDao()
    private val comparacionDao = database.comparacionDao()
    private val configuracionDao = database.configuracionDao()
    private val correccionOcrDao = database.correccionOcrDao()

    private val seedMutex = Mutex()

    companion object {
        private const val CLAVE_TURNO_ACTIVO = "turno_activo"
    }

    /**
     * Inicializa únicamente la estructura mínima requerida (turnos, empresas oficiales,
     * turno activo por defecto si no existe y sesiones abiertas si faltan).
     * NUNCA inserta ni modifica trabajadores ficticios o de demostración.
     */
    suspend fun ensureInitialized() {
        seedMutex.withLock {
            database.withTransaction {
                if (turnoDao.count() == 0) {
                    turnoDao.insertAll(
                        listOf(
                            TurnoEntity(id = Turno.A.id, nombre = Turno.A.nombre),
                            TurnoEntity(id = Turno.B.id, nombre = Turno.B.nombre)
                        )
                    )
                }

                val entidadesEmpresas = EmpresaOficial.todas.map { emp ->
                    EmpresaEntity(
                        id = emp.id,
                        codigo = emp.codigo,
                        nombre = emp.nombre,
                        colorHex = emp.colorHex
                    )
                }
                val existentesEmpresas = empresaDao.getAllOnce()
                if (existentesEmpresas != entidadesEmpresas) {
                    empresaDao.insertAll(entidadesEmpresas)
                    empresaDao.updateAll(entidadesEmpresas)
                }

                if (configuracionDao.getValor(CLAVE_TURNO_ACTIVO) == null) {
                    configuracionDao.setValor(ConfiguracionEntity(CLAVE_TURNO_ACTIVO, Turno.A.id))
                }

                if (sesionDao.getOpenSessionByTurno(Turno.A.id) == null) {
                    sesionDao.insert(crearSesionInicial(Turno.A))
                }
                if (sesionDao.getOpenSessionByTurno(Turno.B.id) == null) {
                    sesionDao.insert(crearSesionInicial(Turno.B))
                }
            }
        }
    }

    private fun crearSesionInicial(turno: Turno): SesionEntity {
        val fechaIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return SesionEntity(
            id = UUID.randomUUID().toString(),
            turnoId = turno.id,
            fechaIso = fechaIso,
            estado = "ABIERTA",
            creadaEn = System.currentTimeMillis()
        )
    }

    val turnoActivo: Flow<Turno> = configuracionDao
        .observeValor(CLAVE_TURNO_ACTIVO)
        .map { Turno.fromId(it) }

    suspend fun setTurnoActivo(turno: Turno) {
        configuracionDao.setValor(ConfiguracionEntity(CLAVE_TURNO_ACTIVO, turno.id))
    }

    fun observeConteoTurno(turno: Turno): Flow<Int> =
        trabajadorDao.observeCountByTurno(turno.id)

    fun observeConteoDatosPrueba(): Flow<Int> =
        trabajadorDao.observeTestCount()

    fun observeConteoDatosReales(): Flow<Int> =
        trabajadorDao.observeRealCount()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTrabajadoresConEstado(turno: Turno): Flow<List<TrabajadorConEstado>> {
        val trabajadoresFlow = trabajadorDao.observeByTurno(turno.id)
        val marcacionesFlow = sesionDao.observeOpenSessionByTurno(turno.id).flatMapLatest { sesion ->
            if (sesion == null) {
                flowOf(emptyList())
            } else {
                marcacionDao.observeBySesionAndTurno(sesion.id, turno.id)
            }
        }

        return combine(trabajadoresFlow, marcacionesFlow) { trabajadores, marcaciones ->
            val marcacionesPorTrabajador = HashMap<String, MarcacionEntity>(marcaciones.size * 2)
            for (m in marcaciones) {
                val tId = m.trabajadorId
                if (tId != null) {
                    marcacionesPorTrabajador[tId] = m
                }
            }

            trabajadores.mapNotNull { entity ->
                val empresa = EmpresaOficial.porId(entity.empresaId) ?: return@mapNotNull null
                val marcacion = marcacionesPorTrabajador[entity.id]
                TrabajadorConEstado(
                    id = entity.id,
                    turno = Turno.fromId(entity.turnoId),
                    empresa = empresa,
                    nombre = entity.nombre,
                    primerApellido = entity.primerApellido,
                    segundoApellido = entity.segundoApellido,
                    habitacion = entity.habitacion,
                    pabellon = entity.pabellon,
                    siBajo = marcacion?.siBajo == true,
                    esDatoPrueba = entity.esDatoPrueba,
                    codigoInterno = entity.codigoInterno
                ).also { it.precalentarIndiceBusqueda() }
            }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun obtenerTrabajadoresPorTurnoOnce(turno: Turno): List<TrabajadorEntity> =
        trabajadorDao.getByTurnoOnce(turno.id)

    private fun combinarOrigen(
        origenActual: String?,
        siBajoActual: Boolean,
        nuevoOrigen: String
    ): String {
        if (!siBajoActual || origenActual.isNullOrBlank()) {
            return nuevoOrigen
        }
        val partes = origenActual
            .split("+")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (nuevoOrigen !in partes) {
            partes.add(nuevoOrigen)
        }
        return partes.joinToString("+")
    }

    suspend fun alternarMarcacionSiBajo(trabajador: TrabajadorConEstado) {
        database.withTransaction {
            val sesion = sesionDao.getOpenSessionByTurno(trabajador.turno.id)
                ?: crearSesionInicial(trabajador.turno).also { sesionDao.insert(it) }

            val existente = marcacionDao.getBySesionAndTrabajador(sesion.id, trabajador.id)
            val nuevoEstado = !(existente?.siBajo == true)
            val nuevoOrigen = if (nuevoEstado) {
                combinarOrigen(existente?.origen, existente?.siBajo == true, "MANUAL")
            } else {
                existente?.origen?.takeIf { it.isNotBlank() } ?: "MANUAL"
            }
            val entidad = MarcacionEntity(
                id = existente?.id ?: UUID.randomUUID().toString(),
                sesionId = sesion.id,
                turnoId = trabajador.turno.id,
                trabajadorId = trabajador.id,
                nombreCompletoSnapshot = trabajador.nombreCompleto,
                primerApellidoSnapshot = trabajador.primerApellido,
                segundoApellidoSnapshot = trabajador.segundoApellido,
                habitacionSnapshot = trabajador.habitacion,
                pabellonSnapshot = trabajador.pabellon,
                empresaIdSnapshot = trabajador.empresa.id,
                empresaNombreSnapshot = trabajador.empresa.nombre,
                siBajo = nuevoEstado,
                esDatoPrueba = trabajador.esDatoPrueba,
                origen = nuevoOrigen,
                marcadoEn = System.currentTimeMillis()
            )
            marcacionDao.upsert(entidad)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRecepcionesSesionAbierta(turno: Turno): Flow<List<RegistroRecepcionEntity>> {
        return sesionDao.observeOpenSessionByTurno(turno.id).flatMapLatest { sesion ->
            if (sesion == null) {
                flowOf(emptyList())
            } else {
                recepcionDao.observeBySesionAndTurno(sesion.id, turno.id)
            }
        }
    }

    fun observeCorreccionesOcr(turno: Turno, empresa: EmpresaOficial): Flow<List<CorreccionOcrEntity>> =
        correccionOcrDao.observeByTurnoAndEmpresa(turno.id, empresa.id)

    suspend fun getCorreccionesOcrOnce(turno: Turno, empresa: EmpresaOficial): List<CorreccionOcrEntity> =
        correccionOcrDao.getByTurnoAndEmpresaOnce(turno.id, empresa.id)

    private fun claveNormalizadaRegistro(textoOriginal: String): String {
        val (limpio, _) = OcrNombreMatcher.limpiarTextoNombreDesdeLinea(textoOriginal)
        return limpio.ifBlank { OcrNombreMatcher.normalizarClaveOcr(textoOriginal) }
    }

    /**
     * Aplica el resultado de una fotografía procesada por OCR sin marcar como NO BAJÓ a quienes aún
     * no han aparecido en fotos anteriores del lote.
     */
    suspend fun aplicarResultadoFotografiaOcr(
        turno: Turno,
        empresa: EmpresaOficial,
        analisis: ResultadoAnalisisOcr
    ): ResumenEscaneoFoto {
        return database.withTransaction {
            val ahora = System.currentTimeMillis()
            val sesion = sesionDao.getOpenSessionByTurno(turno.id)
                ?: crearSesionInicial(turno).also { sesionDao.insert(it) }

            val existentesEmpresa = recepcionDao.getBySesionTurnoAndEmpresaOnce(
                sesionId = sesion.id,
                turnoId = turno.id,
                empresaId = empresa.id
            )
            val yaConfirmadosIds = existentesEmpresa
                .filter { it.confirmado && it.trabajadorId != null }
                .mapNotNull { it.trabajadorId }
                .toMutableSet()
            val pendientesExistentesPorClave = existentesEmpresa
                .filter { !it.confirmado }
                .associateBy { claveNormalizadaRegistro(it.textoOriginal) }
                .toMutableMap()

            var autoMarcadosNuevos = 0
            var yaConfirmadosEnLote = 0
            var nuevosPendientes = 0
            var pendientesRepetidos = 0

            analisis.autoConfirmados.forEachIndexed { idx, det ->
                val t = det.trabajador
                val marcaActual = marcacionDao.getBySesionAndTrabajador(sesion.id, t.id)
                val origenCombinado = combinarOrigen(
                    origenActual = marcaActual?.origen,
                    siBajoActual = marcaActual?.siBajo == true,
                    nuevoOrigen = "FOTO"
                )
                marcacionDao.upsert(
                    MarcacionEntity(
                        id = marcaActual?.id ?: UUID.randomUUID().toString(),
                        sesionId = sesion.id,
                        turnoId = turno.id,
                        trabajadorId = t.id,
                        nombreCompletoSnapshot = t.nombreCompleto,
                        primerApellidoSnapshot = t.primerApellido,
                        segundoApellidoSnapshot = t.segundoApellido,
                        habitacionSnapshot = t.habitacion,
                        pabellonSnapshot = t.pabellon,
                        empresaIdSnapshot = empresa.id,
                        empresaNombreSnapshot = empresa.nombre,
                        siBajo = true,
                        esDatoPrueba = t.esDatoPrueba,
                        origen = origenCombinado,
                        marcadoEn = ahora + idx
                    )
                )

                val pendientePrevio = pendientesExistentesPorClave.remove(det.textoOcrNormalizado)
                if (pendientePrevio != null) {
                    recepcionDao.deleteById(pendientePrevio.id)
                }

                if (yaConfirmadosIds.add(t.id)) {
                    recepcionDao.upsert(
                        RegistroRecepcionEntity(
                            id = UUID.randomUUID().toString(),
                            sesionId = sesion.id,
                            turnoId = turno.id,
                            trabajadorId = t.id,
                            textoOriginal = det.textoOcrOriginal,
                            nombreConfirmadoSnapshot = t.nombreCompleto,
                            empresaIdSnapshot = empresa.id,
                            empresaNombreSnapshot = empresa.nombre,
                            confirmado = true,
                            origen = if (det.viaMemoria) "FOTO_MEMORIA" else "FOTO",
                            creadoEn = ahora + idx
                        )
                    )
                    autoMarcadosNuevos++
                } else {
                    yaConfirmadosEnLote++
                }

                if (det.viaMemoria) {
                    val corr = correccionOcrDao.getByClaveExacta(turno.id, empresa.id, det.textoOcrNormalizado)
                    if (corr != null) {
                        correccionOcrDao.upsert(
                            corr.copy(
                                vecesUsada = corr.vecesUsada + 1,
                                actualizadoEn = ahora + idx
                            )
                        )
                    }
                }
            }

            analisis.pendientes.forEachIndexed { idx, pend ->
                val clavePend = pend.textoOcrNormalizado.ifBlank {
                    claveNormalizadaRegistro(pend.textoOcrOriginal)
                }
                if (pendientesExistentesPorClave.containsKey(clavePend)) {
                    pendientesRepetidos++
                } else {
                    val nuevoPendiente = RegistroRecepcionEntity(
                        id = UUID.randomUUID().toString(),
                        sesionId = sesion.id,
                        turnoId = turno.id,
                        trabajadorId = null,
                        textoOriginal = pend.textoOcrOriginal,
                        nombreConfirmadoSnapshot = pend.motivo,
                        empresaIdSnapshot = empresa.id,
                        empresaNombreSnapshot = empresa.nombre,
                        confirmado = false,
                        origen = "FOTO",
                        creadoEn = ahora + 500 + idx
                    )
                    recepcionDao.upsert(nuevoPendiente)
                    pendientesExistentesPorClave[clavePend] = nuevoPendiente
                    nuevosPendientes++
                }
            }

            ResumenEscaneoFoto(
                autoMarcadosNuevos = autoMarcadosNuevos,
                yaConfirmadosEnLote = yaConfirmadosEnLote,
                nuevosPendientes = nuevosPendientes,
                pendientesRepetidos = pendientesRepetidos
            )
        }
    }

    /**
     * Confirma manualmente un trabajador pendiente de identificación OCR:
     * 1) Lo marca como SÍ BAJÓ preservando el origen previo.
     * 2) Convierte el registro de recepción en confirmado.
     * 3) Guarda la corrección aprendida en correcciones_ocr sin alterar el listado maestro.
     */
    suspend fun confirmarPendienteDescarga(
        pendienteId: String,
        trabajador: TrabajadorConEstado,
        guardarEnMemoria: Boolean = true
    ) {
        database.withTransaction {
            val ahora = System.currentTimeMillis()
            val sesion = sesionDao.getOpenSessionByTurno(trabajador.turno.id)
                ?: crearSesionInicial(trabajador.turno).also { sesionDao.insert(it) }

            val recepcionesEmpresa = recepcionDao.getBySesionTurnoAndEmpresaOnce(
                sesionId = sesion.id,
                turnoId = trabajador.turno.id,
                empresaId = trabajador.empresa.id
            )
            val pendiente = recepcionesEmpresa.firstOrNull { it.id == pendienteId }
                ?: return@withTransaction

            val marcaActual = marcacionDao.getBySesionAndTrabajador(sesion.id, trabajador.id)
            val origenCombinado = combinarOrigen(
                origenActual = marcaActual?.origen,
                siBajoActual = marcaActual?.siBajo == true,
                nuevoOrigen = "FOTO"
            )
            marcacionDao.upsert(
                MarcacionEntity(
                    id = marcaActual?.id ?: UUID.randomUUID().toString(),
                    sesionId = sesion.id,
                    turnoId = trabajador.turno.id,
                    trabajadorId = trabajador.id,
                    nombreCompletoSnapshot = trabajador.nombreCompleto,
                    primerApellidoSnapshot = trabajador.primerApellido,
                    segundoApellidoSnapshot = trabajador.segundoApellido,
                    habitacionSnapshot = trabajador.habitacion,
                    pabellonSnapshot = trabajador.pabellon,
                    empresaIdSnapshot = trabajador.empresa.id,
                    empresaNombreSnapshot = trabajador.empresa.nombre,
                    siBajo = true,
                    esDatoPrueba = trabajador.esDatoPrueba,
                    origen = origenCombinado,
                    marcadoEn = ahora
                )
            )

            recepcionDao.upsert(
                pendiente.copy(
                    trabajadorId = trabajador.id,
                    nombreConfirmadoSnapshot = trabajador.nombreCompleto,
                    empresaIdSnapshot = trabajador.empresa.id,
                    empresaNombreSnapshot = trabajador.empresa.nombre,
                    confirmado = true,
                    origen = "FOTO_CONFIRMADA"
                )
            )

            if (guardarEnMemoria) {
                val claveNorm = claveNormalizadaRegistro(pendiente.textoOriginal)
                if (claveNorm.isNotBlank()) {
                    val previa = correccionOcrDao.getByClaveExacta(
                        turnoId = trabajador.turno.id,
                        empresaId = trabajador.empresa.id,
                        textoNormalizado = claveNorm
                    )
                    correccionOcrDao.upsert(
                        CorreccionOcrEntity(
                            id = previa?.id ?: UUID.randomUUID().toString(),
                            turnoId = trabajador.turno.id,
                            empresaId = trabajador.empresa.id,
                            textoOcrNormalizado = claveNorm,
                            textoOcrOriginal = pendiente.textoOriginal,
                            trabajadorId = trabajador.id,
                            vecesUsada = (previa?.vecesUsada ?: 0) + 1,
                            creadoEn = previa?.creadoEn ?: ahora,
                            actualizadoEn = ahora
                        )
                    )
                }
            }
        }
    }

    suspend fun descartarPendienteDescarga(pendienteId: String) {
        recepcionDao.deleteById(pendienteId)
    }

    suspend fun reiniciarLoteFotosEmpresa(turno: Turno, empresa: EmpresaOficial) {
        database.withTransaction {
            val sesion = sesionDao.getOpenSessionByTurno(turno.id) ?: return@withTransaction
            recepcionDao.deleteBySesionTurnoAndEmpresa(sesion.id, turno.id, empresa.id)
        }
    }

    suspend fun previsualizarConciliacionDescarga(
        turno: Turno,
        empresa: EmpresaOficial
    ): PrevisualizacionConciliacion {
        return database.withTransaction {
            val sesion = sesionDao.getOpenSessionByTurno(turno.id)
            val trabajadoresEmpresa = trabajadorDao.getByTurnoOnce(turno.id)
                .filter { it.empresaId == empresa.id }
            val recepcionesEmpresa = if (sesion == null) {
                emptyList()
            } else {
                recepcionDao.getBySesionTurnoAndEmpresaOnce(sesion.id, turno.id, empresa.id)
            }
            val marcacionesTurno = if (sesion == null) {
                emptyMap()
            } else {
                marcacionDao.getBySesionAndTurnoOnce(sesion.id, turno.id)
                    .mapNotNull { m -> m.trabajadorId?.let { it to m } }
                    .toMap()
            }

            val idsConfirmadosEnFotos = recepcionesEmpresa
                .filter { it.confirmado && it.trabajadorId != null }
                .mapNotNull { it.trabajadorId }
                .toSet()

            val pasaranANoBajo = mutableListOf<TrabajadorConEstado>()
            var confirmadosCount = 0

            for (t in trabajadoresEmpresa) {
                if (t.id in idsConfirmadosEnFotos) {
                    confirmadosCount++
                } else {
                    val m = marcacionesTurno[t.id]
                    if (m?.siBajo == true) {
                        pasaranANoBajo.add(
                            TrabajadorConEstado(
                                id = t.id,
                                turno = turno,
                                empresa = empresa,
                                nombre = t.nombre,
                                primerApellido = t.primerApellido,
                                segundoApellido = t.segundoApellido,
                                habitacion = t.habitacion,
                                pabellon = t.pabellon,
                                siBajo = true,
                                esDatoPrueba = t.esDatoPrueba,
                                codigoInterno = t.codigoInterno
                            )
                        )
                    }
                }
            }

            PrevisualizacionConciliacion(
                turno = turno,
                empresa = empresa,
                totalTrabajadoresEmpresa = trabajadoresEmpresa.size,
                confirmadosEnFotosCount = confirmadosCount,
                pasaranANoBajoDesdeManualOVoz = TextoNormalizer.ordenarPorHabitacion(pasaranANoBajo),
                totalNoBajoFinal = trabajadoresEmpresa.size - confirmadosCount
            )
        }
    }

    /**
     * Concilia el conjunto completo y legible de fotografías de (turno, empresa):
     * - Bloquea si existen pendientes sin resolver o si no hay ninguna detección confirmada en el lote.
     * - Quienes aparecen en el conjunto de fotos quedan SÍ BAJÓ (siBajo = true).
     * - Quienes NO aparecen en el conjunto quedan NO BAJÓ (siBajo = false), conservando su origen previo.
     * - No afecta ninguna otra empresa ni el otro turno.
     */
    suspend fun finalizarYConciliarDescarga(
        turno: Turno,
        empresa: EmpresaOficial
    ): ResultadoConciliacionDescarga {
        return database.withTransaction {
            val ahora = System.currentTimeMillis()
            val sesion = sesionDao.getOpenSessionByTurno(turno.id)
                ?: throw IllegalStateException("No hay sesión abierta para ${turno.nombre}.")

            val recepcionesEmpresa = recepcionDao.getBySesionTurnoAndEmpresaOnce(
                sesionId = sesion.id,
                turnoId = turno.id,
                empresaId = empresa.id
            )
            val pendientes = recepcionesEmpresa.count { !it.confirmado }
            if (pendientes > 0) {
                throw IllegalStateException("Aún hay $pendientes nombres pendientes de identificación.")
            }

            val idsConfirmadosEnFotos = recepcionesEmpresa
                .filter { it.confirmado && it.trabajadorId != null }
                .mapNotNull { it.trabajadorId }
                .toSet()

            if (idsConfirmadosEnFotos.isEmpty()) {
                throw IllegalStateException("No hay trabajadores confirmados en las fotografías para conciliar.")
            }

            val trabajadoresEmpresa = trabajadorDao.getByTurnoOnce(turno.id)
                .filter { it.empresaId == empresa.id }
            val marcacionesExistentes = marcacionDao.getBySesionAndTurnoOnce(sesion.id, turno.id)
                .mapNotNull { m -> m.trabajadorId?.let { it to m } }
                .toMap()

            var revertidosANoBajo = 0
            var totalSiBajo = 0

            trabajadoresEmpresa.forEachIndexed { idx, entity ->
                val marcaActual = marcacionesExistentes[entity.id]
                val nombreCompleto = TextoNormalizer.formatearNombreObligatorio(
                    primerApellido = entity.primerApellido,
                    segundoApellido = entity.segundoApellido,
                    nombre = entity.nombre
                )

                if (entity.id in idsConfirmadosEnFotos) {
                    totalSiBajo++
                    val origenCombinado = combinarOrigen(
                        origenActual = marcaActual?.origen,
                        siBajoActual = marcaActual?.siBajo == true,
                        nuevoOrigen = "FOTO"
                    )
                    marcacionDao.upsert(
                        MarcacionEntity(
                            id = marcaActual?.id ?: UUID.randomUUID().toString(),
                            sesionId = sesion.id,
                            turnoId = turno.id,
                            trabajadorId = entity.id,
                            nombreCompletoSnapshot = nombreCompleto,
                            primerApellidoSnapshot = entity.primerApellido,
                            segundoApellidoSnapshot = entity.segundoApellido,
                            habitacionSnapshot = entity.habitacion,
                            pabellonSnapshot = entity.pabellon,
                            empresaIdSnapshot = empresa.id,
                            empresaNombreSnapshot = empresa.nombre,
                            siBajo = true,
                            esDatoPrueba = entity.esDatoPrueba,
                            origen = origenCombinado,
                            marcadoEn = marcaActual?.marcadoEn ?: (ahora + idx)
                        )
                    )
                } else {
                    if (marcaActual != null && marcaActual.siBajo) {
                        revertidosANoBajo++
                        // Cambia a NO BAJÓ conservando la trazabilidad de origen
                        marcacionDao.upsert(
                            marcaActual.copy(
                                nombreCompletoSnapshot = nombreCompleto,
                                primerApellidoSnapshot = entity.primerApellido,
                                segundoApellidoSnapshot = entity.segundoApellido,
                                habitacionSnapshot = entity.habitacion,
                                pabellonSnapshot = entity.pabellon,
                                empresaIdSnapshot = empresa.id,
                                empresaNombreSnapshot = empresa.nombre,
                                siBajo = false,
                                marcadoEn = ahora + idx
                            )
                        )
                    }
                }
            }

            ResultadoConciliacionDescarga(
                turno = turno,
                empresa = empresa,
                totalSiBajo = totalSiBajo,
                totalNoBajo = trabajadoresEmpresa.size - totalSiBajo,
                revertidosANoBajo = revertidosANoBajo
            )
        }
    }

    suspend fun verificarDuplicadoEnTurno(
        turno: Turno,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        empresa: EmpresaOficial,
        excluirTrabajadorId: String? = null
    ): AvisoDuplicado? {
        val existentes = trabajadorDao.getByTurnoOnce(turno.id)
            .filter { it.id != excluirTrabajadorId }

        val claveNueva = TextoNormalizer.claveNombreCompleto(nombre, primerApellido, segundoApellido)
        val claveBaseNueva = TextoNormalizer.claveNombreBase(nombre, primerApellido)
        val habNueva = TextoNormalizer.normalizar(habitacion)

        val exacto = existentes.firstOrNull { ex ->
            TextoNormalizer.claveNombreCompleto(ex.nombre, ex.primerApellido, ex.segundoApellido) == claveNueva &&
                TextoNormalizer.normalizar(ex.habitacion) == habNueva &&
                ex.empresaId == empresa.id
        }
        if (exacto != null) {
            return AvisoDuplicado(
                mensaje = "Ya existe un trabajador idéntico en ${turno.nombre}.",
                esDuplicadoExacto = true
            )
        }

        val similar = existentes.firstOrNull { ex ->
            TextoNormalizer.claveNombreCompleto(ex.nombre, ex.primerApellido, ex.segundoApellido) == claveNueva ||
                TextoNormalizer.claveNombreBase(ex.nombre, ex.primerApellido) == claveBaseNueva
        }
        if (similar != null) {
            val empSimilar = EmpresaOficial.porId(similar.empresaId)?.nombre ?: ""
            val nombreSimilar = TextoNormalizer.formatearNombreObligatorio(
                primerApellido = similar.primerApellido,
                segundoApellido = similar.segundoApellido,
                nombre = similar.nombre
            )
            return AvisoDuplicado(
                mensaje = "Posible coincidencia en ${turno.nombre}: $nombreSimilar (Hab. ${similar.habitacion}, $empSimilar). Verifique el segundo apellido.",
                esDuplicadoExacto = false
            )
        }

        return null
    }

    suspend fun agregarTrabajador(
        turno: Turno,
        empresa: EmpresaOficial,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        pabellon: String
    ): TrabajadorEntity {
        val ahora = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val prefijo = if (turno == Turno.A) "TA" else "TB"
        val codigo = "$prefijo-${id.take(6).uppercase()}"
        val entidad = TrabajadorEntity(
            id = id,
            turnoId = turno.id,
            empresaId = empresa.id,
            codigoInterno = codigo,
            nombre = nombre.trim(),
            primerApellido = primerApellido.trim(),
            segundoApellido = segundoApellido.trim(),
            habitacion = habitacion.trim(),
            pabellon = pabellon.trim(),
            esDatoPrueba = false,
            creadoEn = ahora,
            actualizadoEn = ahora
        )
        trabajadorDao.insert(entidad)
        return entidad
    }

    suspend fun actualizarTrabajador(
        trabajadorId: String,
        empresa: EmpresaOficial,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        pabellon: String
    ) {
        database.withTransaction {
            val actual = trabajadorDao.getById(trabajadorId) ?: return@withTransaction
            val actualizado = actual.copy(
                empresaId = empresa.id,
                nombre = nombre.trim(),
                primerApellido = primerApellido.trim(),
                segundoApellido = segundoApellido.trim(),
                habitacion = habitacion.trim(),
                pabellon = pabellon.trim(),
                actualizadoEn = System.currentTimeMillis()
            )
            trabajadorDao.update(actualizado)

            val sesionAbierta = sesionDao.getOpenSessionByTurno(actual.turnoId)
            if (sesionAbierta != null) {
                marcacionDao.updateSnapshotForWorker(
                    sesionId = sesionAbierta.id,
                    trabajadorId = actualizado.id,
                    nombreCompletoSnapshot = TextoNormalizer.formatearNombreObligatorio(
                        primerApellido = actualizado.primerApellido,
                        segundoApellido = actualizado.segundoApellido,
                        nombre = actualizado.nombre
                    ),
                    primerApellidoSnapshot = actualizado.primerApellido,
                    segundoApellidoSnapshot = actualizado.segundoApellido,
                    habitacionSnapshot = actualizado.habitacion,
                    pabellonSnapshot = actualizado.pabellon,
                    empresaIdSnapshot = empresa.id,
                    empresaNombreSnapshot = empresa.nombre
                )
            }
        }
    }

    suspend fun eliminarTrabajador(trabajadorId: String) {
        trabajadorDao.deleteById(trabajadorId)
    }

    suspend fun eliminarTrabajadoresPorIds(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        return database.withTransaction {
            trabajadorDao.deleteByIds(ids)
        }
    }

    suspend fun importarTrabajadores(
        filasConfirmadas: List<FilaImportacionConfirmada>
    ): ResultadoImportacion {
        if (filasConfirmadas.isEmpty()) {
            return ResultadoImportacion(
                insertados = 0,
                actualizados = 0,
                omitidos = 0,
                insertadosTurnoA = 0,
                insertadosTurnoB = 0
            )
        }

        var insertadosA = 0
        var insertadosB = 0
        var actualizadosA = 0
        var actualizadosB = 0
        var omitidos = 0
        val ahora = System.currentTimeMillis()

        database.withTransaction {
            val existentesA = trabajadorDao.getByTurnoOnce(Turno.A.id).toMutableList()
            val existentesB = trabajadorDao.getByTurnoOnce(Turno.B.id).toMutableList()
            val nuevosAInsertar = mutableListOf<TrabajadorEntity>()

            filasConfirmadas.forEachIndexed { idx, fila ->
                val existentesTurno = if (fila.turno == Turno.A) existentesA else existentesB

                when (fila.accion) {
                    AccionImportacion.OMITIR -> {
                        omitidos++
                    }

                    AccionImportacion.ACTUALIZAR_EXISTENTE -> {
                        val existenteId = fila.trabajadorExistenteId
                        val actual = existenteId?.let { idBuscado ->
                            existentesTurno.firstOrNull { it.id == idBuscado } ?: trabajadorDao.getById(idBuscado)
                        }
                        if (actual != null) {
                            val actualizado = actual.copy(
                                empresaId = fila.empresa.id,
                                nombre = fila.nombre.trim(),
                                primerApellido = fila.primerApellido.trim(),
                                segundoApellido = fila.segundoApellido.trim(),
                                habitacion = fila.habitacion.trim(),
                                pabellon = fila.pabellon.trim(),
                                actualizadoEn = ahora + idx
                            )
                            trabajadorDao.update(actualizado)

                            val pos = existentesTurno.indexOfFirst { it.id == actualizado.id }
                            if (pos >= 0) existentesTurno[pos] = actualizado

                            val sesionAbierta = sesionDao.getOpenSessionByTurno(actualizado.turnoId)
                            if (sesionAbierta != null) {
                                marcacionDao.updateSnapshotForWorker(
                                    sesionId = sesionAbierta.id,
                                    trabajadorId = actualizado.id,
                                    nombreCompletoSnapshot = TextoNormalizer.formatearNombreObligatorio(
                                        primerApellido = actualizado.primerApellido,
                                        segundoApellido = actualizado.segundoApellido,
                                        nombre = actualizado.nombre
                                    ),
                                    primerApellidoSnapshot = actualizado.primerApellido,
                                    segundoApellidoSnapshot = actualizado.segundoApellido,
                                    habitacionSnapshot = actualizado.habitacion,
                                    pabellonSnapshot = actualizado.pabellon,
                                    empresaIdSnapshot = fila.empresa.id,
                                    empresaNombreSnapshot = fila.empresa.nombre
                                )
                            }

                            if (fila.turno == Turno.A) actualizadosA++ else actualizadosB++
                        } else {
                            omitidos++
                        }
                    }

                    AccionImportacion.AGREGAR_NUEVO -> {
                        val claveFila = TextoNormalizer.claveNombreCompleto(
                            fila.nombre,
                            fila.primerApellido,
                            fila.segundoApellido
                        )
                        val habNorm = TextoNormalizer.normalizar(fila.habitacion)
                        val yaExisteIdentico = existentesTurno.any { ex ->
                            TextoNormalizer.claveNombreCompleto(
                                ex.nombre,
                                ex.primerApellido,
                                ex.segundoApellido
                            ) == claveFila &&
                                TextoNormalizer.normalizar(ex.habitacion) == habNorm &&
                                ex.empresaId == fila.empresa.id
                        }

                        if (yaExisteIdentico) {
                            omitidos++
                        } else {
                            val id = UUID.randomUUID().toString()
                            val prefijo = if (fila.turno == Turno.A) {
                                insertadosA++
                                "TA"
                            } else {
                                insertadosB++
                                "TB"
                            }
                            val entidad = TrabajadorEntity(
                                id = id,
                                turnoId = fila.turno.id,
                                empresaId = fila.empresa.id,
                                codigoInterno = "$prefijo-${id.take(6).uppercase()}",
                                nombre = fila.nombre.trim(),
                                primerApellido = fila.primerApellido.trim(),
                                segundoApellido = fila.segundoApellido.trim(),
                                habitacion = fila.habitacion.trim(),
                                pabellon = fila.pabellon.trim(),
                                esDatoPrueba = false,
                                creadoEn = ahora + idx,
                                actualizadoEn = ahora + idx
                            )
                            nuevosAInsertar.add(entidad)
                            existentesTurno.add(entidad)
                        }
                    }
                }
            }

            if (nuevosAInsertar.isNotEmpty()) {
                trabajadorDao.insertAll(nuevosAInsertar)
            }
        }

        return ResultadoImportacion(
            insertados = insertadosA + insertadosB,
            actualizados = actualizadosA + actualizadosB,
            omitidos = omitidos,
            insertadosTurnoA = insertadosA,
            insertadosTurnoB = insertadosB,
            actualizadosTurnoA = actualizadosA,
            actualizadosTurnoB = actualizadosB
        )
    }

    /**
     * LIMPIAR REGISTRO DE PRUEBA (por turno):
     * Elimina EXCLUSIVAMENTE los datos operativos temporales (marcaciones SÍ BAJÓ, registros de
     * recepción, comparaciones y sesión temporal) del turno indicado.
     * NUNCA elimina trabajadores, empresas, habitaciones, pabellones, turnos ni configuraciones.
     */
    suspend fun limpiarRegistroDePruebaPorTurno(turno: Turno): Int {
        return database.withTransaction {
            val marcacionesEliminadas = marcacionDao.deleteByTurno(turno.id)
            recepcionDao.deleteByTurno(turno.id)
            comparacionDao.deleteByTurno(turno.id)
            sesionDao.deleteByTurno(turno.id)
            sesionDao.insert(crearSesionInicial(turno))
            marcacionesEliminadas
        }
    }

    /**
     * Captura una instantánea consistente completa de todas las tablas de Room SQLite.
     */
    suspend fun capturarSnapshotBackup(): BackupSnapshot {
        ensureInitialized()
        return database.withTransaction {
            val turnos = turnoDao.getAllOnce()
            val empresas = empresaDao.getAllOnce()
            val trabajadores = trabajadorDao.getAllOnce()
            val sesiones = sesionDao.getAllOnce()
            val marcaciones = marcacionDao.getAllOnce()
            val recepciones = recepcionDao.getAllOnce()
            val comparaciones = comparacionDao.getAllOnce()
            val configuracion = configuracionDao.getAllOnce()
            val correccionesOcr = correccionOcrDao.getAllOnce()

            val ahora = System.currentTimeMillis()
            val fechaIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ahora))

            val resumen = BackupResumen(
                totalTrabajadores = trabajadores.size,
                totalTurnoA = trabajadores.count { it.turnoId == Turno.A.id },
                totalTurnoB = trabajadores.count { it.turnoId == Turno.B.id },
                totalEmpresas = empresas.size,
                totalTurnos = turnos.size,
                totalSesiones = sesiones.size,
                totalMarcaciones = marcaciones.size,
                totalRecepciones = recepciones.size,
                totalComparaciones = comparaciones.size,
                totalConfiguraciones = configuracion.size,
                totalCorreccionesOcr = correccionesOcr.size
            )

            BackupSnapshot(
                backupFormatVersion = BACKUP_FORMAT_VERSION,
                appName = "Lavandería Control",
                generadoEn = ahora,
                generadoEnIso = fechaIso,
                resumen = resumen,
                turnos = turnos,
                empresas = empresas,
                trabajadores = trabajadores,
                sesiones = sesiones,
                marcaciones = marcaciones,
                registrosRecepcion = recepciones,
                comparaciones = comparaciones,
                configuracion = configuracion,
                correccionesOcr = correccionesOcr
            )
        }
    }

    suspend fun exportarBackupJson(): Pair<BackupSnapshot, String> {
        val snapshot = capturarSnapshotBackup()
        val json = BackupSerializer.serializar(snapshot)
        return snapshot to json
    }

    fun validarBackupJson(jsonRaw: String): ResultadoValidacionBackup =
        BackupSerializer.validarYParsear(jsonRaw)

    /**
     * Restaura un respaldo previamente validado de forma atómica dentro de una transacción Room.
     * Antes de modificar la base actual, genera un respaldo preventivo automático.
     * Si ocurre cualquier error, la transacción se revierte por completo dejando la base intacta.
     */
    suspend fun restaurarBackupValidado(
        snapshot: BackupSnapshot,
        guardarRespaldoPreventivo: suspend (String) -> String?
    ): ResultadoRestauracionBackup {
        if (snapshot.backupFormatVersion !in BACKUP_FORMAT_MIN_SUPPORTED..BACKUP_FORMAT_VERSION) {
            return ResultadoRestauracionBackup.Error(
                mensaje = "Versión de copia de seguridad incompatible.",
                baseDatosIntacta = true
            )
        }

        val rutaRespaldoPreventivo = try {
            val (_, jsonActual) = exportarBackupJson()
            guardarRespaldoPreventivo(jsonActual)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return ResultadoRestauracionBackup.Error(
                mensaje = "No se pudo crear el respaldo preventivo previo (${e.message ?: "error de escritura"}). La base de datos actual no fue modificada.",
                baseDatosIntacta = true
            )
        }

        return try {
            seedMutex.withLock {
                database.withTransaction {
                    comparacionDao.deleteAll()
                    recepcionDao.deleteAll()
                    marcacionDao.deleteAll()
                    correccionOcrDao.deleteAll()
                    sesionDao.deleteAll()
                    trabajadorDao.deleteAll()
                    configuracionDao.deleteAll()

                    val turnosFinales = if (snapshot.turnos.isNotEmpty()) {
                        snapshot.turnos
                    } else {
                        listOf(
                            TurnoEntity(id = Turno.A.id, nombre = Turno.A.nombre),
                            TurnoEntity(id = Turno.B.id, nombre = Turno.B.nombre)
                        )
                    }
                    turnoDao.upsertAll(turnosFinales)

                    val empresasFinales = EmpresaOficial.todas.associateBy({ it.id }) { emp ->
                        EmpresaEntity(
                            id = emp.id,
                            codigo = emp.codigo,
                            nombre = emp.nombre,
                            colorHex = emp.colorHex
                        )
                    }.toMutableMap()
                    snapshot.empresas.forEach { emp ->
                        empresasFinales[emp.id] = emp
                    }
                    empresaDao.upsertAll(empresasFinales.values.toList())

                    if (snapshot.trabajadores.isNotEmpty()) {
                        trabajadorDao.insertAll(snapshot.trabajadores)
                    }
                    if (snapshot.sesiones.isNotEmpty()) {
                        sesionDao.insertAll(snapshot.sesiones)
                    }
                    if (snapshot.marcaciones.isNotEmpty()) {
                        marcacionDao.insertAll(snapshot.marcaciones)
                    }
                    if (snapshot.registrosRecepcion.isNotEmpty()) {
                        recepcionDao.insertAll(snapshot.registrosRecepcion)
                    }
                    if (snapshot.comparaciones.isNotEmpty()) {
                        comparacionDao.insertAll(snapshot.comparaciones)
                    }
                    if (snapshot.configuracion.isNotEmpty()) {
                        configuracionDao.insertAll(snapshot.configuracion)
                    }
                    if (snapshot.correccionesOcr.isNotEmpty()) {
                        correccionOcrDao.insertAll(snapshot.correccionesOcr)
                    }

                    if (configuracionDao.getValor(CLAVE_TURNO_ACTIVO) == null) {
                        configuracionDao.setValor(ConfiguracionEntity(CLAVE_TURNO_ACTIVO, Turno.A.id))
                    }
                    if (sesionDao.getOpenSessionByTurno(Turno.A.id) == null) {
                        sesionDao.insert(crearSesionInicial(Turno.A))
                    }
                    if (sesionDao.getOpenSessionByTurno(Turno.B.id) == null) {
                        sesionDao.insert(crearSesionInicial(Turno.B))
                    }
                }
            }
            ResultadoRestauracionBackup.Exito(
                resumen = snapshot.resumen,
                rutaRespaldoPreventivo = rutaRespaldoPreventivo
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ResultadoRestauracionBackup.Error(
                mensaje = "Error al restaurar la copia de seguridad (${e.message ?: "error interno"}). La base de datos fue revertida a su estado anterior.",
                baseDatosIntacta = true
            )
        }
    }
}
