package com.example.lucy.core

import com.example.domain.model.BuscadorTrabajadores
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.TrabajadorConEstado
import com.example.lucy.memory.LucyCorreccionAprendida
import com.example.lucy.memory.LucyMemoryStorageSerializer
import com.example.lucy.memory.LucyTipoCorreccionAprendida
import kotlin.math.min

sealed interface LucyBatchMatchOutcome {
    data class Identificado(
        val nombreSolicitado: String,
        val trabajador: TrabajadorConEstado
    ) : LucyBatchMatchOutcome

    data class Ambiguo(
        val nombreSolicitado: String,
        val candidatos: List<TrabajadorConEstado>
    ) : LucyBatchMatchOutcome

    data class OtraEmpresa(
        val nombreSolicitado: String,
        val candidatos: List<TrabajadorConEstado>,
        val empresasReales: List<EmpresaOficial>,
        val empresaEsperada: EmpresaOficial? = null
    ) : LucyBatchMatchOutcome

    data class NoEncontrado(
        val nombreSolicitado: String
    ) : LucyBatchMatchOutcome
}

data class LucyBatchMatchResult(
    val identificados: List<LucyBatchMatchOutcome.Identificado>,
    val ambiguos: List<LucyBatchMatchOutcome.Ambiguo>,
    val otrasEmpresas: List<LucyBatchMatchOutcome.OtraEmpresa>,
    val noEncontrados: List<LucyBatchMatchOutcome.NoEncontrado>,
    val nombresDuplicadosEnDictado: List<String>
)

/**
 * Segmentador y motor seguro de identificación múltiple de trabajadores para dictado por voz y texto.
 *
 * Reglas de seguridad:
 * - Solo opera sobre la lista de trabajadores del turno activo recibido.
 * - Si hay 1 coincidencia inequívoca en el ámbito esperado, se marca como Identificado.
 * - Si hay 2+ coincidencias probables (ambigüedad), NO se selecciona ninguno al azar: se reporta Ambiguo.
 * - Si la empresa fue especificada y el trabajador pertenece a otra empresa, NO se marca: se reporta OtraEmpresa.
 * - Si no existe coincidencia segura, NO se inventa: se reporta NoEncontrado.
 * - Tolera variaciones fonéticas/acentos del español (s/z/c, b/v, ll/y, ñ/n, h muda, g/j) y errores leves de STT
 *   sin confundir personas distintas.
 */
object LucySafeBatchMatcher {

    private val SEPARADORES_EXPLICITOS_REGEX = Regex("[,;\\n•·]+")
    private val CONECTORES_SEPARACION_REGEX = Regex(
        "\\b(?:y|e|tambien|ademas|luego|despues|otro\\s+es|otra\\s+es|sumale|agrega(?:r)?)\\b",
        RegexOption.IGNORE_CASE
    )
    private val PREFIJOS_RELLENO_REGEX = Regex(
        "^(?:(?:a|al|el|la|los|las|de|del|y|e|tambien|ademas|son|fueron|estos|estas|esos|esas|subio|subieron|sr|sra|don|dona|trabajador|trabajadores)\\s+)+",
        RegexOption.IGNORE_CASE
    )
    private val SUFIJOS_RELLENO_REGEX = Regex(
        "\\s+(?:(?:si\\s+)?subi(?:o|eron)|al\\s+camion|por\\s+favor|porfa|tambien)$",
        RegexOption.IGNORE_CASE
    )
    private val CONECTORES_INTERNOS_NOMBRES = setOf("de", "del", "la", "las", "los", "da", "do")

    private val EQUIVALENCIAS_STT_TOKENS = mapOf(
        "brayan" to "brian",
        "braian" to "brian",
        "bryan" to "brian",
        "brayhan" to "brian",
        "brihan" to "brian",
        "jonatan" to "jonathan",
        "yerson" to "jerson",
        "yeferson" to "jeferson",
        "jefferson" to "jeferson",
        "maicol" to "michael",
        "maikol" to "michael",
        "maycol" to "michael",
        "keven" to "kevin",
        "kebin" to "kevin",
        "cristian" to "kristian",
        "christian" to "kristian",
        "cristhian" to "kristian",
        "stiven" to "steven",
        "estiven" to "steven",
        "yordan" to "jordan",
        "johan" to "yoan"
    )

    private enum class MatchTier {
        EXACT_ALL,
        EXACT_TOKENS,
        PHONETIC_TOKENS,
        FUZZY_CONTROLLED
    }

