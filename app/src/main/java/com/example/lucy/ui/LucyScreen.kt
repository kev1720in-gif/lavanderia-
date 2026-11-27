package com.example.lucy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.TrabajadorConEstado
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.example.lucy.voice.LucySttState
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepCobaltContainer
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricSkyBlue
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseError
import com.example.ui.theme.SlateMutedText

@Composable
fun LucyScreen(
    lucyViewModel: LucyViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by lucyViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LucyScreen(
        uiState = uiState,
        onEnviarConsulta = { lucyViewModel.enviarConsulta(it) },
        onIniciarEscucha = { lucyViewModel.iniciarEscuchaVoz() },
        onDetenerEscucha = { lucyViewModel.detenerEscuchaVoz() },
        onCancelarEscucha = { lucyViewModel.cancelarEscuchaVoz() },
        onActualizarPermisoMicrofono = { lucyViewModel.actualizarEstadoPermisoMicrofono(it) },
        onAlternarSalidaVoz = { lucyViewModel.alternarSalidaVoz() },
        onDetenerLecturaVoz = { lucyViewModel.detenerLecturaVoz() },
        onLeerMensajeEnVoz = { lucyViewModel.leerMensajeEnVoz(it) },
        onSeleccionarFotoUri = { uri -> lucyViewModel.seleccionarFotografiaUri(context, uri) },
        onAdjuntarFotoCamara = { path -> lucyViewModel.adjuntarFotoDesdeCamara(context, path) },
        onDescartarFotoCamara = { path -> lucyViewModel.descartarArchivoCamaraFallido(context, path) },
        onErrorCamara = { msg -> lucyViewModel.reportarErrorCamara(msg) },
        onQuitarFoto = { lucyViewModel.limpiarFotoSeleccionada() },
        onAnalizarFoto = { lucyViewModel.analizarFotografiaSeleccionada() },
        onSeleccionarCandidato = { lucyViewModel.seleccionarCandidatoDesambiguacion(it) },
        onPrepararConfirmacionDesdeVision = { trabajador, siBajo ->
            lucyViewModel.prepararConfirmacionDesdeVision(trabajador, siBajo)
        },
        onConfirmarAccion = { lucyViewModel.confirmarAccionPendiente() },
        onCancelarAccion = { lucyViewModel.cancelarAccionPendiente() },
        onLimpiarHistorial = { lucyViewModel.limpiarHistorialTurnoActivo() },
        onLimpiarError = { lucyViewModel.limpiarError() },
        onRefrescarEstado = { lucyViewModel.refrescarEstadoModulos() },
        onSeleccionarVoz = { lucyViewModel.seleccionarVozPreferida(it) },
        onSeleccionarEstiloVoz = { lucyViewModel.seleccionarEstiloVoz(it) },
        onSeleccionarVelocidadVoz = { lucyViewModel.seleccionarVelocidadVoz(it) },
        onProbarVoz = { lucyViewModel.probarVozActual() },
        onCancelarOperacion = { lucyViewModel.cancelarOperacionActual() },
        modifier = modifier
    )
}

