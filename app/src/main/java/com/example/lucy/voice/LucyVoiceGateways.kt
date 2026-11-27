package com.example.lucy.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import java.util.Locale

interface LucySttSessionHandle {
    fun stopListening()
    fun cancel()
    fun destroy()
}

interface LucySttGatewayCallbacks {
    fun onReadyForSpeech()
    fun onBeginningOfSpeech()
    fun onPartialResult(text: String)
    fun onFinalResult(text: String)
    fun onError(errorCode: Int, message: String)
}

interface LucySttGateway {
    fun hasRecordAudioPermission(): Boolean
    fun isRecognitionAvailable(): Boolean
    fun isOnDeviceRecognitionAvailable(): Boolean
    fun startSession(
        preferOffline: Boolean,
        localeTag: String,
        callbacks: LucySttGatewayCallbacks
    ): LucySttSessionHandle
}

data class LucyTtsVoiceInfo(
    val name: String,
    val localeTag: String,
    val isNetworkRequired: Boolean,
    val quality: Int
)

interface LucyTtsGatewayCallbacks {
    fun onInitialized(success: Boolean)
    fun onUtteranceStart(utteranceId: String)
    fun onUtteranceDone(utteranceId: String)
    fun onUtteranceError(utteranceId: String, reason: String)
}

interface LucyTtsGateway {
    fun initialize(callbacks: LucyTtsGatewayCallbacks)
    fun availableVoices(): List<LucyTtsVoiceInfo>
    fun selectVoiceByName(voiceName: String): Boolean
    fun setLanguage(locale: Locale): Int
    fun setSpeechRate(rate: Float)
    fun setPitch(pitch: Float)
    fun speak(text: String, utteranceId: String, flushQueue: Boolean): Int
    fun stop()
    fun shutdown()
}

object LucySpanishVoiceSelector {

    private val FEMALE_NAME_HINTS = setOf(
        "camila", "paulina", "monica", "elena", "paloma", "dalia", "valentina",
        "lucia", "sofia", "laura", "carmen", "catalina", "valeria", "javiera",
        "constanza", "ana", "maria", "salome", "ximena", "sabina", "sfb", "female", "f00"
    )

    private val MALE_NAME_HINTS = setOf(
        "jorge", "diego", "pablo", "carlos", "tpd", "male", "m00", "alvaro", "tomas", "andres"
    )

    @JvmOverloads
    fun pickBestSpanishVoice(
        voices: List<LucyTtsVoiceInfo>,
        preferredLocaleTags: List<String> = listOf("es-CL", "es-419", "es-US", "es-AR", "es-ES", "es"),
        preferredVoiceName: String? = null
    ): LucyTtsVoiceInfo? {
        if (voices.isEmpty()) return null
        val spanishVoices = voices.filter { voice ->
            val normTag = normalizarTag(voice.localeTag)
            normTag == "es" || normTag.startsWith("es-")
        }
        if (spanishVoices.isEmpty()) return null

        if (!preferredVoiceName.isNullOrBlank()) {
            spanishVoices.firstOrNull { it.name == preferredVoiceName }?.let { return it }
        }

        val preferredOrder = preferredLocaleTags.map { normalizarTag(it) }
        return spanishVoices.minWithOrNull(
            compareBy<LucyTtsVoiceInfo>(
                { voice -> localePriority(voice.localeTag, preferredOrder) },
                { voice -> femaleScore(voice.name) },
                { voice -> if (voice.isNetworkRequired) 1 else 0 },
                { voice -> -voice.quality }
            )
        )
    }

