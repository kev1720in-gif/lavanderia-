package com.example.lucy.voice

import com.example.domain.model.TextoNormalizer
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.memory.LucyPreferenciasConversacion

/**
 * Limpia símbolos, abreviaturas técnicas y formatos visuales para producir texto
 * natural, fluido y cercano en español chileno, adaptado al estilo e instrucciones de Memory.
 */
object LucyVoiceTextFormatter {

    private val SOLO_SIMBOLOS_REGEX = Regex("^[\\p{Punct}\\p{S}\\s•·…—–|]+$")
    private val BULLET_INICIO_REGEX = Regex("(?m)^\\s*[•\\-*]+\\s*")
    private val PROPORCION_REGEX = Regex("\\b(\\d+)\\s*/\\s*(\\d+)\\b")
    private val HAB_ABREV_REGEX = Regex("\\b[Hh]ab\\.?\\s*([A-Za-z0-9-]+)")
    private val PAB_ABREV_REGEX = Regex("\\b[Pp]ab\\.?\\s*([A-Za-z0-9-]+)")
    private val CODIGO_CORCHETES_REGEX = Regex("\\[([A-Za-z0-9_-]{2,})\\]")

    fun prepararParaVoz(
        textoOriginal: String,
        preferencias: LucyPreferenciasConversacion = LucyPreferenciasConversacion(),
        config: LucyVoicePersonalityConfig = LucyVoicePersonalityConfig()
    ): String {
        val raw = textoOriginal.trim()
        if (raw.isBlank() || SOLO_SIMBOLOS_REGEX.matches(raw)) {
            return ""
        }

        val pideSinRodeos = preferencias.instruccionesPermanentes.any { instr ->
            val norm = TextoNormalizer.normalizar(instr.texto)
            norm.contains("sin rodeos") ||
                norm.contains("corto") ||
                norm.contains("breve") ||
                norm.contains("directo")
        }
        val modoConciso = preferencias.estilo == LucyEstiloRespuesta.CONCISA || pideSinRodeos

        val lineas = raw
            .replace("\r\n", "\n")
            .split('\n')
            .map { linea -> limpiarLineaParaVoz(linea) }
            .filter { it.isNotBlank() && !SOLO_SIMBOLOS_REGEX.matches(it) }

        if (lineas.isEmpty()) return ""

        val maxLineasVoz = when {
            modoConciso -> 3
            preferencias.estilo == LucyEstiloRespuesta.DETALLADA -> 6
            else -> 4
        }
        val maxCaracteresVoz = when {
            modoConciso -> 170
            preferencias.estilo == LucyEstiloRespuesta.DETALLADA -> 360
            else -> 240
        }

        val lineasAdaptadas = if (lineas.size > maxLineasVoz) {
            val keepItems = (maxLineasVoz - 1).coerceAtLeast(1)
            val encabezado = lineas.first()
            val primeras = lineas.drop(1).take(keepItems)
            val restantes = (lineas.size - (1 + keepItems)).coerceAtLeast(0)
            buildList {
                add(encabezado)
                addAll(primeras)
                if (restantes > 0) {
                    add("y $restantes más en pantalla")
                }
            }
        } else {
            lineas
        }

        var unido = lineasAdaptadas.joinToString(separator = ". ") { frase ->
            frase.trim().trimEnd('.', ';', ':')
        }.trim()

        if (unido.isBlank()) return ""

        if (unido.length > maxCaracteresVoz) {
            val limite = (maxCaracteresVoz - 32).coerceAtLeast(80)
            val corte = unido.lastIndexOf(' ', startIndex = limite.coerceAtMost(unido.length - 1))
                .takeIf { it > 40 } ?: limite.coerceAtMost(unido.length)
            val prefijo = unido.substring(0, corte).trim().trimEnd(',', ';', ':', '.')
            unido = "$prefijo. El detalle completo está en pantalla"
        }

        unido = aplicarTonoConversacional(
            texto = unido,
            modoConciso = modoConciso,
            estilo = preferencias.estilo,
            config = config
        )

        unido = unido
            .replace(Regex("\\s+([,;:.?!])"), "$1")
            .replace(Regex("\\.{2,}"), ".")
            .replace(Regex(",\\s*,"), ",")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        if (unido.isBlank() || SOLO_SIMBOLOS_REGEX.matches(unido)) return ""

        return if (unido.last() in charArrayOf('.', '?', '!')) unido else "$unido."
    }

