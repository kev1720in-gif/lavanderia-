package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LavanderiaDatabase
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.Turno
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyModuleHealth
import com.example.lucy.core.LucyModuleId
import com.example.lucy.core.LucyOperationOrigin
import com.example.lucy.core.LucyTurnContext
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.memory.LucyInstruccionMemoria
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.example.lucy.voice.AndroidLucyVoicePort
import com.example.lucy.voice.AndroidSpeechRecognizerGateway
import com.example.lucy.voice.AndroidTextToSpeechGateway
import com.example.lucy.voice.LucyMicrophonePermissionState
import com.example.lucy.voice.LucySpanishVoiceSelector
import com.example.lucy.voice.LucySttController
import com.example.lucy.voice.LucySttGateway
import com.example.lucy.voice.LucySttGatewayCallbacks
import com.example.lucy.voice.LucySttSessionHandle
import com.example.lucy.voice.LucySttStartResult
import com.example.lucy.voice.LucySttState
import com.example.lucy.voice.LucyTtsEngine
import com.example.lucy.voice.LucyTtsGateway
import com.example.lucy.voice.LucyTtsGatewayCallbacks
import com.example.lucy.voice.LucyTtsState
import com.example.lucy.voice.LucyTtsVoiceInfo
import com.example.lucy.voice.LucyVoicePersonalityConfig
import com.example.lucy.voice.LucyVoiceTextFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
@Config(sdk = [36])
class LucyVoiceStage3Test {

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

