package com.example.domain.model

import com.example.data.local.entities.CorreccionOcrEntity
import kotlin.math.max
import kotlin.math.min

/**
 * Motor de emparejamiento OCR seguro por (Turno + Empresa).
 * - Solo produce confirmación automática cuando existe coincidencia en memoria confirmada
 *   o coincidencia exacta ÚNICA dentro de la empresa y turno seleccionados.
 * - Cualquier coincidencia aproximada, incompleta, homónima o ambigua pasa a Pendientes.
 * - Nunca modifica los datos del listado maestro.
 */
object OcrNombreMatcher {

    private val PALABRAS_RUIDO = setOf(
        "lavanderia", "control", "listado", "empresa", "turno", "habitacion", "hab",
        "pabellon", "pab", "fecha", "pagina", "pag", "total", "observaciones", "firma",
        "cargo", "cargos", "prendas", "ropa", "camion", "descarga", "recepcion",
        "nombre", "apellidos", "apellido", "si", "no", "bajo", "recibido",
        "komatsu", "esop", "fluitek", "somacor", "joy", "global", "finning", "cummins", "pala"
    )

    private val ORIENTACIONES = setOf(
        "sur", "norte", "este", "oeste", "oriente", "poniente", "centro", "alto", "bajo"
    )

    private val PREFIJO_NUMERACION_REGEX = Regex("^\\s*(?:n[°º]\\.?\\s*|nro\\.?\\s*|#\\s*)?\\d{1,4}(?:\\s*[.)\\-:]+\\s*|\\s+)", RegexOption.IGNORE_CASE)
    private val FILA_ILEGIBLE_REGEX = Regex("^\\[FILA\\s+(\\d+)\\s+ILEGIBLE]$", RegexOption.IGNORE_CASE)
    private val SEPARADORES_LIMPIEZA_REGEX = Regex("[,;:\\-_/|·•*]+")
    private val ESPACIOS_REGEX = Regex("\\s+")
    private val TIENE_LETRAS_REGEX = Regex("[a-zñ]+")
    private val TOKEN_ALFANUM_UBICACION_REGEX = Regex("^\\d{1,4}[a-z]?$")

    fun normalizarClaveOcr(textoCrudo: String): String {
        val base = TextoNormalizer.normalizar(textoCrudo)
            .replace(SEPARADORES_LIMPIEZA_REGEX, " ")
            .replace(ESPACIOS_REGEX, " ")
            .trim()
        return base
    }

    fun limpiarTextoNombreDesdeLinea(lineaCruda: String): Pair<String, String?> {
        val sinPrefijo = lineaCruda.trim().replace(PREFIJO_NUMERACION_REGEX, "")
        val norm = normalizarClaveOcr(sinPrefijo)
        if (norm.isBlank()) return "" to null

        val rawTokens = norm.split(' ').filter { it.isNotBlank() }
        val nombreTokens = mutableListOf<String>()
        var pistaHabitacion: String? = null

        var i = 0
        while (i < rawTokens.size) {
            val tok = rawTokens[i]
            when {
                (tok == "hab" || tok == "habitacion") && i + 1 < rawTokens.size -> {
                    pistaHabitacion = rawTokens[i + 1]
                    i += 2
                }
                (tok == "pab" || tok == "pabellon") && i + 1 < rawTokens.size -> {
                    if (i + 2 < rawTokens.size && rawTokens[i + 2] in ORIENTACIONES) {
                        i += 3
                    } else {
                        i += 2
                    }
                }
                i + 1 < rawTokens.size &&
                    rawTokens[i + 1] in ORIENTACIONES &&
                    TOKEN_ALFANUM_UBICACION_REGEX.matches(tok) -> {
                    i += 2
                }
                TOKEN_ALFANUM_UBICACION_REGEX.matches(tok) -> {
                    if (pistaHabitacion == null && nombreTokens.isNotEmpty()) {
                        pistaHabitacion = tok
                    }
                    i += 1
                }
                tok in PALABRAS_RUIDO -> {
                    i += 1
                }
                else -> {
                    if (TIENE_LETRAS_REGEX.containsMatchIn(tok)) {
                        val soloLetrasYConfusiones = tok.replace(Regex("[^a-zñ0-9]"), "")
                        if (soloLetrasYConfusiones.length >= 2) {
                            nombreTokens.add(soloLetrasYConfusiones)
                        }
                    }
                    i += 1
                }
            }
        }

        return nombreTokens.joinToString(" ") to pistaHabitacion
    }

