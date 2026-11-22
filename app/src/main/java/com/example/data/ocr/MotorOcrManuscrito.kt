package com.example.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ResultadoFilaOcr(
    val numeroFila: Int,
    val textoReconocido: String,
    val confianzaPromedio: Float,
    val legible: Boolean
)

data class ResultadoReconocimientoMotor(
    val filas: List<ResultadoFilaOcr>,
    val motorDescripcion: String,
    val usoDeteccionDbNetFallback: Boolean
)

/**
 * Motor OCR offline para escritura manuscrita en español basado en:
 * - PaddleOCR PP-OCRv5 Mobile (`ppocrv5_mobile_det.onnx` + `latin_ppocrv5_mobile_rec.onnx`)
 * - Ejecución local vía ONNX Runtime (sin internet, sin servidores, sin Firebase).
 */
class MotorOcrManuscrito private constructor() {

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dictChars: List<String> = emptyList()
    private val initMutex = Mutex()
    private var inicializado = false

    suspend fun asegurarInicializado(context: Context) {
        if (inicializado && detSession != null && recSession != null && dictChars.isNotEmpty()) {
            return
        }
        initMutex.withLock {
            if (inicializado && detSession != null && recSession != null && dictChars.isNotEmpty()) {
                return
            }
            val detBytes = context.assets.open(ASSET_DET_MODEL).use { it.readBytes() }
            val recBytes = context.assets.open(ASSET_REC_MODEL).use { it.readBytes() }
            val dictLines = context.assets.open(ASSET_LATIN_DICT).bufferedReader(Charsets.UTF_8).use {
                it.readLines()
            }
            cargarSesiones(detBytes, recBytes, dictLines)
        }
    }

    suspend fun inicializarDesdeArchivos(detFile: File, recFile: File, dictFile: File) {
        initMutex.withLock {
            val detBytes = detFile.readBytes()
            val recBytes = recFile.readBytes()
            val dictLines = dictFile.readLines(Charsets.UTF_8)
            cargarSesiones(detBytes, recBytes, dictLines)
        }
    }

    private fun cargarSesiones(detBytes: ByteArray, recBytes: ByteArray, dictLines: List<String>) {
        val ortEnv = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val threads = min(4, max(1, Runtime.getRuntime().availableProcessors() / 2))
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        detSession?.close()
        recSession?.close()
        detSession = ortEnv.createSession(detBytes, opts)
        recSession = ortEnv.createSession(recBytes, opts)
        // En inference.yml de latin_PP-OCRv5_mobile_rec existen 836 símbolos; el índice 0 es CTC blank y el 837 espacio
        dictChars = dictLines.filter { it.isNotEmpty() }
        inicializado = true
    }

    suspend fun procesarFilasPlanilla(
        preprocesado: ResultadoPreprocesamientoPlanilla,
        onProgresoFila: suspend (filaActual: Int, totalFilas: Int) -> Unit = { _, _ -> }
    ): ResultadoReconocimientoMotor {
        check(inicializado) { "El motor OCR PP-OCRv5 no ha sido inicializado." }

        val filasEntrada: List<FilaSegmentadaPlanilla>
        val usoDbNetFallback: Boolean
        if (preprocesado.filas.isNotEmpty()) {
            filasEntrada = preprocesado.filas
            usoDbNetFallback = false
        } else {
            filasEntrada = detectarFilasConDbNet(preprocesado.bitmapPreprocesado)
            usoDbNetFallback = true
        }

        val resultados = mutableListOf<ResultadoFilaOcr>()
        val total = filasEntrada.size
        for ((idx, fila) in filasEntrada.withIndex()) {
            onProgresoFila(idx + 1, total)
            val res = reconocerFilaAislada(fila)
            resultados.add(res)
            // Liberar bitmaps temporales de esta fila inmediatamente para mantener baja huella de RAM
            if (!fila.bitmapNormalizado.isRecycled) fila.bitmapNormalizado.recycle()
            if (fila.bitmapAltoContraste !== fila.bitmapNormalizado && !fila.bitmapAltoContraste.isRecycled) {
                fila.bitmapAltoContraste.recycle()
            }
        }

        return ResultadoReconocimientoMotor(
            filas = resultados,
            motorDescripcion = "PaddleOCR latin_PP-OCRv5_mobile + ONNX Runtime" +
                if (preprocesado.usoOpenCv) " + OpenCV" else "",
            usoDeteccionDbNetFallback = usoDbNetFallback
        )
    }

