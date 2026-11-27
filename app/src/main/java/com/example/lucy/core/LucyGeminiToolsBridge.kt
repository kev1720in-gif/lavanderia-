package com.example.lucy.core

import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal interface LucyGeminiCoordinatorOps {
    suspend fun buscarTrabajador(query: String, turno: Turno, empresa: EmpresaOficial?, usarAnterior: Boolean): LucyResponse
    suspend fun consultarEstadoTrabajador(query: String, turno: Turno, empresa: EmpresaOficial?, usarAnterior: Boolean): LucyResponse
    suspend fun resumenTurno(turno: Turno, empresa: EmpresaOficial?): LucyResponse
    suspend fun listarPendientes(turno: Turno, empresa: EmpresaOficial?): LucyResponse
    suspend fun listarEmpresas(turno: Turno, empresa: EmpresaOficial?): LucyResponse
    suspend fun listarTrabajadores(turno: Turno, empresa: EmpresaOficial?): LucyResponse
    suspend fun marcarSubieronLote(nombresTexto: String, empresa: EmpresaOficial?, esContinuacion: Boolean, incluirUbicacion: Boolean, contexto: LucyTurnContext, origen: LucyOperationOrigin): LucyResponse
    suspend fun prepararMarcacionIndividual(query: String, marcarSiBajo: Boolean, empresa: EmpresaOficial?, usarAnterior: Boolean, contexto: LucyTurnContext): LucyResponse
    suspend fun resolverDudaPendiente(respuesta: String, empresa: EmpresaOficial?, dejarPendiente: Boolean, forzarMarcacion: Boolean, contexto: LucyTurnContext, origen: LucyOperationOrigin): LucyResponse
    suspend fun actualizarDatoTrabajador(nombreTrabajador: String, usarAnterior: Boolean, nuevaHab: String?, nuevoPab: String?, nuevaEmpresa: EmpresaOficial?, textoOriginal: String, contexto: LucyTurnContext, origen: LucyOperationOrigin): LucyResponse
    suspend fun cambiarTurno(nuevoTurno: Turno, contexto: LucyTurnContext): LucyResponse
    suspend fun configurarPreferencias(velocidad: LucyVelocidadRespuesta?, estilo: LucyEstiloRespuesta?, estiloVoz: LucyEstiloVoz?, instruccion: String?, turno: Turno): LucyResponse
    suspend fun guardarNota(texto: String, empresa: EmpresaOficial?, turno: Turno, contexto: LucyTurnContext): LucyResponse
    suspend fun consultarMemoria(empresa: EmpresaOficial?, query: String?, soloPreferencias: Boolean, turno: Turno, contexto: LucyTurnContext): LucyResponse
    suspend fun olvidarMemoria(tipo: LucyTipoOlvidoMemoria, empresa: EmpresaOficial?, referencia: String?, turno: Turno, contexto: LucyTurnContext): LucyResponse
    suspend fun consultarContextoCharla(tipo: String, turno: Turno, contexto: LucyTurnContext): LucyResponse
    suspend fun filtrarPlanillaVisionPorEmpresa(empresaNombre: String, turno: Turno, contexto: LucyTurnContext): LucyResponse
}

internal object LucyGeminiToolsBridge {

