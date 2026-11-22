package com.example.data.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.core.Rect as CvRect

data class FilaSegmentadaPlanilla(
    val numeroFila: Int,
    val bitmapNormalizado: Bitmap,
    val bitmapAltoContraste: Bitmap,
    val cajaOriginal: Rect
)

data class ResultadoPreprocesamientoPlanilla(
    val filas: List<FilaSegmentadaPlanilla>,
    val bitmapPreprocesado: Bitmap,
    val correccionPerspectivaAplicada: Boolean,
    val usoTablaGrid: Boolean,
    val usoOpenCv: Boolean,
    val columnaNombreRecortada: Boolean
)

/**
 * Preprocesamiento y segmentación masiva fila-por-fila de planillas manuscritas.
 * - Usa OpenCV nativo en dispositivo Android cuando `libopencv_java4.so` está disponible:
 *   1. Corrección de perspectiva por detección del contorno del documento / enderezado.
 *   2. Eliminación de sombras e iluminación irregular + realce de tinta azul/negra (CLAHE + división de fondo).
 *   3. Detección de grilla horizontal/vertical: aisla filas sin borrar trazos cursivos que cruzan líneas,
 *      y recorta automáticamente columna estrecha de numeración lateral si la grilla vertical la separa.
 * - Si OpenCV nativo no está cargado (p. ej. JVM unitaria), aplica equivalente puro sobre Bitmap/Kotlin.
 */
object PlanillaPreprocessor {

    private val openCvInicializado = AtomicBoolean(false)
    private val openCvDisponible = AtomicBoolean(false)

    fun asegurarOpenCvDisponible(): Boolean {
        if (openCvInicializado.get()) {
            return openCvDisponible.get()
        }
        synchronized(this) {
            if (!openCvInicializado.get()) {
                val ok = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
                openCvDisponible.set(ok)
                openCvInicializado.set(true)
            }
        }
        return openCvDisponible.get()
    }

    fun procesarPlanillaEnFilas(
        bitmapOriginal: Bitmap,
        forzarFallbackSinOpenCv: Boolean = false
    ): ResultadoPreprocesamientoPlanilla {
        if (!forzarFallbackSinOpenCv && asegurarOpenCvDisponible()) {
            val resultadoOpenCv = runCatching {
                procesarConOpenCv(bitmapOriginal)
            }.getOrNull()
            if (resultadoOpenCv != null) {
                return resultadoOpenCv
            }
        }
        return procesarFallbackBitmap(bitmapOriginal)
    }

