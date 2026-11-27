package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LavanderiaDatabase
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.Turno
import com.example.lucy.LucyAppContainer
import com.example.lucy.LucyDependenciesBundle
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyModuleId
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.ui.LucyPhotoIntake
import com.example.lucy.ui.LucyPhotoIntakeResult
import com.example.lucy.ui.LucyScreen
import com.example.lucy.ui.LucyViewModel
import com.example.lucy.vision.AndroidLucyVisionImageLoader
import com.example.lucy.vision.FirebaseAiLucyVisionGateway
import com.example.lucy.vision.FirebaseLucyVisionPort
import com.example.lucy.vision.LucyAppCheckInitializer
import com.example.lucy.vision.LucyVisionDocumentStatus
import com.example.lucy.vision.LucyVisionGatewayStatus
import com.example.lucy.vision.LucyVisionImagePayload
import com.example.lucy.vision.LucyVisionModelGateway
import com.example.lucy.vision.LucyVisionRawGatewayResult
import com.example.lucy.vision.LucyVisionRequestContext
import com.example.lucy.voice.AndroidLucyVoicePort
import com.example.lucy.voice.LucyMicrophonePermissionState
import com.example.lucy.voice.LucySttController
import com.example.lucy.voice.LucySttGateway
import com.example.lucy.voice.LucySttGatewayCallbacks
import com.example.lucy.voice.LucySttSessionHandle
import com.example.lucy.voice.LucySttState
import com.example.lucy.voice.LucyTtsEngine
import com.example.lucy.voice.LucyTtsGateway
import com.example.lucy.voice.LucyTtsGatewayCallbacks
import com.example.lucy.voice.LucyTtsState
import com.example.lucy.voice.LucyTtsVoiceInfo
import com.example.ui.navigation.LavanderiaApp
import com.example.ui.theme.LavanderiaControlTheme
import com.example.ui.viewmodel.BackupViewModel
import com.example.ui.viewmodel.DescargaViewModel
import com.example.ui.viewmodel.ImportacionViewModel
import com.example.ui.viewmodel.MainViewModel
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class LucyIntegrationStage5Test {

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
    fun `1 - inicio de Lucy con unica composicion de dependencias y verificacion segura de App Check`() {
        val bundle = LucyAppContainer.createDependencies(context, repository)
        assertNotNull(bundle.coordinator)
        assertNotNull(bundle.memoryPort)
        assertNotNull(bundle.voicePort)
        assertNotNull(bundle.visionPort)
        assertNotNull(bundle.imageLoader)

        val factory = LucyViewModel.Factory(
            repository = repository,
            appContext = context
        )
        val vm = ViewModelProvider(ViewModelStore(), factory)[LucyViewModel::class.java]
        assertNotNull(vm.coordinator)

        // Verifica que configurar App Check sin token hardcodeado en Debug no rompe el arranque
        LucyAppCheckInitializer.configureAppCheck(context, Intent())

        // Verifica que el gateway real de Firebase AI responde honestamente en entorno local sin red/emulador
        val realGateway = FirebaseAiLucyVisionGateway(context)
        val realStatus = runBlocking { realGateway.checkStatus() }
        assertTrue(realStatus.requiresInternet)
        assertTrue(realStatus.activeModelName.contains("gemini"))
    }

    @Test
    fun `2 - envio y recepcion de mensajes en LucyScreen incluye empresas trabajadores resumen y no comprendido`() {
        runBlocking {
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.KOMATSU,
                nombre = "Mateo",
                primerApellido = "Vega",
                segundoApellido = "Rojas",
                habitacion = "102",
                pabellon = "A1"
            )
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.FINNING,
                nombre = "Lucas",
                primerApellido = "Soto",
                segundoApellido = "Díaz",
                habitacion = "204",
                pabellon = "B2"
            )
        }

        val bundle = crearBundleTest()
        val vm = crearLucyViewModel(bundle)

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        composeTestRule.onNodeWithTag("lucy_empty_state_card").assertIsDisplayed()

        // Consultar empresas
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("consultar empresas")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Empresas oficiales en Turno A", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Listar trabajadores
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("ver trabajadores")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("VEGA ROJAS MATEO", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Resumen del turno
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("resumen")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("2 NO BAJÓ · 2 en maestro", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Búsqueda libre de trabajador sin coincidencias con respuesta honesta
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("busca a Valdivia Tapia")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("No encontré trabajadores para \"valdivia tapia\" en Turno A.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Entrada casual / no comprendida con respuesta honesta sin inventar acciones
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("hoy hace mucho frio")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("No entendí la solicitud en Turno A", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `3 - cambio de turno aisla historial y consultas entre Turno A y Turno B`() {
        runBlocking {
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.KOMATSU,
                nombre = "Carlos",
                primerApellido = "Araya",
                segundoApellido = "Lagos",
                habitacion = "110",
                pabellon = "A"
            )
            repository.agregarTrabajador(
                turno = Turno.B,
                empresa = EmpresaOficial.SOMACOR,
                nombre = "Pedro",
                primerApellido = "Tapia",
                segundoApellido = "Mora",
                habitacion = "315",
                pabellon = "C"
            )
        }

        val bundle = crearBundleTest()
        val vm = crearLucyViewModel(bundle)

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("ver trabajadores")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("ARAYA LAGOS CARLOS", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Cambiar a Turno B mediante orden directa a Lucy
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("cambia a Turno B")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            idleMainLooper()
            vm.uiState.value.turnoActivo == Turno.B
        }

        // Consultar trabajadores en Turno B: no debe aparecer Carlos de Turno A
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("ver trabajadores")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("TAPIA MORA PEDRO", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            composeTestRule.onAllNodesWithText("ARAYA LAGOS CARLOS", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )

        // Volver a Turno A conserva el historial previo de Turno A
        runBlocking { repository.setTurnoActivo(Turno.A) }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("ARAYA LAGOS CARLOS", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `4 - memoria persistente de Lucy conserva instrucciones y preferencias entre instancias de ViewModel`() = runBlocking {
        val memoryFile = tempFolder.newFile("lucy_stage5_persist.preferences_pb")

        val dsScope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopesToClose.add(it) }
        val store1 = ViewModelStore()
        val vm1 = ViewModelProvider(
            store1,
            LucyViewModel.Factory(
                repository = repository,
                bundleFactory = { repo ->
                    crearBundleTest(
                        memoryFile = memoryFile,
                        memoryScope = dsScope1,
                        repoOverride = repo
                    )
                }
            )
        )[LucyViewModel::class.java]

        vm1.enviarConsulta("recuerda que Komatsu entrega a las 08:00")
        vm1.enviarConsulta("habla corto")
        waitMainLoopUntil(5_000) {
            !vm1.uiState.value.procesando &&
                vm1.uiState.value.historialTurnoActivo.count { !it.esUsuario } >= 2
        }
        store1.clear()
        // DataStore exige cerrar el scope previo antes de abrir otra instancia sobre el mismo archivo
        dsScope1.coroutineContext[Job]?.cancelAndJoin()
        scopesToClose.remove(dsScope1)

        // Segunda instancia usando el mismo archivo DataStore
        val store2 = ViewModelStore()
        val vm2 = ViewModelProvider(
            store2,
            LucyViewModel.Factory(
                repository = repository,
                bundleFactory = { repo -> crearBundleTest(memoryFile = memoryFile, repoOverride = repo) }
            )
        )[LucyViewModel::class.java]

        vm2.enviarConsulta("qué recuerdas")
        waitMainLoopUntil(5_000) {
            !vm2.uiState.value.procesando &&
                vm2.uiState.value.historialTurnoActivo.any { !it.esUsuario && it.texto.contains("08:00") }
        }

        val respuestaMemoria = vm2.uiState.value.historialTurnoActivo.lastOrNull { !it.esUsuario }?.texto.orEmpty()
        assertTrue("Debe conservar la nota operativa: $respuestaMemoria", respuestaMemoria.contains("08:00"))
        store2.clear()
    }

    @Test
    fun `5 - permisos de microfono denegados vs concedidos degradan con mensaje claro y permiten seguir operando`() {
        val fakeStt = FakeSttGateway(hasPermission = false, recognitionAvailable = true)
        val bundle = crearBundleTest(sttGateway = fakeStt)
        val vm = crearLucyViewModel(bundle)

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        // Denegado explícitamente
        vm.actualizarEstadoPermisoMicrofono(false)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_error_banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Permiso de micrófono denegado", substring = true).assertIsDisplayed()

        // Igual puede operar por texto
        vm.limpiarError()
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("ayuda")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Soy Lucy en Turno A. Puedo ayudarte con:", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Permiso concedido posteriormente
        fakeStt.hasPermission = true
        vm.actualizarEstadoPermisoMicrofono(true)
        composeTestRule.waitUntil(5_000) {
            idleMainLooper()
            vm.uiState.value.voiceStatus.permissionState == LucyMicrophonePermissionState.GRANTED
        }
    }

    @Test
    fun `6 - inicio y finalizacion de escucha STT muestra banner en vivo y envia dictado final`() {
        runBlocking {
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.KOMATSU,
                nombre = "Diego",
                primerApellido = "Rojas",
                segundoApellido = "Pérez",
                habitacion = "120",
                pabellon = "A"
            )
        }
        val fakeStt = FakeSttGateway(hasPermission = true, recognitionAvailable = true)
        val bundle = crearBundleTest(sttGateway = fakeStt)
        val vm = crearLucyViewModel(bundle)

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        vm.iniciarEscuchaVoz()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_listening_banner").fetchSemanticsNodes().isNotEmpty()
        }

        fakeStt.emitPartial("quienes")
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Transcripción: quienes", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("lucy_detener_escucha_button").performClick()
        fakeStt.emitFinal("quienes faltan")

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("ROJAS PÉREZ DIEGO", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            vm.uiState.value.historialTurnoActivo.any {
                it.esUsuario && it.texto == "quienes faltan"
            }
        )
    }

    @Test
    fun `7 - lectura TTS responde automaticamente respeta silencio y detiene reproduccion`() {
        val fakeTts = FakeTtsGateway()
        val bundle = crearBundleTest(ttsGateway = fakeTts, initialVoiceEnabled = true)
        val vm = crearLucyViewModel(bundle)

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("resumen")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()

        composeTestRule.waitUntil(5_000) {
            idleMainLooper()
            fakeTts.spokenTexts.isNotEmpty()
        }

        // Mientras habla, mostrar botón detener
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_tts_stop_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("lucy_tts_stop_button").performClick()
        composeTestRule.waitUntil(5_000) {
            idleMainLooper()
            fakeTts.stopCalls > 0
        }
        assertTrue(fakeTts.stopCalls > 0)

        // Silenciar salida automática de voz
        composeTestRule.onNodeWithTag("lucy_tts_toggle_button").performClick()
        val habladosAntes = fakeTts.spokenTexts.size
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("ayuda")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Soy Lucy en Turno A. Puedo ayudarte con:", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
        assertEquals(habladosAntes, fakeTts.spokenTexts.size)
    }

    @Test
    fun `8 - seleccion de fotografias valida formato tamano copia aislada y vista previa segura`() {
        val bundle = crearBundleTest()
        val vm = crearLucyViewModel(bundle)

        // Caso válido por ContentResolver
        val jpegBytes = crearJpegBytes(800, 600)
        val uriValida = Uri.parse("content://com.example.test/planilla_ok.jpg")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            uriValida,
            ByteArrayInputStream(jpegBytes)
        )

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        vm.seleccionarFotografiaUri(context, uriValida)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_foto_preview_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("lucy_foto_preview_image").assertIsDisplayed()
        composeTestRule.onNodeWithText("Requiere conexión", substring = true).assertIsDisplayed()

        val rutaCopiada = vm.uiState.value.fotoSeleccionada?.tempFilePath
        assertNotNull(rutaCopiada)
        assertTrue(rutaCopiada!!.contains("lucy_vision_temp"))
        assertFalse(rutaCopiada.contains("ocr_temp"))

        // Quitar foto limpia el archivo temporal
        composeTestRule.onNodeWithTag("lucy_quitar_foto_button").performClick()
        composeTestRule.waitUntil(5_000) {
            vm.uiState.value.fotoSeleccionada == null
        }
        assertFalse(File(rutaCopiada).exists())

        // Caso inválido: archivo corrupto
        val uriCorrupta = Uri.parse("content://com.example.test/corrupta.jpg")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            uriCorrupta,
            ByteArrayInputStream("no-es-una-imagen-valida".toByteArray())
        )
        vm.seleccionarFotografiaUri(context, uriCorrupta)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_error_banner").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(vm.uiState.value.fotoSeleccionada)

        // Caso inválido: supera 20 MB en lectura directa
        val bigStreamUri = Uri.parse("content://com.example.test/gigante.jpg")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            bigStreamUri,
            ByteArrayInputStream(ByteArray(21 * 1024 * 1024))
        )
        val oversizeRes = runBlocking { LucyPhotoIntake.prepareFromUri(context, bigStreamUri) }
        assertTrue(oversizeRes is LucyPhotoIntakeResult.Invalid)
    }

    @Test
    fun `9 - errores de Vision aislados y resultado exitoso nunca automarca en Room`() {
        val juan = runBlocking {
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

        val fakeVision = FakeVisionGateway()
        val bundle = crearBundleTest(visionGateway = fakeVision)
        val vm = crearLucyViewModel(bundle)

        val fotoFile = crearImagenArchivoTemporal("planilla_test_vision.jpg")

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        // Probar 5 tipos de fallo controlado de Vision sin romper la app ni modificar Room
        val fallos = listOf(
            LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.SIN_CONEXION,
                reason = "Sin conexión a Internet.",
                modelName = "gemini-3.8-flash"
            ),
            LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.TIMEOUT,
                reason = "Tiempo de espera agotado.",
                modelName = "gemini-3.8-flash"
            ),
            LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.CUOTA_O_FACTURACION,
                reason = "Límite de cuota / plan Blaze requerido.",
                modelName = "gemini-3.8-flash"
            ),
            LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.ERROR_APP_CHECK,
                reason = "App Check rechazó la solicitud (403).",
                modelName = "gemini-3.8-flash"
            ),
            LucyVisionRawGatewayResult.Success(
                rawJson = "{json-corrupto",
                modelName = "gemini-3.8-flash"
            )
        )

        fallos.forEach { fallo ->
            val respuestasAntes = vm.uiState.value.historialTurnoActivo.count { !it.esUsuario }
            fakeVision.nextResult = fallo
            vm.seleccionarFotografiaArchivo(context, fotoFile)
            composeTestRule.waitUntil(5_000) {
                idleMainLooper()
                vm.uiState.value.fotoSeleccionada != null
            }
            composeTestRule.onNodeWithTag("lucy_analizar_foto_button").performClick()
            composeTestRule.waitUntil(5_000) {
                idleMainLooper()
                !vm.uiState.value.procesando &&
                    vm.uiState.value.historialTurnoActivo.count { !it.esUsuario } > respuestasAntes
            }
        }

        // Ahora devolver análisis válido con coincidencia única propuesta
        fakeVision.nextResult = LucyVisionRawGatewayResult.Success(
            modelName = "gemini-3.8-flash",
            rawJson = """
                {
                  "isReadable": true,
                  "legibilityStatus": "LEGIBLE",
                  "detectedCompanyText": "Komatsu",
                  "documentNotes": [],
                  "rows": [
                    {
                      "rowNumber": 1,
                      "rawText": "1. ROJAS DIAZ JUAN - Hab 205 [tic]",
                      "detectedName": "ROJAS DIAZ JUAN",
                      "detectedCompanyText": "Komatsu",
                      "detectedPavilion": "18 SUR",
                      "detectedRoom": "205",
                      "visualMarkingText": "tic",
                      "interpretedMarkingStatus": "SI_BAJO",
                      "confidence": 0.93,
                      "ambiguityNotes": [],
                      "isIncompleteOrIllegible": false
                    }
                  ]
                }
            """.trimIndent()
        )
        vm.limpiarError()
        vm.seleccionarFotografiaArchivo(context, fotoFile)
        composeTestRule.waitUntil(5_000) {
            idleMainLooper()
            vm.uiState.value.fotoSeleccionada != null
        }
        composeTestRule.onNodeWithTag("lucy_analizar_foto_button").performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_vision_result_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("RECONOCIDO EN IMAGEN", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("INFERIDO / PROPUESTA (SIN CONFIRMAR)", substring = true).assertIsDisplayed()

        // REGLA CRÍTICA: nunca automarca en Room
        val enDbDespuesVision = runBlocking {
            repository.observeTrabajadoresConEstado(Turno.A).first().firstOrNull { it.id == juan.id }
        }
        assertNotNull(enDbDespuesVision)
        assertFalse(enDbDespuesVision!!.siBajo)
    }

    @Test
    fun `10 - confirmacion explicita de marcacion verifica DB post ejecucion y bloquea cambios si cambia el turno`() {
        val p1 = runBlocking {
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.KOMATSU,
                nombre = "Juan",
                primerApellido = "Pérez",
                segundoApellido = "Soto",
                habitacion = "101",
                pabellon = "A"
            )
        }
        runBlocking {
            repository.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.FINNING,
                nombre = "Luis",
                primerApellido = "Pérez",
                segundoApellido = "Gómez",
                habitacion = "202",
                pabellon = "B"
            )
        }

        val bundle = crearBundleTest()
        val vm = crearLucyViewModel(bundle)

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(lucyViewModel = vm)
            }
        }

        // Desambiguación por apellido compartido
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("marca a Pérez")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_desambiguacion_card").fetchSemanticsNodes().isNotEmpty()
        }

        // Seleccionar Juan Pérez y luego cancelar -> NO cambia Room
        composeTestRule.onNodeWithText("PÉREZ SOTO JUAN", substring = true).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_confirmacion_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("lucy_cancelar_accion_button").performClick()
        assertFalse(
            runBlocking {
                repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == p1.id }.siBajo
            }
        )

        // Volver a solicitar marcación de Juan Pérez, cambiar turno a B antes de confirmar -> bloqueo estricto
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("marca a Juan Pérez")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_confirmacion_card").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { repository.setTurnoActivo(Turno.B) }
        composeTestRule.waitUntil(5_000) {
            idleMainLooper()
            vm.uiState.value.confirmacionPendiente == null
        }
        assertNull(vm.uiState.value.confirmacionPendiente)
        assertFalse(
            runBlocking {
                repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == p1.id }.siBajo
            }
        )

        // Volver a Turno A, confirmar y validar verificación post-ejecución en Room
        runBlocking { repository.setTurnoActivo(Turno.A) }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("lucy_confirmacion_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("lucy_confirmar_accion_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(
                "Confirmado y verificado en Turno A: PÉREZ SOTO JUAN quedó marcado como SÍ BAJÓ.",
                substring = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            runBlocking {
                repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == p1.id }.siBajo
            }
        )

        // Lucy nunca permite eliminar trabajadores
        composeTestRule.onNodeWithTag("lucy_text_input").performTextInput("elimina a Juan Pérez")
        composeTestRule.onNodeWithTag("lucy_send_button").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("No entendí", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithText("no", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull(
            runBlocking {
                repository.observeTrabajadoresConEstado(Turno.A).first().firstOrNull { it.id == p1.id }
            }
        )
    }

    @Test
    fun `11 - navegacion completa en LavanderiaApp conserva Trabajadores y abre Lucy correctamente`() {
        val mainViewModel = MainViewModel(repository)
        val importacionViewModel = ImportacionViewModel(repository)
        val backupViewModel = BackupViewModel(repository)
        val descargaViewModel = DescargaViewModel(repository)
        val lucyViewModel = crearLucyViewModel(crearBundleTest())

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LavanderiaApp(
                    mainViewModel = mainViewModel,
                    importacionViewModel = importacionViewModel,
                    backupViewModel = backupViewModel,
                    descargaViewModel = descargaViewModel,
                    lucyViewModel = lucyViewModel
                )
            }
        }

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("nav_lucy").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("nav_listado").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_trabajadores").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_empresas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_descarga").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_lucy").assertIsDisplayed()

        // Ir a Lucy
        composeTestRule.onNodeWithTag("nav_lucy").performClick()
        composeTestRule.onNodeWithTag("lucy_status_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("lucy_composer_bar").assertIsDisplayed()

        // Confirmar que Trabajadores sigue intacta y navegable
        composeTestRule.onNodeWithTag("nav_trabajadores").performClick()
        composeTestRule.onNodeWithTag("agregar_trabajador_button").assertIsDisplayed()

        // Volver a Lucy
        composeTestRule.onNodeWithTag("nav_lucy").performClick()
        composeTestRule.onNodeWithTag("lucy_status_header").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h900dp")
    fun `13 - diseno adaptable en tablet muestra NavigationRail y LucyScreen`() {
        val mainViewModel = MainViewModel(repository)
        val importacionViewModel = ImportacionViewModel(repository)
        val backupViewModel = BackupViewModel(repository)
        val descargaViewModel = DescargaViewModel(repository)
        val lucyViewModel = crearLucyViewModel(crearBundleTest())

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LavanderiaApp(
                    mainViewModel = mainViewModel,
                    importacionViewModel = importacionViewModel,
                    backupViewModel = backupViewModel,
                    descargaViewModel = descargaViewModel,
                    lucyViewModel = lucyViewModel
                )
            }
        }

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("nav_lucy").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("nav_lucy").performClick()
        composeTestRule.onNodeWithTag("lucy_status_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("lucy_composer_bar").assertIsDisplayed()
    }

    @Test
    fun `12 - liberacion de recursos en onCleared libera STT TTS coordinador y foto temporal`() {
        val fakeStt = FakeSttGateway(hasPermission = true, recognitionAvailable = true)
        val fakeTts = FakeTtsGateway()
        val bundle = crearBundleTest(sttGateway = fakeStt, ttsGateway = fakeTts)
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            LucyViewModel.Factory(
                repository = repository,
                appContext = context,
                bundleFactory = { bundle }
            )
        )[LucyViewModel::class.java]

        // Crear un archivo temporal dentro de lucy_vision_temp para verificar limpieza al cerrar
        val tempPhoto = File(LucyPhotoIntake.getTempDirectory(context), "temp_on_clear.jpg").apply {
            writeBytes(crearJpegBytes(200, 200))
        }
        vm.seleccionarFotografiaArchivo(context, tempPhoto)
        waitMainLoopUntil(3_000) { vm.uiState.value.fotoSeleccionada != null }
        assertNotNull(vm.uiState.value.fotoSeleccionada)

        vm.iniciarEscuchaVoz()
        waitMainLoopUntil(3_000) { fakeStt.sessionStarted }

        // Disparar ciclo de limpieza ViewModel.onCleared()
        store.clear()

        waitMainLoopUntil(5_000) {
            fakeTts.shutdownCalled && fakeStt.destroyed && !tempPhoto.exists()
        }

        assertTrue("STT debió destruirse en onCleared()", fakeStt.destroyed)
        assertTrue("TTS debió detenerse/apagarse en onCleared()", fakeTts.shutdownCalled)
        assertFalse("Foto temporal en caché debió eliminarse en onCleared()", tempPhoto.exists())
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
        memoryFile: File? = null,
        memoryScope: CoroutineScope? = null,
        repoOverride: LavanderiaRepository = repository,
        sttGateway: FakeSttGateway = FakeSttGateway(),
        ttsGateway: FakeTtsGateway = FakeTtsGateway(),
        visionGateway: FakeVisionGateway = FakeVisionGateway(),
        initialVoiceEnabled: Boolean = false
    ): LucyDependenciesBundle {
        val file = memoryFile ?: tempFolder.newFile("lucy_s5_${System.nanoTime()}.preferences_pb")
        val dsScope = memoryScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopesToClose.add(it) }
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
            initialVoiceOutputEnabled = initialVoiceEnabled
        )
        val imageLoader = AndroidLucyVisionImageLoader()
        val visionPort = FirebaseLucyVisionPort(
            repository = repoOverride,
            gateway = visionGateway,
            imageLoader = imageLoader
        )
        val coordinator = LucyCoreCoordinator(
            repository = repoOverride,
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

    private fun crearLucyViewModel(bundle: LucyDependenciesBundle): LucyViewModel {
        return LucyViewModel(
            repository = repository,
            coordinator = bundle.coordinator,
            voicePort = bundle.voicePort,
            appContext = context
        )
    }

    private fun crearJpegBytes(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    private fun crearImagenArchivoTemporal(nombre: String): File {
        val file = File(context.cacheDir, nombre)
        file.writeBytes(crearJpegBytes(800, 600))
        tempFiles.add(file)
        return file
    }

    private class FakeSttGateway(
        var hasPermission: Boolean = true,
        var recognitionAvailable: Boolean = true,
        var onDeviceAvailable: Boolean = false
    ) : LucySttGateway {

        @Volatile
        var callbacks: LucySttGatewayCallbacks? = null

        @Volatile
        var sessionStarted = false

        @Volatile
        var stopCalled = false

        @Volatile
        var cancelCalled = false

        @Volatile
        var destroyed = false

        override fun hasRecordAudioPermission(): Boolean = hasPermission
        override fun isRecognitionAvailable(): Boolean = recognitionAvailable
        override fun isOnDeviceRecognitionAvailable(): Boolean = onDeviceAvailable

        override fun startSession(
            preferOffline: Boolean,
            localeTag: String,
            callbacks: LucySttGatewayCallbacks
        ): LucySttSessionHandle {
            this.callbacks = callbacks
            sessionStarted = true
            callbacks.onReadyForSpeech()
            return object : LucySttSessionHandle {
                override fun stopListening() {
                    stopCalled = true
                }

                override fun cancel() {
                    cancelCalled = true
                }

                override fun destroy() {
                    destroyed = true
                }
            }
        }

        fun emitPartial(text: String) {
            callbacks?.onPartialResult(text)
        }

        fun emitFinal(text: String) {
            callbacks?.onFinalResult(text)
        }
    }

    private class FakeTtsGateway : LucyTtsGateway {
        private var callbacks: LucyTtsGatewayCallbacks? = null
        val spokenTexts = mutableListOf<String>()

        @Volatile
        var stopCalls = 0

        @Volatile
        var shutdownCalled = false

        override fun initialize(callbacks: LucyTtsGatewayCallbacks) {
            this.callbacks = callbacks
            callbacks.onInitialized(true)
        }

        override fun availableVoices(): List<LucyTtsVoiceInfo> = listOf(
            LucyTtsVoiceInfo("es-cl-local", "es-CL", isNetworkRequired = false, quality = 400)
        )

        override fun selectVoiceByName(voiceName: String): Boolean = true
        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
        override fun setSpeechRate(rate: Float) = Unit
        override fun setPitch(pitch: Float) = Unit

        override fun speak(text: String, utteranceId: String, flushQueue: Boolean): Int {
            spokenTexts.add(text)
            callbacks?.onUtteranceStart(utteranceId)
            return TextToSpeech.SUCCESS
        }

        override fun stop() {
            stopCalls++
        }

        override fun shutdown() {
            shutdownCalled = true
        }
    }

    private class FakeVisionGateway(
        var status: LucyVisionGatewayStatus = LucyVisionGatewayStatus(
            isConfigured = true,
            isNetworkAvailable = true,
            activeModelName = "gemini-3.8-flash",
            statusMessage = "OK"
        ),
        var nextResult: LucyVisionRawGatewayResult = LucyVisionRawGatewayResult.Failure(
            status = LucyVisionDocumentStatus.SIN_CONEXION,
            reason = "Sin conexión.",
            modelName = "gemini-3.8-flash"
        )
    ) : LucyVisionModelGateway {
        override val providerName: String = "Firebase AI Logic (${status.activeModelName})"
        override suspend fun checkStatus(): LucyVisionGatewayStatus = status
        override suspend fun analyzeSheetImage(
            payload: LucyVisionImagePayload,
            request: LucyVisionRequestContext
        ): LucyVisionRawGatewayResult = nextResult
    }
}
