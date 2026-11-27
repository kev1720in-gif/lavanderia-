package com.example.lucy.vision

import com.example.domain.model.EmpresaOficial
import com.example.domain.model.OcrNombreMatcher
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.lucy.memory.LucyCorreccionAprendida
import com.example.lucy.memory.LucyMemoryStorageSerializer
import com.example.lucy.memory.LucyTipoCorreccionAprendida
import kotlin.math.max
import kotlin.math.min

/**
 * Motor determinístico de correlación de lectura visual contra el maestro real de un turno.
 * - Solo analiza y propone candidatos: jamás escribe en Room ni confirma identidades.
 * - Distingue explícitamente datos reconocidos literalmente vs datos inferidos.
 * - Detecta duplicados repetidos en la planilla, homónimos, ambigüedades y lecturas incompletas.
 */
object LucyVisionMatcher {

    fun correlacionarConMaestro(
        parsed: LucyVisionParsedSheet,
        turnoContexto: Turno,
        empresaSugerida: EmpresaOficial?,
        trabajadoresTurno: List<TrabajadorConEstado>,
        motorUtilizado: String,
        correccionesAprendidas: List<LucyCorreccionAprendida> = emptyList(),
        strictCompanyFilter: Boolean = false
    ): LucyVisionPlanillaAnalysis {
        // Garantía estricta de separación por turno
        val maestroDelTurno = trabajadoresTurno.filter { it.turno == turnoContexto }

        val empresaDocReconocidaOficial = resolverEmpresaLiteral(parsed.detectedCompanyText)
        val empresaDocumentoInferida = if (strictCompanyFilter && empresaSugerida != null) {
            empresaSugerida
        } else {
            empresaDocReconocidaOficial ?: empresaSugerida
        }

        if (!parsed.isReadable || parsed.legibilityStatus == "ILEGIBLE" || parsed.rows.isEmpty()) {
            val notas = parsed.documentNotes.ifEmpty {
                listOf("La fotografía no es legible o no contiene filas operativas claras para leer.")
            }
            return LucyVisionPlanillaAnalysis(
                turnoContexto = turnoContexto,
                estadoDocumento = LucyVisionDocumentStatus.ILEGIBLE,
                motorUtilizado = motorUtilizado,
                esDocumentoOperativoValido = false,
                tipoEscrituraDocumento = parsed.documentTextType,
                empresaDocumentoReconocida = parsed.detectedCompanyText,
                empresaDocumentoInferida = empresaDocumentoInferida,
                resumenGeneral = "Imagen ilegible en ${turnoContexto.nombre}: ${notas.first()}",
                trabajadoresDetectados = emptyList(),
                posiblesDuplicados = emptyList(),
                dudasGlobales = notas,
                notasIlegibilidad = notas,
                requiereRevisionHumana = true,
                esResultadoPropuestoNoConfirmado = true,
                empresaFiltroAplicada = if (strictCompanyFilter) empresaSugerida else null,
                parsedSheet = parsed
            )
        }

        val deteccionesPreliminares = mutableListOf<LucyVisionWorkerDetection>()
        val dudasGlobales = parsed.documentNotes.toMutableList()
        val notasIlegibilidad = mutableListOf<String>()

        if (strictCompanyFilter && empresaSugerida != null) {
            val hayPersonalEmpresa = maestroDelTurno.any { it.empresa == empresaSugerida }
            if (!hayPersonalEmpresa) {
                dudasGlobales.add(
                    "No hay trabajadores registrados para ${empresaSugerida.nombre} en ${turnoContexto.nombre}."
                )
            }
        }

        if (parsed.detectedCompanyText != null && empresaDocReconocidaOficial == null) {
            dudasGlobales.add(
                "El encabezado dice \"${parsed.detectedCompanyText}\", pero no coincide exactamente con una empresa oficial."
            )
        }

        parsed.rows.forEachIndexed { idx, row ->
            val det = construirDeteccionFila(
                indice = idx + 1,
                row = row,
                empresaDocReconocidaLiteral = parsed.detectedCompanyText,
                empresaDocOficial = empresaDocReconocidaOficial,
                empresaSugerida = empresaSugerida,
                maestroDelTurno = maestroDelTurno,
                correccionesAprendidas = correccionesAprendidas,
                strictCompanyFilter = strictCompanyFilter
            )
            deteccionesPreliminares.add(det)
            if (row.isIncompleteOrIllegible || det.confianzaReconocimiento < 0.60f) {
                notasIlegibilidad.add(
                    "Fila #${row.rowNumber ?: (idx + 1)} (${det.nombreDetectado.ifBlank { "sin nombre" }}): revisión sugerida."
                )
            }
        }

        val (deteccionesConDuplicados, gruposDuplicados) = detectarDuplicadosEnPlanilla(
            detecciones = deteccionesPreliminares
        )

        if (gruposDuplicados.isNotEmpty()) {
            dudasGlobales.add(
                "Se detectaron ${gruposDuplicados.size} posible(s) duplicado(s) dentro de la fotografía."
            )
        }

        val tieneDudas = parsed.legibilityStatus == "PARCIALMENTE_LEGIBLE" ||
            notasIlegibilidad.isNotEmpty() ||
            gruposDuplicados.isNotEmpty() ||
            deteccionesConDuplicados.any {
                it.coincidenciaProbable.estado != LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA ||
                    it.observacionesYDudas.isNotEmpty()
            }

        val estadoDoc = if (tieneDudas || parsed.legibilityStatus == "PARCIALMENTE_LEGIBLE") {
            LucyVisionDocumentStatus.PARCIALMENTE_LEGIBLE
        } else {
            LucyVisionDocumentStatus.LEGIBLE
        }

        val unicas = deteccionesConDuplicados.count {
            it.coincidenciaProbable.estado == LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA
        }
        val ambiguas = deteccionesConDuplicados.count {
            it.coincidenciaProbable.estado == LucyVisionMatchStatus.AMBIGUA_MULTIPLE
        }
        val sinMatch = deteccionesConDuplicados.count {
            it.coincidenciaProbable.estado == LucyVisionMatchStatus.SIN_COINCIDENCIA ||
                it.coincidenciaProbable.estado == LucyVisionMatchStatus.NOMBRE_INCOMPLETO
        }

        val resumen = buildString {
            append("Análisis visual en ${turnoContexto.nombre} (${estadoDoc.etiqueta})")
            if (strictCompanyFilter && empresaSugerida != null) {
                append(" · Filtro estricto: ${empresaSugerida.nombre}")
            }
            append(": ")
            append("${deteccionesConDuplicados.size} fila(s) analizada(s) · ")
            append("$unicas propuesta(s) única(s), $ambiguas ambigua(s), $sinMatch sin coincidencia segura")
            if (gruposDuplicados.isNotEmpty()) {
                append(", ${gruposDuplicados.size} grupo(s) con posible duplicado")
            }
            append(". Ningún dato fue modificado ni confirmado automáticamente.")
        }

        val tipoEscrituraDoc = if (parsed.documentTextType != LucyVisionTextOriginType.INDETERMINADO) {
            parsed.documentTextType
        } else {
            val tiposFilas = deteccionesConDuplicados.map { it.tipoEscritura }
                .filter { it != LucyVisionTextOriginType.INDETERMINADO }
                .distinct()
            when {
                tiposFilas.size == 1 -> tiposFilas.first()
                tiposFilas.size > 1 -> LucyVisionTextOriginType.MIXTO
                else -> LucyVisionTextOriginType.INDETERMINADO
            }
        }

        return LucyVisionPlanillaAnalysis(
            turnoContexto = turnoContexto,
            estadoDocumento = estadoDoc,
            motorUtilizado = motorUtilizado,
            esDocumentoOperativoValido = deteccionesConDuplicados.isNotEmpty(),
            tipoEscrituraDocumento = tipoEscrituraDoc,
            empresaDocumentoReconocida = parsed.detectedCompanyText,
            empresaDocumentoInferida = empresaDocumentoInferida,
            resumenGeneral = resumen,
            trabajadoresDetectados = deteccionesConDuplicados,
            posiblesDuplicados = gruposDuplicados,
            dudasGlobales = dudasGlobales.distinct(),
            notasIlegibilidad = notasIlegibilidad.distinct(),
            requiereRevisionHumana = true,
            esResultadoPropuestoNoConfirmado = true,
            empresaFiltroAplicada = if (strictCompanyFilter) empresaSugerida else null,
            parsedSheet = parsed
        )
    }