    private fun procesarConOpenCv(bitmapOriginal: Bitmap): ResultadoPreprocesamientoPlanilla {
        val rgba = Mat()
        val bgr = Mat()
        val warped = Mat()
        val enhancedGray = Mat()
        val binaryInv = Mat()
        val noGridGray = Mat()

        try {
            Utils.bitmapToMat(bitmapOriginal, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            // 1. Corrección automática de perspectiva si existe contorno claro de planilla
            val perspectivaAplicada = corregirPerspectivaSiAplica(bgr, warped)

            // 2. Realce de tinta azul/negra y normalización de iluminación (elimina sombras/reflejos)
            normalizarIluminacionYRealzarTinta(warped, enhancedGray)

            // 3. Umbral adaptativo invertido para detectar grilla y densidad de trazos
            val blockSize = (min(enhancedGray.cols(), enhancedGray.rows()) / 38)
                .coerceIn(15, 45)
                .let { if (it % 2 == 0) it + 1 else it }
            Imgproc.adaptiveThreshold(
                enhancedGray,
                binaryInv,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                blockSize,
                11.0
            )

            // 4. Detectar estructura de tabla (líneas horizontales y verticales)
            val (lineasH, lineasV, maskLineas) = detectarGrillaTabla(binaryInv)
            try {
                // Limpiar grilla en enhancedGray solo donde hay línea sin borrar trazos gruesos de letras
                enhancedGray.copyTo(noGridGray)
                limpiarGrillaConservandoTrazos(noGridGray, maskLineas)

                // Determinar columna de nombres (excluyendo numeración lateral estrecha y columnas derechas si hay grilla)
                val (colX0, colX1, columnaRecortada) = estimarRangoColumnaNombres(
                    width = noGridGray.cols(),
                    height = noGridGray.rows(),
                    lineasV = lineasV,
                    binaryInv = binaryInv
                )

                val subBinary = binaryInv.submat(0, binaryInv.rows(), colX0, colX1)
                val subGray = noGridGray.submat(0, noGridGray.rows(), colX0, colX1)
                val subColor = warped.submat(0, warped.rows(), colX0, colX1)
                try {
                    val bandasFilas = calcularBandasFilas(
                        lineasH = lineasH,
                        binaryInv = subBinary
                    )

                    val filas = mutableListOf<FilaSegmentadaPlanilla>()
                    var numFila = 0
                    for ((yTop, yBottom) in bandasFilas) {
                        val h = yBottom - yTop
                        if (h < 14) continue

                        val rowBin = subBinary.submat(yTop, yBottom, 0, subBinary.cols())
                        val nonZero = Core.countNonZero(rowBin)
                        val area = max(1.0, (rowBin.rows() * rowBin.cols()).toDouble())
                        val densidadTinta = nonZero.toDouble() / area
                        rowBin.release()

                        // Omitir únicamente filas realmente vacías o de ruido mínimo
                        if (nonZero < 25 || densidadTinta < 0.0008) continue

                        val rowGray = subGray.submat(yTop, yBottom, 0, subGray.cols())
                        val rowColor = subColor.submat(yTop, yBottom, 0, subColor.cols())
                        try {
                            val bmpNorm = construirBitmapFilaConMargen(rowColor)
                            val bmpContraste = construirBitmapAltoContraste(rowGray)
                            numFila++
                            filas.add(
                                FilaSegmentadaPlanilla(
                                    numeroFila = numFila,
                                    bitmapNormalizado = bmpNorm,
                                    bitmapAltoContraste = bmpContraste,
                                    cajaOriginal = Rect(colX0, yTop, colX1, yBottom)
                                )
                            )
                        } finally {
                            rowGray.release()
                            rowColor.release()
                        }
                    }

                    val previewBmp = Bitmap.createBitmap(
                        warped.cols(),
                        warped.rows(),
                        Bitmap.Config.ARGB_8888
                    )
                    val previewRgba = Mat()
                    try {
                        Imgproc.cvtColor(warped, previewRgba, Imgproc.COLOR_BGR2RGBA)
                        Utils.matToBitmap(previewRgba, previewBmp)
                    } finally {
                        previewRgba.release()
                    }

                    return ResultadoPreprocesamientoPlanilla(
                        filas = filas,
                        bitmapPreprocesado = previewBmp,
                        correccionPerspectivaAplicada = perspectivaAplicada,
                        usoTablaGrid = lineasH.size >= 3,
                        usoOpenCv = true,
                        columnaNombreRecortada = columnaRecortada
                    )
                } finally {
                    subBinary.release()
                    subGray.release()
                    subColor.release()
                }
            } finally {
                maskLineas.release()
            }
        } finally {
            rgba.release()
            bgr.release()
            warped.release()
            enhancedGray.release()
            binaryInv.release()
            noGridGray.release()
        }
    }

    private fun corregirPerspectivaSiAplica(srcBgr: Mat, dstBgr: Mat): Boolean {
        val w = srcBgr.cols()
        val h = srcBgr.rows()
        if (w < 120 || h < 120) {
            srcBgr.copyTo(dstBgr)
            return false
        }

        val scale = 800.0 / max(w, h).toDouble()
        val smallW = max(64, (w * scale).roundToInt())
        val smallH = max(64, (h * scale).roundToInt())

        val small = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val dilated = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        try {
            Imgproc.resize(srcBgr, small, Size(smallW.toDouble(), smallH.toDouble()))
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 55.0, 155.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, dilated, kernel)
            kernel.release()

            Imgproc.findContours(
                dilated,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val areaMinima = smallW * smallH * 0.28
            val areaMaxima = smallW * smallH * 0.96
            var mejorQuad: Array<Point>? = null
            var mejorArea = 0.0

            for (c in contours) {
                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
                val pts = approx.toArray()
                val area = Imgproc.contourArea(approx)
                c2f.release()
                approx.release()

                if (pts.size == 4 && area in areaMinima..areaMaxima && area > mejorArea) {
                    mejorArea = area
                    mejorQuad = pts
                }
            }

            if (mejorQuad == null) {
                srcBgr.copyTo(dstBgr)
                return false
            }

            val invScale = 1.0 / scale
            val escalados = mejorQuad.map { Point(it.x * invScale, it.y * invScale) }
            val ordenados = ordenarEsquinasQuad(escalados)
            val (tl, tr, br, bl) = ordenados

            val anchoTop = hypot(tr.x - tl.x, tr.y - tl.y)
            val anchoBottom = hypot(br.x - bl.x, br.y - bl.y)
            val altoLeft = hypot(bl.x - tl.x, bl.y - tl.y)
            val altoRight = hypot(br.x - tr.x, br.y - tr.y)

            val dstW = max(anchoTop, anchoBottom).roundToInt().coerceIn(240, w)
            val dstH = max(altoLeft, altoRight).roundToInt().coerceIn(240, h)

            // Evitar deformación si el cuadrilátero es casi idéntico al marco completo sin perspectiva
            val yaAlineado = abs(anchoTop - anchoBottom) < w * 0.025 &&
                abs(altoLeft - altoRight) < h * 0.025 &&
                tl.x < w * 0.04 && tl.y < h * 0.04
            if (yaAlineado) {
                srcBgr.copyTo(dstBgr)
                return false
            }

            val srcPts = MatOfPoint2f(tl, tr, br, bl)
            val dstPts = MatOfPoint2f(
                Point(0.0, 0.0),
                Point((dstW - 1).toDouble(), 0.0),
                Point((dstW - 1).toDouble(), (dstH - 1).toDouble()),
                Point(0.0, (dstH - 1).toDouble())
            )
            val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            try {
                Imgproc.warpPerspective(
                    srcBgr,
                    dstBgr,
                    transform,
                    Size(dstW.toDouble(), dstH.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE,
                    Scalar(255.0, 255.0, 255.0)
                )
            } finally {
                srcPts.release()
                dstPts.release()
                transform.release()
            }
            return true
        } finally {
            small.release()
            gray.release()
            blurred.release()
            edges.release()
            dilated.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun ordenarEsquinasQuad(pts: List<Point>): List<Point> {
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.minBy { it.y - it.x }
        val bl = pts.maxBy { it.y - it.x }
        return listOf(tl, tr, br, bl)
    }

    private fun normalizarIluminacionYRealzarTinta(srcBgr: Mat, dstGray: Mat) {
        val channels = ArrayList<Mat>(3)
        val gray = Mat()
        val inkBoost = Mat()
        val bg = Mat()
        val norm = Mat()
        val claheDst = Mat()

        try {
            Core.split(srcBgr, channels)
            // Realzar tinta azul/negra combinando canal rojo (contrasta tinta azul) con luminancia gris
            Imgproc.cvtColor(srcBgr, gray, Imgproc.COLOR_BGR2GRAY)
            if (channels.size == 3) {
                // channels[2] = Red en BGR -> la tinta azul absorbe rojo, mejorando contraste sobre papel
                Core.addWeighted(gray, 0.60, channels[2], 0.40, 0.0, inkBoost)
            } else {
                gray.copyTo(inkBoost)
            }

            // Estimar fondo iluminado suave para eliminar sombras y gradientes de luz
            val kernelDim = (min(srcBgr.cols(), srcBgr.rows()) / 24).coerceIn(21, 51)
                .let { if (it % 2 == 0) it + 1 else it }
            val morphKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(kernelDim.toDouble(), kernelDim.toDouble())
            )
            Imgproc.morphologyEx(inkBoost, bg, Imgproc.MORPH_CLOSE, morphKernel)
            morphKernel.release()

            val fInk = Mat()
            val fBg = Mat()
            val fDiv = Mat()
            try {
                inkBoost.convertTo(fInk, CvType.CV_32F)
                bg.convertTo(fBg, CvType.CV_32F)
                Core.max(fBg, Scalar(16.0), fBg)
                Core.divide(fInk, fBg, fDiv, 255.0)
                Core.min(fDiv, Scalar(255.0), fDiv)
                Core.max(fDiv, Scalar(0.0), fDiv)
                fDiv.convertTo(norm, CvType.CV_8UC1)
            } finally {
                fInk.release()
                fBg.release()
                fDiv.release()
            }

            val clahe = Imgproc.createCLAHE(2.1, Size(8.0, 8.0))
            clahe.apply(norm, claheDst)
            claheDst.copyTo(dstGray)
        } finally {
            channels.forEach { it.release() }
            gray.release()
            inkBoost.release()
            bg.release()
            norm.release()
            claheDst.release()
        }
    }

    private data class ResultadoGrilla(
        val lineasH: List<Int>,
        val lineasV: List<Int>,
        val maskLineas: Mat
    )

    private fun detectarGrillaTabla(binaryInv: Mat): ResultadoGrilla {
        val w = binaryInv.cols()
        val h = binaryInv.rows()

        val horizLen = max(24, w / 14)
        val vertLen = max(20, h / 14)

        val horizKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(horizLen.toDouble(), 1.0))
        val vertKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, vertLen.toDouble()))

        val horizMat = Mat()
        val vertMat = Mat()
        val maskCombined = Mat()

        try {
            Imgproc.morphologyEx(binaryInv, horizMat, Imgproc.MORPH_OPEN, horizKernel)
            Imgproc.morphologyEx(binaryInv, vertMat, Imgproc.MORPH_OPEN, vertKernel)

            val lineasH = extraerPosicionesLineasHorizontales(horizMat, minAncho = (w * 0.30).roundToInt())
            val lineasV = extraerPosicionesLineasVerticales(vertMat, minAlto = (h * 0.25).roundToInt())

            Core.bitwise_or(horizMat, vertMat, maskCombined)
            val dilKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val maskDilated = Mat()
            Imgproc.dilate(maskCombined, maskDilated, dilKernel)
            dilKernel.release()

            return ResultadoGrilla(
                lineasH = lineasH,
                lineasV = lineasV,
                maskLineas = maskDilated
            )
        } finally {
            horizKernel.release()
            vertKernel.release()
            horizMat.release()
            vertMat.release()
            maskCombined.release()
        }
    }