    internal fun reconocerBitmapLineaUnica(bitmap: Bitmap): Pair<String, Float> {
        val ortEnv = env ?: error("OrtEnvironment no inicializado")
        val session = recSession ?: error("RecSession no inicializada")

        val srcW = max(1, bitmap.width)
        val srcH = max(1, bitmap.height)
        val ratio = srcW.toFloat() / srcH.toFloat()
        val resizedW = ceil(REC_IMG_HEIGHT * ratio).toInt().coerceIn(REC_MIN_WIDTH, REC_MAX_WIDTH)
        val paddedW = max(REC_DEFAULT_WIDTH, ((resizedW + 7) / 8) * 8).coerceAtMost(REC_MAX_WIDTH)

        val pixels = leerPixelsEscaladosSeguros(bitmap, resizedW, REC_IMG_HEIGHT)

        val planeSize = REC_IMG_HEIGHT * paddedW
        val floatData = FloatArray(3 * planeSize)
        // Formato BGR en CHW normalizado a [-1, 1] con padding a 0.0f
        for (y in 0 until REC_IMG_HEIGHT) {
            val rowSrc = y * resizedW
            val rowDst = y * paddedW
            for (x in 0 until resizedW) {
                val p = pixels[rowSrc + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val dstIdx = rowDst + x
                floatData[dstIdx] = (b / 255.0f - 0.5f) / 0.5f
                floatData[planeSize + dstIdx] = (g / 255.0f - 0.5f) / 0.5f
                floatData[2 * planeSize + dstIdx] = (r / 255.0f - 0.5f) / 0.5f
            }
        }

        val shape = longArrayOf(1L, 3L, REC_IMG_HEIGHT.toLong(), paddedW.toLong())
        val buffer = FloatBuffer.wrap(floatData)
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(ortEnv, buffer, shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { output ->
                val raw = output[0].value
                return decodificarSalidaCtc(raw)
            }
        }
    }

    private fun reconocerFilaAislada(fila: FilaSegmentadaPlanilla): ResultadoFilaOcr {
        // Pasada 1: Imagen de fila normalizada por iluminación/tinta
        val (txt1, conf1) = runCatching {
            reconocerBitmapLineaUnica(fila.bitmapNormalizado)
        }.getOrDefault("" to 0f)

        var mejorTexto = txt1
        var mejorConf = conf1
        var mejorPuntaje = evaluarCalidadLecturaNombre(txt1, conf1)

        // Pasada 2: Imagen alto contraste (mejora bolígrafo azul tenue o sombras residuales)
        if (conf1 < 0.80f || contarLetras(txt1) < 5) {
            val (txt2, conf2) = runCatching {
                reconocerBitmapLineaUnica(fila.bitmapAltoContraste)
            }.getOrDefault("" to 0f)
            val p2 = evaluarCalidadLecturaNombre(txt2, conf2)
            if (p2 > mejorPuntaje) {
                mejorTexto = txt2
                mejorConf = conf2
                mejorPuntaje = p2
            }
        }

        // Pasada 3: Si aún hay duda o múltiples columnas mezcladas en la fila, aislar palabras con DBNet
        if (mejorConf < 0.72f || contarLetras(mejorTexto) < 4 || tienePosibleColumnaExtra(mejorTexto)) {
            val textoCajas = reconocerSubcajasFilaConDbNet(fila.bitmapNormalizado)
            if (textoCajas != null) {
                val (txt3, conf3) = textoCajas
                val p3 = evaluarCalidadLecturaNombre(txt3, conf3)
                if (p3 > mejorPuntaje) {
                    mejorTexto = txt3
                    mejorConf = conf3
                }
            }
        }

        val limpio = mejorTexto.trim().replace(Regex("\\s+"), " ")
        val legible = contarLetras(limpio) >= 3 && mejorConf >= 0.25f

        return ResultadoFilaOcr(
            numeroFila = fila.numeroFila,
            textoReconocido = limpio,
            confianzaPromedio = mejorConf,
            legible = legible
        )
    }

    private fun reconocerSubcajasFilaConDbNet(rowBitmap: Bitmap): Pair<String, Float>? {
        val cajas = runCatching { detectarCajasTextoDbNet(rowBitmap) }.getOrNull().orEmpty()
        if (cajas.isEmpty()) return null

        val ordenadas = cajas.sortedBy { it.left }
        val partes = mutableListOf<String>()
        var sumConf = 0f
        var cnt = 0

        for ((idx, box) in ordenadas.withIndex()) {
            val crop = recortarSeguro(rowBitmap, box, pad = 4) ?: continue
            val (txt, conf) = runCatching { reconocerBitmapLineaUnica(crop) }.getOrDefault("" to 0f)
            crop.recycle()
            val trimmed = txt.trim()
            if (trimmed.isEmpty()) continue

            // Ignorar caja pequeña extrema izquierda si es solo numeración lateral (ej: "31", "32.")
            val esNumeracionLateral = idx == 0 &&
                box.left < rowBitmap.width * 0.16f &&
                box.width() < rowBitmap.width * 0.14f &&
                trimmed.replace(Regex("[.)\\-:\\s]"), "").all { it.isDigit() }
            if (esNumeracionLateral) continue

            partes.add(trimmed)
            sumConf += conf
            cnt++
        }

        if (partes.isEmpty() || cnt == 0) return null
        return partes.joinToString(" ") to (sumConf / cnt)
    }

    internal fun detectarFilasConDbNet(bitmap: Bitmap): List<FilaSegmentadaPlanilla> {
        val cajas = runCatching { detectarCajasTextoDbNet(bitmap) }.getOrDefault(emptyList())
        if (cajas.isEmpty()) return emptyList()

        // Agrupar cajas en filas horizontales estrictas sin mezclar renglones contiguos
        val ordenadasY = cajas.sortedBy { it.centerY() }
        val grupos = mutableListOf<MutableList<Rect>>()
        for (caja in ordenadasY) {
            val grupo = grupos.lastOrNull { g ->
                val refY = g.map { it.centerY() }.average()
                val refH = g.map { it.height() }.average()
                kotlin.math.abs(caja.centerY() - refY) <= min(caja.height(), refH.roundToInt()) * 0.55
            }
            if (grupo != null) {
                grupo.add(caja)
            } else {
                grupos.add(mutableListOf(caja))
            }
        }

        val filas = mutableListOf<FilaSegmentadaPlanilla>()
        grupos.forEachIndexed { idx, grupo ->
            val ordenX = grupo.sortedBy { it.left }
            val union = Rect(
                ordenX.minOf { it.left },
                ordenX.minOf { it.top },
                ordenX.maxOf { it.right },
                ordenX.maxOf { it.bottom }
            )
            val crop = recortarSeguro(bitmap, union, pad = 10) ?: return@forEachIndexed
            filas.add(
                FilaSegmentadaPlanilla(
                    numeroFila = idx + 1,
                    bitmapNormalizado = crop,
                    bitmapAltoContraste = crop.copy(Bitmap.Config.ARGB_8888, false),
                    cajaOriginal = union
                )
            )
        }
        return filas
    }

    internal fun detectarCajasTextoDbNet(bitmap: Bitmap): List<Rect> {
        val ortEnv = env ?: return emptyList()
        val session = detSession ?: return emptyList()

        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW < 16 || srcH < 16) return emptyList()

        val maxSide = 960.0f
        val scale = min(1.0f, maxSide / max(srcW, srcH).toFloat())
        val targetW = max(32, (((srcW * scale) / 32.0f).roundToInt() * 32))
        val targetH = max(32, (((srcH * scale) / 32.0f).roundToInt() * 32))

        val pixels = leerPixelsEscaladosSeguros(bitmap, targetW, targetH)

        val planeSize = targetW * targetH
        val floatData = FloatArray(3 * planeSize)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        for (i in 0 until planeSize) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f
            floatData[i] = (b - mean[0]) / std[0]
            floatData[planeSize + i] = (g - mean[1]) / std[1]
            floatData[2 * planeSize + i] = (r - mean[2]) / std[2]
        }