    private fun construirDeteccionFila(
        indice: Int,
        row: LucyVisionParsedRow,
        empresaDocReconocidaLiteral: String?,
        empresaDocOficial: EmpresaOficial?,
        empresaSugerida: EmpresaOficial?,
        maestroDelTurno: List<TrabajadorConEstado>,
        correccionesAprendidas: List<LucyCorreccionAprendida>,
        strictCompanyFilter: Boolean = false
    ): LucyVisionWorkerDetection {
        val observaciones = row.ambiguityNotes.toMutableList()

        val empresaFilaOficial = resolverEmpresaLiteral(row.detectedCompanyText)
        val empresaLiteralDisponible = row.detectedCompanyText ?: empresaDocReconocidaLiteral
        val empresaInicial: EmpresaOficial?
        val origenEmpresaInicial: LucyVisionDataOrigin

        when {
            strictCompanyFilter && empresaSugerida != null -> {
                empresaInicial = empresaSugerida
                origenEmpresaInicial = LucyVisionDataOrigin.INFERIDO_POR_CONTEXTO
                if (empresaFilaOficial != null && empresaFilaOficial != empresaSugerida) {
                    observaciones.add(
                        "En la fila figura ${empresaFilaOficial.nombre}; filtrado estrictamente por ${empresaSugerida.nombre}."
                    )
                }
            }
            empresaFilaOficial != null -> {
                empresaInicial = empresaFilaOficial
                origenEmpresaInicial = LucyVisionDataOrigin.RECONOCIDO_EN_IMAGEN
            }
            empresaDocOficial != null -> {
                empresaInicial = empresaDocOficial
                origenEmpresaInicial = LucyVisionDataOrigin.RECONOCIDO_EN_IMAGEN
            }
            empresaSugerida != null -> {
                empresaInicial = empresaSugerida
                origenEmpresaInicial = LucyVisionDataOrigin.INFERIDO_POR_CONTEXTO
            }
            else -> {
                empresaInicial = null
                origenEmpresaInicial = LucyVisionDataOrigin.NO_DETERMINADO
            }
        }

        if (row.detectedCompanyText != null && empresaFilaOficial == null) {
            observaciones.add("Empresa escrita en fila (\"${row.detectedCompanyText}\") no coincide exactamente con catálogo oficial.")
        }

        val nombreParaMatch = listOfNotNull(
            row.detectedPaternalLastName,
            row.detectedMaternalLastName,
            row.detectedFirstName
        ).joinToString(" ").trim().ifBlank { row.detectedName }

        val candidatosBase = buscarCandidatosEnMaestro(
            nombreDetectado = nombreParaMatch,
            pistaHabitacion = row.detectedRoom,
            pistaPabellon = row.detectedPavilion,
            empresaFiltro = empresaInicial,
            maestroDelTurno = maestroDelTurno,
            strictCompanyFilter = strictCompanyFilter
        )

        val candidatosConAprendizaje = aplicarCorreccionesAprendidasVision(
            nombreDetectado = row.detectedName,
            nombreParaMatch = nombreParaMatch,
            empresaFiltro = empresaInicial,
            maestroDelTurno = maestroDelTurno,
            correccionesAprendidas = correccionesAprendidas,
            candidatosExistentes = candidatosBase,
            observaciones = observaciones,
            strictCompanyFilter = strictCompanyFilter
        )

        val prop = evaluarPropuestaCoincidencia(
            nombreDetectado = row.detectedName,
            candidatos = candidatosConAprendizaje,
            confianza = row.confidence,
            isIncomplete = row.isIncompleteOrIllegible,
            observaciones = observaciones
        )

        // Si no había empresa reconocida ni de contexto, pero hay candidato único o consenso de candidatos, se infiere desde maestro
        val empresaFinal: EmpresaOficial?
        val origenEmpresaFinal: LucyVisionDataOrigin
        if (empresaInicial != null) {
            empresaFinal = empresaInicial
            origenEmpresaFinal = origenEmpresaInicial
        } else {
            val empresasCandidatos = prop.candidatos.map { it.trabajador.empresa }.distinct()
            if (empresasCandidatos.size == 1) {
                empresaFinal = empresasCandidatos.first()
                origenEmpresaFinal = LucyVisionDataOrigin.INFERIDO_POR_MAESTRO
                observaciones.add("Empresa probable inferida desde coincidencia en maestro (${empresaFinal.nombre}).")
            } else {
                empresaFinal = null
                origenEmpresaFinal = LucyVisionDataOrigin.NO_DETERMINADO
            }
        }

        // Distinguir pabellón reconocido literalmente vs inferido del candidato principal
        val pabellonLiteral = row.detectedPavilion?.trim()?.takeIf { it.isNotBlank() }
        val pabellonFinal: String?
        val origenPabellon: LucyVisionDataOrigin
        when {
            pabellonLiteral != null -> {
                pabellonFinal = TextoNormalizer.formatearMayusculas(pabellonLiteral)
                origenPabellon = LucyVisionDataOrigin.RECONOCIDO_EN_IMAGEN
            }
            prop.candidatoPrincipal != null && prop.candidatoPrincipal.trabajador.pabellonMayusculas.isNotBlank() -> {
                pabellonFinal = prop.candidatoPrincipal.trabajador.pabellonMayusculas
                origenPabellon = LucyVisionDataOrigin.INFERIDO_POR_MAESTRO
            }
            else -> {
                pabellonFinal = null
                origenPabellon = LucyVisionDataOrigin.NO_DETERMINADO
            }
        }

        val origenMarcacion = when {
            row.interpretedMarkingStatus == LucyVisionMarkingStatus.INDETERMINADO ->
                LucyVisionDataOrigin.NO_DETERMINADO
            !row.visualMarkingText.isNullOrBlank() ->
                LucyVisionDataOrigin.RECONOCIDO_EN_IMAGEN
            else ->
                LucyVisionDataOrigin.INFERIDO_POR_CONTEXTO
        }

        if (row.isIncompleteOrIllegible) {
            observaciones.add("Lectura marcada como incompleta o con trazos dudosos.")
        }
        if (row.confidence < 0.65f) {
            observaciones.add("Confianza visual baja (${(row.confidence * 100).toInt()}%); verificar foto original.")
        }
        if (empresaFinal != null && origenEmpresaFinal == LucyVisionDataOrigin.INFERIDO_POR_CONTEXTO) {
            observaciones.add("Empresa probable tomada del contexto (${empresaFinal.nombre}), no leída en la fila.")
        }

        val observacionLimpia = observaciones.distinct()
        val nivelConfianza = LucyVisionConfidenceLevel.fromScore(row.confidence)
        val esDudoso = row.isIncompleteOrIllegible ||
            row.confidence < 0.65f ||
            prop.estado == LucyVisionMatchStatus.NOMBRE_INCOMPLETO ||
            prop.estado == LucyVisionMatchStatus.SIN_COINCIDENCIA

        val apellidosReconocidos = row.detectedLastNames?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(row.detectedPaternalLastName, row.detectedMaternalLastName)
                .joinToString(" ")
                .trim()
                .takeIf { it.isNotBlank() }

        val datosReconocidos = LucyVisionObservedWorkerData(
            filaNumero = row.rowNumber,
            nombreDetectado = row.detectedName,
            apellidosDetectados = apellidosReconocidos,
            apellidoPaternoDetectado = row.detectedPaternalLastName,
            apellidoMaternoDetectado = row.detectedMaternalLastName,
            nombrePilaDetectado = row.detectedFirstName,
            tipoEscritura = row.textOriginType,
            empresaEscritaEnImagen = empresaLiteralDisponible,
            pabellonEscritoEnImagen = pabellonLiteral,
            habitacionEscritaEnImagen = row.detectedRoom?.trim()?.takeIf { it.isNotBlank() },
            marcaVisualLiteral = row.visualMarkingText,
            anotacionesFila = row.rowAnnotations,
            tieneMarcasManuales = row.hasManualMarks,
            textoCrudoFila = row.rawText
        )
        val datosInferidos = LucyVisionInferredWorkerData(
            empresaProbable = empresaFinal,
            origenEmpresa = origenEmpresaFinal,
            pabellonInferido = if (origenPabellon == LucyVisionDataOrigin.INFERIDO_POR_MAESTRO) pabellonFinal else null,
            origenPabellon = origenPabellon,
            estadoMarcacionPropuesto = row.interpretedMarkingStatus,
            origenEstadoMarcacion = origenMarcacion,
            coincidenciaProbable = prop
        )

        return LucyVisionWorkerDetection(
            indiceEnAnalisis = indice,
            nombreDetectado = row.detectedName,
            apellidosDetectados = apellidosReconocidos,
            apellidoPaternoDetectado = row.detectedPaternalLastName,
            apellidoMaternoDetectado = row.detectedMaternalLastName,
            nombrePilaDetectado = row.detectedFirstName,
            tipoEscritura = row.textOriginType,
            esDudosoOIlegible = esDudoso,
            empresaProbable = empresaFinal,
            origenEmpresa = origenEmpresaFinal,
            pabellon = pabellonFinal,
            origenPabellon = origenPabellon,
            habitacion = datosReconocidos.habitacionEscritaEnImagen ?: prop.candidatoPrincipal?.trabajador?.habitacionMayusculas?.takeIf { it.isNotBlank() },
            estadoMarcacion = row.interpretedMarkingStatus,
            origenMarcacion = origenMarcacion,
            anotacionesFila = row.rowAnnotations,
            tieneMarcasManuales = row.hasManualMarks,
            confianzaReconocimiento = row.confidence,
            nivelConfianza = nivelConfianza,
            coincidenciaProbable = prop,
            observacionesYDudas = observacionLimpia,
            datosReconocidos = datosReconocidos,
            datosInferidos = datosInferidos
        )
    }

