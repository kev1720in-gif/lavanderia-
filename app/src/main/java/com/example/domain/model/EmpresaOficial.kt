package com.example.domain.model

import java.text.Normalizer

enum class EmpresaOficial(
    val id: Int,
    val codigo: String,
    val nombre: String,
    val colorHex: Long
) {
    KOMATSU(1, "KOMATSU", "Komatsu", 0xFF22C55E),           // Verde
    KOMATSU_ESOP(2, "KOMATSU_ESOP", "Komatsu ESOP", 0xFFFACC15), // Amarillo / Dorado
    FLUITEK(3, "FLUITEK", "Fluitek", 0xFF38BDF8),           // Celeste
    SOMACOR(4, "SOMACOR", "Somacor", 0xFFFB923C),           // Naranjo
    JOY_GLOBAL(5, "JOY_GLOBAL", "Joy Global", 0xFFC084FC),  // Morado
    FINNING(6, "FINNING", "Finning", 0xFF3B82F6),           // Azul
    CUMMINS(7, "CUMMINS", "Cummins", 0xFFEF4444),           // Rojo
    FINNING_PALA(8, "FINNING_PALA", "Finning Pala", 0xFF2DD4BF); // Turquesa

    companion object {
        val todas: List<EmpresaOficial> = entries.toList()

        private val mapaPorNombreNormalizado: Map<String, EmpresaOficial> =
            entries.associateBy { normalizarNombreEmpresa(it.nombre) }

        fun porId(id: Int): EmpresaOficial? = entries.firstOrNull { it.id == id }

        fun porCodigo(codigo: String): EmpresaOficial? = entries.firstOrNull { it.codigo == codigo }

        /**
         * Búsqueda estricta por igualdad exacta normalizada.
         * Nunca confunde "Komatsu" con "Komatsu ESOP" ni "Finning" con "Finning Pala".
         */
        fun buscarExactaPorNombre(entrada: String?): EmpresaOficial? {
            if (entrada.isNullOrBlank()) return null
            val normalizada = normalizarNombreEmpresa(entrada)
            return mapaPorNombreNormalizado[normalizada]
        }

        fun normalizarNombreEmpresa(valor: String): String {
            val sinAcentos = Normalizer.normalize(valor, Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            return sinAcentos
                .trim()
                .lowercase()
                .replace(Regex("\\s+"), " ")
        }
    }
}