    fun analizarLineas(
        lineasCrudas: List<String>,
        trabajadoresEmpresaTurno: List<TrabajadorConEstado>,
        correccionesEmpresaTurno: List<CorreccionOcrEntity>
    ): ResultadoAnalisisOcr {
        val mapaTrabajadoresPorId = trabajadoresEmpresaTurno.associateBy { it.id }
        val mapaMemoriaPorClave = correccionesEmpresaTurno.associateBy { it.textoOcrNormalizado }

        val autoConfirmados = mutableListOf<DeteccionAutoOcr>()
        val pendientes = mutableListOf<DeteccionPendienteOcr>()
        val autoIdsYaVistos = HashSet<String>()
        val clavesPendientesVistas = HashSet<String>()
        var ruidoDescartado = 0

        for (lineaBruta in lineasCrudas) {
            val lineaOriginal = lineaBruta.trim().replace(ESPACIOS_REGEX, " ")
            if (lineaOriginal.isBlank()) continue

            val filaIlegibleMatch = FILA_ILEGIBLE_REGEX.matchEntire(lineaOriginal)
            if (filaIlegibleMatch != null) {
                val numFila = filaIlegibleMatch.groupValues[1]
                val claveFila = "fila $numFila ilegible"
                if (clavesPendientesVistas.add(claveFila)) {
                    pendientes.add(
                        DeteccionPendienteOcr(
                            textoOcrOriginal = "FILA $numFila (LECTURA DUDOSA)",
                            textoOcrNormalizado = claveFila,
                            candidatos = emptyList(),
                            motivo = "Fila #$numFila ilegible en la planilla: revisa con zoom o asigna manualmente."
                        )
                    )
                }
                continue
            }

            val claveCompleta = normalizarClaveOcr(lineaOriginal)
            val (claveNombre, pistaHabitacion) = limpiarTextoNombreDesdeLinea(lineaOriginal)
            val claveBusqueda = claveNombre.ifBlank { claveCompleta }

            if (!esLineaCandidataDeNombre(claveBusqueda)) {
                ruidoDescartado++
                continue
            }

            // 1. Memoria confirmada para (turno + empresa)
            val enMemoria = mapaMemoriaPorClave[claveBusqueda] ?: mapaMemoriaPorClave[claveCompleta]
            val trabajadorMemoria = enMemoria?.let { mapaTrabajadoresPorId[it.trabajadorId] }
            if (trabajadorMemoria != null) {
                if (autoIdsYaVistos.add(trabajadorMemoria.id)) {
                    autoConfirmados.add(
                        DeteccionAutoOcr(
                            textoOcrOriginal = lineaOriginal,
                            textoOcrNormalizado = claveBusqueda,
                            trabajador = trabajadorMemoria,
                            viaMemoria = true
                        )
                    )
                }
                continue
            }

            val tokensLinea = claveBusqueda.split(' ').filter { it.length >= 2 }
            if (tokensLinea.isEmpty()) {
                ruidoDescartado++
                continue
            }

            // 2. Coincidencia exacta única (solo con >= 2 tokens para evitar marcar por un solo nombre/apellido)
            if (tokensLinea.size >= 2) {
                val exactos = buscarCoincidenciasExactas(tokensLinea, trabajadoresEmpresaTurno)
                if (exactos.size == 1) {
                    val unico = exactos.first()
                    if (autoIdsYaVistos.add(unico.id)) {
                        autoConfirmados.add(
                            DeteccionAutoOcr(
                                textoOcrOriginal = lineaOriginal,
                                textoOcrNormalizado = claveBusqueda,
                                trabajador = unico,
                                viaMemoria = false
                            )
                        )
                    }
                    continue
                } else if (exactos.size > 1) {
                    // Posible homónimo: siempre a Pendientes para confirmar manualmente
                    if (clavesPendientesVistas.add(claveBusqueda)) {
                        val desempateHab = ordenarPorPistaHabitacion(exactos, pistaHabitacion)
                        pendientes.add(
                            DeteccionPendienteOcr(
                                textoOcrOriginal = lineaOriginal,
                                textoOcrNormalizado = claveBusqueda,
                                candidatos = desempateHab.map { CandidatoPendienteOcr(it, 1.0) },
                                motivo = "Posible homónimo: ${exactos.size} coincidencias en la empresa"
                            )
                        )
                    }
                    continue
                }
            }

            // 3. Coincidencia aproximada / incompleta -> SIEMPRE a Pendientes
            val sugeridos = buscarCandidatosSimilares(
                tokensLinea = tokensLinea,
                pistaHabitacion = pistaHabitacion,
                trabajadores = trabajadoresEmpresaTurno
            )
            if (clavesPendientesVistas.add(claveBusqueda)) {
                val motivo = when {
                    tokensLinea.size == 1 && sugeridos.isNotEmpty() ->
                        "Nombre o apellido incompleto (${tokensLinea.first().uppercase()})"
                    sugeridos.isNotEmpty() ->
                        "Lectura dudosa / aproximada: requiere confirmación"
                    else ->
                        "No encontrado en la empresa seleccionada"
                }
                pendientes.add(
                    DeteccionPendienteOcr(
                        textoOcrOriginal = lineaOriginal,
                        textoOcrNormalizado = claveBusqueda,
                        candidatos = sugeridos,
                        motivo = motivo
                    )
                )
            }
        }

        return ResultadoAnalisisOcr(
            autoConfirmados = autoConfirmados,
            pendientes = pendientes,
            lineasDescartadasRuido = ruidoDescartado
        )
    }