@Composable
fun LucyScreen(
    uiState: LucyCoreUiState,
    onEnviarConsulta: (String) -> Unit,
    onIniciarEscucha: () -> Unit,
    onDetenerEscucha: () -> Unit,
    onCancelarEscucha: () -> Unit,
    onActualizarPermisoMicrofono: (Boolean) -> Unit,
    onAlternarSalidaVoz: () -> Unit,
    onDetenerLecturaVoz: () -> Unit,
    onLeerMensajeEnVoz: (String) -> Unit,
    onSeleccionarFotoUri: (Uri) -> Unit,
    onQuitarFoto: () -> Unit,
    onAnalizarFoto: () -> Unit,
    onSeleccionarCandidato: (TrabajadorConEstado) -> Unit,
    onPrepararConfirmacionDesdeVision: (TrabajadorConEstado, Boolean) -> Unit,
    onConfirmarAccion: () -> Unit,
    onCancelarAccion: () -> Unit,
    onLimpiarHistorial: () -> Unit,
    onLimpiarError: () -> Unit,
    onRefrescarEstado: () -> Unit,
    modifier: Modifier = Modifier,
    onAdjuntarFotoCamara: (String) -> Unit = {},
    onDescartarFotoCamara: (String?) -> Unit = {},
    onErrorCamara: (String) -> Unit = {},
    onSeleccionarVoz: (String) -> Unit = {},
    onSeleccionarEstiloVoz: (LucyEstiloVoz) -> Unit = {},
    onSeleccionarVelocidadVoz: (LucyVelocidadRespuesta) -> Unit = {},
    onProbarVoz: () -> Unit = {},
    onCancelarOperacion: () -> Unit = {}
) {
    val context = LocalContext.current
    var inputTexto by rememberSaveable { mutableStateOf("") }
    var rutaFotoCamaraPendiente by rememberSaveable { mutableStateOf<String?>(null) }
    var mostrarConfigVoz by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onActualizarPermisoMicrofono(granted)
        if (granted) {
            onIniciarEscucha()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onSeleccionarFotoUri(uri)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { exito ->
        val ruta = rutaFotoCamaraPendiente
        rutaFotoCamaraPendiente = null
        if (exito && !ruta.isNullOrBlank()) {
            onAdjuntarFotoCamara(ruta)
        } else {
            onDescartarFotoCamara(ruta)
        }
    }

    fun abrirCamaraPlanilla() {
        runCatching {
            val (archivo, uri) = LucyPhotoIntake.crearArchivoTemporalCamara(context)
            rutaFotoCamaraPendiente = archivo.absolutePath
            takePictureLauncher.launch(uri)
        }.onFailure { err ->
            onDescartarFotoCamara(rutaFotoCamaraPendiente)
            rutaFotoCamaraPendiente = null
            onErrorCamara("No se pudo abrir la cámara (${err.message ?: "error"}). Puedes usar galería.")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            abrirCamaraPlanilla()
        } else {
            onErrorCamara("Permiso de cámara denegado. Puedes seleccionar la foto desde la galería.")
        }
    }

    fun gestionarBotonCamara() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            abrirCamaraPlanilla()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        onRefrescarEstado()
    }

    LaunchedEffect(uiState.historialTurnoActivo.size) {
        if (uiState.historialTurnoActivo.isNotEmpty()) {
            listState.animateScrollToItem(uiState.historialTurnoActivo.lastIndex)
        }
    }

    fun enviarMensajeActual() {
        val texto = inputTexto.trim()
        if (texto.isEmpty() || uiState.procesando) return
        inputTexto = ""
        onEnviarConsulta(texto)
    }

    fun gestionarBotonMicrofono() {
        if (uiState.voiceStatus.sttState is LucySttState.Listening) {
            onDetenerEscucha()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onIniciarEscucha()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 20.dp else 12.dp

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 880.dp)
                    .padding(horizontal = horizontalPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LucyStatusHeader(
                    uiState = uiState,
                    onAlternarSalidaVoz = onAlternarSalidaVoz,
                    onDetenerLecturaVoz = onDetenerLecturaVoz,
                    onLimpiarHistorial = onLimpiarHistorial,
                    onAbrirConfigVoz = { mostrarConfigVoz = true }
                )

                val listeningState = uiState.voiceStatus.sttState as? LucySttState.Listening
                if (listeningState != null) {
                    LucyListeningBanner(
                        partialText = listeningState.partialText,
                        offlineHonestSummary = uiState.voiceStatus.sttAvailability.offlineHonestSummary,
                        onDetener = onDetenerEscucha,
                        onCancelar = onCancelarEscucha
                    )
                }

                if (!uiState.ultimoErrorRecuperable.isNullOrBlank()) {
                    LucyErrorBanner(
                        message = uiState.ultimoErrorRecuperable,
                        onDismiss = onLimpiarError
                    )
                }

                if (uiState.procesando || uiState.validandoFoto) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinearProgressIndicator(
                            color = ElectricSkyBlue,
                            trackColor = DeepNavySurface,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lucy_progress_indicator")
                        )
                        if (uiState.procesando) {
                            TextButton(
                                onClick = onCancelarOperacion,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("lucy_cancel_operation_button")
                            ) {
                                Text("Detener", color = SlateMutedText)
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("lucy_conversation_list"),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uiState.historialTurnoActivo.isEmpty()) {
                        item(key = "empty_state_${uiState.turnoActivo.name}") {
                            LucyEmptyStateCard(
                                turnoActivo = uiState.turnoActivo,
                                onQuickPrompt = { prompt ->
                                    if (!uiState.procesando) {
                                        onEnviarConsulta(prompt)
                                    }
                                }
                            )
                        }
                    } else {
                        items(
                            items = uiState.historialTurnoActivo,
                            key = { it.id }
                        ) { entry ->
                            LucyConversationBubble(
                                entry = entry,
                                onLeerMensajeEnVoz = onLeerMensajeEnVoz,
                                onPrepararConfirmacionDesdeVision = onPrepararConfirmacionDesdeVision
                            )
                        }
                    }

                    val streamingTexto = uiState.streamingReplyText
                    if (!streamingTexto.isNullOrBlank()) {
                        item(key = "streaming_${uiState.turnoActivo.name}") {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = CardNavySurface),
                                border = BorderStroke(1.dp, ElectricSkyBlue.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth(0.94f)
                                    .testTag("lucy_streaming_bubble")
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Lucy · ${uiState.turnoActivo.nombre}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanAccent
                                    )
                                    Text(
                                        text = streamingTexto,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.candidatosDesambiguacion.isNotEmpty()) {
                        item(key = "desambiguacion_${uiState.turnoActivo.name}") {
                            LucyDisambiguationCard(
                                candidatos = uiState.candidatosDesambiguacion,
                                marcarComoSiBajo = uiState.accionDesambiguacionSiBajo,
                                onSeleccionarCandidato = onSeleccionarCandidato
                            )
                        }
                    }

                    val confirmacion = uiState.confirmacionPendiente
                    if (confirmacion != null) {
                        item(key = "confirmacion_${confirmacion.id}") {
                            LucyConfirmationCard(
                                confirmacion = confirmacion,
                                onConfirmar = onConfirmarAccion,
                                onCancelar = onCancelarAccion
                            )
                        }
                    }
                }

                val foto = uiState.fotoSeleccionada
                if (foto != null) {
                    LucySelectedPhotoPreviewCard(
                        preview = foto,
                        procesando = uiState.procesando,
                        onAnalizarPlanilla = onAnalizarFoto,
                        onQuitarFoto = onQuitarFoto
                    )
                }

                LucyComposerBar(
                    texto = inputTexto,
                    onTextoChange = { inputTexto = it },
                    procesando = uiState.procesando,
                    escuchando = uiState.voiceStatus.sttState is LucySttState.Listening,
                    onEnviar = { enviarMensajeActual() },
                    onMicClick = { gestionarBotonMicrofono() },
                    onTakePhoto = { gestionarBotonCamara() },
                    onPickPhoto = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }
        }
    }

    if (mostrarConfigVoz) {
        LucyVoiceSettingsDialog(
            uiState = uiState,
            onSeleccionarVoz = onSeleccionarVoz,
            onSeleccionarEstiloVoz = onSeleccionarEstiloVoz,
            onSeleccionarVelocidad = onSeleccionarVelocidadVoz,
            onProbarVoz = onProbarVoz,
            onDismiss = { mostrarConfigVoz = false }
        )
    }
}

@Composable
private fun LucyListeningBanner(
    partialText: String,
    offlineHonestSummary: String,
    onDetener: () -> Unit,
    onCancelar: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DeepCobaltContainer),
        border = BorderStroke(1.dp, CyanAccent),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lucy_listening_banner")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = "Escuchando",
                    tint = CyanAccent
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (partialText.isBlank()) {
                            "Escuchando… habla ahora"
                        } else {
                            "Transcripción: $partialText"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = offlineHonestSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMutedText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(
                    onClick = onCancelar,
                    border = BorderStroke(1.dp, BorderNavy),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("lucy_cancelar_escucha_button")
                ) {
                    Text("Cancelar", color = SlateMutedText)
                }
                OutlinedButton(
                    onClick = onDetener,
                    border = BorderStroke(1.dp, CyanAccent),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("lucy_detener_escucha_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Detener y enviar")
                }
            }
        }
    }
}

