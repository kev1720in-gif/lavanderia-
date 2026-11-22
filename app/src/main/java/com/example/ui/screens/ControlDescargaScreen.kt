package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.ocr.OcrImageProcessor
import com.example.domain.model.AjusteFotoPlanilla
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.FotoLoteDescargaUi
import com.example.domain.model.PendienteDescargaUi
import com.example.domain.model.TrabajadorConEstado
import com.example.ui.components.BuscarTrabajadorManualDialog
import com.example.ui.components.ConfirmarConciliacionDialog
import com.example.ui.components.ConfirmarLimpiarDescargaDialog
import com.example.ui.components.EmpresaSelectorDescargaCard
import com.example.ui.components.FotoLoteChipCard
import com.example.ui.components.FotoLoteEditorDialog
import com.example.ui.components.PendienteDescargaCard
import com.example.ui.components.ResumenBatchDescargaCard
import com.example.ui.components.ResumenIdentificadosDescargaCard
import com.example.ui.theme.SlateMutedText
import com.example.ui.viewmodel.DescargaUiState
import java.io.File

@Composable
fun ControlDescargaScreen(
    uiState: DescargaUiState,
    onSeleccionarEmpresa: (EmpresaOficial) -> Unit,
    onProcesarFotoCamara: (Uri, File?) -> Unit,
    onCancelarFotoCamara: (File?) -> Unit,
    onProcesarFotosGaleria: (List<Uri>) -> Unit,
    onReprocesarFotoConAjuste: (String, AjusteFotoPlanilla) -> Unit,
    onDescartarFotoLote: (String) -> Unit,
    onConfirmarPendiente: (String, TrabajadorConEstado) -> Unit,
    onDescartarPendiente: (String) -> Unit,
    onSolicitarPrevisualizacionConciliacion: () -> Unit,
    onCancelarPrevisualizacionConciliacion: () -> Unit,
    onConfirmarConciliacionFinal: () -> Unit,
    onLimpiarRegistroOperativo: () -> Unit,
    onMostrarMensaje: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var pendienteBuscarManual by remember { mutableStateOf<PendienteDescargaUi?>(null) }
    var fotoAEditar by remember { mutableStateOf<FotoLoteDescargaUi?>(null) }
    var mostrarDialogoLimpiar by remember { mutableStateOf(false) }

    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraUriStr by rememberSaveable { mutableStateOf<String?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraPath?.let { File(it) }
        val uri = pendingCameraUriStr?.let { Uri.parse(it) }
        pendingCameraPath = null
        pendingCameraUriStr = null

        if (success && uri != null) {
            onProcesarFotoCamara(uri, file)
        } else {
            onCancelarFotoCamara(file)
        }
    }

    fun lanzarCamaraConArchivoTemporal() {
        runCatching {
            OcrImageProcessor.crearArchivoTemporalCamara(context)
        }.onSuccess { (file, uri) ->
            pendingCameraPath = file.absolutePath
            pendingCameraUriStr = uri.toString()
            takePictureLauncher.launch(uri)
        }.onFailure { err ->
            onMostrarMensaje("No se pudo iniciar la cámara (${err.message ?: "error interno"}). ")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            lanzarCamaraConArchivoTemporal()
        } else {
            onMostrarMensaje("Permiso de cámara denegado. Puedes elegir fotos existentes desde tu galería.")
        }
    }

    val pickGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onProcesarFotosGaleria(uris)
        }
    }

    fun solicitarTomarFoto() {
        val permisoOk = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (permisoOk) {
            lanzarCamaraConArchivoTemporal()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp)
                .testTag("descarga_screen"),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "selector_empresa") {
                EmpresaSelectorDescargaCard(
                    empresaSeleccionada = uiState.empresaSeleccionada,
                    resumenEmpresas = uiState.resumenEmpresasTurno,
                    onSeleccionarEmpresa = onSeleccionarEmpresa
                )
            }

            item(key = "resumen_batch") {
                ResumenBatchDescargaCard(
                    uiState = uiState,
                    onTomarFoto = { solicitarTomarFoto() },
                    onElegirFotos = {
                        pickGalleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onFinalizarConciliar = onSolicitarPrevisualizacionConciliacion,
                    onLimpiarRegistro = { mostrarDialogoLimpiar = true }
                )
            }

            if (uiState.fotosLote.isNotEmpty()) {
                item(key = "titulo_fotos_lote") {
                    Text(
                        text = "FOTOGRAFÍAS DEL LOTE (${uiState.fotosLote.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = SlateMutedText
                    )
                }

                items(items = uiState.fotosLote, key = { "foto_${it.id}" }) { foto ->
                    FotoLoteChipCard(
                        foto = foto,
                        onEditarRecortar = { fotoAEditar = foto },
                        onDescartar = { onDescartarFotoLote(foto.id) }
                    )
                }
            }

            if (uiState.pendientes.isNotEmpty()) {
                item(key = "titulo_pendientes") {
                    Text(
                        text = "PENDIENTES DE IDENTIFICACIÓN (${uiState.pendientes.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag("descarga_pendientes_header")
                    )
                }

                items(items = uiState.pendientes, key = { "pend_${it.registroId}" }) { pend ->
                    PendienteDescargaCard(
                        pendiente = pend,
                        onConfirmarCandidato = { trabajador ->
                            onConfirmarPendiente(pend.registroId, trabajador)
                        },
                        onBuscarManual = { pendienteBuscarManual = pend },
                        onDescartar = { onDescartarPendiente(pend.registroId) }
                    )
                }
            }

            item(key = "resumen_identificados") {
                ResumenIdentificadosDescargaCard(
                    empresa = uiState.empresaSeleccionada,
                    identificados = uiState.identificadosEnFotos,
                    totalEmpresa = uiState.trabajadoresEmpresa.size
                )
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }

    fotoAEditar?.let { foto ->
        FotoLoteEditorDialog(
            foto = foto,
            onAplicarYReprocesar = { ajuste ->
                fotoAEditar = null
                onReprocesarFotoConAjuste(foto.id, ajuste)
            },
            onCerrar = { fotoAEditar = null }
        )
    }

    pendienteBuscarManual?.let { pend ->
        BuscarTrabajadorManualDialog(
            pendiente = pend,
            trabajadoresEmpresa = uiState.trabajadoresEmpresa,
            onSeleccionar = { elegido ->
                onConfirmarPendiente(pend.registroId, elegido)
                pendienteBuscarManual = null
            },
            onCerrar = { pendienteBuscarManual = null }
        )
    }

    uiState.previsualizacionConciliacion?.let { preview ->
        ConfirmarConciliacionDialog(
            previsualizacion = preview,
            onConfirmar = onConfirmarConciliacionFinal,
            onCancelar = onCancelarPrevisualizacionConciliacion
        )
    }

    if (mostrarDialogoLimpiar) {
        ConfirmarLimpiarDescargaDialog(
            turno = uiState.turnoActivo,
            onConfirmar = {
                onLimpiarRegistroOperativo()
                mostrarDialogoLimpiar = false
            },
            onCancelar = { mostrarDialogoLimpiar = false }
        )
    }
}
