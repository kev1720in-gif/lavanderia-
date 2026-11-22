package com.example.domain.model

import androidx.compose.runtime.Immutable
import java.text.Normalizer
import java.util.Locale

@Immutable
data class TrabajadorConEstado(
    val id: String,
    val turno: Turno,
    val empresa: EmpresaOficial,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String,
    val habitacion: String,
    val pabellon: String,
    val siBajo: Boolean,
    val esDatoPrueba: Boolean,
    val codigoInterno: String
) {
    val nombreCompleto: String
        get() = TextoNormalizer.formatearNombreObligatorio(
            primerApellido = primerApellido,
            segundoApellido = segundoApellido,
            nombre = nombre
        )

    val apellidosYNombre: String
        get() = nombreCompleto

    val apellidosMayusculas: String
        get() = TextoNormalizer.formatearApellidos(
            primerApellido = primerApellido,
            segundoApellido = segundoApellido
        )

    val nombreSoloMayusculas: String
        get() = TextoNormalizer.formatearMayusculas(nombre)

    val habitacionMayusculas: String
        get() = TextoNormalizer.formatearMayusculas(habitacion)

    val pabellonMayusculas: String
        get() = TextoNormalizer.formatearMayusculas(pabellon)

    val ubicacionTexto: String
        get() = buildString {
            append("Hab. ")
            append(habitacionMayusculas.ifBlank { "S/N" })
            if (pabellonMayusculas.isNotBlank()) {
                append(" · Pab. ")
                append(pabellonMayusculas)
            }
        }
    internal val searchIndex: WorkerSearchIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        WorkerSearchIndex(this)
    }

    internal fun precalentarIndiceBusqueda() {
        searchIndex
    }
}

@Immutable
data class ResumenEmpresaTurno(
    val empresa: EmpresaOficial,
    val totalTrabajadores: Int,
    val siBajoCount: Int,
    val noBajoCount: Int
)

data class AvisoDuplicado(
    val mensaje: String,
    val esDuplicadoExacto: Boolean
)

object TextoNormalizer {
    private val localeEs = Locale("es", "CL")

    fun normalizar(texto: String): String {
        val nfd = Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
        return nfd
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    fun claveNombreCompleto(
        nombre: String,
        primerApellido: String,
        segundoApellido: String
    ): String {
        return listOf(nombre, primerApellido, segundoApellido)
            .map { normalizar(it) }
            .filter { it.isNotBlank() }
            .joinToString("|")
    }

    fun claveNombreBase(
        nombre: String,
        primerApellido: String
    ): String {
        return "${normalizar(nombre)}|${normalizar(primerApellido)}"
    }

    fun formatearMayusculas(texto: String): String {
        return texto
            .trim()
            .replace(Regex("\\s+"), " ")
            .uppercase(localeEs)
    }

    fun formatearApellidos(
        primerApellido: String,
        segundoApellido: String
    ): String {
        return listOf(primerApellido, segundoApellido)
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .uppercase(localeEs)
    }

    fun formatearNombreObligatorio(
        primerApellido: String,
        segundoApellido: String,
        nombre: String
    ): String {
        return listOf(primerApellido, segundoApellido, nombre)
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .uppercase(localeEs)
    }

    /**
     * Ordena por habitación de forma natural (numérica ascendente cuando hay números, e.g. 2, 15, 22, 103),
     * y en empate por apellidos y nombre.
     */
    fun ordenarPorHabitacion(lista: List<TrabajadorConEstado>): List<TrabajadorConEstado> {
        return lista.sortedWith(
            compareBy<TrabajadorConEstado>(
                { it.searchIndex.habNumeroNatural },
                { it.searchIndex.normHabitacion },
                { it.searchIndex.normPrimerApellido },
                { it.searchIndex.normSegundoApellido },
                { it.searchIndex.normNombre }
            )
        )
    }

    /**
     * Genera el listado compacto para copiar (una línea por trabajador):
     * APELLIDOS NOMBRE HABITACIÓN - PABELLÓN
     * Conserva el espacio de habitación si falta y no inventa datos si falta el pabellón.
     */
    fun generarListadoCompactoParaCopiar(trabajadoresOrdenados: List<TrabajadorConEstado>): String {
        return trabajadoresOrdenados.joinToString("\n") { t ->
            val persona = t.nombreCompleto
            val hab = t.habitacionMayusculas
            val pab = t.pabellonMayusculas
            when {
                hab.isNotBlank() && pab.isNotBlank() -> "$persona $hab - $pab"
                hab.isNotBlank() -> "$persona $hab"
                pab.isNotBlank() -> "$persona  - $pab"
                else -> persona
            }
        }
    }

    /**
     * Genera texto separado por tabuladores (TSV) con las cuatro columnas obligatorias:
     * APELLIDOS | NOMBRE | HABITACIÓN | PABELLÓN
     * para pegarse directamente en Excel manteniendo cada columna separada.
     */
    fun generarListadoTsvParaExcel(trabajadoresOrdenados: List<TrabajadorConEstado>): String {
        val lineas = mutableListOf("APELLIDOS\tNOMBRE\tHABITACIÓN\tPABELLÓN")
        for (t in trabajadoresOrdenados) {
            val cols = listOf(
                t.apellidosMayusculas,
                t.nombreSoloMayusculas,
                t.habitacionMayusculas,
                t.pabellonMayusculas
            ).map { it.replace("\t", " ").replace("\n", " ").replace("\r", " ") }
            lineas.add(cols.joinToString("\t"))
        }
        return lineas.joinToString("\n")
    }

    /**
     * Genera una tabla HTML de 4 columnas exactas (APELLIDOS, NOMBRE, HABITACIÓN, PABELLÓN)
     * para acompañar al TSV en ClipData.newHtmlText y asegurar que Excel/Sheets conserve
     * siempre las 4 columnas incluso cuando PABELLÓN está vacío.
     */
    fun generarListadoHtmlParaExcel(trabajadoresOrdenados: List<TrabajadorConEstado>): String {
        val sb = StringBuilder()
        sb.append("<table><thead><tr>")
        sb.append("<th>APELLIDOS</th><th>NOMBRE</th><th>HABITACIÓN</th><th>PABELLÓN</th>")
        sb.append("</tr></thead><tbody>")
        for (t in trabajadoresOrdenados) {
            sb.append("<tr>")
            sb.append("<td>").append(escaparHtml(t.apellidosMayusculas)).append("</td>")
            sb.append("<td>").append(escaparHtml(t.nombreSoloMayusculas)).append("</td>")
            sb.append("<td>").append(escaparHtml(t.habitacionMayusculas)).append("</td>")
            sb.append("<td>").append(escaparHtml(t.pabellonMayusculas)).append("</td>")
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        return sb.toString()
    }

    private fun escaparHtml(valor: String): String {
        return valor
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
