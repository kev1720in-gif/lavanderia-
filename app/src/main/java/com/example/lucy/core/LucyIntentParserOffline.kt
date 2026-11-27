package com.example.lucy.core

import com.example.domain.model.BuscadorTrabajadores
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.Turno
import com.example.lucy.memory.LucyEstiloRespuesta
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyTipoCorreccionAprendida
import com.example.lucy.memory.LucyVelocidadRespuesta

/**
 * Analizador determinista y 100% sin conexión para comandos y consultas en español
 * sobre los datos reales de Lavandería Control y memoria de Lucy.
 */
object LucyIntentParserOffline {

    private val EMPRESAS_POR_LONGITUD: List<Pair<String, EmpresaOficial>> =
        EmpresaOficial.todas
            .map { TextoNormalizer.normalizar(it.nombre) to it }
            .sortedByDescending { it.first.length }

    private val PREFIJO_LUCY_REGEX = Regex("^(?:(?:oye|hola)\\s+)?lucy[,:\\s]+")
    private val TURNO_A_REGEX = Regex("(?:\\b(?:en|del|de|para|al)\\s+)?\\bturno\\s*a\\b")
    private val TURNO_B_REGEX = Regex("(?:\\b(?:en|del|de|para|al)\\s+)?\\bturno\\s*b\\b")

    private val CAMBIAR_TURNO_A_REGEX = Regex(
        "^(?:cambia(?:r)?|pasa(?:r)?|ve|ir|selecciona(?:r)?|usa(?:r)?|activa(?:r)?)\\s+(?:a(?:l)?\\s+)?turno\\s*a\\b"
    )
    private val CAMBIAR_TURNO_B_REGEX = Regex(
        "^(?:cambia(?:r)?|pasa(?:r)?|ve|ir|selecciona(?:r)?|usa(?:r)?|activa(?:r)?)\\s+(?:a(?:l)?\\s+)?turno\\s*b\\b"
    )

    private val REFERENCIA_EMPRESA_REGEX = Regex(
        "\\b(?:de\\s+|en\\s+|sobre\\s+)?(?:la\\s+empresa\\s+anterior|esa\\s+empresa|esta\\s+empresa|la\\s+misma\\s+empresa)\\b"
    )
    private val REFERENCIA_TRABAJADOR_REGEX = Regex(
        "\\b(?:al|el)?\\s*(?:trabajador\\s+que\\s+buscamos\\s+recien|trabajador\\s+anterior|ese\\s+trabajador|esa\\s+persona|persona\\s+anterior|mismo\\s+trabajador)\\b"
    )

    private val GUARDAR_MEMORIA_PREFIJO_REGEX = Regex(
        "^(?:recuerda(?:me)?|anota|guarda|ten\\s+en\\s+cuenta|memoriza)(?:\\s+siempre)?(?:\\s+que)?\\s+"
    )
    private val INSTRUCCION_PREF_PREFIJO_REGEX = Regex(
        "^(?:prefiero\\s+que|siempre\\s+respondeme|siempre\\s+responde|respondeme\\s+siempre|hablame\\s+siempre)\\s+"
    )
    private val OLVIDAR_PREFIJO_REGEX = Regex(
        "^(?:olvida(?:te)?|borra|elimina|quita|restablece)\\s+"
    )
    private val CONSULTA_MEMORIA_REGEX = Regex(
        "^(?:que\\s+(?:te\\s+(?:(?:habia\\s+)?dicho|dije|comente)|recuerdas|sabes|tienes\\s+guardado|notas?\\s+(?:tienes|hay)|recuerdos?\\s+(?:tienes|hay))|cuales\\s+son\\s+mis\\s+preferencias|que\\s+preferencias|como\\s+prefiero\\s+que)\\b"
    )

    private val DESMARCAR_PREFIJO_REGEX = Regex(
        "^(?:desmarca(?:r)?|quita(?:r)?\\s+(?:la\\s+)?marca(?:cion)?|pone(?:r)?\\s+(?:como\\s+)?no\\s+bajo|marca(?:r)?\\s+como\\s+no\\s+bajo)\\s+(?:a\\s+|al\\s+)?"
    )
    private val MARCAR_PREFIJO_REGEX = Regex(
        "^(?:marca(?:r)?|anota(?:r)?|registra(?:r)?|confirma(?:r)?|pone(?:r)?)\\s+(?:como\\s+si\\s+bajo\\s+)?(?:a\\s+|al\\s+)?"
    )
    private val MARCAR_SUFIJO_SI_BAJO_REGEX = Regex("\\s+(?:como\\s+)?si\\s+bajo$")
    private val MARCAR_SUFIJO_NO_BAJO_REGEX = Regex("\\s+(?:como\\s+)?no\\s+bajo$")

    private val CONSULTA_ESTADO_PREFIJO_REGEX = Regex(
        "^(?:ya\\s+bajo|ya\\s+subio|si\\s+bajo|bajo|vino|estado\\s+de)\\s+"
    )
    private val BUSCAR_PREFIJO_REGEX = Regex(
        "^(?:busca(?:r)?|encuentra|ubica(?:r)?|quien(?:es)?\\s+(?:esta(?:n)?|son|hay)\\s+en|quien\\s+es)\\s+(?:a\\s+|el\\s+|la\\s+|en\\s+)?"
    )

    private val PREFIJO_LOTE_SUBIERON_REGEX = Regex(
        "^(?:(?:estos|estas|los\\s+que|las\\s+que)\\s+subieron(?:\\s+al\\s+camion)?|subieron(?:\\s+al\\s+camion)?|si\\s+subieron|si\\s+subio|(?:marca(?:r)?|anota(?:r)?|registra(?:r)?|confirma(?:r)?|agrega(?:r)?)\\s+(?:que\\s+subieron|a\\s+estos|estos|estas|los\\s+que\\s+subieron|como\\s+si\\s+subio|como\\s+si\\s+subieron))\\b"
    )
    private val FRASE_SUBIERON_EN_CUERPO_REGEX = Regex(
        "\\b(?:(?:estos|estas|los\\s+que|las\\s+que)\\s+)?(?:si\\s+)?subieron(?:\\s+al\\s+camion)?\\b|\\b(?:si\\s+)?subio(?:\\s+al\\s+camion)?\\b"
    )
    private val PREFIJO_CONTINUACION_LOTE_REGEX = Regex(
        "^(?:y\\s+)?(?:tambien|ademas|luego|despues|agrega(?:r)?(?:\\s+a)?|anade(?:\\s+a)?|sumale(?:\\s+a)?|otros\\s+son|falto|faltaron)\\b"
    )
    private val PIDE_UBICACION_LOTE_REGEX = Regex(
        "\\b(?:con\\s+ubicacion|con\\s+habitacion|con\\s+pabellon|incluir\\s+habitacion|muestra\\s+habitacion|dime\\s+habitacion)\\b"
    )

    private val FRASES_CLIMA_NO_COMPRENDIDAS = setOf(
        "hace frio", "hace calor", "hoy hace frio", "hoy hace mucho frio", "hace mucho frio"
    )

    private val SALUDOS_SET = setOf(
        "hola", "hola lucy", "buenos dias", "buenas tardes", "buenas noches", "buen dia",
        "buenas", "que tal lucy", "oye lucy"
    )
    private val DESPEDIDAS_SET = setOf(
        "chao", "adios", "hasta luego", "nos vemos", "hasta pronto", "bye"
    )
    private val AGRADECIMIENTOS_SET = setOf(
        "gracias", "muchas gracias", "mil gracias", "te agradezco", "gracias lucy"
    )
    private val ESTADO_ASISTENTE_SET = setOf(
        "como estas", "como te va", "todo bien", "estas ahi", "sigues ahi", "me escuchas",
        "como andas", "que tal"
    )
    private val IDENTIDAD_ASISTENTE_SET = setOf(
        "quien eres", "como te llamas", "cual es tu nombre", "que eres"
    )
    private val CHARLA_BREVE_SET = setOf(
        "ok", "okay", "vale", "listo", "entendido", "de acuerdo", "perfecto", "genial",
        "excelente", "claro", "bien", "super"
    )

    private val RECORDAR_CONVERSACION_REGEX = Regex(
        "^(?:recuerda(?:me)?|dime|repiteme|cual\\s+fue|que\\s+fue)\\s+(?:lo\\s+que\\s+(?:te\\s+)?(?:pregunte|dije|pedi|consulte)|mi\\s+ultima\\s+(?:pregunta|consulta)|lo\\s+ultimo\\s+que\\s+(?:te\\s+)?(?:dije|pregunte))\\b"
    )
    private val QUE_TE_PREGUNTE_REGEX = Regex(
        "^(?:y\\s+)?(?:que\\s+(?:te\\s+)?(?:pregunte|consulte|pedi)(?:\\s+(?:recien|antes|hace\\s+un\\s+rato|hace\\s+poco))?|cual\\s+fue\\s+mi\\s+(?:ultima\\s+)?pregunta)\\b"
    )
    private val QUE_TE_DIJE_REGEX = Regex(
        "^(?:y\\s+)?(?:que\\s+(?:te\\s+)?dije(?:\\s+(?:recien|antes|hace\\s+un\\s+rato|hace\\s+poco|denante))?|que\\s+(?:te\\s+)?acabo\\s+de\\s+decir|que\\s+dije\\s+(?:hace\\s+un\\s+rato|antes|recien))\\b"
    )
    private val DE_QUE_HABLABAMOS_REGEX = Regex(
        "^(?:de\\s+que\\s+(?:estabamos\\s+hablando|hablabamos|hablamos|conversamos|conversabamos)|en\\s+que\\s+estabamos|que\\s+(?:hablamos|conversamos))\\b"
    )
    private val QUE_PASO_DESPUES_REGEX = Regex(
        "^(?:y\\s+)?(?:que\\s+(?:paso|ocurrio|sucedio|hiciste|respondiste|me\\s+respondiste|dijiste|me\\s+dijiste)(?:\\s+(?:despues|luego|al\\s+final))?|y\\s+(?:despues|luego)\\s+que(?:\\s+paso)?)\\b"
    )
    private val QUE_ESTABAMOS_REVISANDO_REGEX = Regex(
        "^(?:que\\s+(?:estabamos|estamos|andabamos)\\s+(?:revisando|viendo|buscando|viendo\\s+recien|revisando\\s+recien)|que\\s+(?:trabajador|empresa|planilla|turno)\\s+(?:estabamos|estamos)\\s+(?:revisando|viendo)|en\\s+que\\s+quedamos|cual\\s+era\\s+(?:el\\s+ultimo\\s+trabajador|la\\s+ultima\\s+empresa))\\b"
    )
    private val HISTORIAL_CAMBIOS_REGEX = Regex(
        "(?:que\\s+cambios\\s+(?:hiciste|realizaste|has\\s+hecho|le\\s+hiciste)|que\\s+datos\\s+cambiaste|que\\s+cambiaste|historial\\s+de\\s+cambios|cambios\\s+recientes|auditoria\\s+de\\s+cambios)"
    )

