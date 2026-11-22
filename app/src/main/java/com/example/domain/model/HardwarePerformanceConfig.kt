package com.example.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class PerformanceProfile(
    val cpuCores: Int,
    val maxHeapMb: Long,
    val searchDebounceMs: Long,
    val esDispositivoAltoRendimiento: Boolean
)

/**
 * Adapta parámetros ligeros de ejecución en tiempo real según los núcleos de CPU
 * y memoria heap asignada por el sistema, aprovechando dispositivos de alto desempeño
 * (como RedMagic 11 Pro) sin forzar frecuencias ni perder compatibilidad en ningún Android.
 */
object HardwarePerformanceConfig {

    val profile: PerformanceProfile by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).coerceAtLeast(64L)
        val altoRendimiento = cores >= 6 && maxHeapMb >= 256L
        PerformanceProfile(
            cpuCores = cores,
            maxHeapMb = maxHeapMb,
            searchDebounceMs = if (altoRendimiento) 55L else 100L,
            esDispositivoAltoRendimiento = altoRendimiento
        )
    }

    val searchDebounceMs: Long
        get() = profile.searchDebounceMs
}