    private data class CandidateMatch(
        val trabajador: TrabajadorConEstado,
        val tier: MatchTier,
        val exactAndPhoneticCount: Int,
        val totalDistance: Int
    )

    fun resolverLote(
        nombresTexto: String,
        empresaObjetivo: EmpresaOficial?,
        maestroTurnoActivo: List<TrabajadorConEstado>,
        correccionesAprendidas: List<LucyCorreccionAprendida> = emptyList()
    ): LucyBatchMatchResult {
        val universoPreferido = if (empresaObjetivo != null) {
            maestroTurnoActivo.filter { it.empresa == empresaObjetivo }
        } else {
            maestroTurnoActivo
        }

        val segmentosConEmpresa = extraerSegmentosConEmpresa(
            nombresTexto = nombresTexto,
            universoReferencia = universoPreferido.ifEmpty { maestroTurnoActivo },
            universoCompletoTurno = maestroTurnoActivo
        )

        val identificados = mutableListOf<LucyBatchMatchOutcome.Identificado>()
        val ambiguos = mutableListOf<LucyBatchMatchOutcome.Ambiguo>()
        val otrasEmpresas = mutableListOf<LucyBatchMatchOutcome.OtraEmpresa>()
        val noEncontrados = mutableListOf<LucyBatchMatchOutcome.NoEncontrado>()
        val idsVistos = HashSet<String>()
        val duplicadosEnDictado = mutableListOf<String>()

        for ((segmento, empresaSegmento) in segmentosConEmpresa) {
            when (
                val outcome = evaluarSegmento(
                    segmento = segmento,
                    empresaObjetivo = empresaSegmento ?: empresaObjetivo,
                    maestroTurnoActivo = maestroTurnoActivo,
                    correccionesAprendidas = correccionesAprendidas
                )
            ) {
                is LucyBatchMatchOutcome.Identificado -> {
                    if (idsVistos.add(outcome.trabajador.id)) {
                        identificados.add(outcome)
                    } else {
                        duplicadosEnDictado.add(outcome.nombreSolicitado)
                    }
                }
                is LucyBatchMatchOutcome.Ambiguo -> ambiguos.add(outcome)
                is LucyBatchMatchOutcome.OtraEmpresa -> otrasEmpresas.add(outcome)
                is LucyBatchMatchOutcome.NoEncontrado -> noEncontrados.add(outcome)
            }
        }

        return LucyBatchMatchResult(
            identificados = identificados,
            ambiguos = ambiguos,
            otrasEmpresas = otrasEmpresas,
            noEncontrados = noEncontrados,
            nombresDuplicadosEnDictado = duplicadosEnDictado
        )
    }

    /**
     * Búsqueda individual tolerante a tildes, variaciones fonéticas (Bryan/Brian, Moralez/Morales),
     * errores leves de transcripción ("Sebastan") y nombres parciales ("Seba Moralez"),
     * conservando prioridad absoluta para coincidencias exactas de [BuscadorTrabajadores].
     */
    fun buscarIndividualTolerante(
        query: String,
        universo: List<TrabajadorConEstado>,
        correccionesAprendidas: List<LucyCorreccionAprendida> = emptyList()
    ): List<TrabajadorConEstado> {
        val limpia = query.trim()
        if (limpia.isBlank() || universo.isEmpty()) return emptyList()

        val exactos = BuscadorTrabajadores.filtrarYOrdenar(universo, limpia)
        if (exactos.isNotEmpty()) return exactos

        if (correccionesAprendidas.isNotEmpty()) {
            val clave = LucyMemoryStorageSerializer.normalizarClaveCorreccion(limpia)
            if (clave.isNotBlank()) {
                val idsAprendidos = correccionesAprendidas
                    .filter { it.claveErroneaNorm == clave && it.tipo != LucyTipoCorreccionAprendida.DATO_MAESTRO }
                    .map { it.trabajadorId }
                    .toSet()
                if (idsAprendidos.isNotEmpty()) {
                    val aprendidos = universo.filter { it.id in idsAprendidos }
                    if (aprendidos.isNotEmpty()) return aprendidos
                }
            }
        }

        val tokens = tokenizarConsulta(limpia)
        if (tokens.isEmpty()) return emptyList()

        val seguros = buscarCoincidenciasSeguras(tokens, universo)
        if (seguros.isNotEmpty()) return seguros

        return buscarAproximacionIndividual(tokens, universo)
    }

