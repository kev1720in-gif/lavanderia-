package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.speech.tts.TextToSpeech
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LavanderiaDatabase
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyGeminiBrainGateway
import com.example.lucy.core.LucyGeminiBrainOutcome
import com.example.lucy.core.LucyGeminiBrainRequest
import com.example.lucy.core.LucyIntent
import com.example.lucy.core.LucyIntentParserOffline
import com.example.lucy.core.LucyTurnContext
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.example.lucy.ui.LucyScreen
import com.example.lucy.ui.LucyViewModel
import com.example.lucy.vision.AndroidLucyVisionImageLoader
import com.example.lucy.vision.FirebaseLucyVisionPort
import com.example.lucy.vision.LucyVisionGatewayStatus
import com.example.lucy.vision.LucyVisionMatchStatus
import com.example.lucy.vision.LucyVisionModelGateway
import com.example.lucy.vision.LucyVisionRawGatewayResult
import com.example.lucy.vision.LucyVisionRequestContext
import com.example.lucy.vision.LucyVisionImagePayload
import com.example.lucy.voice.AndroidLucyVoicePort
import com.example.lucy.voice.LucyCloudTtsGateway
import com.example.lucy.voice.LucyCloudTtsResult
import com.example.lucy.voice.LucySttController
import com.example.lucy.voice.LucySttGateway
import com.example.lucy.voice.LucySttGatewayCallbacks
import com.example.lucy.voice.LucySttSessionHandle
import com.example.lucy.voice.LucyTtsEngine
import com.example.lucy.voice.LucyTtsGateway
import com.example.lucy.voice.LucyTtsGatewayCallbacks
import com.example.lucy.voice.LucyTtsVoiceInfo
import com.example.lucy.voice.LucyVoicePersonalityConfig
import com.example.ui.theme.LavanderiaControlTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class LucyVoiceAndVisionPriorityFixTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: LavanderiaDatabase
    private lateinit var repository: LavanderiaRepository
    private val scopesToClose = mutableListOf<CoroutineScope>()

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
        db.close()
    }

    @Test
    fun `1 - voz seleccionada se aplica por igual en prueba, respuestas normales, tras reinicio y sin race`() = runBlocking {
        val dsFile = tempFolder.newFile("lucy_voice_parity_${System.nanoTime()}.preferences_pb")
        val cloud1 = RecordingCloudTtsGateway()
        val tts1 = RecordingTtsGateway()

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopesToClose.add(it) }
        val ds1 = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope1,
            produceFile = { dsFile }
        )
        val mem1 = DataStoreLucyMemoryPort(ds1)
        val voice1 = AndroidLucyVoicePort(
            sttController = LucySttController(FakeSttGateway(), mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(tts1, mainDispatcher = Dispatchers.Unconfined),
            memoryPort = mem1,
            initialVoiceOutputEnabled = true,
            cloudTtsGateway = cloud1
        )
        val coord1 = LucyCoreCoordinator(
            repository = repository,
            voicePort = voice1,
            memoryPort = mem1
        )
        val vm1 = LucyViewModel(
            repository = repository,
            coordinator = coord1,
            voicePort = voice1,
            appContext = context
        )

        // Seleccionar voz y refrescar salud en paralelo para probar ausencia de race
        vm1.seleccionarVozPreferida("gemini:Despina")
        vm1.seleccionarEstiloVoz(LucyEstiloVoz.PAUSADA_SUAVE)
        vm1.seleccionarVelocidadVoz(LucyVelocidadRespuesta.LENTA)
        vm1.refrescarEstadoModulos()

        esperarHasta(
            mensaje = { "vm1 prefs=${vm1.uiState.value.voiceStatus.selectedVoiceId}/${vm1.uiState.value.voiceStatus.appliedVoiceStyle}/${vm1.uiState.value.voiceStatus.appliedSpeed}" }
        ) {
            vm1.uiState.value.voiceStatus.selectedVoiceId == "gemini:Despina" &&
                vm1.uiState.value.voiceStatus.appliedVoiceStyle == LucyEstiloVoz.PAUSADA_SUAVE &&
                vm1.uiState.value.voiceStatus.appliedSpeed == LucyVelocidadRespuesta.LENTA
        }

        // 1) Probar voz
        vm1.probarVozActual()
        esperarHasta(mensaje = { "preview cloud1.calls=${cloud1.calls.size}" }) { cloud1.calls.size >= 1 }
        val previewCall = cloud1.calls.last()
        assertEquals("gemini:Despina", previewCall.voiceId)
        assertEquals(LucyEstiloVoz.PAUSADA_SUAVE, previewCall.estiloVoz)
        assertEquals(LucyVelocidadRespuesta.LENTA, previewCall.velocidad)

        // 2) Respuesta normal usa exactamente la misma voz, estilo y velocidad
        vm1.enviarConsulta("Resumen")
        esperarHasta(mensaje = { "normal cloud1.calls=${cloud1.calls.size}" }) { cloud1.calls.size >= 2 }
        val normalCall = cloud1.calls.last()
        assertEquals(previewCall.voiceId, normalCall.voiceId)
        assertEquals(previewCall.estiloVoz, normalCall.estiloVoz)
        assertEquals(previewCall.velocidad, normalCall.velocidad)
        assertFalse(vm1.uiState.value.voiceStatus.isFallbackActive)
        assertEquals(0, tts1.spokenTexts.size)

        // Esperar a que DataStore persista antes de cerrar el proceso simulado
        esperarHasta(mensaje = { "persisted mem1=${mem1.obtenerPreferencias()}" }) {
            val persisted = mem1.obtenerPreferencias()
            persisted.vozPreferidaId == "gemini:Despina" &&
                persisted.estiloVoz == LucyEstiloVoz.PAUSADA_SUAVE &&
                persisted.velocidad == LucyVelocidadRespuesta.LENTA
        }

        voice1.release()
        coord1.shutdown()
        scope1.coroutineContext[Job]?.cancelAndJoin()

        // 3) Simular reinicio de la app sobre el mismo DataStore
        val cloud2 = RecordingCloudTtsGateway()
        val tts2 = RecordingTtsGateway()
        val ds2 = createDataStore(dsFile)
        val mem2 = DataStoreLucyMemoryPort(ds2)
        val voice2 = AndroidLucyVoicePort(
            sttController = LucySttController(FakeSttGateway(), mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(tts2, mainDispatcher = Dispatchers.Unconfined),
            memoryPort = mem2,
            initialVoiceOutputEnabled = true,
            cloudTtsGateway = cloud2
        )
        val coord2 = LucyCoreCoordinator(
            repository = repository,
            voicePort = voice2,
            memoryPort = mem2
        )
        val vm2 = LucyViewModel(
            repository = repository,
            coordinator = coord2,
            voicePort = voice2,
            appContext = context
        )

        esperarHasta(
            mensaje = { "vm2 restart prefs=${vm2.uiState.value.voiceStatus.selectedVoiceId}/${vm2.uiState.value.voiceStatus.appliedVoiceStyle}" }
        ) {
            vm2.uiState.value.voiceStatus.selectedVoiceId == "gemini:Despina" &&
                vm2.uiState.value.voiceStatus.appliedVoiceStyle == LucyEstiloVoz.PAUSADA_SUAVE
        }

        vm2.enviarConsulta("Hola")
        esperarHasta(mensaje = { "restart cloud2.calls=${cloud2.calls.size}" }) { cloud2.calls.size >= 1 }
        val restartCall = cloud2.calls.last()
        assertEquals("gemini:Despina", restartCall.voiceId)
        assertEquals(LucyEstiloVoz.PAUSADA_SUAVE, restartCall.estiloVoz)
        assertEquals(LucyVelocidadRespuesta.LENTA, restartCall.velocidad)
        assertEquals(0, tts2.spokenTexts.size)

        voice2.release()
        coord2.shutdown()
    }

    @Test
    fun `2 - fallo de Gemini TTS activa respaldo visible no silencioso y desmarca opcion Gemini en dialogo`() = runBlocking {
        val ds = createDataStore()
        val mem = DataStoreLucyMemoryPort(ds)
        val cloud = RecordingCloudTtsGateway().apply {
            nextFailureReason = "Error 403 App Check no autorizado"
        }
        val localTts = RecordingTtsGateway()
        val voicePort = AndroidLucyVoicePort(
            sttController = LucySttController(FakeSttGateway(), mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(localTts, mainDispatcher = Dispatchers.Unconfined),
            memoryPort = mem,
            initialVoiceOutputEnabled = true,
            cloudTtsGateway = cloud
        )
        val coord = LucyCoreCoordinator(
            repository = repository,
            voicePort = voicePort,
            memoryPort = mem
        )
        val vm = LucyViewModel(
            repository = repository,
            coordinator = coord,
            voicePort = voicePort,
            appContext = context
        )

        vm.seleccionarVozPreferida("gemini:Sulafat")
        esperarHasta { vm.uiState.value.voiceStatus.selectedVoiceId == "gemini:Sulafat" }

        vm.enviarConsulta("Resumen")
        esperarHasta {
            localTts.spokenTexts.isNotEmpty() && vm.uiState.value.voiceStatus.isFallbackActive
        }

        val voiceUi = vm.uiState.value.voiceStatus
        assertTrue(voiceUi.isFallbackActive)
        assertEquals("Error 403 App Check no autorizado", voiceUi.fallbackReason)
        assertTrue(voiceUi.activeVoiceEngineLabel.contains("Dispositivo Android"))
        assertTrue(voiceUi.activeVoiceEngineLabel.contains("Sulafat"))

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(
                    uiState = vm.uiState.value,
                    onEnviarConsulta = {},
                    onIniciarEscucha = {},
                    onDetenerEscucha = {},
                    onCancelarEscucha = {},
                    onActualizarPermisoMicrofono = {},
                    onAlternarSalidaVoz = {},
                    onDetenerLecturaVoz = {},
                    onLeerMensajeEnVoz = {},
                    onSeleccionarFotoUri = {},
                    onQuitarFoto = {},
                    onAnalizarFoto = {},
                    onSeleccionarCandidato = {},
                    onPrepararConfirmacionDesdeVision = { _, _ -> },
                    onConfirmarAccion = {},
                    onCancelarAccion = {},
                    onLimpiarHistorial = {},
                    onLimpiarError = {},
                    onRefrescarEstado = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("lucy_voice_fallback_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Error 403 App Check no autorizado", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithTag("lucy_voice_settings_button").performClick()
        composeTestRule.onNodeWithTag("lucy_voice_settings_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("lucy_voice_active_engine_label").assertIsDisplayed()
        composeTestRule.onNodeWithTag("lucy_voice_fallback_notice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("lucy_voice_option_gemini:Sulafat").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Preferencia guardada · En espera (hablando respaldo local)")
            .performScrollTo()
            .assertIsDisplayed()

        // El radio button de la voz Gemini NO debe mostrarse seleccionado mientras habla el motor Android local
        composeTestRule.onNode(
            isSelectable() and hasAnyAncestor(hasTestTag("lucy_voice_option_gemini:Sulafat"))
        ).assertIsNotSelected()

        voicePort.release()
        coord.shutdown()
    }

    @Test
    fun `3 - vision conserva foto activa y refiltra estrictamente por empresa via Gemini Brain sin marcar Room`() = runBlocking {
        val carlosFluitek = agregar("Carlos", "Méndez", "Paz", EmpresaOficial.FLUITEK, Turno.A, "101", "A")
        val pedroKomatsu = agregar("Pedro", "Rojas", "Silva", EmpresaOficial.KOMATSU, Turno.A, "202", "B")
        val carlosKomatsu = agregar("Carlos", "Mena", "Soto", EmpresaOficial.KOMATSU, Turno.A, "203", "B")
        agregar("Luis", "Vega", "Díaz", EmpresaOficial.FINNING, Turno.A, "301", "C")

        val fakeVisionGw = FakeVisionGateway().apply {
            nextJson = """
                {
                  "isReadable": true,
                  "legibilityStatus": "LEGIBLE",
                  "documentTextType": "MIXTO",
                  "detectedCompany": null,
                  "documentNotes": [],
                  "rows": [
                    {
                      "rowNumber": 1,
                      "rawText": "Mendez Paz Carlos | 101 | A | OK",
                      "detectedName": "Carlos Mendez Paz",
                      "detectedPaternalLastName": "Mendez",
                      "detectedMaternalLastName": "Paz",
                      "detectedFirstName": "Carlos",
                      "detectedCompany": null,
                      "detectedPavilion": "A",
                      "detectedRoom": "101",
                      "visualMarkingText": "OK",
                      "interpretedMarkingStatus": "SI_BAJO",
                      "confidence": 0.94,
                      "ambiguityNotes": [],
                      "isIncompleteOrIllegible": false
                    },
                    {
                      "rowNumber": 2,
                      "rawText": "Rojas Silva Pedro | 202 | B | OK",
                      "detectedName": "Pedro Rojas Silva",
                      "detectedPaternalLastName": "Rojas",
                      "detectedMaternalLastName": "Silva",
                      "detectedFirstName": "Pedro",
                      "detectedCompany": null,
                      "detectedPavilion": "B",
                      "detectedRoom": "202",
                      "visualMarkingText": "OK",
                      "interpretedMarkingStatus": "SI_BAJO",
                      "confidence": 0.92,
                      "ambiguityNotes": [],
                      "isIncompleteOrIllegible": false
                    }
                  ]
                }
            """.trimIndent()
        }

        val capturedRequests = CopyOnWriteArrayList<LucyGeminiBrainRequest>()
        val fakeBrain = object : LucyGeminiBrainGateway {
            override val providerName: String = "FakeVisionBrain"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome {
                capturedRequests.add(request)
                val empresaTarget = if (request.userInput.contains("Komatsu", ignoreCase = true)) "Komatsu" else "Fluitek"
                val toolOut = request.toolExecutor.execute(
                    toolName = "filtrar_planilla_vision_por_empresa",
                    args = mapOf("empresa" to JsonPrimitive(empresaTarget))
                )
                return LucyGeminiBrainOutcome.Success(
                    replyText = toolOut["mensaje_exacto_sistema"].toString().trim('"'),
                    modelName = "gemini-3.8-flash",
                    toolCallCount = 1
                )
            }
        }

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            visionPort = FirebaseLucyVisionPort(
                repository = repository,
                gateway = fakeVisionGw,
                imageLoader = AndroidLucyVisionImageLoader(),
                memoryPort = DataStoreLucyMemoryPort(createDataStore())
            ),
            memoryPort = DataStoreLucyMemoryPort(createDataStore()),
            geminiBrainGateway = fakeBrain
        )

        val photoFile = createSheetPhotoFile("planilla_test.jpg")
        val initialResp = coordinator.analizarPlanillaConLucyVision(
            imagePath = photoFile.absolutePath,
            contexto = LucyTurnContext(Turno.A),
            hablarAlFinalizar = false
        )
        assertNotNull(initialResp.visionAnalysis?.parsedSheet)

        // Follow-up 1: "Son de Fluitek"
        val fluitekResp = coordinator.procesarEntrada(
            entrada = "Son de Fluitek",
            contexto = LucyTurnContext(Turno.A),
            hablarAlFinalizar = false
        )
        assertEquals(1, capturedRequests.size)
        assertTrue(capturedRequests.first().hayPlanillaVisionActiva)
        assertNotNull(capturedRequests.first().planillaVisionResumen)

        val vaFluitek = fluitekResp.visionAnalysis
        assertNotNull(vaFluitek)
        assertEquals(EmpresaOficial.FLUITEK, vaFluitek!!.empresaFiltroAplicada)
        assertEquals(
            carlosFluitek.id,
            vaFluitek.trabajadoresDetectados[0].coincidenciaProbable.candidatoPrincipal?.trabajador?.id
        )
        assertEquals(
            LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA,
            vaFluitek.trabajadoresDetectados[0].coincidenciaProbable.estado
        )
        // Pedro Rojas pertenece a Komatsu, así que en filtro estricto Fluitek DEBE quedar sin coincidencia
        assertEquals(
            LucyVisionMatchStatus.SIN_COINCIDENCIA,
            vaFluitek.trabajadoresDetectados[1].coincidenciaProbable.estado
        )
        assertNull(vaFluitek.trabajadoresDetectados[1].coincidenciaProbable.candidatoPrincipal)

        // Follow-up 2 sin reenviar foto: "Ahora revisa los que coinciden con Komatsu"
        val komatsuResp = coordinator.procesarEntrada(
            entrada = "Ahora revisa los que coinciden con Komatsu",
            contexto = LucyTurnContext(Turno.A),
            hablarAlFinalizar = false
        )
        val vaKomatsu = komatsuResp.visionAnalysis
        assertNotNull(vaKomatsu)
        assertEquals(EmpresaOficial.KOMATSU, vaKomatsu!!.empresaFiltroAplicada)
        // En Komatsu, Pedro Rojas coincide exactamente y Carlos Méndez (Fluitek) no debe aparecer como único match de Fluitek
        assertEquals(
            pedroKomatsu.id,
            vaKomatsu.trabajadoresDetectados[1].coincidenciaProbable.candidatoPrincipal?.trabajador?.id
        )
        val fila1Cand = vaKomatsu.trabajadoresDetectados[0].coincidenciaProbable.candidatoPrincipal?.trabajador
        if (fila1Cand != null) {
            assertEquals(EmpresaOficial.KOMATSU, fila1Cand.empresa)
            assertEquals(carlosKomatsu.id, fila1Cand.id)
        }

        // Verificar que ninguna marcación fue modificada automáticamente en Room
        val todos = repository.observeTrabajadoresConEstado(Turno.A).first()
        assertTrue(todos.none { it.siBajo })
        coordinator.shutdown()
    }

    @Test
    fun `4 - parser offline detecta variantes de filtro Vision y rechaza empresa desconocida sin fallback global`() = runBlocking {
        agregar("Carlos", "Méndez", "Paz", EmpresaOficial.FLUITEK, Turno.A, "101", "A")
        agregar("Pedro", "Rojas", "Silva", EmpresaOficial.KOMATSU, Turno.A, "202", "B")

        val variantesFluitek = listOf(
            "Son de Fluitek",
            "Filtra solamente los de Fluitek",
            "Busca esos nombres en Fluitek",
            "Todos pertenecen a Fluitek",
            "Compara la foto únicamente con el personal de Fluitek"
        )
        variantesFluitek.forEach { frase ->
            val intent = LucyIntentParserOffline.parse(
                entrada = frase,
                turnoActivo = Turno.A,
                hayAnalisisVisionActivo = true
            )
            assertTrue("Se esperaba FiltrarPlanillaVisionEmpresa para '$frase' pero fue $intent", intent is LucyIntent.FiltrarPlanillaVisionEmpresa)
            assertEquals(EmpresaOficial.FLUITEK, (intent as LucyIntent.FiltrarPlanillaVisionEmpresa).empresa)
        }

        val intentKomatsu = LucyIntentParserOffline.parse(
            entrada = "Ahora revisa los que coinciden con Komatsu",
            turnoActivo = Turno.A,
            hayAnalisisVisionActivo = true
        )
        assertTrue(intentKomatsu is LucyIntent.FiltrarPlanillaVisionEmpresa)
        assertEquals(EmpresaOficial.KOMATSU, (intentKomatsu as LucyIntent.FiltrarPlanillaVisionEmpresa).empresa)

        val intentDesconocida = LucyIntentParserOffline.parse(
            entrada = "Son de AceroMinero",
            turnoActivo = Turno.A,
            hayAnalisisVisionActivo = true
        )
        assertTrue(intentDesconocida is LucyIntent.FiltrarPlanillaVisionEmpresa)
        assertNull((intentDesconocida as LucyIntent.FiltrarPlanillaVisionEmpresa).empresa)
        assertTrue(intentDesconocida.empresaTexto.contains("AceroMinero", ignoreCase = true))

        // Flujo offline completo tras analizar foto
        val fakeVisionGw = FakeVisionGateway().apply {
            nextJson = """
                {
                  "isReadable": true,
                  "legibilityStatus": "LEGIBLE",
                  "documentTextType": "IMPRESO",
                  "detectedCompany": null,
                  "documentNotes": [],
                  "rows": [
                    {
                      "rowNumber": 1,
                      "rawText": "Carlos Mendez | 101 | A | SI",
                      "detectedName": "Carlos Mendez",
                      "detectedCompany": null,
                      "detectedPavilion": "A",
                      "detectedRoom": "101",
                      "visualMarkingText": "SI",
                      "interpretedMarkingStatus": "SI_BAJO",
                      "confidence": 0.90,
                      "ambiguityNotes": [],
                      "isIncompleteOrIllegible": false
                    },
                    {
                      "rowNumber": 2,
                      "rawText": "Pedro Rojas | 202 | B | SI",
                      "detectedName": "Pedro Rojas",
                      "detectedCompany": null,
                      "detectedPavilion": "B",
                      "detectedRoom": "202",
                      "visualMarkingText": "SI",
                      "interpretedMarkingStatus": "SI_BAJO",
                      "confidence": 0.90,
                      "ambiguityNotes": [],
                      "isIncompleteOrIllegible": false
                    }
                  ]
                }
            """.trimIndent()
        }
        val coordinatorOffline = LucyCoreCoordinator(
            repository = repository,
            visionPort = FirebaseLucyVisionPort(
                repository = repository,
                gateway = fakeVisionGw,
                imageLoader = AndroidLucyVisionImageLoader(),
                memoryPort = DataStoreLucyMemoryPort(createDataStore())
            ),
            memoryPort = DataStoreLucyMemoryPort(createDataStore())
        )
        val photoFile = createSheetPhotoFile("planilla_offline.jpg")
        coordinatorOffline.analizarPlanillaConLucyVision(
            imagePath = photoFile.absolutePath,
            contexto = LucyTurnContext(Turno.A),
            hablarAlFinalizar = false
        )

        val unknownResp = coordinatorOffline.procesarEntrada(
            entrada = "Son de AceroMinero",
            contexto = LucyTurnContext(Turno.A),
            hablarAlFinalizar = false
        )
        assertTrue(unknownResp.esErrorRecuperable)
        assertNull(unknownResp.visionAnalysis)
        assertTrue(unknownResp.texto.contains("AceroMinero", ignoreCase = true))
        assertTrue(unknownResp.texto.contains("no existe", ignoreCase = true))

        val strictFluitekResp = coordinatorOffline.procesarEntrada(
            entrada = "Filtra solamente los de Fluitek",
            contexto = LucyTurnContext(Turno.A),
            hablarAlFinalizar = false
        )
        assertEquals(EmpresaOficial.FLUITEK, strictFluitekResp.visionAnalysis?.empresaFiltroAplicada)
        assertEquals(
            LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA,
            strictFluitekResp.visionAnalysis?.trabajadoresDetectados?.get(0)?.coincidenciaProbable?.estado
        )
        assertEquals(
            LucyVisionMatchStatus.SIN_COINCIDENCIA,
            strictFluitekResp.visionAnalysis?.trabajadoresDetectados?.get(1)?.coincidenciaProbable?.estado
        )
        assertTrue(repository.observeTrabajadoresConEstado(Turno.A).first().none { it.siBajo })
        coordinatorOffline.shutdown()
    }

    private fun createDataStore(
        file: File = tempFolder.newFile("lucy_fix_${System.nanoTime()}.preferences_pb")
    ) = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopesToClose.add(it) },
        produceFile = { file }
    )

    private suspend fun agregar(
        nombre: String,
        paterno: String,
        materno: String,
        empresa: EmpresaOficial,
        turno: Turno,
        hab: String,
        pab: String
    ): TrabajadorConEstado {
        repository.setTurnoActivo(turno)
        val entity = repository.agregarTrabajador(
            turno = turno,
            empresa = empresa,
            nombre = nombre,
            primerApellido = paterno,
            segundoApellido = materno,
            habitacion = hab,
            pabellon = pab
        )
        return repository.observeTrabajadoresConEstado(turno).first().first { it.id == entity.id }
    }

    private fun createSheetPhotoFile(name: String): File {
        val file = tempFolder.newFile(name)
        val bmp = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()
        return file
    }

    private suspend fun esperarHasta(
        timeoutMs: Long = 5_000L,
        mensaje: suspend () -> String = { "" },
        condicion: suspend () -> Boolean
    ) {
        val inicio = System.currentTimeMillis()
        while (!condicion()) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() - inicio > timeoutMs) {
                val detalle = runCatching { mensaje() }.getOrDefault("")
                throw AssertionError("Condición no alcanzada en ${timeoutMs}ms ($detalle)")
            }
            delay(25)
        }
    }

    private data class RecordedCloudTtsCall(
        val text: String,
        val voiceId: String,
        val estiloVoz: LucyEstiloVoz,
        val velocidad: LucyVelocidadRespuesta
    )

    private class RecordingCloudTtsGateway : LucyCloudTtsGateway {
        override val providerName: String = "RecordingCloudTtsGateway"
        val calls = CopyOnWriteArrayList<RecordedCloudTtsCall>()
        @Volatile
        var nextFailureReason: String? = null

        override suspend fun isAvailable(): Boolean = true

        override suspend fun synthesizeAndPlay(
            text: String,
            voiceId: String,
            preferences: LucyPreferenciasConversacion,
            config: LucyVoicePersonalityConfig
        ): LucyCloudTtsResult {
            calls.add(
                RecordedCloudTtsCall(
                    text = text,
                    voiceId = voiceId,
                    estiloVoz = preferences.estiloVoz,
                    velocidad = preferences.velocidad
                )
            )
            val err = nextFailureReason
            if (err != null) {
                return LucyCloudTtsResult.FallbackRequired(err)
            }
            return LucyCloudTtsResult.Spoken(
                voiceName = voiceId.removePrefix("gemini:").ifBlank { "Sulafat" },
                modelName = "gemini-3.1-flash-tts-preview",
                firstAudioLatencyMs = 45L,
                totalLatencyMs = 90L
            )
        }

        override fun stop() = Unit
        override fun release() = Unit
    }

    private class RecordingTtsGateway : LucyTtsGateway {
        private var callbacks: LucyTtsGatewayCallbacks? = null
        val spokenTexts = CopyOnWriteArrayList<String>()

        override fun initialize(callbacks: LucyTtsGatewayCallbacks) {
            this.callbacks = callbacks
            callbacks.onInitialized(true)
        }

        override fun availableVoices(): List<LucyTtsVoiceInfo> = emptyList()
        override fun selectVoiceByName(voiceName: String): Boolean = true
        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun setSpeechRate(rate: Float) = Unit
        override fun setPitch(pitch: Float) = Unit

        override fun speak(text: String, utteranceId: String, flushQueue: Boolean): Int {
            spokenTexts.add(text)
            callbacks?.onUtteranceStart(utteranceId)
            callbacks?.onUtteranceDone(utteranceId)
            return TextToSpeech.SUCCESS
        }

        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private class FakeSttGateway : LucySttGateway {
        override fun hasRecordAudioPermission(): Boolean = true
        override fun isRecognitionAvailable(): Boolean = true
        override fun isOnDeviceRecognitionAvailable(): Boolean = true
        override fun startSession(
            preferOffline: Boolean,
            localeTag: String,
            callbacks: LucySttGatewayCallbacks
        ): LucySttSessionHandle {
            callbacks.onReadyForSpeech()
            return object : LucySttSessionHandle {
                override fun stopListening() = Unit
                override fun cancel() = Unit
                override fun destroy() = Unit
            }
        }
    }

    private class FakeVisionGateway : LucyVisionModelGateway {
        override val providerName: String = "FakeVisionGateway"
        var nextJson: String = "{}"

        override suspend fun checkStatus(): LucyVisionGatewayStatus = LucyVisionGatewayStatus(
            isConfigured = true,
            isNetworkAvailable = true,
            activeModelName = "gemini-3.8-flash",
            statusMessage = "OK"
        )

        override suspend fun analyzeSheetImage(
            payload: LucyVisionImagePayload,
            request: LucyVisionRequestContext
        ): LucyVisionRawGatewayResult = LucyVisionRawGatewayResult.Success(
            rawJson = nextJson,
            modelName = "gemini-3.8-flash"
        )
    }
}