    fun listSpanishVoiceOptions(
        voices: List<LucyTtsVoiceInfo>,
        preferredLocaleTags: List<String> = listOf("es-CL", "es-419", "es-US", "es-AR", "es-ES", "es")
    ): List<LucyVoiceOption> {
        val preferredOrder = preferredLocaleTags.map { normalizarTag(it) }
        return voices
            .filter { voice ->
                val normTag = normalizarTag(voice.localeTag)
                normTag == "es" || normTag.startsWith("es-")
            }
            .sortedWith(
                compareBy<LucyTtsVoiceInfo>(
                    { femaleScore(it.name) },
                    { localePriority(it.localeTag, preferredOrder) },
                    { if (it.isNetworkRequired) 1 else 0 },
                    { -it.quality }
                )
            )
            .distinctBy { it.name }
            .take(12)
            .map { voice ->
                val isFemale = !isLikelyMaleSpanishVoice(voice.name)
                val tipoRed = if (voice.isNetworkRequired) "red" else "sin conexión"
                LucyVoiceOption(
                    id = "tts:${voice.name}",
                    displayName = formatearNombreVozAndroid(voice),
                    description = "Voz del dispositivo (${voice.localeTag}, $tipoRed)",
                    engineType = LucyVoiceEngineType.ANDROID_TTS,
                    isFemale = isFemale,
                    isCloud = voice.isNetworkRequired
                )
            }
    }

    fun isLikelyFemaleSpanishVoice(voiceName: String): Boolean {
        val norm = voiceName.lowercase(Locale.ROOT)
        return FEMALE_NAME_HINTS.any { norm.contains(it) }
    }

    fun isLikelyMaleSpanishVoice(voiceName: String): Boolean {
        val norm = voiceName.lowercase(Locale.ROOT)
        return MALE_NAME_HINTS.any { norm.contains(it) }
    }

    private fun femaleScore(voiceName: String): Int = when {
        isLikelyFemaleSpanishVoice(voiceName) -> 0
        isLikelyMaleSpanishVoice(voiceName) -> 2
        else -> 1
    }

    private fun localePriority(localeTag: String, preferredOrder: List<String>): Int {
        val tag = normalizarTag(localeTag)
        val exactIdx = preferredOrder.indexOf(tag)
        if (exactIdx >= 0) return exactIdx
        val langIdx = preferredOrder.indexOfFirst { tag.startsWith("$it-") || it == tag }
        return if (langIdx >= 0) langIdx + 10 else 100
    }

    private fun formatearNombreVozAndroid(voice: LucyTtsVoiceInfo): String {
        val region = when (normalizarTag(voice.localeTag)) {
            "es-cl" -> "Chile"
            "es-419" -> "Latinoamérica"
            "es-us" -> "Español EE.UU."
            "es-ar" -> "Argentina"
            "es-mx" -> "México"
            "es-es" -> "España"
            else -> voice.localeTag.uppercase(Locale.ROOT)
        }
        val cleanToken = voice.name
            .substringAfter("-x-")
            .substringBefore("-local")
            .substringBefore("-network")
            .ifBlank { voice.name }
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
        return "Dispositivo · $region ($cleanToken)"
    }

    private fun normalizarTag(raw: String): String =
        raw.trim().replace('_', '-').lowercase(Locale.ROOT)
}

