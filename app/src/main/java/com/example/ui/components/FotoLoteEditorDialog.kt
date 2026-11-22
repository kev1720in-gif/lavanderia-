package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ocr.OcrImageProcessor
import com.example.domain.model.AjusteFotoPlanilla
import com.example.domain.model.FotoLoteDescargaUi
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricSkyBlue
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SlateMutedText
import java.io.File
import kotlin.math.hypot
import kotlin.math.min

private enum class ModoEditorFoto {
    RECORTAR,
    ZOOM
}

private enum class BordeRecorteActivo {
    NINGUNO,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    MOVER_CUADRO
}

@Composable
fun FotoLoteEditorDialog(
    foto: FotoLoteDescargaUi,
    onAplicarYReprocesar: (AjusteFotoPlanilla) -> Unit,
    onCerrar: () -> Unit
) {
    var rotacion by remember(foto.id) { mutableIntStateOf(foto.ajuste.rotacionGrados) }
    var cropLeft by remember(foto.id) { mutableFloatStateOf(foto.ajuste.cropLeft) }
    var cropTop by remember(foto.id) { mutableFloatStateOf(foto.ajuste.cropTop) }
    var cropRight by remember(foto.id) { mutableFloatStateOf(foto.ajuste.cropRight) }
    var cropBottom by remember(foto.id) { mutableFloatStateOf(foto.ajuste.cropBottom) }

    var modo by remember(foto.id) { mutableStateOf(ModoEditorFoto.RECORTAR) }
    var zoomScale by remember(foto.id) { mutableFloatStateOf(1f) }
    var panOffset by remember(foto.id) { mutableStateOf(Offset.Zero) }

    var bitmapPreview by remember(foto.id) { mutableStateOf<Bitmap?>(null) }
    var cargando by remember(foto.id) { mutableStateOf(true) }

    LaunchedEffect(foto.archivoTrabajoPath, rotacion) {
        cargando = true
        val anterior = bitmapPreview
        val path = foto.archivoTrabajoPath
        val nuevo = if (!path.isNullOrBlank()) {
            OcrImageProcessor.cargarBitmapPreviewSeguro(
                archivoTrabajo = File(path),
                ajuste = AjusteFotoPlanilla(rotacionGrados = rotacion),
                maxDimPx = 1400
            )
        } else {
            null
        }
        bitmapPreview = nuevo
        if (anterior != null && anterior !== nuevo && !anterior.isRecycled) {
            anterior.recycle()
        }
        cargando = false
    }

    DisposableEffect(foto.id) {
        onDispose {
            bitmapPreview?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
            bitmapPreview = null
        }
    }

    Dialog(
        onDismissRequest = onCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = ObsidianBackground,
            modifier = Modifier
                .fillMaxSize()
                .testTag("foto_editor_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = foto.etiqueta,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (modo == ModoEditorFoto.RECORTAR) {
                                "Arrastra las esquinas para encuadrar solo los nombres de la planilla"
                            } else {
                                "Usa zoom para revisar nombres manuscritos"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateMutedText
                        )
                    }
                    IconButton(
                        onClick = onCerrar,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("foto_editor_cerrar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar editor de fotografía",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Selector de modo (Recortar vs Zoom) y rotación rápida
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = modo == ModoEditorFoto.RECORTAR,
                        onClick = {
                            modo = ModoEditorFoto.RECORTAR
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Recortar") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            containerColor = CardNavySurface,
                            labelColor = SlateMutedText
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    )

                    FilterChip(
                        selected = modo == ModoEditorFoto.ZOOM,
                        onClick = { modo = ModoEditorFoto.ZOOM },
                        leadingIcon = {
                            Icon(Icons.Filled.ZoomIn, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Zoom") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            containerColor = CardNavySurface,
                            labelColor = SlateMutedText
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    OutlinedButton(
                        onClick = { rotacion = ((rotacion - 90) % 360 + 360) % 360 },
                        border = BorderStroke(1.dp, BorderNavy),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("foto_editor_rotar_izq")
                    ) {
                        Icon(Icons.Filled.RotateLeft, contentDescription = "Rotar 90 grados a la izquierda")
                    }

                    OutlinedButton(
                        onClick = { rotacion = (rotacion + 90) % 360 },
                        border = BorderStroke(1.dp, BorderNavy),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("foto_editor_rotar_der")
                    ) {
                        Icon(Icons.Filled.RotateRight, contentDescription = "Rotar 90 grados a la derecha")
                    }
                }

                // Lienzo central de vista previa / recorte / zoom
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = DeepNavySurface,
                    border = BorderStroke(1.dp, BorderNavy),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    val bmp = bitmapPreview
                    if (cargando) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = ElectricSkyBlue)
                        }
                    } else if (bmp == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No se pudo previsualizar la imagen temporal.",
                                color = SlateMutedText
                            )
                        }
                    } else {
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                            val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                            val scaleFit = min(boxW / bmp.width.toFloat(), boxH / bmp.height.toFloat())
                            val dispW = bmp.width.toFloat() * scaleFit
                            val dispH = bmp.height.toFloat() * scaleFit
                            val dispLeft = (boxW - dispW) / 2f
                            val dispTop = (boxH - dispH) / 2f

                            var handleActivo by remember { mutableStateOf(BordeRecorteActivo.NINGUNO) }

                            val gestureModifier = if (modo == ModoEditorFoto.ZOOM) {
                                Modifier.pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val nextZoom = (zoomScale * zoom).coerceIn(1f, 4.5f)
                                        zoomScale = nextZoom
                                        panOffset = if (nextZoom <= 1.02f) {
                                            Offset.Zero
                                        } else {
                                            Offset(
                                                x = (panOffset.x + pan.x).coerceIn(-boxW * 0.6f, boxW * 0.6f),
                                                y = (panOffset.y + pan.y).coerceIn(-boxH * 0.6f, boxH * 0.6f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Modifier.pointerInput(dispW, dispH, dispLeft, dispTop) {
                                    detectDragGestures(
                                        onDragStart = { pos ->
                                            val xL = dispLeft + cropLeft * dispW
                                            val yT = dispTop + cropTop * dispH
                                            val xR = dispLeft + cropRight * dispW
                                            val yB = dispTop + cropBottom * dispH
                                            val hitR = 64f
                                            handleActivo = when {
                                                hypot(pos.x - xL, pos.y - yT) <= hitR -> BordeRecorteActivo.TOP_LEFT
                                                hypot(pos.x - xR, pos.y - yT) <= hitR -> BordeRecorteActivo.TOP_RIGHT
                                                hypot(pos.x - xL, pos.y - yB) <= hitR -> BordeRecorteActivo.BOTTOM_LEFT
                                                hypot(pos.x - xR, pos.y - yB) <= hitR -> BordeRecorteActivo.BOTTOM_RIGHT
                                                pos.x in xL..xR && pos.y in yT..yB -> BordeRecorteActivo.MOVER_CUADRO
                                                else -> BordeRecorteActivo.NINGUNO
                                            }
                                        },
                                        onDragEnd = { handleActivo = BordeRecorteActivo.NINGUNO },
                                        onDragCancel = { handleActivo = BordeRecorteActivo.NINGUNO },
                                        onDrag = { change, drag ->
                                            change.consume()
                                            val dx = drag.x / dispW
                                            val dy = drag.y / dispH
                                            val minSize = 0.14f
                                            when (handleActivo) {
                                                BordeRecorteActivo.TOP_LEFT -> {
                                                    cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - minSize)
                                                    cropTop = (cropTop + dy).coerceIn(0f, cropBottom - minSize)
                                                }
                                                BordeRecorteActivo.TOP_RIGHT -> {
                                                    cropRight = (cropRight + dx).coerceIn(cropLeft + minSize, 1f)
                                                    cropTop = (cropTop + dy).coerceIn(0f, cropBottom - minSize)
                                                }
                                                BordeRecorteActivo.BOTTOM_LEFT -> {
                                                    cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - minSize)
                                                    cropBottom = (cropBottom + dy).coerceIn(cropTop + minSize, 1f)
                                                }
                                                BordeRecorteActivo.BOTTOM_RIGHT -> {
                                                    cropRight = (cropRight + dx).coerceIn(cropLeft + minSize, 1f)
                                                    cropBottom = (cropBottom + dy).coerceIn(cropTop + minSize, 1f)
                                                }
                                                BordeRecorteActivo.MOVER_CUADRO -> {
                                                    val wFrac = cropRight - cropLeft
                                                    val hFrac = cropBottom - cropTop
                                                    val nl = (cropLeft + dx).coerceIn(0f, 1f - wFrac)
                                                    val nt = (cropTop + dy).coerceIn(0f, 1f - hFrac)
                                                    cropLeft = nl
                                                    cropRight = nl + wFrac
                                                    cropTop = nt
                                                    cropBottom = nt + hFrac
                                                }
                                                BordeRecorteActivo.NINGUNO -> Unit
                                            }
                                        }
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(gestureModifier)
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Vista previa de planilla manuscrita",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            if (modo == ModoEditorFoto.ZOOM) {
                                                scaleX = zoomScale
                                                scaleY = zoomScale
                                                translationX = panOffset.x
                                                translationY = panOffset.y
                                            }
                                        }
                                )

                                if (modo == ModoEditorFoto.RECORTAR) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val cLeft = dispLeft + cropLeft * dispW
                                        val cTop = dispTop + cropTop * dispH
                                        val cRight = dispLeft + cropRight * dispW
                                        val cBottom = dispTop + cropBottom * dispH
                                        val cW = (cRight - cLeft).coerceAtLeast(4f)
                                        val cH = (cBottom - cTop).coerceAtLeast(4f)

                                        val shade = Color.Black.copy(alpha = 0.52f)
                                        drawRect(shade, Offset(dispLeft, dispTop), Size(dispW, (cTop - dispTop).coerceAtLeast(0f)))
                                        drawRect(shade, Offset(dispLeft, cBottom), Size(dispW, (dispTop + dispH - cBottom).coerceAtLeast(0f)))
                                        drawRect(shade, Offset(dispLeft, cTop), Size((cLeft - dispLeft).coerceAtLeast(0f), cH))
                                        drawRect(shade, Offset(cRight, cTop), Size((dispLeft + dispW - cRight).coerceAtLeast(0f), cH))

                                        drawRect(
                                            color = ElectricSkyBlue,
                                            topLeft = Offset(cLeft, cTop),
                                            size = Size(cW, cH),
                                            style = Stroke(width = 3.dp.toPx())
                                        )

                                        val handleR = 10.dp.toPx()
                                        val corners = listOf(
                                            Offset(cLeft, cTop),
                                            Offset(cRight, cTop),
                                            Offset(cLeft, cBottom),
                                            Offset(cRight, cBottom)
                                        )
                                        corners.forEach { pt ->
                                            drawCircle(color = ElectricSkyBlue, radius = handleR, center = pt)
                                            drawCircle(color = DeepNavySurface, radius = handleR * 0.45f, center = pt)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (modo == ModoEditorFoto.ZOOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Zoom ${(zoomScale * 10).toInt() / 10.0}x",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricSkyBlue
                        )
                        Slider(
                            value = zoomScale,
                            onValueChange = { zoomScale = it },
                            valueRange = 1f..4.5f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            rotacion = 0
                            cropLeft = 0f
                            cropTop = 0f
                            cropRight = 1f
                            cropBottom = 1f
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        },
                        border = BorderStroke(1.dp, BorderNavy),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("foto_editor_reset_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restablecer")
                    }

                    Button(
                        onClick = {
                            onAplicarYReprocesar(
                                AjusteFotoPlanilla(
                                    rotacionGrados = rotacion,
                                    cropLeft = cropLeft,
                                    cropTop = cropTop,
                                    cropRight = cropRight,
                                    cropBottom = cropBottom
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldSuccess,
                            contentColor = DeepNavySurface
                        ),
                        modifier = Modifier
                            .weight(1.4f)
                            .heightIn(min = 48.dp)
                            .testTag("foto_editor_reprocesar_button")
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("APLICAR Y LEER OCR", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
