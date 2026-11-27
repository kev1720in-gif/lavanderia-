package com.example.lucy.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.Turno
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import kotlin.math.max

data class LucyVisionImagePayload(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/jpeg",
    val preprocessingSummary: String? = null,
    val segmentedRowsHint: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LucyVisionImagePayload) return false
        return width == other.width &&
            height == other.height &&
            mimeType == other.mimeType &&
            preprocessingSummary == other.preprocessingSummary &&
            segmentedRowsHint == other.segmentedRowsHint &&
            jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int {
        var result = jpegBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (preprocessingSummary?.hashCode() ?: 0)
        result = 31 * result + segmentedRowsHint
        return result
    }
}

sealed interface LucyVisionImageLoadResult {
    data class Loaded(val payload: LucyVisionImagePayload) : LucyVisionImageLoadResult
    data class Failed(val reason: String) : LucyVisionImageLoadResult
}

data class LucyVisionRequestContext(
    val turno: Turno,
    val empresaSugerida: EmpresaOficial? = null,
    val empresasOficiales: List<String> = EmpresaOficial.todas.map { it.nombre },
    val preprocessingSummary: String? = null,
    val segmentedRowsHint: Int = 0
)

data class LucyVisionGatewayStatus(
    val isConfigured: Boolean,
    val isNetworkAvailable: Boolean,
    val activeModelName: String,
    val requiresInternet: Boolean = true,
    val statusMessage: String
)

sealed interface LucyVisionRawGatewayResult {
    data class Success(
        val rawJson: String,
        val modelName: String
    ) : LucyVisionRawGatewayResult

    data class Failure(
        val status: LucyVisionDocumentStatus,
        val reason: String,
        val modelName: String
    ) : LucyVisionRawGatewayResult
}

interface LucyVisionImageLoader {
    suspend fun loadPreparedImage(
        imagePath: String,
        maxDimPx: Int,
        jpegQuality: Int,
        maxBytes: Int
    ): LucyVisionImageLoadResult
}

/**
 * Interfaz aislada para motores externos de comprensión visual.
 * Evita que Lucy Core o el resto de la app dependan directamente del SDK remoto.
 */
interface LucyVisionModelGateway {
    val providerName: String
    suspend fun checkStatus(): LucyVisionGatewayStatus
    suspend fun analyzeSheetImage(
        payload: LucyVisionImagePayload,
        request: LucyVisionRequestContext
    ): LucyVisionRawGatewayResult
}

/**
 * Cargador de imágenes reutilizando la decodificación segura EXIF/ARGB de `OcrImageProcessor`
 * sin modificar el archivo original del usuario y manteniendo el payload muy por debajo del límite de 20 MB.
 */