    private fun buscarCandidatosEnMaestro(
        nombreDetectado: String,
        pistaHabitacion: String?,
        pistaPabellon: String?,
        empresaFiltro: EmpresaOficial?,
        maestroDelTurno: List<TrabajadorConEstado>,
        strictCompanyFilter: Boolean = false
    ): List<LucyVisionWorkerMatchCandidate> {
        val (claveNombre, _) = OcrNombreMatcher.limpiarTextoNombreDesdeLinea(nombreDetectado)
        val claveUso = claveNombre.ifBlank { OcrNombreMatcher.normalizarClaveOcr(nombreDetectado) }
        val tokensDetectados = claveUso.split(' ').filter { it.length >= 2 }
        if (tokensDetectados.isEmpty()) return emptyList()

        val universo = if (empresaFiltro != null) {
            val filtrados = maestroDelTurno.filter { it.empresa == empresaFiltro }
            if (strictCompanyFilter) filtrados else filtrados.ifEmpty { maestroDelTurno }
        } else {
            maestroDelTurno
        }

        val habNorm = pistaHabitacion?.let { TextoNormalizer.normalizar(it) }?.takeIf { it.isNotBlank() }
        val pabNorm = pistaPabellon?.let { TextoNormalizer.normalizar(it) }?.takeIf { it.isNotBlank() }

        val resultados = mutableListOf<LucyVisionWorkerMatchCandidate>()
        for (t in universo) {
            val personaTokens = t.searchIndex.tokensPersona
            if (personaTokens.isEmpty()) continue

            var suma = 0.0
            for (tok in tokensDetectados) {
                var mejor = 0.0
                for (pTok in personaTokens) {
                    val sim = when {
                        tok == pTok -> 1.0
                        pTok.startsWith(tok) && tok.length >= 3 -> 0.90
                        tok.startsWith(pTok) && pTok.length >= 3 -> 0.88
                        else -> similitudLevenshtein(tok, pTok)
                    }
                    if (sim > mejor) mejor = sim
                }
                suma += mejor
            }

            var score = suma / tokensDetectados.size.toDouble()
            val motivos = mutableListOf<String>()

            if (tokensDetectados.size == 1) {
                score *= 0.82
                motivos.add("Coincidencia parcial (solo un término)")
            } else if (score >= 0.96) {
                motivos.add("Coincidencia nominal alta")
            } else {
                motivos.add("Similitud aproximada de escritura")
            }

            if (habNorm != null && t.searchIndex.normHabitacion == habNorm) {
                score = min(0.99, score + 0.06)
                motivos.add("Habitación coincidente ($habNorm)")
            }
            if (pabNorm != null && t.searchIndex.normPabellon == pabNorm) {
                score = min(0.99, score + 0.04)
                motivos.add("Pabellón coincidente ($pabNorm)")
            }

            if (empresaFiltro != null && t.empresa != empresaFiltro) {
                score *= 0.85
                motivos.add("Pertenece a ${t.empresa.nombre}")
            }

            if (score >= 0.58) {
                resultados.add(
                    LucyVisionWorkerMatchCandidate(
                        trabajador = t,
                        puntajeSimilitud = score.coerceIn(0.0, 1.0),
                        motivo = motivos.joinToString(" · ")
                    )
                )
            }
        }

        return resultados
            .sortedWith(
                compareByDescending<LucyVisionWorkerMatchCandidate> { it.puntajeSimilitud }
                    .thenBy { it.trabajador.nombreCompleto }
            )
            .take(5)
    }