    private fun createDataStore(file: File = tempFolder.newFile("voice_mem_${System.nanoTime()}.preferences_pb")) =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopesToClose.add(it) },
            produceFile = { file }
        )

    @Test
    fun `1 - inicializacion TTS selecciona voz espanola chilena y maneja fallos de idioma`() = runTest {
        val fakeTts = FakeTtsGateway(
            voices = listOf(
                LucyTtsVoiceInfo("en-us-x-sfg", "en-US", isNetworkRequired = false, quality = 400),
                LucyTtsVoiceInfo("es-es-x-ana-network", "es-ES", isNetworkRequired = true, quality = 400),
                LucyTtsVoiceInfo("es-cl-x-camila-local", "es-CL", isNetworkRequired = false, quality = 400),
                LucyTtsVoiceInfo("es-419-local", "es-419", isNetworkRequired = false, quality = 300)
            )
        )
        val engine = LucyTtsEngine(
            gateway = fakeTts,
            mainDispatcher = Dispatchers.Unconfined
        )

        assertTrue(engine.initialize())
        val state = engine.ttsState.value
        assertTrue(state is LucyTtsState.Ready)
        state as LucyTtsState.Ready
        assertEquals("es-CL", state.selectedLocaleTag)
        assertEquals("es-cl-x-camila-local", state.voiceName)
        assertTrue(state.isLocalVoice)

        // Si el motor falla al inicializar -> estado Error controlado sin excepción
        val brokenTts = FakeTtsGateway(initSucceeds = false)
        val brokenEngine = LucyTtsEngine(gateway = brokenTts, mainDispatcher = Dispatchers.Unconfined)
        assertFalse(brokenEngine.initialize())
        assertTrue(brokenEngine.ttsState.value is LucyTtsState.Error)

        // Si no hay ningún idioma español disponible -> Error claro
        val noSpanishTts = FakeTtsGateway(
            voices = listOf(LucyTtsVoiceInfo("en-us", "en-US", false, 400)),
            languageSupportResult = TextToSpeech.LANG_NOT_SUPPORTED
        )
        val noSpanishEngine = LucyTtsEngine(gateway = noSpanishTts, mainDispatcher = Dispatchers.Unconfined)
        assertFalse(noSpanishEngine.initialize())
        assertTrue(noSpanishEngine.ttsState.value is LucyTtsState.Error)

        // Selector prioriza variante latinoamericana si no hay es-CL
        val pickedFallback = LucySpanishVoiceSelector.pickBestSpanishVoice(
            listOf(
                LucyTtsVoiceInfo("es-es", "es-ES", false, 400),
                LucyTtsVoiceInfo("es-419", "es-419", false, 300)
            )
        )
        assertEquals("es-419", pickedFallback?.name)
    }

    @Test
    fun `2 - STT verifica disponibilidad y reporta con honestidad compatibilidad offline`() {
        val manifestPerms = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()
        assertTrue(manifestPerms.contains(Manifest.permission.RECORD_AUDIO))

        // Servicio disponible pero sin confirmación offline: NO debe afirmar que funciona sin Internet
        val onlineOnlyGateway = FakeSttGateway(
            permissionGranted = true,
            serviceAvailable = true,
            onDeviceAvailable = false
        )
        val onlineOnlyStt = LucySttController(onlineOnlyGateway, mainDispatcher = Dispatchers.Unconfined)
        val availOnline = onlineOnlyStt.refreshStatus()
        assertTrue(availOnline.isServiceAvailable)
        assertFalse(availOnline.isOnDeviceAvailable)
        assertTrue(availOnline.offlineHonestSummary.contains("no confirma reconocimiento sin conexión"))

        // Nunca inicia escucha automáticamente sin acción explícita del usuario
        assertEquals(0, onlineOnlyGateway.startCount)
        assertEquals(LucySttState.Idle, onlineOnlyStt.sttState.value)

        // Dispositivo que sí confirma reconocimiento on-device
        val offlineGateway = FakeSttGateway(
            permissionGranted = true,
            serviceAvailable = true,
            onDeviceAvailable = true
        )
        val offlineStt = LucySttController(offlineGateway, mainDispatcher = Dispatchers.Unconfined)
        val availOffline = offlineStt.refreshStatus()
        assertTrue(availOffline.isOnDeviceAvailable)
        assertTrue(availOffline.offlineHonestSummary.contains("disponible"))

        // Gateways reales Android en entorno Robolectric responden sin lanzar excepción
        val androidStt = AndroidSpeechRecognizerGateway(context)
        androidStt.isRecognitionAvailable()
        androidStt.isOnDeviceRecognitionAvailable()
        androidStt.hasRecordAudioPermission()
        AndroidTextToSpeechGateway(context).shutdown()
    }

    @Test
    fun `3 - STT bloquea escucha si permiso de microfono esta denegado o servicio no disponible`() = runTest {
        val deniedGateway = FakeSttGateway(
            permissionGranted = false,
            serviceAvailable = true,
            onDeviceAvailable = false
        )
        val sttDenied = LucySttController(deniedGateway, mainDispatcher = Dispatchers.Unconfined)

        val result = sttDenied.startListening()
        assertEquals(LucySttStartResult.PermissionDenied, result)
        assertEquals(LucyMicrophonePermissionState.DENIED, sttDenied.permissionState.value)
        assertTrue(sttDenied.sttState.value is LucySttState.Error)
        assertEquals(0, deniedGateway.startCount)

        // Servicio no instalado
        val unavailableGateway = FakeSttGateway(
            permissionGranted = true,
            serviceAvailable = false,
            onDeviceAvailable = false
        )
        val sttUnavailable = LucySttController(unavailableGateway, mainDispatcher = Dispatchers.Unconfined)
        val resUnavail = sttUnavailable.startListening()
        assertTrue(resUnavail is LucySttStartResult.ServiceUnavailable)
        assertEquals(0, unavailableGateway.startCount)
    }

    @Test
    fun `4 - STT maneja parciales, resultado final, errores y timeout de silencio`() = runTest {
        val gateway = FakeSttGateway(
            permissionGranted = true,
            serviceAvailable = true,
            onDeviceAvailable = true
        )
        val stt = LucySttController(
            gateway = gateway,
            config = LucyVoicePersonalityConfig(silenceTimeoutMs = 200L),
            mainDispatcher = Dispatchers.Unconfined
        )

        val parciales = mutableListOf<String>()
        var finalRecibido: String? = null
        val inicio = stt.startListening(
            preferOffline = true,
            onPartialTranscript = { parciales.add(it) },
            onFinalTranscript = { finalRecibido = it }
        )
        assertEquals(LucySttStartResult.Started, inicio)
        assertTrue(stt.sttState.value is LucySttState.Listening)

        gateway.lastCallbacks?.onReadyForSpeech()
        gateway.lastCallbacks?.onBeginningOfSpeech()
        gateway.lastCallbacks?.onPartialResult("resumen")
        assertTrue((stt.sttState.value as LucySttState.Listening).partialText == "resumen")

        gateway.lastCallbacks?.onFinalResult("resumen Finning")
        assertEquals(listOf("resumen"), parciales)
        assertEquals("resumen Finning", finalRecibido)
        assertEquals(LucySttState.Completed("resumen Finning"), stt.sttState.value)
        assertTrue(gateway.lastHandle?.destroyed == true)

        // Error recuperable de red/silencio limpia sesión y permite reintento
        assertEquals(LucySttStartResult.Started, stt.startListening())
        gateway.lastCallbacks?.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "No detecté voz a tiempo.")
        val errState = stt.sttState.value as LucySttState.Error
        assertEquals(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, errState.errorCode)
        assertTrue(errState.isRecoverable)
        assertTrue(gateway.lastHandle?.destroyed == true)

        // Watchdog de silencio cierra sesión si el servicio nunca responde
        val fastTimeoutGateway = FakeSttGateway(permissionGranted = true, serviceAvailable = true, onDeviceAvailable = false)
        val fastTimeoutStt = LucySttController(
            gateway = fastTimeoutGateway,
            config = LucyVoicePersonalityConfig(silenceTimeoutMs = 40L),
            mainDispatcher = Dispatchers.Unconfined
        )
        assertEquals(LucySttStartResult.Started, fastTimeoutStt.startListening())
        waitUntil { fastTimeoutStt.sttState.value is LucySttState.Error }
        assertTrue(fastTimeoutStt.sttState.value is LucySttState.Error)
        assertTrue(fastTimeoutGateway.lastHandle?.destroyed == true)
    }

    private fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timeout esperando condición después de ${timeoutMs}ms")
            }
            Thread.sleep(20L)
        }
    }

    @Test
    fun `5 - STT cancela escucha limpiamente e impide sesiones simultaneas`() = runTest {
        val gateway = FakeSttGateway(
            permissionGranted = true,
            serviceAvailable = true,
            onDeviceAvailable = false
        )
        val stt = LucySttController(gateway, mainDispatcher = Dispatchers.Unconfined)

        // Primera sesión activa
        assertEquals(LucySttStartResult.Started, stt.startListening())
        assertEquals(1, gateway.startCount)

        // Intento simultáneo es rechazado sin crear segundo reconocedor
        assertEquals(LucySttStartResult.AlreadyListening, stt.startListening())
        assertEquals(1, gateway.startCount)

        val callbacksPrimeraSesion = gateway.lastCallbacks
        val handlePrimeraSesion = gateway.lastHandle

        // Cancelación explícita destruye el handle e ignora eventos tardíos
        stt.cancelListening()
        assertEquals(LucySttState.Cancelled, stt.sttState.value)
        assertTrue(handlePrimeraSesion?.destroyed == true)

        callbacksPrimeraSesion?.onFinalResult("texto tardío ignorado")
        assertEquals(LucySttState.Cancelled, stt.sttState.value)

        // Luego de cancelar sí puede iniciar una nueva sesión
        assertEquals(LucySttStartResult.Started, stt.startListening())
        assertEquals(2, gateway.startCount)
    }

    @Test
    fun `6 - TTS limpia simbolos, no habla vacios, no repite mensajes y mantiene tono chileno natural`() = runTest {
        var nowMs = 1_000L
        val fakeTts = FakeTtsGateway(autoCompleteSpeak = true)
        val engine = LucyTtsEngine(
            gateway = fakeTts,
            mainDispatcher = Dispatchers.Unconfined,
            clock = { nowMs }
        )

        // Vacíos y solo símbolos no hablan
        assertFalse(engine.speak(""))
        assertFalse(engine.speak("   \n  "))
        assertFalse(engine.speak("• · — [] ***"))
        assertEquals(0, fakeTts.spokenTexts.size)

        // Formatea limpiamente boletines y abreviaturas sin leer símbolos
        val textoVisual = """
            • MENDOZA QUISPE LUIS · Hab. 108 · Finning [NO BAJÓ]
            Por empresa: Finning: 2/5 | Komatsu: 3/4
        """.trimIndent()
        assertTrue(engine.speak(textoVisual))
        val hablado = fakeTts.spokenTexts.single()
        assertFalse(hablado.contains("•"))
        assertFalse(hablado.contains("·"))
        assertFalse(hablado.contains("["))
        assertFalse(hablado.contains("|"))
        assertTrue(hablado.contains("habitación 108"))
        assertTrue(hablado.contains("no bajó"))
        assertTrue(hablado.contains("2 de 5"))

        // No repite exactamente el mismo mensaje dentro de la ventana de supresión
        nowMs += 500L
        assertFalse(engine.speak(textoVisual))
        assertEquals(1, fakeTts.spokenTexts.size)

        // Distinto mensaje sí se reproduce normalmente
        assertTrue(engine.speak("Confirmado en Turno A: MENDOZA QUISPE LUIS quedó marcado como SÍ BAJÓ."))
        assertEquals(2, fakeTts.spokenTexts.size)
        assertTrue(fakeTts.spokenTexts.last().startsWith("Listo en Turno A,"))

        // Estilo conciso + instrucción permanente acorta encabezados y limita listas largas
        val formateadoConciso = LucyVoiceTextFormatter.prepararParaVoz(
            textoOriginal = "Faltan 5 trabajadores:\n• Uno\n• Dos\n• Tres\n• Cuatro\n• Cinco",
            preferencias = LucyPreferenciasConversacion(
                estilo = LucyEstiloRespuesta.CONCISA,
                instruccionesPermanentes = listOf(
                    LucyInstruccionMemoria(claveNormalizada = "rodeos", texto = "Responder sin rodeos")
                )
            )
        )
        assertTrue(formateadoConciso.contains("en pantalla"))
    }

    @Test
    fun `7 - aplica velocidad y estilo desde DataStore Memory, persiste tras reinicio e integra con LucyCoreCoordinator`() = runTest {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.FINNING,
            nombre = "Luis",
            primerApellido = "Mendoza",
            segundoApellido = "Quispe",
            habitacion = "108",
            pabellon = "B"
        )

        val dsFile = File(tempFolder.root, "voice_memory_persist.preferences_pb")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds1 = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope1,
            produceFile = { dsFile }
        )
        val memory1 = DataStoreLucyMemoryPort(ds1, ioDispatcher = Dispatchers.Unconfined)
        val stt1 = LucySttController(
            FakeSttGateway(permissionGranted = true, serviceAvailable = true, onDeviceAvailable = true),
            mainDispatcher = Dispatchers.Unconfined
        )
        val fakeTts1 = FakeTtsGateway(autoCompleteSpeak = true)
        val tts1 = LucyTtsEngine(fakeTts1, mainDispatcher = Dispatchers.Unconfined)
        val voice1 = AndroidLucyVoicePort(
            sttController = stt1,
            ttsEngine = tts1,
            memoryPort = memory1,
            ioDispatcher = Dispatchers.Unconfined,
            initialVoiceOutputEnabled = true
        )
        val coord1 = LucyCoreCoordinator(
            repository = repository,
            voicePort = voice1,
            memoryPort = memory1,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val salud = coord1.refrescarSaludModulos()
        assertEquals(LucyModuleHealth.Ready, salud[LucyModuleId.VOICE])
        assertEquals(LucyModuleHealth.Ready, salud[LucyModuleId.MEMORY])

        // Cambiar velocidad y estilo mediante Lucy Core -> debe aplicarse inmediatamente al TTS
        coord1.procesarEntrada("Lucy, habla un poco más rápido", LucyTurnContext(Turno.A), LucyOperationOrigin.VOICE)
        assertEquals(1.24f, fakeTts1.lastSpeechRate ?: 0f, 0.01f)

        coord1.procesarEntrada("Prefiero que respondas más corto", LucyTurnContext(Turno.A), LucyOperationOrigin.VOICE)
        assertEquals(LucyEstiloRespuesta.CONCISA, voice1.voiceStatus.value.let {
            // Al no tener colector aún sobre voiceStatus, consultamos directamente preferencias sincronizadas en TTS/Memory
            memory1.obtenerPreferencias().estilo
        })

        // Simular cierre completo de app: liberar voice1 y cerrar DataStore
        voice1.release()
        scope1.coroutineContext[Job]?.cancelAndJoin()

        // Reabrir app con nueva instancia de DataStore, VoicePort y Coordinator
        val ds2 = createDataStore(dsFile)
        val memory2 = DataStoreLucyMemoryPort(ds2, ioDispatcher = Dispatchers.Unconfined)
        val sttGateway2 = FakeSttGateway(permissionGranted = true, serviceAvailable = true, onDeviceAvailable = true)
        val fakeTts2 = FakeTtsGateway(autoCompleteSpeak = true)
        val voice2 = AndroidLucyVoicePort(
            sttController = LucySttController(sttGateway2, mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(fakeTts2, mainDispatcher = Dispatchers.Unconfined),
            memoryPort = memory2,
            ioDispatcher = Dispatchers.Unconfined,
            initialVoiceOutputEnabled = true
        )
        val coord2 = LucyCoreCoordinator(
            repository = repository,
            voicePort = voice2,
            memoryPort = memory2,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )

        coord2.refrescarSaludModulos()
        assertEquals(1.24f, fakeTts2.lastSpeechRate ?: 0f, 0.01f)

        // Dictado simulado vía STT explícito procesa consulta en Lucy Core y habla la respuesta concisa
        val respuestaPorVozDeferred = CompletableDeferred<String>()
        val inicioVoz = voice2.startUserInitiatedListening(
            preferOffline = true,
            onFinalTranscript = { transcrito ->
                val resp = coord2.procesarEntrada(
                    entrada = transcrito,
                    contexto = LucyTurnContext(Turno.A),
                    origen = LucyOperationOrigin.VOICE
                )
                respuestaPorVozDeferred.complete(resp.texto)
            }
        )
        assertEquals(LucySttStartResult.Started, inicioVoz)
        sttGateway2.lastCallbacks?.onFinalResult("resumen")
        waitUntil { respuestaPorVozDeferred.isCompleted }
        val respuestaPorVoz = respuestaPorVozDeferred.await()
        assertTrue(respuestaPorVoz.contains("0/1 bajaron"))
        assertTrue(fakeTts2.spokenTexts.isNotEmpty())
        voice2.release()
    }

    @Test
    fun `8 - liberacion de recursos detiene TTS y STT e impide usos posteriores y propaga VirtualMachineError`() = runTest {
        val sttGateway = FakeSttGateway(permissionGranted = true, serviceAvailable = true, onDeviceAvailable = false)
        val ttsGateway = FakeTtsGateway(autoCompleteSpeak = false)
        val voicePort = AndroidLucyVoicePort(
            sttController = LucySttController(sttGateway, mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(ttsGateway, mainDispatcher = Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            initialVoiceOutputEnabled = true
        )

        voicePort.healthCheck()
        voicePort.speakIfEnabled("Hola desde Turno A")
        assertEquals(LucySttStartResult.Started, voicePort.startUserInitiatedListening())

        // Liberar recursos limpia ambos subsistemas
        voicePort.release()
        assertTrue(sttGateway.lastHandle?.destroyed == true)
        assertTrue(ttsGateway.stopped)
        assertTrue(ttsGateway.shutdownCalled)
        assertTrue(voicePort.healthCheck() is LucyModuleHealth.Unavailable)
        assertTrue(voicePort.startUserInitiatedListening() is LucySttStartResult.Failed)

        // VirtualMachineError siempre se relanza sin ocultarse
        val oomSttGateway = object : LucySttGateway {
            override fun hasRecordAudioPermission(): Boolean = true
            override fun isRecognitionAvailable(): Boolean = true
            override fun isOnDeviceRecognitionAvailable(): Boolean = false
            override fun startSession(
                preferOffline: Boolean,
                localeTag: String,
                callbacks: LucySttGatewayCallbacks
            ): LucySttSessionHandle = throw OutOfMemoryError("OOM simulado en STT")
        }
        val oomStt = LucySttController(oomSttGateway, mainDispatcher = Dispatchers.Unconfined)
        var oomPropagado = false
        try {
            oomStt.startListening()
        } catch (_: OutOfMemoryError) {
            oomPropagado = true
        }
        assertTrue(oomPropagado)
    }

    private class FakeSttHandle : LucySttSessionHandle {
        var stopCalled = false
        var cancelCalled = false
        var destroyed = false

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

    private class FakeSttGateway(
        var permissionGranted: Boolean,
        var serviceAvailable: Boolean,
        var onDeviceAvailable: Boolean
    ) : LucySttGateway {
        var startCount = 0
        var lastCallbacks: LucySttGatewayCallbacks? = null
        var lastHandle: FakeSttHandle? = null

        override fun hasRecordAudioPermission(): Boolean = permissionGranted
        override fun isRecognitionAvailable(): Boolean = serviceAvailable
        override fun isOnDeviceRecognitionAvailable(): Boolean = onDeviceAvailable

        override fun startSession(
            preferOffline: Boolean,
            localeTag: String,
            callbacks: LucySttGatewayCallbacks
        ): LucySttSessionHandle {
            startCount++
            lastCallbacks = callbacks
            return FakeSttHandle().also { lastHandle = it }
        }
    }

    private class FakeTtsGateway(
        private val initSucceeds: Boolean = true,
        private val voices: List<LucyTtsVoiceInfo> = listOf(
            LucyTtsVoiceInfo("es-cl-default", "es-CL", isNetworkRequired = false, quality = 400)
        ),
        private val languageSupportResult: Int = TextToSpeech.LANG_COUNTRY_AVAILABLE,
        private val autoCompleteSpeak: Boolean = true
    ) : LucyTtsGateway {
        var callbacks: LucyTtsGatewayCallbacks? = null
        var lastSpeechRate: Float? = null
        var lastPitch: Float? = null
        var selectedVoice: String? = null
        var stopped = false
        var shutdownCalled = false
        val spokenTexts = mutableListOf<String>()

        override fun initialize(callbacks: LucyTtsGatewayCallbacks) {
            this.callbacks = callbacks
            callbacks.onInitialized(initSucceeds)
        }

        override fun availableVoices(): List<LucyTtsVoiceInfo> = voices

        override fun selectVoiceByName(voiceName: String): Boolean {
            if (voices.none { it.name == voiceName }) return false
            selectedVoice = voiceName
            return true
        }

        override fun setLanguage(locale: Locale): Int = languageSupportResult

        override fun setSpeechRate(rate: Float) {
            lastSpeechRate = rate
        }

        override fun setPitch(pitch: Float) {
            lastPitch = pitch
        }

        override fun speak(text: String, utteranceId: String, flushQueue: Boolean): Int {
            spokenTexts.add(text)
            callbacks?.onUtteranceStart(utteranceId)
            if (autoCompleteSpeak) {
                callbacks?.onUtteranceDone(utteranceId)
            }
            return TextToSpeech.SUCCESS
        }

        override fun stop() {
            stopped = true
        }

        override fun shutdown() {
            shutdownCalled = true
        }
    }
}