    val toolDeclarations: List<FunctionDeclaration> by lazy {
        listOf(
            FunctionDeclaration(
                name = "buscar_trabajador",
                description = "SOLO LECTURA. Busca uno o varios trabajadores en el maestro del turno activo por nombre, apellido(s), habitación o pabellón, tolerando tildes y variaciones fonéticas como Bryan/Brian o Moralez/Morales. Usar para preguntas como '¿tienes registrado a...?' o menciones pasivas.",
                parameters = mapOf(
                    "query" to Schema.string("Nombre, apellido, habitación o pabellón limpio (sin relleno de la pregunta)."),
                    "empresa" to Schema.string("Nombre oficial de empresa opcional (Komatsu, Komatsu ESOP, Fluitek, Somacor, Joy Global, Finning, Cummins, Finning Pala).", nullable = true),
                    "usar_trabajador_anterior" to Schema.boolean("true si el usuario pregunta por el último trabajador mencionado (ej. '¿y en qué habitación está?').", nullable = true)
                ),
                optionalParameters = listOf("empresa", "usar_trabajador_anterior")
            ),
            FunctionDeclaration(
                name = "consultar_estado_trabajador",
                description = "SOLO LECTURA. Consulta si un trabajador figura marcado como SÍ BAJÓ o NO BAJÓ en el turno actual y su ubicación.",
                parameters = mapOf(
                    "query" to Schema.string("Nombre o apellido limpio del trabajador.", nullable = true),
                    "empresa" to Schema.string("Empresa oficial opcional.", nullable = true),
                    "usar_trabajador_anterior" to Schema.boolean("true si se refiere al último trabajador de la conversación.", nullable = true)
                ),
                optionalParameters = listOf("query", "empresa", "usar_trabajador_anterior")
            ),
            FunctionDeclaration(
                name = "resumen_turno",
                description = "SOLO LECTURA. Obtiene el resumen y conteo real (SÍ BAJÓ, NO BAJÓ, total en maestro) del turno activo o de una empresa.",
                parameters = mapOf(
                    "empresa" to Schema.string("Empresa oficial opcional.", nullable = true),
                    "turno" to Schema.string("Turno consultado opcional: 'A' o 'B'.", nullable = true)
                ),
                optionalParameters = listOf("empresa", "turno")
            ),
            FunctionDeclaration(
                name = "listar_pendientes",
                description = "SOLO LECTURA. Lista trabajadores pendientes (NO BAJÓ) del turno o de una empresa.",
                parameters = mapOf(
                    "empresa" to Schema.string("Empresa oficial opcional.", nullable = true),
                    "turno" to Schema.string("Turno consultado opcional: 'A' o 'B'.", nullable = true)
                ),
                optionalParameters = listOf("empresa", "turno")
            ),
            FunctionDeclaration(
                name = "listar_empresas",
                description = "SOLO LECTURA. Consulta el avance por empresas oficiales o el detalle de una empresa específica.",
                parameters = mapOf(
                    "empresa" to Schema.string("Empresa oficial opcional.", nullable = true),
                    "turno" to Schema.string("Turno consultado opcional: 'A' o 'B'.", nullable = true)
                ),
                optionalParameters = listOf("empresa", "turno")
            ),
            FunctionDeclaration(
                name = "listar_trabajadores",
                description = "SOLO LECTURA. Lista trabajadores registrados en el turno activo o filtrados por empresa.",
                parameters = mapOf(
                    "empresa" to Schema.string("Empresa oficial opcional.", nullable = true),
                    "turno" to Schema.string("Turno consultado opcional: 'A' o 'B'.", nullable = true)
                ),
                optionalParameters = listOf("empresa", "turno")
            ),
            FunctionDeclaration(
                name = "marcar_subieron_lote",
                description = "ACCIÓN REAL EN ROOM. Marca como SÍ SUBIÓ (SÍ BAJÓ = true) a uno o varios trabajadores identificados de forma segura mediante LucySafeBatchMatcher. Usar SOLO ante afirmación u orden directa de subida (ej. 'estos subieron de Komatsu: Juan Pérez y Pedro Rojas', 'subió Carlos', 'agrega también a Ana'). NUNCA llamar ante preguntas, ejemplos o menciones casuales.",
                parameters = mapOf(
                    "nombres_texto" to Schema.string("Texto con los nombres separados por coma o 'y'."),
                    "empresa" to Schema.string("Empresa oficial si se mencionó o aplica al lote.", nullable = true),
                    "es_continuacion" to Schema.boolean("true si continúa el dictado inmediatamente anterior (ej. 'y también...').", nullable = true),
                    "incluir_ubicacion" to Schema.boolean("true si el usuario pidió mostrar habitaciones/ubicaciones.", nullable = true)
                ),
                optionalParameters = listOf("empresa", "es_continuacion", "incluir_ubicacion")
            ),
            FunctionDeclaration(
                name = "preparar_marcacion_individual",
                description = "Prepara tarjeta de confirmación manual para marcar o desmarcar a UN trabajador individual cuando el usuario lo ordena ('marca a Juan Pérez', 'desmarca a Rojas'). NUNCA llamar ante preguntas o menciones.",
                parameters = mapOf(
                    "query" to Schema.string("Nombre o apellido del trabajador.", nullable = true),
                    "marcar_si_bajo" to Schema.boolean("true para marcar SÍ BAJÓ, false para desmarcar (NO BAJÓ)."),
                    "empresa" to Schema.string("Empresa oficial opcional.", nullable = true),
                    "usar_trabajador_anterior" to Schema.boolean("true si se refiere al trabajador anterior.", nullable = true)
                ),
                optionalParameters = listOf("query", "empresa", "usar_trabajador_anterior")
            ),
            FunctionDeclaration(
                name = "resolver_duda_pendiente",
                description = "Resuelve o deja pendiente una duda abierta tras un dictado o marcación reciente.",
                parameters = mapOf(
                    "respuesta" to Schema.string("Nombre aclaratorio o texto de respuesta del usuario."),
                    "empresa" to Schema.string("Empresa oficial si el usuario la indicó.", nullable = true),
                    "dejar_pendiente" to Schema.boolean("true si el usuario pide omitir o dejar pendiente la duda.", nullable = true),
                    "forzar_marcacion" to Schema.boolean("true si confirma marcarlo aunque sea de otra empresa.", nullable = true)
                ),
                optionalParameters = listOf("empresa", "dejar_pendiente", "forzar_marcacion")
            ),
            FunctionDeclaration(
                name = "actualizar_dato_trabajador",
                description = "ACCIÓN REAL EN ROOM. Actualiza habitación, pabellón o empresa de un trabajador ante instrucción explícita de cambio (NUNCA en preguntas ni hipótesis).",
                parameters = mapOf(
                    "nombre_trabajador" to Schema.string("Nombre del trabajador.", nullable = true),
                    "usar_trabajador_anterior" to Schema.boolean("true si se refiere al último trabajador mencionado.", nullable = true),
                    "nueva_habitacion" to Schema.string("Nueva habitación si corresponde.", nullable = true),
                    "nuevo_pabellon" to Schema.string("Nuevo pabellón si corresponde.", nullable = true),
                    "nueva_empresa" to Schema.string("Nueva empresa oficial si corresponde.", nullable = true)
                ),
                optionalParameters = listOf("nombre_trabajador", "usar_trabajador_anterior", "nueva_habitacion", "nuevo_pabellon", "nueva_empresa")
            ),
            FunctionDeclaration(
                name = "gestionar_memoria",
                description = "Guarda, consulta u olvida notas operativas o configura estilo de respuesta y estilo/velocidad de voz de Lucy.",
                parameters = mapOf(
                    "operacion" to Schema.enumeration(
                        values = listOf(
                            "guardar_nota",
                            "consultar_notas",
                            "consultar_preferencias",
                            "olvidar_nota",
                            "olvidar_ultima_preferencia",
                            "olvidar_todas_preferencias",
                            "configurar_preferencias"
                        ),
                        description = "Acción sobre la memoria o preferencias."
                    ),
                    "texto" to Schema.string("Contenido de la nota, filtro o instrucción permanente.", nullable = true),
                    "empresa" to Schema.string("Empresa oficial asociada opcional.", nullable = true),
                    "velocidad" to Schema.enumeration(listOf("LENTA", "NORMAL", "RAPIDA"), nullable = true),
                    "estilo_respuesta" to Schema.enumeration(listOf("CONCISA", "NORMAL", "DETALLADA"), nullable = true),
                    "estilo_voz" to Schema.enumeration(listOf("NATURAL_CERCANA", "CLARA_PROFESIONAL", "BREVE_DIRECTA", "PAUSADA_SUAVE"), nullable = true)
                ),
                optionalParameters = listOf("texto", "empresa", "velocidad", "estilo_respuesta", "estilo_voz")
            ),
            FunctionDeclaration(
                name = "cambiar_turno",
                description = "Cambia el turno activo de operación entre A y B solo cuando el operador lo pide expresamente.",
                parameters = mapOf(
                    "nuevo_turno" to Schema.enumeration(listOf("A", "B"), "Turno destino ('A' o 'B').")
                )
            ),
            FunctionDeclaration(
                name = "consultar_contexto_charla",
                description = "SOLO LECTURA. Recupera de qué se estaba hablando recién, última pregunta, o historial de cambios maestros en el turno.",
                parameters = mapOf(
                    "tipo" to Schema.enumeration(
                        values = listOf("ultima_pregunta", "resumen_charla", "que_estabamos_revisando", "historial_cambios"),
                        description = "Tipo de contexto a consultar."
                    ),
                    "nombre_trabajador" to Schema.string("Nombre opcional para filtrar historial de cambios.", nullable = true)
                ),
                optionalParameters = listOf("nombre_trabajador")
            ),
            FunctionDeclaration(
                name = "filtrar_planilla_vision_por_empresa",
                description = "SOLO LECTURA. Re-evalúa las filas ya leídas de la fotografía de planilla activa filtrando ESTRICTAMENTE contra el maestro de UNA empresa indicada (ej. 'Son de Fluitek', 'Filtra solamente los de Fluitek', 'Ahora revisa los que coinciden con Komatsu'). Nunca inventa ni marca trabajadores.",
                parameters = mapOf(
                    "empresa" to Schema.string("Nombre de la empresa indicada para filtrar la planilla (ej. Fluitek, Komatsu, Komatsu ESOP, Finning, Somacor, Joy Global, Cummins, Finning Pala)."),
                    "turno" to Schema.string("Turno consultado opcional: 'A' o 'B'.", nullable = true)
                ),
                optionalParameters = listOf("turno")
            )
        )
    }