    private fun evaluarPropuestaCoincidencia(
        nombreDetectado: String,
        candidatos: List<LucyVisionWorkerMatchCandidate>,
        confianza: Float,
        isIncomplete: Boolean,
        observaciones: MutableList<String>
    ): LucyVisionWorkerMatchProposal {
        val tokens = OcrNombreMatcher.normalizarClaveOcr(nombreDetectado)
            .split(' ')
            .filter { it.length >= 2 }

        if (tokens.size <= 1) {
            val unicaAprendida = candidatos.size == 1 && candidatos.first().motivo.startsWith("Corrección aprendida")
            if (unicaAprendida) {
                return LucyVisionWorkerMatchProposal(
                    estado = LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA,
                    candidatoPrincipal = candidatos.first(),
                    candidatos = candidatos,
                    esIdentidadConfirmada = false
                )
            }
            observaciones.add("Nombre incompleto en la planilla (falta nombre o apellido para distinguir).")
            return LucyVisionWorkerMatchProposal(
                estado = if (candidatos.size > 1) LucyVisionMatchStatus.AMBIGUA_MULTIPLE else LucyVisionMatchStatus.NOMBRE_INCOMPLETO,
                candidatoPrincipal = candidatos.firstOrNull(),
                candidatos = candidatos,
                esIdentidadConfirmada = false
            )
        }

        if (candidatos.isEmpty()) {
            observaciones.add("Sin coincidencia en el maestro del turno para \"$nombreDetectado\".")
            return LucyVisionWorkerMatchProposal(
                estado = LucyVisionMatchStatus.SIN_COINCIDENCIA,
                candidatoPrincipal = null,
                candidatos = emptyList(),
                esIdentidadConfirmada = false
            )
        }

        val mejor = candidatos.first()
        val segundo = candidatos.getOrNull(1)
        val empateOAmbiguo = segundo != null && (
            (mejor.puntajeSimilitud - segundo.puntajeSimilitud) < 0.08 ||
                segundo.puntajeSimilitud >= 0.82
            )

        if (empateOAmbiguo) {
            observaciones.add(
                "Coincidencia ambigua entre ${candidatos.take(3).joinToString(" / ") { it.trabajador.nombreCompleto }}."
            )
            return LucyVisionWorkerMatchProposal(
                estado = LucyVisionMatchStatus.AMBIGUA_MULTIPLE,
                candidatoPrincipal = mejor,
                candidatos = candidatos,
                esIdentidadConfirmada = false
            )
        }

        if (isIncomplete || confianza < 0.65f || mejor.puntajeSimilitud < 0.85) {
            observaciones.add(
                "Propuesta sujeta a validación manual (${mejor.trabajador.nombreCompleto}, similitud ${(mejor.puntajeSimilitud * 100).toInt()}%)."
            )
        }

        return LucyVisionWorkerMatchProposal(
            estado = LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA,
            candidatoPrincipal = mejor,
            candidatos = candidatos,
            esIdentidadConfirmada = false
        )
    }