    private fun extraerPosicionesLineasHorizontales(horizMat: Mat, minAncho: Int): List<Int> {
        val rowSums = Mat()
        try {
            Core.reduce(horizMat, rowSums, 1, Core.REDUCE_SUM, CvType.CV_32SC1)
            val nRows = rowSums.rows()
            val data = IntArray(nRows)
            rowSums.get(0, 0, data)

            val candidatas = mutableListOf<Int>()
            val threshold = minAncho * 255
            for (y in 0 until nRows) {
                if (data[y] >= threshold) {
                    candidatas.add(y)
                }
            }
            return agruparCoordenadasCercanas(candidatas, toleranciaPx = 12)
        } finally {
            rowSums.release()
        }
    }

    private fun extraerPosicionesLineasVerticales(vertMat: Mat, minAlto: Int): List<Int> {
        val colSums = Mat()
        try {
            Core.reduce(vertMat, colSums, 0, Core.REDUCE_SUM, CvType.CV_32SC1)
            val nCols = colSums.cols()
            val data = IntArray(nCols)
            colSums.get(0, 0, data)

            val candidatas = mutableListOf<Int>()
            val threshold = minAlto * 255
            for (x in 0 until nCols) {
                if (data[x] >= threshold) {
                    candidatas.add(x)
                }
            }
            return agruparCoordenadasCercanas(candidatas, toleranciaPx = 14)
        } finally {
            colSums.release()
        }
    }

