package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.data.ocr.OcrImageProcessor
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.EstadoFotoDescarga
import com.example.domain.model.FotoLoteDescargaUi
import com.example.domain.model.ResumenEmpresaTurno
import com.example.domain.model.TrabajadorConEstado
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.DeepCobaltContainer
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricSkyBlue
import com.example.ui.theme.ElevatedNavySurface
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MonoBadgeStyle
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseError
import com.example.ui.theme.SlateMutedText
import com.example.ui.viewmodel.DescargaUiState
import java.io.File

@Composable
fun EmpresaSelectorDescargaCard(
    empresaSeleccionada: EmpresaOficial,
    resumenEmpresas: List<ResumenEmpresaTurno>,
    onSeleccionarEmpresa: (EmpresaOficial) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavySurface),
        border = BorderStroke(1.dp, BorderNavy),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "1. SELECCIONA LA EMPRESA A CONTROLAR",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                resumenEmpresas.forEach { res ->
                    val emp = res.empresa
                    val seleccionado = emp == empresaSeleccionada
                    val empColor = Color(emp.colorHex)
                    FilterChip(
                        selected = seleccionado,
                        onClick = { onSeleccionarEmpresa(emp) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(empColor)
                            )
                        },
                        label = {
                            Text(
                                text = "${emp.nombre} (${res.siBajoCount}/${res.totalTrabajadores})",
                                maxLines = 1
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = empColor.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            containerColor = DeepNavySurface,
                            labelColor = SlateMutedText
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = seleccionado,
                            selectedBorderColor = empColor,
                            borderColor = BorderNavy
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("descarga_empresa_chip_${emp.codigo}")
                    )
                }
            }
        }
    }
}

