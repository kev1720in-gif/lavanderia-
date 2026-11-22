package com.example.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.domain.model.AjusteFotoPlanilla
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class DetalleOcrPlanilla(
    val filasDetectadas: Int,
    val filasLegibles: Int,
    val filasIlegibles: Int,
    val correccionPerspectivaAplicada: Boolean,
    val usoTablaGrid: Boolean,
    val usoOpenCv: Boolean,
    val columnaNombreRecortada: Boolean,
    val motorDescripcion: String
)

sealed class ResultadoPreparacionTrabajo {
    data class Lista(val archivoTrabajo: File) : ResultadoPreparacionTrabajo()
    data class ErrorCarga(val motivo: String) : ResultadoPreparacionTrabajo()
}

sealed class ResultadoOcrFoto {
    data class Exito(
        val lineasOrdenadas: List<String>,
        val detalle: DetalleOcrPlanilla
    ) : ResultadoOcrFoto()

    data class ErrorCargaArchivo(val motivo: String) : ResultadoOcrFoto()
    data class ErrorDecodificacionImagen(val motivo: String) : ResultadoOcrFoto()
    data class ErrorOcrSinTexto(val motivo: String) : ResultadoOcrFoto()
    data class ErrorProcesamiento(val motivo: String) : ResultadoOcrFoto()

    val mensajeErrorOrNull: String?
        get() = when (this) {
            is Exito -> null
            is ErrorCargaArchivo -> motivo
            is ErrorDecodificacionImagen -> motivo
            is ErrorOcrSinTexto -> motivo
            is ErrorProcesamiento -> motivo
        }
}

/**
 * Orquestador seguro de carga, decodificación, ajuste visual (rotación/recorte), preprocesamiento OpenCV
 * y OCR manuscrito offline con PaddleOCR PP-OCRv5 + ONNX Runtime.
 * - Galería: copia en una sola lectura hacia `cacheDir/ocr_temp/work_*.img` para edición/reprocesado
 *   sin modificar ni eliminar jamás el archivo original del usuario.
 * - Cámara: captura en `cacheDir/ocr_temp/ocr_cam_*.jpg` y lo mueve/conserva como archivo de trabajo
 *   mientras la foto permanezca en el lote activo; se elimina al descartar, reiniciar o limpiar.
 */
object OcrImageProcessor {

    private const val DIR_TEMP = "ocr_temp"
    private const val MAX_DIM_PX = 2400

    fun obtenerDirectorioTemporal(context: Context): File {
        val dir = File(context.cacheDir, DIR_TEMP)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun crearArchivoTemporalCamara(context: Context): Pair<File, Uri> {
        val dir = obtenerDirectorioTemporal(context)
        val archivo = File.createTempFile("ocr_cam_", ".jpg", dir)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            archivo
        )
        return archivo to uri
    }

    fun eliminarArchivoTemporalSiEsInterno(context: Context, file: File?) {
        if (file == null) return
        val tempRoot = runCatching { obtenerDirectorioTemporal(context).canonicalPath }.getOrNull() ?: return
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return
        if (filePath.startsWith(tempRoot) && file.exists()) {
            file.delete()
        }
    }