        val shape = longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
        val inputName = session.inputNames.first()
        val probMap = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floatData), shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val arr4 = output[0].value as Array<Array<Array<FloatArray>>>
                arr4[0][0]
            }
        }

        // Componentes conexos sobre mapa binario de probabilidad (DB threshold = 0.30)
        val visitado = BooleanArray(targetW * targetH)
        val queue = IntArray(targetW * targetH)
        val invScaleX = srcW.toFloat() / targetW.toFloat()
        val invScaleY = srcH.toFloat() / targetH.toFloat()
        val boxes = mutableListOf<Rect>()

        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val startIdx = y * targetW + x
                if (visitado[startIdx] || probMap[y][x] < DET_PROB_THRESHOLD) continue

                var head = 0
                var tail = 0
                queue[tail++] = startIdx
                visitado[startIdx] = true

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var area = 0
                var sumProb = 0f

                while (head < tail) {
                    val cur = queue[head++]
                    val cx = cur % targetW
                    val cy = cur / targetW
                    area++
                    sumProb += probMap[cy][cx]
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val neighbors = intArrayOf(cx - 1, cy, cx + 1, cy, cx, cy - 1, cx, cy + 1)
                    var k = 0
                    while (k < 8) {
                        val nx = neighbors[k++]
                        val ny = neighbors[k++]
                        if (nx in 0 until targetW && ny in 0 until targetH) {
                            val nIdx = ny * targetW + nx
                            if (!visitado[nIdx] && probMap[ny][nx] >= DET_PROB_THRESHOLD) {
                                visitado[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                val boxW = maxX - minX + 1
                val boxH = maxY - minY + 1
                val meanScore = sumProb / max(1, area)
                if (boxW >= 6 && boxH >= 5 && area >= 20 && meanScore >= 0.42f) {
                    // Expansión controlada tipo DBNet unclip conservando separación vertical
                    val unclipX = (boxH * 0.35f).roundToInt()
                    val unclipY = (boxH * 0.22f).roundToInt()
                    val rx0 = ((minX - unclipX) * invScaleX).roundToInt().coerceIn(0, srcW - 1)
                    val ry0 = ((minY - unclipY) * invScaleY).roundToInt().coerceIn(0, srcH - 1)
                    val rx1 = ((maxX + 1 + unclipX) * invScaleX).roundToInt().coerceIn(rx0 + 1, srcW)
                    val ry1 = ((maxY + 1 + unclipY) * invScaleY).roundToInt().coerceIn(ry0 + 1, srcH)
                    boxes.add(Rect(rx0, ry0, rx1, ry1))
                }
            }
        }

        return boxes
    }

    private fun decodificarSalidaCtc(rawValue: Any?): Pair<String, Float> {
        @Suppress("UNCHECKED_CAST")
        val batch = rawValue as? Array<Array<FloatArray>> ?: return "" to 0f
        val timeSteps = batch.firstOrNull() ?: return "" to 0f

        val sb = StringBuilder()
        var prevIdx = -1
        var sumConf = 0f
        var charCount = 0
        val spaceIndex = dictChars.size + 1

        for (step in timeSteps) {
            if (step.isEmpty()) continue
            var maxIdx = 0
            var maxVal = step[0]
            for (c in 1 until step.size) {
                if (step[c] > maxVal) {
                    maxVal = step[c]
                    maxIdx = c
                }
            }

            if (maxIdx != 0 && maxIdx != prevIdx) {
                // Softmax local aproximada para medir confianza real de este paso
                val prob = calcularProbabilidadPaso(step, maxIdx, maxVal)
                val ch = when {
                    maxIdx in 1..dictChars.size -> dictChars[maxIdx - 1]
                    maxIdx == spaceIndex -> " "
                    else -> ""
                }
                if (ch.isNotEmpty()) {
                    sb.append(ch)
                    sumConf += prob
                    charCount++
                }
            }
            prevIdx = maxIdx
        }

        val avgConf = if (charCount > 0) sumConf / charCount else 0f
        return sb.toString() to avgConf
    }

    private fun calcularProbabilidadPaso(logits: FloatArray, maxIdx: Int, maxVal: Float): Float {
        // Si ya vienen probabilidades [0..1] sumando ~1, devolver directamente; si son logits, aplicar softmax
        var sum = 0.0
        var enRangoProb = true
        for (v in logits) {
            if (v < -0.01f || v > 1.01f) {
                enRangoProb = false
                break
            }
            sum += v
        }
        if (enRangoProb && sum in 0.95..1.05) {
            return logits[maxIdx].coerceIn(0f, 1f)
        }
        var expSum = 0.0
        for (v in logits) {
            val d = (v - maxVal).toDouble()
            if (d > -20.0) {
                expSum += exp(d)
            }
        }
        return if (expSum > 0.0) (1.0 / expSum).toFloat().coerceIn(0f, 1f) else 0f
    }

    private fun evaluarCalidadLecturaNombre(texto: String, conf: Float): Double {
        val limpio = texto.trim()
        val letras = contarLetras(limpio)
        if (letras == 0) return 0.0
        val tokensLetras = limpio.split(Regex("\\s+")).count { tok -> contarLetras(tok) >= 2 }
        return (conf * 0.65) + (min(16, letras) / 16.0 * 0.20) + (min(3, tokensLetras) / 3.0 * 0.15)
    }

    private fun contarLetras(texto: String): Int =
        texto.count { it.isLetter() }

    private fun tienePosibleColumnaExtra(texto: String): Boolean {
        val tokens = texto.trim().split(Regex("\\s+"))
        return tokens.firstOrNull()?.all { it.isDigit() || it in ".)-" } == true && tokens.size >= 2
    }

    private fun leerPixelsEscaladosSeguros(bitmap: Bitmap, dstW: Int, dstH: Int): IntArray {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW == dstW && srcH == dstH) {
            return IntArray(dstW * dstH).also { bitmap.getPixels(it, 0, dstW, 0, 0, dstW, dstH) }
        }
        val escalado = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        val pixels = IntArray(dstW * dstH)
        escalado.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        if (escalado !== bitmap) {
            escalado.recycle()
        }
        if (pixels.any { it != 0 }) {
            return pixels
        }
        // Fallback bilinear cuando el runtime de pruebas JVM no implementa ShadowBitmap.createScaledBitmap
        val srcPixels = IntArray(srcW * srcH)
        bitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        if (srcPixels.none { it != 0 }) return pixels

        val scaleX = srcW.toFloat() / dstW.toFloat()
        val scaleY = srcH.toFloat() / dstH.toFloat()
        for (y in 0 until dstH) {
            val fy = ((y + 0.5f) * scaleY - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(srcH - 1, y0 + 1)
            val wy = fy - y0
            for (x in 0 until dstW) {
                val fx = ((x + 0.5f) * scaleX - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(srcW - 1, x0 + 1)
                val wx = fx - x0
                val p00 = srcPixels[y0 * srcW + x0]
                val p10 = srcPixels[y0 * srcW + x1]
                val p01 = srcPixels[y1 * srcW + x0]
                val p11 = srcPixels[y1 * srcW + x1]
                val r = bilinearChannel(p00, p10, p01, p11, 16, wx, wy)
                val g = bilinearChannel(p00, p10, p01, p11, 8, wx, wy)
                val b = bilinearChannel(p00, p10, p01, p11, 0, wx, wy)
                pixels[y * dstW + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    private fun bilinearChannel(
        p00: Int,
        p10: Int,
        p01: Int,
        p11: Int,
        shift: Int,
        wx: Float,
        wy: Float
    ): Int {
        val c00 = (p00 shr shift) and 0xFF
        val c10 = (p10 shr shift) and 0xFF
        val c01 = (p01 shr shift) and 0xFF
        val c11 = (p11 shr shift) and 0xFF
        val top = c00 + (c10 - c00) * wx
        val bot = c01 + (c11 - c01) * wx
        return (top + (bot - top) * wy).roundToInt().coerceIn(0, 255)
    }

    private fun recortarSeguro(src: Bitmap, rect: Rect, pad: Int): Bitmap? {
        val x0 = (rect.left - pad).coerceIn(0, src.width - 1)
        val y0 = (rect.top - pad).coerceIn(0, src.height - 1)
        val x1 = (rect.right + pad).coerceIn(x0 + 1, src.width)
        val y1 = (rect.bottom + pad).coerceIn(y0 + 1, src.height)
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw < 8 || ch < 8) return null
        val cropPixels = IntArray(cw * ch)
        src.getPixels(cropPixels, 0, cw, x0, y0, cw, ch)
        val dw = cw + 16
        val dh = ch + 16
        val outPixels = IntArray(dw * dh) { Color.WHITE }
        for (y in 0 until ch) {
            System.arraycopy(cropPixels, y * cw, outPixels, (y + 8) * dw + 8, cw)
        }
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, dw, 0, 0, dw, dh)
        return out
    }

    companion object {
        private const val ASSET_DET_MODEL = "ocr/ppocrv5_mobile_det.onnx"
        private const val ASSET_REC_MODEL = "ocr/latin_ppocrv5_mobile_rec.onnx"
        private const val ASSET_LATIN_DICT = "ocr/latin_dict.txt"

        private const val REC_IMG_HEIGHT = 48
        private const val REC_MIN_WIDTH = 48
        private const val REC_DEFAULT_WIDTH = 320
        private const val REC_MAX_WIDTH = 960
        private const val DET_PROB_THRESHOLD = 0.30f

        val instancia: MotorOcrManuscrito by lazy { MotorOcrManuscrito() }

        fun crearParaPruebas(): MotorOcrManuscrito = MotorOcrManuscrito()
    }
}