class AndroidLucyVisionImageLoader(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LucyVisionImageLoader {

    override suspend fun loadPreparedImage(
        imagePath: String,
        maxDimPx: Int,
        jpegQuality: Int,
        maxBytes: Int
    ): LucyVisionImageLoadResult = withContext(ioDispatcher) {
        if (imagePath.isBlank()) {
            return@withContext LucyVisionImageLoadResult.Failed("La ruta de la fotografía está vacía.")
        }
        val file = File(imagePath)
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            return@withContext LucyVisionImageLoadResult.Failed(
                "El archivo de fotografía no existe o está vacío."
            )
        }

        var preparedBitmap: Bitmap? = null
        try {
            val preprocessed = LucyVisionDocumentPreprocessor.prepararDocumento(
                archivo = file,
                maxDimPx = maxDimPx
            ) ?: return@withContext LucyVisionImageLoadResult.Failed(
                "No se pudo decodificar la fotografía (formato no soportado o archivo dañado)."
            )
            preparedBitmap = preprocessed.bitmap

            var quality = jpegQuality.coerceIn(50, 95)
            var bytes = compressJpeg(preparedBitmap, quality)
            while (bytes.size > maxBytes && quality > 45) {
                quality -= 10
                bytes = compressJpeg(preparedBitmap, quality)
            }

            if (bytes.isEmpty() || bytes.size > maxBytes) {
                return@withContext LucyVisionImageLoadResult.Failed(
                    "La fotografía excede el tamaño máximo permitido para envío seguro (${maxBytes / (1024 * 1024)} MB)."
                )
            }

            LucyVisionImageLoadResult.Loaded(
                LucyVisionImagePayload(
                    jpegBytes = bytes,
                    width = preparedBitmap.width,
                    height = preparedBitmap.height,
                    preprocessingSummary = preprocessed.resumenTecnico,
                    segmentedRowsHint = preprocessed.filasSegmentadasDetectadas
                )
            )
        } catch (oom: OutOfMemoryError) {
            LucyVisionImageLoadResult.Failed(
                "Memoria insuficiente al preparar la fotografía para Lucy Vision."
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (e: Exception) {
            LucyVisionImageLoadResult.Failed(
                "No se pudo preparar la imagen (${e.message ?: "error inesperado"})."
            )
        } finally {
            preparedBitmap?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            baos.toByteArray()
        }
    }
}

/**
 * Adaptador oficial sobre Firebase AI Logic (`Firebase.ai`) con salida JSON estructurada (`Schema.obj`).
 * - Inicialización lazy para no fallar si Firebase aún no está listo al construir objetos.
 * - No requiere API key ni Firestore; utiliza Firebase App Check.
 * - Requiere conexión a Internet y cuota de Firebase AI Logic vigente.
 */
class FirebaseAiLucyVisionGateway(
    context: Context,
    private val config: LucyVisionConfig = LucyVisionConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LucyVisionModelGateway {

    private val appContext = context.applicationContext

    override val providerName: String = "Firebase AI Logic (${config.modelName})"

    private val sheetSchema: Schema by lazy {
        Schema.obj(
            properties = mapOf(
                "isReadable" to Schema.boolean(
                    description = "true si la fotografía permite leer al menos parte de una planilla o listado operativo; false si está borrosa, oscura, cortada o ilegible."
                ),
                "legibilityStatus" to Schema.enumeration(
                    values = listOf("LEGIBLE", "PARCIALMENTE_LEGIBLE", "ILEGIBLE"),
                    description = "Nivel global de legibilidad de la imagen."
                ),
                "documentTextType" to Schema.enumeration(
                    values = listOf("IMPRESO", "MANUSCRITO", "MIXTO", "INDETERMINADO"),
                    description = "Tipo de escritura predominante en la planilla o documento."
                ),
                "detectedCompanyText" to Schema.string(
                    description = "Nombre de empresa literalmente visible en encabezado/membrete de la planilla si aparece; null si no aparece.",
                    nullable = true
                ),
                "documentNotes" to Schema.array(
                    items = Schema.string(),
                    description = "Observaciones generales, zonas ilegibles, cortes, tachaduras o dudas del documento."
                ),
                "rows" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "rowNumber" to Schema.integer(
                                description = "Número de fila o ítem si aparece impreso o manuscrito.",
                                nullable = true
                            ),
                            "rawText" to Schema.string(
                                description = "Transcripción literal completa de la fila observada."
                            ),
                            "detectedPaternalLastName" to Schema.string(
                                description = "Apellido paterno literalmente visible en la fila; null si no se distingue o no figura.",
                                nullable = true
                            ),
                            "detectedMaternalLastName" to Schema.string(
                                description = "Apellido materno literalmente visible en la fila; null si no se distingue o no figura.",
                                nullable = true
                            ),
                            "detectedLastNames" to Schema.string(
                                description = "Apellidos completos literalmente visibles en la fila (paterno y materno); null si no se distinguen.",
                                nullable = true
                            ),
                            "detectedFirstName" to Schema.string(
                                description = "Nombre(s) de pila literalmente visibles en la fila si aparecen; null si no se distinguen.",
                                nullable = true
                            ),
                            "detectedName" to Schema.string(
                                description = "Nombre y/o apellidos detectados en esa fila exacta (solo texto visible, sin inventar apellidos faltantes)."
                            ),
                            "textOriginType" to Schema.enumeration(
                                values = listOf("IMPRESO", "MANUSCRITO", "MIXTO", "INDETERMINADO"),
                                description = "Si la escritura de esta fila es IMPRESO, MANUSCRITO, MIXTO o INDETERMINADO."
                            ),
                            "detectedCompanyText" to Schema.string(
                                description = "Empresa indicada específicamente en esa fila si aparece; null si no figura.",
                                nullable = true
                            ),
                            "detectedPavilion" to Schema.string(
                                description = "Pabellón observado exactamente en la MISMA fila del trabajador si aparece (ej. 18 SUR, B2); null si no figura.",
                                nullable = true
                            ),
                            "detectedRoom" to Schema.string(
                                description = "Número o código de habitación observado exactamente en la MISMA fila del trabajador si aparece; null si no figura.",
                                nullable = true
                            ),
                            "visualMarkingText" to Schema.string(
                                description = "Marca visual literal observada sobre o junto a la fila (ej. tic, ✓, X, SÍ, NO, tarjado, subrayado, en blanco); null si no hay columna/marca.",
                                nullable = true
                            ),
                            "rowAnnotations" to Schema.string(
                                description = "Anotaciones adicionales, observaciones manuscritas, notas al margen o correcciones hechas sobre esa fila; null si no hay.",
                                nullable = true
                            ),
                            "hasManualMarks" to Schema.boolean(
                                description = "true si existen marcas hechas a mano (tic, X, rayado, círculo, subrayado o anotación manual) sobre esa fila."
                            ),
                            "interpretedMarkingStatus" to Schema.enumeration(
                                values = listOf("SI_BAJO", "NO_BAJO", "INDETERMINADO"),
                                description = "Interpretación de bajada/subida según marcas visibles sobre la fila; usa INDETERMINADO si no es claro o no hay marca."
                            ),
                            "confidence" to Schema.double(
                                description = "Confianza de lectura entre 0.0 y 1.0."
                            ),
                            "ambiguityNotes" to Schema.array(
                                items = Schema.string(),
                                description = "Dudas específicas de esa fila (letra poco clara, abreviatura, posible tachadura, falta apellido)."
                            ),
                            "isIncompleteOrIllegible" to Schema.boolean(
                                description = "true si la fila está parcialmente ilegible, borrosa, dudosa o incompleta."
                            )
                        ),
                        optionalProperties = listOf(
                            "rowNumber",
                            "detectedPaternalLastName",
                            "detectedMaternalLastName",
                            "detectedLastNames",
                            "detectedFirstName",
                            "textOriginType",
                            "detectedCompanyText",
                            "detectedPavilion",
                            "detectedRoom",
                            "visualMarkingText",
                            "rowAnnotations",
                            "hasManualMarks",
                            "confidence",
                            "ambiguityNotes",
                            "isIncompleteOrIllegible"
                        )
                    ),
                    description = "Lista ordenada de filas o personas detectadas en la planilla."
                )
            ),
            optionalProperties = listOf("documentTextType", "detectedCompanyText", "documentNotes")
        )
    }

    private fun createModel(modelName: String) = Firebase.ai.generativeModel(
        modelName = modelName,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = sheetSchema
            temperature = 0.1f
        }
    )

    private val model by lazy { createModel(config.modelName) }
    private val fallbackModel by lazy { createModel(config.fallbackModelName) }

    override suspend fun checkStatus(): LucyVisionGatewayStatus {
        val firebaseReady = isFirebaseConfigured()
        val networkReady = isNetworkAvailable()
        val msg = when {
            !firebaseReady -> "Firebase no está inicializado en el dispositivo (falta configuración google-services.json)."
            !networkReady -> "Sin conexión a Internet. Lucy Vision con Gemini requiere red activa."
            else -> "Listo con ${config.modelName} vía Firebase AI Logic (requiere conexión)."
        }
        return LucyVisionGatewayStatus(
            isConfigured = firebaseReady,
            isNetworkAvailable = networkReady,
            activeModelName = config.modelName,
            requiresInternet = true,
            statusMessage = msg
        )
    }

    override suspend fun analyzeSheetImage(
        payload: LucyVisionImagePayload,
        request: LucyVisionRequestContext
    ): LucyVisionRawGatewayResult = withContext(ioDispatcher) {
        if (!isFirebaseConfigured()) {
            return@withContext LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.NO_CONFIGURADO,
                reason = "Firebase AI Logic no está configurado en el proyecto o dispositivo.",
                modelName = config.modelName
            )
        }
        if (!isNetworkAvailable()) {
            return@withContext LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.SIN_CONEXION,
                reason = "No hay conexión a Internet disponible. Lucy Vision requiere red para consultar Gemini; puedes usar el OCR offline en Control de Descarga.",
                modelName = config.modelName
            )
        }

        var bitmap: Bitmap? = null
        try {
            bitmap = BitmapFactory.decodeByteArray(payload.jpegBytes, 0, payload.jpegBytes.size)
                ?: return@withContext LucyVisionRawGatewayResult.Failure(
                    status = LucyVisionDocumentStatus.ERROR_PROVEEDOR,
                    reason = "No se pudo reconstruir la imagen JPEG para enviarla a Gemini.",
                    modelName = config.modelName
                )

            val prompt = buildOperationalPrompt(request)
            Log.d(
                TAG,
                "Enviando imagen real a Firebase AI Logic (${config.modelName}): ${bitmap.width}x${bitmap.height}px, ${payload.jpegBytes.size} bytes JPEG"
            )

            val primaryResult = invokeGenerativeModel(model, config.modelName, bitmap, prompt)
            if (primaryResult is LucyVisionRawGatewayResult.Success) {
                return@withContext primaryResult
            }

            val failure = primaryResult as LucyVisionRawGatewayResult.Failure
            val puedeReintentarFallback = config.fallbackModelName.isNotBlank() &&
                config.fallbackModelName != config.modelName &&
                failure.status in setOf(
                    LucyVisionDocumentStatus.ERROR_PROVEEDOR,
                    LucyVisionDocumentStatus.RESPUESTA_INVALIDA,
                    LucyVisionDocumentStatus.CUOTA_O_FACTURACION
                )

            if (puedeReintentarFallback) {
                Log.w(
                    TAG,
                    "Reintentando con modelo fallback (${config.fallbackModelName}) tras ${failure.status}: ${failure.reason}"
                )
                return@withContext invokeGenerativeModel(fallbackModel, config.fallbackModelName, bitmap, prompt)
            }

            failure
        } finally {
            bitmap?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private suspend fun invokeGenerativeModel(
        generativeModel: com.google.firebase.ai.GenerativeModel,
        modelName: String,
        bitmap: Bitmap,
        prompt: String
    ): LucyVisionRawGatewayResult {
        return try {
            val response = withTimeout(config.timeoutMs) {
                generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
            }
            val rawText = response.text?.trim().orEmpty()
            if (rawText.isEmpty()) {
                LucyVisionRawGatewayResult.Failure(
                    status = LucyVisionDocumentStatus.RESPUESTA_INVALIDA,
                    reason = "Gemini ($modelName) devolvió una respuesta vacía.",
                    modelName = modelName
                )
            } else {
                LucyVisionRawGatewayResult.Success(
                    rawJson = rawText,
                    modelName = modelName
                )
            }
        } catch (te: TimeoutCancellationException) {
            Log.e(TAG, "Tiempo de espera agotado en Firebase AI Vision ($modelName)", te)
            LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.TIMEOUT,
                reason = "El análisis visual superó el tiempo de espera (${config.timeoutMs / 1000} s).",
                modelName = modelName
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (le: LinkageError) {
            Log.e(TAG, "Error de enlace nativo/SDK en Firebase AI Vision ($modelName)", le)
            LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.ERROR_PROVEEDOR,
                reason = "Error técnico del módulo de visión (${le.message ?: le::class.java.simpleName}).",
                modelName = modelName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al consultar Firebase AI Vision ($modelName)", e)
            classifyRemoteException(e, modelName)
        }
    }

    private fun isFirebaseConfigured(): Boolean {
        return try {
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                true
            } else {
                FirebaseApp.initializeApp(appContext) != null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: Exception) {
            false
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun classifyRemoteException(
        e: Exception,
        modelName: String = config.modelName
    ): LucyVisionRawGatewayResult.Failure {
        val causaCadena = generateSequence<Throwable>(e) { it.cause }
            .take(5)
            .toList()
        val msg = causaCadena
            .mapNotNull { it.message?.trim()?.takeIf { m -> m.isNotEmpty() } }
            .distinct()
            .joinToString(" | ")
        val lower = msg.lowercase()
        val hasNetworkCause = causaCadena.any { it is UnknownHostException || it is IOException }
        val status = when {
            hasNetworkCause ||
                "unable to resolve host" in lower || "network error" in lower || "timed out" in lower || "failed to connect" in lower ->
                LucyVisionDocumentStatus.SIN_CONEXION
            "403" in lower || "app check" in lower || "appcheck" in lower || "attestation" in lower || "permission_denied" in lower ->
                LucyVisionDocumentStatus.ERROR_APP_CHECK
            "429" in lower || "quota" in lower || "resource_exhausted" in lower || "billing" in lower ->
                LucyVisionDocumentStatus.CUOTA_O_FACTURACION
            "not configured" in lower || "google-services" in lower || "firebaseapp" in lower ->
                LucyVisionDocumentStatus.NO_CONFIGURADO
            else -> LucyVisionDocumentStatus.ERROR_PROVEEDOR
        }
        val userReason = when (status) {
            LucyVisionDocumentStatus.SIN_CONEXION ->
                "No se pudo conectar con Gemini por falta de red o fallo de conexión."
            LucyVisionDocumentStatus.ERROR_APP_CHECK ->
                "Firebase App Check rechazó o no pudo validar la solicitud (403). Si usas una build debug manual, verifica el token de depuración de App Check o la red."
            LucyVisionDocumentStatus.CUOTA_O_FACTURACION ->
                "Se alcanzó el límite de cuota o el modelo no está disponible con el plan actual."
            LucyVisionDocumentStatus.NO_CONFIGURADO ->
                "Falta configuración activa de Firebase AI Logic."
            else -> "No se pudo completar el análisis visual (${msg.ifBlank { e::class.java.simpleName }})."
        }
        return LucyVisionRawGatewayResult.Failure(
            status = status,
            reason = userReason,
            modelName = modelName
        )
    }

    private fun buildOperationalPrompt(request: LucyVisionRequestContext): String {
        val listaEmpresas = request.empresasOficiales.joinToString(", ")
        val empresaHint = request.empresaSugerida?.nombre ?: "No especificada previamente"
        val preprocInfo = buildList {
            request.preprocessingSummary?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (request.segmentedRowsHint > 0) add("segmentación previa estimó ~${request.segmentedRowsHint} banda(s) de fila")
        }.joinToString("; ").ifBlank { "imagen normalizada" }

        return """
            Eres el módulo de comprensión visual de Lavandería Control en faena minera (Chile).
            Analiza la fotografía real adjunta de una planilla de lavandería (puede contener tabla impresa de sistema/Excel, texto manuscrito, o marcas manuales con lápiz sobre las filas).

            Contexto operativo (solo referencia, NO inventes ni rellenes datos faltantes con este contexto):
            - Turno activo: ${request.turno.nombre}
            - Empresa sugerida por el operador: $empresaHint
            - Empresas oficiales existentes: $listaEmpresas
            - Preprocesamiento aplicado a la imagen: $preprocInfo

            Reglas estrictas de extracción y alineación tabular:
            1. Transcribe ÚNICAMENTE lo que se ve en la fotografía. Jamás inventes nombres, apellidos, habitaciones, pabellones ni empresas que no aparezcan en la imagen.
            2. Respeta ESTRICTAMENTE la alineación de filas y columnas: cada nombre debe quedar asociado exclusivamente a la habitación, pabellón, empresa y marca de SU PROPIA FILA horizontal. Nunca mezcles ni desplaces celdas de una fila superior o inferior.
            3. Si la imagen permite leer al menos un nombre o fila (aunque no tenga columna de marcación), marca isReadable=true y legibilityStatus="LEGIBLE" o "PARCIALMENTE_LEGIBLE", dejando interpretedMarkingStatus="INDETERMINADO" cuando no haya marca clara.
            4. Solo marca isReadable=false y legibilityStatus="ILEGIBLE" si la fotografía está completamente borrosa, oscura, tapada o no contiene texto operativo legible, explicando el motivo en documentNotes.
            5. Separa claramente el origen de escritura:
               - documentTextType y textOriginType por fila: "IMPRESO" (letra de computador/tabla), "MANUSCRITO" (escrito a mano), "MIXTO" (impreso con anotaciones/marcas a mano) o "INDETERMINADO".
            6. Extrae por cada fila:
               - detectedPaternalLastName: apellido paterno literalmente visible (o null).
               - detectedMaternalLastName: apellido materno literalmente visible (o null).
               - detectedLastNames: ambos apellidos visibles juntos (o null).
               - detectedFirstName: nombre(s) de pila visibles (o null).
               - detectedName: nombre completo o parcial literalmente observado en la fila.
               - detectedRoom y detectedPavilion: habitación y pabellón de esa MISMA fila (o null si no figuran).
               - detectedCompanyText: empresa visible en esa fila (no copies la del encabezado/contexto si no está en la fila).
               - visualMarkingText: marca visual literal sobre la fila (✓, tic, X, SÍ, NO, tarjado, círculo, en blanco).
               - rowAnnotations: anotaciones manuscritas, observaciones al margen o notas sobre la fila.
               - hasManualMarks: true si la fila tiene marcas o anotaciones hechas a mano.
               - interpretedMarkingStatus: "SI_BAJO", "NO_BAJO" o "INDETERMINADO".
            7. Asigna confidence realista entre 0.0 y 1.0. Si una celda o trazo manuscrito es dudoso, borroso, abreviado o parcialmente tachado, marca isIncompleteOrIllegible=true y explica exactamente la duda en ambiguityNotes.
            8. No resumas ni omitas filas repetidas: incluye todas las filas en orden descendente con su rowNumber.
        """.trimIndent()
    }

    companion object {
        private const val TAG = "FirebaseAiVisionGw"
    }
}
