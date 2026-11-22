package com.example.domain.model

/**
 * Índice precalculado por trabajador para evitar normalizaciones repetidas
 * durante la escritura rápida en el buscador y desplazamientos de lista.
 */
internal class WorkerSearchIndex(
    trabajador: TrabajadorConEstado
) {
    val normPrimerApellido: String = TextoNormalizer.normalizar(trabajador.primerApellido)
    val normSegundoApellido: String = TextoNormalizer.normalizar(trabajador.segundoApellido)
    val normNombre: String = TextoNormalizer.normalizar(trabajador.nombre)

    val tokensApellidos: List<String> = buildList {
        normPrimerApellido.split(' ').filterTo(this) { it.isNotBlank() }
        normSegundoApellido.split(' ').filterTo(this) { it.isNotBlank() }
    }
    val tokensNombre: List<String> = normNombre.split(' ').filter { it.isNotBlank() }
    val tokensPersona: List<String> = buildList(tokensApellidos.size + tokensNombre.size) {
        addAll(tokensApellidos)
        addAll(tokensNombre)
    }

    val normHabitacion: String = TextoNormalizer.normalizar(trabajador.habitacion)
    val normPabellon: String = TextoNormalizer.normalizar(trabajador.pabellon)
    val tokensPabellon: List<String> = normPabellon.split(' ').filter { it.isNotBlank() }

    val normEmpresa: String = TextoNormalizer.normalizar(trabajador.empresa.nombre)
    val tokensEmpresa: List<String> = normEmpresa.split(' ').filter { it.isNotBlank() }

    val habNumeroNatural: Int = Regex("^\\d+").find(normHabitacion)?.value?.toIntOrNull() ?: Int.MAX_VALUE
}

data class QueryAnalizada(
    val rawNormalized: String,
    val roomOnlyTokens: List<String>,
    val pavilionOnlyPhrases: List<String>,
    val generalTokens: List<String>
) {
    val estaVacia: Boolean
        get() = roomOnlyTokens.isEmpty() && pavilionOnlyPhrases.isEmpty() && generalTokens.isEmpty()
}

object BuscadorTrabajadores {

    private val ORIENTACIONES_PABELLON = setOf(
        "sur", "norte", "este", "oeste", "oriente", "poniente", "centro", "alto", "bajo"
    )
    private val PREFIJOS_HABITACION = setOf("hab", "habitacion")
    private val PREFIJOS_PABELLON = setOf("pab", "pabellon")
    private val SEPARADORES_REGEX = Regex("[\\-/,·.]+")
    private val ESPACIOS_REGEX = Regex("\\s+")

    fun analizarQuery(query: String): QueryAnalizada {
        val base = TextoNormalizer.normalizar(query)
        if (base.isBlank()) {
            return QueryAnalizada("", emptyList(), emptyList(), emptyList())
        }

        val limpio = base
            .replace(SEPARADORES_REGEX, " ")
            .replace(ESPACIOS_REGEX, " ")
            .trim()
        if (limpio.isBlank()) {
            return QueryAnalizada("", emptyList(), emptyList(), emptyList())
        }

        val rawTokens = limpio.split(' ').filter { it.isNotBlank() }
        val roomOnly = mutableListOf<String>()
        val pavilionOnly = mutableListOf<String>()
        val general = mutableListOf<String>()

        var i = 0
        while (i < rawTokens.size) {
            val tok = rawTokens[i]
            when {
                tok in PREFIJOS_HABITACION && i + 1 < rawTokens.size -> {
                    roomOnly.add(rawTokens[i + 1])
                    i += 2
                }
                tok in PREFIJOS_PABELLON && i + 1 < rawTokens.size -> {
                    if (i + 2 < rawTokens.size && rawTokens[i + 2] in ORIENTACIONES_PABELLON) {
                        pavilionOnly.add("${rawTokens[i + 1]} ${rawTokens[i + 2]}")
                        i += 3
                    } else {
                        pavilionOnly.add(rawTokens[i + 1])
                        i += 2
                    }
                }
                i + 1 < rawTokens.size &&
                    rawTokens[i + 1] in ORIENTACIONES_PABELLON &&
                    esIdentificadorUbicacion(tok) -> {
                    pavilionOnly.add("$tok ${rawTokens[i + 1]}")
                    i += 2
                }
                else -> {
                    general.add(tok)
                    i += 1
                }
            }
        }

        return QueryAnalizada(
            rawNormalized = limpio,
            roomOnlyTokens = roomOnly,
            pavilionOnlyPhrases = pavilionOnly,
            generalTokens = general
        )
    }

