package com.example.lucy.voice

import android.speech.tts.TextToSpeech
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.memory.LucyVelocidadRespuesta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/**
 * Motor de síntesis de voz (TTS) para Lucy.
 * - Selecciona español como idioma principal (priorizando es-CL y variantes latinoamericanas).
 * - Aplica velocidad, estilo e instrucciones de Lucy Memory.
 * - Limpia símbolos/códigos y evita leer vacíos o repetir el mismo mensaje consecutivamente.
 */
class LucyTtsEngine(
    private val gateway: LucyTtsGateway,
    private val config: LucyVoicePersonalityConfig = LucyVoicePersonalityConfig(),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val initTimeoutMs: Long = 5_000L
) {
    private val ttsMutex = Mutex()

    @Volatile
    private var isReleased: Boolean = false

    @Volatile
    private var currentPreferences: LucyPreferenciasConversacion = LucyPreferenciasConversacion()

    @Volatile
    private var lastSpokenNormalized: String? = null

    @Volatile
    private var lastSpokenAtMs: Long = 0L

    @Volatile
    private var selectedLocaleTag: String = "es-CL"

    @Volatile
    private var selectedVoiceName: String? = null

    @Volatile
    private var selectedVoiceIsLocal: Boolean = false

    @Volatile
    private var lastCompletedUtteranceId: String? = null

    @Volatile
    private var pendingVoiceReconfigure: Boolean = false

    @Volatile
    private var pendingTuningRefresh: Boolean = false

    private val _ttsState = MutableStateFlow<LucyTtsState>(LucyTtsState.Uninitialized)
    val ttsState: StateFlow<LucyTtsState> = _ttsState.asStateFlow()

    suspend fun initialize(): Boolean {
        if (isReleased) return false
        return ttsMutex.withLock {
            initializeLocked()
        }
    }

    suspend fun applyPreferences(preferencias: LucyPreferenciasConversacion) {
        val previaVoz = currentPreferences.vozPreferidaId
        currentPreferences = preferencias
        if (isReleased) return
        val nuevaVoz = preferencias.vozPreferidaId
        val cambioVozLocal = nuevaVoz != previaVoz && (nuevaVoz.startsWith("tts:") || previaVoz.startsWith("tts:"))
        if (cambioVozLocal) {
            pendingVoiceReconfigure = true
        }
        pendingTuningRefresh = true

        if (ttsMutex.tryLock()) {
            try {
                if (!isReleased && isReadyState(_ttsState.value)) {
                    if (pendingVoiceReconfigure) {
                        pendingVoiceReconfigure = false
                        configureSpanishVoiceLocked()
                    }
                    if (pendingTuningRefresh) {
                        pendingTuningRefresh = false
                        applyAudioTuningLocked()
                    }
                }
            } finally {
                ttsMutex.unlock()
            }
        }
    }

    fun availableSpanishVoiceOptions(): List<LucyVoiceOption> {
        val voices = runCatching { gateway.availableVoices() }.getOrDefault(emptyList())
        return LucySpanishVoiceSelector.listSpanishVoiceOptions(
            voices = voices,
            preferredLocaleTags = config.preferredLocaleTags
        )
    }

    suspend fun speak(
        rawText: String,
        forceRepeat: Boolean = false
    ): Boolean {
        if (isReleased) return false

        val formatted = LucyVoiceTextFormatter.prepararParaVoz(
            textoOriginal = rawText,
            preferencias = currentPreferences,
            config = config
        )
        if (formatted.isBlank()) return false

        return ttsMutex.withLock {
            if (isReleased) return@withLock false

            val now = clock()
            val isConsecutiveDuplicate = !forceRepeat &&
                lastSpokenNormalized == formatted &&
                (now - lastSpokenAtMs) in 0L..config.minDuplicateSuppressionWindowMs
            if (isConsecutiveDuplicate) {
                return@withLock false
            }

            if (!isReadyState(_ttsState.value)) {
                val ok = initializeLocked()
                if (!ok) return@withLock false
            }

            if (pendingVoiceReconfigure) {
                pendingVoiceReconfigure = false
                configureSpanishVoiceLocked()
            }
            pendingTuningRefresh = false
            applyAudioTuningLocked()

            val utteranceId = "lucy_${UUID.randomUUID()}"
            _ttsState.value = LucyTtsState.Speaking(
                utteranceId = utteranceId,
                selectedLocaleTag = selectedLocaleTag
            )
            val result = try {
                withContext(mainDispatcher) {
                    gateway.speak(formatted, utteranceId, flushQueue = true)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (vme: VirtualMachineError) {
                throw vme
            } catch (le: LinkageError) {
                _ttsState.value = LucyTtsState.Error(le.message ?: "Error nativo al hablar.")
                return@withLock false
            } catch (e: Exception) {
                _ttsState.value = LucyTtsState.Error(e.message ?: "Error al reproducir voz.")
                return@withLock false
            }

            if (result == TextToSpeech.SUCCESS) {
                lastSpokenNormalized = formatted
                lastSpokenAtMs = now
                if (lastCompletedUtteranceId == utteranceId) {
                    _ttsState.value = LucyTtsState.Ready(
                        selectedLocaleTag = selectedLocaleTag,
                        voiceName = selectedVoiceName,
                        isLocalVoice = selectedVoiceIsLocal
                    )
                }
                true
            } else {
                _ttsState.value = LucyTtsState.Error("El motor TTS devolvió un error al reproducir.")
                false
            }
        }
    }

    suspend fun stop() {
        if (isReleased) return
        ttsMutex.withLock {
            if (isReleased) return@withLock
            withContext(mainDispatcher) {
                runCatching { gateway.stop() }
            }
            if (_ttsState.value is LucyTtsState.Speaking) {
                _ttsState.value = LucyTtsState.Ready(
                    selectedLocaleTag = selectedLocaleTag,
                    voiceName = selectedVoiceName,
                    isLocalVoice = selectedVoiceIsLocal
                )
            }
        }
    }

    suspend fun release() {
        isReleased = true
        ttsMutex.withLock {
            lastSpokenNormalized = null
            withContext(mainDispatcher) {
                runCatching { gateway.stop() }
                runCatching { gateway.shutdown() }
            }
            _ttsState.value = LucyTtsState.Released
        }
    }

    private suspend fun initializeLocked(): Boolean {
        if (isReleased) return false
        if (isReadyState(_ttsState.value)) return true

        _ttsState.value = LucyTtsState.Initializing
        val initDeferred = CompletableDeferred<Boolean>()

        try {
            withContext(mainDispatcher) {
                gateway.initialize(object : LucyTtsGatewayCallbacks {
                    override fun onInitialized(success: Boolean) {
                        if (!initDeferred.isCompleted) {
                            initDeferred.complete(success)
                        }
                    }

                    override fun onUtteranceStart(utteranceId: String) {
                        if (!isReleased) {
                            _ttsState.value = LucyTtsState.Speaking(
                                utteranceId = utteranceId,
                                selectedLocaleTag = selectedLocaleTag
                            )
                        }
                    }

                    override fun onUtteranceDone(utteranceId: String) {
                        lastCompletedUtteranceId = utteranceId
                        if (!isReleased && _ttsState.value is LucyTtsState.Speaking) {
                            _ttsState.value = LucyTtsState.Ready(
                                selectedLocaleTag = selectedLocaleTag,
                                voiceName = selectedVoiceName,
                                isLocalVoice = selectedVoiceIsLocal
                            )
                        }
                    }

                    override fun onUtteranceError(utteranceId: String, reason: String) {
                        lastCompletedUtteranceId = utteranceId
                        if (!isReleased) {
                            _ttsState.value = LucyTtsState.Error(reason)
                        }
                    }
                })
            }

            val initialized = withTimeoutOrNull(initTimeoutMs) {
                initDeferred.await()
            } ?: false

            if (!initialized || isReleased) {
                if (!isReleased) {
                    _ttsState.value = LucyTtsState.Error("No se pudo inicializar la síntesis de voz en español.")
                }
                return false
            }

            pendingVoiceReconfigure = false
            pendingTuningRefresh = false
            val spanishConfigured = configureSpanishVoiceLocked()
            if (!spanishConfigured) {
                _ttsState.value = LucyTtsState.Error("No hay idioma o voz en español disponible en este dispositivo.")
                return false
            }

            applyAudioTuningLocked()
            _ttsState.value = LucyTtsState.Ready(
                selectedLocaleTag = selectedLocaleTag,
                voiceName = selectedVoiceName,
                isLocalVoice = selectedVoiceIsLocal
            )
            return true
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            _ttsState.value = LucyTtsState.Error("Error nativo en TTS: ${le.message ?: le::class.java.simpleName}")
            return false
        } catch (e: Exception) {
            _ttsState.value = LucyTtsState.Error(e.message ?: "Error al inicializar TTS.")
            return false
        }
    }

    private fun configureSpanishVoiceLocked(): Boolean {
        val available = runCatching { gateway.availableVoices() }.getOrDefault(emptyList())
        val requestedAndroidVoice = currentPreferences.vozPreferidaId
            .takeIf { it.startsWith("tts:") }
            ?.removePrefix("tts:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val bestVoice = LucySpanishVoiceSelector.pickBestSpanishVoice(
            voices = available,
            preferredLocaleTags = config.preferredLocaleTags,
            preferredVoiceName = requestedAndroidVoice
        )

        if (bestVoice != null && runCatching { gateway.selectVoiceByName(bestVoice.name) }.getOrDefault(false)) {
            selectedLocaleTag = bestVoice.localeTag
            selectedVoiceName = bestVoice.name
            selectedVoiceIsLocal = !bestVoice.isNetworkRequired
            return true
        }

        for (tag in config.preferredLocaleTags) {
            val locale = Locale.forLanguageTag(tag)
            val status = runCatching { gateway.setLanguage(locale) }.getOrDefault(TextToSpeech.ERROR)
            if (status != TextToSpeech.LANG_MISSING_DATA &&
                status != TextToSpeech.LANG_NOT_SUPPORTED &&
                status >= 0
            ) {
                selectedLocaleTag = tag
                selectedVoiceName = null
                selectedVoiceIsLocal = false
                return true
            }
        }
        return false
    }

    private suspend fun applyAudioTuningLocked() {
        val baseRate = config.speechRateFor(currentPreferences.velocidad)
        val styleRateFactor = when (currentPreferences.estiloVoz) {
            LucyEstiloVoz.PAUSADA_SUAVE -> 0.92f
            LucyEstiloVoz.BREVE_DIRECTA -> 1.06f
            LucyEstiloVoz.CLARA_PROFESIONAL,
            LucyEstiloVoz.NATURAL_CERCANA -> 1.0f
        }
        val rate = (baseRate * styleRateFactor).coerceIn(config.minRate, config.maxRate)
        val stylePitchOffset = when (currentPreferences.estiloVoz) {
            LucyEstiloVoz.PAUSADA_SUAVE -> -0.03f
            LucyEstiloVoz.NATURAL_CERCANA -> 0.02f
            LucyEstiloVoz.CLARA_PROFESIONAL,
            LucyEstiloVoz.BREVE_DIRECTA -> 0.0f
        }
        val pitch = (config.basePitch + stylePitchOffset).coerceIn(config.minPitch, config.maxPitch)
        withContext(mainDispatcher) {
            runCatching { gateway.setSpeechRate(rate) }
            runCatching { gateway.setPitch(pitch) }
        }
    }

    private fun isReadyState(state: LucyTtsState): Boolean =
        state is LucyTtsState.Ready || state is LucyTtsState.Speaking
}
