package com.example.data.importer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

data class HojaTabular(
    val nombre: String,
    val encabezados: List<String>,
    val filas: List<List<String>>
)

data class ArchivoTabular(
    val nombreArchivo: String,
    val hojas: List<HojaTabular>
)

enum class FormatoArchivo {
    XLSX,
    CSV
}

object SpreadsheetParser {

    fun parsear(
        nombreArchivo: String,
        inputStream: InputStream,
        mimeType: String? = null
    ): ArchivoTabular {
        val bytes = inputStream.readBytes()
        val formato = detectarFormato(nombreArchivo, mimeType, bytes)
        return when (formato) {
            FormatoArchivo.XLSX -> parsearXlsx(nombreArchivo, bytes)
            FormatoArchivo.CSV -> parsearCsv(nombreArchivo, bytes)
        }
    }

    private fun detectarFormato(
        nombreArchivo: String,
        mimeType: String?,
        bytes: ByteArray
    ): FormatoArchivo {
        val lower = nombreArchivo.lowercase()
        val esZip = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte()
        if (lower.endsWith(".xlsx") ||
            mimeType?.contains("spreadsheetml") == true ||
            esZip
        ) {
            return FormatoArchivo.XLSX
        }
        return FormatoArchivo.CSV
    }

    private fun parsearCsv(nombreArchivo: String, bytes: ByteArray): ArchivoTabular {
        val textoCrudo = decodificarTextoCsv(bytes).removePrefix("\uFEFF")
        val primeraLinea = textoCrudo.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val delimitador = detectarDelimitadorCsv(primeraLinea)
        val registros = parsearRegistrosCsv(textoCrudo, delimitador)
            .filter { fila -> fila.any { it.isNotBlank() } }

        if (registros.isEmpty()) {
            return ArchivoTabular(nombreArchivo, emptyList())
        }

        val maxCols = registros.maxOf { it.size }
        val encabezados = registros.first().normalizarAncho(maxCols).mapIndexed { i, v ->
            v.trim().ifBlank { "Columna ${i + 1}" }
        }
        val filas = registros.drop(1).map { it.normalizarAncho(maxCols).map { c -> c.trim() } }

        return ArchivoTabular(
            nombreArchivo = nombreArchivo,
            hojas = listOf(HojaTabular(nombre = "CSV", encabezados = encabezados, filas = filas))
        )
    }

