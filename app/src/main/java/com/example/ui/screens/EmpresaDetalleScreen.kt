package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.AvisoDuplicado
import com.example.domain.model.BuscadorTrabajadores
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.HardwarePerformanceConfig
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.ui.components.WorkerFormDialog
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElevatedNavySurface
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.ui.theme.SlateMutedText
import com.example.ui.viewmodel.FiltroEstadoMarcacion
import com.example.ui.viewmodel.MainUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun EmpresaDetalleScreen(
    empresa: EmpresaOficial,
    uiState: MainUiState,
    onVolver: () -> Unit,
    onAlternarSiBajo: (TrabajadorConEstado) -> Unit,
    onVerificarDuplicado: suspend (
        turno: Turno,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        empresa: EmpresaOficial,
        excluirId: String?
    ) -> AvisoDuplicado?,
    onGuardarTrabajador: (
        trabajadorExistenteId: String?,
        turno: Turno,
        empresa: EmpresaOficial,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        pabellon: String
    ) -> Unit,
    onEliminarTrabajador: (TrabajadorConEstado) -> Unit,
    onMostrarMensaje: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onVolver)

    val context = LocalContext.current
    val accent = Color(empresa.colorHex)

    var busquedaLocal by remember { mutableStateOf("") }
    var filtroEstado by remember { mutableStateOf(FiltroEstadoMarcacion.TODOS) }
    var mostrarFormulario by remember { mutableStateOf(false) }
    var trabajadorEditar by remember { mutableStateOf<TrabajadorConEstado?>(null) }
    var trabajadorEliminar by remember { mutableStateOf<TrabajadorConEstado?>(null) }

    // Lista exclusiva de esta empresa en el turno activo desde la fuente maestra única, ordenada por habitación
    val trabajadoresEmpresaOrdenados = remember(uiState.trabajadoresTurno, empresa) {
        val deEmpresa = uiState.trabajadoresTurno.filter { it.empresa == empresa }
        TextoNormalizer.ordenarPorHabitacion(deEmpresa)
    }

    val siBajoEmpresaCount = remember(trabajadoresEmpresaOrdenados) {
        trabajadoresEmpresaOrdenados.count { it.siBajo }
    }
    val noBajoEmpresaCount = trabajadoresEmpresaOrdenados.size - siBajoEmpresaCount

    var busquedaDebounced by remember { mutableStateOf("") }
    LaunchedEffect(busquedaLocal) {
        if (busquedaLocal.isBlank()) {
            busquedaDebounced = ""
        } else {
            delay(HardwarePerformanceConfig.searchDebounceMs)
            busquedaDebounced = busquedaLocal
        }
    }

    val trabajadoresVisibles by produceState(
        initialValue = trabajadoresEmpresaOrdenados,
        trabajadoresEmpresaOrdenados,
        busquedaDebounced,
        filtroEstado
    ) {
        value = withContext(Dispatchers.Default) {
            val porEstado = if (filtroEstado == FiltroEstadoMarcacion.TODOS) {
                trabajadoresEmpresaOrdenados
            } else {
                trabajadoresEmpresaOrdenados.filter { t ->
                    when (filtroEstado) {
                        FiltroEstadoMarcacion.TODOS -> true
                        FiltroEstadoMarcacion.SI_BAJO -> t.siBajo
                        FiltroEstadoMarcacion.NO_BAJO -> !t.siBajo
                    }
                }
            }
            BuscadorTrabajadores.filtrarYOrdenar(porEstado, busquedaDebounced)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 960.dp)
                .testTag("empresa_detalle_table"),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "cabecera_empresa") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Encabezado con botón Volver, color distintivo de empresa, turno y contadores sincronizados
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = CardNavySurface,
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = onVolver,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("volver_empresas_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Volver a Empresas",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(accent)
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = TextoNormalizer.formatearMayusculas(empresa.nombre),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${uiState.turnoActivo.nombre} · ${trabajadoresEmpresaOrdenados.size} trabajadores · Ordenado por habitación",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SlateMutedText
                                    )
                                }
                            }

                            // Contadores operativos sincronizados con Listado
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Total: ${trabajadoresEmpresaOrdenados.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("empresa_detalle_total_count")
                                )
                                Text(
                                    text = "SÍ BAJÓ: $siBajoEmpresaCount",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldSuccess,
                                    modifier = Modifier.testTag("empresa_detalle_sibajo_count")
                                )
                                Text(
                                    text = "NO BAJÓ: $noBajoEmpresaCount",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SlateMutedText,
                                    modifier = Modifier.testTag("empresa_detalle_nobajo_count")
                                )
                            }
                        }
                    }

                    // Acciones principales: COPIAR LISTADO (4 columnas) y Agregar trabajador (empresa preseleccionada)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (trabajadoresVisibles.isEmpty()) {
                                    val detalleVacio = if (trabajadoresEmpresaOrdenados.isEmpty()) {
                                        "No hay trabajadores en ${empresa.nombre} (${uiState.turnoActivo.nombre}) para copiar."
                                    } else {
                                        "No hay trabajadores en ${empresa.nombre} (${uiState.turnoActivo.nombre}) con el filtro actual (${filtroEstado.etiqueta}) para copiar."
                                    }
                                    onMostrarMensaje(detalleVacio)
                                } else {
                                    val textoListado = TextoNormalizer.generarListadoCompactoParaCopiar(trabajadoresVisibles)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Listado ${empresa.nombre} ${uiState.turnoActivo.nombre} ${filtroEstado.etiqueta}",
                                            textoListado
                                        )
                                    )
                                    onMostrarMensaje(
                                        "Listado de ${empresa.nombre} (${uiState.turnoActivo.nombre} · ${filtroEstado.etiqueta}) copiado (${trabajadoresVisibles.size} trabajadores)."
                                    )
                                }
                            },
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = accent.copy(alpha = 0.14f),
                                contentColor = accent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("copiar_listado_empresa_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "COPIAR LISTADO",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = {
                                trabajadorEditar = null
                                mostrarFormulario = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("agregar_trabajador_empresa_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Agregar trabajador",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Buscador interno en la empresa
                    OutlinedTextField(
                        value = busquedaLocal,
                        onValueChange = { busquedaLocal = it },
                        placeholder = { Text("Buscar en ${empresa.nombre}…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Buscar") },
                        trailingIcon = {
                            if (busquedaLocal.isNotEmpty()) {
                                IconButton(onClick = { busquedaLocal = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Limpiar búsqueda")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DeepNavySurface,
                            unfocusedContainerColor = DeepNavySurface,
                            focusedBorderColor = accent,
                            unfocusedBorderColor = BorderNavy
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("buscar_empresa_detalle_input")
                    )

                    // Filtros discretos de estado operativo (sin alterar las 4 columnas que se copian)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FiltroEstadoMarcacion.entries.forEach { filtro ->
                            val selected = filtroEstado == filtro
                            FilterChip(
                                selected = selected,
                                onClick = { filtroEstado = filtro },
                                label = { Text(filtro.etiqueta) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.testTag("empresa_filtro_estado_${filtro.name}")
                            )
                        }
                    }

                    // Encabezado compacto estilo tabla Excel (4 columnas obligatorias en orden exacto)
                    Surface(
                        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                        color = ElevatedNavySurface,
                        border = BorderStroke(1.dp, BorderNavy),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "APELLIDOS",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1.25f)
                                    .padding(end = 3.dp)
                            )
                            Text(
                                text = "NOMBRE",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(0.95f)
                                    .padding(end = 3.dp)
                            )
                            Text(
                                text = "HABITACIÓN",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.weight(0.74f)
                            )
                            Text(
                                text = "PABELLÓN",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.weight(0.62f)
                            )
                            Spacer(modifier = Modifier.width(132.dp))
                        }
                    }
                }
            }

            if (trabajadoresVisibles.isEmpty()) {
                item(key = "tabla_vacia") {
                    Surface(
                        shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
                        color = CardNavySurface,
                        border = BorderStroke(1.dp, BorderNavy),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (trabajadoresEmpresaOrdenados.isEmpty()) {
                                "No hay trabajadores registrados en ${empresa.nombre} para ${uiState.turnoActivo.nombre}."
                            } else {
                                "No se encontraron trabajadores con ese criterio en ${empresa.nombre}."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlateMutedText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = trabajadoresVisibles,
                    key = { _, t -> t.id },
                    contentType = { _, _ -> "empresa_detalle_row" }
                ) { index, trabajador ->
                    val esUltimo = index == trabajadoresVisibles.lastIndex
                    val rowBg = if (index % 2 == 0) CardNavySurface else DeepNavySurface
                    val shape = if (esUltimo) {
                        RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
                    } else {
                        RoundedCornerShape(0.dp)
                    }

                    Surface(
                        shape = shape,
                        color = rowBg,
                        border = BorderStroke(0.5.dp, BorderNavy),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("empresa_detalle_row_${trabajador.id}")
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = trabajador.apellidosMayusculas,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1.25f)
                                        .padding(end = 3.dp)
                                )
                                Text(
                                    text = trabajador.nombreSoloMayusculas,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(0.95f)
                                        .padding(end = 3.dp)
                                )
                                Text(
                                    text = trabajador.habitacionMayusculas,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(0.74f)
                                )
                                Text(
                                    text = trabajador.pabellonMayusculas,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = SlateMutedText,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(0.62f)
                                )

                                // Indicador operativo discreto sincronizado + Editar + Eliminar
                                Row(
                                    modifier = Modifier.width(132.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val siBajo = trabajador.siBajo
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (siBajo) EmeraldContainer else ElevatedNavySurface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (siBajo) EmeraldSuccess else BorderNavy
                                        ),
                                        modifier = Modifier
                                            .width(48.dp)
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(role = Role.Switch) { onAlternarSiBajo(trabajador) }
                                            .testTag("empresa_detalle_estado_${trabajador.id}")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = if (siBajo) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                                contentDescription = if (siBajo) "SÍ BAJÓ" else "NO BAJÓ",
                                                tint = if (siBajo) EmeraldSuccess else SlateMutedText,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = if (siBajo) "SÍ BAJÓ" else "NO BAJÓ",
                                                fontSize = 7.5.sp,
                                                lineHeight = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (siBajo) EmeraldSuccess else SlateMutedText,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            trabajadorEditar = trabajador
                                            mostrarFormulario = true
                                        },
                                        modifier = Modifier
                                            .size(42.dp)
                                            .testTag("empresa_detalle_editar_${trabajador.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Editar a ${trabajador.nombreCompleto}",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { trabajadorEliminar = trabajador },
                                        modifier = Modifier
                                            .size(42.dp)
                                            .testTag("empresa_detalle_eliminar_${trabajador.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Eliminar a ${trabajador.nombreCompleto}",
                                            tint = RoseError,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }
                            if (!esUltimo) {
                                HorizontalDivider(thickness = 0.5.dp, color = BorderNavy)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }

    if (mostrarFormulario) {
        WorkerFormDialog(
            turnoActivo = uiState.turnoActivo,
            trabajadorEditar = trabajadorEditar,
            empresaFija = if (trabajadorEditar == null) empresa else null,
            onVerificarDuplicado = onVerificarDuplicado,
            onDismiss = { mostrarFormulario = false },
            onGuardar = { empSeleccionada, nombre, primerApellido, segundoApellido, habitacion, pabellon ->
                onGuardarTrabajador(
                    trabajadorEditar?.id,
                    trabajadorEditar?.turno ?: uiState.turnoActivo,
                    empSeleccionada,
                    nombre,
                    primerApellido,
                    segundoApellido,
                    habitacion,
                    pabellon
                )
                mostrarFormulario = false
            }
        )
    }

    trabajadorEliminar?.let { target ->
        AlertDialog(
            onDismissRequest = { trabajadorEliminar = null },
            containerColor = CardNavySurface,
            title = { Text("¿Eliminar trabajador?") },
            text = {
                Text(
                    "Se eliminará únicamente a ${target.nombreCompleto} (${target.empresa.nombre}, ${target.ubicacionTexto}) del listado maestro de ${target.turno.nombre}.\n\n" +
                        "Los demás trabajadores y registros históricos existentes permanecerán intactos."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEliminarTrabajador(target)
                        trabajadorEliminar = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirmar_eliminar_empresa_detalle_button")
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { trabajadorEliminar = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}