    private fun buscarAproximacionIndividual(
        tokensRaw: List<String>,
        universo: List<TrabajadorConEstado>
    ): List<TrabajadorConEstado> {
        val qTokens = tokensRaw.filter { it.length >= 2 && it !in CONECTORES_INTERNOS_NOMBRES }
        if (qTokens.isEmpty() || qTokens.size > 4) return emptyList()
        val qPhon = qTokens.map { normalizarFoneticoEspanol(it) }

        val ranked = mutableListOf<Pair<TrabajadorConEstado, Int>>()
        for (trabajador in universo) {
            val pTokens = trabajador.searchIndex.tokensPersona
                .filter { it.length >= 2 && it !in CONECTORES_INTERNOS_NOMBRES }
            if (pTokens.isEmpty()) continue
            val pPhon = pTokens.map { normalizarFoneticoEspanol(it) }
            val usados = BooleanArray(pTokens.size)
            var costoTotal = 0
            var matched = true

            for (i in qTokens.indices) {
                val q = qTokens[i]
                val qp = qPhon[i]

                val idxExact = pTokens.indices.firstOrNull { !usados[it] && pTokens[it] == q }
                if (idxExact != null) {
                    usados[idxExact] = true
                    continue
                }
                val idxPhon = pTokens.indices.firstOrNull { !usados[it] && pPhon[it] == qp }
                if (idxPhon != null) {
                    usados[idxPhon] = true
                    costoTotal += 1
                    continue
                }
                val idxPrefix = if (q.length >= 3) {
                    pTokens.indices.firstOrNull { !usados[it] && (pTokens[it].startsWith(q) || pPhon[it].startsWith(qp)) }
                } else null
                if (idxPrefix != null) {
                    usados[idxPrefix] = true
                    costoTotal += 2
                    continue
                }
                val idxFuzzy = if (q.length >= 5) {
                    pTokens.indices
                        .filter { !usados[it] && pTokens[it].length >= 5 }
                        .mapNotNull { idx ->
                            val dRaw = levenshtein(q, pTokens[idx])
                            val dPhon = levenshtein(qp, pPhon[idx])
                            val d = min(dRaw, dPhon)
                            if (d <= 1) idx to d else null
                        }
                        .minByOrNull { it.second }
                } else null
                if (idxFuzzy != null) {
                    usados[idxFuzzy.first] = true
                    costoTotal += 3 + idxFuzzy.second
                    continue
                }
                matched = false
                break
            }

            if (matched) {
                ranked.add(trabajador to costoTotal)
            }
        }

        if (ranked.isEmpty()) return emptyList()
        val mejorCosto = ranked.minOf { it.second }
        return ranked.filter { it.second == mejorCosto }.map { it.first }
    }

    internal fun extraerSegmentosNombres(
        nombresTexto: String,
        universoReferencia: List<TrabajadorConEstado>,
        universoCompletoTurno: List<TrabajadorConEstado>
    ): List<String> = extraerSegmentosConEmpresa(
        nombresTexto = nombresTexto,
        universoReferencia = universoReferencia,
        universoCompletoTurno = universoCompletoTurno
    ).map { it.first }

    private fun extraerSegmentosConEmpresa(
        nombresTexto: String,
        universoReferencia: List<TrabajadorConEstado>,
        universoCompletoTurno: List<TrabajadorConEstado>
    ): List<Pair<String, EmpresaOficial?>> {
        val normalizadoSeparadores = nombresTexto
            .replace("\r\n", "\n")
            .replace(":", ",")
            .replace(SEPARADORES_EXPLICITOS_REGEX, "|")
            .split('|')
            .flatMap { trozo -> dividirPorConectores(trozo) }
            .map { extraerEmpresaDeFragmento(limpiarFragmento(it)) }
            .filter { it.first.isNotBlank() }

        val resultado = mutableListOf<Pair<String, EmpresaOficial?>>()
        for ((fragmento, empresaSeg) in normalizadoSeparadores) {
            val tokens = tokenizarConsulta(fragmento)
            if (tokens.isEmpty()) continue

            if (tokens.size <= 3) {
                resultado.add(fragmento to empresaSeg)
                continue
            }

            // Intentar primero como un único nombre completo (ej. "Sebastián Alberto Morales Pérez")
            val matchCompleto = buscarCoincidenciasSeguras(tokens, universoCompletoTurno)
            if (matchCompleto.isNotEmpty() && tokens.size <= 4) {
                resultado.add(fragmento to empresaSeg)
                continue
            }

            // Cuando el dictado viene sin comas ("Sebastián Morales Pedro González Juan Pérez"),
            // segmentamos por ventanas descendentes (4 -> 3 -> 2 tokens).
            val refBase = if (empresaSeg != null) {
                universoCompletoTurno.filter { it.empresa == empresaSeg }.ifEmpty { universoReferencia }
            } else {
                universoReferencia
            }
            val universoSegmentacion = if (refBase.isNotEmpty()) {
                val idsRef = refBase.mapTo(HashSet(refBase.size * 2)) { it.id }
                refBase + universoCompletoTurno.filter { it.id !in idsRef }
            } else {
                universoCompletoTurno
            }
            segmentarNombresConsecutivos(tokens, universoSegmentacion).forEach { seg ->
                resultado.add(seg to empresaSeg)
            }
        }
        return resultado
    }