    private val SEGUIMIENTO_TRABAJADOR_HAB_REGEX = Regex(
        "^(?:(?:y\\s+)?(?:en\\s+que\\s+(?:hab|habitacion)\\s+(?:esta|vive|duerme|figura|quedo)|cual\\s+es\\s+su\\s+(?:hab|habitacion)|(?:su\\s+)?(?:hab|habitacion)))\\s*$"
    )
    private val SEGUIMIENTO_TRABAJADOR_PAB_REGEX = Regex(
        "^(?:(?:y\\s+)?(?:en\\s+que\\s+(?:pab|pabellon)\\s+(?:esta|vive|figura|quedo)|cual\\s+es\\s+su\\s+(?:pab|pabellon)|(?:su\\s+)?(?:pab|pabellon)))\\s*$"
    )
    private val SEGUIMIENTO_TRABAJADOR_EMP_REGEX = Regex(
        "^(?:de\\s+que\\s+empresa\\s+es|a\\s+que\\s+empresa\\s+pertenece|cual\\s+es\\s+su\\s+empresa|su\\s+empresa)\\s*$"
    )
    private val SEGUIMIENTO_EMPRESA_CHECK_REGEX = Regex(
        "^(?:y\\s+)?(?:(?:el|ella|esa\\s+persona|ese\\s+trabajador)\\s+)?(?:es\\s+(?:de|del)|trabaja\\s+en|pertenece\\s+a)\\s*$"
    )
    private val SEGUIMIENTO_TRABAJADOR_ESTADO_REGEX = Regex(
        "^(?:y\\s+)?(?:ya\\s+bajo|bajo|si\\s+bajo|subio|ya\\s+subio|como\\s+esta\\s+(?:el|ella)|cual\\s+es\\s+su\\s+estado)\\s*$"
    )
    private val MARCAR_EN_CONTEXTO_SI_REGEX = Regex(
        "^(?:ahora\\s+)?(?:marcalo|marcala|anotalo|anotala|registralo|registrala|ponlo\\s+como\\s+si\\s+bajo|ponlo\\s+que\\s+subio|marca\\s+(?:que\\s+)?(?:si\\s+)?subio)(?:[,\\s]+(?:porque|ya\\s+que|que)?\\s*(?:si\\s+)?(?:subio|bajo))?(?:\\s+por\\s+favor|\\s+porfa)?$"
    )
    private val MARCAR_EN_CONTEXTO_NO_REGEX = Regex(
        "^(?:ahora\\s+)?(?:desmarcalo|desmarcala|ponlo\\s+como\\s+no\\s+bajo|ponlo\\s+que\\s+no\\s+subio|marca\\s+(?:que\\s+)?no\\s+(?:subio|bajo))(?:\\s+por\\s+favor|\\s+porfa)?$"
    )

    private val DUDA_DEJAR_PENDIENTE_REGEX = Regex(
        "^(?:dejalo(?:s)?\\s+pendiente(?:s)?|dejala\\s+pendiente|dejar\\s+pendiente|omite(?:lo)?|omitir|salta(?:lo)?|saltar|ninguno|no\\s+lo\\s+marques|cancela(?:r)?\\s+duda|olvida\\s+la\\s+duda)$"
    )
    private val DUDA_FORZAR_MARCACION_REGEX = Regex(
        "^(?:marcalo\\s+igual|marcala\\s+igual|marcalos\\s+igual|igual\\s+marcalo|igual\\s+subio|si\\s+marcalo|marcalo\\s+aunque\\s+sea\\s+de\\s+otra\\s+empresa)$"
    )

    private val CORRECCION_NO_ESE_ES_REGEX = Regex(
        "^(?:no[,:\\s]+(?:(?:ese|esa|el|ella|ese\\s+trabajador)\\s+)?(?:es|era)|quise\\s+decir|queria\\s+decir|me\\s+equivoque[,:\\s]+(?:es|era))\\s+(.+)$"
    )
    private val CORRECCION_NO_ES_X_ES_Y_REGEX = Regex(
        "^(?:no\\s+es\\s+(.+?)[,:\\s]+(?:sino\\s+)?es|(.+?)\\s+no\\s+es[,:\\s]+es)\\s+(.+)$"
    )
    private val CORRECCION_CUANDO_DIGO_REGEX = Regex(
        "^cuando\\s+(?:digo|diga|te\\s+digo|dije)\\s+(.+?)\\s+(?:es|era|me\\s+refiero\\s+a|quise\\s+decir|corresponde\\s+a)\\s+(.+)$"
    )
    private val CORRECCION_FILA_VISION_REGEX = Regex(
        "^(?:en\\s+la\\s+)?fila\\s+(\\d+)\\s+(?:es|dice|va|corresponde\\s+a)\\s+(.+)$"
    )
    private val CORRECCION_PLANILLA_DICE_REGEX = Regex(
        "^(?:en\\s+la\\s+planilla\\s+dice|la\\s+planilla\\s+dice)\\s+(.+?)\\s+(?:pero\\s+es|corresponde\\s+a|es)\\s+(.+)$"
    )

    private val CAMBIO_HAB_DESDE_HASTA_REGEX = Regex(
        "^(.+?)\\s+(?:ya\\s+no\\s+(?:esta|vive|duerme|figura)\\s+en\\s+(?:la\\s+)?(?:hab|habitacion)\\s+([a-z0-9-]+)(?:\\s*[.,;]?\\s*(?:y\\s+)?ahora\\s+(?:esta|vive|duerme|figura)?\\s*(?:en\\s+)?(?:(?:la|a\\s+la)\\s+)?(?:hab|habitacion)?\\s*([a-z0-9-]+))|(?:paso|cambio)\\s+de\\s+(?:la\\s+)?(?:hab|habitacion)\\s+([a-z0-9-]+)\\s+a\\s+(?:la\\s+)?(?:hab|habitacion)?\\s*([a-z0-9-]+))$"
    )
    private val CAMBIO_PAB_DESDE_HASTA_REGEX = Regex(
        "^(.+?)\\s+(?:ya\\s+no\\s+(?:esta|vive|figura)\\s+en\\s+(?:el\\s+)?(?:pab|pabellon)\\s+([a-z0-9-]+(?:\\s+[a-z0-9-]+)?)\\s*[.,;]?\\s*(?:y\\s+)?ahora\\s+(?:esta|vive|figura)?\\s*(?:en\\s+)?(?:(?:el|al)\\s+)?(?:pab|pabellon)?\\s*([a-z0-9-]+(?:\\s+[a-z0-9-]+)?)|(?:paso|cambio)\\s+de\\s+(?:el\\s+)?(?:pab|pabellon)\\s+([a-z0-9-]+(?:\\s+[a-z0-9-]+)?)\\s+a\\s+(?:el\\s+|al\\s+)?(?:pab|pabellon)?\\s*([a-z0-9-]+(?:\\s+[a-z0-9-]+)?))$"
    )
    private val CAMBIO_CMD_HAB_REGEX = Regex(
        "^(?:cambia|actualiza|modifica|corrige|mueve|pon|pone|pasa)\\s+(?:(?:la\\s+)?(?:hab|habitacion)\\s+(?:de\\s+)?(.+?)\\s+(?:a|por|en)\\s+(?:la\\s+)?(?:hab|habitacion)?\\s*([a-z0-9-]+)|(?:a\\s+)?(.+?)\\s+(?:a\\s+la|en\\s+la)\\s+(?:hab|habitacion)\\s+([a-z0-9-]+))$"
    )
    private val CAMBIO_CMD_PAB_REGEX = Regex(
        "^(?:cambia|actualiza|modifica|corrige|mueve|pon|pone|pasa)\\s+(?:(?:el\\s+)?(?:pab|pabellon)\\s+(?:de\\s+)?(.+?)\\s+(?:a|por|en)\\s+(?:el\\s+)?(?:pab|pabellon)?\\s*([a-z0-9-]+(?:\\s+[a-z0-9-]+)?)|(?:a\\s+)?(.+?)\\s+(?:al|en\\s+el)\\s+(?:pab|pabellon)\\s+([a-z0-9-]+(?:\\s+[a-z0-9-]+)?))$"
    )
    private val CAMBIO_AHORA_HAB_REGEX = Regex(
        "^(?:(.+?)\\s+)?ahora\\s+(?:esta|vive|duerme|figura)\\s+en\\s+(?:la\\s+)?(?:hab|habitacion)\\s+([a-z0-9-]+)$"
    )
    private val CAMBIO_AHORA_PAB_REGEX = Regex(
        "^(?:(.+?)\\s+)?ahora\\s+(?:esta|vive|figura)\\s+en\\s+(?:el\\s+)?(?:pab|pabellon)\\s+([a-z0-9-]+(?:\\s+[a-z0-9-]+)?)$"
    )

