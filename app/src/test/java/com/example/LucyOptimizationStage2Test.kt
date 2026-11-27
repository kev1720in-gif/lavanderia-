package com.example

import android.content.Context
import android.speech.tts.TextToSpeech
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
import com.example.lucy.core.LucyOperationOrigin
import com.example.lucy.core.LucyTurnContext
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.example.lucy.ui.LucyViewModel
import com.example.lucy.voice.AndroidLucyVoicePort
import com.example.lucy.voice.LucyCloudTtsGateway
import com.example.lucy.voice.LucyCloudTtsResult
import com.example.lucy.voice.LucyGeminiVoiceCatalog
import com.example.lucy.voice.LucySttController
import com.example.lucy.voice.LucySttGateway
import com.example.lucy.voice.LucySttGatewayCallbacks
import com.example.lucy.voice.LucySttSessionHandle
import com.example.lucy.voice.LucyTtsEngine
import com.example.lucy.voice.LucyTtsGateway
import com.example.lucy.voice.LucyTtsGatewayCallbacks
import com.example.lucy.voice.LucyTtsVoiceInfo
import com.example.lucy.voice.LucyVoicePersonalityConfig
import com.example.lucy.voice.LucyVoiceTextFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class LucyOptimizationStage2Test {

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
        file: File = tempFolder.newFile("lucy_opt_${System.nanoTime()}.preferences_pb")
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
    fun `1 - Fast-path local responde Hola y Resumen sin esperar a Gemini ni bloquear red`() = runTest {
        agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Sebastián", "Morales", "Vega", "204", "B")
        agregarTrabajador(Turno.A, EmpresaOficial.FINNING, "Luis", "Mendoza", "Quispe", "108", "A")

        val geminiCalls = AtomicInteger(0)
        val slowBrain = object : LucyGeminiBrainGateway {
            override val providerName: String = "SlowGemini"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome {
                geminiCalls.incrementAndGet()
                delay(5_000L)
                return LucyGeminiBrainOutcome.Success("Respuesta tardía", "gemini-3.8-flash")
            }
        }

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            memoryPort = DataStoreLucyMemoryPort(createDataStore()),
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined,
            geminiBrainGateway = slowBrain
        )

        val t0Hola = System.nanoTime()
        val respHola = coordinator.procesarEntrada("Hola", LucyTurnContext(Turno.A))
        val msHola = (System.nanoTime() - t0Hola) / 1_000_000L

        val t0Resumen = System.nanoTime()
        val respResumen = coordinator.procesarEntrada("Dame el resumen del turno", LucyTurnContext(Turno.A))
        val msResumen = (System.nanoTime() - t0Resumen) / 1_000_000L

        println("[TIMING_BENCHMARK] fastpath_hola=${msHola}ms fastpath_resumen=${msResumen}ms")

        assertEquals(0, geminiCalls.get())
        assertTrue(respHola.performanceTrace?.usedLocalFastPath == true)
        assertTrue(respHola.texto.contains("Hola", ignoreCase = true))

        assertTrue(respResumen.performanceTrace?.usedLocalFastPath == true)
        assertTrue(
            "Resumen debe mostrar conteo real (0 SÍ BAJÓ / 2 en maestro): ${respResumen.texto}",
            respResumen.texto.contains("0 SÍ BAJÓ") && respResumen.texto.contains("2 en maestro")
        )
    }

    @Test
    fun `2 - Flujo conversacional completo con Gemini streaming, marcacion multiple y fallback sin conexion`() = runTest {
        val sebastian = agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Sebastián", "Morales", "Vega", "204", "B")
        val juan = agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Juan", "Pérez", "Soto", "101", "A")
        val diego = agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Diego", "Rojas", "Díaz", "102", "A")

        var offlineMode = false
        val partialSnapshots = CopyOnWriteArrayList<String>()
        val streamingBrain = object : LucyGeminiBrainGateway {
            override val providerName: String = "StreamingGemini"
            override suspend fun isAvailable(): Boolean = !offlineMode
            override suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome {
                val inputLower = request.userInput.lowercase()
                return if (inputLower.contains("sebastián") || inputLower.contains("sebastian")) {
                    val toolRes = request.toolExecutor.execute(
                        toolName = "buscar_trabajador",
                        args = mapOf("query" to JsonPrimitive("Sebastián Morales"))
                    )
                    request.onFirstToken?.invoke(25L)
                    request.onPartialText?.invoke("Sí, tengo registrado a Sebastián")
                    request.onPartialText?.invoke("Sí, tengo registrado a Sebastián Morales Vega en Komatsu.")
                    val msg = toolRes["mensaje_exacto_sistema"].toString().trim('"')
                    LucyGeminiBrainOutcome.Success(
                        replyText = "Sí, tengo registrado a Sebastián Morales Vega en Komatsu. $msg",
                        modelName = "gemini-3.8-flash",
                        toolCallCount = 1,
                        firstTokenLatencyMs = 25L,
                        totalLatencyMs = 60L
                    )
                } else {
                    val batchRes = request.toolExecutor.execute(
                        toolName = "marcar_subieron_lote",
                        args = mapOf(
                            "nombres_texto" to JsonPrimitive("Juan Pérez, Diego Rojas"),
                            "empresa" to JsonPrimitive("Komatsu")
                        )
                    )
                    val msg = batchRes["mensaje_exacto_sistema"].toString().trim('"')
                    LucyGeminiBrainOutcome.Success(
                        replyText = msg,
                        modelName = "gemini-3.8-flash",
                        toolCallCount = 1,
                        firstTokenLatencyMs = 30L,
                        totalLatencyMs = 70L
                    )
                }
            }
        }

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            memoryPort = DataStoreLucyMemoryPort(createDataStore()),
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined,
            geminiBrainGateway = streamingBrain
        )

        val respConsulta = coordinator.procesarEntrada(
            entrada = "¿Tienes registrado a Sebastián Morales?",
            contexto = LucyTurnContext(Turno.A),
            onPartialText = { partialSnapshots.add(it) }
        )
        assertEquals(LucyExecutionMode.HYBRID_CLOUD_OPTIONAL, respConsulta.modoEjecucion)
        assertTrue(partialSnapshots.isNotEmpty())
        assertTrue(respConsulta.texto.contains("Sebastián Morales Vega"))
        assertFalse(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == sebastian.id }.siBajo)

        val respLote = coordinator.procesarEntrada(
            entrada = "estos subieron de Komatsu: Juan Pérez y Diego Rojas",
            contexto = LucyTurnContext(Turno.A)
        )
        assertTrue(respLote.texto.contains("Marqué 2 trabajadores de Komatsu"))
        val actual = repository.observeTrabajadoresConEstado(Turno.A).first()
        assertTrue(actual.first { it.id == juan.id }.siBajo)
        assertTrue(actual.first { it.id == diego.id }.siBajo)

        offlineMode = true
        val respOffline = coordinator.procesarEntrada(
            entrada = "¿ya bajó Juan Pérez?",
            contexto = LucyTurnContext(Turno.A)
        )
        assertEquals(LucyExecutionMode.OFFLINE_ONLY, respOffline.modoEjecucion)
        assertTrue("Debe identificar a Juan Pérez: ${respOffline.texto}", respOffline.texto.contains("Pérez", ignoreCase = true))
        assertTrue("Debe mostrar estado SÍ BAJÓ: ${respOffline.texto}", respOffline.texto.contains("SÍ BAJÓ", ignoreCase = true))
    }

    @Test
    fun `3 - Voz unificada usa Despina y Sulafat igual en Probar voz y respuestas normales, persiste y reporta fallback honesto`() = runTest {
        val prefFile = File(tempFolder.root, "voice_unified_${System.nanoTime()}.preferences_pb")
        val fakeTts1 = FakeAndroidTtsGateway()
        val fakeCloud1 = FakeInstrumentedCloudTtsGateway()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds1 = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope1,
            produceFile = { prefFile }
        )
        val mem1 = DataStoreLucyMemoryPort(ds1, ioDispatcher = Dispatchers.Unconfined)
        val voice1 = AndroidLucyVoicePort(
            sttController = LucySttController(FakeSttGateway(), mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(fakeTts1, mainDispatcher = Dispatchers.Unconfined),
            memoryPort = mem1,
            ioDispatcher = Dispatchers.Unconfined,
            initialVoiceOutputEnabled = true,
            cloudTtsGateway = fakeCloud1
        )
        val coord1 = LucyCoreCoordinator(
            repository = repository,
            voicePort = voice1,
            memoryPort = mem1,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        coord1.refrescarSaludModulos()

        // A) Seleccionar Despina -> Probar voz -> Respuesta normal
        voice1.selectPreferredVoice("gemini:Despina")
        assertTrue(voice1.speakExplicitly("Hola, soy Lucy. Así suena mi voz.", forceRepeat = true))
        coord1.procesarEntrada("Hola", LucyTurnContext(Turno.A), LucyOperationOrigin.TEXT)

        assertEquals(2, fakeCloud1.calls.size)
        assertEquals("gemini:Despina", fakeCloud1.calls[0].voiceId)
        assertEquals("gemini:Despina", fakeCloud1.calls[1].voiceId)
        assertEquals("Gemini (Despina)", voice1.lastPlaybackReport.value?.engineLabel)
        assertEquals(0, fakeTts1.spokenTexts.size)

        // B) Caché en segunda prueba repetida con Despina
        assertTrue(voice1.speakExplicitly("Hola, soy Lucy. Así suena mi voz.", forceRepeat = true))
        assertTrue(voice1.lastPlaybackReport.value?.fromCache == true)

        // C) Cambiar a Sulafat + Estilo Pausada + Velocidad Lenta -> Probar voz + Respuesta normal
        voice1.selectPreferredVoice("gemini:Sulafat")
        voice1.selectVoiceStyle(LucyEstiloVoz.PAUSADA_SUAVE)
        voice1.selectSpeechSpeed(LucyVelocidadRespuesta.LENTA)

        assertTrue(voice1.speakExplicitly("Hola, soy Lucy. Así suena mi voz.", forceRepeat = true))
        coord1.procesarEntrada("Dame el resumen del turno", LucyTurnContext(Turno.A), LucyOperationOrigin.TEXT)

        assertEquals("gemini:Sulafat", fakeCloud1.calls[3].voiceId)
        assertEquals("gemini:Sulafat", fakeCloud1.calls[4].voiceId)
        assertEquals(LucyEstiloVoz.PAUSADA_SUAVE, fakeCloud1.calls[4].preferences.estiloVoz)
        assertEquals(LucyVelocidadRespuesta.LENTA, fakeCloud1.calls[4].preferences.velocidad)
        assertEquals("Gemini (Sulafat)", voice1.lastPlaybackReport.value?.engineLabel)
        assertEquals(0, fakeTts1.spokenTexts.size)

        voice1.release()
        scope1.coroutineContext[Job]?.cancelAndJoin()

        // D) Reiniciar app con nuevo DataStore / VoicePort / Coordinator -> debe recordar Sulafat, Pausada, Lenta
        val fakeTts2 = FakeAndroidTtsGateway()
        val fakeCloud2 = FakeInstrumentedCloudTtsGateway()
        val mem2 = DataStoreLucyMemoryPort(createDataStore(prefFile), ioDispatcher = Dispatchers.Unconfined)
        val voice2 = AndroidLucyVoicePort(
            sttController = LucySttController(FakeSttGateway(), mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(fakeTts2, mainDispatcher = Dispatchers.Unconfined),
            memoryPort = mem2,
            ioDispatcher = Dispatchers.Unconfined,
            initialVoiceOutputEnabled = true,
            cloudTtsGateway = fakeCloud2
        )
        val coord2 = LucyCoreCoordinator(
            repository = repository,
            voicePort = voice2,
            memoryPort = mem2,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        coord2.refrescarSaludModulos()

        coord2.procesarEntrada("Hola", LucyTurnContext(Turno.A), LucyOperationOrigin.TEXT)
        assertEquals(1, fakeCloud2.calls.size)
        assertEquals("gemini:Sulafat", fakeCloud2.calls.single().voiceId)
        assertEquals(LucyEstiloVoz.PAUSADA_SUAVE, fakeCloud2.calls.single().preferences.estiloVoz)
        assertEquals(LucyVelocidadRespuesta.LENTA, fakeCloud2.calls.single().preferences.velocidad)

        // E) Modo sin conexión / fallo de Gemini TTS -> Android TTS actúa como respaldo y se reporta con transparencia
        fakeCloud2.shouldFail = true
        coord2.procesarEntrada("Ayuda", LucyTurnContext(Turno.A), LucyOperationOrigin.TEXT)
        assertEquals(1, fakeTts2.spokenTexts.size)
        val reportFallback = voice2.lastPlaybackReport.value
        assertNotNull(reportFallback)
        assertTrue(reportFallback!!.engineLabel.contains("Dispositivo"))
        assertTrue(reportFallback.engineLabel.contains("Sulafat"))
        assertFalse(reportFallback.fallbackReason.isNullOrBlank())

        // healthCheck posterior NO debe ocultar que hubo fallback activo
        voice2.healthCheck()
        assertTrue(voice2.lastPlaybackReport.value?.fallbackReason != null)

        voice2.release()
    }

    @Test
    fun `4 - Desacoplamiento texto vs audio en LucyViewModel limpia Procesando antes de terminar audio y permite cancelar`() = runBlocking {
        val fakeTts = FakeAndroidTtsGateway()
        val audioStarted = CompletableDeferred<Unit>()
        val allowAudioFinish = CompletableDeferred<Unit>()

        val slowAudioCloud = object : LucyCloudTtsGateway {
            override val providerName: String = "SlowAudioCloud"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun synthesizeAndPlay(
                text: String,
                voiceId: String,
                preferences: LucyPreferenciasConversacion,
                config: LucyVoicePersonalityConfig
            ): LucyCloudTtsResult {
                audioStarted.complete(Unit)
                allowAudioFinish.await()
                return LucyCloudTtsResult.Spoken(
                    voiceName = "Despina",
                    modelName = LucyGeminiVoiceCatalog.GEMINI_TTS_MODEL_NAME,
                    fromCache = false,
                    firstAudioLatencyMs = 40L,
                    totalLatencyMs = 400L
                )
            }
            override fun stop() {
                if (audioStarted.isCompleted && !allowAudioFinish.isCompleted) {
                    allowAudioFinish.complete(Unit)
                }
            }
            override fun release() = stop()
        }

        val voicePort = AndroidLucyVoicePort(
            sttController = LucySttController(FakeSttGateway(), mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(fakeTts, mainDispatcher = Dispatchers.Unconfined),
            memoryPort = DataStoreLucyMemoryPort(createDataStore()),
            ioDispatcher = Dispatchers.IO,
            initialVoiceOutputEnabled = true,
            cloudTtsGateway = slowAudioCloud
        )
        val coordinator = LucyCoreCoordinator(
            repository = repository,
            voicePort = voicePort,
            ioDispatcher = Dispatchers.IO,
            defaultDispatcher = Dispatchers.Default
        )
        val viewModel = LucyViewModel(
            repository = repository,
            coordinator = coordinator,
            voicePort = voicePort
        )

        val tSend = System.currentTimeMillis()
        viewModel.enviarConsulta("Hola")

        // Esperar que aparezca la respuesta en pantalla aunque el audio todavía esté bloqueado en reproducción
        waitForCondition(2_500L) {
            viewModel.uiState.value.historialTurnoActivo.size >= 2 && !viewModel.uiState.value.procesando
        }
        val tTextShown = System.currentTimeMillis() - tSend

        assertFalse("Procesando debe desaparecer apenas llega el texto", viewModel.uiState.value.procesando)
        assertFalse("Audio aún no debe haber terminado cuando texto ya está visible", allowAudioFinish.isCompleted)

        waitForCondition(2_500L) { audioStarted.isCompleted }
        val tAudioStart = System.currentTimeMillis() - tSend
        allowAudioFinish.complete(Unit)

        println(
            "[TIMING_BENCHMARK] ui_text_visible=${tTextShown}ms audio_started_async=${tAudioStart}ms"
        )

        // Probar cancelación explícita de consulta lenta en Gemini
        val slowBrainStarted = CompletableDeferred<Unit>()
        val hangingBrain = object : LucyGeminiBrainGateway {
            override val providerName: String = "HangingBrain"
            override suspend fun isAvailable(): Boolean = true
            override suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome {
                slowBrainStarted.complete(Unit)
                delay(30_000L)
                return LucyGeminiBrainOutcome.FallbackToOffline("timeout")
            }
        }
        val coordCancel = LucyCoreCoordinator(
            repository = repository,
            geminiBrainGateway = hangingBrain,
            ioDispatcher = Dispatchers.IO,
            defaultDispatcher = Dispatchers.Default
        )
        val vmCancel = LucyViewModel(repository = repository, coordinator = coordCancel)
        vmCancel.enviarConsulta("¿Tienes registrado a Sebastián Morales?")
        waitForCondition(2_500L) { slowBrainStarted.isCompleted }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(vmCancel.uiState.value.procesando)

        vmCancel.cancelarOperacionActual()
        waitForCondition(1_500L) { !vmCancel.uiState.value.procesando }
        assertFalse(vmCancel.uiState.value.procesando)
        voicePort.release()
    }

    private fun waitForCondition(timeoutMs: Long = 2_500L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timeout esperando condición después de ${timeoutMs}ms")
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun `5 - Formateador de voz acorta listas largas en modo normal para evitar timeouts de Gemini TTS`() {
        val listaLarga = buildString {
            appendLine("Resumen Turno A: 5/20 bajaron.")
            appendLine("• Komatsu: 2/5")
            appendLine("• Finning: 1/4")
            appendLine("• Cummins: 1/4")
            appendLine("• Fluitek: 1/3")
            appendLine("• Somacor: 0/2")
            append("• Joy Global: 0/2")
        }

        val spoken = LucyVoiceTextFormatter.prepararParaVoz(
            textoOriginal = listaLarga,
            preferencias = LucyPreferenciasConversacion(estilo = LucyEstiloRespuesta.NORMAL)
        )
        assertTrue("Texto hablado debe acortarse para TTS rápido (<240 chars): ${spoken.length}", spoken.length <= 240)
        assertTrue("Debe indicar contenido restante en pantalla: $spoken", spoken.contains("en pantalla"))
    }

    private data class RecordedCloudCall(
        val text: String,
        val voiceId: String,
        val preferences: LucyPreferenciasConversacion
    )

    private class FakeInstrumentedCloudTtsGateway : LucyCloudTtsGateway {
        override val providerName: String = "FakeInstrumentedCloudTts"
        var shouldFail: Boolean = false
        val calls = CopyOnWriteArrayList<RecordedCloudCall>()
        private val cache = mutableSetOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun synthesizeAndPlay(
            text: String,
            voiceId: String,
            preferences: LucyPreferenciasConversacion,
            config: LucyVoicePersonalityConfig
        ): LucyCloudTtsResult {
            calls.add(RecordedCloudCall(text, voiceId, preferences))
            if (shouldFail) {
                return LucyCloudTtsResult.FallbackRequired("Error de red simulado")
            }
            val voiceName = LucyGeminiVoiceCatalog.resolveGeminiVoiceName(voiceId)
            val cacheKey = "$voiceName|${preferences.estiloVoz.name}|${preferences.velocidad.name}|$text"
            val hit = !cache.add(cacheKey)
            return LucyCloudTtsResult.Spoken(
                voiceName = voiceName,
                modelName = LucyGeminiVoiceCatalog.GEMINI_TTS_MODEL_NAME,
                fromCache = hit,
                firstAudioLatencyMs = if (hit) 0L else 120L,
                totalLatencyMs = if (hit) 5L else 180L
            )
        }

        override fun stop() = Unit
        override fun release() = Unit
    }

    private class FakeAndroidTtsGateway : LucyTtsGateway {
        private var callbacks: LucyTtsGatewayCallbacks? = null
        val spokenTexts = CopyOnWriteArrayList<String>()

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

    private class FakeSttGateway : LucySttGateway {
        override fun hasRecordAudioPermission(): Boolean = true
        override fun isRecognitionAvailable(): Boolean = true
        override fun isOnDeviceRecognitionAvailable(): Boolean = true
        override fun startSession(
            preferOffline: Boolean,
            localeTag: String,
            callbacks: LucySttGatewayCallbacks
        ): LucySttSessionHandle = object : LucySttSessionHandle {
            override fun stopListening() = Unit
            override fun cancel() = Unit
            override fun destroy() = Unit
        }
    }
}