    private fun extraerEmpresaDeFragmento(fragmentoLimpio: String): Pair<String, EmpresaOficial?> {
        if (fragmentoLimpio.isBlank()) return "" to null
        val ordenadas = EmpresaOficial.todas
            .map { TextoNormalizer.normalizar(it.nombre) to it }
            .sortedByDescending { it.first.length }
        for ((empNorm, empresa) in ordenadas) {
            val regex = Regex("(?:\\b(?:de|del|en|para|empresa)\\s+)?\\b${Regex.escape(empNorm)}\\b")
            if (regex.containsMatchIn(fragmentoLimpio)) {
                val sinEmpresa = limpiarFragmento(fragmentoLimpio.replace(regex, " "))
                return sinEmpresa to empresa
            }
        }
        return fragmentoLimpio to null
    }

    private fun dividirPorConectores(texto: String): List<String> {
        val sinAcentos = TextoNormalizer.normalizar(texto)
        if (sinAcentos.isBlank()) return emptyList()

        // Usamos los rangos sobre el texto normalizado para conservar palabras legibles
        val partes = mutableListOf<String>()
        var ultimo = 0
        for (m in CONECTORES_SEPARACION_REGEX.findAll(sinAcentos)) {
            val previo = sinAcentos.substring(ultimo, m.range.first).trim()
            if (previo.isNotBlank()) partes.add(previo)
            ultimo = m.range.last + 1
        }
        val cola = sinAcentos.substring(ultimo).trim()
        if (cola.isNotBlank()) partes.add(cola)
        return partes
    }