    private fun aplicarCorreccionesAprendidasVision(
        nombreDetectado: String,
        nombreParaMatch: String,
        empresaFiltro: EmpresaOficial?,
        maestroDelTurno: List<TrabajadorConEstado>,
        correccionesAprendidas: List<LucyCorreccionAprendida>,
        candidatosExistentes: List<LucyVisionWorkerMatchCandidate>,
        observaciones: MutableList<String>,
        strictCompanyFilter: Boolean = false
    ): List<LucyVisionWorkerMatchCandidate> {
        if (correccionesAprendidas.isEmpty()) return candidatosExistentes
        val claves = setOf(
            LucyMemoryStorageSerializer.normalizarClaveCorreccion(nombreDetectado),
            LucyMemoryStorageSerializer.normalizarClaveCorreccion(nombreParaMatch)
        ).filter { it.isNotBlank() }.toSet()
        if (claves.isEmpty()) return candidatosExistentes

        val aplicadas = correccionesAprendidas.filter { corr ->
            corr.tipo != LucyTipoCorreccionAprendida.DATO_MAESTRO &&
                corr.claveErroneaNorm in claves &&
                (empresaFiltro == null || corr.empresaCodigo == null || corr.empresaCodigo == empresaFiltro.codigo)
        }
        if (aplicadas.isEmpty()) return candidatosExistentes

        val trabajadoresAprendidos = aplicadas.mapNotNull { corr ->
            maestroDelTurno.firstOrNull {
                it.id == corr.trabajadorId &&
                    (!strictCompanyFilter || empresaFiltro == null || it.empresa == empresaFiltro)
            }
        }.distinctBy { it.id }
        if (trabajadoresAprendidos.isEmpty()) return candidatosExistentes

        val candidatosAprendidos = trabajadoresAprendidos.map { t ->
            LucyVisionWorkerMatchCandidate(
                trabajador = t,
                puntajeSimilitud = 0.99,
                motivo = "Corrección aprendida previa (${t.nombreCompleto})"
            )
        }
        observaciones.add("Propuesta apoyada en corrección aprendida guardada (sujeta a validación humana).")
        val idsAprendidos = trabajadoresAprendidos.mapTo(HashSet()) { it.id }
        return (candidatosAprendidos + candidatosExistentes.filterNot { it.trabajador.id in idsAprendidos }).take(5)
    }

