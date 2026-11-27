package com.example.lucy.voice

import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Controlador de reconocimiento de voz (STT) basado en Android SpeechRecognizer.
 * - Exige permiso de micrófono y acción explícita del usuario.
 * - Impide sesiones simultáneas.
 * - No graba ni persiste audio en almacenamiento.
 * - Informa con honestidad si el dispositivo confirma soporte offline.
 */
class LucySttController(
    private val gateway: LucySttGateway,
    private val config: LucyVoicePersonalityConfig = LucyVoicePersonalityConfig(),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val sessionMutex = Mutex()

    @Volatile
    private var isReleased: Boolean = false

    @Volatile
    private var activeHandle: LucySttSessionHandle? = null

    @Volatile
    private var activeSessionToken: Long = 0L

    private var silenceTimeoutJob: Job? = null

    private val _permissionState = MutableStateFlow(LucyMicrophonePermissionState.NOT_CHECKED)
    val permissionState: StateFlow<LucyMicrophonePermissionState> = _permissionState.asStateFlow()

    private val _availability = MutableStateFlow(LucySttAvailability())
    val availability: StateFlow<LucySttAvailability> = _availability.asStateFlow()

    private val _sttState = MutableStateFlow<LucySttState>(LucySttState.Idle)
    val sttState: StateFlow<LucySttState> = _sttState.asStateFlow()

    fun refreshStatus(): LucySttAvailability {
        val permGranted = try {
            gateway.hasRecordAudioPermission()
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: Exception) {
            false
        }
        _permissionState.value = if (permGranted) {
            LucyMicrophonePermissionState.GRANTED
        } else {
            LucyMicrophonePermissionState.DENIED
        }

        val serviceAvailable = try {
            gateway.isRecognitionAvailable()
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: Exception) {
            false
        }

        val onDeviceAvailable = if (serviceAvailable) {
            try {
                gateway.isOnDeviceRecognitionAvailable()
            } catch (ce: CancellationException) {
                throw ce
            } catch (vme: VirtualMachineError) {
                throw vme
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        val summary = when {
            !serviceAvailable ->
                "El servicio de reconocimiento de voz no está disponible en este dispositivo."
            onDeviceAvailable ->
                "Reconocimiento en el dispositivo disponible; sujeto al paquete de voz en español instalado."
            else ->
                "El servicio instalado no confirma reconocimiento sin conexión; puede requerir Internet."
        }

        return LucySttAvailability(
            isServiceAvailable = serviceAvailable,
            isOnDeviceAvailable = onDeviceAvailable,
            offlineHonestSummary = summary
        ).also { _availability.value = it }
    }

    suspend fun startListening(
        preferOffline: Boolean = false,
        onPartialTranscript: (String) -> Unit = {},
        onFinalTranscript: suspend (String) -> Unit = {}
    ): LucySttStartResult {
        if (isReleased) {
            return LucySttStartResult.Failed("El módulo de reconocimiento de voz ya fue liberado.")
        }

        return sessionMutex.withLock {
            if (isReleased) {
                return@withLock LucySttStartResult.Failed("El módulo de reconocimiento de voz ya fue liberado.")
            }
            if (activeHandle != null || _sttState.value is LucySttState.Listening) {
                return@withLock LucySttStartResult.AlreadyListening
            }

            val status = refreshStatus()
            if (_permissionState.value != LucyMicrophonePermissionState.GRANTED) {
                _sttState.value = LucySttState.Error(
                    errorCode = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    message = "Necesito permiso de micrófono para escucharte.",
                    isRecoverable = true
                )
                return@withLock LucySttStartResult.PermissionDenied
            }

            if (!status.isServiceAvailable) {
                _sttState.value = LucySttState.Error(
                    errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    message = status.offlineHonestSummary,
                    isRecoverable = true
                )
                return@withLock LucySttStartResult.ServiceUnavailable(status.offlineHonestSummary)
            }

            val token = ++activeSessionToken
            val localeTag = config.preferredLocaleTags.firstOrNull() ?: "es-CL"

            return@withLock try {
                val handle = withContext(mainDispatcher) {
                    val callbacks = object : LucySttGatewayCallbacks {
                        override fun onReadyForSpeech() {
                            if (token != activeSessionToken || isReleased) return
                            _sttState.value = LucySttState.Listening(
                                partialText = "",
                                preferOffline = preferOffline
                            )
                        }

                        override fun onBeginningOfSpeech() {
                            if (token != activeSessionToken || isReleased) return
                            reprogramarWatchdog(token)
                        }

                        override fun onPartialResult(text: String) {
                            if (token != activeSessionToken || isReleased) return
                            val limpio = text.trim()
                            if (limpio.isNotEmpty()) {
                                _sttState.value = LucySttState.Listening(
                                    partialText = limpio,
                                    preferOffline = preferOffline
                                )
                                reprogramarWatchdog(token)
                                onPartialTranscript(limpio)
                            }
                        }

                        override fun onFinalResult(text: String) {
                            if (token != activeSessionToken || isReleased) return
                            val limpio = text.trim()
                            scope.launch {
                                cerrarSesionInterna(token)
                                if (limpio.isEmpty()) {
                                    _sttState.value = LucySttState.Error(
                                        errorCode = SpeechRecognizer.ERROR_NO_MATCH,
                                        message = "No detecté palabras claras.",
                                        isRecoverable = true
                                    )
                                } else {
                                    _sttState.value = LucySttState.Completed(limpio)
                                    onFinalTranscript(limpio)
                                }
                            }
                        }

                        override fun onError(errorCode: Int, message: String) {
                            if (token != activeSessionToken || isReleased) return
                            scope.launch {
                                cerrarSesionInterna(token)
                                _sttState.value = LucySttState.Error(
                                    errorCode = errorCode,
                                    message = message,
                                    isRecoverable = true
                                )
                            }
                        }
                    }

                    gateway.startSession(
                        preferOffline = preferOffline,
                        localeTag = localeTag,
                        callbacks = callbacks
                    )
                }

                activeHandle = handle
                _sttState.value = LucySttState.Listening(
                    partialText = "",
                    preferOffline = preferOffline
                )
                reprogramarWatchdog(token)
                LucySttStartResult.Started
            } catch (ce: CancellationException) {
                cerrarSesionSinLock(token)
                throw ce
            } catch (vme: VirtualMachineError) {
                cerrarSesionSinLock(token)
                throw vme
            } catch (le: LinkageError) {
                cerrarSesionSinLock(token)
                val msg = "Error nativo en reconocimiento de voz: ${le.message ?: le::class.java.simpleName}"
                _sttState.value = LucySttState.Error(SpeechRecognizer.ERROR_CLIENT, msg, isRecoverable = true)
                LucySttStartResult.Failed(msg)
            } catch (e: Exception) {
                cerrarSesionSinLock(token)
                val msg = e.message ?: "No se pudo iniciar el micrófono."
                _sttState.value = LucySttState.Error(SpeechRecognizer.ERROR_CLIENT, msg, isRecoverable = true)
                LucySttStartResult.Failed(msg)
            }
        }
    }

    suspend fun stopListening() {
        if (isReleased) return
        sessionMutex.withLock {
            val handle = activeHandle ?: return@withLock
            withContext(mainDispatcher) {
                runCatching { handle.stopListening() }
            }
        }
    }

    suspend fun cancelListening() {
        sessionMutex.withLock {
            val hadSession = activeHandle != null || _sttState.value is LucySttState.Listening
            val token = ++activeSessionToken
            cerrarSesionSinLock(token)
            if (hadSession) {
                _sttState.value = LucySttState.Cancelled
            }
        }
    }

    suspend fun release() {
        isReleased = true
        sessionMutex.withLock {
            val token = ++activeSessionToken
            cerrarSesionSinLock(token)
            _sttState.value = LucySttState.Idle
        }
        scope.cancel()
    }

    private fun reprogramarWatchdog(token: Long) {
        silenceTimeoutJob?.cancel()
        val timeoutMs = config.silenceTimeoutMs
        if (timeoutMs <= 0L) return
        silenceTimeoutJob = scope.launch {
            delay(timeoutMs)
            sessionMutex.withLock {
                if (token == activeSessionToken && activeHandle != null && !isReleased) {
                    cerrarSesionSinLock(token, cancelWatchdog = false)
                    _sttState.value = LucySttState.Error(
                        errorCode = SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        message = "No detecté voz a tiempo. Presiona el micrófono cuando quieras intentar de nuevo.",
                        isRecoverable = true
                    )
                }
            }
        }
    }

    private suspend fun cerrarSesionInterna(token: Long) {
        sessionMutex.withLock {
            if (token == activeSessionToken) {
                cerrarSesionSinLock(token)
            }
        }
    }

    private suspend fun cerrarSesionSinLock(
        token: Long,
        cancelWatchdog: Boolean = true
    ) {
        if (token == activeSessionToken) {
            if (cancelWatchdog) {
                silenceTimeoutJob?.cancel()
            }
            silenceTimeoutJob = null
            val handle = activeHandle
            activeHandle = null
            if (handle != null) {
                withContext(mainDispatcher) {
                    runCatching { handle.destroy() }
                }
            }
        }
    }
}
