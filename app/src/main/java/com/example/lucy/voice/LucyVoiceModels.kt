package com.example.lucy.voice

import androidx.compose.runtime.Immutable
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyVelocidadRespuesta

@Immutable
enum class LucyVoiceEngineType {
    AUTO,
    GEMINI_TTS,
    ANDROID_TTS
}

@Immutable
data class LucyVoiceOption(
    val id: String,
    val displayName: String,
    val description: String,
    val engineType: LucyVoiceEngineType,
    val isFemale: Boolean = true,
    val isCloud: Boolean = false
)

@Immutable
enum class LucyMicrophonePermissionState {
    NOT_CHECKED,
    GRANTED,
    DENIED
}

@Immutable
data class LucySttAvailability(
    val isServiceAvailable: Boolean = false,
    val isOnDeviceAvailable: Boolean = false,
    val offlineHonestSummary: String = "Disponibilidad de reconocimiento de voz no verificada."
)

@Immutable
sealed interface LucySttState {
    data object Idle : LucySttState

    data class Listening(
        val partialText: String = "",
        val preferOffline: Boolean = false
    ) : LucySttState

    data class Completed(
        val finalText: String
    ) : LucySttState

    data class Error(
        val errorCode: Int,
        val message: String,
        val isRecoverable: Boolean = true
    ) : LucySttState

    data object Cancelled : LucySttState
}

@Immutable
sealed interface LucyTtsState {
    data object Uninitialized : LucyTtsState
    data object Initializing : LucyTtsState

    data class Ready(
        val selectedLocaleTag: String,
        val voiceName: String? = null,
        val isLocalVoice: Boolean = false
    ) : LucyTtsState

    data class Speaking(
        val utteranceId: String,
        val selectedLocaleTag: String
    ) : LucyTtsState

    data class Error(
        val reason: String
    ) : LucyTtsState

    data object Released : LucyTtsState
}

/**
 * Configuración modular de personalidad y cadencia de voz para Lucy.
 * Permite ajustar entonación, velocidad y tono chileno natural sin modificar Lucy Core.
 */
@Immutable
data class LucyVoicePersonalityConfig(
    val preferredLocaleTags: List<String> = listOf("es-CL", "es-419", "es-US", "es-AR", "es-ES", "es"),
    val basePitch: Float = 1.02f,
    val speechRateSlow: Float = 0.85f,
    val speechRateNormal: Float = 1.02f,
    val speechRateFast: Float = 1.24f,
    val minPitch: Float = 0.80f,
    val maxPitch: Float = 1.25f,
    val minRate: Float = 0.65f,
    val maxRate: Float = 1.50f,
    val silenceTimeoutMs: Long = 12_000L,
    val minDuplicateSuppressionWindowMs: Long = 2_500L,
    val useNaturalChileanConversationalTone: Boolean = true,
    val avoidOverusingIdioms: Boolean = true
) {
    fun clampedPitch(): Float = basePitch.coerceIn(minPitch, maxPitch)

    fun speechRateFor(velocidad: LucyVelocidadRespuesta): Float {
        val raw = when (velocidad) {
            LucyVelocidadRespuesta.LENTA -> speechRateSlow
            LucyVelocidadRespuesta.NORMAL -> speechRateNormal
            LucyVelocidadRespuesta.RAPIDA -> speechRateFast
        }
        return raw.coerceIn(minRate, maxRate)
    }
}

sealed interface LucySttStartResult {
    data object Started : LucySttStartResult
    data object AlreadyListening : LucySttStartResult
    data object PermissionDenied : LucySttStartResult
    data class ServiceUnavailable(val reason: String) : LucySttStartResult
    data class Failed(val reason: String) : LucySttStartResult
}

@Immutable
data class LucyVoiceUiStatus(
    val permissionState: LucyMicrophonePermissionState = LucyMicrophonePermissionState.NOT_CHECKED,
    val sttAvailability: LucySttAvailability = LucySttAvailability(),
    val sttState: LucySttState = LucySttState.Idle,
    val ttsState: LucyTtsState = LucyTtsState.Uninitialized,
    val appliedSpeed: LucyVelocidadRespuesta = LucyVelocidadRespuesta.NORMAL,
    val appliedStyle: LucyEstiloRespuesta = LucyEstiloRespuesta.NORMAL,
    val voiceOutputEnabled: Boolean = false,
    val availableVoices: List<LucyVoiceOption> = emptyList(),
    val selectedVoiceId: String = "",
    val appliedVoiceStyle: LucyEstiloVoz = LucyEstiloVoz.NATURAL_CERCANA,
    val activeVoiceEngineLabel: String = "Automática (Gemini / Dispositivo)",
    val fallbackReason: String? = null,
    val lastAudioLatencyMs: Long? = null,
    val isFallbackActive: Boolean = false
)