    private val INICIO_INTERROGATIVO_REGEX = Regex(
        "^(?:que|cual|cuales|quien|quienes|como|cuando|donde|adonde|cuanto|cuantos|cuanta|cuantas|por\\s+que|para\\s+que|puedes|podrias|sabes|tienes|recuerdas|estabamos|hiciste|paso|dijiste|dije|pregunte|y\\s+(?:que|como|donde|cual|quien|si))\\b"
    )
    private val VERBO_NO_BUSQUEDA_REGEX = Regex(
        "^(?:abre|cierra|dime|cuentame|explica|explicame|ayudame|puedes|podrias|sabes|quiero|necesito|tengo|estoy|vamos|haz|hazme|mira|oye|revisa|borra|elimina|crea|agrega|anade|pon|pone|cambia|hace|recuerda|olvida|responde|habla)\\b"
    )
    private val PALABRAS_NO_NOMBRE = setOf(
        "eso", "esto", "aquello", "algo", "nada", "todo", "todos", "alguien",
        "te", "me", "nos", "se", "lo", "los", "las", "un", "una", "unos", "unas",
        "rato", "despues", "luego", "antes", "ahora", "ayer", "hoy", "manana",
        "recuerdo", "pregunta", "respuesta", "conversacion", "charla", "tema",
        "porton", "puerta", "ventana", "hora", "clima", "tiempo", "favor", "porfa",
        "planilla", "foto", "imagen", "archivo"
    )
    private val OBJETIVOS_VAGOS_BUSQUEDA = setOf(
        "eso", "esto", "algo", "nada", "alguien", "ese", "esa", "lo que dije"
    )
    private val PALABRAS_CONTEXTO_RESUMEN = setOf(
        "turno", "trabajador", "trabajadores", "persona", "personas",
        "bajo", "bajaron", "bajada", "falta", "faltan", "pendiente", "pendientes",
        "maestro", "listado", "dotacion", "personal", "lavanderia", "registro", "registros"
    )