    fun buscarCandidatosParaTextoPendiente(
        textoPendiente: String,
        trabajadoresEmpresaTurno: List<TrabajadorConEstado>
    ): List<CandidatoPendienteOcr> {
        val (claveNombre, pistaHab) = limpiarTextoNombreDesdeLinea(textoPendiente)
        val clave = claveNombre.ifBlank { normalizarClaveOcr(textoPendiente) }
        val tokens = clave.split(' ').filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()
        return buscarCandidatosSimilares(tokens, pistaHab, trabajadoresEmpresaTurno)
    }

    private fun esLineaCandidataDeNombre(claveNombre: String): Boolean {
        if (claveNombre.isBlank()) return false
        val tokens = claveNombre.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val letrasTotales = tokens.sumOf { t -> t.count { it in 'a'..'z' || it == 'ñ' } }
        if (letrasTotales < 3) return false
        return tokens.any { tok -> tok.count { it in 'a'..'z' || it == 'ñ' } >= 3 }
    }

    private fun buscarCoincidenciasExactas(
        tokensLinea: List<String>,
        trabajadores: List<TrabajadorConEstado>
    ): List<TrabajadorConEstado> {
        val coincidenciasCompletas = mutableListOf<TrabajadorConEstado>()
        val coincidenciasBaseSinSegundoApellido = mutableListOf<TrabajadorConEstado>()

        for (t in trabajadores) {
            val idx = t.searchIndex
            val personaTokens = idx.tokensPersona
            if (personaTokens.isEmpty()) continue

            if (tokensCoincidenExactamenteSinOrden(tokensLinea, personaTokens)) {
                coincidenciasCompletas.add(t)
                continue
            }

            // Si el papel trae PrimerApellido + Nombre(s) (sin segundo apellido)
            if (idx.normSegundoApellido.isNotBlank()) {
                val tokensBase = buildList {
                    idx.normPrimerApellido.split(' ').filterTo(this) { it.isNotBlank() }
                    addAll(idx.tokensNombre)
                }
                if (tokensBase.isNotEmpty() && tokensCoincidenExactamenteSinOrden(tokensLinea, tokensBase)) {
                    coincidenciasBaseSinSegundoApellido.add(t)
                }
            }
        }

        return if (coincidenciasCompletas.isNotEmpty()) {
            coincidenciasCompletas
        } else {
            coincidenciasBaseSinSegundoApellido
        }
    }