    fun createToolExecutor(
        ops: LucyGeminiCoordinatorOps,
        userInputOriginal: String,
        contexto: LucyTurnContext,
        origen: LucyOperationOrigin,
        sideEffects: LucyGeminiToolSideEffects
    ): LucyGeminiToolExecutor {
        val bloquearMutacionPorPreguntaOHipotesis = esPreguntaMencionOHipotesisSinOrden(userInputOriginal)

        return object : LucyGeminiToolExecutor {
            override suspend fun execute(
                toolName: String,
                args: Map<String, JsonElement>
            ): JsonObject {
                return when (toolName) {
                    "buscar_trabajador" -> {
                        sideEffects.isReadOnlyQuery = true
                        sideEffects.lastActionType = "Búsqueda de trabajador (Gemini)"
                        val query = args.str("query").orEmpty()
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val usarAnt = args.bool("usar_trabajador_anterior") ?: false
                        val res = ops.buscarTrabajador(
                            query = query,
                            turno = sideEffects.turnoFinal,
                            empresa = empresa,
                            usarAnterior = usarAnt
                        )
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "consultar_estado_trabajador" -> {
                        sideEffects.isReadOnlyQuery = true
                        sideEffects.lastActionType = "Consulta de estado (Gemini)"
                        val query = args.str("query").orEmpty()
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val usarAnt = args.bool("usar_trabajador_anterior") ?: false
                        val res = ops.consultarEstadoTrabajador(
                            query = query,
                            turno = sideEffects.turnoFinal,
                            empresa = empresa,
                            usarAnterior = usarAnt
                        )
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "resumen_turno" -> {
                        sideEffects.isReadOnlyQuery = true
                        sideEffects.lastActionType = "Resumen de turno (Gemini)"
                        val turnoObj = resolverTurno(args.str("turno")) ?: sideEffects.turnoFinal
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val res = ops.resumenTurno(turnoObj, empresa)
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "listar_pendientes" -> {
                        sideEffects.isReadOnlyQuery = true
                        sideEffects.lastActionType = "Consulta de pendientes (Gemini)"
                        val turnoObj = resolverTurno(args.str("turno")) ?: sideEffects.turnoFinal
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val res = ops.listarPendientes(turnoObj, empresa)
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "listar_empresas" -> {
                        sideEffects.isReadOnlyQuery = true
                        sideEffects.lastActionType = "Consulta de empresas (Gemini)"
                        val turnoObj = resolverTurno(args.str("turno")) ?: sideEffects.turnoFinal
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val res = ops.listarEmpresas(turnoObj, empresa)
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "listar_trabajadores" -> {
                        sideEffects.isReadOnlyQuery = true
                        sideEffects.lastActionType = "Listado de trabajadores (Gemini)"
                        val turnoObj = resolverTurno(args.str("turno")) ?: sideEffects.turnoFinal
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val res = ops.listarTrabajadores(turnoObj, empresa)
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "marcar_subieron_lote" -> {
                        if (bloquearMutacionPorPreguntaOHipotesis) {
                            return blockedMutationJson(
                                "La entrada del operador parece una pregunta, ejemplo o mención sin orden explícita de subida. No se realizaron marcaciones."
                            )
                        }
                        val nombresTexto = args.str("nombres_texto").orEmpty()
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val esCont = args.bool("es_continuacion") ?: false
                        val inclUb = args.bool("incluir_ubicacion") ?: false
                        val res = ops.marcarSubieronLote(
                            nombresTexto = nombresTexto,
                            empresa = empresa,
                            esContinuacion = esCont,
                            incluirUbicacion = inclUb,
                            contexto = contexto.copy(turnoActivo = sideEffects.turnoFinal),
                            origen = origen
                        )
                        sideEffects.mutatedDb = true
                        sideEffects.lastActionType = "Marcación múltiple (Gemini)"
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "preparar_marcacion_individual" -> {
                        if (bloquearMutacionPorPreguntaOHipotesis) {
                            return blockedMutationJson(
                                "La entrada del operador parece una pregunta, ejemplo o mención. No se preparó ninguna marcación."
                            )
                        }
                        val query = args.str("query").orEmpty()
                        val siBajo = args.bool("marcar_si_bajo") ?: true
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val usarAnt = args.bool("usar_trabajador_anterior") ?: false
                        val res = ops.prepararMarcacionIndividual(
                            query = query,
                            marcarSiBajo = siBajo,
                            empresa = empresa,
                            usarAnterior = usarAnt,
                            contexto = contexto.copy(turnoActivo = sideEffects.turnoFinal)
                        )
                        sideEffects.lastActionType = "Preparación de marcación (Gemini)"
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "resolver_duda_pendiente" -> {
                        if (bloquearMutacionPorPreguntaOHipotesis) {
                            return blockedMutationJson("No se resolvió la duda porque la frase parece interrogativa o hipotética.")
                        }
                        val respTexto = args.str("respuesta").orEmpty()
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val dejar = args.bool("dejar_pendiente") ?: false
                        val forzar = args.bool("forzar_marcacion") ?: false
                        val res = ops.resolverDudaPendiente(
                            respuesta = respTexto,
                            empresa = empresa,
                            dejarPendiente = dejar,
                            forzarMarcacion = forzar,
                            contexto = contexto.copy(turnoActivo = sideEffects.turnoFinal),
                            origen = origen
                        )
                        sideEffects.mutatedDb = !dejar
                        sideEffects.lastActionType = "Resolución de duda (Gemini)"
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "actualizar_dato_trabajador" -> {
                        if (bloquearMutacionPorPreguntaOHipotesis) {
                            return blockedMutationJson(
                                "No se modificó ningún dato maestro porque el mensaje es una pregunta, ejemplo o hipótesis."
                            )
                        }
                        val nombre = args.str("nombre_trabajador").orEmpty()
                        val usarAnt = args.bool("usar_trabajador_anterior") ?: false
                        val nuevaHab = args.str("nueva_habitacion")
                        val nuevoPab = args.str("nuevo_pabellon")
                        val nuevaEmp = resolverEmpresa(args.str("nueva_empresa"))
                        val res = ops.actualizarDatoTrabajador(
                            nombreTrabajador = nombre,
                            usarAnterior = usarAnt,
                            nuevaHab = nuevaHab,
                            nuevoPab = nuevoPab,
                            nuevaEmpresa = nuevaEmp,
                            textoOriginal = userInputOriginal,
                            contexto = contexto.copy(turnoActivo = sideEffects.turnoFinal),
                            origen = origen
                        )
                        sideEffects.mutatedDb = true
                        sideEffects.lastActionType = "Actualización de maestro (Gemini)"
                        capturarSideEffects(res, nuevaEmp, sideEffects)
                        responseToJson(res)
                    }

                    "cambiar_turno" -> {
                        val turnoDestino = resolverTurno(args.str("nuevo_turno")) ?: sideEffects.turnoFinal
                        val res = ops.cambiarTurno(
                            nuevoTurno = turnoDestino,
                            contexto = contexto.copy(turnoActivo = sideEffects.turnoFinal)
                        )
                        sideEffects.turnoFinal = res.turnoContexto
                        sideEffects.lastActionType = "Cambio de turno (Gemini)"
                        capturarSideEffects(res, null, sideEffects)
                        responseToJson(res)
                    }

                    "gestionar_memoria" -> {
                        val op = args.str("operacion").orEmpty()
                        val empresa = resolverEmpresa(args.str("empresa"))
                        val texto = args.str("texto").orEmpty()
                        val turnoObj = sideEffects.turnoFinal
                        val ctxObj = contexto.copy(turnoActivo = turnoObj)
                        val res = when (op) {
                            "guardar_nota" -> ops.guardarNota(texto, empresa, turnoObj, ctxObj)
                            "consultar_notas" -> ops.consultarMemoria(empresa, texto.ifBlank { null }, false, turnoObj, ctxObj)
                            "consultar_preferencias" -> ops.consultarMemoria(null, null, true, turnoObj, ctxObj)
                            "olvidar_nota" -> ops.olvidarMemoria(LucyTipoOlvidoMemoria.NOTA_O_RECUERDO, empresa, texto.ifBlank { null }, turnoObj, ctxObj)
                            "olvidar_ultima_preferencia" -> ops.olvidarMemoria(LucyTipoOlvidoMemoria.ULTIMA_PREFERENCIA, null, null, turnoObj, ctxObj)
                            "olvidar_todas_preferencias" -> ops.olvidarMemoria(LucyTipoOlvidoMemoria.TODAS_PREFERENCIAS, null, null, turnoObj, ctxObj)
                            "configurar_preferencias" -> {
                                val vel = args.str("velocidad")?.let { LucyVelocidadRespuesta.fromStored(it) }
                                val est = args.str("estilo_respuesta")?.let { LucyEstiloRespuesta.fromStored(it) }
                                val estVoz = args.str("estilo_voz")?.let { LucyEstiloVoz.fromStored(it) }
                                val instr = texto.takeIf { it.isNotBlank() }
                                ops.configurarPreferencias(vel, est, estVoz, instr, turnoObj)
                            }
                            else -> LucyResponse(
                                turnoContexto = turnoObj,
                                texto = "Operación de memoria no reconocida."
                            )
                        }
                        sideEffects.lastActionType = "Memoria / Preferencias (Gemini)"
                        capturarSideEffects(res, empresa, sideEffects)
                        responseToJson(res)
                    }

                    "consultar_contexto_charla" -> {
                        sideEffects.isReadOnlyQuery = true
                        sideEffects.lastActionType = "Contexto conversacional (Gemini)"
                        val tipo = args.str("tipo").orEmpty()
                        val res = ops.consultarContextoCharla(
                            tipo = tipo,
                            turno = sideEffects.turnoFinal,
                            contexto = contexto.copy(turnoActivo = sideEffects.turnoFinal)
                        )
                        capturarSideEffects(res, null, sideEffects)
                        responseToJson(res)
                    }

                    "filtrar_planilla_vision_por_empresa" -> {
                        sideEffects.isReadOnlyQuery = true
                        sideEffects.lastActionType = "Filtro de planilla por empresa (Vision)"
                        val empresaNombre = args.str("empresa").orEmpty()
                        val turnoObj = resolverTurno(args.str("turno")) ?: sideEffects.turnoFinal
                        val res = ops.filtrarPlanillaVisionPorEmpresa(
                            empresaNombre = empresaNombre,
                            turno = turnoObj,
                            contexto = contexto.copy(turnoActivo = turnoObj)
                        )
                        capturarSideEffects(res, res.visionAnalysis?.empresaFiltroAplicada, sideEffects)
                        responseToJson(res)
                    }

                    else -> buildJsonObject {
                        put("error", "Herramienta no reconocida: $toolName")
                    }
                }
            }
        }
    }

    internal fun esPreguntaMencionOHipotesisSinOrden(input: String): Boolean {
        val raw = input.trim()
        if (raw.isBlank()) return true
        val norm = TextoNormalizer.normalizar(raw)
        val tieneInterrogacion = raw.contains('?') || raw.contains('¿')
        val tieneOrdenExplicitaGrupo = Regex(
            "\\b(?:estos\\s+subieron|subieron:|marca\\s+a|desmarca\\s+a|registra\\s+a|anota\\s+a)\\b"
        ).containsMatchIn(norm)

        if (tieneInterrogacion && !tieneOrdenExplicitaGrupo) {
            return true
        }
        val esHipotesisOEjemplo = Regex(
            "^(?:que\\s+pasa\\s+si|que\\s+pasaria\\s+si|por\\s+ejemplo|suponiendo\\s+que|si\\s+llegara\\s+a|si\\s+te\\s+dijera|imagina\\s+que)\\b"
        ).containsMatchIn(norm)
        if (esHipotesisOEjemplo) return true

        val esPreguntaSinSignos = Regex(
            "^(?:tienes\\s+registrado|esta\\s+registrado|me\\s+puedes\\s+decir\\s+si|sabes\\s+si|podrias\\s+ver\\s+si|consulta\\s+si)\\b"
        ).containsMatchIn(norm)
        if (esPreguntaSinSignos && !tieneOrdenExplicitaGrupo) return true

        return false
    }

    private fun capturarSideEffects(
        res: LucyResponse,
        empresa: EmpresaOficial?,
        sideEffects: LucyGeminiToolSideEffects
    ) {
        sideEffects.turnoFinal = res.turnoContexto
        sideEffects.lastDirectResponseText = res.texto
        if (res.esErrorRecuperable) {
            sideEffects.esErrorRecuperable = true
        }
        if (res.trabajadoresRelacionados.isNotEmpty()) {
            val existentes = sideEffects.trabajadoresRelacionados.mapTo(HashSet()) { it.id }
            res.trabajadoresRelacionados.forEach { t ->
                if (existentes.add(t.id)) {
                    sideEffects.trabajadoresRelacionados.add(t)
                }
            }
        }
        if (res.confirmacionRequerida != null) {
            sideEffects.confirmacionRequerida = res.confirmacionRequerida
        }
        if (res.candidatosDesambiguacion.isNotEmpty()) {
            sideEffects.candidatosDesambiguacion.clear()
            sideEffects.candidatosDesambiguacion.addAll(res.candidatosDesambiguacion)
            sideEffects.accionDesambiguacionSiBajo = res.accionDesambiguacionSiBajo
        }
        if (res.visionAnalysis != null) {
            sideEffects.visionAnalysis = res.visionAnalysis
        }
        val empReal = empresa ?: res.visionAnalysis?.empresaFiltroAplicada ?: res.trabajadoresRelacionados.firstOrNull()?.empresa
        if (empReal != null) {
            sideEffects.referencedCompany = empReal
        }
    }

    private fun responseToJson(res: LucyResponse): JsonObject {
        return buildJsonObject {
            put("turno", res.turnoContexto.nombre)
            put("mensaje_exacto_sistema", res.texto)
            put("es_error_o_aviso", res.esErrorRecuperable)
            put("requiere_confirmacion_tarjeta", res.confirmacionRequerida != null)
            res.confirmacionRequerida?.let { conf ->
                put("confirmacion_descripcion", conf.descripcion)
            }
            if (res.candidatosDesambiguacion.isNotEmpty()) {
                put("requiere_desambiguacion", true)
                put("candidatos_desambiguacion", trabajadoresToJsonArray(res.candidatosDesambiguacion))
            }
            res.visionAnalysis?.let { va ->
                put(
                    "planilla_filtrada",
                    buildJsonObject {
                        put("empresa_filtro", va.empresaFiltroAplicada?.nombre ?: "Ninguna")
                        put("total_filas", va.trabajadoresDetectados.size)
                        put("requiere_revision_humana", va.requiereRevisionHumana)
                    }
                )
            }
            put("trabajadores_relacionados", trabajadoresToJsonArray(res.trabajadoresRelacionados))
        }
    }

    private fun trabajadoresToJsonArray(lista: List<TrabajadorConEstado>): JsonArray {
        return buildJsonArray {
            lista.take(15).forEach { t ->
                add(
                    buildJsonObject {
                        put("id", t.id)
                        put("nombre_completo", t.nombreCompleto)
                        put("empresa", t.empresa.nombre)
                        put("habitacion", t.habitacionMayusculas)
                        put("pabellon", t.pabellonMayusculas)
                        put("estado", if (t.siBajo) "SÍ BAJÓ" else "NO BAJÓ")
                    }
                )
            }
        }
    }

    private fun blockedMutationJson(reason: String): JsonObject = buildJsonObject {
        put("blocked", true)
        put("reason", reason)
    }

    private fun resolverEmpresa(raw: String?): EmpresaOficial? {
        val clean = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        EmpresaOficial.buscarExactaPorNombre(clean)?.let { return it }
        val norm = TextoNormalizer.normalizar(clean)
        return EmpresaOficial.todas
            .sortedByDescending { it.nombre.length }
            .firstOrNull {
                TextoNormalizer.normalizar(it.nombre) == norm ||
                    TextoNormalizer.normalizar(it.codigo) == norm ||
                    norm.contains(TextoNormalizer.normalizar(it.nombre))
            }
    }

    private fun resolverTurno(raw: String?): Turno? {
        val norm = raw?.trim()?.uppercase() ?: return null
        return when {
            norm == "A" || norm.contains("TURNO A") -> Turno.A
            norm == "B" || norm.contains("TURNO B") -> Turno.B
            else -> null
        }
    }

    private fun Map<String, JsonElement>.str(key: String): String? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return prim.contentOrNull?.takeIf { it != "null" }
    }

    private fun Map<String, JsonElement>.bool(key: String): Boolean? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return prim.booleanOrNull
    }
}