    private fun decodificarTextoCsv(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) {
            bytes.toString(Charset.forName("ISO-8859-1"))
        } else {
            utf8
        }
    }

    private fun detectarDelimitadorCsv(primeraLinea: String): Char {
        val candidatos = listOf(';', ',', '\t')
        return candidatos.maxByOrNull { delim ->
            var enComillas = false
            var count = 0
            for (ch in primeraLinea) {
                if (ch == '"') enComillas = !enComillas
                else if (ch == delim && !enComillas) count++
            }
            count
        } ?: ','
    }

    private fun parsearRegistrosCsv(texto: String, delimitador: Char): List<List<String>> {
        val filas = mutableListOf<List<String>>()
        val filaActual = mutableListOf<String>()
        val celdaActual = StringBuilder()
        var enComillas = false
        var i = 0

        while (i < texto.length) {
            val c = texto[i]
            if (enComillas) {
                if (c == '"') {
                    if (i + 1 < texto.length && texto[i + 1] == '"') {
                        celdaActual.append('"')
                        i++
                    } else {
                        enComillas = false
                    }
                } else {
                    celdaActual.append(c)
                }
            } else {
                when (c) {
                    '"' -> enComillas = true
                    delimitador -> {
                        filaActual.add(celdaActual.toString())
                        celdaActual.clear()
                    }
                    '\r' -> {
                        if (i + 1 < texto.length && texto[i + 1] == '\n') i++
                        filaActual.add(celdaActual.toString())
                        celdaActual.clear()
                        filas.add(filaActual.toList())
                        filaActual.clear()
                    }
                    '\n' -> {
                        filaActual.add(celdaActual.toString())
                        celdaActual.clear()
                        filas.add(filaActual.toList())
                        filaActual.clear()
                    }
                    else -> celdaActual.append(c)
                }
            }
            i++
        }

        if (celdaActual.isNotEmpty() || filaActual.isNotEmpty()) {
            filaActual.add(celdaActual.toString())
            filas.add(filaActual.toList())
        }
        return filas
    }

    private fun parsearXlsx(nombreArchivo: String, bytes: ByteArray): ArchivoTabular {
        val entradas = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entradas[entry.name.trimStart('/')] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sharedStrings = entradas["xl/sharedStrings.xml"]
            ?.let { parsearSharedStrings(it) }
            ?: emptyList()

        val rels = entradas["xl/_rels/workbook.xml.rels"]
            ?.let { parsearWorkbookRels(it) }
            ?: emptyMap()

        val sheetsMeta = entradas["xl/workbook.xml"]
            ?.let { parsearWorkbookSheets(it, rels) }
            ?: entradas.keys
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted()
                .mapIndexed { idx, path -> "Hoja ${idx + 1}" to path }

        val hojas = sheetsMeta.mapNotNull { (sheetName, path) ->
            val xmlBytes = entradas[path] ?: return@mapNotNull null
            val matriz = parsearWorksheetXml(xmlBytes, sharedStrings)
                .filter { fila -> fila.any { it.isNotBlank() } }
            if (matriz.isEmpty()) return@mapNotNull null
            val maxCols = matriz.maxOf { it.size }.coerceAtLeast(1)
            val encabezados = matriz.first().normalizarAncho(maxCols).mapIndexed { i, v ->
                v.trim().ifBlank { "Columna ${i + 1}" }
            }
            val filas = matriz.drop(1).map { it.normalizarAncho(maxCols).map { c -> c.trim() } }
            HojaTabular(nombre = sheetName, encabezados = encabezados, filas = filas)
        }

        return ArchivoTabular(nombreArchivo = nombreArchivo, hojas = hojas)
    }

    private fun crearPullParser(xmlBytes: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }
        return factory.newPullParser().apply {
            setInput(StringReader(xmlBytes.toString(Charsets.UTF_8)))
        }
    }

    private fun parsearSharedStrings(xmlBytes: ByteArray): List<String> {
        val lista = mutableListOf<String>()
        val parser = crearPullParser(xmlBytes)
        var dentroSi = false
        val buffer = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = localTagName(parser.name)
                    if (tag == "si") {
                        dentroSi = true
                        buffer.clear()
                    } else if (dentroSi && tag == "t") {
                        buffer.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (localTagName(parser.name) == "si" && dentroSi) {
                        lista.add(buffer.toString())
                        dentroSi = false
                    }
                }
            }
            event = parser.next()
        }
        return lista
    }

    private fun parsearWorkbookRels(xmlBytes: ByteArray): Map<String, String> {
        val mapa = mutableMapOf<String, String>()
        val parser = crearPullParser(xmlBytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && localTagName(parser.name) == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                    val ruta = if (target.startsWith("/")) {
                        target.trimStart('/')
                    } else if (target.startsWith("xl/")) {
                        target
                    } else {
                        "xl/${target.trimStart('/')}"
                    }
                    mapa[id] = ruta
                }
            }
            event = parser.next()
        }
        return mapa
    }

    private fun parsearWorkbookSheets(
        xmlBytes: ByteArray,
        rels: Map<String, String>
    ): List<Pair<String, String>> {
        val hojas = mutableListOf<Pair<String, String>>()
        val parser = crearPullParser(xmlBytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && localTagName(parser.name) == "sheet") {
                val nombre = parser.getAttributeValue(null, "name") ?: "Hoja ${hojas.size + 1}"
                val rId = parser.getAttributeValue(null, "r:id")
                    ?: (0 until parser.attributeCount)
                        .firstOrNull { parser.getAttributeName(it).endsWith(":id") }
                        ?.let { parser.getAttributeValue(it) }
                val path = rId?.let { rels[it] } ?: "xl/worksheets/sheet${hojas.size + 1}.xml"
                hojas.add(nombre to path)
            }
            event = parser.next()
        }
        return hojas
    }

    private fun parsearWorksheetXml(
        xmlBytes: ByteArray,
        sharedStrings: List<String>
    ): List<List<String>> {
        val filas = mutableListOf<List<String>>()
        val parser = crearPullParser(xmlBytes)

        var filaActual: MutableList<String>? = null
        var colActual = 0
        var tipoCelda: String? = null
        var dentroIs = false
        val bufferInline = StringBuilder()
        var valorV: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (localTagName(parser.name)) {
                        "row" -> {
                            filaActual = mutableListOf()
                            colActual = 0
                        }
                        "c" -> {
                            val ref = parser.getAttributeValue(null, "r")
                            colActual = if (!ref.isNullOrBlank()) {
                                extraerIndiceColumna(ref)
                            } else {
                                filaActual?.size ?: 0
                            }
                            tipoCelda = parser.getAttributeValue(null, "t")
                            valorV = null
                            bufferInline.clear()
                            dentroIs = false
                        }
                        "is" -> dentroIs = true
                        "t" -> {
                            if (dentroIs) {
                                bufferInline.append(parser.nextText())
                            }
                        }
                        "v" -> {
                            valorV = parser.nextText()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (localTagName(parser.name)) {
                        "is" -> dentroIs = false
                        "c" -> {
                            val fila = filaActual
                            if (fila != null) {
                                while (fila.size <= colActual) fila.add("")
                                val texto = when (tipoCelda) {
                                    "s" -> valorV?.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                                    "inlineStr" -> bufferInline.toString()
                                    "str", "b", "e" -> valorV.orEmpty()
                                    else -> limpiarNumeroExcel(valorV.orEmpty())
                                }
                                fila[colActual] = texto
                            }
                        }
                        "row" -> {
                            filaActual?.let { filas.add(it.toList()) }
                            filaActual = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return filas
    }

    private fun localTagName(raw: String?): String {
        val name = raw.orEmpty()
        val idx = name.indexOf(':')
        return if (idx >= 0) name.substring(idx + 1) else name
    }

    private fun extraerIndiceColumna(referenciaCelda: String): Int {
        var indice = 0
        for (ch in referenciaCelda) {
            if (ch.isLetter()) {
                indice = indice * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else {
                break
            }
        }
        return (indice - 1).coerceAtLeast(0)
    }

    private fun limpiarNumeroExcel(valor: String): String {
        if (valor.endsWith(".0") && valor.count { it == '.' } == 1) {
            val entero = valor.removeSuffix(".0")
            if (entero.toLongOrNull() != null) return entero
        }
        return valor
    }

    private fun List<String>.normalizarAncho(ancho: Int): List<String> {
        if (size >= ancho) return this
        return this + List(ancho - size) { "" }
    }
}
