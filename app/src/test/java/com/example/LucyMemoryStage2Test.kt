package com.example

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LavanderiaDatabase
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.Turno
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyEstadoEscrituraMemoria
import com.example.lucy.core.LucyIntent
import com.example.lucy.core.LucyIntentParserOffline
import com.example.lucy.core.LucyModuleHealth
import com.example.lucy.core.LucyModuleId
import com.example.lucy.core.LucyTurnContext
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.memory.LucyVelocidadRespuesta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LucyMemoryStage2Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: LavanderiaDatabase
    private lateinit var repository: LavanderiaRepository
    private val scopesToClose = mutableListOf<CoroutineScope>()

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LavanderiaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LavanderiaRepository(db)
        repository.ensureInitialized()
    }

    @After
    fun teardown() {
        runBlocking {
            scopesToClose.forEach { scope ->
                scope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
        scopesToClose.clear()
        db.close()
    }

    private fun createTempDataStore(file: File = tempFolder.newFile("lucy_mem_${System.nanoTime()}.preferences_pb")): Pair<DataStore<Preferences>, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopesToClose.add(scope)
        val ds = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file }
        )
        return ds to scope
    }

    private fun newCoordinator(memoryPort: DataStoreLucyMemoryPort): LucyCoreCoordinator {
        return LucyCoreCoordinator(
            repository = repository,
            memoryPort = memoryPort,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun `1 - guarda y persiste preferencias de conversacion e instrucciones permanentes entre reinicios`() = runTest {
        val backingFile = File(tempFolder.root, "persistencia_preferencias.preferences_pb")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds1 = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope1,
            produceFile = { backingFile }
        )
        val memory1 = DataStoreLucyMemoryPort(ds1, ioDispatcher = Dispatchers.Unconfined)
        val coord1 = newCoordinator(memory1)

        val rVel = coord1.procesarEntrada("Lucy, habla un poco más rápido", LucyTurnContext(Turno.A))
        assertFalse(rVel.esErrorRecuperable)
        assertTrue(rVel.texto.contains("Rápida"))

        val rEst = coord1.procesarEntrada("Prefiero que respondas más corto", LucyTurnContext(Turno.A))
        assertFalse(rEst.esErrorRecuperable)
        assertTrue(rEst.texto.contains("Corta"))

        val rInstr = coord1.procesarEntrada("Recuerda siempre indicar primero la habitación", LucyTurnContext(Turno.A))
        assertFalse(rInstr.esErrorRecuperable)

        // Cerrar primera instancia DataStore y reabrir archivo desde cero
        scope1.coroutineContext[Job]?.cancelAndJoin()

        val (ds2, _) = createTempDataStore(backingFile)
        val memory2 = DataStoreLucyMemoryPort(ds2, ioDispatcher = Dispatchers.Unconfined)
        val coord2 = newCoordinator(memory2)

        val prefs = memory2.obtenerPreferencias()
        assertEquals(LucyVelocidadRespuesta.RAPIDA, prefs.velocidad)
        assertEquals(LucyEstiloRespuesta.CONCISA, prefs.estilo)
        assertEquals(1, prefs.instruccionesPermanentes.size)
        assertTrue(prefs.instruccionesPermanentes.first().texto.contains("indicar primero la habitación"))

        // Preferencias compartidas también están disponibles desde Turno B
        val consultaPrefs = coord2.procesarEntrada("¿Cuáles son mis preferencias?", LucyTurnContext(Turno.B))
        assertTrue(consultaPrefs.texto.contains("Rápida"))
        assertTrue(consultaPrefs.texto.contains("Corta"))
        assertTrue(consultaPrefs.texto.contains("indicar primero la habitación"))
    }

    @Test
    fun `2 - actualiza preferencias existentes y evita duplicados en instrucciones y notas`() = runTest {
        val (ds, _) = createTempDataStore()
        val memory = DataStoreLucyMemoryPort(ds, ioDispatcher = Dispatchers.Unconfined)
        val coord = newCoordinator(memory)

        // Repetir preferencia idéntica no falla ni duplica
        coord.procesarEntrada("Lucy, habla un poco más rápido", LucyTurnContext(Turno.A))
        val repetida = memory.actualizarVelocidadRespuesta(LucyVelocidadRespuesta.RAPIDA)
        assertEquals(LucyEstadoEscrituraMemoria.YA_EXISTIA, repetida.estado)

        // Actualizar velocidad existente
        val actualizada = memory.actualizarVelocidadRespuesta(LucyVelocidadRespuesta.LENTA)
        assertEquals(LucyEstadoEscrituraMemoria.ACTUALIZADO, actualizada.estado)
        assertEquals(LucyVelocidadRespuesta.LENTA, memory.obtenerPreferencias().velocidad)

        // Repetir instrucción permanente evita duplicados
        coord.procesarEntrada("Recuerda siempre hablar en español claro", LucyTurnContext(Turno.A))
        coord.procesarEntrada("Recuerda siempre hablar en español claro", LucyTurnContext(Turno.A))
        assertEquals(1, memory.obtenerPreferencias().instruccionesPermanentes.size)

        // Repetir nota operativa evita duplicados
        coord.procesarEntrada("Recuerda que Finning revisa primero turno mañana", LucyTurnContext(Turno.A))
        coord.procesarEntrada("Recuerda que Finning revisa primero turno mañana", LucyTurnContext(Turno.A))
        val notas = memory.consultarNotasOperativas(Turno.A, EmpresaOficial.FINNING)
        assertEquals(1, notas.size)
    }

    @Test
    fun `3 - elimina recuerdos y preferencias especificas cuando el usuario lo pide`() = runTest {
        val (ds, _) = createTempDataStore()
        val memory = DataStoreLucyMemoryPort(ds, ioDispatcher = Dispatchers.Unconfined)
        val coord = newCoordinator(memory)

        // Configurar velocidad y estilo, luego olvidar última preferencia (estilo)
        coord.procesarEntrada("Lucy, habla un poco más rápido", LucyTurnContext(Turno.A))
        coord.procesarEntrada("Prefiero que respondas más corto", LucyTurnContext(Turno.A))

        val olvidoUltimaPref = coord.procesarEntrada("Olvida esa preferencia", LucyTurnContext(Turno.A))
        assertFalse(olvidoUltimaPref.esErrorRecuperable)
        val prefsTrasOlvido = memory.obtenerPreferencias()
        assertEquals(LucyEstiloRespuesta.NORMAL, prefsTrasOlvido.estilo)
        assertEquals(LucyVelocidadRespuesta.RAPIDA, prefsTrasOlvido.velocidad)

        // Agregar instrucción permanente y luego olvidar todas las preferencias
        coord.procesarEntrada("Recuerda siempre confirmar antes de salir", LucyTurnContext(Turno.A))
        val olvidoTodas = coord.procesarEntrada("Olvida todas mis preferencias", LucyTurnContext(Turno.A))
        assertFalse(olvidoTodas.esErrorRecuperable)
        val prefsLimpias = memory.obtenerPreferencias()
        assertEquals(LucyVelocidadRespuesta.NORMAL, prefsLimpias.velocidad)
        assertEquals(LucyEstiloRespuesta.NORMAL, prefsLimpias.estilo)
        assertTrue(prefsLimpias.instruccionesPermanentes.isEmpty())

        // Guardar notas para Finning y Komatsu en Turno A
        coord.procesarEntrada("Recuerda que Finning baja por puerta sur", LucyTurnContext(Turno.A))
        coord.procesarEntrada("Recuerda que Komatsu tiene ropa especial", LucyTurnContext(Turno.A))
        assertEquals(1, memory.consultarNotasOperativas(Turno.A, EmpresaOficial.FINNING).size)
        assertEquals(1, memory.consultarNotasOperativas(Turno.A, EmpresaOficial.KOMATSU).size)

        // Consultar Komatsu para establecerla como empresa actual y pedir olvidar sobre "esta empresa"
        coord.procesarEntrada("resumen Komatsu", LucyTurnContext(Turno.A))
        val olvidoEmpresa = coord.procesarEntrada("Olvida lo que te dije sobre esta empresa", LucyTurnContext(Turno.A))
        assertFalse(olvidoEmpresa.esErrorRecuperable)
        assertTrue(memory.consultarNotasOperativas(Turno.A, EmpresaOficial.KOMATSU).isEmpty())
        assertEquals(1, memory.consultarNotasOperativas(Turno.A, EmpresaOficial.FINNING).size)

        // Olvidar ese recuerdo (elimina la última nota restante en Turno A)
        val olvidoRecuerdo = coord.procesarEntrada("Olvida ese recuerdo", LucyTurnContext(Turno.A))
        assertFalse(olvidoRecuerdo.esErrorRecuperable)
        assertTrue(memory.consultarNotasOperativas(Turno.A).isEmpty())
    }

    @Test
    fun `4 - estricta separacion por turno entre Turno A y Turno B sin contaminacion`() = runTest {
        val wA = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.FINNING,
            nombre = "Carlos",
            primerApellido = "Mena",
            segundoApellido = "Soto",
            habitacion = "101",
            pabellon = "A"
        )
        repository.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Pedro",
            primerApellido = "Araya",
            segundoApellido = "Díaz",
            habitacion = "205",
            pabellon = "B"
        )

        val (ds, _) = createTempDataStore()
        val memory = DataStoreLucyMemoryPort(ds, ioDispatcher = Dispatchers.Unconfined)
        val coord = newCoordinator(memory)

        // En Turno A: trabajar con Finning, buscar trabajador y guardar nota
        coord.procesarEntrada("resumen Finning", LucyTurnContext(Turno.A))
        coord.procesarEntrada("buscar Mena", LucyTurnContext(Turno.A))
        coord.procesarEntrada("Recuerda que Finning entrega planilla tarde", LucyTurnContext(Turno.A))

        // En Turno B: NO debe heredar ni empresa anterior, ni trabajador anterior, ni notas de Turno A
        val empresaEnB = coord.procesarEntrada("¿quiénes faltan de la empresa anterior?", LucyTurnContext(Turno.B))
        assertTrue(empresaEnB.esErrorRecuperable)
        assertTrue(empresaEnB.texto.contains("No hay una empresa reciente recordada en Turno B"))

        val trabajadorEnB = coord.procesarEntrada("¿ya bajó el trabajador que buscamos recién?", LucyTurnContext(Turno.B))
        assertTrue(trabajadorEnB.esErrorRecuperable)
        assertTrue(trabajadorEnB.texto.contains("No hay un trabajador reciente recordado en Turno B"))

        val notasEnB = coord.procesarEntrada("¿Qué te había dicho sobre Finning?", LucyTurnContext(Turno.B))
        assertTrue(notasEnB.texto.contains("No tengo recuerdos guardados"))

        // En Turno A, al preguntar sobre "esta empresa", recupera la nota de Turno A
        val notasEnA = coord.procesarEntrada("¿Qué te había dicho sobre esta empresa?", LucyTurnContext(Turno.A))
        assertTrue(notasEnA.texto.contains("entrega planilla tarde"))

        // Guardar explícitamente para Turno B estando en Turno A ("Recuerda que esta empresa pertenece al Turno B")
        val notaParaB = coord.procesarEntrada(
            "Recuerda que esta empresa pertenece al Turno B",
            LucyTurnContext(Turno.A, empresaActiva = EmpresaOficial.FINNING)
        )
        assertTrue(notaParaB.texto.contains("Turno B"))

        // Se guardó en Turno B y NO contaminó notas ni ultimaEmpresa de Turno B ni de Turno A
        val notasGuardadasB = memory.consultarNotasOperativas(Turno.B, EmpresaOficial.FINNING)
        assertEquals(1, notasGuardadasB.size)
        assertTrue(notasGuardadasB.first().texto.contains("Finning pertenece al Turno B"))
        assertNull(memory.resolveLastReferencedEmpresa(Turno.B))
        assertEquals(wA.id, memory.resolveLastReferencedWorkerId(Turno.A))
        assertNull(memory.resolveLastReferencedWorkerId(Turno.B))
    }

    @Test
    fun `5 - recupera referencias anaforicas reales y mantiene confirmacion obligatoria al marcar`() = runTest {
        val tFinning = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.FINNING,
            nombre = "Luis",
            primerApellido = "Mendoza",
            segundoApellido = "Quispe",
            habitacion = "108",
            pabellon = "B"
        )
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Mario",
            primerApellido = "Rojas",
            segundoApellido = "Vega",
            habitacion = "112",
            pabellon = "A"
        )

        val (ds, _) = createTempDataStore()
        val memory = DataStoreLucyMemoryPort(ds, ioDispatcher = Dispatchers.Unconfined)
        val coord = newCoordinator(memory)

        // 1. Consultar Finning -> preguntar por "la empresa anterior"
        coord.procesarEntrada("resumen Finning", LucyTurnContext(Turno.A))
        val faltanAnterior = coord.procesarEntrada("¿quiénes faltan de la empresa anterior?", LucyTurnContext(Turno.A))
        assertFalse(faltanAnterior.esErrorRecuperable)
        assertTrue(faltanAnterior.texto.contains("Finning"))
        assertEquals(1, faltanAnterior.trabajadoresRelacionados.size)
        assertEquals(tFinning.id, faltanAnterior.trabajadoresRelacionados.first().id)

        // 2. Buscar trabajador -> consultar estado usando referencia anafórica
        val busq = coord.procesarEntrada("buscar Mendoza", LucyTurnContext(Turno.A))
        assertEquals(1, busq.trabajadoresRelacionados.size)

        val estadoReciente = coord.procesarEntrada("¿ya bajó el trabajador que buscamos recién?", LucyTurnContext(Turno.A))
        assertFalse(estadoReciente.esErrorRecuperable)
        assertTrue(estadoReciente.texto.contains("MENDOZA", ignoreCase = true))
        assertTrue(estadoReciente.texto.contains("NO BAJÓ"))

        // 3. Solicitar marcación sobre trabajador reciente REQUIERE confirmación y no muta BD antes de confirmar
        val solMarcar = coord.procesarEntrada("marca al trabajador que buscamos recién", LucyTurnContext(Turno.A))
        val confirmacion = solMarcar.confirmacionRequerida
        assertNotNull(confirmacion)
        assertEquals(tFinning.id, confirmacion!!.trabajador.id)
        assertFalse(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == tFinning.id }.siBajo)

        // Confirmar aplica la marcación real
        val confirmada = coord.confirmarAccionPendiente(confirmacion)
        assertFalse(confirmada.esErrorRecuperable)
        assertTrue(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == tFinning.id }.siBajo)

        // Conflicto de turno al referenciar trabajador reciente en otro turno sigue bloqueado
        val conflicto = coord.procesarEntrada("marca al trabajador que buscamos recién en Turno B", LucyTurnContext(Turno.A))
        assertTrue(conflicto.esErrorRecuperable)
        assertNull(conflicto.confirmacionRequerida)
    }

    @Test
    fun `6 - comentarios casuales y preguntas de lectura nunca se guardan como recuerdos ni preferencias`() = runTest {
        val (ds, _) = createTempDataStore()
        val memory = DataStoreLucyMemoryPort(ds, ioDispatcher = Dispatchers.Unconfined)
        val coord = newCoordinator(memory)

        val entradasNoPersistentes = listOf(
            "hola Lucy",
            "gracias",
            "hoy hace mucho frío",
            "ok",
            "¿cuántos faltan?",
            "¿Qué te había dicho sobre esta empresa?"
        )

        entradasNoPersistentes.forEach { entrada ->
            coord.procesarEntrada(entrada, LucyTurnContext(Turno.A))
        }

        val resumen = memory.obtenerResumenMemoria()
        assertNotNull(resumen)
        assertEquals(LucyVelocidadRespuesta.NORMAL, resumen!!.preferencias.velocidad)
        assertEquals(LucyEstiloRespuesta.NORMAL, resumen.preferencias.estilo)
        assertTrue(resumen.preferencias.instruccionesPermanentes.isEmpty())
        assertTrue(resumen.turnoA.notas.isEmpty())
        assertTrue(resumen.turnoB.notas.isEmpty())
        assertNull(resumen.turnoA.ultimoTrabajadorId)
        assertNull(resumen.turnoA.ultimaEmpresaCodigo)
    }

    @Test
    fun `7 - maneja almacenamiento vacio, JSON corrupto, archivo corrupto, error de IO y VirtualMachineError`() = runTest {
        // A. Almacenamiento vacío y JSON corrupto en DataStore
        val (dsCorruptJson, _) = createTempDataStore()
        dsCorruptJson.edit { prefs ->
            prefs[stringPreferencesKey("lucy_pref_instrucciones_json")] = "{corrupto[[["
            prefs[stringPreferencesKey("lucy_turno_a_notas_json")] = "NO_ES_JSON"
        }
        val memJsonCorrupto = DataStoreLucyMemoryPort(dsCorruptJson, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(LucyModuleHealth.Ready, memJsonCorrupto.healthCheck())
        assertTrue(memJsonCorrupto.obtenerPreferencias().instruccionesPermanentes.isEmpty())
        assertTrue(memJsonCorrupto.consultarNotasOperativas(Turno.A).isEmpty())

        // B. Archivo binario corrupto recuperado mediante ReplaceFileCorruptionHandler
        val archivoCorrupto = tempFolder.newFile("corrupto.preferences_pb")
        archivoCorrupto.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x7f, -0x01, -0x02))
        val (dsRecuperado, _) = createTempDataStore(archivoCorrupto)
        val memRecuperada = DataStoreLucyMemoryPort(dsRecuperado, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(LucyModuleHealth.Ready, memRecuperada.healthCheck())
        val escrituraPostCorrupcion = memRecuperada.guardarNotaOperativa(Turno.A, "Prueba recuperada", EmpresaOficial.FINNING)
        assertEquals(LucyEstadoEscrituraMemoria.GUARDADO, escrituraPostCorrupcion.estado)

        // C. Fallo de IO en DataStore degrada Memory pero Core sigue operando con datos reales
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Ana",
            primerApellido = "Torres",
            segundoApellido = "Gil",
            habitacion = "15",
            pabellon = "SUR"
        )
        val failingDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                throw IOException("Disco simulado no disponible")
            }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw IOException("Escritura simulada denegada")
            }
        }
        val memFailing = DataStoreLucyMemoryPort(failingDataStore, ioDispatcher = Dispatchers.Unconfined)
        val coordFailing = newCoordinator(memFailing)

        val salud = coordFailing.refrescarSaludModulos()
        assertEquals(LucyModuleHealth.Ready, salud[LucyModuleId.CORE])
        assertTrue(salud[LucyModuleId.MEMORY] is LucyModuleHealth.Unavailable)

        val resPrefError = coordFailing.procesarEntrada("Lucy, habla un poco más rápido", LucyTurnContext(Turno.A))
        assertTrue(resPrefError.esErrorRecuperable)
        assertTrue(coordFailing.saludModulos.value[LucyModuleId.MEMORY] is LucyModuleHealth.Degraded)

        // Core sigue respondiendo consultas reales sin caer
        val consultaReal = coordFailing.procesarEntrada("buscar Torres", LucyTurnContext(Turno.A))
        assertFalse(consultaReal.esErrorRecuperable)
        assertEquals(1, consultaReal.trabajadoresRelacionados.size)
        assertEquals(LucyModuleHealth.Ready, coordFailing.saludModulos.value[LucyModuleId.CORE])

        // D. VirtualMachineError nunca se oculta
        val oomDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                throw OutOfMemoryError("OOM simulado en DataStore")
            }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw CorruptionException("no usado")
            }
        }
        val memOom = DataStoreLucyMemoryPort(oomDataStore, ioDispatcher = Dispatchers.Unconfined)
        var oomPropagado = false
        try {
            memOom.healthCheck()
        } catch (_: OutOfMemoryError) {
            oomPropagado = true
        }
        assertTrue(oomPropagado)
    }

    @Test
    fun `8 - parser clasifica correctamente memoria, olvido, preferencias y referencias`() {
        val prefVel = LucyIntentParserOffline.parse("Lucy, habla un poco más rápido")
        assertTrue(prefVel is LucyIntent.ConfigurarPreferenciaConversacion)
        assertEquals(LucyVelocidadRespuesta.RAPIDA, (prefVel as LucyIntent.ConfigurarPreferenciaConversacion).velocidad)

        val prefEst = LucyIntentParserOffline.parse("Prefiero que respondas más corto")
        assertTrue(prefEst is LucyIntent.ConfigurarPreferenciaConversacion)
        assertEquals(LucyEstiloRespuesta.CONCISA, (prefEst as LucyIntent.ConfigurarPreferenciaConversacion).estilo)

        val notaTurnoB = LucyIntentParserOffline.parse("Recuerda que esta empresa pertenece al Turno B")
        assertTrue(notaTurnoB is LucyIntent.GuardarNotaOperativa)
        notaTurnoB as LucyIntent.GuardarNotaOperativa
        assertTrue(notaTurnoB.usarEmpresaAnterior)
        assertEquals(Turno.B, notaTurnoB.turnoDestino)

        val pregEmpresa = LucyIntentParserOffline.parse("¿Qué te había dicho sobre esta empresa?")
        assertTrue(pregEmpresa is LucyIntent.ConsultarMemoria)
        assertTrue((pregEmpresa as LucyIntent.ConsultarMemoria).usarEmpresaAnterior)

        val olvidoEmpresa = LucyIntentParserOffline.parse("Olvida lo que te dije sobre esta empresa")
        assertTrue(olvidoEmpresa is LucyIntent.OlvidarMemoria)
        assertTrue((olvidoEmpresa as LucyIntent.OlvidarMemoria).usarEmpresaAnterior)

        val olvidoTodas = LucyIntentParserOffline.parse("Olvida todas mis preferencias")
        assertTrue(olvidoTodas is LucyIntent.OlvidarMemoria)

        val casual = LucyIntentParserOffline.parse("hoy hace mucho frío")
        assertTrue(casual is LucyIntent.NoComprendido)
    }
}
