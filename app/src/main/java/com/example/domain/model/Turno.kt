package com.example.domain.model

enum class Turno(val id: String, val nombre: String) {
    A(id = "A", nombre = "Turno A"),
    B(id = "B", nombre = "Turno B");

    companion object {
        fun fromId(id: String?): Turno {
            return when (id?.trim()?.uppercase()) {
                "B" -> B
                else -> A
            }
        }

        /**
         * Parsea texto proveniente de Excel/CSV (e.g. "A", "Turno A", "B", "Turno B").
         * Retorna null si no corresponde inequívocamente a Turno A o Turno B.
         */
        fun parsearOpcional(valor: String?): Turno? {
            val limpio = valor
                ?.trim()
                ?.lowercase()
                ?.replace(Regex("\\s+"), " ")
                ?: return null
            return when (limpio) {
                "a", "turno a", "turno_a", "turnoa" -> A
                "b", "turno b", "turno_b", "turnob" -> B
                else -> null
            }
        }
    }
}
