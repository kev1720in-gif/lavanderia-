package com.example.lucy.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.data.ocr.OcrImageProcessor
import com.example.data.ocr.PlanillaPreprocessor
import kotlinx.coroutines.CancellationException
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LucyVisionPreprocessedDocument(
    val bitmap: Bitmap,
    val perspectivaCorregida: Boolean,
    val contrasteYNitidezAplicados: Boolean,
    val filasSegmentadasDetectadas: Int,
    val usoOpenCv: Boolean
) {
    val resumenTecnico: String
        get() = buildList {
            if (perspectivaCorregida) add("perspectiva corregida")
            if (contrasteYNitidezAplicados) add("contraste y nitidez realzados")
            if (filasSegmentadasDetectadas > 0) add("$filasSegmentadasDetectadas filas detectadas")
            if (usoOpenCv) add("OpenCV") else add("procesamiento Kotlin")
        }.joinToString(", ")
}

/**
 * Preprocesamiento específico para fotografías reales de planillas enviadas a Lucy Vision:
 * - Decodificación segura respetando orientación EXIF (vía OcrImageProcessor).
 * - Corrección de perspectiva por contorno del documento cuando es aplicable.
 * - Normalización de iluminación/contraste (CLAHE en luminancia Lab preservando colores de tinta)
 *   y realce suave de nitidez (unsharp mask) para distinguir texto impreso, manuscrito y marcas.
 * - Estimación de filas segmentadas vía PlanillaPreprocessor para ayudar a preservar la alineación
 *   fila-columna sin destruir el bitmap completo ni alterar el archivo original ni `ocr_temp`.
 */
object LucyVisionDocumentPreprocessor {

    fun prepararDocumento(
        archivo: File,
        maxDimPx: Int,
        forzarFallbackSinOpenCv: Boolean = false
    ): LucyVisionPreprocessedDocument? {
        val decodificado = OcrImageProcessor.decodificarArchivoSeguro(archivo, maxDimPx)
            ?: return null
        return try {
            prepararBitmap(decodificado, forzarFallbackSinOpenCv = forzarFallbackSinOpenCv)
        } finally {
            if (!decodificado.isRecycled) {
                decodificado.recycle()
            }
        }
    }

    fun prepararBitmap(
        bitmapOriginal: Bitmap,
        forzarFallbackSinOpenCv: Boolean = false
    ): LucyVisionPreprocessedDocument {
        val filasEstimadas = estimarFilasSeguras(bitmapOriginal, forzarFallbackSinOpenCv)

        if (!forzarFallbackSinOpenCv && asegurarOpenCvSinFallo()) {
            val openCvResult = try {
                procesarConOpenCv(bitmapOriginal, filasEstimadas)
            } catch (ce: CancellationException) {
                throw ce
            } catch (vme: VirtualMachineError) {
                throw vme
            } catch (_: Throwable) {
                null
            }
            if (openCvResult != null) {
                return openCvResult
            }
        }

        return procesarFallbackBitmap(bitmapOriginal, filasEstimadas)
    }

    private fun asegurarOpenCvSinFallo(): Boolean {
        return try {
            PlanillaPreprocessor.asegurarOpenCvDisponible()
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: Throwable) {
            false
        }
    }

    private fun estimarFilasSeguras(bitmap: Bitmap, forzarFallback: Boolean): Int {
        return try {
            val res = PlanillaPreprocessor.procesarPlanillaEnFilas(
                bitmapOriginal = bitmap,
                forzarFallbackSinOpenCv = forzarFallback
            )
            val count = res.filas.size
            res.filas.forEach { fila ->
                if (!fila.bitmapNormalizado.isRecycled) fila.bitmapNormalizado.recycle()
                if (!fila.bitmapAltoContraste.isRecycled) fila.bitmapAltoContraste.recycle()
            }
            if (!res.bitmapPreprocesado.isRecycled) {
                res.bitmapPreprocesado.recycle()
            }
            count
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: Throwable) {
            0
        }
    }

