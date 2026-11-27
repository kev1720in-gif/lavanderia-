package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LavanderiaDatabase
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.Turno
import com.example.lucy.LucyAppContainer
import com.example.lucy.LucyDependenciesBundle
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyIntent
import com.example.lucy.core.LucyIntentParserOffline
import com.example.lucy.core.LucyOperationOrigin
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.ui.LucyPhotoIntake
import com.example.lucy.ui.LucyScreen
import com.example.lucy.ui.LucyViewModel
import com.example.lucy.vision.AndroidLucyVisionImageLoader
import com.example.lucy.vision.FirebaseLucyVisionPort
import com.example.lucy.vision.LucyVisionDocumentStatus
import com.example.lucy.vision.LucyVisionGatewayStatus
import com.example.lucy.vision.LucyVisionImagePayload
import com.example.lucy.vision.LucyVisionMatchStatus
import com.example.lucy.vision.LucyVisionModelGateway
import com.example.lucy.vision.LucyVisionRawGatewayResult
import com.example.lucy.vision.LucyVisionRequestContext
import com.example.lucy.vision.LucyVisionTextOriginType
import com.example.lucy.voice.AndroidLucyVoicePort
import com.example.lucy.voice.LucySttController
import com.example.lucy.voice.LucySttGateway
import com.example.lucy.voice.LucySttGatewayCallbacks
import com.example.lucy.voice.LucySttSessionHandle
import com.example.lucy.voice.LucyTtsEngine
import com.example.lucy.voice.LucyTtsGateway
import com.example.lucy.voice.LucyTtsGatewayCallbacks
import com.example.lucy.voice.LucyTtsVoiceInfo
import com.example.ui.theme.LavanderiaControlTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class LucyConversationalAndVisionAuditTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: LavanderiaDatabase
    private lateinit var repository: LavanderiaRepository
    private val scopesToClose = mutableListOf<CoroutineScope>()
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LavanderiaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LavanderiaRepository(db)
        runBlocking {
            repository.ensureInitialized()
        }
    }

    @After
    fun teardown() {
        runBlocking {
            scopesToClose.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
        }
        scopesToClose.clear()
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
        LucyPhotoIntake.getTempDirectory(context).listFiles()?.forEach { it.delete() }
        db.close()
    }

    @Test
    fun `1 - que te dije hace un rato consulta historial conversacional y nunca busca trabajadores`() = runBlocking {
        val bundle = crearBundleTest()
        val coordinator = bundle.coordinator

        // Sesión vacía: responde honestamente sin decir "No encontré trabajadores"
        val vacia = coordinator.procesarEntrada("¿Qué te dije hace un rato?")
        assertFalse(vacia.texto.contains("No encontré trabajadores", ignoreCase = true))
        assertTrue(vacia.texto.contains("Todavía no hemos conversado", ignoreCase = true))

        // Conversación previa real
        coordinator.procesarEntrada("Hola Lucy")
        coordinator.procesarEntrada("Komatsu entrega las bolsas temprano")

        val recuerdo = coordinator.procesarEntrada("¿Qué te dije hace un rato?")
        assertFalse(recuerdo.texto.contains("No encontré trabajadores", ignoreCase = true))
        assertTrue(
            "Debe recordar lo dicho por el usuario: ${recuerdo.texto}",
            recuerdo.texto.contains("Komatsu entrega las bolsas temprano", ignoreCase = true)
        )
    }

    @Test
    fun `2 - que estabamos revisando recupera contexto activo de turno empresa trabajador y vision`() = runBlocking {
        val trabajador = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Juan",
            primerApellido = "Pérez",
            segundoApellido = "Soto",
            habitacion = "108",
            pabellon = "A1"
        )
        val fakeVision = FakeVisionGateway().apply {
            nextResult = LucyVisionRawGatewayResult.Success(
                modelName = "gemini-3.8-flash",
                rawJson = """
                    {
                      "isReadable": true,
                      "legibilityStatus": "LEGIBLE",
                      "documentTextType": "IMPRESO",
                      "detectedCompanyText": "Komatsu",
                      "documentNotes": [],
                      "rows": [
                        {
                          "rowNumber": 1,
                          "rawText": "1. PEREZ SOTO JUAN | Hab 108 | Pab A1 | SI",
                          "detectedLastNames": "PEREZ SOTO",
                          "detectedFirstName": "JUAN",
                          "detectedName": "PEREZ SOTO JUAN",
                          "textOriginType": "IMPRESO",
                          "detectedCompanyText": "Komatsu",
                          "detectedPavilion": "A1",
                          "detectedRoom": "108",
                          "visualMarkingText": "SI",
                          "interpretedMarkingStatus": "SI_BAJO",
                          "confidence": 0.94,
                          "ambiguityNotes": [],
                          "isIncompleteOrIllegible": false
                        }
                      ]
                    }
                """.trimIndent()
            )
        }
        val bundle = crearBundleTest(visionGateway = fakeVision)
        val coordinator = bundle.coordinator

        coordinator.procesarEntrada("Busca a Juan Pérez")
        val fotoFile = crearImagenArchivoTemporal("planilla_contexto.jpg")
        coordinator.analizarPlanillaConLucyVision(fotoFile.absolutePath)

        val ctxResp = coordinator.procesarEntrada("¿Qué estábamos revisando?")
        assertFalse(ctxResp.texto.contains("No encontré trabajadores", ignoreCase = true))
        assertTrue(ctxResp.texto.contains("Turno A"))
        assertTrue(ctxResp.texto.contains("PÉREZ SOTO JUAN"))
        assertTrue(ctxResp.trabajadoresRelacionados.any { it.id == trabajador.id })
        assertTrue(ctxResp.texto.contains("Komatsu", ignoreCase = true))
        assertTrue(ctxResp.texto.contains("Planilla revisada por foto", ignoreCase = true))
    }

    @Test
    fun `3 - recuerdame lo que te pregunte no guarda nota operativa y recuerda la pregunta anterior`() = runBlocking {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Ana",
            primerApellido = "Mora",
            segundoApellido = "Díaz",
            habitacion = "202",
            pabellon = "B2"
        )
        val bundle = crearBundleTest()
        val coordinator = bundle.coordinator

        val intentParse = LucyIntentParserOffline.parse("Recuérdame lo que te pregunté", Turno.A)
        assertTrue(intentParse is LucyIntent.ConsultarConversacionReciente)

        coordinator.procesarEntrada("¿Cuántos trabajadores tiene Komatsu?")
        val resp = coordinator.procesarEntrada("Recuérdame lo que te pregunté")

        assertFalse(resp.texto.contains("Guardado en", ignoreCase = true))
        assertFalse(resp.texto.contains("No encontré trabajadores", ignoreCase = true))
        assertTrue(
            "Debe recordar la pregunta anterior: ${resp.texto}",
            resp.texto.contains("¿Cuántos trabajadores tiene Komatsu?")
        )
        val notasGuardadas = bundle.memoryPort.consultarNotasOperativas(Turno.A, null, null)
        assertTrue("No debe haberse guardado como nota operativa", notasGuardadas.isEmpty())
    }

    @Test
    fun `4 - y que paso despues sigue la secuencia de turnos y confirmaciones`() = runBlocking {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Juan",
            primerApellido = "Pérez",
            segundoApellido = "Soto",
            habitacion = "101",
            pabellon = "A1"
        )
        val bundle = crearBundleTest()
        val coordinator = bundle.coordinator

        coordinator.procesarEntrada("Busca a Juan Pérez")
        val solicitudMarcacion = coordinator.procesarEntrada("marca a Juan Pérez")
        assertNotNull(solicitudMarcacion.confirmacionRequerida)
        coordinator.confirmarAccionPendiente(solicitudMarcacion.confirmacionRequerida!!)

        val seguimiento = coordinator.procesarEntrada("¿Y qué pasó después?")
        assertFalse(seguimiento.texto.contains("No encontré trabajadores", ignoreCase = true))
        assertTrue(seguimiento.texto.contains("marca a Juan Pérez"))
        assertTrue(seguimiento.texto.contains("Confirmado y verificado"))
    }

    @Test
    fun `5 - busca a Juan Perez encuentra trabajador pero interrogativas y ambiguas piden aclaracion sin buscar`() = runBlocking {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Juan",
            primerApellido = "Pérez",
            segundoApellido = "Soto",
            habitacion = "101",
            pabellon = "A1"
        )
        val bundle = crearBundleTest()
        val coordinator = bundle.coordinator

        val busqueda = coordinator.procesarEntrada("Busca a Juan Pérez")
        assertTrue(busqueda.texto.contains("PÉREZ SOTO JUAN"))
        assertEquals(1, busqueda.trabajadoresRelacionados.size)

        val preguntaAbierta = coordinator.procesarEntrada("¿Por qué demoró el carro?")
        assertFalse(preguntaAbierta.texto.contains("No encontré trabajadores", ignoreCase = true))
        assertTrue(
            "Debe pedir aclaración ante pregunta abierta: ${preguntaAbierta.texto}",
            preguntaAbierta.texto.contains("No estoy segura", ignoreCase = true)
        )
        assertTrue(preguntaAbierta.trabajadoresRelacionados.isEmpty())

        val fraseAmbigua = coordinator.procesarEntrada("abre el porton 9")
        assertFalse(fraseAmbigua.texto.contains("No encontré trabajadores", ignoreCase = true))
        assertTrue(
            "Debe pedir aclaración ante orden ambigua: ${fraseAmbigua.texto}",
            fraseAmbigua.texto.contains("No entendí bien", ignoreCase = true)
        )
    }

    @Test
    fun `6 - cuantos trabajadores tiene Komatsu entrega conteo real por empresa`() = runBlocking {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Juan",
            primerApellido = "Pérez",
            segundoApellido = "Soto",
            habitacion = "101",
            pabellon = "A1"
        )
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "María",
            primerApellido = "Gómez",
            segundoApellido = "Lagos",
            habitacion = "102",
            pabellon = "A1"
        )
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.FINNING,
            nombre = "Pedro",
            primerApellido = "Rojas",
            segundoApellido = "Silva",
            habitacion = "205",
            pabellon = "B2"
        )

        val bundle = crearBundleTest()
        val resp = bundle.coordinator.procesarEntrada("¿Cuántos trabajadores tiene Komatsu?")
        assertTrue("Debe informar los 2 de Komatsu: ${resp.texto}", resp.texto.contains("Komatsu"))
        assertTrue("Debe indicar total 2: ${resp.texto}", resp.texto.contains("2 en maestro"))
        assertFalse(resp.texto.contains("3 en maestro"))
    }

    @Test
    fun `7 - voz STT y texto usan exactamente el mismo pipeline de comprension de intenciones`() {
        runBlocking {
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.KOMATSU,
                nombre = "Juan",
                primerApellido = "Pérez",
                segundoApellido = "Soto",
                habitacion = "101",
                pabellon = "A1"
            )
        }
        val fakeStt = FakeSttGateway(hasPermission = true, recognitionAvailable = true)
        val bundle = crearBundleTest(sttGateway = fakeStt)
        val vm = LucyViewModel(
            repository = repository,
            coordinator = bundle.coordinator,
            voicePort = bundle.voicePort,
            appContext = context
        )

        vm.iniciarEscuchaVoz()
        waitMainLoopUntil(3_000) { fakeStt.sessionStarted }
        fakeStt.emitFinal("Busca a Juan Pérez")

        waitMainLoopUntil(5_000) {
            !vm.uiState.value.procesando &&
                vm.uiState.value.historialTurnoActivo.any { !it.esUsuario && it.texto.contains("PÉREZ SOTO JUAN") }
        }

        vm.iniciarEscuchaVoz()
        waitMainLoopUntil(3_000) { fakeStt.startCount >= 2 }
        fakeStt.emitFinal("¿Qué te dije hace un rato?")

        waitMainLoopUntil(5_000) {
            !vm.uiState.value.procesando &&
                vm.uiState.value.historialTurnoActivo.any { !it.esUsuario && it.texto.contains("Busca a Juan Pérez") }
        }
        val ultima = vm.uiState.value.historialTurnoActivo.last { !it.esUsuario }.texto
        assertFalse(ultima.contains("No encontré trabajadores", ignoreCase = true))
    }

    @Test
    fun `8 - lectura de planilla impresa muestra formato APELLIDOS NOMBRE HABITACION PABELLON y origen Impreso`() {
        runBlocking {
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.KOMATSU,
                nombre = "Juan",
                primerApellido = "Rojas",
                segundoApellido = "Díaz",
                habitacion = "205",
                pabellon = "18 SUR"
            )
        }
        val fakeVision = FakeVisionGateway().apply {
            nextResult = LucyVisionRawGatewayResult.Success(
                modelName = "gemini-3.8-flash",
                rawJson = """
                    {
                      "isReadable": true,
                      "legibilityStatus": "LEGIBLE",
                      "documentTextType": "IMPRESO",
                      "detectedCompanyText": "Komatsu",
                      "documentNotes": [],
                      "rows": [
                        {
                          "rowNumber": 1,
                          "rawText": "ROJAS DIAZ | JUAN | 205 | 18 SUR | SI",
                          "detectedLastNames": "ROJAS DIAZ",
                          "detectedFirstName": "JUAN",
                          "detectedName": "ROJAS DIAZ JUAN",
                          "textOriginType": "IMPRESO",
                          "detectedCompanyText": "Komatsu",
                          "detectedPavilion": "18 SUR",
                          "detectedRoom": "205",
                          "visualMarkingText": "tic",
                          "interpretedMarkingStatus": "SI_BAJO",
                          "confidence": 0.96,
                          "ambiguityNotes": [],
                          "isIncompleteOrIllegible": false
                        }
                      ]
                    }
                """.trimIndent()
            )
        }
        val bundle = crearBundleTest(visionGateway = fakeVision)
        val vm = LucyViewModel(
            repository = repository,
            coordinator = bundle.coordinator,
            voicePort = bundle.voicePort,
            appContext = context
        )
        val fotoFile = crearImagenArchivoTemporal("planilla_impresa.jpg")

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        vm.seleccionarFotografiaArchivo(context, fotoFile)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_analizar_foto_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("lucy_analizar_foto_button").performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_vision_result_card").fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull(fakeVision.lastPayload)
        assertTrue(fakeVision.lastPayload!!.jpegBytes.isNotEmpty())
        assertTrue(
            composeTestRule.onAllNodesWithText("ROJAS DIAZ | JUAN | 205 | 18 SUR", substring = true)
                .fetchSemanticsNodes().size >= 2
        )
        assertTrue(composeTestRule.onAllNodesWithText("Impreso", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `9 - lectura manuscrita con datos dudosos marca Dudoso sin inventar apellidos ni habitaciones`() = runBlocking {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Carlos",
            primerApellido = "Araya",
            segundoApellido = "Lagos",
            habitacion = "110",
            pabellon = "A1"
        )
        val fakeVision = FakeVisionGateway().apply {
            nextResult = LucyVisionRawGatewayResult.Success(
                modelName = "gemini-3.8-flash",
                rawJson = """
                    {
                      "isReadable": true,
                      "legibilityStatus": "PARCIALMENTE_LEGIBLE",
                      "documentTextType": "MANUSCRITO",
                      "detectedCompanyText": null,
                      "documentNotes": ["Tinta tenue en la segunda fila manuscrita"],
                      "rows": [
                        {
                          "rowNumber": 1,
                          "rawText": "Araya ??? Hab 11?",
                          "detectedLastNames": "Araya",
                          "detectedFirstName": null,
                          "detectedName": "Araya",
                          "textOriginType": "MANUSCRITO",
                          "detectedCompanyText": null,
                          "detectedPavilion": null,
                          "detectedRoom": null,
                          "visualMarkingText": null,
                          "interpretedMarkingStatus": "INDETERMINADO",
                          "confidence": 0.42,
                          "ambiguityNotes": ["Falta nombre de pila y número de habitación borroso"],
                          "isIncompleteOrIllegible": true
                        }
                      ]
                    }
                """.trimIndent()
            )
        }
        val bundle = crearBundleTest(visionGateway = fakeVision)
        val fotoFile = crearImagenArchivoTemporal("planilla_manuscrita.jpg")
        val resp = bundle.coordinator.analizarPlanillaConLucyVision(fotoFile.absolutePath)

        val analysis = resp.visionAnalysis
        assertNotNull(analysis)
        assertEquals(LucyVisionTextOriginType.MANUSCRITO, analysis!!.tipoEscrituraDocumento)
        assertEquals(1, analysis.trabajadoresDetectados.size)
        val det = analysis.trabajadoresDetectados.first()
        assertTrue(det.esDudosoOIlegible)
        assertEquals(LucyVisionTextOriginType.MANUSCRITO, det.tipoEscritura)
        assertEquals(LucyVisionMatchStatus.NOMBRE_INCOMPLETO, det.coincidenciaProbable.estado)
        // Nunca debe inventar datos en datosReconocidos cuando faltan en la imagen
        assertNull(det.datosReconocidos.nombrePilaDetectado)
        assertNull(det.datosReconocidos.habitacionEscritaEnImagen)
        assertTrue(resp.texto.contains("Manuscrito"))
        assertTrue(resp.texto.contains("DUDOSO"))
    }

    @Test
    fun `10 - errores de Vision App Check o red informan con claridad y Room permanece intacto`() = runBlocking {
        val worker = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.FINNING,
            nombre = "Lucas",
            primerApellido = "Soto",
            segundoApellido = "Díaz",
            habitacion = "204",
            pabellon = "B2"
        )
        val fakeVision = FakeVisionGateway().apply {
            nextResult = LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.ERROR_APP_CHECK,
                reason = "Firebase App Check rechazó o no pudo validar la solicitud (403).",
                modelName = "gemini-3.8-flash"
            )
        }
        val bundle = crearBundleTest(visionGateway = fakeVision)
        val fotoFile = crearImagenArchivoTemporal("planilla_error.jpg")

        val resp = bundle.coordinator.analizarPlanillaConLucyVision(fotoFile.absolutePath)
        assertTrue(resp.esErrorRecuperable)
        assertTrue(resp.texto.contains("App Check", ignoreCase = true))

        // La base Room y sus trabajadores/marcaciones permanecen intactos
        val listaDb = repository.observeTrabajadoresConEstado(Turno.A).first()
        assertEquals(1, listaDb.size)
        assertEquals(worker.id, listaDb.first().id)
        assertFalse(listaDb.first().siBajo)
    }

    @Test
    fun `11 - pideResumen exige contexto operativo y diferencia persona pregunta ambigua y turno`() = runBlocking {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Juan",
            primerApellido = "Pérez",
            segundoApellido = "Soto",
            habitacion = "101",
            pabellon = "A1"
        )
        val bundle = crearBundleTest()
        val coordinator = bundle.coordinator

        // "¿Cómo está Juan Pérez?" -> consulta sobre la persona, nunca resumen del turno
        val intentPersona = LucyIntentParserOffline.parse("¿Cómo está Juan Pérez?", Turno.A)
        assertTrue(
            "Se esperaba ConsultarEstadoTrabajador y fue $intentPersona",
            intentPersona is LucyIntent.ConsultarEstadoTrabajador
        )
        val respPersona = coordinator.procesarEntrada("¿Cómo está Juan Pérez?")
        assertTrue(respPersona.texto.contains("PÉREZ SOTO JUAN"))
        assertFalse(respPersona.texto.contains("Por empresa:"))

        // "¿Cómo va el carro?" -> no debe activar resumen del turno
        val intentCarro = LucyIntentParserOffline.parse("¿Cómo va el carro?", Turno.A)
        assertTrue(
            "Se esperaba AclaracionAmbigua y fue $intentCarro",
            intentCarro is LucyIntent.AclaracionAmbigua
        )
        val respCarro = coordinator.procesarEntrada("¿Cómo va el carro?")
        assertFalse(respCarro.texto.contains("en maestro"))

        // "¿Cómo va el turno?" -> sí activa resumen del turno
        val intentTurno = LucyIntentParserOffline.parse("¿Cómo va el turno?", Turno.A)
        assertTrue(intentTurno is LucyIntent.ResumenTurno)
        val respTurno = coordinator.procesarEntrada("¿Cómo va el turno?")
        assertTrue(respTurno.texto.contains("Turno A:"))
        assertTrue(respTurno.texto.contains("1 en maestro"))
    }

    @Test
    fun `12 - Vision muestra el nombre real del modelo fallback cuando responde gemini-3_5-flash-lite`() {
        runBlocking {
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.KOMATSU,
                nombre = "Juan",
                primerApellido = "Rojas",
                segundoApellido = "Díaz",
                habitacion = "205",
                pabellon = "18 SUR"
            )
        }
        val fakeVision = FakeVisionGateway().apply {
            nextResult = LucyVisionRawGatewayResult.Success(
                modelName = "gemini-3.5-flash-lite",
                rawJson = """
                    {
                      "isReadable": true,
                      "legibilityStatus": "LEGIBLE",
                      "documentTextType": "IMPRESO",
                      "detectedCompanyText": "Komatsu",
                      "documentNotes": [],
                      "rows": [
                        {
                          "rowNumber": 1,
                          "rawText": "ROJAS DIAZ JUAN | 205 | 18 SUR | SI",
                          "detectedLastNames": "ROJAS DIAZ",
                          "detectedFirstName": "JUAN",
                          "detectedName": "ROJAS DIAZ JUAN",
                          "textOriginType": "IMPRESO",
                          "detectedCompanyText": "Komatsu",
                          "detectedPavilion": "18 SUR",
                          "detectedRoom": "205",
                          "visualMarkingText": "SI",
                          "interpretedMarkingStatus": "SI_BAJO",
                          "confidence": 0.91,
                          "ambiguityNotes": [],
                          "isIncompleteOrIllegible": false
                        }
                      ]
                    }
                """.trimIndent()
            )
        }
        val bundle = crearBundleTest(visionGateway = fakeVision)
        val vm = LucyViewModel(
            repository = repository,
            coordinator = bundle.coordinator,
            voicePort = bundle.voicePort,
            appContext = context
        )
        val fotoFile = crearImagenArchivoTemporal("planilla_fallback.jpg")

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        vm.seleccionarFotografiaArchivo(context, fotoFile)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_analizar_foto_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("lucy_analizar_foto_button").performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_vision_result_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Firebase AI Logic (gemini-3.5-flash-lite)").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("gemini-3.8-flash", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    private fun idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitMainLoopUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            idleMainLooper()
            if (condition()) return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timeout esperando condición después de ${timeoutMs}ms")
            }
            Thread.sleep(20L)
        }
    }

    private fun crearBundleTest(
        sttGateway: FakeSttGateway = FakeSttGateway(),
        ttsGateway: FakeTtsGateway = FakeTtsGateway(),
        visionGateway: FakeVisionGateway = FakeVisionGateway()
    ): LucyDependenciesBundle {
        val file = tempFolder.newFile("lucy_audit_${System.nanoTime()}.preferences_pb")
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopesToClose.add(it) }
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = dsScope,
            produceFile = { file }
        )
        val memoryPort = DataStoreLucyMemoryPort(dataStore)
        val stt = LucySttController(
            gateway = sttGateway,
            mainDispatcher = Dispatchers.Main.immediate
        )
        val tts = LucyTtsEngine(
            gateway = ttsGateway,
            mainDispatcher = Dispatchers.Main.immediate
        )
        val voicePort = AndroidLucyVoicePort(
            sttController = stt,
            ttsEngine = tts,
            memoryPort = memoryPort,
            initialVoiceOutputEnabled = false
        )
        val imageLoader = AndroidLucyVisionImageLoader()
        val visionPort = FirebaseLucyVisionPort(
            repository = repository,
            gateway = visionGateway,
            imageLoader = imageLoader
        )
        val coordinator = LucyCoreCoordinator(
            repository = repository,
            visionPort = visionPort,
            voicePort = voicePort,
            memoryPort = memoryPort
        )
        return LucyDependenciesBundle(
            coordinator = coordinator,
            memoryPort = memoryPort,
            voicePort = voicePort,
            visionPort = visionPort,
            imageLoader = imageLoader
        )
    }

    private fun crearImagenArchivoTemporal(nombre: String): File {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        bmp.recycle()
        return File(context.cacheDir, nombre).apply {
            writeBytes(baos.toByteArray())
            tempFiles.add(this)
        }
    }

    private class FakeSttGateway(
        var hasPermission: Boolean = true,
        var recognitionAvailable: Boolean = true
    ) : LucySttGateway {
        @Volatile
        var callbacks: LucySttGatewayCallbacks? = null

        @Volatile
        var sessionStarted = false

        @Volatile
        var startCount = 0

        override fun hasRecordAudioPermission(): Boolean = hasPermission
        override fun isRecognitionAvailable(): Boolean = recognitionAvailable
        override fun isOnDeviceRecognitionAvailable(): Boolean = false

        override fun startSession(
            preferOffline: Boolean,
            localeTag: String,
            callbacks: LucySttGatewayCallbacks
        ): LucySttSessionHandle {
            this.callbacks = callbacks
            sessionStarted = true
            startCount++
            callbacks.onReadyForSpeech()
            return object : LucySttSessionHandle {
                override fun stopListening() = Unit
                override fun cancel() = Unit
                override fun destroy() = Unit
            }
        }

        fun emitFinal(text: String) {
            callbacks?.onFinalResult(text)
        }
    }

    private class FakeTtsGateway : LucyTtsGateway {
        override fun initialize(callbacks: LucyTtsGatewayCallbacks) {
            callbacks.onInitialized(true)
        }

        override fun availableVoices(): List<LucyTtsVoiceInfo> = listOf(
            LucyTtsVoiceInfo("es-cl-local", "es-CL", isNetworkRequired = false, quality = 400)
        )

        override fun selectVoiceByName(voiceName: String): Boolean = true
        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
        override fun setSpeechRate(rate: Float) = Unit
        override fun setPitch(pitch: Float) = Unit
        override fun speak(text: String, utteranceId: String, flushQueue: Boolean): Int = TextToSpeech.SUCCESS
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private class FakeVisionGateway : LucyVisionModelGateway {
        override val providerName: String = "Gemini Vision Audit"
        var lastPayload: LucyVisionImagePayload? = null
        var nextResult: LucyVisionRawGatewayResult = LucyVisionRawGatewayResult.Failure(
            status = LucyVisionDocumentStatus.SIN_CONEXION,
            reason = "Sin conexión",
            modelName = "gemini-3.8-flash"
        )

        override suspend fun checkStatus(): LucyVisionGatewayStatus = LucyVisionGatewayStatus(
            isConfigured = true,
            isNetworkAvailable = true,
            activeModelName = "gemini-3.8-flash",
            statusMessage = "OK"
        )

        override suspend fun analyzeSheetImage(
            payload: LucyVisionImagePayload,
            request: LucyVisionRequestContext
        ): LucyVisionRawGatewayResult {
            lastPayload = payload
            return nextResult
        }
    }
}