class AndroidSpeechRecognizerGateway(
    private val context: Context
) : LucySttGateway {

    private val appContext = context.applicationContext

    override fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(appContext)
    }

    @SuppressLint("NewApi")
    override fun isOnDeviceRecognitionAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)
        } else {
            false
        }
    }

    @SuppressLint("NewApi")
    override fun startSession(
        preferOffline: Boolean,
        localeTag: String,
        callbacks: LucySttGatewayCallbacks
    ): LucySttSessionHandle {
        val recognizer = if (preferOffline &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            isOnDeviceRecognitionAvailable()
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                callbacks.onReadyForSpeech()
            }

            override fun onBeginningOfSpeech() {
                callbacks.onBeginningOfSpeech()
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                callbacks.onError(error, mapSpeechRecognizerError(error))
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val text = matches.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (text.isBlank()) {
                    callbacks.onError(SpeechRecognizer.ERROR_NO_MATCH, "No logré entender lo que dijiste.")
                } else {
                    callbacks.onFinalResult(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val partial = matches.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (partial.isNotBlank()) {
                    callbacks.onPartialResult(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        // Solo audio efímero para transcripción; jamás grabar ni escribir archivos de audio en disco.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
        }

        recognizer.startListening(intent)

        return object : LucySttSessionHandle {
            override fun stopListening() {
                runCatching { recognizer.stopListening() }
            }

            override fun cancel() {
                runCatching { recognizer.cancel() }
            }

            override fun destroy() {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
        }
    }

    companion object {
        fun mapSpeechRecognizerError(errorCode: Int): String = when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "No se pudo capturar audio del micrófono."
            SpeechRecognizer.ERROR_CLIENT -> "La escucha fue cancelada o interrumpida."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta permiso de micrófono para escuchar."
            SpeechRecognizer.ERROR_NETWORK -> "El servicio de voz requiere conexión a Internet."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera agotado al conectar con el servicio de voz."
            SpeechRecognizer.ERROR_NO_MATCH -> "No logré distinguir palabras claras."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El micrófono ya está ocupado en otra sesión."
            SpeechRecognizer.ERROR_SERVER -> "El servicio de reconocimiento devolvió un error temporal."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No detecté voz a tiempo."
            12, 13 -> "El idioma o modelo offline no está disponible en este dispositivo."
            else -> "Error de reconocimiento de voz (código $errorCode)."
        }
    }
}

class AndroidTextToSpeechGateway(
    private val context: Context
) : LucyTtsGateway {

    private val appContext = context.applicationContext

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var activeCallbacks: LucyTtsGatewayCallbacks? = null

    override fun initialize(callbacks: LucyTtsGatewayCallbacks) {
        activeCallbacks = callbacks
        shutdownInternal()

        val instance = TextToSpeech(appContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                runCatching {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        callbacks.onUtteranceStart(utteranceId.orEmpty())
                    }

                    override fun onDone(utteranceId: String?) {
                        callbacks.onUtteranceDone(utteranceId.orEmpty())
                    }

                    @Deprecated("Deprecated in API 21")
                    override fun onError(utteranceId: String?) {
                        callbacks.onUtteranceError(utteranceId.orEmpty(), "Error de síntesis de voz.")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        callbacks.onUtteranceError(
                            utteranceId.orEmpty(),
                            "Error de síntesis de voz (código $errorCode)."
                        )
                    }
                })
            }
            callbacks.onInitialized(ok)
        }
        tts = instance
    }

    override fun availableVoices(): List<LucyTtsVoiceInfo> {
        val current = tts ?: return emptyList()
        val rawVoices: Set<Voice> = runCatching { current.voices }.getOrNull().orEmpty()
        return rawVoices.map { voice ->
            LucyTtsVoiceInfo(
                name = voice.name,
                localeTag = voice.locale?.toLanguageTag().orEmpty(),
                isNetworkRequired = voice.isNetworkConnectionRequired,
                quality = voice.quality
            )
        }
    }

    override fun selectVoiceByName(voiceName: String): Boolean {
        val current = tts ?: return false
        val target = runCatching { current.voices }.getOrNull()?.firstOrNull { it.name == voiceName }
            ?: return false
        return runCatching { current.setVoice(target) == TextToSpeech.SUCCESS }.getOrDefault(false)
    }

    override fun setLanguage(locale: Locale): Int {
        val current = tts ?: return TextToSpeech.ERROR
        return runCatching { current.setLanguage(locale) }.getOrDefault(TextToSpeech.ERROR)
    }

    override fun setSpeechRate(rate: Float) {
        runCatching { tts?.setSpeechRate(rate) }
    }

    override fun setPitch(pitch: Float) {
        runCatching { tts?.setPitch(pitch) }
    }

    override fun speak(text: String, utteranceId: String, flushQueue: Boolean): Int {
        val current = tts ?: return TextToSpeech.ERROR
        val mode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return runCatching { current.speak(text, mode, null, utteranceId) }.getOrDefault(TextToSpeech.ERROR)
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun shutdown() {
        activeCallbacks = null
        shutdownInternal()
    }

    private fun shutdownInternal() {
        val prev = tts
        tts = null
        if (prev != null) {
            runCatching { prev.stop() }
            runCatching { prev.shutdown() }
        }
    }
}
