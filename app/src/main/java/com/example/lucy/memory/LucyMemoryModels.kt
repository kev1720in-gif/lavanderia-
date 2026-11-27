package com.example.lucy.memory

import androidx.compose.runtime.Immutable
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.Turno
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class LucyVelocidadRespuesta(val etiqueta: String) {
    LENTA("Lenta"),
    NORMAL("Normal"),
    RAPIDA("Rápida");

    companion object {
        fun fromStored(valor: String?): LucyVelocidadRespuesta =
            entries.firstOrNull { it.name == valor } ?: NORMAL
    }
}

enum class LucyEstiloRespuesta(val etiqueta: String) {
    CONCISA("Corta"),
    NORMAL("Normal"),
    DETALLADA("Detallada");

    companion object {
        fun fromStored(valor: String?): LucyEstiloRespuesta =
            entries.firstOrNull { it.name == valor } ?: NORMAL
    }
}

enum class LucyEstiloVoz(
    val etiqueta: String,
    val descripcionGemini: String
) {
    NATURAL_CERCANA(
        etiqueta = "Natural",
        descripcionGemini = "Tono femenino cálido, cercano y natural en español chileno o latinoamericano neutro, fluido y amable."
    ),
    CLARA_PROFESIONAL(
        etiqueta = "Clara",
        descripcionGemini = "Dicción muy nítida, tono femenino sereno, claro y profesional."
    ),
    BREVE_DIRECTA(
        etiqueta = "Directa",
        descripcionGemini = "Tono femenino ágil, seguro y directo al grano, sin rodeos."
    ),
    PAUSADA_SUAVE(
        etiqueta = "Pausada",
        descripcionGemini = "Tono femenino suave, tranquilo y ligeramente pausado para facilitar la escucha."
    );

    companion object {
        fun fromStored(valor: String?): LucyEstiloVoz =
            entries.firstOrNull { it.name == valor } ?: NATURAL_CERCANA
    }
}

enum class LucyTipoPreferencia {
    VELOCIDAD,
    ESTILO,
    ESTILO_VOZ,
    VOZ_PREFERIDA,
    INSTRUCCION
}

@Immutable
data class LucyInstruccionMemoria(
    val id: String = UUID.randomUUID().toString(),
    val claveNormalizada: String,
    val texto: String,
    val actualizadoEn: Long = System.currentTimeMillis()
)

@Immutable
data class LucyPreferenciasConversacion(
    val velocidad: LucyVelocidadRespuesta = LucyVelocidadRespuesta.NORMAL,
    val estilo: LucyEstiloRespuesta = LucyEstiloRespuesta.NORMAL,
    val instruccionesPermanentes: List<LucyInstruccionMemoria> = emptyList(),
    val ultimoTipoModificado: LucyTipoPreferencia? = null,
    val ultimaInstruccionId: String? = null,
    val vozPreferidaId: String = "",
    val estiloVoz: LucyEstiloVoz = LucyEstiloVoz.NATURAL_CERCANA
)

@Immutable
data class LucyNotaMemoria(
    val id: String = UUID.randomUUID().toString(),
    val claveNormalizada: String,
    val texto: String,
    val empresaCodigo: String? = null,
    val turno: Turno,
    val creadoEn: Long = System.currentTimeMillis(),
    val actualizadoEn: Long = System.currentTimeMillis()
)

@Immutable
data class LucyMemoriaTurnoSnapshot(
    val turno: Turno,
    val ultimoTrabajadorId: String? = null,
    val ultimaEmpresaCodigo: String? = null,
    val ultimaNotaId: String? = null,
    val notas: List<LucyNotaMemoria> = emptyList()
)

@Immutable
data class LucyResumenMemoria(
    val preferencias: LucyPreferenciasConversacion,
    val turnoA: LucyMemoriaTurnoSnapshot,
    val turnoB: LucyMemoriaTurnoSnapshot
)

enum class LucyTipoCorreccionAprendida(val etiqueta: String) {
    NOMBRE("Nombre de trabajador"),
    PRONUNCIACION_VOZ("Pronunciación / dictado por voz"),
    LECTURA_VISION("Lectura de planilla (Vision)"),
    DATO_MAESTRO("Dato maestro");