    /**
     * Filtra y ordena por relevancia cuando la búsqueda no está vacía.
     * Si la búsqueda está vacía, devuelve la lista original sin reordenar ni copiar.
     */
    fun filtrarYOrdenar(
        trabajadores: List<TrabajadorConEstado>,
        query: String
    ): List<TrabajadorConEstado> {
        val analizada = analizarQuery(query)
        if (analizada.estaVacia) return trabajadores

        val matches = ArrayList<Pair<Int, TrabajadorConEstado>>()
        for (t in trabajadores) {
            val score = calcularRelevancia(t, analizada)
            if (score > 0) {
                matches.add(score to t)
            }
        }
        if (matches.size > 1) {
            matches.sortWith { a, b -> b.first.compareTo(a.first) }
        }
        return matches.map { it.second }
    }

    fun coincide(trabajador: TrabajadorConEstado, query: String): Boolean {
        val analizada = analizarQuery(query)
        if (analizada.estaVacia) return true
        return calcularRelevancia(trabajador, analizada) > 0
    }

    fun calcularRelevancia(trabajador: TrabajadorConEstado, query: QueryAnalizada): Int {
        if (query.estaVacia) return 1
        val idx = trabajador.searchIndex
        var scoreTotal = 0

        // 1) Restricciones explícitas de pabellón (ej. "18 SUR", "pab 4 sur") -> NO confundir con habitación
        for (frase in query.pavilionOnlyPhrases) {
            val pabScore = scoreFrasePabellon(idx, frase)
            if (pabScore == 0) return 0
            scoreTotal += pabScore
        }

        // 2) Restricciones explícitas de habitación (ej. "hab 25c")
        for (habTok in query.roomOnlyTokens) {
            val habScore = scoreHabitacion(idx.normHabitacion, habTok)
            if (habScore == 0) return 0
            scoreTotal += habScore
        }

        if (query.generalTokens.isEmpty()) {
            return scoreTotal
        }

        // 3) Tokens generales: soporta nombre/apellidos en cualquier orden, parciales,
        //    habitaciones alfanuméricas, pabellones y empresa sin confundir habitación vs pabellón.
        val personaUsedIndices = HashSet<Int>(query.generalTokens.size)
        var matchedHabOnce = false
        var matchedPabTokens = 0
        var soloUbicacionHab = 0
        var soloUbicacionPab = 0

        for (tok in query.generalTokens) {
            val personaMatch = mejorMatchEnLista(idx.tokensPersona, tok, personaUsedIndices)
            val scoreP = personaMatch.first
            val scoreH = scoreHabitacion(idx.normHabitacion, tok)
            val scorePab = scorePabellonToken(idx.tokensPabellon, tok)
            val scoreEmp = scoreEmpresaToken(idx.tokensEmpresa, idx.normEmpresa, tok)

            val mejor = maxOf(scoreP, scoreH, scorePab, scoreEmp)
            if (mejor == 0) {
                return 0
            }

            // Si un token solo puede venir de habitación/pabellón, asignar campo de manera consistente
            val puedeSerPersonaOEmpresa = scoreP > 0 || scoreEmp > 0
            if (!puedeSerPersonaOEmpresa && scoreH > 0 && scorePab == 0) {
                if (matchedHabOnce && query.generalTokens.none { it == idx.normHabitacion }) {
                    // Evita aceptar dos tokens de habitación incompatibles en la misma celda
                    return 0
                }
                matchedHabOnce = true
                soloUbicacionHab++
                scoreTotal += scoreH
            } else if (!puedeSerPersonaOEmpresa && scorePab > 0 && scoreH == 0) {
                matchedPabTokens++
                soloUbicacionPab++
                scoreTotal += scorePab
            } else if (!puedeSerPersonaOEmpresa && scoreH > 0 && scorePab > 0) {
                // Token numérico que coincide con ambos (ej. hab=18 y pab=18 SUR):
                // si ya se usó habitación, asignarlo a pabellón y viceversa
                if (matchedHabOnce) {
                    matchedPabTokens++
                    scoreTotal += scorePab
                } else {
                    matchedHabOnce = true
                    scoreTotal += scoreH
                }
            } else {
                // Coincidencia con persona / empresa (o mixta)
                if (personaMatch.second >= 0) {
                    personaUsedIndices.add(personaMatch.second)
                    scoreTotal += scoreP + 10
                } else {
                    scoreTotal += mejor
                }
            }
        }

        // Bonificación si coincide exactamente en orden estándar o invertido de persona
        if (personaUsedIndices.size >= 2) {
            scoreTotal += 25
        }
        // Bonificación si coincide con habitación + pabellón simultáneamente
        if ((matchedHabOnce || query.roomOnlyTokens.isNotEmpty()) &&
            (matchedPabTokens > 0 || query.pavilionOnlyPhrases.isNotEmpty())
        ) {
            scoreTotal += 40
        }

        return scoreTotal
    }