    private fun detectarDuplicadosEnPlanilla(
        detecciones: List<LucyVisionWorkerDetection>
    ): Pair<List<LucyVisionWorkerDetection>, List<LucyVisionDuplicateGroup>> {
        if (detecciones.size <= 1) return detecciones to emptyList()

        val indicesPorNombreNorm = mutableMapOf<String, MutableList<Int>>()
        val indicesPorTrabajadorId = mutableMapOf<String, MutableList<Int>>()

        detecciones.forEachIndexed { idx, det ->
            val normNombre = OcrNombreMatcher.normalizarClaveOcr(det.nombreDetectado)
            if (normNombre.isNotBlank()) {
                indicesPorNombreNorm.getOrPut(normNombre) { mutableListOf() }.add(idx)
            }
            val idPrincipal = det.coincidenciaProbable.candidatoPrincipal
                ?.takeIf { det.coincidenciaProbable.estado == LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA }
                ?.trabajador?.id
            if (idPrincipal != null) {
                indicesPorTrabajadorId.getOrPut(idPrincipal) { mutableListOf() }.add(idx)
            }
        }

        val grupos = mutableListOf<LucyVisionDuplicateGroup>()
        val detallePorIndice = mutableMapOf<Int, MutableList<String>>()
        val paresYaReportados = HashSet<String>()

        for ((idTrabajador, idxs) in indicesPorTrabajadorId) {
            if (idxs.size > 1) {
                val nombre = detecciones[idxs.first()].coincidenciaProbable.candidatoPrincipal?.trabajador?.nombreCompleto
                    ?: idTrabajador
                val filas = idxs.map { detecciones[it].indiceEnAnalisis }
                val motivo = "Posible duplicado del mismo trabajador propuesto ($nombre) en filas ${filas.joinToString(", ")}."
                grupos.add(
                    LucyVisionDuplicateGroup(
                        motivo = motivo,
                        claveNormalizada = OcrNombreMatcher.normalizarClaveOcr(nombre),
                        indicesFilasDetectadas = filas,
                        trabajadorCandidatoId = idTrabajador
                    )
                )
                paresYaReportados.add(filas.joinToString(","))
                for (i in idxs) {
                    detallePorIndice.getOrPut(i) { mutableListOf() }.add(motivo)
                }
            }
        }

        for ((clave, idxs) in indicesPorNombreNorm) {
            if (idxs.size > 1) {
                val filas = idxs.map { detecciones[it].indiceEnAnalisis }
                val keyFilas = filas.joinToString(",")
                if (keyFilas !in paresYaReportados) {
                    val motivo = "Texto repetido en planilla (\"${detecciones[idxs.first()].nombreDetectado}\") en filas $keyFilas."
                    grupos.add(
                        LucyVisionDuplicateGroup(
                            motivo = motivo,
                            claveNormalizada = clave,
                            indicesFilasDetectadas = filas,
                            trabajadorCandidatoId = null
                        )
                    )
                    for (i in idxs) {
                        detallePorIndice.getOrPut(i) { mutableListOf() }.add(motivo)
                    }
                }
            }
        }

        val actualizadas = detecciones.mapIndexed { idx, det ->
            val msgs = detallePorIndice[idx]
            if (msgs.isNullOrEmpty()) {
                det
            } else {
                val unido = msgs.distinct().joinToString(" ")
                det.copy(
                    esPosibleDuplicadoEnPlanilla = true,
                    detalleDuplicado = unido,
                    observacionesYDudas = (det.observacionesYDudas + unido).distinct()
                )
            }
        }

        return actualizadas to grupos
    }

