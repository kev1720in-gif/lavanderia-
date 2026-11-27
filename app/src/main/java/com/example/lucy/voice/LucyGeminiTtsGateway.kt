package com.example.lucy.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LucyCloudTtsResult {
    data class Spoken(
        val voiceName: String,
        val modelName: String,
        val fromCache: Boolean = false,
        val firstAudioLatencyMs: Long = 0L,
        val totalLatencyMs: Long = 0L
    ) : LucyCloudTtsResult
    data object Cancelled : LucyCloudTtsResult
    data class FallbackRequired(val reason: String) : LucyCloudTtsResult
}

interface LucyCloudTtsGateway {
    val providerName: String
    suspend fun isAvailable(): Boolean
    suspend fun synthesizeAndPlay(
        text: String,
        voiceId: String,
        preferences: LucyPreferenciasConversacion,
        config: LucyVoicePersonalityConfig
    ): LucyCloudTtsResult
    fun stop()
    fun release()
}

object NoOpLucyCloudTtsGateway : LucyCloudTtsGateway {
    override val providerName: String = "Sin voz Gemini"
    override suspend fun isAvailable(): Boolean = false
    override suspend fun synthesizeAndPlay(
        text: String,
        voiceId: String,
        preferences: LucyPreferenciasConversacion,
        config: LucyVoicePersonalityConfig
    ): LucyCloudTtsResult = LucyCloudTtsResult.FallbackRequired("Gemini TTS no habilitado.")
    override fun stop() = Unit
    override fun release() = Unit
}

object LucyGeminiVoiceCatalog {
    const val GEMINI_TTS_MODEL_NAME = "gemini-3.1-flash-tts-preview"
    const val PREFIX_GEMINI = "gemini:"
    const val DEFAULT_VOICE_NAME = "Despina"

    val femaleVoices: List<LucyVoiceOption> = listOf(
        LucyVoiceOption(
            id = "${PREFIX_GEMINI}Despina",
            displayName = "Lucy · Natural y cercana (Despina)",
            description = "Voz femenina cálida y fluida en español (Gemini, recomendada)",
            engineType = LucyVoiceEngineType.GEMINI_TTS,
            isFemale = true,
            isCloud = true
        ),
        LucyVoiceOption(
            id = "${PREFIX_GEMINI}Sulafat",
            displayName = "Lucy · Amable y cordial (Sulafat)",
            description = "Voz femenina expresiva y cálida (Gemini)",
            engineType = LucyVoiceEngineType.GEMINI_TTS,
            isFemale = true,
            isCloud = true
        ),
        LucyVoiceOption(
            id = "${PREFIX_GEMINI}Aoede",
            displayName = "Lucy · Conversacional (Aoede)",
            description = "Voz femenina dinámica para diálogo natural (Gemini)",
            engineType = LucyVoiceEngineType.GEMINI_TTS,
            isFemale = true,
            isCloud = true
        ),
        LucyVoiceOption(
            id = "${PREFIX_GEMINI}Erinome",
            displayName = "Lucy · Nítida y clara (Erinome)",
            description = "Voz femenina muy clara para lectura de datos (Gemini)",
            engineType = LucyVoiceEngineType.GEMINI_TTS,
            isFemale = true,
            isCloud = true
        ),
        LucyVoiceOption(
            id = "${PREFIX_GEMINI}Vindemiatrix",
            displayName = "Lucy · Suave y pausada (Vindemiatrix)",
            description = "Voz femenina tranquila y gentil (Gemini)",
            engineType = LucyVoiceEngineType.GEMINI_TTS,
            isFemale = true,
            isCloud = true
        ),
        LucyVoiceOption(
            id = "${PREFIX_GEMINI}Kore",
            displayName = "Lucy · Firme y profesional (Kore)",
            description = "Voz femenina ejecutiva y directa (Gemini)",
            engineType = LucyVoiceEngineType.GEMINI_TTS,
            isFemale = true,
            isCloud = true
        ),
        LucyVoiceOption(
            id = "${PREFIX_GEMINI}Leda",
            displayName = "Lucy · Joven y ágil (Leda)",
            description = "Voz femenina ligera y rápida (Gemini)",
            engineType = LucyVoiceEngineType.GEMINI_TTS,
            isFemale = true,
            isCloud = true
        )
    )