    private fun limpiarGrillaConservandoTrazos(grayMat: Mat, maskLineas: Mat) {
        // Reemplaza únicamente píxeles de la grilla con blanco suave (248) sin dilatar hacia trazos adyacentes
        grayMat.setTo(Scalar(248.0), maskLineas)
    }

    private fun estimarRangoColumnaNombres(
        width: Int,
        height: Int,
        lineasV: List<Int>,
        binaryInv: Mat
    ): Triple<Int, Int, Boolean> {
        // Caso 1: La grilla vertical detectada divide claramente columnas (p. ej. [Nº | Nombre | Hab | Pab])
        if (lineasV.size >= 2) {
            val bordes = buildList {
                if (lineasV.first() > width * 0.04) add(0)
                addAll(lineasV)
                if (width - lineasV.last() > width * 0.04) add(width)
            }
            val columnas = (0 until bordes.size - 1).map { i -> bordes[i] to bordes[i + 1] }
                .filter { (x0, x1) -> (x1 - x0) > width * 0.03 }

            if (columnas.size >= 2) {
                val primera = columnas.first()
                val anchoPrimera = primera.second - primera.first
                val candidatos = if (anchoPrimera < width * 0.14 && columnas.size >= 2) {
                    // Descarta columna estrecha de numeración lateral izquierda (ej: 1..30, 31..60)
                    columnas.drop(1)
                } else {
                    columnas
                }
                // Elegir la columna principal más ancha (habitualmente la columna de Apellidos/Nombres)
                val principal = candidatos.maxByOrNull { (x0, x1) -> x1 - x0 }
                if (principal != null && (principal.second - principal.first) >= width * 0.22) {
                    val x0 = (principal.first + 3).coerceIn(0, width - 20)
                    val x1 = (principal.second - 3).coerceIn(x0 + 20, width)
                    return Triple(x0, x1, x0 > width * 0.04 || x1 < width * 0.95)
                }
            }
        }

        // Caso 2: Sin grilla vertical clara, verificar si hay margen izquierdo estrecho (numeración lateral)
        // separado por un canal vertical en blanco dentro del primer 16% del ancho.
        val colSums = Mat()
        try {
            val topY = (height * 0.10).roundToInt()
            val botY = (height * 0.90).roundToInt().coerceAtLeast(topY + 10)
            val band = binaryInv.submat(topY, botY, 0, min(width, (width * 0.22).roundToInt()))
            Core.reduce(band, colSums, 0, Core.REDUCE_SUM, CvType.CV_32SC1)
            band.release()

            val nCols = colSums.cols()
            val data = IntArray(nCols)
            colSums.get(0, 0, data)
            val maxCol = data.maxOrNull() ?: 0
            if (maxCol > 0) {
                var tintaIzqVista = false
                var anchoBlancoSeguido = 0
                var finGutter = -1
                val umbralBlanco = (maxCol * 0.04).roundToInt()
                val inicioBusqueda = (width * 0.03).roundToInt()
                for (x in 0 until nCols) {
                    if (data[x] > umbralBlanco * 3) {
                        tintaIzqVista = true
                        anchoBlancoSeguido = 0
                    } else if (tintaIzqVista && x >= inicioBusqueda && data[x] <= umbralBlanco) {
                        anchoBlancoSeguido++
                        if (anchoBlancoSeguido >= max(6, (width * 0.015).roundToInt())) {
                            finGutter = x
                        }
                    } else if (tintaIzqVista && finGutter > 0) {
                        break
                    }
                }
                if (finGutter in (width * 0.04).roundToInt()..(width * 0.16).roundToInt()) {
                    return Triple(finGutter, width, true)
                }
            }
        } finally {
            colSums.release()
        }

        return Triple(0, width, false)
    }

