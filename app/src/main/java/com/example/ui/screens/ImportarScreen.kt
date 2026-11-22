package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.data.importer.FormatoArchivo
import com.example.domain.model.AccionImportacion
import com.example.domain.model.CampoImportacion
import com.example.domain.model.ClasificacionImportacion
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.FilaImportacionPreview
import com.example.domain.model.ResultadoImportacion
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.Turno
import com.example.ui.components.CompanyBadge
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MonoBadgeStyle
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseError
import com.example.ui.theme.SlateMutedText
import com.example.ui.viewmodel.FiltroImportacion
import com.example.ui.viewmodel.ImportacionUiState
import com.example.ui.viewmodel.PasoImportacion

@Composable
fun ImportarScreen(
    uiState: ImportacionUiState,
    turnoActivo: Turno,
    onVolver: () -> Unit,
    onCargarArchivo: (nombre: String, abrirStream: () -> java.io.InputStream?, mime: String?, turnoActivo: Turno) -> Unit,
    onSeleccionarHoja: (Int) -> Unit,
    onActualizarMapeo: (CampoImportacion, Int?) -> Unit,
    onCambiarModoTurno: (usarColumnaArchivo: Boolean, turnoPorDefecto: Turno) -> Unit,
    onCambiarFiltroPreview: (FiltroImportacion) -> Unit,
    onToggleIncluirFila: (Int) -> Unit,
    onResolverRevisionFila: (Int, AccionImportacion) -> Unit,
    onCorregirFila: (
        numeroFila: Int,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        pabellon: String,
        empresa: EmpresaOficial?,
        turno: Turno?
    ) -> Unit,
    onConfirmarImportacion: ((ResultadoImportacion) -> Unit) -> Unit,
    onReiniciar: () -> Unit,
    onImportacionExitosaMensaje: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var filaParaCorregir by remember { mutableStateOf<FilaImportacionPreview?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val nombre = resolverNombreArchivo(context, uri)
            val mime = context.contentResolver.getType(uri)
            onCargarArchivo(
                nombre,
                { context.contentResolver.openInputStream(uri) },
                mime,
                turnoActivo
            )
        }
    }

    BackHandler {
        if (uiState.paso == PasoImportacion.MAPEO_Y_REVISION) {
            onReiniciar()
        } else {
            onVolver()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        when (uiState.paso) {
            PasoImportacion.SELECCION_ARCHIVO -> {
                SeleccionArchivoContent(
                    uiState = uiState,
                    turnoActivo = turnoActivo,
                    onVolver = onVolver,
                    onSeleccionarClick = {
                        val mimes = when (uiState.formatoPreferido) {
                            FormatoArchivo.XLSX -> arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel"
                            )
                            FormatoArchivo.CSV -> arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "application/csv",
                                "text/plain"
                            )
                            null -> arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "text/csv",
                                "text/comma-separated-values",
                                "application/csv",
                                "text/plain"
                            )
                        }
                        launcher.launch(mimes)
                    }
                )
            }

            PasoImportacion.MAPEO_Y_REVISION -> {
                MapeoYRevisionContent(
                    uiState = uiState,
                    onSeleccionarHoja = onSeleccionarHoja,
                    onActualizarMapeo = onActualizarMapeo,
                    onCambiarModoTurno = onCambiarModoTurno,
                    onCambiarFiltroPreview = onCambiarFiltroPreview,
                    onToggleIncluirFila = onToggleIncluirFila,
                    onResolverRevisionFila = onResolverRevisionFila,
                    onEditarFila = { filaParaCorregir = it },
                    onReiniciar = onReiniciar,
                    onConfirmar = {
                        onConfirmarImportacion { resultado ->
                            onImportacionExitosaMensaje(
                                buildString {
                                    append("Importación al maestro: ")
                                    append("${resultado.insertados} nuevos")
                                    if (resultado.actualizados > 0) {
                                        append(", ${resultado.actualizados} actualizados")
                                    }
                                    if (resultado.omitidos > 0) {
                                        append(", ${resultado.omitidos} omitidos")
                                    }
                                    append(".")
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    filaParaCorregir?.let { fila ->
        CorregirFilaDialog(
            fila = fila,
            onDismiss = { filaParaCorregir = null },
            onGuardar = { nom, ap1, ap2, hab, pab, emp, turno ->
                onCorregirFila(fila.numeroFila, nom, ap1, ap2, hab, pab, emp, turno)
                filaParaCorregir = null
            }
        )
    }
}

@Composable
private fun SeleccionArchivoContent(
    uiState: ImportacionUiState,
    turnoActivo: Turno,
    onVolver: () -> Unit,
    onSeleccionarClick: () -> Unit
) {
    val tituloFormato = when (uiState.formatoPreferido) {
        FormatoArchivo.XLSX -> "Importar desde Excel (.xlsx)"
        FormatoArchivo.CSV -> "Importar desde CSV (.csv)"
        null -> "Importar Excel o CSV"
    }
    val subtituloFormato = when (uiState.formatoPreferido) {
        FormatoArchivo.XLSX -> "Integración directa al registro maestro desde planillas Excel (.xlsx)"
        FormatoArchivo.CSV -> "Integración directa al registro maestro desde archivos CSV"
        null -> "Formatos compatibles: .xlsx y .csv"
    }
    val botonFormato = when (uiState.formatoPreferido) {
        FormatoArchivo.XLSX -> "Seleccionar archivo Excel (.xlsx)"
        FormatoArchivo.CSV -> "Seleccionar archivo CSV (.csv)"
        null -> "Seleccionar archivo (.xlsx / .csv)"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = CardNavySurface,
            border = BorderStroke(1.dp, BorderNavy),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onVolver,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("volver_desde_importar_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver a Ajustes"
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.FileUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tituloFormato,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtituloFormato,
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateMutedText
                        )
                    }
                }

                Text(
                    text = "Antes de confirmar la carga, podrá mapear columnas, revisar registros nuevos, existentes o ambiguos y validar las ocho empresas oficiales sin duplicar ni borrar trabajadores existentes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onSeleccionarClick,
                    enabled = !uiState.cargando,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("seleccionar_archivo_button")
                ) {
                    if (uiState.cargando) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Analizando archivo…")
                    } else {
                        Icon(Icons.Filled.Description, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = botonFormato,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Si el archivo no incluye columna Turno, se asignará por defecto al ${turnoActivo.nombre} activo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (uiState.errorMensaje != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = RoseContainer,
                border = BorderStroke(1.dp, RoseError.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = RoseError)
                    Text(
                        text = uiState.errorMensaje,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (uiState.resultadoImportacion != null) {
            val res = uiState.resultadoImportacion
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = EmeraldContainer,
                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = EmeraldSuccess)
                    Column {
                        Text(
                            text = "Importación completada al registro maestro",
                            style = MaterialTheme.typography.titleSmall,
                            color = EmeraldSuccess
                        )
                        Text(
                            text = "Nuevos: ${res.insertados} (A: +${res.insertadosTurnoA}, B: +${res.insertadosTurnoB}) · Actualizados: ${res.actualizados} · Omitidos: ${res.omitidos}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Guía de columnas esperadas
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardNavySurface,
            border = BorderStroke(1.dp, BorderNavy),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Columnas reconocidas automáticamente",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                CampoImportacion.entries.forEach { campo ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = campo.etiqueta,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (campo.obligatorio) "Obligatorio" else "Opcional",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (campo.obligatorio) MaterialTheme.colorScheme.primary else SlateMutedText
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapeoYRevisionContent(
    uiState: ImportacionUiState,
    onSeleccionarHoja: (Int) -> Unit,
    onActualizarMapeo: (CampoImportacion, Int?) -> Unit,
    onCambiarModoTurno: (Boolean, Turno) -> Unit,
    onCambiarFiltroPreview: (FiltroImportacion) -> Unit,
    onToggleIncluirFila: (Int) -> Unit,
    onResolverRevisionFila: (Int, AccionImportacion) -> Unit,
    onEditarFila: (FilaImportacionPreview) -> Unit,
    onReiniciar: () -> Unit,
    onConfirmar: () -> Unit
) {
    val archivo = uiState.archivo ?: return
    val hoja = uiState.hojaActual ?: return
    var mostrarConfigColumnas by remember { mutableStateOf(true) }

    val filasFiltradas = remember(uiState.filasPreview, uiState.filtroPreview) {
        when (uiState.filtroPreview) {
            FiltroImportacion.TODAS -> uiState.filasPreview
            FiltroImportacion.VALIDAS -> uiState.filasPreview.filter {
                !it.tieneErrorBloqueante && it.clasificacion == ClasificacionImportacion.NUEVO
            }
            FiltroImportacion.CON_ALERTAS -> uiState.filasPreview.filter {
                it.tieneErrorBloqueante || it.tieneAdvertencia
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 840.dp)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "cabecera_archivo") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardNavySurface,
                    border = BorderStroke(1.dp, BorderNavy),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = archivo.nombreArchivo,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${hoja.filas.size} filas detectadas",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateMutedText
                                )
                            }
                            OutlinedButton(
                                onClick = onReiniciar,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cambiar")
                            }
                        }

                        if (archivo.hojas.size > 1) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                archivo.hojas.forEachIndexed { idx, h ->
                                    FilterChip(
                                        selected = idx == uiState.hojaSeleccionadaIdx,
                                        onClick = { onSeleccionarHoja(idx) },
                                        label = { Text(h.nombre) }
                                    )
                                }
                            }
                        }

                        // Modo de asignación de turno
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Usar columna Turno del archivo",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (uiState.usarColumnaTurnoArchivo && uiState.mapeo.indiceTurno != null) {
                                        "Cada fila se asignará según su columna Turno"
                                    } else {
                                        "Todas las filas se asignarán a ${uiState.turnoDestinoPorDefecto.nombre}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateMutedText
                                )
                            }
                            Switch(
                                checked = uiState.usarColumnaTurnoArchivo && uiState.mapeo.indiceTurno != null,
                                enabled = uiState.mapeo.indiceTurno != null,
                                onCheckedChange = { checked ->
                                    onCambiarModoTurno(checked, uiState.turnoDestinoPorDefecto)
                                }
                            )
                        }

                        if (!uiState.usarColumnaTurnoArchivo || uiState.mapeo.indiceTurno == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Turno.entries.forEach { turno ->
                                    FilterChip(
                                        selected = uiState.turnoDestinoPorDefecto == turno,
                                        onClick = { onCambiarModoTurno(false, turno) },
                                        label = { Text("Asignar a ${turno.nombre}") }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "mapeo_columnas") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardNavySurface,
                    border = BorderStroke(1.dp, BorderNavy),
                    modifier = Modifier.fillMaxWidth()
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
                            Text(
                                text = "Mapeo de columnas",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedButton(
                                onClick = { mostrarConfigColumnas = !mostrarConfigColumnas },
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) {
                                Text(if (mostrarConfigColumnas) "Ocultar" else "Configurar")
                            }
                        }

                        if (mostrarConfigColumnas) {
                            CampoImportacion.entries.forEach { campo ->
                                ColumnaSelectorRow(
                                    campo = campo,
                                    encabezados = hoja.encabezados,
                                    indiceActual = uiState.mapeo.obtenerIndice(campo),
                                    onSeleccionar = { idx -> onActualizarMapeo(campo, idx) }
                                )
                            }
                        }
                    }
                }
            }

            item(key = "resumen_validacion") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = EmeraldContainer,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Nuevos: ${uiState.totalNuevos}",
                                style = MaterialTheme.typography.labelLarge,
                                color = EmeraldSuccess,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = AmberContainer,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Revisión: ${uiState.totalRequierenRevision + uiState.totalPosiblesActualizaciones}",
                                style = MaterialTheme.typography.labelLarge,
                                color = AmberWarning,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = RoseContainer,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Errores: ${uiState.totalConErrores}",
                                style = MaterialTheme.typography.labelLarge,
                                color = RoseError,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FiltroImportacion.entries.forEach { filtro ->
                            FilterChip(
                                selected = uiState.filtroPreview == filtro,
                                onClick = { onCambiarFiltroPreview(filtro) },
                                label = { Text(filtro.etiqueta) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            items(items = filasFiltradas, key = { it.numeroFila }) { fila ->
                FilaPreviewCard(
                    fila = fila,
                    onToggleIncluir = { onToggleIncluirFila(fila.numeroFila) },
                    onResolverRevision = { accion -> onResolverRevisionFila(fila.numeroFila, accion) },
                    onCorregir = { onEditarFila(fila) }
                )
            }
        }

        // Barra inferior fija para confirmar importación
        Surface(
            color = CardNavySurface,
            border = BorderStroke(1.dp, BorderNavy),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${uiState.totalIncluidas} operaciones listas",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (uiState.totalRevisionPendiente > 0) {
                            "${uiState.totalRevisionPendiente} por confirmar · No borra existentes"
                        } else {
                            "Se integrará al registro maestro sin duplicar"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                }
                Button(
                    onClick = onConfirmar,
                    enabled = uiState.totalIncluidas > 0 && !uiState.cargando,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .testTag("confirmar_importacion_button")
                ) {
                    Text("Confirmar importación", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnaSelectorRow(
    campo: CampoImportacion,
    encabezados: List<String>,
    indiceActual: Int?,
    onSeleccionar: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val textoActual = indiceActual?.let { encabezados.getOrNull(it) } ?: "Sin asignar"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (campo.obligatorio) "${campo.etiqueta} *" else campo.etiqueta,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.45f)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.weight(0.55f)
        ) {
            OutlinedTextField(
                value = textoActual,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Sin asignar") },
                    onClick = {
                        onSeleccionar(null)
                        expanded = false
                    }
                )
                encabezados.forEachIndexed { idx, col ->
                    DropdownMenuItem(
                        text = { Text(col) },
                        onClick = {
                            onSeleccionar(idx)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilaPreviewCard(
    fila: FilaImportacionPreview,
    onToggleIncluir: () -> Unit,
    onResolverRevision: (AccionImportacion) -> Unit,
    onCorregir: () -> Unit
) {
    val border = when {
        fila.tieneErrorBloqueante -> RoseError.copy(alpha = 0.65f)
        fila.clasificacion == ClasificacionImportacion.REQUIERE_REVISION ||
            fila.clasificacion == ClasificacionImportacion.EXISTENTE_CON_CAMBIOS ->
            AmberWarning.copy(alpha = 0.65f)
        fila.clasificacion == ClasificacionImportacion.YA_EXISTE_IDENTICO -> BorderNavy
        else -> EmeraldSuccess.copy(alpha = 0.45f)
    }

    val badgeBg = when (fila.clasificacion) {
        ClasificacionImportacion.NUEVO -> EmeraldContainer
        ClasificacionImportacion.YA_EXISTE_IDENTICO -> CardNavySurface
        ClasificacionImportacion.EXISTENTE_CON_CAMBIOS,
        ClasificacionImportacion.REQUIERE_REVISION -> AmberContainer
        ClasificacionImportacion.ERROR_DATOS -> RoseContainer
    }
    val badgeFg = when (fila.clasificacion) {
        ClasificacionImportacion.NUEVO -> EmeraldSuccess
        ClasificacionImportacion.YA_EXISTE_IDENTICO -> SlateMutedText
        ClasificacionImportacion.EXISTENTE_CON_CAMBIOS,
        ClasificacionImportacion.REQUIERE_REVISION -> AmberWarning
        ClasificacionImportacion.ERROR_DATOS -> RoseError
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavySurface),
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("import_preview_fila_${fila.numeroFila}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = fila.seraProcesada,
                    enabled = !fila.tieneErrorBloqueante &&
                        fila.clasificacion != ClasificacionImportacion.YA_EXISTE_IDENTICO,
                    onCheckedChange = { onToggleIncluir() }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Fila ${fila.numeroFila} · ${fila.nombreCompletoPreview}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    val habUp = TextoNormalizer.formatearMayusculas(fila.habitacion).ifBlank { "—" }
                    val pabUp = TextoNormalizer.formatearMayusculas(fila.pabellon).ifBlank { "—" }
                    Text(
                        text = "Hab. $habUp · Pab. $pabUp",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeFg.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = fila.clasificacion.etiqueta.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                fila.turnoResuelto?.let { t ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = t.nombre,
                            style = MonoBadgeStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (fila.empresaResuelta != null) {
                    CompanyBadge(empresa = fila.empresaResuelta)
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = RoseContainer
                    ) {
                        Text(
                            text = "Empresa desconocida: ${fila.empresaTextoOriginal.ifBlank { "(vacía)" }}",
                            style = MaterialTheme.typography.labelMedium,
                            color = RoseError,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                OutlinedButton(
                    onClick = onCorregir,
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Corregir")
                }
            }

            fila.candidatoExistente?.let { cand ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "En maestro: ${cand.nombreCompletoMayusculas} · Hab. ${cand.habitacionMayusculas}" +
                            (cand.empresa?.let { " · ${it.nombre}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            fila.problemas.forEach { problema ->
                val esBloq = problema.tipo.bloqueantePorDefecto
                val bg = if (esBloq) RoseContainer else AmberContainer
                val fg = if (esBloq) RoseError else AmberWarning
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = bg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (esBloq) Icons.Filled.Error else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${problema.tipo.etiqueta}: ${problema.detalle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Resolución explícita para posibles existentes con cambios o coincidencias ambiguas
            if (!fila.tieneErrorBloqueante &&
                (fila.clasificacion == ClasificacionImportacion.EXISTENTE_CON_CAMBIOS ||
                    fila.clasificacion == ClasificacionImportacion.REQUIERE_REVISION)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (fila.candidatoExistente != null) {
                        FilterChip(
                            selected = fila.revisionResuelta && fila.accion == AccionImportacion.ACTUALIZAR_EXISTENTE,
                            onClick = { onResolverRevision(AccionImportacion.ACTUALIZAR_EXISTENTE) },
                            label = { Text("Actualizar existente") },
                            modifier = Modifier.testTag("resolver_actualizar_${fila.numeroFila}")
                        )
                    }
                    FilterChip(
                        selected = fila.revisionResuelta && fila.accion == AccionImportacion.AGREGAR_NUEVO,
                        onClick = { onResolverRevision(AccionImportacion.AGREGAR_NUEVO) },
                        label = { Text("Agregar como nuevo") },
                        modifier = Modifier.testTag("resolver_nuevo_${fila.numeroFila}")
                    )
                    FilterChip(
                        selected = fila.revisionResuelta && fila.accion == AccionImportacion.OMITIR,
                        onClick = { onResolverRevision(AccionImportacion.OMITIR) },
                        label = { Text("Omitir") },
                        modifier = Modifier.testTag("resolver_omitir_${fila.numeroFila}")
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorregirFilaDialog(
    fila: FilaImportacionPreview,
    onDismiss: () -> Unit,
    onGuardar: (
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        pabellon: String,
        empresa: EmpresaOficial?,
        turno: Turno?
    ) -> Unit
) {
    var nombre by remember { mutableStateOf(fila.nombre) }
    var primerApellido by remember { mutableStateOf(fila.primerApellido) }
    var segundoApellido by remember { mutableStateOf(fila.segundoApellido) }
    var mostrarSegundoApellido by remember { mutableStateOf(fila.segundoApellido.isNotBlank()) }
    var habitacion by remember { mutableStateOf(fila.habitacion) }
    var pabellon by remember { mutableStateOf(fila.pabellon) }
    var empresa by remember { mutableStateOf(fila.empresaResuelta) }
    var turno by remember { mutableStateOf(fila.turnoResuelto ?: Turno.A) }
    var expEmpresa by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
            .padding(horizontal = 20.dp),
        containerColor = CardNavySurface,
        title = { Text("Corregir fila ${fila.numeroFila}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text("Nombre *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = primerApellido,
                    onValueChange = { primerApellido = it },
                    label = { Text("Primer apellido *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (mostrarSegundoApellido) {
                    OutlinedTextField(
                        value = segundoApellido,
                        onValueChange = { segundoApellido = it },
                        label = { Text("Segundo apellido (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedButton(
                        onClick = { mostrarSegundoApellido = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("+ AGREGAR SEGUNDO APELLIDO")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = habitacion,
                        onValueChange = { habitacion = it },
                        label = { Text("Habitación") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pabellon,
                        onValueChange = { pabellon = it },
                        label = { Text("Pabellón") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = expEmpresa,
                    onExpandedChange = { expEmpresa = !expEmpresa }
                ) {
                    OutlinedTextField(
                        value = empresa?.nombre ?: "Seleccionar empresa *",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Empresa oficial *") },
                        leadingIcon = empresa?.let { emp ->
                            {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(emp.colorHex))
                                )
                            }
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expEmpresa) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expEmpresa,
                        onDismissRequest = { expEmpresa = false }
                    ) {
                        EmpresaOficial.todas.forEach { op ->
                            val opColor = Color(op.colorHex)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(opColor)
                                        )
                                        Text(op.nombre, color = opColor, fontWeight = FontWeight.SemiBold)
                                    }
                                },
                                onClick = {
                                    empresa = op
                                    expEmpresa = false
                                }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Turno.entries.forEach { t ->
                        FilterChip(
                            selected = turno == t,
                            onClick = { turno = t },
                            label = { Text(t.nombre) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onGuardar(
                        nombre,
                        primerApellido,
                        if (mostrarSegundoApellido) segundoApellido else "",
                        habitacion,
                        pabellon,
                        empresa,
                        turno
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Aplicar")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancelar")
            }
        }
    )
}

private fun resolverNombreArchivo(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
                val nombre = cursor.getString(idx)
                if (!nombre.isNullOrBlank()) return nombre
            }
        }
    }
    return uri.lastPathSegment ?: "listado.xlsx"
}