    fun limpiarTodosLosTemporales(context: Context) {
        val dir = File(context.cacheDir, DIR_TEMP)
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                if (child.isFile) {
                    child.delete()
                }
            }
        }
    }

    fun limpiarTemporalesHuerfanos(
        context: Context,
        rutasActivas: Set<String> = emptySet(),
        edadMinimaBorradoMs: Long = 15 * 60 * 1000L
    ) {
        val dir = File(context.cacheDir, DIR_TEMP)
        if (!dir.exists() || !dir.isDirectory) return
        val ahora = System.currentTimeMillis()
        dir.listFiles()?.forEach { child ->
            if (!child.isFile) return@forEach
            val abs = child.absolutePath
            if (abs in rutasActivas) return@forEach
            if (ahora - child.lastModified() >= edadMinimaBorradoMs) {
                child.delete()
            }
        }
    }

    suspend fun prepararArchivoTrabajo(
        context: Context,
        uri: Uri?,
        archivoTemporalCamara: File? = null
    ): ResultadoPreparacionTrabajo = withContext(Dispatchers.IO) {
        val dir = obtenerDirectorioTemporal(context)
        val destino = File(dir, "work_${UUID.randomUUID()}.img")

        // 1. Si proviene de cámara y el archivo temporal ya existe con bytes válidos, copiarlo/moverlo directo
        if (archivoTemporalCamara != null && archivoTemporalCamara.exists() && archivoTemporalCamara.length() > 0L) {
            val movido = runCatching {
                if (archivoTemporalCamara.renameTo(destino) && destino.exists() && destino.length() > 0L) {
                    true
                } else {
                    archivoTemporalCamara.inputStream().use { input ->
                        destino.outputStream().use { output -> input.copyTo(output) }
                    }
                    eliminarArchivoTemporalSiEsInterno(context, archivoTemporalCamara)
                    destino.exists() && destino.length() > 0L
                }
            }.getOrDefault(false)
            if (movido) {
                return@withContext ResultadoPreparacionTrabajo.Lista(destino)
            }
        }

        if (uri == null) {
            destino.delete()
            return@withContext ResultadoPreparacionTrabajo.ErrorCarga(
                "No se recibió ninguna ruta de fotografía válida."
            )
        }

        // 2. Copiar en UNA sola apertura de ContentResolver hacia almacenamiento temporal privado
        return@withContext try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                destino.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            } ?: run {
                destino.delete()
                return@withContext ResultadoPreparacionTrabajo.ErrorCarga(
                    "Android no otorgó acceso de lectura a la fotografía (stream nulo o permiso expirado)."
                )
            }

            if (bytes <= 0L || !destino.exists() || destino.length() <= 0L) {
                destino.delete()
                ResultadoPreparacionTrabajo.ErrorCarga(
                    "La fotografía está vacía (0 bytes). Es posible que la cámara no terminara de guardarla."
                )
            } else {
                ResultadoPreparacionTrabajo.Lista(destino)
            }
        } catch (se: SecurityException) {
            destino.delete()
            ResultadoPreparacionTrabajo.ErrorCarga(
                "Permiso de lectura denegado para la imagen (${se.message ?: "SecurityException"})."
            )
        } catch (io: IOException) {
            destino.delete()
            ResultadoPreparacionTrabajo.ErrorCarga(
                "Error de lectura al copiar la imagen (${io.message ?: "E/S"})."
            )
        } catch (e: Exception) {
            destino.delete()
            ResultadoPreparacionTrabajo.ErrorCarga(
                "No se pudo cargar el archivo de imagen (${e.message ?: "error desconocido"})."
            )
        } finally {
            if (archivoTemporalCamara != null && archivoTemporalCamara != destino) {
                eliminarArchivoTemporalSiEsInterno(context, archivoTemporalCamara)
            }
        }
    }

    suspend fun cargarBitmapPreviewSeguro(
        archivoTrabajo: File,
        ajuste: AjusteFotoPlanilla = AjusteFotoPlanilla(),
        maxDimPx: Int = 1400
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!archivoTrabajo.exists() || archivoTrabajo.length() <= 0L) return@withContext null
        val decoded = decodificarArchivoSeguro(archivoTrabajo, maxDimPx) ?: return@withContext null
        aplicarRotacionYRecorte(decoded, ajuste, aplicarRecorte = false)
    }

    suspend fun procesarArchivoTrabajo(
        context: Context,
        archivoTrabajo: File,
        ajuste: AjusteFotoPlanilla = AjusteFotoPlanilla(),
        motor: MotorOcrManuscrito = MotorOcrManuscrito.instancia,
        onProgreso: suspend (String) -> Unit = {}
    ): ResultadoOcrFoto = withContext(Dispatchers.IO) {
        if (!archivoTrabajo.exists() || archivoTrabajo.length() <= 0L) {
            return@withContext ResultadoOcrFoto.ErrorCargaArchivo(
                "El archivo de trabajo de la fotografía ya no existe o está vacío. Vuelve a tomar o elegir la foto."
            )
        }

        var baseBitmap: Bitmap? = null
        var adjustedBitmap: Bitmap? = null
        var preprocesado: ResultadoPreprocesamientoPlanilla? = null

        try {
            onProgreso("Decodificando fotografía…")
            baseBitmap = decodificarArchivoSeguro(archivoTrabajo, MAX_DIM_PX)
                ?: return@withContext ResultadoOcrFoto.ErrorDecodificacionImagen(
                    "No se pudo decodificar la imagen (formato no soportado, archivo dañado o memoria insuficiente)."
                )

            adjustedBitmap = aplicarRotacionYRecorte(baseBitmap, ajuste, aplicarRecorte = true)
            if (adjustedBitmap !== baseBitmap) {
                baseBitmap.recycle()
                baseBitmap = null
            }

            onProgreso("Preprocesando perspectiva y segmentando filas…")
            preprocesado = PlanillaPreprocessor.procesarPlanillaEnFilas(adjustedBitmap)

            onProgreso("Iniciando modelo PaddleOCR manuscrito offline…")
            motor.asegurarInicializado(context.applicationContext)

            val resultadoMotor = motor.procesarFilasPlanilla(preprocesado) { filaActual, totalFilas ->
                onProgreso("Reconociendo fila $filaActual de $totalFilas (PaddleOCR PP-OCRv5)…")
            }

            if (resultadoMotor.filas.isEmpty()) {
                return@withContext ResultadoOcrFoto.ErrorOcrSinTexto(
                    "La imagen se cargó correctamente, pero no se detectaron filas ni trazos de texto. Acerca o recorta la planilla e intenta nuevamente."
                )
            }

            val legibles = resultadoMotor.filas.filter { it.legible && it.textoReconocido.isNotBlank() }
            val ilegibles = resultadoMotor.filas.filterNot { it.legible && it.textoReconocido.isNotBlank() }

            if (legibles.isEmpty()) {
                return@withContext ResultadoOcrFoto.ErrorOcrSinTexto(
                    "Se detectaron ${resultadoMotor.filas.size} fila(s), pero ninguna tuvo lectura legible. Prueba rotar/recortar el área de nombres o mejorar la luz."
                )
            }

            // Cada fila legible entra estrictamente como línea separada; filas puntuales ilegibles
            // pasan como aviso pendiente por fila para no perder el resto de la planilla.
            val lineas = buildList {
                for (fila in resultadoMotor.filas) {
                    if (fila.legible && fila.textoReconocido.isNotBlank()) {
                        add(fila.textoReconocido)
                    } else {
                        add("[FILA ${fila.numeroFila} ILEGIBLE]")
                    }
                }
            }

            val detalle = DetalleOcrPlanilla(
                filasDetectadas = resultadoMotor.filas.size,
                filasLegibles = legibles.size,
                filasIlegibles = ilegibles.size,
                correccionPerspectivaAplicada = preprocesado.correccionPerspectivaAplicada,
                usoTablaGrid = preprocesado.usoTablaGrid,
                usoOpenCv = preprocesado.usoOpenCv,
                columnaNombreRecortada = preprocesado.columnaNombreRecortada,
                motorDescripcion = resultadoMotor.motorDescripcion
            )

            ResultadoOcrFoto.Exito(
                lineasOrdenadas = lineas,
                detalle = detalle
            )
        } catch (oom: OutOfMemoryError) {
            ResultadoOcrFoto.ErrorDecodificacionImagen(
                "Memoria insuficiente para procesar la fotografía en alta resolución."
            )
        } catch (e: Exception) {
            ResultadoOcrFoto.ErrorProcesamiento(
                "Error en el motor OCR offline (${e.message ?: "fallo inesperado"})."
            )
        } finally {
            preprocesado?.bitmapPreprocesado?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
            adjustedBitmap?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
            baseBitmap?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    internal fun decodificarArchivoSeguro(archivo: File, maxDimPx: Int): Bitmap? {
        if (!archivo.exists() || archivo.length() <= 0L) return null
        if (!esCabeceraImagenValida(archivo)) return null

        // API 28+: ImageDecoder maneja JPEG/PNG/WEBP/HEIC/AVIF y orientación EXIF automáticamente
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val source = ImageDecoder.createSource(archivo)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val srcMax = max(info.size.width, info.size.height)
                    if (srcMax > maxDimPx && srcMax > 0) {
                        val scale = maxDimPx.toFloat() / srcMax.toFloat()
                        val targetW = max(1, (info.size.width * scale).roundToInt())
                        val targetH = max(1, (info.size.height * scale).roundToInt())
                        decoder.setTargetSize(targetW, targetH)
                    }
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }

        // Fallback API 24-27 o si ImageDecoder falló en algún formato particular
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(archivo.absolutePath, boundsOpts)
        val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight)
        if (maxDim <= 0) return null

        var inSample = 1
        while ((maxDim / inSample) > maxDimPx) {
            inSample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val base = BitmapFactory.decodeFile(archivo.absolutePath, decodeOpts) ?: return null
        val exifGrados = leerRotacionExifArchivo(archivo)
        if (exifGrados == 0) return base

        val matrix = Matrix().apply { postRotate(exifGrados.toFloat()) }
        val rotado = Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
        if (rotado !== base) base.recycle()
        return rotado
    }

    internal fun aplicarRotacionYRecorte(
        src: Bitmap,
        ajuste: AjusteFotoPlanilla,
        aplicarRecorte: Boolean
    ): Bitmap {
        val rot = ((ajuste.rotacionGrados % 360) + 360) % 360
        val rotado = if (rot == 0) {
            src
        } else {
            val matrix = Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }

        if (!aplicarRecorte || !ajuste.tieneRecortePersonalizado) {
            return rotado
        }

        val w = rotado.width
        val h = rotado.height
        val left = (ajuste.cropLeft.coerceIn(0f, 0.9f) * w).roundToInt().coerceIn(0, w - 2)
        val top = (ajuste.cropTop.coerceIn(0f, 0.9f) * h).roundToInt().coerceIn(0, h - 2)
        val right = (ajuste.cropRight.coerceIn(ajuste.cropLeft + 0.08f, 1f) * w).roundToInt().coerceIn(left + 2, w)
        val bottom = (ajuste.cropBottom.coerceIn(ajuste.cropTop + 0.08f, 1f) * h).roundToInt().coerceIn(top + 2, h)

        val recortado = Bitmap.createBitmap(rotado, left, top, right - left, bottom - top)
        if (rotado !== src && rotado !== recortado) {
            rotado.recycle()
        }
        return recortado
    }

    private fun leerRotacionExifArchivo(archivo: File): Int {
        return runCatching {
            val exif = ExifInterface(archivo.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }

    private fun esCabeceraImagenValida(archivo: File): Boolean {
        val header = ByteArray(16)
        val n = runCatching {
            archivo.inputStream().use { it.read(header) }
        }.getOrDefault(0)
        if (n < 6) return false

        // JPEG: FF D8 FF
        if ((header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xFF) == 0xD8 &&
            (header[2].toInt() and 0xFF) == 0xFF
        ) {
            return true
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (n >= 8 &&
            (header[0].toInt() and 0xFF) == 0x89 &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte()
        ) {
            return true
        }
        // WEBP: RIFF....WEBP
        if (n >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()
        ) {
            return true
        }
        // GIF: GIF8
        if (header[0] == 'G'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == '8'.code.toByte()
        ) {
            return true
        }
        // BMP: BM
        if (header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
            return true
        }
        // HEIC / HEIF / AVIF (ISO BMFF): ....ftyp
        if (n >= 12 &&
            header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()
        ) {
            return true
        }
        return false
    }
}
