package com.example.lucy.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.vision.LucyVisionConfig
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionCallingConfig
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.ToolConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Cerebro conversacional central de Lucy sobre Firebase AI Logic (`Firebase.ai`),
 * con respuestas en streaming para baja latencia percibida, function calling controlado sobre el maestro real de Room,
 * timeouts realistas y fallback automático a [LucyIntentParserOffline].
 */
class FirebaseAiLucyGeminiBrainGateway(
    context: Context,
    private val primaryModelName: String = LucyVisionConfig.DEFAULT_MODEL_NAME,
    private val fallbackModelName: String = LucyVisionConfig.FALLBACK_MODEL_NAME,
    private val timeoutMs: Long = 15_000L,
    private val fallbackTimeoutMs: Long = 12_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LucyGeminiBrainGateway {

    private val appContext = context.applicationContext

    @Volatile
    private var firebaseConfiguredCached: Boolean = false

    override val providerName: String = "Firebase AI Logic ($primaryModelName)"

    private val primaryModel: GenerativeModel by lazy { createModel(primaryModelName) }
    private val fallbackModel: GenerativeModel by lazy { createModel(fallbackModelName) }

    override suspend fun isAvailable(): Boolean = withContext(ioDispatcher) {
        isFirebaseConfigured() && isNetworkAvailable()
    }

    override suspend fun generateTurnReply(
        request: LucyGeminiBrainRequest
    ): LucyGeminiBrainOutcome = withContext(ioDispatcher) {
        if (!isFirebaseConfigured()) {
            return@withContext LucyGeminiBrainOutcome.FallbackToOffline(
                "Firebase AI Logic no está configurado en esta instancia."
            )
        }
        if (!isNetworkAvailable()) {
            return@withContext LucyGeminiBrainOutcome.FallbackToOffline(
                "Sin conexión a Internet; operando con motor offline."
            )
        }

        val firstAttempt = invokeWithModel(
            model = primaryModel,
            modelName = primaryModelName,
            attemptTimeoutMs = timeoutMs,
            request = request
        )
        if (firstAttempt is LucyGeminiBrainOutcome.Success) {
            return@withContext firstAttempt
        }

        val shouldRetryFallback = fallbackModelName.isNotBlank() && fallbackModelName != primaryModelName
        if (shouldRetryFallback) {
            Log.w(
                TAG,
                "Reintentando cerebro Gemini con fallback ($fallbackModelName) tras: ${(firstAttempt as LucyGeminiBrainOutcome.FallbackToOffline).reason}"
            )
            val fallbackAttempt = invokeWithModel(
                model = fallbackModel,
                modelName = fallbackModelName,
                attemptTimeoutMs = fallbackTimeoutMs,
                request = request
            )
            if (fallbackAttempt is LucyGeminiBrainOutcome.Success) {
                return@withContext fallbackAttempt
            }
        }

        firstAttempt
    }

    private suspend fun invokeWithModel(
        model: GenerativeModel,
        modelName: String,
        attemptTimeoutMs: Long,
        request: LucyGeminiBrainRequest
    ): LucyGeminiBrainOutcome {
        val startMs = System.currentTimeMillis()
        var firstTokenLatencyMs = -1L
        return try {
            val historyContents: List<Content> = request.recentHistory
                .takeLast(MAX_HISTORY_TURNS)
                .flatMap { ex ->
                    listOf(
                        content("user") { text(ex.userText) },
                        content("model") { text(ex.assistantText) }
                    )
                }

            val chat = model.startChat(historyContents)
            val contextualMessage = buildContextualUserPrompt(request)

            var toolCallsCount = 0
            var turnResult = collectChatStreamTurn(
                chat = chat,
                promptContent = content("user") { text(contextualMessage) },
                timeoutBudgetMs = attemptTimeoutMs,
                onTextUpdate = { accumulated ->
                    if (firstTokenLatencyMs < 0L && accumulated.isNotBlank()) {
                        firstTokenLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                        request.onFirstToken?.invoke(firstTokenLatencyMs)
                    }
                    request.onPartialText?.invoke(accumulated)
                }
            )

            var rounds = 0
            while (turnResult.functionCalls.isNotEmpty() && rounds < MAX_TOOL_ROUNDS) {
                rounds++
                val calls = turnResult.functionCalls
                val responseParts = mutableListOf<FunctionResponsePart>()
                for (call in calls) {
                    toolCallsCount++
                    val jsonObj = request.toolExecutor.execute(call.name, call.args)
                    responseParts.add(
                        FunctionResponsePart(
                            name = call.name,
                            response = jsonObj,
                            id = call.id
                        )
                    )
                }
                val functionContent = Content(
                    role = "function",
                    parts = responseParts
                )
                turnResult = collectChatStreamTurn(
                    chat = chat,
                    promptContent = functionContent,
                    timeoutBudgetMs = attemptTimeoutMs,
                    onTextUpdate = { accumulated ->
                        if (firstTokenLatencyMs < 0L && accumulated.isNotBlank()) {
                            firstTokenLatencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                            request.onFirstToken?.invoke(firstTokenLatencyMs)
                        }
                        request.onPartialText?.invoke(accumulated)
                    }
                )
            }

            val finalText = turnResult.text.trim()
            if (finalText.isBlank()) {
                LucyGeminiBrainOutcome.FallbackToOffline(
                    "Gemini ($modelName) no devolvió texto de respuesta."
                )
            } else {
                val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                val effectiveFirstToken = if (firstTokenLatencyMs >= 0L) firstTokenLatencyMs else totalMs
                LucyGeminiBrainOutcome.Success(
                    replyText = finalText,
                    modelName = modelName,
                    toolCallCount = toolCallsCount,
                    firstTokenLatencyMs = effectiveFirstToken,
                    totalLatencyMs = totalMs
                )
            }
        } catch (te: TimeoutCancellationException) {
            Log.w(TAG, "Timeout en Gemini Brain ($modelName) tras ${attemptTimeoutMs}ms", te)
            LucyGeminiBrainOutcome.FallbackToOffline("Tiempo de espera agotado al consultar Gemini.")
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            Log.w(TAG, "Error nativo en Gemini Brain ($modelName)", le)
            LucyGeminiBrainOutcome.FallbackToOffline("Error técnico en módulo Gemini.")
        } catch (e: Exception) {
            Log.w(TAG, "Fallo en Gemini Brain ($modelName): ${e.message}", e)
            LucyGeminiBrainOutcome.FallbackToOffline(e.message ?: "Error al conectar con Gemini.")
        }
    }

    private data class StreamTurnResult(
        val text: String,
        val functionCalls: List<FunctionCallPart>
    )

    private suspend fun collectChatStreamTurn(
        chat: Chat,
        promptContent: Content,
        timeoutBudgetMs: Long,
        onTextUpdate: (String) -> Unit
    ): StreamTurnResult {
        val textBuffer = StringBuilder()
        val calls = mutableListOf<FunctionCallPart>()
        return try {
            withTimeout(timeoutBudgetMs) {
                chat.sendMessageStream(promptContent).collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        textBuffer.append(chunkText)
                        onTextUpdate(textBuffer.toString())
                    }
                    if (chunk.functionCalls.isNotEmpty()) {
                        calls.addAll(chunk.functionCalls)
                    }
                }
            }
            StreamTurnResult(text = textBuffer.toString(), functionCalls = calls)
        } catch (te: TimeoutCancellationException) {
            throw te
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Si streaming no pudo iniciar antes de recibir chunks, caer una vez a llamada unaria
            if (textBuffer.isEmpty() && calls.isEmpty()) {
                val unary = withTimeout(timeoutBudgetMs) {
                    chat.sendMessage(promptContent)
                }
                val unaryText = unary.text.orEmpty()
                if (unaryText.isNotBlank()) {
                    onTextUpdate(unaryText)
                }
                StreamTurnResult(text = unaryText, functionCalls = unary.functionCalls)
            } else {
                throw e
            }
        }
    }

    private fun createModel(modelName: String): GenerativeModel {
        return Firebase.ai.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                temperature = 0.2f
            },
            tools = listOf(Tool.functionDeclarations(LucyGeminiToolsBridge.toolDeclarations)),
            toolConfig = ToolConfig(FunctionCallingConfig.auto()),
            systemInstruction = content { text(SYSTEM_INSTRUCTION) }
        )
    }

    private fun buildContextualUserPrompt(request: LucyGeminiBrainRequest): String {
        val estiloTexto = when (request.preferences.estilo) {
            LucyEstiloRespuesta.CONCISA -> "Concisa y directa"
            LucyEstiloRespuesta.NORMAL -> "Natural, clara y amable"
            LucyEstiloRespuesta.DETALLADA -> "Detallada con datos útiles"
        }
        val instrucciones = if (request.preferences.instruccionesPermanentes.isEmpty()) {
            "Ninguna adicional"
        } else {
            request.preferences.instruccionesPermanentes.joinToString("; ") { it.texto }
        }
        val dudas = if (request.dudasPendientesResumen.isEmpty()) {
            "Sin dudas pendientes"
        } else {
            request.dudasPendientesResumen.joinToString(" | ")
        }

        return """
            [Contexto operativo del turno actual]
            - Turno activo: ${request.turnoActivo.nombre}
            - Empresa seleccionada/reciente: ${request.empresaActiva?.nombre ?: request.ultimaEmpresaNombre ?: "Ninguna"}
            - Último trabajador mencionado en este turno: ${request.ultimoTrabajadorResumen ?: "Ninguno"}
            - Dictado múltiple en curso: ${if (request.hayBatchActivo) "Sí" else "No"}
            - Dudas pendientes de lote: $dudas
            - Planilla por foto activa en este turno: ${request.planillaVisionResumen ?: "Ninguna"}
            - Estilo de respuesta preferido: $estiloTexto
            - Instrucciones permanentes del usuario: $instrucciones

            Mensaje actual del operador:
            ${request.userInput}
        """.trimIndent()
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
        private const val TAG = "FirebaseAiBrainGw"
        private const val MAX_HISTORY_TURNS = 5
        private const val MAX_TOOL_ROUNDS = 5

        private val SYSTEM_INSTRUCTION = """
            Eres Lucy, la asistente operativa inteligente de Lavandería Control en faena minera (Chile).
            Respondes siempre en español natural, cálido, respetuoso, claro y sin rodeos innecesarios.

            REGLAS ESTRICTAS DE VERACIDAD Y SEGURIDAD:
            1. NUNCA inventes trabajadores, apellidos, habitaciones, pabellones, empresas, conteos ni estados.
            2. Antes de responder cualquier consulta sobre trabajadores, estados, pendientes, empresas, conteos o memoria, consulta primero las herramientas reales disponibles.
            3. Separa SIEMPRE la intención del contenido: si el operador dice "Lucy, ¿tienes registrado a Sebastián Morales?", busca solo "Sebastián Morales" con `buscar_trabajador`, nunca frases completas ni signos.
            4. Preguntas ("¿bajó Juan?", "¿tienes registrado a...?"), menciones pasivas ("vi a Pedro en el pasillo") o ejemplos/hipótesis ("¿qué pasaría si marco a Carlos?") son SOLO LECTURA. Jamás ejecutes `marcar_subieron_lote`, `preparar_marcacion_individual` ni `actualizar_dato_trabajador` en esos casos.
            5. Cuando el operador dé una orden o afirmación explícita grupal o de subida múltiple ("estos subieron de Komatsu: Juan, Pedro y Diego", "subió X y Y", "agrega también a Z" durante un dictado activo), llama a `marcar_subieron_lote`. No pidas confirmación por cada trabajador ya identificado de forma inequívoca. Reporta exactamente cuántos quedaron marcados, quiénes ya lo estaban y qué dudas quedaron pendientes (ambiguos, otra empresa o no encontrados).
            6. Cuando el operador pida marcar o desmarcar a un solo trabajador ("marca a Juan Pérez", "desmarca a Rojas"), llama a `preparar_marcacion_individual` para generar la tarjeta de confirmación manual en pantalla.
            7. Cuando una herramienta indique varios candidatos o dudas, respeta fielmente esos datos y pregunta de forma breve cuál corresponde o si desea dejarlo pendiente.
            8. Respeta los resultados de `mensaje_exacto_sistema` sin alterar cifras, nombres ni estados de la base de datos.
            9. Si existe una planilla por foto activa en el turno y el operador indica o pide filtrar/comparar esos nombres por una empresa ("Son de Fluitek", "Filtra solamente los de Fluitek", "Busca esos nombres en Fluitek", "Todos pertenecen a Fluitek", "Compara la foto únicamente con el personal de Fluitek", "Ahora revisa los que coinciden con Komatsu"), llama SIEMPRE a `filtrar_planilla_vision_por_empresa` con el nombre de esa empresa. Nunca busques en otras empresas ni ejecutes marcaciones automáticas desde la foto.
        """.trimIndent()
    }
}