    companion object {
        fun fromStored(valor: String?): LucyTipoCorreccionAprendida =
            entries.firstOrNull { it.name == valor } ?: NOMBRE
    }
}

@Immutable
data class LucyCorreccionAprendida(
    val id: String = UUID.randomUUID().toString(),
    val tipo: LucyTipoCorreccionAprendida,
    val turno: Turno,
    val empresaCodigo: String? = null,
    val textoErroneo: String,
    val claveErroneaNorm: String,
    val trabajadorId: String,
    val valorCorregido: String,
    val creadoEn: Long = System.currentTimeMillis(),
    val actualizadoEn: Long = System.currentTimeMillis()
)

enum class LucyCampoMaestroEditado(val etiqueta: String) {
    HABITACION("habitación"),
    PABELLON("pabellón"),
    EMPRESA("empresa");

    companion object {
        fun fromStored(valor: String?): LucyCampoMaestroEditado =
            entries.firstOrNull { it.name == valor } ?: HABITACION
    }
}

@Immutable
data class LucyRegistroCambioMaestro(
    val id: String = UUID.randomUUID().toString(),
    val trabajadorId: String,
    val trabajadorNombre: String,
    val turno: Turno,
    val campo: LucyCampoMaestroEditado,
    val valorAnterior: String,
    val valorNuevo: String,
    val fechaMs: Long = System.currentTimeMillis(),
    val origen: String = "LUCY"
)

object LucyMemoryStorageSerializer {
    private val STOP_WORDS_CLAVE = setOf(
        "que", "el", "la", "los", "las", "un", "una", "de", "del", "al", "a", "en",
        "por", "para", "con", "sobre", "este", "esta", "eso", "esa", "esa", "siempre",
        "recuerda", "anota", "guarda", "ten", "cuenta", "prefiero", "lucy"
    )

