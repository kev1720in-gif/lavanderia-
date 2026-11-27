package com.example.lucy.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.data.ocr.OcrImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface LucyPhotoIntakeResult {
    data class Validated(val preview: LucySelectedPhotoPreview) : LucyPhotoIntakeResult
    data class Invalid(val reason: String) : LucyPhotoIntakeResult
}

data class LucySelectedPhotoPreview(
    val tempFilePath: String,
    val displayName: String,
    val rawSizeBytes: Long,
    val widthPx: Int,
    val heightPx: Int,
    val previewBitmap: Bitmap? = null,
    val requiresInternet: Boolean = true,
    val networkAvailable: Boolean = false
) {
    val sizeLabel: String
        get() = when {
            rawSizeBytes < 1024L -> "$rawSizeBytes B"
            rawSizeBytes < 1024L * 1024L -> "${rawSizeBytes / 1024L} KB"
            else -> String.format("%.1f MB", rawSizeBytes / (1024f * 1024f))
        }
}

/**
 * Validación, copia segura a caché privada aislada y vista previa de fotografías para Lucy Vision.
 * Nunca modifica el archivo original del usuario ni escribe en el directorio de OCR offline (`ocr_temp`).
 */
object LucyPhotoIntake {

    private const val DIR_LUCY_TEMP = "lucy_vision_temp"
    private const val MAX_RAW_FILE_BYTES = 20L * 1024L * 1024L
    private const val PREVIEW_MAX_DIM_PX = 1200

    fun getTempDirectory(context: Context): File {
        val dir = File(context.applicationContext.cacheDir, DIR_LUCY_TEMP)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun cleanupOrphanedFiles(
        context: Context,
        activePaths: Set<String> = emptySet(),
        minAgeMs: Long = 15 * 60 * 1000L
    ) {
        val dir = File(context.applicationContext.cacheDir, DIR_LUCY_TEMP)
        if (!dir.exists() || !dir.isDirectory) return
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { child ->
            if (!child.isFile) return@forEach
            if (child.absolutePath in activePaths) return@forEach
            if (now - child.lastModified() >= minAgeMs) {
                child.delete()
            }
        }
    }

    fun deleteTempFileIfOwned(context: Context, filePath: String?) {
        if (filePath.isNullOrBlank()) return
        val file = File(filePath)
        val rootCanonical = runCatching { getTempDirectory(context).canonicalPath }.getOrNull() ?: return
        val fileCanonical = runCatching { file.canonicalPath }.getOrNull() ?: return
        if (fileCanonical.startsWith(rootCanonical) && file.exists()) {
            file.delete()
        }
    }

    fun crearArchivoTemporalCamara(context: Context): Pair<File, Uri> {
        val appContext = context.applicationContext
        val dir = getTempDirectory(appContext)
        val archivo = File(dir, "lucy_cam_${UUID.randomUUID()}.jpg")
        if (!archivo.exists()) {
            archivo.createNewFile()
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            archivo
        )
        return archivo to uri
    }

    fun isNetworkCurrentlyAvailable(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun prepareFromUri(
        context: Context,
        uri: Uri
    ): LucyPhotoIntakeResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dir = getTempDirectory(appContext)
        val dest = File(dir, "lucy_img_${UUID.randomUUID()}.img")

        try {
            val copiedBytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    var total = 0L
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_RAW_FILE_BYTES) {
                            throw IllegalArgumentException(
                                "La imagen supera el tamaño máximo permitido de 20 MB."
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    total
                }
            } ?: run {
                dest.delete()
                return@withContext LucyPhotoIntakeResult.Invalid(
                    "Android no otorgó acceso de lectura a la fotografía seleccionada."
                )
            }

            if (copiedBytes <= 0L || !dest.exists() || dest.length() <= 0L) {
                dest.delete()
                return@withContext LucyPhotoIntakeResult.Invalid(
                    "El archivo seleccionado está vacío (0 bytes)."
                )
            }

            val displayName = resolveDisplayName(appContext, uri) ?: dest.name
            validateAndDecodeFile(
                context = appContext,
                file = dest,
                displayName = displayName,
                deleteFileOnError = true
            )
        } catch (ce: CancellationException) {
            dest.delete()
            throw ce
        } catch (vme: VirtualMachineError) {
            dest.delete()
            throw vme
        } catch (iae: IllegalArgumentException) {
            dest.delete()
            LucyPhotoIntakeResult.Invalid(iae.message ?: "Imagen demasiado grande.")
        } catch (se: SecurityException) {
            dest.delete()
            LucyPhotoIntakeResult.Invalid("Sin permiso de lectura para la fotografía seleccionada.")
        } catch (io: IOException) {
            dest.delete()
            LucyPhotoIntakeResult.Invalid("No se pudo leer la fotografía (${io.message ?: "error E/S"}). ")
        } catch (e: Exception) {
            dest.delete()
            LucyPhotoIntakeResult.Invalid("Error al preparar la fotografía (${e.message ?: "error"}). ")
        }
    }

    suspend fun prepareFromExistingFile(
        context: Context,
        file: File,
        displayName: String = file.name
    ): LucyPhotoIntakeResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            return@withContext LucyPhotoIntakeResult.Invalid(
                "El archivo de fotografía no existe o está vacío."
            )
        }
        if (file.length() > MAX_RAW_FILE_BYTES) {
            return@withContext LucyPhotoIntakeResult.Invalid(
                "La fotografía excede el tamaño máximo de 20 MB."
            )
        }
        validateAndDecodeFile(
            context = context.applicationContext,
            file = file,
            displayName = displayName,
            deleteFileOnError = false
        )
    }

    private fun validateAndDecodeFile(
        context: Context,
        file: File,
        displayName: String,
        deleteFileOnError: Boolean
    ): LucyPhotoIntakeResult {
        val decoded = try {
            OcrImageProcessor.decodificarArchivoSeguro(file, PREVIEW_MAX_DIM_PX)
        } catch (oom: OutOfMemoryError) {
            if (deleteFileOnError) file.delete()
            return LucyPhotoIntakeResult.Invalid(
                "Memoria insuficiente para previsualizar esta fotografía."
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (e: Exception) {
            if (deleteFileOnError) file.delete()
            return LucyPhotoIntakeResult.Invalid(
                "No se pudo decodificar la imagen (${e.message ?: "formato inválido"}). "
            )
        }

        if (decoded == null || decoded.width <= 0 || decoded.height <= 0) {
            decoded?.recycle()
            if (deleteFileOnError) file.delete()
            return LucyPhotoIntakeResult.Invalid(
                "Formato no compatible o archivo de imagen dañado. Usa JPG, PNG, WEBP o HEIC."
            )
        }

        return LucyPhotoIntakeResult.Validated(
            LucySelectedPhotoPreview(
                tempFilePath = file.absolutePath,
                displayName = displayName,
                rawSizeBytes = file.length(),
                widthPx = decoded.width,
                heightPx = decoded.height,
                previewBitmap = decoded,
                requiresInternet = true,
                networkAvailable = isNetworkCurrentlyAvailable(context)
            )
        )
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else null
            }
        }.getOrNull()
    }
}