@Composable
private fun LucyErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = RoseContainer,
        border = BorderStroke(1.dp, RoseError.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lucy_error_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = RoseError,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("lucy_error_dismiss_button")
            ) {
                Text("Cerrar", color = RoseError)
            }
        }
    }
}

@Composable
private fun LucyComposerBar(
    texto: String,
    onTextoChange: (String) -> Unit,
    procesando: Boolean,
    escuchando: Boolean,
    onEnviar: () -> Unit,
    onMicClick: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardNavySurface,
        border = BorderStroke(1.dp, BorderNavy),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lucy_composer_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = onTakePhoto,
                enabled = !procesando,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("lucy_tomar_foto_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = "Tomar foto de planilla con cámara",
                    tint = CyanAccent
                )
            }

            IconButton(
                onClick = onPickPhoto,
                enabled = !procesando,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("lucy_elegir_foto_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.AddPhotoAlternate,
                    contentDescription = "Seleccionar foto de planilla",
                    tint = ElectricSkyBlue
                )
            }

            OutlinedTextField(
                value = texto,
                onValueChange = onTextoChange,
                placeholder = {
                    Text(
                        text = "Consulta o instrucción…",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = false,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onEnviar() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricSkyBlue,
                    unfocusedBorderColor = BorderNavy,
                    focusedContainerColor = DeepNavySurface,
                    unfocusedContainerColor = DeepNavySurface
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("lucy_text_input")
            )

            FilledIconButton(
                onClick = onMicClick,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (escuchando) RoseError else DeepCobaltContainer,
                    contentColor = if (escuchando) ObsidianBackground else CyanAccent
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("lucy_mic_button")
            ) {
                Icon(
                    imageVector = if (escuchando) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (escuchando) "Detener escucha" else "Hablar con Lucy"
                )
            }

            FilledIconButton(
                onClick = onEnviar,
                enabled = texto.isNotBlank() && !procesando,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ElectricSkyBlue,
                    contentColor = ObsidianBackground,
                    disabledContainerColor = DeepNavySurface,
                    disabledContentColor = SlateMutedText
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("lucy_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar consulta a Lucy"
                )
            }
        }
    }
}