    private fun calcularBandasFilas(
        lineasH: List<Int>,
        binaryInv: Mat
    ): List<Pair<Int, Int>> {
        val h = binaryInv.rows()
        if (lineasH.size >= 3) {
            val bordes = mutableListOf<Int>()
            if (lineasH.first() > 18) bordes.add(0)
            bordes.addAll(lineasH)
            if (h - lineasH.last() > 18) bordes.add(h)

            return (0 until bordes.size - 1).mapNotNull { i ->
                val y0 = (bordes[i] + 2).coerceIn(0, h)
                val y1 = (bordes[i + 1] - 2).coerceIn(0, h)
                if (y1 - y0 >= 14) y0 to y1 else null
            }
        }

        // Proyección horizontal cuando no hay grilla de líneas horizontales suficientes
        val rowSums = Mat()
        try {
            Core.reduce(binaryInv, rowSums, 1, Core.REDUCE_SUM, CvType.CV_32SC1)
            val data = IntArray(h)
            rowSums.get(0, 0, data)
            return segmentarPorProyeccionHorizontal(data, h)
        } finally {
            rowSums.release()
        }
    }

    private fun construirBitmapFilaConMargen(rowMatBgr: Mat): Bitmap {
        val h = rowMatBgr.rows()
        val w = rowMatBgr.cols()
        val padY = max(10, (h * 0.18).roundToInt())
        val padX = 14
        val padded = Mat()
        val rgba = Mat()
        try {
            Core.copyMakeBorder(
                rowMatBgr,
                padded,
                padY,
                padY,
                padX,
                padX,
                Core.BORDER_CONSTANT,
                Scalar(252.0, 252.0, 252.0)
            )
            Imgproc.cvtColor(padded, rgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            return bmp
        } finally {
            padded.release()
            rgba.release()
        }
    }

    private fun construirBitmapAltoContraste(rowGray: Mat): Bitmap {
        val h = rowGray.rows()
        val padY = max(10, (h * 0.18).roundToInt())
        val padX = 14
        val padded = Mat()
        val norm = Mat()
        val rgba = Mat()
        try {
            // Re-normalizar contraste local dentro de la fila manteniendo suavidad para SVTR/CTC
            Core.normalize(rowGray, norm, 0.0, 255.0, Core.NORM_MINMAX)
            Core.copyMakeBorder(
                norm,
                padded,
                padY,
                padY,
                padX,
                padX,
                Core.BORDER_CONSTANT,
                Scalar(255.0)
            )
            Imgproc.cvtColor(padded, rgba, Imgproc.COLOR_GRAY2RGBA)
            val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            return bmp
        } finally {
            padded.release()
            norm.release()
            rgba.release()
        }
    }

    /**
     * Fallback puro en Kotlin/Bitmap (utilizado si la librería nativa de OpenCV no puede cargarse
     * en pruebas unitarias JVM o arquitecturas atípicas). Preserva el aislamiento estricto de filas.
     */
    private fun procesarFallbackBitmap(bitmapOriginal: Bitmap): ResultadoPreprocesamientoPlanilla {
        val w = bitmapOriginal.width
        val h = bitmapOriginal.height
        val pixels = IntArray(w * h)
        bitmapOriginal.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Enfatizar tinta azul/negra combinando gris con canal rojo
            luma[i] = ((r * 4 + g * 4 + b * 2) / 10).coerceIn(0, 255)
        }

        // Estimar umbral global adaptativo tipo percentiles simples
        val rowDensity = IntArray(h)
        val horizLineCounts = IntArray(h)
        for (y in 0 until h) {
            val offset = y * w
            var filaMin = 255
            var filaMax = 0
            for (x in 0 until w) {
                val v = luma[offset + x]
                if (v < filaMin) filaMin = v
                if (v > filaMax) filaMax = v
            }
            val oscuros = if (filaMax - filaMin < 18) {
                0
            } else {
                val umbralLocal = (filaMax - (filaMax - filaMin) * 0.28).roundToInt().coerceIn(70, 220)
                var cnt = 0
                for (x in 0 until w) {
                    if (luma[offset + x] < umbralLocal) cnt++
                }
                cnt
            }
            rowDensity[y] = oscuros
            if (oscuros >= (w * 0.55).roundToInt()) {
                horizLineCounts[y] = 1
            }
        }

        val lineasH = agruparCoordenadasCercanas(
            horizLineCounts.indices.filter { horizLineCounts[it] == 1 },
            toleranciaPx = 10
        )

        val bandas = if (lineasH.size >= 3) {
            val bordes = buildList {
                if (lineasH.first() > 18) add(0)
                addAll(lineasH)
                if (h - lineasH.last() > 18) add(h)
            }
            (0 until bordes.size - 1).mapNotNull { idx ->
                val y0 = (bordes[idx] + 2).coerceIn(0, h)
                val y1 = (bordes[idx + 1] - 2).coerceIn(0, h)
                if (y1 - y0 >= 14) y0 to y1 else null
            }
        } else {
            val cleaned = IntArray(h) { y -> if (horizLineCounts[y] == 1) 0 else rowDensity[y] }
            segmentarPorProyeccionHorizontal(cleaned, h)
        }

        // Detectar separadores verticales (columna de numeración lateral izquierda y/o columna derecha vacía)
        val colInk = IntArray(w)
        val vertLineCandidates = mutableListOf<Int>()
        for (x in 0 until w) {
            var oscurosCol = 0
            for (y in 0 until h) {
                val p = luma[y * w + x]
                if (p < 190) oscurosCol++
            }
            colInk[x] = oscurosCol
            if (oscurosCol >= (h * 0.35).roundToInt()) {
                vertLineCandidates.add(x)
            }
        }
        val lineasV = agruparCoordenadasCercanas(vertLineCandidates, toleranciaPx = 12)
        var colX0 = 0
        var colX1 = w
        if (lineasV.size >= 2) {
            val bordesV = buildList {
                if (lineasV.first() > w * 0.04) add(0)
                addAll(lineasV)
                if (w - lineasV.last() > w * 0.04) add(w)
            }
            val cols = (0 until bordesV.size - 1).map { bordesV[it] to bordesV[it + 1] }
                .filter { (a, b) -> (b - a) > w * 0.03 }
            if (cols.size >= 2) {
                val cands = if ((cols.first().second - cols.first().first) < w * 0.14) cols.drop(1) else cols
                val principal = cands.maxByOrNull { (a, b) -> b - a }
                if (principal != null && (principal.second - principal.first) >= w * 0.22) {
                    colX0 = (principal.first + 3).coerceIn(0, w - 20)
                    colX1 = (principal.second - 3).coerceIn(colX0 + 20, w)
                }
            }
        } else if (lineasV.size == 1 && lineasV.first() in (w * 0.04).roundToInt()..(w * 0.18).roundToInt()) {
            colX0 = (lineasV.first() + 3).coerceIn(0, w - 20)
        }

        val cropW = (colX1 - colX0).coerceAtLeast(1)
        val filas = mutableListOf<FilaSegmentadaPlanilla>()
        var idxFila = 0
        for ((yTop, yBottom) in bandas) {
            val rowH = yBottom - yTop
            if (rowH < 14) continue
            var inkPixels = 0
            for (y in yTop until yBottom) {
                val rowOff = y * w
                var filaMin = 255
                var filaMax = 0
                for (x in colX0 until colX1) {
                    val v = luma[rowOff + x]
                    if (v < filaMin) filaMin = v
                    if (v > filaMax) filaMax = v
                }
                val umbralCol = (filaMax - (filaMax - filaMin) * 0.28).roundToInt().coerceIn(70, 220)
                for (x in colX0 until colX1) {
                    if (luma[rowOff + x] < umbralCol) inkPixels++
                }
            }
            val avgInk = inkPixels.toDouble() / max(1, cropW * rowH)
            if (inkPixels < 25 || avgInk < 0.0008) continue

            val crop = Bitmap.createBitmap(bitmapOriginal, colX0, yTop, cropW, rowH)
            val padded = añadirMargenBlanco(crop, padX = 14, padY = max(10, (rowH * 0.18).roundToInt()))
            crop.recycle()
            idxFila++
            filas.add(
                FilaSegmentadaPlanilla(
                    numeroFila = idxFila,
                    bitmapNormalizado = padded,
                    bitmapAltoContraste = padded.copy(Bitmap.Config.ARGB_8888, false),
                    cajaOriginal = Rect(colX0, yTop, colX1, yBottom)
                )
            )
        }

        return ResultadoPreprocesamientoPlanilla(
            filas = filas,
            bitmapPreprocesado = bitmapOriginal.copy(Bitmap.Config.ARGB_8888, false),
            correccionPerspectivaAplicada = false,
            usoTablaGrid = lineasH.size >= 3,
            usoOpenCv = false,
            columnaNombreRecortada = false
        )
    }

