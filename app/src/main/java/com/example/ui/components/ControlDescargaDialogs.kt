package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.BuscadorTrabajadores
import com.example.domain.model.PendienteDescargaUi
import com.example.domain.model.PrevisualizacionConciliacion
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricSkyBlue
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SlateMutedText
import kotlin.math.roundToInt

@Composable
fun PendienteDescargaCard(
    pendiente: PendienteDescargaUi,
    onConfirmarCandidato: (TrabajadorConEstado) -> Unit,
    onBuscarManual: () -> Unit,
    onDescartar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AmberContainer.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.7f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("pendiente_card_${pendiente.registroId}")
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LEÍDO EN FOTO: ${pendiente.textoOriginal.uppercase()}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = pendiente.motivo,
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberWarning
                    )
                }

                IconButton(
                    onClick = onDescartar,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("descartar_pendiente_${pendiente.registroId}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Descartar línea pendiente",
                        tint = SlateMutedText
                    )
                }
            }

            if (pendiente.candidatosSugeridos.isNotEmpty()) {
                Text(
                    text = "Coincidencias sugeridas (toca para confirmar y recordar):",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateMutedText
                )
                pendiente.candidatosSugeridos.forEach { cand ->
                    val w = cand.trabajador
                    val pct = (cand.puntajeSimilitud * 100.0).roundToInt().coerceIn(1, 100)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = CardNavySurface,
                        border = BorderStroke(1.dp, BorderNavy),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onConfirmarCandidato(w) }
                            .heightIn(min = 48.dp)
                            .testTag("confirmar_candidato_${pendiente.registroId}_${w.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = w.nombreCompleto,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = listOfNotNull(
                                        w.habitacionMayusculas.takeIf { it.isNotBlank() }?.let { "Hab. $it" },
                                        w.pabellonMayusculas.takeIf { it.isNotBlank() }?.let { "Pab. $it" }
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SlateMutedText
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = EmeraldContainer,
                                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = "CONFIRMAR ($pct%)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldSuccess,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onBuscarManual,
                border = BorderStroke(1.dp, ElectricSkyBlue.copy(alpha = 0.65f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricSkyBlue),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("buscar_manual_pendiente_${pendiente.registroId}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("BUSCAR MANUALMENTE EN EMPRESA", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun BuscarTrabajadorManualDialog(
    pendiente: PendienteDescargaUi,
    trabajadoresEmpresa: List<TrabajadorConEstado>,
    onSeleccionar: (TrabajadorConEstado) -> Unit,
    onCerrar: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtrados = remember(trabajadoresEmpresa, query) {
        BuscadorTrabajadores.filtrarYOrdenar(trabajadoresEmpresa, query).take(40)
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = CardNavySurface,
        title = { Text("Asignar trabajador correcto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Texto OCR: ${pendiente.textoOriginal.uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberWarning
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar apellido, nombre o habitación") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricSkyBlue,
                        unfocusedBorderColor = BorderNavy
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("buscar_manual_pendiente_input")
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtrados, key = { it.id }) { w ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DeepNavySurface,
                            border = BorderStroke(1.dp, BorderNavy),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSeleccionar(w) }
                                .heightIn(min = 48.dp)
                                .testTag("seleccionar_manual_worker_${w.id}")
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    text = w.nombreCompleto,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val ubic = listOfNotNull(
                                    w.habitacionMayusculas.takeIf { it.isNotBlank() }?.let { "Hab. $it" },
                                    w.pabellonMayusculas.takeIf { it.isNotBlank() }?.let { "Pab. $it" }
                                ).joinToString(" · ")
                                if (ubic.isNotBlank()) {
                                    Text(
                                        text = ubic,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SlateMutedText
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(
                onClick = onCerrar,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
fun ConfirmarConciliacionDialog(
    previsualizacion: PrevisualizacionConciliacion,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        containerColor = CardNavySurface,
        modifier = Modifier.testTag("dialogo_confirmar_conciliacion"),
        title = { Text("¿Finalizar descarga de ${previsualizacion.empresa.nombre}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${previsualizacion.turno.nombre}: se conciliará todo el conjunto completo y legible de fotografías contra el listado maestro.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Quedarán SÍ BAJÓ: ${previsualizacion.confirmadosEnFotosCount} trabajadores detectados en las fotografías.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmeraldSuccess,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• Quedarán NO BAJÓ: ${previsualizacion.totalNoBajoFinal} trabajadores ausentes en las fotografías.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateMutedText
                )
                if (previsualizacion.pasaranANoBajoDesdeManualOVoz.isNotEmpty()) {
                    HorizontalDivider(color = BorderNavy)
                    Text(
                        text = "Atención: ${previsualizacion.pasaranANoBajoDesdeManualOVoz.size} trabajadores tenían marca previa (MANUAL o VOZ) pero no aparecieron en las fotos y pasarán a NO BAJÓ:",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarning,
                        fontWeight = FontWeight.SemiBold
                    )
                    previsualizacion.pasaranANoBajoDesdeManualOVoz.take(5).forEach { w ->
                        Text(
                            text = "- ${w.nombreCompleto} (${w.habitacionMayusculas})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (previsualizacion.pasaranANoBajoDesdeManualOVoz.size > 5) {
                        Text(
                            text = "… y ${previsualizacion.pasaranANoBajoDesdeManualOVoz.size - 5} más.",
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateMutedText
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmar,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldSuccess,
                    contentColor = DeepNavySurface
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirmar_conciliacion_final_button")
            ) {
                Text("Confirmar descarga", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cancelar_conciliacion_final_button")
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun ConfirmarLimpiarDescargaDialog(
    turno: Turno,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        containerColor = CardNavySurface,
        title = { Text("¿Limpiar registro de ${turno.nombre}?") },
        text = {
            Text(
                "Se reiniciarán los estados SÍ BAJÓ, las lecturas fotográficas y los pendientes de identificación del ${turno.nombre}.\n\n" +
                    "El maestro de trabajadores, el otro turno y las correcciones aprendidas de OCR NO serán eliminados."
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmar,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberWarning,
                    contentColor = DeepNavySurface
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirmar_limpiar_descarga_button")
            ) {
                Text("Limpiar registro", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cancelar_limpiar_descarga_button")
            ) {
                Text("Cancelar")
            }
        }
    )
}