    fun normalizarClaveSemantica(texto: String, empresa: EmpresaOficial? = null): String {
        val tokens = TextoNormalizer.normalizar(texto)
            .replace(Regex("[^a-z0-9ñ\\s]"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it !in STOP_WORDS_CLAVE }
        val base = tokens.joinToString(" ").trim()
        return if (empresa != null) {
            "${empresa.codigo.lowercase()}::$base"
        } else {
            base
        }
    }

    fun normalizarClaveCorreccion(texto: String): String {
        return TextoNormalizer.normalizar(texto)
            .replace(Regex("[^a-z0-9ñ\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun serializarInstrucciones(lista: List<LucyInstruccionMemoria>): String {
        val array = JSONArray()
        lista.forEach { item ->
            val obj = JSONObject()
                .put("id", item.id)
                .put("clave", item.claveNormalizada)
                .put("texto", item.texto)
                .put("actualizadoEn", item.actualizadoEn)
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializarInstrucciones(json: String?): List<LucyInstruccionMemoria> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        val result = mutableListOf<LucyInstruccionMemoria>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val texto = obj.optString("texto", "").trim()
            val clave = obj.optString("clave", "").trim()
            if (texto.isBlank() || clave.isBlank()) continue
            result.add(
                LucyInstruccionMemoria(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    claveNormalizada = clave,
                    texto = texto,
                    actualizadoEn = obj.optLong("actualizadoEn", 0L)
                )
            )
        }
        return result
    }

    fun serializarNotas(lista: List<LucyNotaMemoria>): String {
        val array = JSONArray()
        lista.forEach { nota ->
            val obj = JSONObject()
                .put("id", nota.id)
                .put("clave", nota.claveNormalizada)
                .put("texto", nota.texto)
                .put("empresaCodigo", nota.empresaCodigo ?: JSONObject.NULL)
                .put("turno", nota.turno.id)
                .put("creadoEn", nota.creadoEn)
                .put("actualizadoEn", nota.actualizadoEn)
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializarNotas(json: String?, turnoEsperado: Turno): List<LucyNotaMemoria> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        val result = mutableListOf<LucyNotaMemoria>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val texto = obj.optString("texto", "").trim()
            val clave = obj.optString("clave", "").trim()
            if (texto.isBlank() || clave.isBlank()) continue
            val empresaRaw = if (obj.isNull("empresaCodigo")) null else obj.optString("empresaCodigo", "").ifBlank { null }
            val turnoNota = Turno.fromId(obj.optString("turno", turnoEsperado.id))
            if (turnoNota != turnoEsperado) continue
            result.add(
                LucyNotaMemoria(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    claveNormalizada = clave,
                    texto = texto,
                    empresaCodigo = empresaRaw,
                    turno = turnoEsperado,
                    creadoEn = obj.optLong("creadoEn", 0L),
                    actualizadoEn = obj.optLong("actualizadoEn", 0L)
                )
            )
        }
        return result
    }

    fun serializarCorrecciones(lista: List<LucyCorreccionAprendida>): String {
        val array = JSONArray()
        lista.forEach { c ->
            val obj = JSONObject()
                .put("id", c.id)
                .put("tipo", c.tipo.name)
                .put("turno", c.turno.id)
                .put("empresaCodigo", c.empresaCodigo ?: JSONObject.NULL)
                .put("textoErroneo", c.textoErroneo)
                .put("claveErronea", c.claveErroneaNorm)
                .put("trabajadorId", c.trabajadorId)
                .put("valorCorregido", c.valorCorregido)
                .put("creadoEn", c.creadoEn)
                .put("actualizadoEn", c.actualizadoEn)
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializarCorrecciones(json: String?, turnoEsperado: Turno): List<LucyCorreccionAprendida> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        val result = mutableListOf<LucyCorreccionAprendida>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val textoErroneo = obj.optString("textoErroneo", "").trim()
            val clave = obj.optString("claveErronea", "").trim()
            val trabajadorId = obj.optString("trabajadorId", "").trim()
            val valorCorregido = obj.optString("valorCorregido", "").trim()
            if (textoErroneo.isBlank() || clave.isBlank() || trabajadorId.isBlank()) continue
            val turnoItem = Turno.fromId(obj.optString("turno", turnoEsperado.id))
            if (turnoItem != turnoEsperado) continue
            val empresaRaw = if (obj.isNull("empresaCodigo")) null else obj.optString("empresaCodigo", "").ifBlank { null }
            result.add(
                LucyCorreccionAprendida(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    tipo = LucyTipoCorreccionAprendida.fromStored(obj.optString("tipo", "NOMBRE")),
                    turno = turnoEsperado,
                    empresaCodigo = empresaRaw,
                    textoErroneo = textoErroneo,
                    claveErroneaNorm = clave,
                    trabajadorId = trabajadorId,
                    valorCorregido = valorCorregido,
                    creadoEn = obj.optLong("creadoEn", 0L),
                    actualizadoEn = obj.optLong("actualizadoEn", 0L)
                )
            )
        }
        return result
    }

    fun serializarCambiosMaestro(lista: List<LucyRegistroCambioMaestro>): String {
        val array = JSONArray()
        lista.forEach { item ->
            val obj = JSONObject()
                .put("id", item.id)
                .put("trabajadorId", item.trabajadorId)
                .put("trabajadorNombre", item.trabajadorNombre)
                .put("turno", item.turno.id)
                .put("campo", item.campo.name)
                .put("valorAnterior", item.valorAnterior)
                .put("valorNuevo", item.valorNuevo)
                .put("fechaMs", item.fechaMs)
                .put("origen", item.origen)
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializarCambiosMaestro(json: String?, turnoEsperado: Turno): List<LucyRegistroCambioMaestro> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        val result = mutableListOf<LucyRegistroCambioMaestro>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val trabajadorId = obj.optString("trabajadorId", "").trim()
            val trabajadorNombre = obj.optString("trabajadorNombre", "").trim()
            val valorNuevo = obj.optString("valorNuevo", "").trim()
            if (trabajadorId.isBlank() || valorNuevo.isBlank()) continue
            val turnoItem = Turno.fromId(obj.optString("turno", turnoEsperado.id))
            if (turnoItem != turnoEsperado) continue
            result.add(
                LucyRegistroCambioMaestro(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    trabajadorId = trabajadorId,
                    trabajadorNombre = trabajadorNombre,
                    turno = turnoEsperado,
                    campo = LucyCampoMaestroEditado.fromStored(obj.optString("campo", "HABITACION")),
                    valorAnterior = obj.optString("valorAnterior", ""),
                    valorNuevo = valorNuevo,
                    fechaMs = obj.optLong("fechaMs", 0L),
                    origen = obj.optString("origen", "LUCY")
                )
            )
        }
        return result
    }
}
