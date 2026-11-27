package com.example.lucy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.lucy.core.LucyConversationEntry
import com.example.lucy.core.LucyModuleHealth
import com.example.lucy.core.LucyModuleId
import com.example.lucy.core.LucyOperationOrigin
import com.example.lucy.core.LucyPendingConfirmation
import com.example.lucy.memory.LucyEstiloVoz
import com.example.lucy.memory.LucyVelocidadRespuesta
import com.example.lucy.vision.LucyVisionDocumentStatus
import com.example.lucy.vision.LucyVisionMarkingStatus
import com.example.lucy.vision.LucyVisionMatchStatus
import com.example.lucy.vision.LucyVisionPlanillaAnalysis
import com.example.lucy.vision.LucyVisionTextOriginType
import com.example.lucy.vision.LucyVisionWorkerDetection
import com.example.lucy.voice.LucyTtsState
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepCobaltContainer
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricSkyBlue
import com.example.ui.theme.ElevatedNavySurface
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MonoBadgeStyle
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseError
import com.example.ui.theme.SlateMutedText
import kotlin.math.roundToInt

@Composable
fun LucyStatusHeader(
    uiState: LucyCoreUiState,
    onAlternarSalidaVoz: () -> Unit,
    onDetenerLecturaVoz: () -> Unit,
    onLimpiarHistorial: () -> Unit,
    modifier: Modifier = Modifier,
    onAbrirConfigVoz: (() -> Unit)? = null
) {
    val statusColor = when (uiState.estadoOperativo) {
        LucyOperationalStatus.LISTA -> EmeraldSuccess
        LucyOperationalStatus.ESCUCHANDO -> CyanAccent
        LucyOperationalStatus.PROCESANDO -> ElectricSkyBlue
        LucyOperationalStatus.RESPONDIENDO -> EmeraldSuccess
        LucyOperationalStatus.ERROR -> RoseError
    }
    val statusBg = when (uiState.estadoOperativo) {
        LucyOperationalStatus.LISTA -> EmeraldContainer
        LucyOperationalStatus.ESCUCHANDO -> DeepCobaltContainer
        LucyOperationalStatus.PROCESANDO -> DeepCobaltContainer
        LucyOperationalStatus.RESPONDIENDO -> EmeraldContainer
        LucyOperationalStatus.ERROR -> RoseContainer
    }
    val statusIcon = when (uiState.estadoOperativo) {
        LucyOperationalStatus.LISTA -> Icons.Filled.AutoAwesome
        LucyOperationalStatus.ESCUCHANDO -> Icons.Filled.GraphicEq
        LucyOperationalStatus.PROCESANDO -> Icons.Filled.HourglassTop
        LucyOperationalStatus.RESPONDIENDO -> Icons.AutoMirrored.Filled.VolumeUp
        LucyOperationalStatus.ERROR -> Icons.Filled.ErrorOutline
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DeepNavySurface),
        border = BorderStroke(1.dp, BorderNavy),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lucy_status_header")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusBg,
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("lucy_operational_status_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = uiState.estadoOperativo.etiqueta,
                                tint = statusColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Lucy · ${uiState.estadoOperativo.etiqueta}",
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (uiState.voiceStatus.ttsState is LucyTtsState.Speaking) {
                        IconButton(
                            onClick = onDetenerLecturaVoz,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("lucy_tts_stop_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.StopCircle,
                                contentDescription = "Detener voz de Lucy",
                                tint = AmberWarning
                            )
                        }
                    }

                    val voiceEnabled = uiState.voiceStatus.voiceOutputEnabled
                    IconButton(
                        onClick = onAlternarSalidaVoz,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("lucy_tts_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (voiceEnabled) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = if (voiceEnabled) {
                                "Silenciar respuestas habladas"
                            } else {
                                "Activar respuestas habladas"
                            },
                            tint = if (voiceEnabled) ElectricSkyBlue else SlateMutedText
                        )
                    }

                    if (onAbrirConfigVoz != null) {
                        IconButton(
                            onClick = onAbrirConfigVoz,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("lucy_voice_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = "Configurar voz de Lucy",
                                tint = CyanAccent
                            )
                        }
                    }

                    IconButton(
                        onClick = onLimpiarHistorial,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("lucy_limpiar_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = "Limpiar conversación del turno",
                            tint = SlateMutedText
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModuleHealthBadge(
                    label = "Memoria",
                    health = uiState.saludModulos[LucyModuleId.MEMORY]
                )
                val voiceHealth = if (uiState.voiceStatus.isFallbackActive) {
                    LucyModuleHealth.Degraded(
                        uiState.voiceStatus.fallbackReason ?: "Respaldo Android"
                    )
                } else {
                    uiState.saludModulos[LucyModuleId.VOICE]
                }
                ModuleHealthBadge(
                    label = "Voz",
                    health = voiceHealth
                )
                ModuleHealthBadge(
                    label = "Visión",
                    health = uiState.saludModulos[LucyModuleId.VISION]
                )
            }

            if (uiState.voiceStatus.isFallbackActive && !uiState.voiceStatus.fallbackReason.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AmberContainer.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lucy_voice_fallback_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Hablando con: ${uiState.voiceStatus.activeVoiceEngineLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AmberWarning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Motivo respaldo: ${uiState.voiceStatus.fallbackReason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleHealthBadge(
    label: String,
    health: LucyModuleHealth?
) {
    val (estadoTexto, tint) = when (health) {
        is LucyModuleHealth.Ready -> "Lista" to EmeraldSuccess
        is LucyModuleHealth.Degraded -> "Limitada" to AmberWarning
        is LucyModuleHealth.Unavailable -> "No disp." to RoseError
        is LucyModuleHealth.NotConfigured, null -> "Sin config." to SlateMutedText
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = CardNavySurface,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Text(
                text = "$label: $estadoTexto",
                style = MaterialTheme.typography.labelSmall,
                color = SlateMutedText,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LucyEmptyStateCard(
    turnoActivo: Turno,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sugerencias = remember(turnoActivo) {
        listOf(
            "Resumen",
            "Consultar empresas",
            "Pendientes",
            "Ver trabajadores",
            "Ayuda"
        )
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavySurface),
        border = BorderStroke(1.dp, BorderNavy),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lucy_empty_state_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DeepCobaltContainer)
                        .border(1.dp, ElectricSkyBlue.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = ElectricSkyBlue
                    )
                }
                Column {
                    Text(
                        text = "Lucy lista en ${turnoActivo.nombre}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Consultas operativas, voz, memoria y revisión visual de planillas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                }
            }

            Text(
                text = "Acciones rápidas:",
                style = MaterialTheme.typography.labelMedium,
                color = SlateMutedText
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sugerencias.forEach { prompt ->
                    AssistChip(
                        onClick = { onQuickPrompt(prompt) },
                        label = { Text(prompt) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = ElevatedNavySurface,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, BorderNavy),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("lucy_chip_${prompt.lowercase().replace(' ', '_')}")
                    )
                }
            }
        }
    }
}

@Composable
fun LucyConversationBubble(
    entry: LucyConversationEntry,
    onLeerMensajeEnVoz: (String) -> Unit,
    onPrepararConfirmacionDesdeVision: (TrabajadorConEstado, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val alignment = if (entry.esUsuario) Alignment.End else Alignment.Start
    val containerColor = if (entry.esUsuario) DeepCobaltContainer else CardNavySurface
    val borderColor = if (entry.esUsuario) {
        ElectricSkyBlue.copy(alpha = 0.55f)
    } else if (entry.response?.esErrorRecuperable == true) {
        RoseError.copy(alpha = 0.55f)
    } else {
        BorderNavy
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (entry.esUsuario) 16.dp else 4.dp,
                bottomEnd = if (entry.esUsuario) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth(0.94f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val etiquetaOrigen = when {
                        entry.esUsuario && entry.origen == LucyOperationOrigin.VOICE -> "Tú · Voz"
                        entry.esUsuario && entry.origen == LucyOperationOrigin.VISION -> "Tú · Visión"
                        entry.esUsuario -> "Tú"
                        else -> "Lucy · ${entry.turno.nombre}"
                    }
                    Text(
                        text = etiquetaOrigen,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.esUsuario) ElectricSkyBlue else CyanAccent
                    )

                    if (!entry.esUsuario && entry.texto.isNotBlank()) {
                        IconButton(
                            onClick = { onLeerMensajeEnVoz(entry.texto) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Leer mensaje en voz alta",
                                tint = SlateMutedText,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Text(
                    text = entry.texto,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val visionAnalysis = entry.response?.visionAnalysis
                if (visionAnalysis != null) {
                    LucyVisionAnalysisCard(
                        analysis = visionAnalysis,
                        onPrepararConfirmacion = onPrepararConfirmacionDesdeVision
                    )
                }
            }
        }
    }
}

@Composable
fun LucyVisionAnalysisCard(
    analysis: LucyVisionPlanillaAnalysis,
    onPrepararConfirmacion: (TrabajadorConEstado, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val docColor = when (analysis.estadoDocumento) {
        LucyVisionDocumentStatus.LEGIBLE -> EmeraldSuccess
        LucyVisionDocumentStatus.PARCIALMENTE_LEGIBLE -> AmberWarning
        else -> RoseError
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DeepNavySurface,
        border = BorderStroke(1.dp, BorderNavy),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lucy_vision_result_card")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = docColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, docColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = analysis.estadoDocumento.etiqueta,
                            style = MaterialTheme.typography.labelSmall,
                            color = docColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (analysis.tipoEscrituraDocumento != LucyVisionTextOriginType.INDETERMINADO) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DeepCobaltContainer,
                            border = BorderStroke(1.dp, ElectricSkyBlue.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = analysis.tipoEscrituraDocumento.etiqueta,
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricSkyBlue,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Text(
                    text = analysis.motorUtilizado,
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateMutedText
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AmberContainer.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = AmberWarning,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Propuesta visual sin confirmar. No realiza marcaciones automáticas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberWarning
                    )
                }
            }

            if (analysis.posiblesDuplicados.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = RoseContainer.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, RoseError.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Posibles duplicados en la planilla:",
                            style = MaterialTheme.typography.labelMedium,
                            color = RoseError
                        )
                        analysis.posiblesDuplicados.forEach { dup ->
                            Text(
                                text = "• ${dup.motivo}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            analysis.trabajadoresDetectados.forEach { det ->
                LucyVisionWorkerRowCard(
                    detection = det,
                    onPrepararConfirmacion = onPrepararConfirmacion
                )
            }
        }
    }
}

@Composable
private fun LucyVisionWorkerRowCard(
    detection: LucyVisionWorkerDetection,
    onPrepararConfirmacion: (TrabajadorConEstado, Boolean) -> Unit
) {
    val obs = detection.datosReconocidos
    val inf = detection.datosInferidos
    val pct = (detection.confianzaReconocimiento * 100f).roundToInt().coerceIn(0, 100)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ElevatedNavySurface,
        border = BorderStroke(1.dp, BorderNavy),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${obs.filaNumero ?: detection.indiceEnAnalisis} · ${obs.nombreDetectado}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${detection.nivelConfianza.etiqueta} ($pct%)",
                    style = MonoBadgeStyle,
                    color = ElectricSkyBlue
                )
            }

            if (detection.tipoEscritura != LucyVisionTextOriginType.INDETERMINADO ||
                detection.esDudosoOIlegible ||
                detection.tieneMarcasManuales
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (detection.tipoEscritura != LucyVisionTextOriginType.INDETERMINADO) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DeepCobaltContainer,
                            border = BorderStroke(1.dp, ElectricSkyBlue.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = detection.tipoEscritura.etiqueta,
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricSkyBlue,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (detection.tieneMarcasManuales) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DeepCobaltContainer,
                            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "Marca manual",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (detection.esDudosoOIlegible) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AmberContainer,
                            border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "Dudoso / revisar",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberWarning,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Bloque 1: Datos literalmente reconocidos en la fotografía
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DeepNavySurface,
                border = BorderStroke(1.dp, BorderNavy)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "RECONOCIDO EN IMAGEN",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent
                    )
                    Text(
                        text = detection.formatoTablaRevision,
                        style = MonoBadgeStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Texto fila: ${obs.textoCrudoFila}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val detallesVis = buildList {
                        obs.apellidoPaternoDetectado?.let { add("Paterno: $it") }
                        obs.apellidoMaternoDetectado?.let { add("Materno: $it") }
                        obs.empresaEscritaEnImagen?.let { add("Empresa escrita: $it") }
                        obs.pabellonEscritoEnImagen?.let { add("Pabellón: $it") }
                        obs.habitacionEscritaEnImagen?.let { add("Hab: $it") }
                        obs.marcaVisualLiteral?.let { add("Marca: $it") }
                        obs.anotacionesFila?.let { add("Anotación: $it") }
                    }
                    if (detallesVis.isNotEmpty()) {
                        Text(
                            text = detallesVis.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateMutedText
                        )
                    }
                }
            }

            // Bloque 2: Datos inferidos / propuesta del maestro
            val matchColor = when (inf.coincidenciaProbable.estado) {
                LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA -> EmeraldSuccess
                LucyVisionMatchStatus.AMBIGUA_MULTIPLE -> AmberWarning
                LucyVisionMatchStatus.NOMBRE_INCOMPLETO,
                LucyVisionMatchStatus.SIN_COINCIDENCIA -> RoseError
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = ObsidianBackground.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, matchColor.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "INFERIDO / PROPUESTA (SIN CONFIRMAR)",
                        style = MaterialTheme.typography.labelSmall,
                        color = matchColor
                    )
                    Text(
                        text = "Empresa probable: ${inf.empresaProbable?.nombre ?: "Sin determinar"} (${inf.origenEmpresa.etiqueta})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Marcación propuesta: ${inf.estadoMarcacionPropuesto.etiqueta}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                    Text(
                        text = "Maestro: ${inf.coincidenciaProbable.estado.etiqueta}",
                        style = MaterialTheme.typography.bodySmall,
                        color = matchColor
                    )

                    if (inf.coincidenciaProbable.estado == LucyVisionMatchStatus.AMBIGUA_MULTIPLE) {
                        inf.coincidenciaProbable.candidatos.take(3).forEach { cand ->
                            Text(
                                text = "  • ${cand.trabajador.nombreCompleto} (${cand.trabajador.empresa.nombre}, ${cand.trabajador.ubicacionTexto})",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberWarning
                            )
                        }
                    } else if (inf.coincidenciaProbable.estado == LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA) {
                        val cand = inf.coincidenciaProbable.candidatoPrincipal?.trabajador
                        if (cand != null) {
                            Text(
                                text = "Candidato: ${cand.nombreCompleto} (${cand.empresa.nombre}, ${cand.ubicacionTexto})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val estadoPropuesto = inf.estadoMarcacionPropuesto
                            if (estadoPropuesto != LucyVisionMarkingStatus.INDETERMINADO) {
                                val siBajoTarget = estadoPropuesto == LucyVisionMarkingStatus.SI_BAJO
                                OutlinedButton(
                                    onClick = { onPrepararConfirmacion(cand, siBajoTarget) },
                                    border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        text = "Preparar confirmación (${estadoPropuesto.etiqueta})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = EmeraldSuccess
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (detection.observacionesYDudas.isNotEmpty()) {
                Text(
                    text = "Dudas: ${detection.observacionesYDudas.joinToString("; ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberWarning
                )
            }
        }
    }
}

@Composable
fun LucyDisambiguationCard(
    candidatos: List<TrabajadorConEstado>,
    marcarComoSiBajo: Boolean,
    onSeleccionarCandidato: (TrabajadorConEstado) -> Unit,
    modifier: Modifier = Modifier
) {
    val etiqueta = if (marcarComoSiBajo) "SÍ BAJÓ" else "NO BAJÓ"
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AmberContainer.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.65f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lucy_desambiguacion_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PersonSearch,
                    contentDescription = null,
                    tint = AmberWarning
                )
                Text(
                    text = "Selecciona el trabajador exacto para marcar $etiqueta:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            candidatos.forEach { trabajador ->
                OutlinedButton(
                    onClick = { onSeleccionarCandidato(trabajador) },
                    border = BorderStroke(1.dp, BorderNavy),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        text = "${trabajador.nombreCompleto} · ${trabajador.empresa.nombre} · ${trabajador.ubicacionTexto}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun LucyConfirmationCard(
    confirmacion: LucyPendingConfirmation,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ElevatedNavySurface),
        border = BorderStroke(1.5.dp, ElectricSkyBlue),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lucy_confirmacion_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = ElectricSkyBlue
                )
                Text(
                    text = "Confirmación explícita requerida (${confirmacion.turno.nombre})",
                    style = MaterialTheme.typography.titleSmall,
                    color = ElectricSkyBlue
                )
            }

            Text(
                text = confirmacion.descripcion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCancelar,
                    border = BorderStroke(1.dp, BorderNavy),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("lucy_cancelar_accion_button")
                ) {
                    Text("Cancelar", color = SlateMutedText)
                }

                Button(
                    onClick = onConfirmar,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldSuccess,
                        contentColor = ObsidianBackground
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("lucy_confirmar_accion_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Confirmar", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LucySelectedPhotoPreviewCard(
    preview: LucySelectedPhotoPreview,
    procesando: Boolean,
    onAnalizarPlanilla: () -> Unit,
    onQuitarFoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(preview.previewBitmap) {
        preview.previewBitmap?.asImageBitmap()
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavySurface),
        border = BorderStroke(1.dp, ElectricSkyBlue.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lucy_foto_preview_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Vista previa de ${preview.displayName}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, BorderNavy, RoundedCornerShape(10.dp))
                            .testTag("lucy_foto_preview_image")
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = preview.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${preview.widthPx}×${preview.heightPx} px · ${preview.sizeLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (preview.networkAvailable) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                            contentDescription = null,
                            tint = if (preview.networkAvailable) EmeraldSuccess else AmberWarning,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (preview.networkAvailable) {
                                "Requiere conexión · Red activa"
                            } else {
                                "Requiere conexión a Internet (sin red ahora)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (preview.networkAvailable) EmeraldSuccess else AmberWarning
                        )
                    }
                }

                IconButton(
                    onClick = onQuitarFoto,
                    enabled = !procesando,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("lucy_quitar_foto_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Quitar fotografía seleccionada",
                        tint = SlateMutedText
                    )
                }
            }

            HorizontalDivider(color = BorderNavy)

            Button(
                onClick = onAnalizarPlanilla,
                enabled = !procesando,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("lucy_analizar_foto_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DocumentScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (procesando) "Analizando planilla…" else "Analizar planilla con Lucy Vision")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LucyVoiceSettingsDialog(
    uiState: LucyCoreUiState,
    onSeleccionarVoz: (String) -> Unit,
    onSeleccionarEstiloVoz: (LucyEstiloVoz) -> Unit,
    onSeleccionarVelocidad: (LucyVelocidadRespuesta) -> Unit,
    onProbarVoz: () -> Unit,
    onDismiss: () -> Unit
) {
    val voiceStatus = uiState.voiceStatus
    val opcionesVoz = voiceStatus.availableVoices

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepNavySurface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("lucy_voice_settings_dialog"),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Voz de Lucy",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Motor activo: ${voiceStatus.activeVoiceEngineLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!voiceStatus.isFallbackActive && voiceStatus.fallbackReason.isNullOrBlank()) CyanAccent else AmberWarning,
                    modifier = Modifier.testTag("lucy_voice_active_engine_label")
                )
                if (!voiceStatus.fallbackReason.isNullOrBlank()) {
                    Text(
                        text = "Respaldo activo: ${voiceStatus.fallbackReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMutedText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("lucy_voice_fallback_notice")
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Estilo de voz",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlateMutedText
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LucyEstiloVoz.entries.forEach { estilo ->
                        val selected = voiceStatus.appliedVoiceStyle == estilo
                        FilterChip(
                            selected = selected,
                            onClick = { onSeleccionarEstiloVoz(estilo) },
                            label = { Text(estilo.etiqueta) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepCobaltContainer,
                                selectedLabelColor = ElectricSkyBlue
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("lucy_voice_style_${estilo.name.lowercase()}")
                        )
                    }
                }

                Text(
                    text = "Velocidad",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlateMutedText
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LucyVelocidadRespuesta.entries.forEach { vel ->
                        val selected = voiceStatus.appliedSpeed == vel
                        FilterChip(
                            selected = selected,
                            onClick = { onSeleccionarVelocidad(vel) },
                            label = { Text(vel.etiqueta) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepCobaltContainer,
                                selectedLabelColor = ElectricSkyBlue
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("lucy_voice_speed_${vel.name.lowercase()}")
                        )
                    }
                }

                if (opcionesVoz.isNotEmpty()) {
                    Text(
                        text = "Seleccionar voz",
                        style = MaterialTheme.typography.labelMedium,
                        color = SlateMutedText
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        opcionesVoz.forEach { opcion ->
                            val isSavedPreference = voiceStatus.selectedVoiceId == opcion.id
                            val isVoiceActuallyActive =
                                (!voiceStatus.isFallbackActive || opcion.id.startsWith("tts:")) &&
                                    isSavedPreference
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isVoiceActuallyActive) DeepCobaltContainer else CardNavySurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isVoiceActuallyActive) ElectricSkyBlue else BorderNavy
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSeleccionarVoz(opcion.id) }
                                    .testTag("lucy_voice_option_${opcion.id.ifBlank { "auto" }}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(
                                        selected = isVoiceActuallyActive,
                                        onClick = { onSeleccionarVoz(opcion.id) }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = opcion.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = opcion.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SlateMutedText,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (voiceStatus.isFallbackActive && isSavedPreference && !opcion.id.startsWith("tts:")) {
                                            Text(
                                                text = "Preferencia guardada · En espera (hablando respaldo local)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AmberWarning,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onProbarVoz,
                border = BorderStroke(1.dp, CyanAccent),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("lucy_voice_test_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Probar voz")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("lucy_voice_dialog_close_button")
            ) {
                Text("Cerrar", color = ElectricSkyBlue)
            }
        }
    )
}
