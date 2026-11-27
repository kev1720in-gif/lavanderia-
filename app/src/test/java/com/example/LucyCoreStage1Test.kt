package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LavanderiaDatabase
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.Turno
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyExecutionMode
import com.example.lucy.core.LucyIntent
import com.example.lucy.core.LucyIntentParserOffline
import com.example.lucy.core.LucyMemoryPort
import com.example.lucy.core.LucyModuleHealth
import com.example.lucy.core.LucyModuleId
import com.example.lucy.core.LucyTurnContext
import com.example.lucy.core.LucyVisionOutcome
import com.example.lucy.core.LucyVisionPort
import com.example.lucy.core.LucyVoicePort
import com.example.lucy.ui.LucyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LucyCoreStage1Test {

    private lateinit var context: Context
    private lateinit var db: LavanderiaDatabase
    private lateinit var repository: LavanderiaRepository

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
        db.close()
    }

    @Test
    fun `1 - parser offline reconoce intenciones principales en espanol sin red`() {
        assertTrue(LucyIntentParserOffline.parse("ayuda") is LucyIntent.Ayuda)

        val cambioB = LucyIntentParserOffline.parse("cambia a Turno B")
        assertTrue(cambioB is LucyIntent.CambiarTurno)
        assertEquals(Turno.B, (cambioB as LucyIntent.CambiarTurno).nuevoTurno)

        val resumen = LucyIntentParserOffline.parse("¿cuántos faltan en Turno B?")
        assertTrue(resumen is LucyIntent.ResumenTurno)
        assertEquals(Turno.B, (resumen as LucyIntent.ResumenTurno).turnoSolicitado)

        val pendientes = LucyIntentParserOffline.parse("quiénes faltan de Finning")
        assertTrue(pendientes is LucyIntent.ConsultarPendientes)
        assertEquals(EmpresaOficial.FINNING, (pendientes as LucyIntent.ConsultarPendientes).empresa)

        val estado = LucyIntentParserOffline.parse("¿ya bajó Rojas?")
        assertTrue(estado is LucyIntent.ConsultarEstadoTrabajador)
        assertEquals("rojas", (estado as LucyIntent.ConsultarEstadoTrabajador).query)

        val buscar = LucyIntentParserOffline.parse("buscar hab 204")
        assertTrue(buscar is LucyIntent.BuscarTrabajador)
        assertEquals("hab 204", (buscar as LucyIntent.BuscarTrabajador).query)

        val marcar = LucyIntentParserOffline.parse("marca a Pérez González")
        assertTrue(marcar is LucyIntent.SolicitarMarcacion)
        marcar as LucyIntent.SolicitarMarcacion
        assertTrue(marcar.marcarComoSiBajo)
        assertEquals("perez gonzalez", marcar.query)

        val desmarcar = LucyIntentParserOffline.parse("desmarca a Rojas")
        assertTrue(desmarcar is LucyIntent.SolicitarMarcacion)
        desmarcar as LucyIntent.SolicitarMarcacion
        assertFalse(desmarcar.marcarComoSiBajo)
        assertEquals("rojas", desmarcar.query)
    }

    @Test
    fun `2 - parser distingue Komatsu ESOP y Finning Pala sin confundir nombres cortos`() {
        val kEsop = LucyIntentParserOffline.parse("resumen Komatsu ESOP") as LucyIntent.ResumenTurno
        assertEquals(EmpresaOficial.KOMATSU_ESOP, kEsop.empresa)

        val kBase = LucyIntentParserOffline.parse("resumen Komatsu") as LucyIntent.ResumenTurno
        assertEquals(EmpresaOficial.KOMATSU, kBase.empresa)

        val fPala = LucyIntentParserOffline.parse("quiénes faltan de Finning Pala") as LucyIntent.ConsultarPendientes
        assertEquals(EmpresaOficial.FINNING_PALA, fPala.empresa)

        val fBase = LucyIntentParserOffline.parse("quiénes faltan de Finning") as LucyIntent.ConsultarPendientes
        assertEquals(EmpresaOficial.FINNING, fBase.empresa)
    }

    @Test
    fun `3 - coordinador opera aislado por turno y nunca mezcla trabajadores de Turno A y Turno B`() = runTest {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Carlos",
            primerApellido = "Vega",
            segundoApellido = "Soto",
            habitacion = "101",
            pabellon = "A"
        )
        repository.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.FINNING,
            nombre = "Pedro",
            primerApellido = "Araya",
            segundoApellido = "Díaz",
            habitacion = "305",
            pabellon = "C"
        )

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val respA = coordinator.procesarEntrada("buscar Araya", LucyTurnContext(Turno.A))
        assertTrue(respA.texto.contains("No encontré trabajadores"))
        assertTrue(respA.trabajadoresRelacionados.isEmpty())

        val respB = coordinator.procesarEntrada("resumen", LucyTurnContext(Turno.B))
        assertEquals(Turno.B, respB.turnoContexto)
        assertTrue(respB.texto.contains("Turno B"))
        assertTrue(respB.texto.contains("1 en maestro"))
        assertEquals(LucyExecutionMode.OFFLINE_ONLY, respB.modoEjecucion)
    }

    @Test
    fun `4 - marcacion requiere confirmacion explicita, es idempotente y respeta el turno activo`() = runTest {
        val tA = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Luis",
            primerApellido = "Mendoza",
            segundoApellido = "Quispe",
            habitacion = "108",
            pabellon = "B"
        )
        val tB = repository.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Luis",
            primerApellido = "Mendoza",
            segundoApellido = "Quispe",
            habitacion = "208",
            pabellon = "C"
        )

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )

        // Solicitar marcación en Turno A NO cambia la BD antes de confirmar
        val solicitud = coordinator.procesarEntrada(
            entrada = "marca a Mendoza Quispe",
            contexto = LucyTurnContext(Turno.A)
        )
        val confirmacion = solicitud.confirmacionRequerida
        assertNotNull(confirmacion)
        assertEquals(tA.id, confirmacion!!.trabajador.id)
        assertFalse(
            repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == tA.id }.siBajo
        )

        // Confirmar sí marca en Turno A y deja intacto Turno B
        val ejecutada = coordinator.confirmarAccionPendiente(confirmacion)
        assertFalse(ejecutada.esErrorRecuperable)
        assertTrue(
            repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == tA.id }.siBajo
        )
        assertFalse(
            repository.observeTrabajadoresConEstado(Turno.B).first().first { it.id == tB.id }.siBajo
        )

        // Re-confirmar idempotente no desmarca por accidente
        val segundaVez = coordinator.confirmarAccionPendiente(confirmacion)
        assertTrue(segundaVez.texto.contains("ya figuraba como SÍ BAJÓ"))
        assertTrue(
            repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == tA.id }.siBajo
        )
    }

    @Test
    fun `5 - solicitud ambigua devuelve candidatos de desambiguacion sin alterar marcaciones`() = runTest {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.SOMACOR,
            nombre = "Juan",
            primerApellido = "Pérez",
            segundoApellido = "Rojas",
            habitacion = "12",
            pabellon = "SUR"
        )
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.SOMACOR,
            nombre = "Diego",
            primerApellido = "Pérez",
            segundoApellido = "Torres",
            habitacion = "14",
            pabellon = "SUR"
        )

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val respuesta = coordinator.procesarEntrada("desmarca a Pérez", LucyTurnContext(Turno.A))
        assertNull(respuesta.confirmacionRequerida)
        assertEquals(2, respuesta.candidatosDesambiguacion.size)
        assertFalse(respuesta.accionDesambiguacionSiBajo)
    }

    @Test
    fun `6 - fallo critico UnsatisfiedLinkError en Vision queda aislado sin afectar Core ni Voice`() = runTest {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.CUMMINS,
            nombre = "Mario",
            primerApellido = "Silva",
            segundoApellido = "Paredes",
            habitacion = "44",
            pabellon = "NORTE"
        )

        val visionConFalloNativo = object : LucyVisionPort {
            override val providerName: String = "Fallo Nativo Simulado"
            override suspend fun healthCheck(): LucyModuleHealth =
                throw UnsatisfiedLinkError("dlopen failed: libc++_shared.so 4KB alignment")

            override suspend fun extractPlanillaLines(imagePath: String): LucyVisionOutcome =
                throw UnsatisfiedLinkError("libonnxruntime4j_jni.so failed")
        }
        val voiceSana = object : LucyVoicePort {
            override suspend fun healthCheck(): LucyModuleHealth = LucyModuleHealth.Ready
            override suspend fun speakIfEnabled(text: String) = Unit
        }
        val memoryConError = object : LucyMemoryPort {
            override suspend fun healthCheck(): LucyModuleHealth = LucyModuleHealth.Ready
            override suspend fun recordInteraction(turno: Turno, userInput: String, assistantResponse: String) {
                throw IllegalStateException("Error simulado al grabar contexto")
            }
            override suspend fun resolveLastReferencedWorkerId(turno: Turno): String? = null
            override suspend fun rememberReferencedWorker(turno: Turno, trabajadorId: String) {
                throw RuntimeException("Error simulado de recuerdo")
            }
        }

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            visionPort = visionConFalloNativo,
            voicePort = voiceSana,
            memoryPort = memoryConError,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val salud = coordinator.refrescarSaludModulos()
        assertEquals(LucyModuleHealth.Ready, salud[LucyModuleId.CORE])
        assertEquals(LucyModuleHealth.Ready, salud[LucyModuleId.VOICE])
        assertTrue(salud[LucyModuleId.VISION] is LucyModuleHealth.Unavailable)

        // Incluso tras un UnsatisfiedLinkError en Vision, no lanza excepción ni cierra la app
        val visionOutcome = coordinator.ejecutarLecturaPlanillaAislada("/tmp/fake.jpg", Turno.A)
        assertTrue(visionOutcome is LucyVisionOutcome.Failed)
        assertTrue(coordinator.saludModulos.value[LucyModuleId.VISION] is LucyModuleHealth.Unavailable)

        // Core sigue atendiendo consultas reales normalmente aunque Memory y Vision hayan fallado
        val consulta = coordinator.procesarEntrada("buscar Silva", LucyTurnContext(Turno.A))
        assertFalse(consulta.esErrorRecuperable)
        assertEquals(1, consulta.trabajadoresRelacionados.size)
        assertEquals(LucyModuleHealth.Ready, coordinator.saludModulos.value[LucyModuleId.CORE])
        assertEquals(LucyModuleHealth.Ready, coordinator.saludModulos.value[LucyModuleId.VOICE])
    }

    @Test
    fun `7 - LucyViewModel mantiene historial separado por turno y confirma o cancela marcaciones`() {
        val wA = kotlinx.coroutines.runBlocking {
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.FLUITEK,
                nombre = "Raúl",
                primerApellido = "Campos",
                segundoApellido = "Vera",
                habitacion = "55",
                pabellon = "A"
            ).also {
                repository.agregarTrabajador(
                    turno = Turno.B,
                    empresa = EmpresaOficial.FLUITEK,
                    nombre = "Hugo",
                    primerApellido = "Núñez",
                    segundoApellido = "Lagos",
                    habitacion = "66",
                    pabellon = "B"
                )
            }
        }

        val viewModel = LucyViewModel(
            repository = repository,
            coordinator = LucyCoreCoordinator(
                repository = repository,
                ioDispatcher = Dispatchers.Unconfined,
                defaultDispatcher = Dispatchers.Unconfined
            )
        )
        val collectorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        val collectorJob = collectorScope.launch {
            viewModel.uiState.collect {}
        }

        waitForState { viewModel.uiState.value.turnoActivo == Turno.A }
        viewModel.enviarConsulta("marca a Campos Vera")
        waitForState {
            viewModel.uiState.value.confirmacionPendiente != null &&
                viewModel.uiState.value.historialTurnoActivo.size == 2
        }

        // Cambiar a Turno B limpia visualmente la vista de conversación al contexto de Turno B
        kotlinx.coroutines.runBlocking { repository.setTurnoActivo(Turno.B) }
        waitForState { viewModel.uiState.value.turnoActivo == Turno.B }
        assertTrue(viewModel.uiState.value.historialTurnoActivo.isEmpty())
        assertNull(viewModel.uiState.value.confirmacionPendiente)

        viewModel.enviarConsulta("resumen")
        waitForState {
            !viewModel.uiState.value.procesando &&
                viewModel.uiState.value.historialTurnoActivo.size == 2
        }
        assertTrue(viewModel.uiState.value.historialTurnoActivo.last().texto.contains("Turno B"))

        // Volver a Turno A recupera su conversación y confirmación pendiente intactas
        kotlinx.coroutines.runBlocking { repository.setTurnoActivo(Turno.A) }
        waitForState { viewModel.uiState.value.turnoActivo == Turno.A }
        assertNotNull(viewModel.uiState.value.confirmacionPendiente)

        // Probar cancelación sin modificar BD
        viewModel.cancelarAccionPendiente()
        waitForState { viewModel.uiState.value.confirmacionPendiente == null }
        assertFalse(
            kotlinx.coroutines.runBlocking {
                repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == wA.id }.siBajo
            }
        )

        // Solicitar nuevamente y confirmar marcación real en Turno A
        viewModel.enviarConsulta("marca a Campos Vera")
        waitForState { viewModel.uiState.value.confirmacionPendiente != null }
        viewModel.confirmarAccionPendiente()
        waitForState {
            !viewModel.uiState.value.procesando &&
                kotlinx.coroutines.runBlocking {
                    repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == wA.id }.siBajo
                }
        }

        collectorJob.cancel()
    }

    @Test
    fun `8 - errores fatales VirtualMachineError se relanzan mientras LinkageError de Vision sigue aislado`() = runTest {
        val visionFatal = object : LucyVisionPort {
            override val providerName: String = "OOM Simulado"
            override suspend fun healthCheck(): LucyModuleHealth =
                throw OutOfMemoryError("Memoria JVM agotada")

            override suspend fun extractPlanillaLines(imagePath: String): LucyVisionOutcome =
                throw OutOfMemoryError("Memoria JVM agotada en OCR")
        }
        val coordinatorOom = LucyCoreCoordinator(
            repository = repository,
            visionPort = visionFatal,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )

        var oomPropagadoHealth = false
        try {
            coordinatorOom.refrescarSaludModulos()
        } catch (_: OutOfMemoryError) {
            oomPropagadoHealth = true
        }
        assertTrue(oomPropagadoHealth)

        var oomPropagadoVision = false
        try {
            coordinatorOom.ejecutarLecturaPlanillaAislada("/tmp/fake.jpg", Turno.A)
        } catch (_: OutOfMemoryError) {
            oomPropagadoVision = true
        }
        assertTrue(oomPropagadoVision)

        val memoryOom = object : LucyMemoryPort {
            override suspend fun healthCheck(): LucyModuleHealth = LucyModuleHealth.Ready
            override suspend fun recordInteraction(turno: Turno, userInput: String, assistantResponse: String) {
                throw StackOverflowError("Desbordamiento simulado")
            }
            override suspend fun resolveLastReferencedWorkerId(turno: Turno): String? = null
            override suspend fun rememberReferencedWorker(turno: Turno, trabajadorId: String) = Unit
        }
        val coordinatorStack = LucyCoreCoordinator(
            repository = repository,
            memoryPort = memoryOom,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        var stackPropagado = false
        try {
            coordinatorStack.procesarEntrada("ayuda", LucyTurnContext(Turno.A))
        } catch (_: StackOverflowError) {
            stackPropagado = true
        }
        assertTrue(stackPropagado)
    }

    @Test
    fun `9 - parser y coordinador advierten conflicto de turno sin ejecutar marcacion cruzada`() = runTest {
        val wA = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Luis",
            primerApellido = "Pérez",
            segundoApellido = "Soto",
            habitacion = "101",
            pabellon = "A"
        )
        val wB = repository.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Luis",
            primerApellido = "Pérez",
            segundoApellido = "Soto",
            habitacion = "202",
            pabellon = "B"
        )

        val intentConflicto = LucyIntentParserOffline.parse("marca a Pérez en Turno B", Turno.A)
        assertTrue(intentConflicto is LucyIntent.ConflictoTurno)
        intentConflicto as LucyIntent.ConflictoTurno
        assertEquals(Turno.A, intentConflicto.turnoActivo)
        assertEquals(Turno.B, intentConflicto.turnoSolicitado)
        assertEquals("perez", intentConflicto.query)

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        val respuesta = coordinator.procesarEntrada(
            entrada = "marca a Pérez en Turno B",
            contexto = LucyTurnContext(Turno.A)
        )
        assertTrue(respuesta.esErrorRecuperable)
        assertNull(respuesta.confirmacionRequerida)
        assertTrue(respuesta.texto.contains("cambia primero a Turno B"))
        assertFalse(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == wA.id }.siBajo)
        assertFalse(repository.observeTrabajadoresConEstado(Turno.B).first().first { it.id == wB.id }.siBajo)
    }

    @Test
    fun `10 - confirmarAccionPendiente rechaza confirmacion si el turno activo real cambio en repositorio`() = runTest {
        val wA = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.FINNING,
            nombre = "Mario",
            primerApellido = "Rojas",
            segundoApellido = "Díaz",
            habitacion = "301",
            pabellon = "C"
        )

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        repository.setTurnoActivo(Turno.A)
        val solicitud = coordinator.procesarEntrada("marca a Rojas", LucyTurnContext(Turno.A))
        val confirmacionTurnoA = solicitud.confirmacionRequerida
        assertNotNull(confirmacionTurnoA)

        // El turno activo del repositorio cambia a Turno B antes de confirmar
        repository.setTurnoActivo(Turno.B)
        val resultado = coordinator.confirmarAccionPendiente(confirmacionTurnoA!!)
        assertTrue(resultado.esErrorRecuperable)
        assertEquals(Turno.B, resultado.turnoContexto)
        assertTrue(resultado.texto.contains("pertenece a Turno A"))
        assertFalse(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == wA.id }.siBajo)
    }

    @Test
    fun `11 - cambio de turno registra el contexto en memoria bajo el nuevo turno`() = runTest {
        val registros = mutableListOf<Triple<Turno, String, String>>()
        val memorySpy = object : LucyMemoryPort {
            override suspend fun healthCheck(): LucyModuleHealth = LucyModuleHealth.Ready
            override suspend fun recordInteraction(turno: Turno, userInput: String, assistantResponse: String) {
                registros.add(Triple(turno, userInput, assistantResponse))
            }
            override suspend fun resolveLastReferencedWorkerId(turno: Turno): String? = null
            override suspend fun rememberReferencedWorker(turno: Turno, trabajadorId: String) = Unit
        }
        val coordinator = LucyCoreCoordinator(
            repository = repository,
            memoryPort = memorySpy,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val respuesta = coordinator.procesarEntrada("cambia a Turno B", LucyTurnContext(Turno.A))
        assertEquals(Turno.B, respuesta.turnoContexto)
        assertEquals(1, registros.size)
        assertEquals(Turno.B, registros.first().first)
    }

    @Test
    fun `12 - LucyViewModel opera sin colector activo y serializa consultas simultaneas manteniendo procesando`() {
        val wB = kotlinx.coroutines.runBlocking {
            repository.setTurnoActivo(Turno.B)
            repository.agregarTrabajador(
                turno = Turno.B,
                empresa = EmpresaOficial.SOMACOR,
                nombre = "Elena",
                primerApellido = "Vargas",
                segundoApellido = "Paz",
                habitacion = "410",
                pabellon = "SUR"
            )
        }

        val viewModel = LucyViewModel(
            repository = repository,
            coordinator = LucyCoreCoordinator(
                repository = repository,
                ioDispatcher = Dispatchers.Unconfined,
                defaultDispatcher = Dispatchers.Unconfined
            )
        )

        // Sin colector en viewModel.uiState, enviar y confirmar debe usar repository.turnoActivo real (Turno B)
        viewModel.enviarConsulta("marca a Vargas Paz")
        viewModel.confirmarAccionPendiente()
        waitForState {
            !viewModel.uiState.value.procesando &&
                kotlinx.coroutines.runBlocking {
                    repository.observeTrabajadoresConEstado(Turno.B).first().first { it.id == wB.id }.siBajo
                }
        }

        // Consultas simultáneas que incluyen cambio de turno mantienen el orden y apagan procesando al terminar ambas
        val estadosProcesando = mutableListOf<Boolean>()
        val collectorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        val job = collectorScope.launch {
            viewModel.uiState.collect { estadosProcesando.add(it.procesando) }
        }
        viewModel.enviarConsulta("cambia a Turno A")
        viewModel.enviarConsulta("resumen")
        waitForState {
            !viewModel.uiState.value.procesando &&
                viewModel.uiState.value.turnoActivo == Turno.A &&
                viewModel.uiState.value.historialTurnoActivo.size == 3
        }
        assertTrue(estadosProcesando.contains(true))
        assertFalse(viewModel.uiState.value.procesando)
        assertTrue(viewModel.uiState.value.historialTurnoActivo.any { it.texto.contains("Turno cambiado a Turno A") })
        assertTrue(viewModel.uiState.value.historialTurnoActivo.last().texto.contains("No hay trabajadores registrados para Turno A"))
        job.cancel()
    }

    private fun waitForState(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timeout esperando estado después de ${timeoutMs}ms")
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(15)
        }
    }
}