    private fun esIdentificadorUbicacion(token: String): Boolean {
        return token.any { it.isDigit() } || token.length <= 2
    }

    private fun scoreFrasePabellon(idx: WorkerSearchIndex, frase: String): Int {
        if (idx.normPabellon.isBlank()) return 0
        if (idx.normPabellon == frase) return 110
        val tokensFrase = frase.split(' ').filter { it.isNotBlank() }
        if (tokensFrase.isEmpty()) return 0
        if (tokensFrase.size == 1) {
            return scorePabellonToken(idx.tokensPabellon, tokensFrase[0])
        }
        // Deben aparecer todos los tokens dentro de pabellón y en secuencia contigua para no mezclar
        val tPab = idx.tokensPabellon
        if (tPab.size < tokensFrase.size) return 0
        for (start in 0..(tPab.size - tokensFrase.size)) {
            var allOk = true
            for (k in tokensFrase.indices) {
                val cand = tPab[start + k]
                val q = tokensFrase[k]
                val coincideToken = if (q.any { it.isDigit() }) {
                    cand == q
                } else {
                    cand == q || (q.length >= 2 && cand.startsWith(q))
                }
                if (!coincideToken) {
                    allOk = false
                    break
                }
            }
            if (allOk) return 95
        }
        return 0
    }

    private fun scoreHabitacion(normHab: String, tok: String): Int {
        if (normHab.isBlank()) return 0
        if (normHab == tok) return 80
        // Para alfanuméricos o prefijos numéricos (ej. "25" -> "25C", "10" -> "10B", "25c" -> "25c"):
        // Nunca confundir "4" con "14" o "24" (sólo prefijo desde inicio de habitación)
        if (normHab.startsWith(tok)) {
            return 45
        }
        return 0
    }

    private fun scorePabellonToken(tokensPab: List<String>, tok: String): Int {
        if (tokensPab.isEmpty()) return 0
        val tieneDigito = tok.any { it.isDigit() }
        var mejor = 0
        for (cand in tokensPab) {
            val s = when {
                cand == tok -> 60
                !tieneDigito && cand.startsWith(tok) -> 30
                !tieneDigito && tok.length >= 2 && cand.contains(tok) -> 15
                else -> 0
            }
            if (s > mejor) mejor = s
        }
        return mejor
    }

    private fun scoreEmpresaToken(tokensEmp: List<String>, normEmp: String, tok: String): Int {
        if (normEmp.isBlank()) return 0
        var mejor = 0
        for (cand in tokensEmp) {
            val s = when {
                cand == tok -> 40
                cand.startsWith(tok) -> 22
                tok.length >= 2 && cand.contains(tok) -> 10
                else -> 0
            }
            if (s > mejor) mejor = s
        }
        return mejor
    }

    private fun mejorMatchEnLista(
        tokens: List<String>,
        queryTok: String,
        usados: Set<Int>
    ): Pair<Int, Int> {
        var mejorScore = 0
        var mejorIdx = -1
        for (i in tokens.indices) {
            val cand = tokens[i]
            val baseScore = when {
                cand == queryTok -> 55
                cand.startsWith(queryTok) -> 35
                queryTok.length >= 2 && cand.contains(queryTok) -> 18
                else -> 0
            }
            if (baseScore == 0) continue
            // Priorizar palabras aún no consumidas por otro token de la búsqueda
            val adjusted = if (i !in usados) baseScore + 10 else baseScore
            if (adjusted > mejorScore) {
                mejorScore = adjusted
                mejorIdx = i
            }
        }
        return mejorScore to mejorIdx
    }
}