    private fun tokensCoincidenExactamenteSinOrden(
        tokensA: List<String>,
        tokensB: List<String>
    ): Boolean {
        if (tokensA.size != tokensB.size) return false
        val restantes = tokensB.toMutableList()
        for (a in tokensA) {
            val pos = restantes.indexOf(a)
            if (pos < 0) return false
            restantes.removeAt(pos)
        }
        return restantes.isEmpty()
    }

    private fun ordenarPorPistaHabitacion(
        candidatos: List<TrabajadorConEstado>,
        pistaHabitacion: String?
    ): List<TrabajadorConEstado> {
        if (pistaHabitacion.isNullOrBlank()) return candidatos
        return candidatos.sortedByDescending {
            if (it.searchIndex.normHabitacion == pistaHabitacion) 1 else 0
        }
    }

    private fun buscarCandidatosSimilares(
        tokensLinea: List<String>,
        pistaHabitacion: String?,
        trabajadores: List<TrabajadorConEstado>
    ): List<CandidatoPendienteOcr> {
        val tokensOcrNorm = tokensLinea.map(::normalizarConfusionesOcr)
        val resultados = mutableListOf<CandidatoPendienteOcr>()

        for (t in trabajadores) {
            val personaTokens = t.searchIndex.tokensPersona
            if (personaTokens.isEmpty()) continue

            var suma = 0.0
            for (tokOcr in tokensOcrNorm) {
                var mejorToken = 0.0
                for (tokPersona in personaTokens) {
                    val personaConf = normalizarConfusionesOcr(tokPersona)
                    val sim = when {
                        tokOcr == personaConf -> 1.0
                        personaConf.startsWith(tokOcr) && tokOcr.length >= 3 -> 0.90
                        tokOcr.startsWith(personaConf) && personaConf.length >= 3 -> 0.88
                        else -> similitudLevenshtein(tokOcr, personaConf)
                    }
                    if (sim > mejorToken) mejorToken = sim
                }
                suma += mejorToken
            }

            var score = suma / tokensOcrNorm.size.toDouble()
            if (tokensLinea.size == 1) {
                // Penalizar suavemente cuando viene un solo token para no inflar falsos altos
                score *= 0.85
            }
            if (!pistaHabitacion.isNullOrBlank() && t.searchIndex.normHabitacion == pistaHabitacion) {
                score = min(0.99, score + 0.08)
            }

            if (score >= 0.58) {
                resultados.add(CandidatoPendienteOcr(trabajador = t, puntajeSimilitud = score))
            }
        }

        return resultados
            .sortedWith(
                compareByDescending<CandidatoPendienteOcr> { it.puntajeSimilitud }
                    .thenBy { it.trabajador.searchIndex.normPrimerApellido }
                    .thenBy { it.trabajador.searchIndex.normNombre }
            )
            .take(4)
    }

    private fun normalizarConfusionesOcr(token: String): String {
        // Primero reemplazos de dígitos confundidos con letras
        val sb = StringBuilder(token.length)
        for (c in token) {
            sb.append(
                when (c) {
                    '0' -> 'o'
                    '1' -> 'i'
                    '2' -> 'z'
                    '3' -> 'e'
                    '4' -> 'a'
                    '5' -> 's'
                    '6' -> 'g'
                    '7' -> 't'
                    '8' -> 'b'
                    '9' -> 'g'
                    else -> c
                }
            )
        }
        // Luego normalización fonética/cursiva española SOLO para rankear sugerencias en Pendientes
        return sb.toString()
            .replace("rn", "m")
            .replace("cl", "d")
            .replace("vv", "w")
            .replace("ll", "y")
            .replace("ñ", "n")
            .replace('v', 'b')
            .replace('z', 's')
    }

    private fun similitudLevenshtein(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        val dist = levenshtein(a, b)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
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