@Composable
fun ResumenBatchDescargaCard(
    uiState: DescargaUiState,
    onTomarFoto: () -> Unit,
    onElegirFotos: () -> Unit,
    onFinalizarConciliar: () -> Unit,
    onLimpiarRegistro: () -> Unit,
    modifier: Modifier = Modifier
) {
    val empColor = Color(uiState.empresaSeleccionada.colorHex)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavySurface),
        border = BorderStroke(1.dp, empColor.copy(alpha = 0.55f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${uiState.empresaSeleccionada.nombre.uppercase()} · ${uiState.turnoActivo.nombre.uppercase()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = empColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${uiState.trabajadoresEmpresa.size} en maestro · ${uiState.confirmadosEnFotosIds.size} detectados en fotos",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = DeepCobaltContainer,
                    border = BorderStroke(1.dp, ElectricSkyBlue.copy(alpha = 0.45f)),
                    modifier = Modifier.testTag("descarga_memoria_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Psychology,
                            contentDescription = "Correcciones OCR aprendidas",
                            tint = ElectricSkyBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Memoria: ${uiState.correccionesAprendidasCount}",
                            style = MonoBadgeStyle,
                            color = ElectricSkyBlue
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConteoEstadoPill(
                    titulo = "IDENTIFICADOS",
                    valor = uiState.confirmadosEnFotosIds.size,
                    color = EmeraldSuccess,
                    bg = EmeraldContainer,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("descarga_conteo_sibajo")
                )
                ConteoEstadoPill(
                    titulo = "SIN FOTO",
                    valor = (uiState.trabajadoresEmpresa.size - uiState.confirmadosEnFotosIds.size).coerceAtLeast(0),
                    color = SlateMutedText,
                    bg = DeepNavySurface,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("descarga_conteo_nobajo")
                )
                ConteoEstadoPill(
                    titulo = "PENDIENTES",
                    valor = uiState.pendientes.size,
                    color = if (uiState.pendientes.isNotEmpty()) AmberWarning else SlateMutedText,
                    bg = if (uiState.pendientes.isNotEmpty()) AmberContainer else DeepNavySurface,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("descarga_conteo_pendientes")
                )
            }

            if (uiState.procesandoOcr) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = ElectricSkyBlue
                    )
                    Text(
                        text = uiState.procesandoDetalle.ifBlank { "Procesando planilla offline en el dispositivo…" },
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricSkyBlue
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTomarFoto,
                    enabled = !uiState.procesandoOcr,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("descarga_tomar_foto_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("TOMAR FOTO", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onElegirFotos,
                    enabled = !uiState.procesandoOcr,
                    border = BorderStroke(1.dp, ElectricSkyBlue.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricSkyBlue),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("descarga_elegir_fotos_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ELEGIR FOTO(S)", fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onFinalizarConciliar,
                    enabled = uiState.puedeFinalizarConciliacion,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldSuccess,
                        contentColor = DeepNavySurface,
                        disabledContainerColor = ElevatedNavySurface,
                        disabledContentColor = SlateMutedText
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .heightIn(min = 48.dp)
                        .testTag("descarga_finalizar_conciliar_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.FactCheck,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("FINALIZAR Y CONCILIAR", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onLimpiarRegistro,
                    border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = AmberContainer.copy(alpha = 0.35f),
                        contentColor = AmberWarning
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("descarga_limpiar_registro_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "LIMPIAR",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            val bloqueoMotivo = when {
                uiState.tieneFotoIlegible -> "Hay fotografías con error o ilegibles. Toca editar (rotar/recortar) o descarta el aviso antes de conciliar."
                uiState.pendientes.isNotEmpty() -> "Resuelve o descarta los ${uiState.pendientes.size} nombres pendientes antes de conciliar la descarga."
                else -> null
            }
            if (bloqueoMotivo != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AmberContainer,
                    border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = AmberWarning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = bloqueoMotivo,
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConteoEstadoPill(
    titulo: String,
    valor: Int,
    color: Color,
    bg: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = valor.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = titulo,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FotoLoteChipCard(
    foto: FotoLoteDescargaUi,
    onEditarRecortar: () -> Unit,
    onDescartar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val esError = foto.estado == EstadoFotoDescarga.ERROR_ILEGIBLE
    val borderColor = if (esError) RoseError else EmeraldSuccess.copy(alpha = 0.6f)
    val bgColor = if (esError) RoseContainer else EmeraldContainer.copy(alpha = 0.55f)
    val iconColor = if (esError) RoseError else EmeraldSuccess

    var thumb by remember(foto.id, foto.archivoTrabajoPath, foto.ajuste.rotacionGrados) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(foto.archivoTrabajoPath, foto.ajuste.rotacionGrados) {
        val anterior = thumb
        val path = foto.archivoTrabajoPath
        val nuevo = if (!path.isNullOrBlank()) {
            OcrImageProcessor.cargarBitmapPreviewSeguro(
                archivoTrabajo = File(path),
                ajuste = foto.ajuste,
                maxDimPx = 160
            )
        } else {
            null
        }
        thumb = nuevo
        if (anterior != null && anterior !== nuevo && !anterior.isRecycled) {
            anterior.recycle()
        }
    }

    DisposableEffect(foto.id) {
        onDispose {
            thumb?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
            thumb = null
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .testTag("descarga_foto_card_${foto.id}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Miniatura de ${foto.etiqueta}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Icon(
                    imageVector = if (esError) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                    contentDescription = if (esError) "Error de lectura" else "Foto procesada",
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = foto.etiqueta,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = foto.mensajeDetalle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (esError) RoseError else SlateMutedText
                )
            }

            if (!foto.archivoTrabajoPath.isNullOrBlank()) {
                IconButton(
                    onClick = onEditarRecortar,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("editar_foto_${foto.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.CropRotate,
                        contentDescription = "Previsualizar, rotar o recortar ${foto.etiqueta}",
                        tint = ElectricSkyBlue
                    )
                }
            }

            IconButton(
                onClick = onDescartar,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("descartar_foto_${foto.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Descartar fotografía del lote",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResumenIdentificadosDescargaCard(
    empresa: EmpresaOficial,
    identificados: List<TrabajadorConEstado>,
    totalEmpresa: Int,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavySurface),
        border = BorderStroke(1.dp, BorderNavy),
        modifier = modifier
            .fillMaxWidth()
            .testTag("descarga_resumen_identificados")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "IDENTIFICADOS EN FOTOGRAFÍAS (${identificados.size}/$totalEmpresa)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = EmeraldSuccess
                )
            }

            if (identificados.isEmpty()) {
                Text(
                    text = "Aún no hay trabajadores identificados desde fotografías en ${empresa.nombre}. " +
                        "Toma o selecciona una foto de la planilla; una vez conciliada, los resultados se reflejarán en Listado y Empresas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateMutedText
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    identificados.forEach { w ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = EmeraldContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.5f)),
                            modifier = Modifier.testTag("descarga_identificado_chip_${w.id}")
                        ) {
                            Text(
                                text = "${w.nombreCompleto} (${w.habitacionMayusculas.ifBlank { "-" }})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
