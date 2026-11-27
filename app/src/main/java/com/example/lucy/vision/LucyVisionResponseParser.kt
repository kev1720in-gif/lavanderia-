package com.example.lucy.vision

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

sealed interface LucyVisionJsonParseResult {
    data class Valid(val sheet: LucyVisionParsedSheet) : LucyVisionJsonParseResult
    data class Invalid(val reason: String) : LucyVisionJsonParseResult
}

/**
 * Parser estricto de la respuesta JSON estructurada generada por el modelo de visión.
 * Rechaza texto libre no JSON, estructuras incompletas o filas corruptas sin lanzar excepciones no controladas.
 */
object LucyVisionResponseParser {

    private val VALID_LEGIBILITY = setOf("LEGIBLE", "PARCIALMENTE_LEGIBLE", "ILEGIBLE")

    fun parseStructuredJson(rawJson: String): LucyVisionJsonParseResult {
        val trimmed = rawJson.trim().trimStart('\uFEFF')
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return LucyVisionJsonParseResult.Invalid(
                "El modelo no devolvió un objeto JSON estructurado válido."
            )
        }

        val root = try {
            JSONObject(trimmed)
        } catch (je: JSONException) {
            return LucyVisionJsonParseResult.Invalid(
                "JSON malformado en respuesta del modelo: ${je.message ?: "error de sintaxis"}"
            )
        }

        if (!root.has("isReadable") || !root.has("legibilityStatus") || !root.has("rows")) {
            return LucyVisionJsonParseResult.Invalid(
                "La respuesta del modelo no contiene los campos obligatorios (isReadable, legibilityStatus, rows)."
            )
        }

        val isReadable = root.opt("isReadable") as? Boolean
            ?: return LucyVisionJsonParseResult.Invalid(
                "Campo 'isReadable' inválido en la respuesta del modelo."
            )

        val legibility = root.optString("legibilityStatus", "").trim().uppercase()
        if (legibility !in VALID_LEGIBILITY) {
            return LucyVisionJsonParseResult.Invalid(
                "Valor de 'legibilityStatus' desconocido: '$legibility'."
            )
        }

        val rowsArray = root.optJSONArray("rows")
            ?: return LucyVisionJsonParseResult.Invalid("Campo 'rows' no es un arreglo JSON válido.")

        val documentTextType = LucyVisionTextOriginType.fromRaw(nullableCleanString(root, "documentTextType"))
        val detectedCompanyText = nullableCleanString(root, "detectedCompanyText")
        val documentNotes = parseStringArray(root.optJSONArray("documentNotes"))

        val parsedRows = mutableListOf<LucyVisionParsedRow>()
        for (i in 0 until rowsArray.length()) {
            val rowObj = rowsArray.optJSONObject(i)
                ?: return LucyVisionJsonParseResult.Invalid("Elemento #$i en 'rows' no es un objeto JSON válido.")

            val detectedPaternal = nullableCleanString(rowObj, "detectedPaternalLastName")
            val detectedMaternal = nullableCleanString(rowObj, "detectedMaternalLastName")
            val combinedSurnames = listOfNotNull(detectedPaternal, detectedMaternal)
                .joinToString(" ")
                .trim()
                .takeIf { it.isNotBlank() }
            val detectedLastNames = nullableCleanString(rowObj, "detectedLastNames") ?: combinedSurnames
            val detectedFirstName = nullableCleanString(rowObj, "detectedFirstName")
            val rawText = rowObj.optString("rawText", "").trim()
            val explicitName = nullableCleanString(rowObj, "detectedName").orEmpty()
            val combinedFromParts = listOfNotNull(detectedLastNames, detectedFirstName)
                .joinToString(" ")
                .trim()
            val detectedName = explicitName.ifBlank { combinedFromParts }.ifBlank { rawText }

            if (!rowObj.has("detectedName") && !rowObj.has("rawText") && combinedFromParts.isBlank()) {
                return LucyVisionJsonParseResult.Invalid("Fila #$i sin 'detectedName' ni 'rawText'.")
            }

            val rowNumber = if (rowObj.isNull("rowNumber")) null else {
                val n = rowObj.optInt("rowNumber", -1)
                if (n > 0) n else null
            }

            val statusRaw = rowObj.optString("interpretedMarkingStatus", "INDETERMINADO").trim().uppercase()
            val markingStatus = when (statusRaw) {
                "SI_BAJO" -> LucyVisionMarkingStatus.SI_BAJO
                "NO_BAJO" -> LucyVisionMarkingStatus.NO_BAJO
                "INDETERMINADO" -> LucyVisionMarkingStatus.INDETERMINADO
                else -> return LucyVisionJsonParseResult.Invalid(
                    "Estado de marcación inválido en fila #$i: '$statusRaw'."
                )
            }

            val rawConf = if (!rowObj.has("confidence") || rowObj.isNull("confidence")) {
                0.50
            } else {
                val rawObj = rowObj.opt("confidence")
                if (rawObj !is Number) {
                    return LucyVisionJsonParseResult.Invalid("Confianza ausente o no numérica en fila #$i.")
                }
                rawObj.toDouble()
            }
            val confidence = rawConf.toFloat().coerceIn(0f, 1f)

            val ambiguityNotes = parseStringArray(rowObj.optJSONArray("ambiguityNotes"))
            val isIncomplete = rowObj.optBoolean("isIncompleteOrIllegible", false)
            val rowAnnotations = nullableCleanString(rowObj, "rowAnnotations")
            val visualMarkingText = nullableCleanString(rowObj, "visualMarkingText")
            val hasManualMarks = rowObj.optBoolean("hasManualMarks", false) ||
                !visualMarkingText.isNullOrBlank() ||
                !rowAnnotations.isNullOrBlank()
            val rowTextOrigin = LucyVisionTextOriginType.fromRaw(nullableCleanString(rowObj, "textOriginType"))
                .let { origin -> if (origin == LucyVisionTextOriginType.INDETERMINADO) documentTextType else origin }

            parsedRows.add(
                LucyVisionParsedRow(
                    rowNumber = rowNumber,
                    rawText = rawText.ifBlank { detectedName },
                    detectedName = detectedName,
                    detectedLastNames = detectedLastNames,
                    detectedPaternalLastName = detectedPaternal,
                    detectedMaternalLastName = detectedMaternal,
                    detectedFirstName = detectedFirstName,
                    textOriginType = rowTextOrigin,
                    detectedCompanyText = nullableCleanString(rowObj, "detectedCompanyText"),
                    detectedPavilion = nullableCleanString(rowObj, "detectedPavilion"),
                    detectedRoom = nullableCleanString(rowObj, "detectedRoom"),
                    visualMarkingText = visualMarkingText,
                    rowAnnotations = rowAnnotations,
                    hasManualMarks = hasManualMarks,
                    interpretedMarkingStatus = markingStatus,
                    confidence = confidence,
                    ambiguityNotes = ambiguityNotes,
                    isIncompleteOrIllegible = isIncomplete
                )
            )
        }

        return LucyVisionJsonParseResult.Valid(
            LucyVisionParsedSheet(
                isReadable = isReadable,
                legibilityStatus = legibility,
                documentTextType = documentTextType,
                detectedCompanyText = detectedCompanyText,
                documentNotes = documentNotes,
                rows = parsedRows
            )
        )
    }

    private fun nullableCleanString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.optString(key, "").trim()
        return value.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val s = array.optString(i, "").trim()
            if (s.isNotEmpty() && !s.equals("null", ignoreCase = true)) {
                result.add(s)
            }
        }
        return result
    }
}