    private fun limpiarLineaParaVoz(linea: String): String {
        var out = linea.trim()
        if (out.isEmpty()) return ""

        out = out.replace(BULLET_INICIO_REGEX, "")

        // Estados operativos en lenguaje natural (evitar deletreo en mayúsculas)
        out = out
            .replace("[SÍ BAJÓ]", ", sí bajó")
            .replace("[NO BAJÓ]", ", no bajó")
            .replace("SÍ BAJÓ", "sí bajó")
            .replace("NO BAJÓ", "no bajó")
            .replace("SÍ SUBIÓ", "sí subió")
            .replace("NO SUBIÓ", "no subió")
            .replace("[actualizado]", "")
            .replace("[ya estaba guardado]", "")

        // Remover códigos técnicos entre corchetes que no aportan al audio
        out = out.replace(CODIGO_CORCHETES_REGEX, "")

        // Separadores visuales -> pausas naturales
        out = out
            .replace("·", ", ")
            .replace("|", ". ")
            .replace("—", ", ")
            .replace("–", ", ")
            .replace("…", ", ")
            .replace("...", ", ")
            .replace("\"", "")
            .replace("'", "")
            .replace("*", "")
            .replace("#", "")
            .replace("_", " ")

        // Proporciones tipo 12/20 -> "12 de 20"
        out = out.replace(PROPORCION_REGEX, "$1 de $2")

        // Abreviaturas de ubicación habituales en la faena
        out = out.replace(HAB_ABREV_REGEX, "habitación $1")
        out = out.replace(PAB_ABREV_REGEX, "pabellón $1")

        // Quitar paréntesis vacíos o que quedaron solo con puntuación
        out = out
            .replace(Regex("\\(\\s*\\)"), "")
            .replace("(", ", ")
            .replace(")", "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trimStart(',', ';', ':')
            .trim()

        return out
    }

    private fun aplicarTonoConversacional(
        texto: String,
        modoConciso: Boolean,
        estilo: LucyEstiloRespuesta,
        config: LucyVoicePersonalityConfig
    ): String {
        var out = texto
            .replace(Regex("^Confirmado en (Turno [AB]):\\s*", RegexOption.IGNORE_CASE), "Listo en $1, ")
            .replace(Regex("^Guardado en (Turno [AB]):\\s*", RegexOption.IGNORE_CASE), "Anotado en $1, ")
            .replace(Regex("^No encontré trabajadores para\\s+", RegexOption.IGNORE_CASE), "No pillé registros para ")
            .replace(Regex("^Ya estás trabajando en\\s+", RegexOption.IGNORE_CASE), "Ya estamos en ")

        if (modoConciso) {
            out = out
                .replace(Regex("^Listo en (Turno [AB]),\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^Anotado en (Turno [AB]),\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
        } else if (config.useNaturalChileanConversationalTone && estilo == LucyEstiloRespuesta.DETALLADA) {
            if (!out.startsWith("Listo", ignoreCase = true) &&
                !out.startsWith("Perfecto", ignoreCase = true) &&
                !out.startsWith("Ya ", ignoreCase = true) &&
                !out.startsWith("Soy Lucy", ignoreCase = true) &&
                out.length < 140
            ) {
                out = "Te cuento: $out"
            }
        }

        // Mantener mayúscula inicial limpia tras recorte
        return out.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
    }
}