    fun parse(
        entrada: String,
        turnoActivo: Turno? = null,
        hayContextoMarcacionLote: Boolean = false,
        hayDudasPendientes: Boolean = false,
        hayAnalisisVisionActivo: Boolean = false
    ): LucyIntent {
        val rawTrimmed = entrada.trim()
        if (rawTrimmed.isBlank()) {
            return LucyIntent.NoComprendido(entrada)
        }

        val rawSinLucy = rawTrimmed
            .replace(Regex("^(?:(?:oye|hola)\\s+)?lucy[,:\\s]+", RegexOption.IGNORE_CASE), "")
            .trim()
        val normConSeparadores = TextoNormalizer.normalizar(rawSinLucy)
            .replace(Regex("[¿?¡!\"'()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val normConComas = TextoNormalizer.normalizar(rawTrimmed)
            .replace(Regex("[¿?¡!\"',.;:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val norm = normConComas.replace(PREFIJO_LUCY_REGEX, "").trim()

        if (norm.isBlank()) {
            return LucyIntent.NoComprendido(entrada)
        }

        // 0. Comentarios casuales climáticos mantenidos como NoComprendido
        if (norm in FRASES_CLIMA_NO_COMPRENDIDAS || normConComas in FRASES_CLIMA_NO_COMPRENDIDAS) {
            return LucyIntent.NoComprendido(rawTrimmed)
        }

        // 0b. Conversación general natural (saludos, agradecimientos, estado/identidad, confirmaciones breves)
        detectarConversacionGeneral(norm, normConComas)?.let { subtipo ->
            return LucyIntent.ConversacionGeneral(subtipo = subtipo, textoOriginal = rawTrimmed)
        }

        // 1. Ayuda
        if (norm == "ayuda" || norm == "help" || norm.contains("que puedes hacer") || norm == "comandos") {
            return LucyIntent.Ayuda
        }

        // 2. Cambio explícito de turno
        if (CAMBIAR_TURNO_A_REGEX.containsMatchIn(norm) || norm == "turno a") {
            return LucyIntent.CambiarTurno(Turno.A)
        }
        if (CAMBIAR_TURNO_B_REGEX.containsMatchIn(norm) || norm == "turno b") {
            return LucyIntent.CambiarTurno(Turno.B)
        }

        val (sinTurno, turnoSolicitado) = extraerTurno(norm)
        val (sinEmpresa, empresa) = extraerEmpresa(sinTurno)
        val cuerpo = limpiarConectoresResiduales(sinEmpresa)

        val usaEmpresaAnterior = REFERENCIA_EMPRESA_REGEX.containsMatchIn(norm)
        val usaTrabajadorAnterior = REFERENCIA_TRABAJADOR_REGEX.containsMatchIn(norm)

        // 2a. Historial de cambios maestros realizados por Lucy
        if (HISTORIAL_CAMBIOS_REGEX.containsMatchIn(norm)) {
            val nombreFiltrado = extraerNombreObjetivoHistorialCambios(cuerpo)
            return LucyIntent.ConsultarHistorialCambios(
                nombreTrabajador = nombreFiltrado,
                turnoSolicitado = turnoSolicitado,
                textoOriginal = rawTrimmed
            )
        }

        // 2b. Preguntas sobre mensajes anteriores y recuperación del contexto actual
        if (QUE_ESTABAMOS_REVISANDO_REGEX.containsMatchIn(norm)) {
            return LucyIntent.RecuperarContextoActual(textoOriginal = rawTrimmed)
        }
        if (QUE_PASO_DESPUES_REGEX.containsMatchIn(norm)) {
            return LucyIntent.ConsultarConversacionReciente(
                pideContinuacion = true,
                textoOriginal = rawTrimmed
            )
        }
        if (DE_QUE_HABLABAMOS_REGEX.containsMatchIn(norm)) {
            return LucyIntent.ConsultarConversacionReciente(
                pideResumenCharla = true,
                textoOriginal = rawTrimmed
            )
        }
        if (RECORDAR_CONVERSACION_REGEX.containsMatchIn(norm) || QUE_TE_PREGUNTE_REGEX.containsMatchIn(norm)) {
            return LucyIntent.ConsultarConversacionReciente(
                pideUltimaPregunta = true,
                textoOriginal = rawTrimmed
            )
        }
        if (QUE_TE_DIJE_REGEX.containsMatchIn(norm) && empresa == null && !usaEmpresaAnterior && !norm.contains(" sobre ")) {
            return LucyIntent.ConsultarConversacionReciente(
                pideUltimaPregunta = false,
                textoOriginal = rawTrimmed
            )
        }

        // 2c. Seguimiento contextual sobre el trabajador visto recientemente
        if ((empresa != null || usaEmpresaAnterior) && SEGUIMIENTO_EMPRESA_CHECK_REGEX.containsMatchIn(sinEmpresa.trim())) {
            return LucyIntent.ConsultarDetalleTrabajadorAnterior(
                campo = LucyDetalleTrabajadorCampo.EMPRESA_VERIFICACION,
                textoOriginal = rawTrimmed,
                empresaConsulta = empresa
            )
        }
        when {
            SEGUIMIENTO_TRABAJADOR_HAB_REGEX.containsMatchIn(norm) -> {
                return LucyIntent.ConsultarDetalleTrabajadorAnterior(
                    campo = LucyDetalleTrabajadorCampo.HABITACION,
                    textoOriginal = rawTrimmed
                )
            }
            SEGUIMIENTO_TRABAJADOR_PAB_REGEX.containsMatchIn(norm) -> {
                return LucyIntent.ConsultarDetalleTrabajadorAnterior(
                    campo = LucyDetalleTrabajadorCampo.PABELLON,
                    textoOriginal = rawTrimmed
                )
            }
            SEGUIMIENTO_TRABAJADOR_EMP_REGEX.containsMatchIn(norm) -> {
                return LucyIntent.ConsultarDetalleTrabajadorAnterior(
                    campo = LucyDetalleTrabajadorCampo.EMPRESA,
                    textoOriginal = rawTrimmed
                )
            }
            SEGUIMIENTO_TRABAJADOR_ESTADO_REGEX.containsMatchIn(norm) -> {
                val esPreguntaBreveSubio = norm.trim() in setOf("subio", "ya subio", "bajo", "ya bajo", "y ya bajo", "y subio")
                return LucyIntent.ConsultarEstadoTrabajador(
                    query = "",
                    turnoSolicitado = turnoSolicitado,
                    empresa = empresa,
                    usarEmpresaAnterior = usaEmpresaAnterior,
                    usarTrabajadorAnterior = true,
                    respuestaBreve = esPreguntaBreveSubio
                )
            }
        }

        // 2d. Marcación directa del trabajador en contexto por pronombre ("Ahora márcalo, porque sí subió", "Márcalo")
        if (MARCAR_EN_CONTEXTO_SI_REGEX.containsMatchIn(norm)) {
            return LucyIntent.MarcarTrabajadorEnContexto(
                marcarComoSiBajo = true,
                textoOriginal = rawTrimmed
            )
        }
        if (MARCAR_EN_CONTEXTO_NO_REGEX.containsMatchIn(norm)) {
            return LucyIntent.MarcarTrabajadorEnContexto(
                marcarComoSiBajo = false,
                textoOriginal = rawTrimmed
            )
        }

        // 2e. Correcciones explícitas del usuario para aprendizaje persistente ("No, ese es...", "Cuando digo X es Y", "En la fila N es...")
        detectarCorreccionUsuario(
            rawTrimmed = rawTrimmed,
            norm = norm
        )?.let { return it }

        // 2f. Actualización de datos maestros (habitación, pabellón, empresa) — nunca en preguntas
        detectarActualizacionDatoMaestro(
            rawTrimmed = rawTrimmed,
            norm = norm,
            normSinSeparadores = normConSeparadores,
            turnoSolicitado = turnoSolicitado,
            empresaDetectada = empresa,
            usaTrabajadorAnterior = usaTrabajadorAnterior
        )?.let { return it }

        // 3. Preguntas sobre memoria o preferencias (solo lectura)
        if (CONSULTA_MEMORIA_REGEX.containsMatchIn(norm) ||
            norm.startsWith("muestrame tus preferencias") ||
            norm.startsWith("que recuerdas")
        ) {
            val pidePreferencias = listOf("preferencia", "preferencias", "como prefiero", "velocidad", "estilo")
                .any { norm.contains(it) } && empresa == null && !usaEmpresaAnterior
            val queryLimpia = norm
                .replace(CONSULTA_MEMORIA_REGEX, "")
                .replace(TURNO_A_REGEX, " ")
                .replace(TURNO_B_REGEX, " ")
                .replace(REFERENCIA_EMPRESA_REGEX, " ")
                .let { texto ->
                    empresa?.let { emp ->
                        texto.replace(Regex("\\b${Regex.escape(TextoNormalizer.normalizar(emp.nombre))}\\b"), " ")
                    } ?: texto
                }
                .replace(Regex("\\b(?:sobre|acerca\\s+de|de|del|lo\\s+que)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.isNotBlank() }

            return LucyIntent.ConsultarMemoria(
                empresa = empresa,
                usarEmpresaAnterior = usaEmpresaAnterior,
                turnoSolicitado = turnoSolicitado,
                soloPreferencias = pidePreferencias,
                query = queryLimpia
            )
        }

        // 4. Comandos para olvidar o restablecer recuerdos/preferencias (sin confundir con "elimina a Juan Pérez")
        if (OLVIDAR_PREFIJO_REGEX.containsMatchIn(norm)) {
            val esComandoDestructivoTrabajador = (norm.startsWith("elimina ") || norm.startsWith("borra ")) &&
                !listOf("recuerdo", "nota", "memoria", "preferencia", "contexto", "correccion", "correcciones", "lo que te dije", "eso")
                    .any { norm.contains(it) }
            if (esComandoDestructivoTrabajador) {
                return LucyIntent.AclaracionAmbigua(
                    textoOriginal = rawTrimmed,
                    esPregunta = false,
                    motivo = "no_eliminar_trabajadores"
                )
            }

            val resto = OLVIDAR_PREFIJO_REGEX.replace(norm, "").trim()
            val tipo = when {
                norm.contains("todas") && norm.contains("preferencia") ||
                    norm.startsWith("restablece") && norm.contains("preferencia") ->
                    LucyTipoOlvidoMemoria.TODAS_PREFERENCIAS
                resto.contains("preferencia") || resto == "esa preferencia" || resto == "eso de hablar" ->
                    LucyTipoOlvidoMemoria.ULTIMA_PREFERENCIA
                norm.contains("todo el contexto") || norm.contains("toda la memoria del turno") ->
                    LucyTipoOlvidoMemoria.TODO_TURNO
                else -> LucyTipoOlvidoMemoria.NOTA_O_RECUERDO
            }
            val refLimpia = resto
                .replace(TURNO_A_REGEX, " ")
                .replace(TURNO_B_REGEX, " ")
                .replace(REFERENCIA_EMPRESA_REGEX, " ")
                .let { texto ->
                    empresa?.let { emp ->
                        texto.replace(Regex("\\b${Regex.escape(TextoNormalizer.normalizar(emp.nombre))}\\b"), " ")
                    } ?: texto
                }
                .replace(Regex("^(?:lo\\s+que\\s+te\\s+(?:(?:habia\\s+)?dicho|dije|comente)|ese\\s+recuerdo|esa\\s+nota|el\\s+recuerdo)\\s*"), "")
                .replace(Regex("\\b(?:sobre|acerca\\s+de|de|del)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.isNotBlank() && it !in setOf("recuerdo", "nota", "preferencia", "eso") }

            return LucyIntent.OlvidarMemoria(
                tipo = tipo,
                empresa = empresa,
                usarEmpresaAnterior = usaEmpresaAnterior,
                turnoSolicitado = turnoSolicitado,
                referenciaTexto = refLimpia
            )
        }

        // 5. Preferencias explícitas de conversación (compartidas entre turnos)
        detectarPreferenciaConversacion(
            norm = norm,
            rawSinLucy = rawSinLucy,
            tieneContextoOperativo = empresa != null || usaEmpresaAnterior || turnoSolicitado != null
        )?.let { return it }

        // 6. Instrucción explícita para guardar recuerdo/nota operativa asociada al turno
        if (GUARDAR_MEMORIA_PREFIJO_REGEX.containsMatchIn(norm)) {
            val textoOriginalNota = rawSinLucy
                .replace(
                    Regex("^(?:recuerda(?:me)?|anota|guarda|ten\\s+en\\s+cuenta|memoriza)(?:\\s+siempre)?(?:\\s+que)?\\s+", RegexOption.IGNORE_CASE),
                    ""
                )
                .trim()
            if (textoOriginalNota.isNotBlank()) {
                return LucyIntent.GuardarNotaOperativa(
                    texto = textoOriginalNota,
                    empresa = empresa,
                    usarEmpresaAnterior = usaEmpresaAnterior,
                    turnoDestino = turnoSolicitado
                )
            }
        }

        // 7. Desmarcar (NO BAJÓ) - evaluar antes que marcar para evitar colisión con "marca como no bajo"
        if (DESMARCAR_PREFIJO_REGEX.containsMatchIn(norm) ||
            (MARCAR_PREFIJO_REGEX.containsMatchIn(norm) && MARCAR_SUFIJO_NO_BAJO_REGEX.containsMatchIn(norm))
        ) {
            val target = limpiarSufijosMarcacion(
                DESMARCAR_PREFIJO_REGEX.replace(cuerpo, "").let { MARCAR_PREFIJO_REGEX.replace(it, "") }
            ).let { limpiarReferencias(it) }
            if (target.isNotBlank() || usaTrabajadorAnterior) {
                if (turnoActivo != null && turnoSolicitado != null && turnoSolicitado != turnoActivo) {
                    return LucyIntent.ConflictoTurno(
                        turnoActivo = turnoActivo,
                        turnoSolicitado = turnoSolicitado,
                        query = target.ifBlank { "trabajador reciente" },
                        marcarComoSiBajo = false
                    )
                }
                return LucyIntent.SolicitarMarcacion(
                    query = target,
                    marcarComoSiBajo = false,
                    empresa = empresa,
                    turnoSolicitado = turnoSolicitado,
                    usarEmpresaAnterior = usaEmpresaAnterior,
                    usarTrabajadorAnterior = usaTrabajadorAnterior
                )
            }
        }

        // 7a. Filtrado estricto por empresa sobre planilla analizada por Lucy Vision (solo cuando hay foto activa)
        if (hayAnalisisVisionActivo) {
            detectarFiltroEmpresaVision(
                rawSinLucy = rawSinLucy,
                norm = norm,
                empresa = empresa,
                turnoSolicitado = turnoSolicitado,
                usaTrabajadorAnterior = usaTrabajadorAnterior
            )?.let { return it }
        }

        // 7b. Marcación múltiple / SÍ SUBIÓ por voz o texto (sin confirmación para lotes y "subieron")
        detectarMarcacionMultiple(
            rawTrimmed = rawTrimmed,
            norm = norm,
            normConSeparadores = normConSeparadores,
            turnoActivo = turnoActivo,
            turnoSolicitado = turnoSolicitado,
            empresa = empresa,
            usaEmpresaAnterior = usaEmpresaAnterior,
            hayContextoMarcacionLote = hayContextoMarcacionLote
        )?.let { return it }

        // 8. Marcar individual (SÍ BAJÓ) - mantiene confirmación explícita
        if (MARCAR_PREFIJO_REGEX.containsMatchIn(norm)) {
            val target = limpiarSufijosMarcacion(MARCAR_PREFIJO_REGEX.replace(cuerpo, ""))
                .let { limpiarReferencias(it) }
            if (target.isNotBlank() || usaTrabajadorAnterior) {
                if (turnoActivo != null && turnoSolicitado != null && turnoSolicitado != turnoActivo) {
                    return LucyIntent.ConflictoTurno(
                        turnoActivo = turnoActivo,
                        turnoSolicitado = turnoSolicitado,
                        query = target.ifBlank { "trabajador reciente" },
                        marcarComoSiBajo = true
                    )
                }
                return LucyIntent.SolicitarMarcacion(
                    query = target,
                    marcarComoSiBajo = true,
                    empresa = empresa,
                    turnoSolicitado = turnoSolicitado,
                    usarEmpresaAnterior = usaEmpresaAnterior,
                    usarTrabajadorAnterior = usaTrabajadorAnterior
                )
            }
        }

        // 9. Pendientes / quién falta (sin confundir con búsqueda de una persona específica)
        val pidePendientes = listOf(
            "pendiente", "quienes faltan", "quien falta", "no han bajado",
            "no bajaron", "sin bajar", "faltan por bajar", "faltantes"
        ).any { norm.contains(it) }
        if (pidePendientes && !norm.contains("cuantos") && !DUDA_DEJAR_PENDIENTE_REGEX.containsMatchIn(norm)) {
            return LucyIntent.ConsultarPendientes(
                turnoSolicitado = turnoSolicitado,
                empresa = empresa,
                usarEmpresaAnterior = usaEmpresaAnterior
            )
        }

        // 10. Consultar empresas oficiales y su avance por turno
        val pideEmpresas = cuerpo in setOf(
            "empresas", "empresa", "consultar empresas", "consulta empresas",
            "ver empresas", "listar empresas", "lista de empresas",
            "listado de empresas", "que empresas hay", "cuales empresas",
            "resumen empresas", "resumen de empresas", "estado de empresas"
        ) || (norm.contains("empresas") && (norm.startsWith("consulta") || norm.startsWith("ver ") || norm.startsWith("lista") || norm.startsWith("muestra") || norm.startsWith("que ")))
        if (pideEmpresas) {
            return LucyIntent.ConsultarEmpresas(
                turnoSolicitado = turnoSolicitado,
                empresa = empresa
            )
        }

        // 11. Consultar/listar trabajadores del turno o empresa
        val pideListarTrabajadores = cuerpo in setOf(
            "trabajadores", "consultar trabajadores", "consulta trabajadores",
            "ver trabajadores", "listar trabajadores", "lista de trabajadores",
            "listado de trabajadores", "todos los trabajadores", "listado",
            "ver listado", "consultar listado", "personal", "dotacion"
        )
        if (pideListarTrabajadores) {
            return LucyIntent.ListarTrabajadores(
                turnoSolicitado = turnoSolicitado,
                empresa = empresa,
                usarEmpresaAnterior = usaEmpresaAnterior
            )
        }

        // 12. Resumen o conteos de turno/empresa (requiere contexto operativo suficiente para evitar falsos positivos)
        val tieneContextoOperativoResumen = empresa != null ||
            usaEmpresaAnterior ||
            turnoSolicitado != null ||
            PALABRAS_CONTEXTO_RESUMEN.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(norm) }

        val pideResumenExplicito = norm.contains("resumen") ||
            cuerpo in setOf("total", "conteo", "estadistica", "estadisticas", "avance", "estado")
        val pideResumenConContexto = tieneContextoOperativoResumen && listOf(
            "cuantos", "cuantas", "cuanto falta", "total", "conteo",
            "estadistica", "avance", "como va", "como esta", "estado"
        ).any { norm.contains(it) }

        if (pideResumenExplicito || pideResumenConContexto) {
            return LucyIntent.ResumenTurno(
                turnoSolicitado = turnoSolicitado,
                empresa = empresa,
                usarEmpresaAnterior = usaEmpresaAnterior
            )
        }

        // 13. Consulta directa de estado de trabajador ("¿bajó Pérez?", "¿ya bajó Rojas?", "¿cómo está Juan Pérez?")
        if (CONSULTA_ESTADO_PREFIJO_REGEX.containsMatchIn(cuerpo)) {
            val query = limpiarReferencias(CONSULTA_ESTADO_PREFIJO_REGEX.replace(cuerpo, "").trim())
            if (query.isNotBlank() || usaTrabajadorAnterior) {
                return LucyIntent.ConsultarEstadoTrabajador(
                    query = query,
                    turnoSolicitado = turnoSolicitado,
                    empresa = empresa,
                    usarEmpresaAnterior = usaEmpresaAnterior,
                    usarTrabajadorAnterior = usaTrabajadorAnterior
                )
            }
        }
        if (cuerpo.startsWith("como esta ")) {
            val objetivoRaw = cuerpo.removePrefix("como esta ").trim()
            val query = limpiarReferencias(objetivoRaw)
            val empiezaConArticuloObjeto = Regex("^(?:el|la|los|las|un|una|este|esta|ese|esa)\\s+")
                .containsMatchIn(objetivoRaw)
            if (usaTrabajadorAnterior && query.isBlank()) {
                return LucyIntent.ConsultarEstadoTrabajador(
                    query = "",
                    turnoSolicitado = turnoSolicitado,
                    empresa = empresa,
                    usarEmpresaAnterior = usaEmpresaAnterior,
                    usarTrabajadorAnterior = true
                )
            }
            if (query.isNotBlank() && !empiezaConArticuloObjeto && !esOrdenOCharlaNoBuscable(query)) {
                return LucyIntent.ConsultarEstadoTrabajador(
                    query = query,
                    turnoSolicitado = turnoSolicitado,
                    empresa = empresa,
                    usarEmpresaAnterior = usaEmpresaAnterior,
                    usarTrabajadorAnterior = usaTrabajadorAnterior
                )
            }
        }

        // 13b. Resolución de dudas pendientes de un dictado reciente (cuando no fue una pregunta ni comando explícito)
        if (hayDudasPendientes) {
            detectarResolucionDudaPendiente(
                rawTrimmed = rawTrimmed,
                norm = norm,
                cuerpo = cuerpo,
                empresa = empresa,
                turnoSolicitado = turnoSolicitado
            )?.let { return it }
        }

        // 14. Búsqueda explícita vs mención pasiva segura (NUNCA tratar preguntas o frases ambiguas como nombres)
        val tienePrefijoBusquedaExplicito = BUSCAR_PREFIJO_REGEX.containsMatchIn(cuerpo)
        val queryBusqueda = limpiarReferencias(BUSCAR_PREFIJO_REGEX.replace(cuerpo, "").trim())
        if (usaTrabajadorAnterior && queryBusqueda.isBlank()) {
            return LucyIntent.BuscarTrabajador(
                query = "",
                turnoSolicitado = turnoSolicitado,
                empresa = empresa,
                usarEmpresaAnterior = usaEmpresaAnterior,
                usarTrabajadorAnterior = true
            )
        }

        if (tienePrefijoBusquedaExplicito) {
            if (queryBusqueda.isBlank() || queryBusqueda in OBJETIVOS_VAGOS_BUSQUEDA) {
                return LucyIntent.AclaracionAmbigua(
                    textoOriginal = rawTrimmed,
                    esPregunta = esFraseInterrogativa(rawTrimmed, norm),
                    motivo = "busqueda_vaga"
                )
            }
            val analizada = BuscadorTrabajadores.analizarQuery(queryBusqueda)
            if (!analizada.estaVacia) {
                return LucyIntent.BuscarTrabajador(
                    query = queryBusqueda,
                    turnoSolicitado = turnoSolicitado,
                    empresa = empresa,
                    usarEmpresaAnterior = usaEmpresaAnterior,
                    usarTrabajadorAnterior = usaTrabajadorAnterior,
                    esMencionPasiva = false
                )
            }
        }

        if (empresa != null || usaEmpresaAnterior) {
            if (cuerpo.isBlank() || esFraseInterrogativa(rawTrimmed, norm) || esOrdenOCharlaNoBuscable(cuerpo)) {
                return LucyIntent.ResumenTurno(
                    turnoSolicitado = turnoSolicitado,
                    empresa = empresa,
                    usarEmpresaAnterior = usaEmpresaAnterior
                )
            }
        }

        val esPregunta = esFraseInterrogativa(rawTrimmed, norm)
        if (esPregunta) {
            return LucyIntent.AclaracionAmbigua(
                textoOriginal = rawTrimmed,
                esPregunta = true,
                motivo = "pregunta_ambigua"
            )
        }

        if (cuerpo.isNotBlank() && !esOrdenOCharlaNoBuscable(cuerpo)) {
            val analizada = BuscadorTrabajadores.analizarQuery(cuerpo)
            if (!analizada.estaVacia) {
                return LucyIntent.BuscarTrabajador(
                    query = cuerpo,
                    turnoSolicitado = turnoSolicitado,
                    empresa = empresa,
                    usarEmpresaAnterior = usaEmpresaAnterior,
                    usarTrabajadorAnterior = usaTrabajadorAnterior,
                    esMencionPasiva = true
                )
            }
        }

        return if (cuerpo.isNotBlank()) {
            LucyIntent.AclaracionAmbigua(
                textoOriginal = rawTrimmed,
                esPregunta = false,
                motivo = "frase_ambigua"
            )
        } else {
            LucyIntent.NoComprendido(rawTrimmed)
        }
    }

    private fun detectarFiltroEmpresaVision(
        rawSinLucy: String,
        norm: String,
        empresa: EmpresaOficial?,
        turnoSolicitado: Turno?,
        usaTrabajadorAnterior: Boolean
    ): LucyIntent.FiltrarPlanillaVisionEmpresa? {
        if (usaTrabajadorAnterior) return null
        if (PREFIJO_LOTE_SUBIERON_REGEX.containsMatchIn(norm) ||
            FRASE_SUBIERON_EN_CUERPO_REGEX.containsMatchIn(norm) ||
            MARCAR_PREFIJO_REGEX.containsMatchIn(norm) ||
            DESMARCAR_PREFIJO_REGEX.containsMatchIn(norm)
        ) {
            return null
        }
        // Evitar confundir preguntas de estado singular ("¿él es de Fluitek?")
        if (Regex("^(?:el|ella|ese\\s+trabajador|esa\\s+persona)\\s+(?:es|pertenece)\\b").containsMatchIn(norm)) {
            return null
        }

        val atribuciónPluralRegex = Regex(
            "^(?:ahora\\s+)?(?:todos\\s+|todas\\s+)?(?:ellos\\s+|ellas\\s+|estos\\s+|estas\\s+|esos\\s+|esas\\s+)?(?:son|pertenecen|corresponden)(?:\\s+todos|\\s+todas)?\\s+(?:a\\s+|de\\s+|del\\s+)?(?:la\\s+empresa\\s+|el\\s+personal\\s+de\\s+)?(.+)$"
        )
        val matchPlural = atribuciónPluralRegex.find(norm)

        val palabrasFiltroVision = listOf(
            "filtra", "filtrar", "compara", "comparar", "coincid",
            "solamente", "unicamente", "solo los de", "solo personal",
            "esos nombres", "estos nombres", "la foto", "la planilla", "la imagen",
            "revisa los que", "busca esos nombres"
        )
        val tieneIndicadorFiltroVision = palabrasFiltroVision.any { norm.contains(it) }

        if (matchPlural == null && !tieneIndicadorFiltroVision) {
            return null
        }

        val empresaTexto = if (empresa != null) {
            empresa.nombre
        } else {
            val candidatoRaw = matchPlural?.groupValues?.getOrNull(1)
                ?: Regex("(?:con\\s+el\\s+personal\\s+de|con\\s+los\\s+de|personal\\s+de|la\\s+empresa|con|en|de|del|a)\\s+([a-zñ0-9\\s-]+)$")
                    .find(norm)
                    ?.groupValues
                    ?.getOrNull(1)
            val limpio = candidatoRaw
                ?.replace(TURNO_A_REGEX, " ")
                ?.replace(TURNO_B_REGEX, " ")
                ?.replace(Regex("^(?:la\\s+empresa|el\\s+personal\\s+de|personal\\s+de|los\\s+de|las\\s+de)\\s+"), "")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        it !in setOf("esa empresa", "la empresa", "la foto", "la planilla", "turno a", "turno b", "todos", "ellos")
                }
                ?: return null
            // Conservar capitalización legible si viene en rawSinLucy
            extraerFragmentoOriginalEmpresa(rawSinLucy, limpio)
        }

        return LucyIntent.FiltrarPlanillaVisionEmpresa(
            empresaTexto = empresaTexto,
            empresa = empresa,
            turnoSolicitado = turnoSolicitado,
            textoOriginal = rawSinLucy
        )
    }

    private fun extraerFragmentoOriginalEmpresa(raw: String, fallbackNorm: String): String {
        val tokensNorm = fallbackNorm.split(' ').filter { it.isNotBlank() }
        if (tokensNorm.isEmpty()) return fallbackNorm
        val palabrasRaw = raw.trim().replace(Regex("[¿?¡!.,;:]+$"), "").split(Regex("\\s+"))
        if (palabrasRaw.size >= tokensNorm.size) {
            val tail = palabrasRaw.takeLast(tokensNorm.size).joinToString(" ")
            if (TextoNormalizer.normalizar(tail) == fallbackNorm) {
                return tail
            }
        }
        return fallbackNorm
    }

    private fun detectarConversacionGeneral(
        norm: String,
        normConComas: String
    ): LucyConversationalSubtipo? {
        return when {
            norm in SALUDOS_SET || normConComas in SALUDOS_SET -> LucyConversationalSubtipo.SALUDO
            norm in DESPEDIDAS_SET || normConComas in DESPEDIDAS_SET -> LucyConversationalSubtipo.DESPEDIDA
            norm in AGRADECIMIENTOS_SET || normConComas in AGRADECIMIENTOS_SET -> LucyConversationalSubtipo.AGRADECIMIENTO
            norm in ESTADO_ASISTENTE_SET || normConComas in ESTADO_ASISTENTE_SET -> LucyConversationalSubtipo.ESTADO_ASISTENTE
            norm in IDENTIDAD_ASISTENTE_SET || normConComas in IDENTIDAD_ASISTENTE_SET -> LucyConversationalSubtipo.IDENTIDAD_ASISTENTE
            norm in CHARLA_BREVE_SET || normConComas in CHARLA_BREVE_SET -> LucyConversationalSubtipo.CHARLA_GENERAL
            else -> null
        }
    }

    private fun detectarResolucionDudaPendiente(
        rawTrimmed: String,
        norm: String,
        cuerpo: String,
        empresa: EmpresaOficial?,
        turnoSolicitado: Turno?
    ): LucyIntent.ResolverDudaPendiente? {
        if (esFraseInterrogativa(rawTrimmed, norm)) return null

        if (DUDA_DEJAR_PENDIENTE_REGEX.containsMatchIn(norm)) {
            return LucyIntent.ResolverDudaPendiente(
                textoRespuesta = rawTrimmed,
                empresa = empresa,
                turnoSolicitado = turnoSolicitado,
                dejarPendiente = true
            )
        }
        if (DUDA_FORZAR_MARCACION_REGEX.containsMatchIn(norm)) {
            return LucyIntent.ResolverDudaPendiente(
                textoRespuesta = rawTrimmed,
                empresa = empresa,
                turnoSolicitado = turnoSolicitado,
                forzarMarcacion = true
            )
        }

        val indice = when (norm) {
            "el primero", "la primera", "primero", "primera", "opcion 1", "el 1", "la 1", "1" -> 0
            "el segundo", "la segunda", "segundo", "segunda", "opcion 2", "el 2", "la 2", "2" -> 1
            "el tercero", "la tercera", "tercero", "tercera", "opcion 3", "el 3", "la 3", "3" -> 2
            else -> null
        }
        if (indice != null) {
            return LucyIntent.ResolverDudaPendiente(
                textoRespuesta = rawTrimmed,
                empresa = empresa,
                turnoSolicitado = turnoSolicitado,
                indiceOpcion = indice
            )
        }

        if (empresa != null && cuerpo in setOf("", "el", "la", "es", "ese", "esa", "el que es", "es de", "trabaja en")) {
            return LucyIntent.ResolverDudaPendiente(
                textoRespuesta = rawTrimmed,
                empresa = empresa,
                turnoSolicitado = turnoSolicitado
            )
        }

        // Respuesta aclaratoria con nombre/apellido completo ("Bryan Gutiérrez", "El de Gutiérrez")
        val limpio = cuerpo
            .replace(Regex("^(?:el|la|es|era|ese\\s+es|esa\\s+es)\\s+(?:de\\s+)?"), "")
            .trim()
        if (limpio.isNotBlank() && !esOrdenOCharlaNoBuscable(limpio)) {
            val tokens = limpio.split(' ').filter { it.isNotBlank() }
            if (tokens.size in 1..4) {
                return LucyIntent.ResolverDudaPendiente(
                    textoRespuesta = limpio,
                    empresa = empresa,
                    turnoSolicitado = turnoSolicitado
                )
            }
        }
        return null
    }

    private fun detectarCorreccionUsuario(
        rawTrimmed: String,
        norm: String
    ): LucyIntent.RegistrarCorreccionUsuario? {
        if (rawTrimmed.contains('?') || rawTrimmed.contains('¿')) return null
        if (norm.contains("ya no esta") || norm.contains("habitacion") || norm.contains("pabellon")) {
            return null
        }

        CORRECCION_FILA_VISION_REGEX.find(norm)?.let { m ->
            val fila = m.groupValues[1].toIntOrNull()
            val corr = m.groupValues[2].trim()
            if (fila != null && corr.isNotBlank()) {
                return LucyIntent.RegistrarCorreccionUsuario(
                    textoCorregido = corr,
                    textoErroneo = null,
                    tipoSugerido = LucyTipoCorreccionAprendida.LECTURA_VISION,
                    filaVision = fila,
                    textoOriginal = rawTrimmed
                )
            }
        }

        CORRECCION_PLANILLA_DICE_REGEX.find(norm)?.let { m ->
            val err = m.groupValues[1].trim()
            val corr = m.groupValues[2].trim()
            if (err.isNotBlank() && corr.isNotBlank()) {
                return LucyIntent.RegistrarCorreccionUsuario(
                    textoCorregido = corr,
                    textoErroneo = err,
                    tipoSugerido = LucyTipoCorreccionAprendida.LECTURA_VISION,
                    textoOriginal = rawTrimmed
                )
            }
        }

        CORRECCION_CUANDO_DIGO_REGEX.find(norm)?.let { m ->
            val err = m.groupValues[1].trim()
            val corr = m.groupValues[2].trim()
            if (err.isNotBlank() && corr.isNotBlank()) {
                return LucyIntent.RegistrarCorreccionUsuario(
                    textoCorregido = corr,
                    textoErroneo = err,
                    tipoSugerido = LucyTipoCorreccionAprendida.PRONUNCIACION_VOZ,
                    textoOriginal = rawTrimmed
                )
            }
        }

        CORRECCION_NO_ES_X_ES_Y_REGEX.find(norm)?.let { m ->
            val err = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            val corr = m.groupValues[3].trim()
            val (_, empErr) = extraerEmpresa(err)
            val (_, empCorr) = extraerEmpresa(corr)
            if (empErr == null && empCorr == null && corr.isNotBlank() && !corr.startsWith("de ")) {
                return LucyIntent.RegistrarCorreccionUsuario(
                    textoCorregido = corr,
                    textoErroneo = err.ifBlank { null },
                    tipoSugerido = LucyTipoCorreccionAprendida.NOMBRE,
                    textoOriginal = rawTrimmed
                )
            }
        }

        CORRECCION_NO_ESE_ES_REGEX.find(norm)?.let { m ->
            val corr = m.groupValues[1].trim()
            val (_, empCorr) = extraerEmpresa(corr)
            if (empCorr == null && corr.isNotBlank() && !corr.startsWith("de ") && corr.split(' ').size <= 4) {
                return LucyIntent.RegistrarCorreccionUsuario(
                    textoCorregido = corr,
                    textoErroneo = null,
                    tipoSugerido = LucyTipoCorreccionAprendida.NOMBRE,
                    textoOriginal = rawTrimmed
                )
            }
        }

        return null
    }

    private fun detectarActualizacionDatoMaestro(
        rawTrimmed: String,
        norm: String,
        normSinSeparadores: String,
        turnoSolicitado: Turno?,
        empresaDetectada: EmpresaOficial?,
        usaTrabajadorAnterior: Boolean
    ): LucyIntent.ActualizarDatoTrabajador? {
        if (esFraseInterrogativa(rawTrimmed, norm)) return null
        if (norm.startsWith("si ") || norm.contains("si lo ") || norm.contains("por si")) return null

        val sinTurno = extraerTurno(norm).first
        val cuerpoCambio = sinTurno
            .replace(Regex("^(?:actualiza(?:r)?|corrige|modifica)\\s+(?:que\\s+)?"), "")
            .trim()

        // 1. "... ya no está en habitación X. Ahora está en habitación Y" / "... pasó de hab X a Y"
        CAMBIO_HAB_DESDE_HASTA_REGEX.find(sinTurno)?.let { m ->
            val nombre = limpiarNombreActualizacion(m.groupValues[1], usaTrabajadorAnterior)
            val oldHab = (m.groupValues[2].ifBlank { m.groupValues[4] }).trim().uppercase()
            val newHab = (m.groupValues[3].ifBlank { m.groupValues[5] }).trim().uppercase()
            if ((nombre.isNotBlank() || usaTrabajadorAnterior) && newHab.isNotBlank()) {
                return LucyIntent.ActualizarDatoTrabajador(
                    nombreTrabajador = nombre,
                    usarTrabajadorAnterior = usaTrabajadorAnterior && nombre.isBlank(),
                    nuevaHabitacion = newHab,
                    habitacionAnteriorEsperada = oldHab.ifBlank { null },
                    turnoSolicitado = turnoSolicitado,
                    textoOriginal = rawTrimmed
                )
            }
        }

        // 2. "... ya no está en pabellón X. Ahora está en pabellón Y" / "... pasó de pab X a Y"
        CAMBIO_PAB_DESDE_HASTA_REGEX.find(sinTurno)?.let { m ->
            val nombre = limpiarNombreActualizacion(m.groupValues[1], usaTrabajadorAnterior)
            val newPab = (m.groupValues[3].ifBlank { m.groupValues[5] }).trim().uppercase()
            if ((nombre.isNotBlank() || usaTrabajadorAnterior) && newPab.isNotBlank()) {
                return LucyIntent.ActualizarDatoTrabajador(
                    nombreTrabajador = nombre,
                    usarTrabajadorAnterior = usaTrabajadorAnterior && nombre.isBlank(),
                    nuevoPabellon = newPab,
                    turnoSolicitado = turnoSolicitado,
                    textoOriginal = rawTrimmed
                )
            }
        }

        // 3. Combinado: "Cambia la habitación de <nombre> a <hab> y el pabellón a <pab>"
        Regex(
            "^(?:cambia|actualiza|modifica)\\s+(?:la\\s+)?(?:hab|habitacion)\\s+(?:de\\s+)?(.+?)\\s+(?:a|por)\\s+([a-z0-9-]+)\\s+y\\s+(?:el\\s+)?(?:pab|pabellon)\\s+(?:a|por)\\s+([a-z0-9-]+(?:\\s+[a-z0-9-]+)?)$"
        ).find(sinTurno)?.let { m ->
            val nombre = limpiarNombreActualizacion(m.groupValues[1], usaTrabajadorAnterior)
            return LucyIntent.ActualizarDatoTrabajador(
                nombreTrabajador = nombre,
                usarTrabajadorAnterior = usaTrabajadorAnterior && nombre.isBlank(),
                nuevaHabitacion = m.groupValues[2].trim().uppercase(),
                nuevoPabellon = m.groupValues[3].trim().uppercase(),
                turnoSolicitado = turnoSolicitado,
                textoOriginal = rawTrimmed
            )
        }

        // 4. Comandos directos de habitación: "Cambia la habitación de X a Y", "Pon a X en habitación Y"
        CAMBIO_CMD_HAB_REGEX.find(cuerpoCambio)?.let { m ->
            val nombre = limpiarNombreActualizacion(m.groupValues[1].ifBlank { m.groupValues[3] }, usaTrabajadorAnterior)
            val newHab = (m.groupValues[2].ifBlank { m.groupValues[4] }).trim().uppercase()
            if ((nombre.isNotBlank() || usaTrabajadorAnterior) && newHab.isNotBlank()) {
                return LucyIntent.ActualizarDatoTrabajador(
                    nombreTrabajador = nombre,
                    usarTrabajadorAnterior = usaTrabajadorAnterior && nombre.isBlank(),
                    nuevaHabitacion = newHab,
                    turnoSolicitado = turnoSolicitado,
                    textoOriginal = rawTrimmed
                )
            }
        }

        // 5. Comandos directos de pabellón
        CAMBIO_CMD_PAB_REGEX.find(cuerpoCambio)?.let { m ->
            val nombre = limpiarNombreActualizacion(m.groupValues[1].ifBlank { m.groupValues[3] }, usaTrabajadorAnterior)
            val newPab = (m.groupValues[2].ifBlank { m.groupValues[4] }).trim().uppercase()
            if ((nombre.isNotBlank() || usaTrabajadorAnterior) && newPab.isNotBlank()) {
                return LucyIntent.ActualizarDatoTrabajador(
                    nombreTrabajador = nombre,
                    usarTrabajadorAnterior = usaTrabajadorAnterior && nombre.isBlank(),
                    nuevoPabellon = newPab,
                    turnoSolicitado = turnoSolicitado,
                    textoOriginal = rawTrimmed
                )
            }
        }

        // 6. "... ahora está en la habitación Y" / "Ahora está en habitación Y"
        CAMBIO_AHORA_HAB_REGEX.find(sinTurno)?.let { m ->
            val nombre = limpiarNombreActualizacion(m.groupValues[1], usaTrabajadorAnterior)
            val newHab = m.groupValues[2].trim().uppercase()
            if (newHab.isNotBlank()) {
                return LucyIntent.ActualizarDatoTrabajador(
                    nombreTrabajador = nombre,
                    usarTrabajadorAnterior = nombre.isBlank(),
                    nuevaHabitacion = newHab,
                    turnoSolicitado = turnoSolicitado,
                    textoOriginal = rawTrimmed
                )
            }
        }

        // 7. "... ahora está en el pabellón Y" / "Ahora está en pabellón Y"
        CAMBIO_AHORA_PAB_REGEX.find(sinTurno)?.let { m ->
            val nombre = limpiarNombreActualizacion(m.groupValues[1], usaTrabajadorAnterior)
            val newPab = m.groupValues[2].trim().uppercase()
            if (newPab.isNotBlank()) {
                return LucyIntent.ActualizarDatoTrabajador(
                    nombreTrabajador = nombre,
                    usarTrabajadorAnterior = nombre.isBlank(),
                    nuevoPabellon = newPab,
                    turnoSolicitado = turnoSolicitado,
                    textoOriginal = rawTrimmed
                )
            }
        }

        // 8. Cambio explícito de empresa: "Pasa a X a Finning", "Cambia la empresa de X a Finning"
        if (empresaDetectada != null) {
            Regex(
                "^(?:cambia\\s+(?:la\\s+)?empresa\\s+(?:de\\s+)?|pasa(?:r)?\\s+(?:a\\s+)?|mueve\\s+(?:a\\s+)?|traslada\\s+(?:a\\s+)?)(.+?)\\s+(?:a|para|a\\s+la\\s+empresa)\\s+${Regex.escape(TextoNormalizer.normalizar(empresaDetectada.nombre))}$"
            ).find(sinTurno)?.let { m ->
                val nombre = limpiarNombreActualizacion(m.groupValues[1], usaTrabajadorAnterior)
                if (nombre.isNotBlank() || usaTrabajadorAnterior) {
                    return LucyIntent.ActualizarDatoTrabajador(
                        nombreTrabajador = nombre,
                        usarTrabajadorAnterior = usaTrabajadorAnterior && nombre.isBlank(),
                        nuevaEmpresa = empresaDetectada,
                        turnoSolicitado = turnoSolicitado,
                        textoOriginal = rawTrimmed
                    )
                }
            }
            Regex(
                "^(.+?)\\s+ahora\\s+(?:es\\s+de|trabaja\\s+en|pertenece\\s+a)\\s+${Regex.escape(TextoNormalizer.normalizar(empresaDetectada.nombre))}$"
            ).find(sinTurno)?.let { m ->
                val nombre = limpiarNombreActualizacion(m.groupValues[1], usaTrabajadorAnterior)
                if (nombre.isNotBlank() || usaTrabajadorAnterior) {
                    return LucyIntent.ActualizarDatoTrabajador(
                        nombreTrabajador = nombre,
                        usarTrabajadorAnterior = usaTrabajadorAnterior && nombre.isBlank(),
                        nuevaEmpresa = empresaDetectada,
                        turnoSolicitado = turnoSolicitado,
                        textoOriginal = rawTrimmed
                    )
                }
            }
        }

        // Evitar advertencia sin uso de parámetro si solo es contexto de oración
        if (normSinSeparadores.isEmpty()) return null
        return null
    }

    private fun limpiarNombreActualizacion(raw: String, usaTrabajadorAnterior: Boolean): String {
        val sinRef = limpiarReferencias(raw.trim())
        val limpio = sinRef
            .replace(Regex("^(?:a|al|el|la|de|del)\\s+"), "")
            .replace(Regex("\\s+(?:a|al|en|de)$"), "")
            .trim()
        return if (usaTrabajadorAnterior && limpio.isBlank()) "" else limpio
    }

    private fun extraerNombreObjetivoHistorialCambios(cuerpo: String): String? {
        val m = Regex("(?:a|de)\\s+([a-zñ0-9\\s]+)$").find(cuerpo) ?: return null
        val cand = m.groupValues[1].trim()
        return if (cand.isBlank() || cand in setOf("cambios", "datos", "hoy", "lucy")) null else cand
    }

    private fun esFraseInterrogativa(rawTrimmed: String, norm: String): Boolean {
        if (rawTrimmed.contains('?') || rawTrimmed.contains('¿')) return true
        return INICIO_INTERROGATIVO_REGEX.containsMatchIn(norm)
    }

    private fun esOrdenOCharlaNoBuscable(cuerpo: String): Boolean {
        if (cuerpo in OBJETIVOS_VAGOS_BUSQUEDA) return true
        if (VERBO_NO_BUSQUEDA_REGEX.containsMatchIn(cuerpo)) return true
        val tokens = cuerpo.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        // Permitir ubicaciones explícitas (hab 204, pab 18 sur)
        val analizada = BuscadorTrabajadores.analizarQuery(cuerpo)
        if (analizada.roomOnlyTokens.isNotEmpty() || analizada.pavilionOnlyPhrases.isNotEmpty()) {
            return false
        }
        if (tokens.size > 4) return true
        if (tokens.any { it in PALABRAS_NO_NOMBRE }) return true
        return false
    }

    private fun detectarMarcacionMultiple(
        rawTrimmed: String,
        norm: String,
        normConSeparadores: String,
        turnoActivo: Turno?,
        turnoSolicitado: Turno?,
        empresa: EmpresaOficial?,
        usaEmpresaAnterior: Boolean,
        hayContextoMarcacionLote: Boolean
    ): LucyIntent? {
        val esPreguntaONegacion = rawTrimmed.contains('?') ||
            rawTrimmed.contains('¿') ||
            norm.startsWith("ya ") ||
            norm.startsWith("quien") ||
            norm.startsWith("cuanto") ||
            norm.startsWith("cuanta") ||
            norm.contains("no subio") ||
            norm.contains("no subieron") ||
            norm.contains("no han subido") ||
            norm.contains("no bajo") ||
            norm.contains("no bajaron") ||
            norm.contains("faltan") ||
            norm.contains("pendiente") ||
            norm in setOf("subio", "ya subio", "bajo", "ya bajo")
        if (esPreguntaONegacion) return null

        val incluirUbicacion = PIDE_UBICACION_LOTE_REGEX.containsMatchIn(norm)

        val esComandoSubieron = PREFIJO_LOTE_SUBIERON_REGEX.containsMatchIn(norm) ||
            FRASE_SUBIERON_EN_CUERPO_REGEX.containsMatchIn(norm)

        val esMarcarMulti = MARCAR_PREFIJO_REGEX.containsMatchIn(norm)
        val esContinuacionExplicita = hayContextoMarcacionLote &&
            PREFIJO_CONTINUACION_LOTE_REGEX.containsMatchIn(norm) &&
            !norm.contains("recuerda") &&
            !norm.contains("olvida") &&
            !norm.contains("resumen") &&
            !norm.contains("empresa")

        val empresaGlobal: EmpresaOficial? = if (empresa != null && normConSeparadores.contains(':')) {
            val idxColon = normConSeparadores.indexOf(':')
            val header = normConSeparadores.substring(0, idxColon)
            val body = normConSeparadores.substring(idxColon + 1)
            val empNorm = TextoNormalizer.normalizar(empresa.nombre)
            val enHeader = Regex("\\b${Regex.escape(empNorm)}\\b").containsMatchIn(header)
            if (enHeader || contarSeparadoresMultiples(body) == 0) empresa else null
        } else {
            empresa
        }

        val extraido = extraerTextoNombresParaLote(
            normConSeparadores = normConSeparadores,
            empresa = empresaGlobal
        )

        if (!esComandoSubieron && !esContinuacionExplicita) {
            val tieneMultiplesEnTexto = contarSeparadoresMultiples(extraido) >= 1
            if (esMarcarMulti && tieneMultiplesEnTexto) {
                // OK: marcar varios nombres en una sola frase
            } else if (hayContextoMarcacionLote && tieneMultiplesEnTexto && !esFraseInterrogativa(rawTrimmed, norm)) {
                val sinPalabrasReservadas = !listOf(
                    "busca", "encuentra", "ver ", "listar", "pendiente", "resumen", "empresa", "turno"
                ).any { norm.startsWith(it) }
                if (!sinPalabrasReservadas) return null
            } else {
                return null
            }
        }

        if (extraido.isBlank()) return null
        val tokensLimpios = extraido.replace(Regex("[,;:\\n]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
        if (tokensLimpios.isEmpty()) return null

        // Si es continuación implícita (sin "subieron"), exigir que parezca nombre(s) y no charla cualquiera
        if (!esComandoSubieron && !esMarcarMulti && tokensLimpios.any { it in PALABRAS_NO_NOMBRE }) {
            return null
        }

        if (turnoActivo != null && turnoSolicitado != null && turnoSolicitado != turnoActivo) {
            return LucyIntent.ConflictoTurno(
                turnoActivo = turnoActivo,
                turnoSolicitado = turnoSolicitado,
                query = extraido,
                marcarComoSiBajo = true
            )
        }

        val esContinuacion = esContinuacionExplicita ||
            (hayContextoMarcacionLote && !esComandoSubieron && !esMarcarMulti)

        return LucyIntent.MarcacionMultiple(
            nombresTexto = extraido,
            empresa = empresaGlobal,
            turnoSolicitado = turnoSolicitado,
            usarEmpresaAnterior = usaEmpresaAnterior || (esContinuacion && empresaGlobal == null),
            esContinuacion = esContinuacion,
            incluirUbicacion = incluirUbicacion
        )
    }

    private fun extraerTextoNombresParaLote(
        normConSeparadores: String,
        empresa: EmpresaOficial?
    ): String {
        var texto = normConSeparadores
        texto = texto.replace(TURNO_A_REGEX, " ")
        texto = texto.replace(TURNO_B_REGEX, " ")
        texto = texto.replace(REFERENCIA_EMPRESA_REGEX, " ")
        texto = texto.replace(PIDE_UBICACION_LOTE_REGEX, " ")

        if (empresa != null) {
            val empNorm = TextoNormalizer.normalizar(empresa.nombre)
            texto = texto.replace(
                Regex("(?:\\b(?:de|en|para|del|empresa)\\s+)?\\b${Regex.escape(empNorm)}\\b"),
                " "
            )
        }

        texto = texto.replace(PREFIJO_LOTE_SUBIERON_REGEX, " ")
        texto = texto.replace(MARCAR_PREFIJO_REGEX, " ")
        texto = texto.replace(PREFIJO_CONTINUACION_LOTE_REGEX, " ")
        // Reemplazar "subió"/"subieron" internos por coma para separar dictados continuos
        texto = texto.replace(FRASE_SUBIERON_EN_CUERPO_REGEX, ",")
        texto = texto.replace(Regex("^[,;:\\s]+"), "")
        texto = texto.replace(Regex("^(?:de|del|en|a|al|los|las|que|son)\\s+"), "")
        texto = texto.replace(Regex("\\s*,\\s*"), ", ")
        texto = texto.replace(Regex("(?:,\\s*)+$"), "")
        texto = texto.replace(Regex("\\s+"), " ").trim()
        return texto
    }

    private fun contarSeparadoresMultiples(textoNombres: String): Int {
        if (textoNombres.isBlank()) return 0
        val comas = textoNombres.count { it == ',' || it == ';' || it == '\n' }
        val conectores = Regex("\\b(?:y|e|tambien|ademas|luego|despues)\\b")
            .findAll(textoNombres)
            .count()
        return comas + conectores
    }

    private fun detectarPreferenciaConversacion(
        norm: String,
        rawSinLucy: String,
        tieneContextoOperativo: Boolean
    ): LucyIntent.ConfigurarPreferenciaConversacion? {
        val mencionaHabla = listOf("habla", "hables", "hablame", "responde", "respondas", "respondeme", "voz", "velocidad")
            .any { norm.contains(it) }

        val velocidad = when {
            mencionaHabla && (norm.contains("mas rapido") || norm.contains("un poco mas rapido") || norm.contains("acelera")) ->
                LucyVelocidadRespuesta.RAPIDA
            mencionaHabla && (norm.contains("mas lento") || norm.contains("mas despacio") || norm.contains("despacio")) ->
                LucyVelocidadRespuesta.LENTA
            (norm.contains("velocidad normal") || norm.contains("habla normal") || norm.contains("ritmo normal")) ->
                LucyVelocidadRespuesta.NORMAL
            else -> null
        }

        val estilo = when {
            listOf("mas corto", "respuestas cortas", "mas breve", "breve", "directo al grano", "mas conciso", "concisa")
                .any { norm.contains(it) } && (mencionaHabla || norm.contains("prefiero") || norm.contains("respuestas") || norm.contains("se ")) ->
                LucyEstiloRespuesta.CONCISA
            listOf("mas detalle", "detallada", "detalladas", "mas largo", "explica mas")
                .any { norm.contains(it) } && (mencionaHabla || norm.contains("prefiero") || norm.contains("respuestas") || norm.contains("explica")) ->
                LucyEstiloRespuesta.DETALLADA
            norm.contains("estilo normal") || norm.contains("respuestas normales") || norm.contains("responde normal") ->
                LucyEstiloRespuesta.NORMAL
            else -> null
        }

        val estiloVoz = when {
            (mencionaHabla || norm.contains("tono")) && listOf("pausad", "suave", "tranquil", "calmad").any { norm.contains(it) } ->
                LucyEstiloVoz.PAUSADA_SUAVE
            (mencionaHabla || norm.contains("tono")) && listOf("clara", "profesional", "formal").any { norm.contains(it) } ->
                LucyEstiloVoz.CLARA_PROFESIONAL
            (mencionaHabla || norm.contains("tono")) && (norm.contains("voz directa") || norm.contains("voz breve")) ->
                LucyEstiloVoz.BREVE_DIRECTA
            (mencionaHabla || norm.contains("tono")) && listOf("voz natural", "voz cercana", "tono natural").any { norm.contains(it) } ->
                LucyEstiloVoz.NATURAL_CERCANA
            else -> null
        }

        if (velocidad != null || estilo != null || estiloVoz != null) {
            return LucyIntent.ConfigurarPreferenciaConversacion(
                velocidad = velocidad,
                estilo = estilo,
                estiloVoz = estiloVoz
            )
        }

        if (!tieneContextoOperativo &&
            (INSTRUCCION_PREF_PREFIJO_REGEX.containsMatchIn(norm) || norm.startsWith("recuerda siempre "))
        ) {
            val limpia = rawSinLucy
                .replace(Regex("^(?:recuerda\\s+siempre(?:\\s+que)?|siempre)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            if (limpia.isNotBlank()) {
                return LucyIntent.ConfigurarPreferenciaConversacion(
                    instruccionPermanente = limpia
                )
            }
        }
        return null
    }

    private fun limpiarReferencias(texto: String): String {
        return texto
            .replace(REFERENCIA_TRABAJADOR_REGEX, " ")
            .replace(REFERENCIA_EMPRESA_REGEX, " ")
            .replace(Regex("\\b(?:a|al|el|la|de|del|en)\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extraerTurno(textoNorm: String): Pair<String, Turno?> {
        return when {
            TURNO_B_REGEX.containsMatchIn(textoNorm) -> {
                val limpio = textoNorm.replace(TURNO_B_REGEX, " ").replace(Regex("\\s+"), " ").trim()
                limpio to Turno.B
            }
            TURNO_A_REGEX.containsMatchIn(textoNorm) -> {
                val limpio = textoNorm.replace(TURNO_A_REGEX, " ").replace(Regex("\\s+"), " ").trim()
                limpio to Turno.A
            }
            else -> textoNorm to null
        }
    }

    private fun extraerEmpresa(textoNorm: String): Pair<String, EmpresaOficial?> {
        for ((nombreNorm, empresa) in EMPRESAS_POR_LONGITUD) {
            val regex = Regex("\\b${Regex.escape(nombreNorm)}\\b")
            if (regex.containsMatchIn(textoNorm)) {
                val limpio = textoNorm.replace(regex, " ").replace(Regex("\\s+"), " ").trim()
                return limpio to empresa
            }
        }
        return textoNorm to null
    }

    private fun limpiarConectoresResiduales(texto: String): String {
        return texto
            .replace(Regex("\\b(?:en|del|de|para|por)\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun limpiarSufijosMarcacion(texto: String): String {
        return texto
            .replace(MARCAR_SUFIJO_SI_BAJO_REGEX, "")
            .replace(MARCAR_SUFIJO_NO_BAJO_REGEX, "")
            .replace(Regex("\\b(?:como|en)\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
