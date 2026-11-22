package com.example.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupValidado
import com.example.data.backup.ResultadoRestauracionBackup
import com.example.data.backup.ResultadoValidacionBackup
import com.example.data.repository.LavanderiaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupPendienteConfirmacion(
    val nombreArchivo: String,
    val validado: BackupValidado
)

data class BackupUiState(
    val procesando: Boolean = false,
    val confirmacionPendiente: BackupPendienteConfirmacion? = null,
    val ultimoMensaje: String? = null,
    val ultimoEsError: Boolean = false
)

class BackupViewModel(
    private val repository: LavanderiaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private val _eventosMensaje = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val eventosMensaje: SharedFlow<String> = _eventosMensaje.asSharedFlow()

    fun generarNombreArchivoBackup(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        return "Lavanderia_Control_Backup_$stamp.json"
    }

    fun exportarBackupHaciaUri(
        contentResolver: ContentResolver,
        uri: Uri,
        nombreArchivo: String
    ) {
        if (_uiState.value.procesando) return
        viewModelScope.launch {
            _uiState.update { it.copy(procesando = true) }
            try {
                val (snapshot, json) = withContext(Dispatchers.IO) {
                    repository.exportarBackupJson()
                }
                withContext(Dispatchers.IO) {
                    val outputStream = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("No se pudo abrir la ubicación elegida para guardar el archivo.")
                    outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(json)
                        writer.flush()
                    }
                }
                val r = snapshot.resumen
                val msg = "Copia de seguridad exportada correctamente ($nombreArchivo): " +
                    "${r.totalTrabajadores} trabajadores (Turno A: ${r.totalTurnoA}, Turno B: ${r.totalTurnoB})."
                _uiState.update {
                    it.copy(
                        procesando = false,
                        ultimoMensaje = msg,
                        ultimoEsError = false
                    )
                }
                _eventosMensaje.emit(msg)
            } catch (e: Exception) {
                val err = "Error al exportar copia de seguridad: ${e.message ?: "no se pudo escribir el archivo"}"
                _uiState.update {
                    it.copy(
                        procesando = false,
                        ultimoMensaje = err,
                        ultimoEsError = true
                    )
                }
                _eventosMensaje.emit(err)
            }
        }
    }

    fun prepararRestauracionDesdeUri(
        contentResolver: ContentResolver,
        uri: Uri,
        nombreArchivo: String
    ) {
        if (_uiState.value.procesando) return
        viewModelScope.launch {
            _uiState.update { it.copy(procesando = true) }
            try {
                val jsonRaw = withContext(Dispatchers.IO) {
                    val inputStream = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado.")
                    inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                procesarContenidoParaConfirmacion(jsonRaw, nombreArchivo)
            } catch (e: Exception) {
                val err = "Error al leer el archivo de respaldo: ${e.message ?: "archivo ilegible"}. La base actual no fue modificada."
                _uiState.update {
                    it.copy(
                        procesando = false,
                        confirmacionPendiente = null,
                        ultimoMensaje = err,
                        ultimoEsError = true
                    )
                }
                _eventosMensaje.emit(err)
            }
        }
    }

    fun prepararRestauracionDesdeContenido(jsonRaw: String, nombreArchivo: String) {
        if (_uiState.value.procesando) return
        viewModelScope.launch {
            _uiState.update { it.copy(procesando = true) }
            procesarContenidoParaConfirmacion(jsonRaw, nombreArchivo)
        }
    }

    private suspend fun procesarContenidoParaConfirmacion(jsonRaw: String, nombreArchivo: String) {
        val validacion = withContext(Dispatchers.Default) {
            repository.validarBackupJson(jsonRaw)
        }
        when (validacion) {
            is ResultadoValidacionBackup.Valido -> {
                _uiState.update {
                    it.copy(
                        procesando = false,
                        confirmacionPendiente = BackupPendienteConfirmacion(
                            nombreArchivo = nombreArchivo,
                            validado = validacion.validado
                        )
                    )
                }
            }
            is ResultadoValidacionBackup.Invalido -> {
                val err = "Copia de seguridad inválida: ${validacion.motivo} No se modificaron los datos actuales."
                _uiState.update {
                    it.copy(
                        procesando = false,
                        confirmacionPendiente = null,
                        ultimoMensaje = err,
                        ultimoEsError = true
                    )
                }
                _eventosMensaje.emit(err)
            }
        }
    }

    fun cancelarRestauracionPendiente() {
        _uiState.update { it.copy(confirmacionPendiente = null) }
    }

    fun confirmarRestauracion(safetyBackupDir: File) {
        val pendiente = _uiState.value.confirmacionPendiente ?: return
        if (_uiState.value.procesando) return

        viewModelScope.launch {
            _uiState.update { it.copy(procesando = true) }
            val resultado = withContext(Dispatchers.IO) {
                repository.restaurarBackupValidado(
                    snapshot = pendiente.validado.snapshot,
                    guardarRespaldoPreventivo = { jsonActual ->
                        if (!safetyBackupDir.exists()) {
                            safetyBackupDir.mkdirs()
                        }
                        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
                        val archivoPreventivo = File(safetyBackupDir, "AutoSafetyBackup_$stamp.json")
                        archivoPreventivo.writeText(jsonActual, Charsets.UTF_8)
                        limpiarRespaldosPreventivosAntiguos(safetyBackupDir, maxConservar = 5)
                        archivoPreventivo.absolutePath
                    }
                )
            }

            when (resultado) {
                is ResultadoRestauracionBackup.Exito -> {
                    val r = resultado.resumen
                    val msg = "Copia de seguridad restaurada correctamente: " +
                        "${r.totalTrabajadores} trabajadores (Turno A: ${r.totalTurnoA}, Turno B: ${r.totalTurnoB})."
                    _uiState.update {
                        it.copy(
                            procesando = false,
                            confirmacionPendiente = null,
                            ultimoMensaje = msg,
                            ultimoEsError = false
                        )
                    }
                    _eventosMensaje.emit(msg)
                }
                is ResultadoRestauracionBackup.Error -> {
                    _uiState.update {
                        it.copy(
                            procesando = false,
                            confirmacionPendiente = null,
                            ultimoMensaje = resultado.mensaje,
                            ultimoEsError = true
                        )
                    }
                    _eventosMensaje.emit(resultado.mensaje)
                }
            }
        }
    }

    private fun limpiarRespaldosPreventivosAntiguos(dir: File, maxConservar: Int) {
        val archivos = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (archivos.size > maxConservar) {
            archivos.drop(maxConservar).forEach { it.delete() }
        }
    }

    class Factory(private val repository: LavanderiaRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BackupViewModel(repository) as T
        }
    }
}
