package com.example.lucy.core

import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.lucy.memory.LucyPreferenciasConversacion
import com.example.lucy.vision.LucyVisionPlanillaAnalysis
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class LucyGeminiBrainExchange(
    val userText: String,
    val assistantText: String
)

interface LucyGeminiToolExecutor {
    suspend fun execute(
        toolName: String,
        args: Map<String, JsonElement>
    ): JsonObject
}

data class LucyGeminiBrainRequest(
    val userInput: String,
    val turnoActivo: Turno,
    val empresaActiva: EmpresaOficial? = null,
    val recentHistory: List<LucyGeminiBrainExchange> = emptyList(),
    val preferences: LucyPreferenciasConversacion = LucyPreferenciasConversacion(),
    val hayBatchActivo: Boolean = false,
    val dudasPendientesResumen: List<String> = emptyList(),
    val ultimoTrabajadorResumen: String? = null,
    val ultimaEmpresaNombre: String? = null,
    val hayPlanillaVisionActiva: Boolean = false,
    val planillaVisionResumen: String? = null,
    val toolExecutor: LucyGeminiToolExecutor,
    val onPartialText: ((String) -> Unit)? = null,
    val onFirstToken: ((Long) -> Unit)? = null
)

sealed interface LucyGeminiBrainOutcome {
    data class Success(
        val replyText: String,
        val modelName: String,
        val toolCallCount: Int = 0,
        val firstTokenLatencyMs: Long = 0L,
        val totalLatencyMs: Long = 0L
    ) : LucyGeminiBrainOutcome

    data class FallbackToOffline(
        val reason: String
    ) : LucyGeminiBrainOutcome
}

class LucyGeminiToolSideEffects(
    initialTurno: Turno
) {
    var turnoFinal: Turno = initialTurno
    val trabajadoresRelacionados = mutableListOf<TrabajadorConEstado>()
    var confirmacionRequerida: LucyPendingConfirmation? = null
    val candidatosDesambiguacion = mutableListOf<TrabajadorConEstado>()
    var accionDesambiguacionSiBajo: Boolean = true
    var esErrorRecuperable: Boolean = false
    var mutatedDb: Boolean = false
    var referencedCompany: EmpresaOficial? = null
    var lastActionType: String = "Conversación con Gemini"
    var lastDirectResponseText: String? = null
    var isReadOnlyQuery: Boolean = false
    var visionAnalysis: LucyVisionPlanillaAnalysis? = null
}

interface LucyGeminiBrainGateway {
    val providerName: String
    suspend fun isAvailable(): Boolean
    suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome
}

object NoOpLucyGeminiBrainGateway : LucyGeminiBrainGateway {
    override val providerName: String = "Sin cerebro Gemini"
    override suspend fun isAvailable(): Boolean = false
    override suspend fun generateTurnReply(request: LucyGeminiBrainRequest): LucyGeminiBrainOutcome =
        LucyGeminiBrainOutcome.FallbackToOffline("Gemini Brain no configurado en esta instancia.")
}