    private fun limpiarFragmento(raw: String): String {
        var out = TextoNormalizer.normalizar(raw)
            .replace(Regex("[¿?¡!\"'()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        out = PREFIJOS_RELLENO_REGEX.replace(out, "").trim()
        var anterior: String
        do {
            anterior = out
            out = SUFIJOS_RELLENO_REGEX.replace(out, "").trim()
        } while (out != anterior)
        return out
    }

    private fun segmentarNombresConsecutivos(
        tokens: List<String>,
        universo: List<TrabajadorConEstado>
    ): List<String> {
        val segmentos = mutableListOf<String>()
        val noReconocidosBuffer = mutableListOf<String>()

        fun vaciarNoReconocidos() {
            if (noReconocidosBuffer.isNotEmpty()) {
                // Si quedaron 4+ tokens desconocidos sin separar, dividir de a pares para reportar limpio
                if (noReconocidosBuffer.size >= 4) {
                    noReconocidosBuffer.chunked(2).forEach { par ->
                        segmentos.add(formatearDisplay(par.joinToString(" ")))
                    }
                } else {
                    segmentos.add(formatearDisplay(noReconocidosBuffer.joinToString(" ")))
                }
                noReconocidosBuffer.clear()
            }
        }

        var i = 0
        while (i < tokens.size) {
            var matchedLen = 0
            val maxLen = min(4, tokens.size - i)
            for (len in maxLen downTo 2) {
                val sub = tokens.subList(i, i + len)
                // Evitar cortar en un conector ("de", "la") como último token
                if (sub.last() in CONECTORES_INTERNOS_NOMBRES) continue
                val matches = buscarCoincidenciasSeguras(sub, universo)
                if (matches.isNotEmpty()) {
                    // Verificar si empezar justo en i+1 da un match aún más largo (para no absorber un token huérfano)
                    matchedLen = len
                    break
                }
            }

            if (matchedLen >= 2) {
                vaciarNoReconocidos()
                segmentos.add(formatearDisplay(tokens.subList(i, i + matchedLen).joinToString(" ")))
                i += matchedLen
            } else {
                noReconocidosBuffer.add(tokens[i])
                i += 1
            }
        }
        vaciarNoReconocidos()
        return segmentos
    }

    private fun evaluarSegmento(
        segmento: String,
        empresaObjetivo: EmpresaOficial?,
        maestroTurnoActivo: List<TrabajadorConEstado>,
        correccionesAprendidas: List<LucyCorreccionAprendida>
    ): LucyBatchMatchOutcome {
        val nombreDisplay = formatearDisplay(segmento)
        resolverPorCorreccionAprendida(
            segmento = segmento,
            empresaObjetivo = empresaObjetivo,
            maestroTurnoActivo = maestroTurnoActivo,
            correccionesAprendidas = correccionesAprendidas
        )?.let { outcome -> return outcome }

        val tokens = tokenizarConsulta(segmento)
        if (tokens.isEmpty()) {
            return LucyBatchMatchOutcome.NoEncontrado(nombreDisplay)
        }

        if (empresaObjetivo != null) {
            val dentroEmpresa = maestroTurnoActivo.filter { it.empresa == empresaObjetivo }
            val matchesEmpresa = buscarCoincidenciasSeguras(tokens, dentroEmpresa)
            if (matchesEmpresa.size == 1) {
                return LucyBatchMatchOutcome.Identificado(nombreDisplay, matchesEmpresa.first())
            }
            if (matchesEmpresa.size > 1) {
                return LucyBatchMatchOutcome.Ambiguo(nombreDisplay, matchesEmpresa)
            }

            val fueraEmpresa = maestroTurnoActivo.filter { it.empresa != empresaObjetivo }
            val matchesOtra = buscarCoincidenciasSeguras(tokens, fueraEmpresa)
            if (matchesOtra.isNotEmpty()) {
                return LucyBatchMatchOutcome.OtraEmpresa(
                    nombreSolicitado = nombreDisplay,
                    candidatos = matchesOtra,
                    empresasReales = matchesOtra.map { it.empresa }.distinct(),
                    empresaEsperada = empresaObjetivo
                )
            }
            return LucyBatchMatchOutcome.NoEncontrado(nombreDisplay)
        }

        val matchesTurno = buscarCoincidenciasSeguras(tokens, maestroTurnoActivo)
        return when {
            matchesTurno.size == 1 -> LucyBatchMatchOutcome.Identificado(nombreDisplay, matchesTurno.first())
            matchesTurno.size > 1 -> LucyBatchMatchOutcome.Ambiguo(nombreDisplay, matchesTurno)
            else -> LucyBatchMatchOutcome.NoEncontrado(nombreDisplay)
        }
    }

    private fun resolverPorCorreccionAprendida(
        segmento: String,
        empresaObjetivo: EmpresaOficial?,
        maestroTurnoActivo: List<TrabajadorConEstado>,
        correccionesAprendidas: List<LucyCorreccionAprendida>
    ): LucyBatchMatchOutcome? {
        if (correccionesAprendidas.isEmpty()) return null
        val clave = LucyMemoryStorageSerializer.normalizarClaveCorreccion(segmento)
        if (clave.isBlank()) return null

        val aplicables = correccionesAprendidas.filter { corr ->
            corr.claveErroneaNorm == clave &&
                corr.tipo != LucyTipoCorreccionAprendida.DATO_MAESTRO
        }
        if (aplicables.isEmpty()) return null

        val priorizadas = if (empresaObjetivo != null) {
            aplicables.filter { it.empresaCodigo == empresaObjetivo.codigo }.ifEmpty { aplicables }
        } else {
            aplicables
        }
        val candidatos = priorizadas.mapNotNull { corr ->
            maestroTurnoActivo.firstOrNull { it.id == corr.trabajadorId }
        }.distinctBy { it.id }

        if (candidatos.isEmpty()) return null
        val nombreDisplay = formatearDisplay(segmento)
        if (empresaObjetivo != null) {
            val enEmpresa = candidatos.filter { it.empresa == empresaObjetivo }
            if (enEmpresa.size == 1) {
                return LucyBatchMatchOutcome.Identificado(nombreDisplay, enEmpresa.first())
            }
            if (enEmpresa.size > 1) {
                return LucyBatchMatchOutcome.Ambiguo(nombreDisplay, enEmpresa)
            }
            val otras = candidatos.filter { it.empresa != empresaObjetivo }
            if (otras.isNotEmpty()) {
                return LucyBatchMatchOutcome.OtraEmpresa(
                    nombreSolicitado = nombreDisplay,
                    candidatos = otras,
                    empresasReales = otras.map { it.empresa }.distinct()
                )
            }
        }
        return if (candidatos.size == 1) {
            LucyBatchMatchOutcome.Identificado(nombreDisplay, candidatos.first())
        } else {
            LucyBatchMatchOutcome.Ambiguo(nombreDisplay, candidatos)
        }
    }

    private fun buscarCoincidenciasSeguras(
        tokensRaw: List<String>,
        universo: List<TrabajadorConEstado>
    ): List<TrabajadorConEstado> {
        if (tokensRaw.isEmpty() || universo.isEmpty()) return emptyList()

        val tokens = tokensRaw.filter { it.length >= 2 || it in CONECTORES_INTERNOS_NOMBRES }
        val tokensSignificativos = tokens.filter { it.length >= 2 && it !in CONECTORES_INTERNOS_NOMBRES }
        if (tokensSignificativos.isEmpty()) return emptyList()

        val consultaNorm = tokensSignificativos.joinToString(" ")
        val tokensFoneticos = tokensSignificativos.map { normalizarFoneticoEspanol(it) }

        val candidatos = mutableListOf<CandidateMatch>()
        for (trabajador in universo) {
            val idx = trabajador.searchIndex
            val pTokens = idx.tokensPersona.filter { it.length >= 2 && it !in CONECTORES_INTERNOS_NOMBRES }
            if (pTokens.isEmpty()) continue

            val exact = evaluarCoincidencia(
                consultaNorm = consultaNorm,
                tokensQuery = tokensSignificativos,
                tokensFoneticos = tokensFoneticos,
                trabajador = trabajador,
                personaTokens = pTokens
            )
            if (exact != null) {
                candidatos.add(exact)
            }
        }

        if (candidatos.isEmpty()) return emptyList()

        // Quedarse con el nivel de mayor precisión para evitar que una coincidencia aproximada
        // contamine un empate exacto o único.
        val mejorTier = candidatos.minBy { it.tier.ordinal }.tier
        val delMejorTier = candidatos.filter { it.tier == mejorTier }

        if (delMejorTier.size > 1 && mejorTier == MatchTier.EXACT_ALL) {
            return delMejorTier.map { it.trabajador }
        }
        if (mejorTier == MatchTier.EXACT_TOKENS) {
            // Si algún candidato coincide en TODOS los tokens del trabajador o tiene distancia menor de tokens extra, priorizarlo
            val minExtra = delMejorTier.minOf {
                (it.trabajador.searchIndex.tokensPersona.size - tokensSignificativos.size).coerceAtLeast(0)
            }
            val exactos = delMejorTier.filter {
                (it.trabajador.searchIndex.tokensPersona.size - tokensSignificativos.size).coerceAtLeast(0) == minExtra
            }
            if (exactos.isNotEmpty()) return exactos.map { it.trabajador }
        }

        if (mejorTier == MatchTier.FUZZY_CONTROLLED) {
            val minDist = delMejorTier.minOf { it.totalDistance }
            return delMejorTier.filter { it.totalDistance == minDist }.map { it.trabajador }
        }

        return delMejorTier.map { it.trabajador }
    }

    private fun evaluarCoincidencia(
        consultaNorm: String,
        tokensQuery: List<String>,
        tokensFoneticos: List<String>,
        trabajador: TrabajadorConEstado,
        personaTokens: List<String>
    ): CandidateMatch? {
        val idx = trabajador.searchIndex
        val apellidosNombre = listOf(idx.normPrimerApellido, idx.normSegundoApellido, idx.normNombre)
            .filter { it.isNotBlank() }.joinToString(" ")
        val nombreApellidos = listOf(idx.normNombre, idx.normPrimerApellido, idx.normSegundoApellido)
            .filter { it.isNotBlank() }.joinToString(" ")
        val nombrePrimerApellido = listOf(idx.normNombre, idx.normPrimerApellido)
            .filter { it.isNotBlank() }.joinToString(" ")
        val primerApellidoNombre = listOf(idx.normPrimerApellido, idx.normNombre)
            .filter { it.isNotBlank() }.joinToString(" ")

        if (consultaNorm == apellidosNombre ||
            consultaNorm == nombreApellidos ||
            consultaNorm == nombrePrimerApellido ||
            consultaNorm == primerApellidoNombre
        ) {
            return CandidateMatch(
                trabajador = trabajador,
                tier = MatchTier.EXACT_ALL,
                exactAndPhoneticCount = tokensQuery.size,
                totalDistance = 0
            )
        }

        // Emparejamiento 1-a-1 sin reutilizar el mismo token de persona dos veces
        val usados = BooleanArray(personaTokens.size)
        var exactCount = 0
        var phoneticCount = 0
        var fuzzyCount = 0
        var totalDist = 0

        for (qIdx in tokensQuery.indices) {
            val q = tokensQuery[qIdx]
            val qPhon = tokensFoneticos[qIdx]

            val idxExact = (personaTokens.indices).firstOrNull { !usados[it] && personaTokens[it] == q }
            if (idxExact != null) {
                usados[idxExact] = true
                exactCount++
                continue
            }

            val idxPhon = (personaTokens.indices).firstOrNull {
                !usados[it] && normalizarFoneticoEspanol(personaTokens[it]) == qPhon
            }
            if (idxPhon != null) {
                usados[idxPhon] = true
                phoneticCount++
                continue
            }

            // En consultas de 1 solo término NO permitimos distancia de edición fuzzy para evitar falsos positivos;
            // los equivalentes exactos y fonéticos sí se permiten (p. ej. "Brian" <-> "Bryan").
            if (tokensQuery.size == 1) {
                return null
            }

            // Levenshtein controlado (<=1 edición) solo en tokens largos (>=5 chars) y máx. 1 token fuzzy
            if (q.length >= 5 && fuzzyCount == 0) {
                val idxFuzzy = (personaTokens.indices).firstOrNull { pIdx ->
                    !usados[pIdx] &&
                        personaTokens[pIdx].length >= 5 &&
                        levenshtein(q, personaTokens[pIdx]) <= 1
                }
                if (idxFuzzy != null) {
                    usados[idxFuzzy] = true
                    fuzzyCount++
                    totalDist += levenshtein(q, personaTokens[idxFuzzy])
                    continue
                }
            }

            return null
        }

        val tier = when {
            exactCount == tokensQuery.size -> MatchTier.EXACT_TOKENS
            fuzzyCount == 0 -> MatchTier.PHONETIC_TOKENS
            exactCount + phoneticCount >= 1 -> MatchTier.FUZZY_CONTROLLED
            else -> return null
        }

        return CandidateMatch(
            trabajador = trabajador,
            tier = tier,
            exactAndPhoneticCount = exactCount + phoneticCount,
            totalDistance = totalDist
        )
    }

    internal fun normalizarFoneticoEspanol(token: String): String {
        var s = TextoNormalizer.normalizar(token)
        if (s.isEmpty()) return s
        EQUIVALENCIAS_STT_TOKENS[s]?.let { s = it }
        // Preservar "ch" antes de remover h muda
        s = s.replace("ch", "\u0001")
        s = s.replace("ph", "f")
        s = s.replace("qu", "k")
        s = s.replace("v", "b")
        s = s.replace("w", "b")
        s = s.replace("ll", "y")
        s = s.replace(Regex("(?<=[bcdfghjklmnpqrstvwxz])y(?=[bcdfghjklmnpqrstvwxz])"), "i")
        s = s.replace("z", "s")
        s = s.replace(Regex("c([ei])"), "s$1")
        s = s.replace(Regex("c([aou])"), "k$1")
        s = s.replace(Regex("g([ei])"), "j$1")
        s = s.replace("ñ", "n")
        s = s.replace("h", "")
        s = s.replace("\u0001", "ch")
        s = s.replace(Regex("(.)\\1+"), "$1")
        return s
    }

    private fun tokenizarConsulta(texto: String): List<String> {
        return TextoNormalizer.normalizar(texto)
            .replace(Regex("[^a-z0-9ñ\\s]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
    }

    private fun formatearDisplay(texto: String): String {
        return texto.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { palabra ->
                if (palabra in CONECTORES_INTERNOS_NOMBRES) palabra
                else palabra.replaceFirstChar { ch -> ch.uppercase() }
            }
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = temp
            }
        }
        return dp[n]
    }
}
