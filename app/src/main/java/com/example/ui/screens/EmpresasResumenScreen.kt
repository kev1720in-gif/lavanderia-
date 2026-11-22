package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.ResumenEmpresaTurno
import com.example.domain.model.Turno
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElevatedNavySurface
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MonoBadgeStyle
import com.example.ui.theme.SlateMutedText
import com.example.ui.theme.TurnoAColor
import com.example.ui.theme.TurnoBColor
import com.example.ui.viewmodel.MainUiState

@Composable
fun EmpresasResumenScreen(
    uiState: MainUiState,
    onAbrirEmpresa: (EmpresaOficial) -> Unit,
    onLimpiarRegistroOperativo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmarLimpiarRegistro by remember { mutableStateOf(false) }
    val turnoColor = if (uiState.turnoActivo == Turno.A) TurnoAColor else TurnoBColor

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .testTag("empresas_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "resumen_turno_activo") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardNavySurface,
                    border = BorderStroke(1.dp, turnoColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("resumen_turno_empresas_card")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Schedule,
                                    contentDescription = null,
                                    tint = turnoColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "RESUMEN ${uiState.turnoActivo.nombre.uppercase()}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = turnoColor,
                                    modifier = Modifier.testTag("resumen_turno_titulo")
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = ElevatedNavySurface,
                                border = BorderStroke(1.dp, turnoColor.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "${uiState.trabajadoresTurno.size} trabajadores",
                                    style = MonoBadgeStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                        .testTag("resumen_turno_conteo_text")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(
                                    text = "SÍ BAJÓ: ${uiState.totalSiBajo}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = EmeraldSuccess,
                                    modifier = Modifier.testTag("resumen_turno_sibajo_text")
                                )
                                Text(
                                    text = "NO BAJÓ: ${uiState.totalNoBajo}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SlateMutedText,
                                    modifier = Modifier.testTag("resumen_turno_nobajo_text")
                                )
                            }

                            OutlinedButton(
                                onClick = { confirmarLimpiarRegistro = true },
                                border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.65f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = AmberContainer.copy(alpha = 0.35f),
                                    contentColor = AmberWarning
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("limpiar_registro_empresas_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CleaningServices,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "LIMPIAR REGISTRO",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            item(key = "titulo_empresas") {
                Text(
                    text = "Las 8 empresas oficiales · ${uiState.turnoActivo.nombre}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(items = uiState.resumenEmpresas, key = { it.empresa.id }) { resumen ->
                EmpresaResumenCard(
                    resumen = resumen,
                    onClick = { onAbrirEmpresa(resumen.empresa) }
                )
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }

    if (confirmarLimpiarRegistro) {
        AlertDialog(
            onDismissRequest = { confirmarLimpiarRegistro = false },
            containerColor = CardNavySurface,
            title = { Text("¿Limpiar registro de ${uiState.turnoActivo.nombre}?") },
            text = {
                Text(
                    "Se limpiarán exclusivamente los estados operativos (SÍ BAJÓ), recepciones, comparaciones y datos temporales de la sesión del ${uiState.turnoActivo.nombre}.\n\n" +
                        "Los trabajadores del registro maestro NO serán eliminados. El otro turno permanecerá intacto."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onLimpiarRegistroOperativo()
                        confirmarLimpiarRegistro = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberWarning,
                        contentColor = DeepNavySurface
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirmar_limpiar_empresas_button")
                ) {
                    Text("Limpiar registro", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { confirmarLimpiarRegistro = false },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("cancelar_limpiar_empresas_button")
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun EmpresaResumenCard(
    resumen: ResumenEmpresaTurno,
    onClick: () -> Unit
) {
    val accent = Color(resumen.empresa.colorHex)
    val progreso = if (resumen.totalTrabajadores > 0) {
        resumen.siBajoCount.toFloat() / resumen.totalTrabajadores.toFloat()
    } else {
        0f
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavySurface),
        border = BorderStroke(1.dp, BorderNavy),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("empresa_card_${resumen.empresa.codigo}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Text(
                        text = resumen.empresa.nombre,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${resumen.totalTrabajadores} trab.",
                        style = MonoBadgeStyle,
                        color = accent
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Ver trabajadores de ${resumen.empresa.nombre}",
                        tint = SlateMutedText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progreso },
                color = EmeraldSuccess,
                trackColor = ElevatedNavySurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SÍ BAJÓ: ${resumen.siBajoCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldSuccess
                )
                Text(
                    text = "NO BAJÓ: ${resumen.noBajoCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlateMutedText
                )
            }
        }
    }
}