    private fun procesarConOpenCv(
        bitmapOriginal: Bitmap,
        filasEstimadas: Int
    ): LucyVisionPreprocessedDocument {
        val rgba = Mat()
        val bgr = Mat()
        val warped = Mat()
        val lab = Mat()
        val enhancedBgr = Mat()
        val blurred = Mat()
        val sharpened = Mat()
        val outRgba = Mat()
        val labChannels = ArrayList<Mat>(3)

        try {
            Utils.bitmapToMat(bitmapOriginal, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            val perspectivaAplicada = corregirPerspectivaDocumento(bgr, warped)

            // Realzar contraste únicamente en luminancia (canal L de Lab) para conservar color de tinta/marcas
            Imgproc.cvtColor(warped, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, labChannels)
            if (labChannels.size == 3) {
                val clahe = Imgproc.createCLAHE(1.8, Size(8.0, 8.0))
                val lEnhanced = Mat()
                try {
                    clahe.apply(labChannels[0], lEnhanced)
                    lEnhanced.copyTo(labChannels[0])
                } finally {
                    lEnhanced.release()
                }
                Core.merge(labChannels, lab)
                Imgproc.cvtColor(lab, enhancedBgr, Imgproc.COLOR_Lab2BGR)
            } else {
                warped.copyTo(enhancedBgr)
            }

            // Realce suave de nitidez (unsharp mask) sin destruir trazos finos manuscritos
            Imgproc.GaussianBlur(enhancedBgr, blurred, Size(0.0, 0.0), 1.1)
            Core.addWeighted(enhancedBgr, 1.35, blurred, -0.35, 0.0, sharpened)

            Imgproc.cvtColor(sharpened, outRgba, Imgproc.COLOR_BGR2RGBA)
            val resultBitmap = Bitmap.createBitmap(
                outRgba.cols(),
                outRgba.rows(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(outRgba, resultBitmap)

            return LucyVisionPreprocessedDocument(
                bitmap = resultBitmap,
                perspectivaCorregida = perspectivaAplicada,
                contrasteYNitidezAplicados = true,
                filasSegmentadasDetectadas = filasEstimadas,
                usoOpenCv = true
            )
        } finally {
            labChannels.forEach { it.release() }
            rgba.release()
            bgr.release()
            warped.release()
            lab.release()
            enhancedBgr.release()
            blurred.release()
            sharpened.release()
            outRgba.release()
        }
    }

    private fun corregirPerspectivaDocumento(srcBgr: Mat, dstBgr: Mat): Boolean {
        val w = srcBgr.cols()
        val h = srcBgr.rows()
        if (w < 160 || h < 160) {
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
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
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

            val areaMinima = smallW * smallH * 0.30
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
            val (tl, tr, br, bl) = ordenarEsquinasQuad(escalados)

            val anchoTop = hypot(tr.x - tl.x, tr.y - tl.y)
            val anchoBottom = hypot(br.x - bl.x, br.y - bl.y)
            val altoLeft = hypot(bl.x - tl.x, bl.y - tl.y)
            val altoRight = hypot(br.x - tr.x, br.y - tr.y)

            val dstW = max(anchoTop, anchoBottom).roundToInt().coerceIn(240, w)
            val dstH = max(altoLeft, altoRight).roundToInt().coerceIn(240, h)

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

    /**
     * Fallback puro en Kotlin sobre Bitmap cuando OpenCV nativo no está disponible:
     * aplica estiramiento suave de contraste por luminancia y un filtro ligero de nitidez
     * conservando colores y dimensiones.
     */
    private fun procesarFallbackBitmap(
        bitmapOriginal: Bitmap,
        filasEstimadas: Int
    ): LucyVisionPreprocessedDocument {
        val w = bitmapOriginal.width
        val h = bitmapOriginal.height
        if (w <= 2 || h <= 2) {
            return LucyVisionPreprocessedDocument(
                bitmap = bitmapOriginal.copy(Bitmap.Config.ARGB_8888, false),
                perspectivaCorregida = false,
                contrasteYNitidezAplicados = false,
                filasSegmentadasDetectadas = filasEstimadas,
                usoOpenCv = false
            )
        }

        val pixels = IntArray(w * h)
        bitmapOriginal.getPixels(pixels, 0, w, 0, 0, w, h)

        var minLuma = 255
        var maxLuma = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = (r * 3 + g * 6 + b) / 10
            if (l < minLuma) minLuma = l
            if (l > maxLuma) maxLuma = l
        }

        val rango = maxLuma - minLuma
        if (rango < 18) {
            // Imagen casi uniforme (p. ej. fondo blanco sintético de prueba): copiar sin distorsionar
            val copia = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(copia).drawBitmap(bitmapOriginal, 0f, 0f, Paint())
            return LucyVisionPreprocessedDocument(
                bitmap = copia,
                perspectivaCorregida = false,
                contrasteYNitidezAplicados = false,
                filasSegmentadasDetectadas = filasEstimadas,
                usoOpenCv = false
            )
        }

        // 1. Estiramiento de contraste suave sobre RGB preservando tonos
        val contrasted = IntArray(pixels.size)
        val low = minLuma
        val span = max(1, rango)
        val necesitaEstirar = low > 12 || maxLuma < 242
        for (i in pixels.indices) {
            val p = pixels[i]
            if (!necesitaEstirar) {
                contrasted[i] = p
            } else {
                val a = (p ushr 24) and 0xFF
                val r = (((((p shr 16) and 0xFF) - low) * 255) / span).coerceIn(0, 255)
                val g = (((((p shr 8) and 0xFF) - low) * 255) / span).coerceIn(0, 255)
                val b = ((((p and 0xFF) - low) * 255) / span).coerceIn(0, 255)
                contrasted[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // 2. Realce ligero de nitidez (laplaciano ponderado 0.25) en pixeles interiores
        val outPixels = contrasted.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = contrasted[idx]
                val up = contrasted[idx - w]
                val down = contrasted[idx + w]
                val left = contrasted[idx - 1]
                val right = contrasted[idx + 1]

                val a = (c ushr 24) and 0xFF
                val rC = (c shr 16) and 0xFF
                val gC = (c shr 8) and 0xFF
                val bC = c and 0xFF

                val rBlur = (((up shr 16) and 0xFF) + ((down shr 16) and 0xFF) + ((left shr 16) and 0xFF) + ((right shr 16) and 0xFF)) / 4
                val gBlur = (((up shr 8) and 0xFF) + ((down shr 8) and 0xFF) + ((left shr 8) and 0xFF) + ((right shr 8) and 0xFF)) / 4
                val bBlur = ((up and 0xFF) + (down and 0xFF) + (left and 0xFF) + (right and 0xFF)) / 4

                val rOut = (rC + ((rC - rBlur) * 3) / 10).coerceIn(0, 255)
                val gOut = (gC + ((gC - gBlur) * 3) / 10).coerceIn(0, 255)
                val bOut = (bC + ((bC - bBlur) * 3) / 10).coerceIn(0, 255)
                outPixels[idx] = Color.argb(a, rOut, gOut, bOut)
            }
        }

        val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(outPixels, 0, w, 0, 0, w, h)
        return LucyVisionPreprocessedDocument(
            bitmap = outBitmap,
            perspectivaCorregida = false,
            contrasteYNitidezAplicados = true,
            filasSegmentadasDetectadas = filasEstimadas,
            usoOpenCv = false
        )
    }
}
