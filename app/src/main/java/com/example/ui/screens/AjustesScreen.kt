package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BeigeBorderDiscrete
import com.example.ui.theme.BeigeButtonSurface
import com.example.ui.theme.BeigeIndustrialBg
import com.example.ui.theme.BeigeSandCard
import com.example.ui.theme.BeigeSecondaryButtonSurface
import com.example.ui.theme.ElectricSkyBlue
import com.example.ui.theme.EmeraldContainerBeige
import com.example.ui.theme.EmeraldOnBeige
import com.example.ui.theme.GraphiteTextPrimary
import com.example.ui.theme.GraphiteTextSecondary
import com.example.ui.theme.RoseContainerBeige
import com.example.ui.theme.RoseOnBeige
import com.example.ui.theme.SkyBlueOnBeige
import com.example.ui.theme.TurnoAOnBeige
import com.example.ui.theme.TurnoBOnBeige
import com.example.ui.viewmodel.BackupUiState
import com.example.ui.viewmodel.MainUiState
import java.io.File

@Composable
fun AjustesScreen(
    uiState: MainUiState,
    backupUiState: BackupUiState,
    onVolver: () -> Unit,
    onImportarExcel: () -> Unit,
    onImportarCsv: () -> Unit,
    onGenerarNombreBackup: () -> String,
    onExportarBackupUri: (Uri, String) -> Unit,
    onPrepararRestauracionUri: (Uri, String) -> Unit,
    onConfirmarRestauracion: (File) -> Unit,
    onCancelarRestauracion: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onVolver)
    val context = LocalContext.current
    var ultimoNombreSugerido by remember { mutableStateOf("Lavanderia_Control_Backup.json") }

    val launcherCrearBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val nombre = resolverNombreArchivo(context, uri) ?: ultimoNombreSugerido
            onExportarBackupUri(uri, nombre)
        }
    }

    val launcherAbrirBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val nombre = resolverNombreArchivo(context, uri) ?: "copia_seguridad.json"
            onPrepararRestauracionUri(uri, nombre)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BeigeIndustrialBg),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .testTag("ajustes_screen"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Importación unificada al registro maestro
            item(key = "seccion_importar") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BeigeSandCard,
                    border = BorderStroke(1.dp, BeigeBorderDiscrete),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Importar al registro maestro",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = GraphiteTextPrimary
                        )
                        Text(
                            text = "Los trabajadores importados se integran directamente en el registro maestro único, respetando identificadores internos, turnos y empresas oficiales.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraphiteTextSecondary
                        )

                        Button(
                            onClick = onImportarExcel,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BeigeButtonSurface,
                                contentColor = GraphiteTextPrimary
                            ),
                            border = BorderStroke(1.5.dp, ElectricSkyBlue),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 0.dp,
                                pressedElevation = 0.dp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("ajustes_importar_excel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.TableChart,
                                contentDescription = null,
                                tint = SkyBlueOnBeige,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Importar trabajadores desde Excel (.xlsx)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = GraphiteTextPrimary
                            )
                        }

                        OutlinedButton(
                            onClick = onImportarCsv,
                            border = BorderStroke(1.dp, ElectricSkyBlue.copy(alpha = 0.75f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = BeigeSecondaryButtonSurface,
                                contentColor = GraphiteTextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("ajustes_importar_csv_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Description,
                                contentDescription = null,
                                tint = SkyBlueOnBeige,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Importar trabajadores desde CSV (.csv)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = GraphiteTextPrimary
                            )
                        }
                    }
                }
            }

            // 2. Copias de seguridad completas (.json)
            item(key = "seccion_copias_seguridad") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BeigeSandCard,
                    border = BorderStroke(1.dp, BeigeBorderDiscrete),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("seccion_copias_seguridad")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Backup,
                                contentDescription = null,
                                tint = SkyBlueOnBeige
                            )
                            Text(
                                text = "COPIAS DE SEGURIDAD",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = GraphiteTextPrimary
                            )
                        }
                        Text(
                            text = "Exporta o restaura una copia de seguridad completa (.json) con los turnos A y B, trabajadores, apellidos, habitaciones, pabellones, empresas, estados y configuraciones.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraphiteTextSecondary
                        )

                        if (backupUiState.procesando) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = SkyBlueOnBeige,
                                trackColor = BeigeBorderDiscrete
                            )
                        }

                        Button(
                            onClick = {
                                val nombre = onGenerarNombreBackup()
                                ultimoNombreSugerido = nombre
                                launcherCrearBackup.launch(nombre)
                            },
                            enabled = !backupUiState.procesando,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BeigeButtonSurface,
                                contentColor = GraphiteTextPrimary,
                                disabledContainerColor = BeigeSecondaryButtonSurface.copy(alpha = 0.6f),
                                disabledContentColor = GraphiteTextSecondary
                            ),
                            border = BorderStroke(1.5.dp, ElectricSkyBlue),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 0.dp,
                                pressedElevation = 0.dp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("ajustes_exportar_backup_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                tint = SkyBlueOnBeige,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "EXPORTAR COPIA DE SEGURIDAD",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = GraphiteTextPrimary
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                launcherAbrirBackup.launch(
                                    arrayOf("application/json", "text/plain", "application/octet-stream")
                                )
                            },
                            enabled = !backupUiState.procesando,
                            border = BorderStroke(1.dp, ElectricSkyBlue.copy(alpha = 0.75f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = BeigeSecondaryButtonSurface,
                                contentColor = GraphiteTextPrimary,
                                disabledContainerColor = BeigeSecondaryButtonSurface.copy(alpha = 0.5f),
                                disabledContentColor = GraphiteTextSecondary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("ajustes_restaurar_backup_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Restore,
                                contentDescription = null,
                                tint = SkyBlueOnBeige,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "RESTAURAR COPIA DE SEGURIDAD",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = GraphiteTextPrimary
                            )
                        }

                        backupUiState.ultimoMensaje?.let { mensaje ->
                            val esError = backupUiState.ultimoEsError
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (esError) RoseContainerBeige else EmeraldContainerBeige,
                                border = BorderStroke(
                                    1.dp,
                                    (if (esError) RoseOnBeige else EmeraldOnBeige).copy(alpha = 0.45f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("backup_estado_mensaje")
                            ) {
                                Text(
                                    text = mensaje,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (esError) RoseOnBeige else EmeraldOnBeige,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Información de la base de datos
            item(key = "seccion_bd_info") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BeigeSandCard,
                    border = BorderStroke(1.dp, BeigeBorderDiscrete),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Storage,
                                contentDescription = null,
                                tint = SkyBlueOnBeige
                            )
                            Text(
                                text = "Información de la base de datos",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = GraphiteTextPrimary
                            )
                        }

                        InfoRowBeige("Almacenamiento", "Room SQLite local persistente")
                        HorizontalDivider(color = BeigeBorderDiscrete, thickness = 0.5.dp)
                        InfoRowBeige(
                            "Total en registro maestro",
                            "${uiState.conteoTurnoA + uiState.conteoTurnoB} trabajadores"
                        )
                        HorizontalDivider(color = BeigeBorderDiscrete, thickness = 0.5.dp)
                        InfoRowBeige("Turno A", "${uiState.conteoTurnoA} trabajadores", TurnoAOnBeige)
                        HorizontalDivider(color = BeigeBorderDiscrete, thickness = 0.5.dp)
                        InfoRowBeige("Turno B", "${uiState.conteoTurnoB} trabajadores", TurnoBOnBeige)
                        HorizontalDivider(color = BeigeBorderDiscrete, thickness = 0.5.dp)
                        InfoRowBeige(
                            "Estado ${uiState.turnoActivo.nombre}",
                            "SÍ BAJÓ: ${uiState.totalSiBajo} · NO BAJÓ: ${uiState.totalNoBajo}",
                            EmeraldOnBeige
                        )
                        HorizontalDivider(color = BeigeBorderDiscrete, thickness = 0.5.dp)
                        InfoRowBeige(
                            "Fuente unificada",
                            "Manuales e importados en un único maestro"
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }

    val pendiente = backupUiState.confirmacionPendiente
    if (pendiente != null) {
        val resumen = pendiente.validado.resumen
        AlertDialog(
            onDismissRequest = onCancelarRestauracion,
            containerColor = BeigeSandCard,
            titleContentColor = GraphiteTextPrimary,
            textContentColor = GraphiteTextPrimary,
            modifier = Modifier.testTag("dialogo_confirmar_restauracion"),
            title = {
                Text(
                    text = "¿Restaurar copia de seguridad?",
                    fontWeight = FontWeight.Bold,
                    color = GraphiteTextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Archivo: ${pendiente.nombreArchivo}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = GraphiteTextPrimary
                    )
                    if (pendiente.validado.snapshot.generadoEnIso.isNotBlank()) {
                        Text(
                            text = "Fecha del respaldo: ${pendiente.validado.snapshot.generadoEnIso}",
                            style = MaterialTheme.typography.bodySmall,
                            color = GraphiteTextSecondary
                        )
                    }
                    HorizontalDivider(color = BeigeBorderDiscrete, thickness = 0.5.dp)
                    Text(
                        text = "• Total de trabajadores: ${resumen.totalTrabajadores}\n" +
                            "• Turno A: ${resumen.totalTurnoA} · Turno B: ${resumen.totalTurnoB}\n" +
                            "• Empresas: ${resumen.totalEmpresas} · Marcaciones: ${resumen.totalMarcaciones}",
                        style = MaterialTheme.typography.bodySmall,
                        color = GraphiteTextPrimary
                    )
                    HorizontalDivider(color = BeigeBorderDiscrete, thickness = 0.5.dp)
                    Text(
                        text = "Esta acción reemplazará los datos actuales por los del respaldo seleccionado. Antes de restaurar se guardará automáticamente una copia preventiva de la base actual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberWarning,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val safetyDir = File(context.filesDir, "auto_safety_backups")
                        onConfirmarRestauracion(safetyDir)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BeigeButtonSurface,
                        contentColor = GraphiteTextPrimary
                    ),
                    border = BorderStroke(1.5.dp, ElectricSkyBlue),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirmar_restauracion_button")
                ) {
                    Text("Restaurar copia", fontWeight = FontWeight.Bold, color = GraphiteTextPrimary)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onCancelarRestauracion,
                    border = BorderStroke(1.dp, BeigeBorderDiscrete),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = BeigeSecondaryButtonSurface,
                        contentColor = GraphiteTextPrimary
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("cancelar_restauracion_button")
                ) {
                    Text("Cancelar", color = GraphiteTextPrimary)
                }
            }
        )
    }
}

private fun resolverNombreArchivo(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    } catch (_: Exception) {
        uri.lastPathSegment
    }
}

@Composable
private fun InfoRowBeige(
    etiqueta: String,
    valor: String,
    valorColor: Color = GraphiteTextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.bodySmall,
            color = GraphiteTextSecondary
        )
        Text(
            text = valor,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = valorColor
        )
    }
}