    private fun añadirMargenBlanco(src: Bitmap, padX: Int, padY: Int): Bitmap {
        val sw = src.width
        val sh = src.height
        val dw = sw + padX * 2
        val dh = sh + padY * 2
        val srcPixels = IntArray(sw * sh)
        src.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)
        val outPixels = IntArray(dw * dh) { Color.WHITE }
        for (y in 0 until sh) {
            System.arraycopy(srcPixels, y * sw, outPixels, (y + padY) * dw + padX, sw)
        }
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, dw, 0, 0, dw, dh)
        return out
    }

    internal fun segmentarPorProyeccionHorizontal(
        perfil: IntArray,
        altoTotal: Int
    ): List<Pair<Int, Int>> {
        if (altoTotal <= 0 || perfil.isEmpty()) return emptyList()

        // Suavizado con ventana de 5 px para evitar micro-cortes dentro de una misma línea manuscrita
        val suavizado = DoubleArray(altoTotal)
        for (y in 0 until altoTotal) {
            var sum = 0.0
            var cnt = 0
            for (k in max(0, y - 2)..min(altoTotal - 1, y + 2)) {
                sum += perfil[k]
                cnt++
            }
            suavizado[y] = sum / cnt
        }

        val picoMax = suavizado.maxOrNull() ?: 0.0
        if (picoMax <= 0.0) return emptyList()

        val umbralActividad = picoMax * 0.10
        val bandasCrudas = mutableListOf<Pair<Int, Int>>()
        var enBanda = false
        var inicio = 0

        for (y in 0 until altoTotal) {
            if (suavizado[y] > umbralActividad) {
                if (!enBanda) {
                    enBanda = true
                    inicio = y
                }
            } else if (enBanda) {
                if (y - inicio >= 10) {
                    bandasCrudas.add(inicio to y)
                }
                enBanda = false
            }
        }
        if (enBanda && altoTotal - inicio >= 10) {
            bandasCrudas.add(inicio to altoTotal)
        }

        // Fusionar únicamente separaciones muy pequeñas (< 5 px) provocadas por acentos/tildes flotantes,
        // sin fusionar nunca dos filas reales separadas.
        val fusionadas = mutableListOf<Pair<Int, Int>>()
        for (banda in bandasCrudas) {
            val prev = fusionadas.lastOrNull()
            if (prev != null && banda.first - prev.second <= 4) {
                fusionadas[fusionadas.lastIndex] = prev.first to banda.second
            } else {
                fusionadas.add(banda)
            }
        }

        // Agregar margen acotado al punto medio con la fila anterior/siguiente (nunca invade la otra fila)
        return fusionadas.mapIndexed { idx, (y0, y1) ->
            val limiteArriba = if (idx == 0) 0 else (fusionadas[idx - 1].second + y0) / 2
            val limiteAbajo = if (idx == fusionadas.lastIndex) altoTotal else (y1 + fusionadas[idx + 1].first) / 2
            val topPad = max(limiteArriba, y0 - 4)
            val botPad = min(limiteAbajo, y1 + 4)
            topPad to botPad
        }
    }

    private fun agruparCoordenadasCercanas(coords: List<Int>, toleranciaPx: Int): List<Int> {
        if (coords.isEmpty()) return emptyList()
        val ordenadas = coords.sorted()
        val grupos = mutableListOf<Int>()
        var sum = ordenadas.first()
        var cnt = 1
        var prev = ordenadas.first()

        for (i in 1 until ordenadas.size) {
            val v = ordenadas[i]
            if (v - prev <= toleranciaPx) {
                sum += v
                cnt++
            } else {
                grupos.add((sum.toDouble() / cnt).roundToInt())
                sum = v
                cnt = 1
            }
            prev = v
        }
        grupos.add((sum.toDouble() / cnt).roundToInt())
        return grupos
    }
}