    fun resolveGeminiVoiceName(voiceId: String): String {
        val raw = voiceId.removePrefix(PREFIX_GEMINI).trim()
        val match = femaleVoices.firstOrNull {
            it.id.removePrefix(PREFIX_GEMINI).equals(raw, ignoreCase = true)
        }
        return match?.id?.removePrefix(PREFIX_GEMINI) ?: DEFAULT_VOICE_NAME
    }
}

/**
 * Motor de voz natural de alta calidad usando Firebase AI Logic TTS (`gemini-3.1-flash-tts-preview`)
 * con reproducción streaming en memoria vía [AudioTrack], caché LRU de frases frecuentes
 * y fallback automático transparente a Android TextToSpeech si no hay red o falla.
 */
class FirebaseAiLucyGeminiTtsGateway(
    context: Context,
    private val modelName: String = LucyGeminiVoiceCatalog.GEMINI_TTS_MODEL_NAME,
    private val timeoutMs: Long = 35_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LucyCloudTtsGateway {

    private data class CachedPcmAudio(
        val pcmData: ByteArray,
        val sampleRate: Int
    )

    private val appContext = context.applicationContext
    private val modelsByVoice = ConcurrentHashMap<String, GenerativeModel>()
    private val stopRequested = AtomicBoolean(false)
    private val cacheLock = Any()
    private val audioCache = object : LinkedHashMap<String, CachedPcmAudio>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPcmAudio>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    @Volatile
    private var firebaseConfiguredCached: Boolean = false

    @Volatile
    private var isReleased: Boolean = false

    @Volatile
    private var activeTrack: AudioTrack? = null

    override val providerName: String = "Firebase AI Logic TTS ($modelName)"

    override suspend fun isAvailable(): Boolean = withContext(ioDispatcher) {
        !isReleased && isFirebaseConfigured() && isNetworkAvailable()
    }

    override suspend fun synthesizeAndPlay(
        text: String,
        voiceId: String,
        preferences: LucyPreferenciasConversacion,
        config: LucyVoicePersonalityConfig
    ): LucyCloudTtsResult = withContext(ioDispatcher) {
        if (isReleased) {
            return@withContext LucyCloudTtsResult.FallbackRequired("Módulo Gemini TTS liberado.")
        }

        val resolvedVoice = LucyGeminiVoiceCatalog.resolveGeminiVoiceName(voiceId)
        val cacheKey = buildCacheKey(resolvedVoice, preferences, text)
        val cached = synchronized(cacheLock) { audioCache[cacheKey] }

        stop()
        stopRequested.set(false)

        val startMs = System.currentTimeMillis()
        if (cached != null) {
            val played = playPcmAudio(pcmData = cached.pcmData, sampleRate = cached.sampleRate)
            val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            return@withContext when {
                stopRequested.get() || isReleased -> LucyCloudTtsResult.Cancelled
                played -> LucyCloudTtsResult.Spoken(
                    voiceName = resolvedVoice,
                    modelName = modelName,
                    fromCache = true,
                    firstAudioLatencyMs = 0L,
                    totalLatencyMs = totalMs
                )
                else -> LucyCloudTtsResult.FallbackRequired("No se pudo inicializar AudioTrack en el dispositivo.")
            }
        }

        if (!isFirebaseConfigured() || !isNetworkAvailable()) {
            return@withContext LucyCloudTtsResult.FallbackRequired("Sin red o sin configuración Firebase para voz Gemini.")
        }

        try {
            val model = getOrCreateModel(resolvedVoice)
            val ttsPrompt = buildSpeechPrompt(
                text = text,
                preferences = preferences,
                config = config
            )

            val streamOutcome = streamSynthesizeAndPlay(
                model = model,
                ttsPrompt = ttsPrompt,
                cacheKey = cacheKey,
                startMs = startMs
            )
            if (streamOutcome != null) {
                return@withContext when (streamOutcome) {
                    is LucyCloudTtsResult.Spoken -> streamOutcome.copy(voiceName = resolvedVoice)
                    else -> streamOutcome
                }
            }

            // Fallback interno a llamada unaria si el flujo streaming devolvió vacío o agotó tiempo antes de emitir audio
            val response = withTimeout(timeoutMs) {
                model.generateContent(ttsPrompt)
            }

            if (stopRequested.get() || isReleased) {
                return@withContext LucyCloudTtsResult.Cancelled
            }

            val audioPart = response.inlineDataParts.firstOrNull { part ->
                part.mimeType.startsWith("audio/", ignoreCase = true) && part.inlineData.isNotEmpty()
            } ?: return@withContext LucyCloudTtsResult.FallbackRequired(
                "Gemini TTS no devolvió pista de audio."
            )

            val firstAudioMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            val pcmData = stripWavHeaderIfPresent(audioPart.inlineData)
            if (pcmData.isEmpty()) {
                return@withContext LucyCloudTtsResult.FallbackRequired("Gemini TTS devolvió audio vacío.")
            }
            val sampleRate = parseSampleRate(audioPart.mimeType)
            cacheAudioIfSmall(cacheKey, pcmData, sampleRate)

            val played = playPcmAudio(pcmData = pcmData, sampleRate = sampleRate)
            val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            when {
                stopRequested.get() || isReleased -> LucyCloudTtsResult.Cancelled
                played -> LucyCloudTtsResult.Spoken(
                    voiceName = resolvedVoice,
                    modelName = modelName,
                    fromCache = false,
                    firstAudioLatencyMs = firstAudioMs,
                    totalLatencyMs = totalMs
                )
                else -> LucyCloudTtsResult.FallbackRequired("No se pudo inicializar AudioTrack en el dispositivo.")
            }
        } catch (te: TimeoutCancellationException) {
            Log.e(TAG, "Timeout en Gemini TTS ($modelName) tras ${timeoutMs}ms", te)
            LucyCloudTtsResult.FallbackRequired("Tiempo de espera agotado en voz Gemini (${timeoutMs / 1000}s).")
        } catch (ce: CancellationException) {
            stop()
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            Log.e(TAG, "Error técnico en voz Gemini ($modelName)", le)
            LucyCloudTtsResult.FallbackRequired("Error técnico en voz Gemini (${le::class.java.simpleName}).")
        } catch (e: Exception) {
            Log.e(TAG, "Fallo en Gemini TTS ($modelName), aplicando fallback local", e)
            LucyCloudTtsResult.FallbackRequired(formatGeminiErrorReason(e))
        }
    }

    private suspend fun streamSynthesizeAndPlay(
        model: GenerativeModel,
        ttsPrompt: String,
        cacheKey: String,
        startMs: Long
    ): LucyCloudTtsResult? {
        var track: AudioTrack? = null
        var resolvedSampleRate = 24_000
        var firstAudioLatencyMs = -1L
        val pcmBuffer = ByteArrayOutputStream()
        var trackFailed = false
        var totalFramesWritten = 0L
        val streamTimeoutMs = (timeoutMs * 2L / 3L).coerceAtLeast(15_000L)

        return try {
            withTimeout(streamTimeoutMs) {
                model.generateContentStream(ttsPrompt).collect { chunk ->
                    if (stopRequested.get() || isReleased) return@collect
                    for (part in chunk.inlineDataParts) {
                        if (!part.mimeType.startsWith("audio/", ignoreCase = true) || part.inlineData.isEmpty()) {
                            continue
                        }
                        val isFirstChunk = firstAudioLatencyMs < 0L
                        val pcmChunk = if (isFirstChunk) {
                            stripWavHeaderIfPresent(part.inlineData)
                        } else {
                            part.inlineData
                        }
                        if (pcmChunk.isEmpty()) continue

                        if (isFirstChunk) {
                            firstAudioLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                            resolvedSampleRate = parseSampleRate(part.mimeType)
                            track = createInitializedTrack(resolvedSampleRate)
                            if (track == null) {
                                trackFailed = true
                                break
                            }
                            activeTrack = track
                            runCatching { track?.play() }
                        }

                        if (pcmBuffer.size() + pcmChunk.size <= MAX_CACHED_AUDIO_BYTES) {
                            pcmBuffer.write(pcmChunk)
                        }
                        val currentTrack = track
                        if (currentTrack != null && !stopRequested.get() && !isReleased) {
                            val bytesWritten = writePcmChunk(currentTrack, pcmChunk)
                            if (bytesWritten < 0) {
                                trackFailed = true
                                break
                            }
                            totalFramesWritten += bytesWritten / 2L
                        }
                    }
                }
            }

            if (trackFailed) {
                return LucyCloudTtsResult.FallbackRequired("Error de reproducción en AudioTrack del dispositivo.")
            }
            if (stopRequested.get() || isReleased) {
                return LucyCloudTtsResult.Cancelled
            }
            if (firstAudioLatencyMs < 0L) {
                return null
            }

            val active = track
            if (active != null && totalFramesWritten > 0L) {
                waitForAudioDrain(active, totalFramesWritten, resolvedSampleRate)
            }

            val fullPcm = pcmBuffer.toByteArray()
            if (fullPcm.isNotEmpty()) {
                cacheAudioIfSmall(cacheKey, fullPcm, resolvedSampleRate)
            }
            val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            LucyCloudTtsResult.Spoken(
                voiceName = LucyGeminiVoiceCatalog.DEFAULT_VOICE_NAME,
                modelName = modelName,
                fromCache = false,
                firstAudioLatencyMs = firstAudioLatencyMs,
                totalLatencyMs = totalMs
            )
        } catch (te: TimeoutCancellationException) {
            if (firstAudioLatencyMs >= 0L) {
                throw te
            }
            Log.w(TAG, "Stream de Gemini TTS expiró antes de emitir audio (${streamTimeoutMs}ms), intentando llamada directa", te)
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (firstAudioLatencyMs >= 0L) {
                Log.w(TAG, "Stream de Gemini TTS interrumpido tras iniciar audio", e)
                LucyCloudTtsResult.FallbackRequired(formatGeminiErrorReason(e))
            } else {
                Log.d(TAG, "Streaming TTS no disponible (${e.message}), intentando llamada directa")
                null
            }
        } finally {
            val current = track
            if (current != null) {
                if (activeTrack === current) {
                    activeTrack = null
                }
                runCatching { current.stop() }
                runCatching { current.release() }
            }
        }
    }

    override fun stop() {
        stopRequested.set(true)
        val current = activeTrack
        activeTrack = null
        if (current != null) {
            runCatching { current.pause() }
            runCatching { current.flush() }
            runCatching { current.stop() }
            runCatching { current.release() }
        }
    }

    override fun release() {
        isReleased = true
        stop()
        modelsByVoice.clear()
        synchronized(cacheLock) {
            audioCache.clear()
        }
    }

    @OptIn(PublicPreviewAPI::class)
    private fun getOrCreateModel(voiceName: String): GenerativeModel {
        return modelsByVoice.getOrPut(voiceName) {
            Firebase.ai.generativeModel(
                modelName = modelName,
                generationConfig = generationConfig {
                    responseModalities = listOf(ResponseModality.AUDIO)
                    speechConfig = SpeechConfig(voice = Voice(voiceName))
                }
            )
        }
    }

    private fun buildCacheKey(
        resolvedVoice: String,
        preferences: LucyPreferenciasConversacion,
        text: String
    ): String = "$resolvedVoice|${preferences.estiloVoz.name}|${preferences.velocidad.name}|$text"

    private fun cacheAudioIfSmall(key: String, pcmData: ByteArray, sampleRate: Int) {
        if (pcmData.isEmpty() || pcmData.size > MAX_CACHED_AUDIO_BYTES) return
        synchronized(cacheLock) {
            audioCache[key] = CachedPcmAudio(pcmData = pcmData, sampleRate = sampleRate)
        }
    }

    private fun buildSpeechPrompt(
        text: String,
        preferences: LucyPreferenciasConversacion,
        config: LucyVoicePersonalityConfig
    ): String {
        val velocidadTxt = when (preferences.velocidad) {
            LucyVelocidadRespuesta.LENTA -> "ritmo pausado"
            LucyVelocidadRespuesta.NORMAL -> "ritmo natural"
            LucyVelocidadRespuesta.RAPIDA -> "ritmo ágil"
        }
        val acento = if (config.useNaturalChileanConversationalTone) {
            "español chileno natural"
        } else {
            "español latino neutro"
        }
        val estilo = preferences.estiloVoz.etiqueta.lowercase()
        return "Lee textualmente en $acento ($velocidadTxt, tono $estilo), sin añadir palabras:\n$text"
    }

    private fun createInitializedTrack(sampleRate: Int): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo crear AudioTrack", e)
            return null
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return null
        }
        return track
    }

    private fun writePcmChunk(track: AudioTrack, pcmData: ByteArray): Int {
        var offset = 0
        val chunkSize = 4096
        while (offset < pcmData.size && !stopRequested.get() && !isReleased) {
            val len = (pcmData.size - offset).coerceAtMost(chunkSize)
            val written = track.write(pcmData, offset, len)
            if (written < 0) return -1
            if (written == 0) break
            offset += written
        }
        return offset
    }

    private fun waitForAudioDrain(track: AudioTrack, totalFrames: Long, sampleRate: Int) {
        if (totalFrames <= 0L || sampleRate <= 0) return
        val maxWaitMs = ((totalFrames * 1000L) / sampleRate).coerceIn(250L, 2_500L)
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (!stopRequested.get() && !isReleased && System.currentTimeMillis() < deadline) {
            val head = runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(totalFrames)
            if (head >= totalFrames - 256L) break
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun formatGeminiErrorReason(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("403") || raw.contains("App Check", ignoreCase = true) || raw.contains("attestation", ignoreCase = true) ->
                "Error de autorización / App Check en Gemini TTS ($raw)."
            raw.contains("quota", ignoreCase = true) || raw.contains("429") ->
                "Cuota de Gemini TTS excedida temporalmente."
            raw.isNotBlank() -> raw
            else -> "Error remoto en voz Gemini (${error::class.java.simpleName})."
        }
    }

    private fun playPcmAudio(pcmData: ByteArray, sampleRate: Int): Boolean {
        if (pcmData.isEmpty()) return false
        val track = createInitializedTrack(sampleRate) ?: return false

        activeTrack = track
        return try {
            track.play()
            val written = writePcmChunk(track, pcmData)
            if (written < 0) {
                return false
            }
            val totalFrames = (written / 2L).coerceAtLeast(0L)
            if (!stopRequested.get() && !isReleased && totalFrames > 0L) {
                waitForAudioDrain(track, totalFrames, sampleRate)
                runCatching { track.stop() }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error durante reproducción PCM en AudioTrack", e)
            false
        } finally {
            if (activeTrack === track) {
                activeTrack = null
            }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun stripWavHeaderIfPresent(bytes: ByteArray): ByteArray {
        if (bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            return bytes.copyOfRange(44, bytes.size)
        }
        return bytes
    }

    private fun parseSampleRate(mimeType: String): Int {
        val match = Regex("rate=(\\d{4,6})", RegexOption.IGNORE_CASE).find(mimeType)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8_000, 48_000) ?: 24_000
    }

    private fun isFirebaseConfigured(): Boolean {
        if (firebaseConfiguredCached) return true
        return try {
            val ok = if (FirebaseApp.getApps(appContext).isNotEmpty()) true
            else FirebaseApp.initializeApp(appContext) != null
            if (ok) {
                firebaseConfiguredCached = true
            }
            ok
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: Exception) {
            false
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "GeminiTtsGateway"
        private const val MAX_CACHE_ENTRIES = 20
        private const val MAX_CACHED_AUDIO_BYTES = 1_500_000
    }
}
