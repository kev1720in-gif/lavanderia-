package com.example.lucy.voice

import android.content.Context
import com.example.lucy.core.LucyMemoryPort
import com.example.lucy.core.LucyModuleHealth
import com.example.lucy.core.LucyVoicePort
import com.example.lucy.core.NoOpLucyMemoryPort
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.memory.LucyVelocidadRespuesta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Puerto unificado de LUCY VOICE que implementa [LucyVoicePort] conectando:
 * - [LucySttController] (Android SpeechRecognizer)
 * - [LucyCloudTtsGateway] (Gemini TTS natural femenino cuando hay red/configuración)
 * - [LucyTtsEngine] (Android TextToSpeech en español chileno como respaldo automático)
 * - [LucyMemoryPort] (velocidad, estilo, voz elegida e instrucciones permanentes)
 *
 * Nunca activa el micrófono automáticamente ni graba audio permanente.
 */
class AndroidLucyVoicePort(
    private val sttController: LucySttController,
    private val ttsEngine: LucyTtsEngine,
    private val memoryPort: LucyMemoryPort = NoOpLucyMemoryPort,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialVoiceOutputEnabled: Boolean = false,
    private val cloudTtsGateway: LucyCloudTtsGateway = NoOpLucyCloudTtsGateway,
    private val config: LucyVoicePersonalityConfig = LucyVoicePersonalityConfig()
) : LucyVoicePort {

    private val portScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val speakMutex = Mutex()
    private val prefsMutex = Mutex()

    private val _voiceOutputEnabled = MutableStateFlow(initialVoiceOutputEnabled)
    private val _appliedPreferences = MutableStateFlow(LucyPreferenciasConversacion())
    private val _cloudSpeakingState = MutableStateFlow<LucyTtsState?>(null)
    private val _activeEngineLabel = MutableStateFlow("Automática (Gemini / Dispositivo)")
    private val _fallbackReason = MutableStateFlow<String?>(null)
    private val _isFallbackActive = MutableStateFlow(false)
    private val _lastAudioLatencyMs = MutableStateFlow<Long?>(null)
    private val _lastPlaybackReport = MutableStateFlow<LucyVoicePlaybackReport?>(null)
    val lastPlaybackReport: StateFlow<LucyVoicePlaybackReport?> = _lastPlaybackReport

    @Volatile
    private var isReleased: Boolean = false

    @Volatile
    private var preferencesLoadedOnce: Boolean = false

    @Volatile
    private var inFallbackMode: Boolean = false

    @Volatile
    private var localPreferenceVersion: Long = 0L

    private var pendingPreferenceWrites: Int = 0

    @Volatile
    private var lastCloudSpokenKey: String? = null

    @Volatile
    private var lastCloudSpokenAtMs: Long = 0L

    private val baseStatusFlow = combine(
        sttController.permissionState,
        sttController.availability,
        sttController.sttState,
        ttsEngine.ttsState,
        _cloudSpeakingState
    ) { perm, avail, stt, localTts, cloudTts ->
        BaseVoiceTuple(
            perm = perm,
            avail = avail,
            stt = stt,
            tts = cloudTts ?: localTts
        )
    }

    private val fallbackAndLatencyFlow = combine(
        _isFallbackActive,
        _lastAudioLatencyMs
    ) { isFallbackActive, lastLatencyMs ->
        isFallbackActive to lastLatencyMs
    }

    private val voiceMetaFlow = combine(
        _appliedPreferences,
        _voiceOutputEnabled,
        _activeEngineLabel,
        _fallbackReason,
        fallbackAndLatencyFlow
    ) { prefs, enabled, engineLabel, fallbackReason, fallbackAndLatency ->
        VoiceMetaTuple(
            prefs = prefs,
            enabled = enabled,
            engineLabel = engineLabel,
            fallbackReason = fallbackReason,
            lastAudioLatencyMs = fallbackAndLatency.second,
            isFallbackActive = fallbackAndLatency.first
        )
    }

    val voiceStatus: StateFlow<LucyVoiceUiStatus> = combine(
        baseStatusFlow,
        voiceMetaFlow
    ) { base, meta ->
        LucyVoiceUiStatus(
            permissionState = base.perm,
            sttAvailability = base.avail,
            sttState = base.stt,
            ttsState = base.tts,
            appliedSpeed = meta.prefs.velocidad,
            appliedStyle = meta.prefs.estilo,
            voiceOutputEnabled = meta.enabled,
            availableVoices = availableVoiceOptions(),
            selectedVoiceId = meta.prefs.vozPreferidaId,
            appliedVoiceStyle = meta.prefs.estiloVoz,
            activeVoiceEngineLabel = meta.engineLabel,
            fallbackReason = meta.fallbackReason,
            lastAudioLatencyMs = meta.lastAudioLatencyMs,
            isFallbackActive = meta.isFallbackActive
        )
    }.stateIn(
        scope = portScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LucyVoiceUiStatus(
            voiceOutputEnabled = initialVoiceOutputEnabled,
            availableVoices = availableVoiceOptions()
        )
    )

    override suspend fun healthCheck(): LucyModuleHealth {
        if (isReleased) {
            return LucyModuleHealth.Unavailable("Módulo Lucy Voice liberado.")
        }

        val sttAvail = sttController.refreshStatus()
        syncPreferencesFromMemory()

        val ttsOk = ttsEngine.initialize()
        val cloudReady = runCatching { cloudTtsGateway.isAvailable() }.getOrDefault(false)
        if (!inFallbackMode) {
            _activeEngineLabel.value = resolverEtiquetaMotor(
                preferidaId = _appliedPreferences.value.vozPreferidaId,
                cloudReady = cloudReady
            )
            if (cloudReady || _appliedPreferences.value.vozPreferidaId.startsWith("tts:")) {
                _fallbackReason.value = null
                _isFallbackActive.value = false
            }
        }

        val ttsState = ttsEngine.ttsState.value
        val salidaDisponible = ttsOk || cloudReady

        return when {
            !salidaDisponible && !sttAvail.isServiceAvailable -> {
                val motivo = (ttsState as? LucyTtsState.Error)?.reason
                    ?: "Ni TTS en español ni reconocimiento de voz están disponibles."
                LucyModuleHealth.Unavailable(motivo)
            }
            !salidaDisponible -> {
                val motivo = (ttsState as? LucyTtsState.Error)?.reason ?: "TTS en español no disponible."
                LucyModuleHealth.Degraded(motivo)
            }
            !sttAvail.isServiceAvailable -> {
                LucyModuleHealth.Degraded("Voz de salida lista; reconocimiento de voz no disponible en el dispositivo.")
            }
            sttController.permissionState.value == LucyMicrophonePermissionState.DENIED -> {
                LucyModuleHealth.Degraded("Voz de salida lista; falta permiso de micrófono para dictado.")
            }
            else -> LucyModuleHealth.Ready
        }
    }

    override suspend fun speakIfEnabled(text: String) {
        if (isReleased || !_voiceOutputEnabled.value) return
        ensurePreferencesLoadedOnce()
        speakWithFallback(text = text, forceRepeat = false)
    }

    suspend fun speakExplicitly(text: String, forceRepeat: Boolean = false): Boolean {
        if (isReleased) return false
        ensurePreferencesLoadedOnce()
        return speakWithFallback(text = text, forceRepeat = forceRepeat)
    }

    private suspend fun ensurePreferencesLoadedOnce() {
        if (!preferencesLoadedOnce) {
            syncPreferencesFromMemory()
        }
    }

    private suspend fun speakWithFallback(text: String, forceRepeat: Boolean): Boolean {
        val prefs = _appliedPreferences.value
        val formatted = LucyVoiceTextFormatter.prepararParaVoz(
            textoOriginal = text,
            preferencias = prefs,
            config = config
        )
        if (formatted.isBlank()) return false

        // Interrumpir inmediatamente audio anterior en reproducción antes de adquirir el lock
        if (_cloudSpeakingState.value != null) {
            cloudTtsGateway.stop()
            _cloudSpeakingState.value = null
        }

        return speakMutex.withLock {
            if (isReleased) return@withLock false
            val preferLocalOnly = prefs.vozPreferidaId.startsWith("tts:")
            val requestedCloudVoice = LucyGeminiVoiceCatalog.resolveGeminiVoiceName(prefs.vozPreferidaId)
            val cloudDisponible = !preferLocalOnly && runCatching { cloudTtsGateway.isAvailable() }.getOrDefault(false)
            val startSpeakMs = System.currentTimeMillis()

            if (cloudDisponible) {
                val now = System.currentTimeMillis()
                val dedupKey = "${prefs.vozPreferidaId}|${prefs.estiloVoz.name}|${prefs.velocidad.name}|$formatted"
                val isDup = !forceRepeat &&
                    lastCloudSpokenKey == dedupKey &&
                    (now - lastCloudSpokenAtMs) in 0L..config.minDuplicateSuppressionWindowMs
                if (isDup) return@withLock false

                ttsEngine.stop()
                val uttId = "lucy_gemini_${UUID.randomUUID()}"
                _cloudSpeakingState.value = LucyTtsState.Speaking(
                    utteranceId = uttId,
                    selectedLocaleTag = "es-CL"
                )
                val outcome = try {
                    cloudTtsGateway.synthesizeAndPlay(
                        text = formatted,
                        voiceId = prefs.vozPreferidaId,
                        preferences = prefs,
                        config = config
                    )
                } finally {
                    _cloudSpeakingState.value = null
                }

                when (outcome) {
                    is LucyCloudTtsResult.Spoken -> {
                        lastCloudSpokenKey = dedupKey
                        lastCloudSpokenAtMs = now
                        inFallbackMode = false
                        _isFallbackActive.value = false
                        _fallbackReason.value = null
                        val engineLabel = "Gemini (${outcome.voiceName})"
                        _activeEngineLabel.value = engineLabel
                        val totalMs = outcome.totalLatencyMs.takeIf { it > 0L }
                            ?: (System.currentTimeMillis() - startSpeakMs).coerceAtLeast(0L)
                        _lastAudioLatencyMs.value = totalMs
                        _lastPlaybackReport.value = LucyVoicePlaybackReport(
                            spoken = true,
                            engineLabel = engineLabel,
                            fallbackReason = null,
                            fromCache = outcome.fromCache,
                            audioStartLatencyMs = outcome.firstAudioLatencyMs,
                            audioTotalLatencyMs = totalMs
                        )
                        return@withLock true
                    }
                    is LucyCloudTtsResult.Cancelled -> return@withLock true
                    is LucyCloudTtsResult.FallbackRequired -> {
                        inFallbackMode = true
                        _isFallbackActive.value = true
                        _fallbackReason.value = outcome.reason
                        _activeEngineLabel.value = "Dispositivo Android (respaldo de $requestedCloudVoice)"
                    }
                }
            } else if (preferLocalOnly) {
                inFallbackMode = false
                _isFallbackActive.value = false
                _fallbackReason.value = null
                _activeEngineLabel.value = "Dispositivo (voz seleccionada)"
            } else {
                inFallbackMode = true
                _isFallbackActive.value = true
                _fallbackReason.value = "Sin conexión o Firebase no disponible; usando voz del dispositivo."
                _activeEngineLabel.value = "Dispositivo Android (sin red · respaldo de $requestedCloudVoice)"
            }

            val localSpoken = ttsEngine.speak(text, forceRepeat = forceRepeat)
            val totalLocalMs = (System.currentTimeMillis() - startSpeakMs).coerceAtLeast(0L)
            _lastAudioLatencyMs.value = totalLocalMs
            _lastPlaybackReport.value = LucyVoicePlaybackReport(
                spoken = localSpoken,
                engineLabel = _activeEngineLabel.value,
                fallbackReason = _fallbackReason.value,
                fromCache = false,
                audioStartLatencyMs = totalLocalMs,
                audioTotalLatencyMs = totalLocalMs
            )
            localSpoken
        }
    }

    override suspend fun applyPreferences(preferencias: LucyPreferenciasConversacion) {
        if (isReleased) return
        prefsMutex.withLock {
            localPreferenceVersion++
            applyPreferencesLocked(preferencias)
        }
    }

    private suspend fun applyPreferencesLocked(preferencias: LucyPreferenciasConversacion) {
        if (isReleased) return
        val previaVoz = _appliedPreferences.value.vozPreferidaId
        _appliedPreferences.value = preferencias
        preferencesLoadedOnce = true
        if (preferencias.vozPreferidaId != previaVoz) {
            inFallbackMode = false
            _isFallbackActive.value = false
            _fallbackReason.value = null
        }
        val preferLocalOnly = preferencias.vozPreferidaId.startsWith("tts:")
        if (!inFallbackMode) {
            val cloudReady = if (preferLocalOnly) false else runCatching { cloudTtsGateway.isAvailable() }.getOrDefault(false)
            _activeEngineLabel.value = resolverEtiquetaMotor(preferencias.vozPreferidaId, cloudReady)
        }
        ttsEngine.applyPreferences(preferencias)
    }

    override suspend fun stopSpeaking() {
        cloudTtsGateway.stop()
        _cloudSpeakingState.value = null
        ttsEngine.stop()
    }

    override fun availableVoiceOptions(): List<LucyVoiceOption> {
        val auto = LucyVoiceOption(
            id = "",
            displayName = "Automática (Gemini femenina / dispositivo)",
            description = "Usa Gemini TTS natural cuando hay conexión y el motor del teléfono offline",
            engineType = LucyVoiceEngineType.AUTO,
            isFemale = true,
            isCloud = false
        )
        val geminiVoices = if (cloudTtsGateway !== NoOpLucyCloudTtsGateway) {
            LucyGeminiVoiceCatalog.femaleVoices
        } else {
            emptyList()
        }
        val localVoices = ttsEngine.availableSpanishVoiceOptions()
        return buildList {
            add(auto)
            addAll(geminiVoices)
            addAll(localVoices)
        }
    }

    override suspend fun selectPreferredVoice(voiceId: String) {
        if (isReleased) return
        val limpio = voiceId.trim()
        prefsMutex.withLock {
            pendingPreferenceWrites++
            localPreferenceVersion++
            inFallbackMode = false
            _isFallbackActive.value = false
            _fallbackReason.value = null
            val updated = _appliedPreferences.value.copy(vozPreferidaId = limpio)
            // Aplicar de inmediato en memoria para que UI y 'Probar voz' usen la nueva voz sin esperar
            applyPreferencesLocked(updated)
        }
        try {
            runCatching { memoryPort.actualizarVozPreferida(limpio) }
        } finally {
            prefsMutex.withLock {
                pendingPreferenceWrites = (pendingPreferenceWrites - 1).coerceAtLeast(0)
                localPreferenceVersion++
            }
        }
    }

    override suspend fun selectVoiceStyle(style: LucyEstiloVoz) {
        if (isReleased) return
        prefsMutex.withLock {
            pendingPreferenceWrites++
            localPreferenceVersion++
            val updated = _appliedPreferences.value.copy(estiloVoz = style)
            applyPreferencesLocked(updated)
        }
        try {
            runCatching { memoryPort.actualizarEstiloVoz(style) }
        } finally {
            prefsMutex.withLock {
                pendingPreferenceWrites = (pendingPreferenceWrites - 1).coerceAtLeast(0)
                localPreferenceVersion++
            }
        }
    }

    override suspend fun selectSpeechSpeed(speed: LucyVelocidadRespuesta) {
        if (isReleased) return
        prefsMutex.withLock {
            pendingPreferenceWrites++
            localPreferenceVersion++
            val updated = _appliedPreferences.value.copy(velocidad = speed)
            applyPreferencesLocked(updated)
        }
        try {
            runCatching { memoryPort.actualizarVelocidadRespuesta(speed) }
        } finally {
            prefsMutex.withLock {
                pendingPreferenceWrites = (pendingPreferenceWrites - 1).coerceAtLeast(0)
                localPreferenceVersion++
            }
        }
    }

    fun setVoiceOutputEnabled(enabled: Boolean) {
        _voiceOutputEnabled.value = enabled
    }

    fun isVoiceOutputEnabled(): Boolean = _voiceOutputEnabled.value

    fun refreshAvailability(): LucySttAvailability = sttController.refreshStatus()

    suspend fun startUserInitiatedListening(
        preferOffline: Boolean = false,
        onPartialTranscript: (String) -> Unit = {},
        onFinalTranscript: suspend (String) -> Unit = {}
    ): LucySttStartResult {
        if (isReleased) {
            return LucySttStartResult.Failed("Módulo de voz liberado.")
        }
        // Evitar que TTS y micrófono se acoplen acústicamente al iniciar escucha explícita
        stopSpeaking()
        return sttController.startListening(
            preferOffline = preferOffline,
            onPartialTranscript = onPartialTranscript,
            onFinalTranscript = onFinalTranscript
        )
    }

    suspend fun stopListening() = sttController.stopListening()

    suspend fun cancelListening() = sttController.cancelListening()

    suspend fun syncPreferencesFromMemory() {
        if (isReleased) return
        val startVersion = prefsMutex.withLock {
            if (isReleased || pendingPreferenceWrites > 0) return
            localPreferenceVersion
        }
        val prefs = try {
            memoryPort.obtenerPreferencias()
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: LinkageError) {
            return
        } catch (_: Exception) {
            return
        }
        prefsMutex.withLock {
            if (!isReleased && pendingPreferenceWrites == 0 && localPreferenceVersion == startVersion) {
                applyPreferencesLocked(prefs)
            }
        }
    }

    suspend fun release() {
        if (isReleased) return
        isReleased = true
        cloudTtsGateway.release()
        sttController.release()
        ttsEngine.release()
        portScope.cancel()
    }

    private fun resolverEtiquetaMotor(preferidaId: String, cloudReady: Boolean): String = when {
        preferidaId.startsWith("tts:") -> "Dispositivo (voz seleccionada)"
        preferidaId.startsWith(LucyGeminiVoiceCatalog.PREFIX_GEMINI) -> {
            val nombre = LucyGeminiVoiceCatalog.resolveGeminiVoiceName(preferidaId)
            if (cloudReady) "Gemini ($nombre)" else "Dispositivo (respaldo de $nombre)"
        }
        cloudReady -> "Gemini (${LucyGeminiVoiceCatalog.DEFAULT_VOICE_NAME})"
        else -> "Dispositivo (offline)"
    }

    private data class BaseVoiceTuple(
        val perm: LucyMicrophonePermissionState,
        val avail: LucySttAvailability,
        val stt: LucySttState,
        val tts: LucyTtsState
    )

    private data class VoiceMetaTuple(
        val prefs: LucyPreferenciasConversacion,
        val enabled: Boolean,
        val engineLabel: String,
        val fallbackReason: String?,
        val lastAudioLatencyMs: Long?,
        val isFallbackActive: Boolean
    )

    data class LucyVoicePlaybackReport(
        val spoken: Boolean,
        val engineLabel: String,
        val fallbackReason: String?,
        val fromCache: Boolean,
        val audioStartLatencyMs: Long,
        val audioTotalLatencyMs: Long
    )

    companion object {
        fun createForApp(
            context: Context,
            memoryPort: LucyMemoryPort = NoOpLucyMemoryPort,
            config: LucyVoicePersonalityConfig = LucyVoicePersonalityConfig(),
            initialVoiceOutputEnabled: Boolean = false
        ): AndroidLucyVoicePort {
            val stt = LucySttController(
                gateway = AndroidSpeechRecognizerGateway(context),
                config = config
            )
            val tts = LucyTtsEngine(
                gateway = AndroidTextToSpeechGateway(context),
                config = config
            )
            val cloudTts = FirebaseAiLucyGeminiTtsGateway(context = context.applicationContext)
            return AndroidLucyVoicePort(
                sttController = stt,
                ttsEngine = tts,
                memoryPort = memoryPort,
                initialVoiceOutputEnabled = initialVoiceOutputEnabled,
                cloudTtsGateway = cloudTts,
                config = config
            )
        }
    }
}