    internal fun resolverEmpresaLiteral(textoEmpresa: String?): EmpresaOficial? {
        if (textoEmpresa.isNullOrBlank()) return null
        val exacta = EmpresaOficial.buscarExactaPorNombre(textoEmpresa)
        if (exacta != null) return exacta

        val norm = EmpresaOficial.normalizarNombreEmpresa(textoEmpresa)
        // Respetar estrictamente distinción Komatsu vs Komatsu ESOP y Finning vs Finning Pala
        return when {
            "komatsu" in norm && "esop" in norm -> EmpresaOficial.KOMATSU_ESOP
            norm == "komatsu" || ("komatsu" in norm && "esop" !in norm && norm.length <= 18) -> EmpresaOficial.KOMATSU
            "finning" in norm && "pala" in norm -> EmpresaOficial.FINNING_PALA
            norm == "finning" || ("finning" in norm && "pala" !in norm && norm.length <= 18) -> EmpresaOficial.FINNING
            "fluitek" in norm -> EmpresaOficial.FLUITEK
            "somacor" in norm -> EmpresaOficial.SOMACOR
            "joy" in norm && "global" in norm -> EmpresaOficial.JOY_GLOBAL
            "cummins" in norm -> EmpresaOficial.CUMMINS
            else -> null
        }
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
