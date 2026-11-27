package com.example.lucy

import android.content.Context
import com.example.data.repository.LavanderiaRepository
import com.example.lucy.core.FirebaseAiLucyGeminiBrainGateway
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.vision.FirebaseLucyVisionPort
import com.example.lucy.vision.LucyVisionImageLoader
import com.example.lucy.vision.AndroidLucyVisionImageLoader
import com.example.lucy.voice.AndroidLucyVoicePort

/**
 * Contenedor único de dependencias de Lucy para toda la aplicación.
 * Evita instancias duplicadas de DataStore, STT, TTS y Vision,
 * y centraliza la liberación de recursos asociada al ciclo de vida de LucyViewModel.
 */
data class LucyDependenciesBundle(
    val coordinator: LucyCoreCoordinator,
    val memoryPort: DataStoreLucyMemoryPort,
    val voicePort: AndroidLucyVoicePort,
    val visionPort: FirebaseLucyVisionPort,
    val imageLoader: LucyVisionImageLoader
)

object LucyAppContainer {

    fun createDependencies(
        context: Context,
        repository: LavanderiaRepository
    ): LucyDependenciesBundle {
        val appContext = context.applicationContext
        val memoryPort = DataStoreLucyMemoryPort.createForApp(appContext)
        val voicePort = AndroidLucyVoicePort.createForApp(
            context = appContext,
            memoryPort = memoryPort,
            initialVoiceOutputEnabled = true
        )
        val visionPort = FirebaseLucyVisionPort.createForApp(
            context = appContext,
            repository = repository,
            memoryPort = memoryPort
        )
        val imageLoader = AndroidLucyVisionImageLoader()
        val geminiBrainGateway = FirebaseAiLucyGeminiBrainGateway(context = appContext)
        val coordinator = LucyCoreCoordinator(
            repository = repository,
            visionPort = visionPort,
            voicePort = voicePort,
            memoryPort = memoryPort,
            geminiBrainGateway = geminiBrainGateway
        )
        return LucyDependenciesBundle(
            coordinator = coordinator,
            memoryPort = memoryPort,
            voicePort = voicePort,
            visionPort = visionPort,
            imageLoader = imageLoader
        )
    }
}
