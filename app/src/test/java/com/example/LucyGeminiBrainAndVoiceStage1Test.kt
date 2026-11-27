package com.example

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import com.example.lucy.core.LucyExecutionMode
import com.example.lucy.core.LucyGeminiBrainGateway
import com.example.lucy.core.LucyGeminiBrainOutcome
import com.example.lucy.core.LucyGeminiBrainRequest
import com.example.lucy.core.LucySafeBatchMatcher
import com.example.lucy.core.LucyTurnContext
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.example.lucy.ui.LucyCoreUiState
import com.example.lucy.ui.LucyScreen
import com.example.lucy.voice.AndroidLucyVoicePort
import com.example.lucy.voice.LucyCloudTtsGateway
import com.example.lucy.voice.LucyCloudTtsResult
import com.example.lucy.voice.LucyGeminiVoiceCatalog
import com.example.lucy.voice.LucySpanishVoiceSelector
import com.example.lucy.voice.LucySttController
import com.example.lucy.voice.LucySttGateway
import com.example.lucy.voice.LucySttGatewayCallbacks
import com.example.lucy.voice.LucySttSessionHandle
import com.example.lucy.voice.LucyTtsEngine
import com.example.lucy.voice.LucyTtsGateway
import com.example.lucy.voice.LucyTtsGatewayCallbacks
import com.example.lucy.voice.LucyTtsVoiceInfo
import com.example.lucy.voice.LucyVoiceEngineType
import com.example.lucy.voice.LucyVoiceOption
import com.example.lucy.voice.LucyVoicePersonalityConfig
import com.example.lucy.voice.LucyVoiceUiStatus
import com.example.ui.theme.LavanderiaControlTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class LucyGeminiBrainAndVoiceStage1Test {

    @get:Rule
    val composeTestRule = createComposeRule()

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
            scopesToClose.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
        }
        scopesToClose.clear()
        db.close()
    }

    private fun createDataStore(
        file: File = tempFolder.newFile("lucy_stage1_${System.nanoTime()}.preferences_pb")
    ) = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopesToClose.add(it) },
        produceFile = { file }
    )

    private suspend fun agregarTrabajador(
        turno: Turno,
        empresa: EmpresaOficial,
        nombre: String,
        primerApellido: String,
        segundoApellido: String = "",
        habitacion: String = "101",
        pabellon: String = "A"
    ): TrabajadorConEstado {
        repository.setTurnoActivo(turno)
        repository.agregarTrabajador(
            turno = turno,
            empresa = empresa,
            nombre = nombre,
            primerApellido = primerApellido,
            segundoApellido = segundoApellido,
            habitacion = habitacion,
            pabellon = pabellon
        )
        return repository.observeTrabajadoresConEstado(turno).first()
            .first {
                it.nombre.equals(nombre, ignoreCase = true) &&
                    it.primerApellido.equals(primerApellido, ignoreCase = true) &&
                    it.empresa == empresa
            }
    }

    @Test
    fun `1 - Gemini Brain consulta trabajador por pregunta natural sin marcar DB`() = runTest {
        val sebastian = agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Sebastián",
            primerApellido = "Morales",
            segundoApellido = "Vega",
            habitacion = "204",
            pabellon = "B"
        )

        var lastToolArgsQuery: String? = null
        val fakeBrain = object : LucyGeminiBrainGateway {
            override val providerName: String = "FakeGemini"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome {
                lastToolArgsQuery = "Sebastián Morales"
                val result = request.toolExecutor.execute(
                    toolName = "buscar_trabajador",
                    args = mapOf("query" to JsonPrimitive("Sebastián Morales"))
                )
                val msgSistema = result["mensaje_exacto_sistema"].toString()
                return LucyGeminiBrainOutcome.Success(
                    replyText = "Sí, tengo registrado a Sebastián Morales Vega en Komatsu (hab. 204, pabellón B). $msgSistema",
                    modelName = "gemini-3.8-flash",
                    toolCallCount = 1
                )
            }
        }

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            memoryPort = DataStoreLucyMemoryPort(createDataStore()),
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined,
            geminiBrainGateway = fakeBrain
        )

        val resp = coordinator.procesarEntrada(
            entrada = "Lucy, ¿tienes registrado a Sebastián Morales?",
            contexto = LucyTurnContext(turnoActivo = Turno.A)
        )

        assertEquals(LucyExecutionMode.HYBRID_CLOUD_OPTIONAL, resp.modoEjecucion)
        assertEquals("Sebastián Morales", lastToolArgsQuery)
        assertTrue(resp.texto.contains("Sebastián Morales Vega"))
        assertEquals(1, resp.trabajadoresRelacionados.size)
        assertEquals(sebastian.id, resp.trabajadoresRelacionados.first().id)

        val actual = repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == sebastian.id }
        assertFalse("Pregunta de consulta jamás debe marcar trabajador", actual.siBajo)
    }

    @Test
    fun `2 - Guardia bloquea mutaciones si pregunta, mencion pasiva o hipotesis intenta llamar herramienta de marcacion`() = runTest {
        val pedro = agregarTrabajador(Turno.A, EmpresaOficial.FINNING, "Pedro", "González", "Rojas", "105", "A")
        var blockedCount = 0

        val rogueBrain = object : LucyGeminiBrainGateway {
            override val providerName: String = "RogueGemini"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome {
                val resLote = request.toolExecutor.execute(
                    toolName = "marcar_subieron_lote",
                    args = mapOf("nombres_texto" to JsonPrimitive("Pedro González"))
                )
                if (resLote["blocked"]?.toString() == "true") blockedCount++

                val resIndiv = request.toolExecutor.execute(
                    toolName = "preparar_marcacion_individual",
                    args = mapOf(
                        "query" to JsonPrimitive("Pedro González"),
                        "marcar_si_bajo" to JsonPrimitive(true)
                    )
                )
                if (resIndiv["blocked"]?.toString() == "true") blockedCount++

                val resUpdate = request.toolExecutor.execute(
                    toolName = "actualizar_dato_trabajador",
                    args = mapOf(
                        "nombre_trabajador" to JsonPrimitive("Pedro González"),
                        "nueva_habitacion" to JsonPrimitive("999")
                    )
                )
                if (resUpdate["blocked"]?.toString() == "true") blockedCount++

                return LucyGeminiBrainOutcome.Success(
                    replyText = "Solo fue una mención o hipótesis; no modifiqué nada.",
                    modelName = "gemini-3.8-flash",
                    toolCallCount = 3
                )
            }
        }

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined,
            geminiBrainGateway = rogueBrain
        )

        coordinator.procesarEntrada("¿Qué pasaría si marco a Pedro González?")
        coordinator.procesarEntrada("¿Tienes registrado a Pedro González?")

        assertEquals(6, blockedCount)
        val actual = repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == pedro.id }
        assertFalse(actual.siBajo)
        assertEquals("105", actual.habitacion)
    }

    @Test
    fun `3 - Marcacion grupal via Gemini Brain marca trabajadores reales en Room y deja desconocidos pendientes`() = runTest {
        val t1 = agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Juan", "Pérez", "Soto", "101", "A")
        val t2 = agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Diego", "Rojas", "Díaz", "102", "A")
        val otro = agregarTrabajador(Turno.A, EmpresaOficial.CUMMINS, "Carlos", "Tapia", "Mena", "301", "C")

        val batchBrain = object : LucyGeminiBrainGateway {
            override val providerName: String = "BatchGemini"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome {
                val res = request.toolExecutor.execute(
                    toolName = "marcar_subieron_lote",
                    args = mapOf(
                        "nombres_texto" to JsonPrimitive("Juan Pérez, Diego Rojas, Persona Inexistente"),
                        "empresa" to JsonPrimitive("Komatsu")
                    )
                )
                val msgSistema = res["mensaje_exacto_sistema"].toString().trim('"')
                return LucyGeminiBrainOutcome.Success(
                    replyText = msgSistema,
                    modelName = "gemini-3.8-flash",
                    toolCallCount = 1
                )
            }
        }

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined,
            geminiBrainGateway = batchBrain
        )

        val resp = coordinator.procesarEntrada("estos subieron de Komatsu: Juan Pérez, Diego Rojas y Persona Inexistente")

        assertTrue(resp.confirmacionRequerida == null)
        assertTrue(resp.texto.contains("Marqué 2 trabajadores de Komatsu"))
        assertTrue(resp.texto.contains("Persona Inexistente"))

        val estadoActual = repository.observeTrabajadoresConEstado(Turno.A).first()
        assertTrue(estadoActual.first { it.id == t1.id }.siBajo)
        assertTrue(estadoActual.first { it.id == t2.id }.siBajo)
        assertFalse(estadoActual.first { it.id == otro.id }.siBajo)
    }

    @Test
    fun `4 - Busqueda individual tolera variantes foneticas, errores tipograficos y detecta ambiguedad`() = runTest {
        val bryan = agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Brian", "Castro", "Pinto", "110", "A")
        val sebastian = agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Sebastián", "Morales", "Vega", "204", "B")
        agregarTrabajador(Turno.A, EmpresaOficial.FINNING, "Andrés", "Morales", "Silva", "205", "B")

        val universo = repository.observeTrabajadoresConEstado(Turno.A).first()

        val matchFon = LucySafeBatchMatcher.buscarIndividualTolerante("Bryan Castro", universo)
        assertEquals(1, matchFon.size)
        assertEquals(bryan.id, matchFon.first().id)

        val matchTypo = LucySafeBatchMatcher.buscarIndividualTolerante("Sebastan Moralez", universo)
        assertEquals(1, matchTypo.size)
        assertEquals(sebastian.id, matchTypo.first().id)

        val matchAmbiguo = LucySafeBatchMatcher.buscarIndividualTolerante("Morales", universo)
        assertEquals(2, matchAmbiguo.size)

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        val respEstado = coordinator.procesarEntrada("¿ya bajó Bryan Castro?")
        assertTrue(
            "Se esperaba encontrar ${bryan.nombreCompleto} en: ${respEstado.texto}",
            respEstado.texto.contains(bryan.nombreCompleto)
        )
    }

    @Test
    fun `5 - Fallback automatico offline y aislamiento de contexto entre Turno A y Turno B`() = runTest {
        val sebastian = agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Sebastián", "Morales", "Vega", "204", "B")
        agregarTrabajador(Turno.B, EmpresaOficial.FINNING, "Matías", "Reyes", "Lagos", "401", "D")

        val historyCaptured = mutableListOf<LucyGeminiBrainRequest>()
        var failWithFallback = true
        val switchableBrain = object : LucyGeminiBrainGateway {
            override val providerName: String = "SwitchableGemini"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome {
                historyCaptured.add(request)
                if (failWithFallback) {
                    return LucyGeminiBrainOutcome.FallbackToOffline("Sin red simulada")
                }
                val res = request.toolExecutor.execute(
                    toolName = "buscar_trabajador",
                    args = mapOf("query" to JsonPrimitive("Matías Reyes"))
                )
                return LucyGeminiBrainOutcome.Success(
                    replyText = res["mensaje_exacto_sistema"].toString().trim('"'),
                    modelName = "gemini-3.5-flash-lite",
                    toolCallCount = 1
                )
            }
        }

        val memoryPort = DataStoreLucyMemoryPort(createDataStore())
        val coordinator = LucyCoreCoordinator(
            repository = repository,
            memoryPort = memoryPort,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined,
            geminiBrainGateway = switchableBrain
        )

        // Cuando Gemini falla, responde correctamente el motor offline sin perder el turno A
        val offlineResp = coordinator.procesarEntrada(
            entrada = "¿ya subió Sebastián Morales?",
            contexto = LucyTurnContext(turnoActivo = Turno.A)
        )
        assertEquals(LucyExecutionMode.OFFLINE_ONLY, offlineResp.modoEjecucion)
        assertTrue(
            "Se esperaba encontrar ${sebastian.nombreCompleto} en: ${offlineResp.texto}",
            offlineResp.texto.contains(sebastian.nombreCompleto)
        )

        // En Turno B, el historial de Turno A NO debe filtrarse
        failWithFallback = false
        repository.setTurnoActivo(Turno.B)
        val turnoBResp = coordinator.procesarEntrada(
            entrada = "¿tienes registrado a Matías Reyes?",
            contexto = LucyTurnContext(turnoActivo = Turno.B)
        )
        assertEquals(LucyExecutionMode.HYBRID_CLOUD_OPTIONAL, turnoBResp.modoEjecucion)
        val reqB = historyCaptured.last()
        assertEquals(Turno.B, reqB.turnoActivo)
        assertTrue("El historial reciente de Turno B no debe incluir conversaciones de Turno A", reqB.recentHistory.isEmpty())
        assertTrue(reqB.ultimoTrabajadorResumen == null)
    }

    @Test
    fun `6 - Selector prioriza voz femenina en español y DataStore persiste voz y estilo`() = runTest {
        val voces = listOf(
            LucyTtsVoiceInfo("es-cl-x-diego-local", "es-CL", isNetworkRequired = false, quality = 400),
            LucyTtsVoiceInfo("es-cl-x-camila-female-local", "es-CL", isNetworkRequired = false, quality = 400),
            LucyTtsVoiceInfo("es-es-x-lucia-female-local", "es-ES", isNetworkRequired = false, quality = 500)
        )
        val seleccionada = LucySpanishVoiceSelector.pickBestSpanishVoice(
            voices = voces,
            preferredLocaleTags = listOf("es-CL", "es-419", "es-ES")
        )
        assertEquals("es-cl-x-camila-female-local", seleccionada?.name)

        val prefFile = File(tempFolder.root, "prefs_voice_${System.nanoTime()}.preferences_pb")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds1 = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope1,
            produceFile = { prefFile }
        )
        val mem1 = DataStoreLucyMemoryPort(ds1, ioDispatcher = Dispatchers.Unconfined)
        mem1.actualizarVozPreferida("gemini:Kore")
        mem1.actualizarEstiloVoz(LucyEstiloVoz.PAUSADA_SUAVE)
        mem1.actualizarVelocidadRespuesta(LucyVelocidadRespuesta.LENTA)
        scope1.coroutineContext[Job]?.cancelAndJoin()

        val mem2 = DataStoreLucyMemoryPort(createDataStore(prefFile), ioDispatcher = Dispatchers.Unconfined)
        val loaded = mem2.obtenerPreferencias()
        assertEquals("gemini:Kore", loaded.vozPreferidaId)
        assertEquals(LucyEstiloVoz.PAUSADA_SUAVE, loaded.estiloVoz)
        assertEquals(LucyVelocidadRespuesta.LENTA, loaded.velocidad)
    }

    @Test
    fun `7 - AndroidLucyVoicePort usa Gemini TTS cuando disponible y cae a Android TTS cuando falla`() = runTest {
        val fakeTts = FakeTtsGatewayForStage1()
        val fakeStt = FakeSttGatewayForStage1()
        val ttsEngine = LucyTtsEngine(gateway = fakeTts, mainDispatcher = Dispatchers.Unconfined)
        ttsEngine.initialize()
        val sttController = LucySttController(gateway = fakeStt, mainDispatcher = Dispatchers.Unconfined)
        val memoryPort = DataStoreLucyMemoryPort(createDataStore())

        var shouldCloudFail = false
        var cloudCalls = 0
        val fakeCloudTts = object : LucyCloudTtsGateway {
            override val providerName: String = "FakeCloudTts"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun synthesizeAndPlay(
                text: String,
                voiceId: String,
                preferences: LucyPreferenciasConversacion,
                config: LucyVoicePersonalityConfig
            ): LucyCloudTtsResult {
                cloudCalls++
                return if (shouldCloudFail) {
                    LucyCloudTtsResult.FallbackRequired("Error de red simulado")
                } else {
                    LucyCloudTtsResult.Spoken(
                        voiceName = LucyGeminiVoiceCatalog.resolveGeminiVoiceName(voiceId),
                        modelName = LucyGeminiVoiceCatalog.GEMINI_TTS_MODEL_NAME
                    )
                }
            }
            override fun stop() = Unit
            override fun release() = Unit
        }

        val voicePort = AndroidLucyVoicePort(
            sttController = sttController,
            ttsEngine = ttsEngine,
            memoryPort = memoryPort,
            ioDispatcher = Dispatchers.Unconfined,
            initialVoiceOutputEnabled = true,
            cloudTtsGateway = fakeCloudTts
        )

        voicePort.selectPreferredVoice("gemini:Aoede")
        voicePort.speakIfEnabled("Hola, primer aviso.")
        assertEquals(1, cloudCalls)
        assertEquals(0, fakeTts.spokenTexts.size)

        shouldCloudFail = true
        voicePort.speakIfEnabled("Segundo aviso con respaldo local.")
        assertEquals(2, cloudCalls)
        assertEquals(1, fakeTts.spokenTexts.size)
        assertTrue(fakeTts.spokenTexts.first().contains("Segundo aviso"))
        voicePort.release()
    }

    @Test
    fun `8 - UI mantiene cabecera sobria y permite configurar voz desde dialogo discreto`() {
        var selectedVoice: String? = null
        var selectedStyle: LucyEstiloVoz? = null

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LucyScreen(
                    uiState = LucyCoreUiState(
                        voiceStatus = LucyVoiceUiStatus(
                            availableVoices = listOf(
                                LucyVoiceOption(
                                    id = "",
                                    displayName = "Automática (Gemini femenina / dispositivo)",
                                    description = "Predeterminada",
                                    engineType = LucyVoiceEngineType.AUTO
                                ),
                                LucyVoiceOption(
                                    id = "gemini:Despina",
                                    displayName = "Lucy Despina (Gemini)",
                                    description = "Femenina cálida",
                                    engineType = LucyVoiceEngineType.GEMINI_TTS,
                                    isCloud = true
                                )
                            )
                        )
                    ),
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
                    onRefrescarEstado = {},
                    onSeleccionarVoz = { selectedVoice = it },
                    onSeleccionarEstiloVoz = { selectedStyle = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("lucy_voice_settings_button").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("lucy_voice_settings_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("lucy_voice_style_pausada_suave").performClick()
        composeTestRule.onNodeWithTag("lucy_voice_option_gemini:Despina").performClick()
        composeTestRule.onNodeWithTag("lucy_voice_dialog_close_button").performClick()

        assertEquals(LucyEstiloVoz.PAUSADA_SUAVE, selectedStyle)
        assertEquals("gemini:Despina", selectedVoice)
    }

    private class FakeTtsGatewayForStage1 : LucyTtsGateway {
        private var callbacks: LucyTtsGatewayCallbacks? = null
        val spokenTexts = mutableListOf<String>()

        override fun initialize(callbacks: LucyTtsGatewayCallbacks) {
            this.callbacks = callbacks
            callbacks.onInitialized(true)
        }

        override fun availableVoices(): List<LucyTtsVoiceInfo> = listOf(
            LucyTtsVoiceInfo("es-cl-x-camila-female-local", "es-CL", isNetworkRequired = false, quality = 400)
        )

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

    private class FakeSttGatewayForStage1 : LucySttGateway {
        override fun hasRecordAudioPermission(): Boolean = true
        override fun isRecognitionAvailable(): Boolean = true
        override fun isOnDeviceRecognitionAvailable(): Boolean = true
        override fun startSession(
            preferOffline: Boolean,
            localeTag: String,
            callbacks: LucySttGatewayCallbacks
        ): LucySttSessionHandle {
            return object : LucySttSessionHandle {
                override fun stopListening() = Unit
                override fun cancel() = Unit
                override fun destroy() = Unit
            }
        }
    }
}
