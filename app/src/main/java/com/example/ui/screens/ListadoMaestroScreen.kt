package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TrabajadorConEstado
import com.example.ui.components.CompanyBadge
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElevatedNavySurface
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SlateMutedText
import com.example.ui.viewmodel.FiltroEstadoMarcacion
import com.example.ui.viewmodel.MainUiState

@Composable
fun ListadoMaestroScreen(
    uiState: MainUiState,
    onBusquedaChange: (String) -> Unit,
    onEmpresaFiltroChange: (EmpresaOficial?) -> Unit,
    onEstadoFiltroChange: (FiltroEstadoMarcacion) -> Unit,
    onAlternarSiBajo: (TrabajadorConEstado) -> Unit,
    modifier: Modifier = Modifier
) {
    val conteosPorEmpresa = remember(uiState.resumenEmpresas) {
        uiState.resumenEmpresas.associate { it.empresa.id to it.totalTrabajadores }
    }
    val sugerenciasRapidas = remember(uiState.busqueda, uiState.trabajadoresFiltrados) {
        if (uiState.busqueda.isBlank()) {
            emptyList()
        } else {
            uiState.trabajadoresFiltrados.take(8)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .testTag("listado_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "filtros_busqueda", contentType = "header_filters") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                    OutlinedTextField(
                        value = uiState.busqueda,
                        onValueChange = onBusquedaChange,
                        placeholder = {
                            Text(
                                text = "Buscar nombre, apellido, habitación o pabellón…",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Buscar"
                            )
                        },
                        trailingIcon = {
                            if (uiState.busqueda.isNotEmpty()) {
                                IconButton(onClick = { onBusquedaChange("") }) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Limpiar búsqueda"
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DeepNavySurface,
                            unfocusedContainerColor = DeepNavySurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = BorderNavy
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("buscar_listado_input")
                    )

                    if (uiState.busqueda.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "${uiState.trabajadoresFiltrados.size} coincidencia(s) en ${uiState.turnoActivo.nombre}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("listado_coincidencias_count")
                            )
                            if (sugerenciasRapidas.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("listado_sugerencias_row")
                                ) {
                                    items(
                                        items = sugerenciasRapidas,
                                        key = { "sug_${it.id}" },
                                        contentType = { "suggestion_chip" }
                                    ) { sugerido ->
                                        val habYPab = buildString {
                                            append(sugerido.habitacionMayusculas.ifBlank { "S/N" })
                                            if (sugerido.pabellonMayusculas.isNotBlank()) {
                                                append(" - ")
                                                append(sugerido.pabellonMayusculas)
                                            }
                                        }
                                        SuggestionChip(
                                            onClick = {
                                                val consultaDirecta = listOf(
                                                    sugerido.nombreCompleto,
                                                    sugerido.habitacionMayusculas,
                                                    sugerido.pabellonMayusculas
                                                ).filter { it.isNotBlank() }.joinToString(" ")
                                                onBusquedaChange(consultaDirecta)
                                            },
                                            label = {
                                                Text(
                                                    text = "${sugerido.nombreCompleto} · $habYPab",
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = CardNavySurface,
                                                labelColor = MaterialTheme.colorScheme.onSurface
                                            ),
                                            border = BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                            ),
                                            modifier = Modifier.testTag("sugerencia_worker_${sugerido.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Filtros de estado
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FiltroEstadoMarcacion.entries.forEach { filtro ->
                            val selected = uiState.filtroEstado == filtro
                            FilterChip(
                                selected = selected,
                                onClick = { onEstadoFiltroChange(filtro) },
                                label = { Text(filtro.etiqueta) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    // Filtros por las 8 empresas con sus colores distintivos
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = uiState.empresaFiltro == null,
                            onClick = { onEmpresaFiltroChange(null) },
                            label = { Text("Todas (${uiState.trabajadoresTurno.size})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("filtro_empresa_todas")
                        )
                        EmpresaOficial.todas.forEach { empresa ->
                            val selected = uiState.empresaFiltro == empresa
                            val count = conteosPorEmpresa[empresa.id] ?: 0
                            val accent = Color(empresa.colorHex)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    onEmpresaFiltroChange(if (selected) null else empresa)
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(accent)
                                    )
                                },
                                label = { Text("${empresa.nombre} ($count)") },
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = accent.copy(alpha = 0.4f),
                                    selectedBorderColor = accent
                                ),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = accent.copy(alpha = 0.08f),
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedContainerColor = accent.copy(alpha = 0.22f),
                                    selectedLabelColor = accent
                                ),
                                modifier = Modifier.testTag("filtro_empresa_${empresa.codigo}")
                            )
                        }
                    }
                }
            }

            if (uiState.trabajadoresFiltrados.isEmpty()) {
                item(key = "vacio", contentType = "empty_state") {
                    EmptyListadoCard(
                        hayTrabajadoresEnTurno = uiState.trabajadoresTurno.isNotEmpty()
                    )
                }
            } else {
                items(
                    items = uiState.trabajadoresFiltrados,
                    key = { it.id },
                    contentType = { "worker_card" }
                ) { trabajador ->
                    WorkerListadoCard(
                        trabajador = trabajador,
                        onToggleSiBajo = { onAlternarSiBajo(trabajador) }
                    )
                }
            }

            item(key = "bottom_spacer", contentType = "spacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun WorkerListadoCard(
    trabajador: TrabajadorConEstado,
    onToggleSiBajo: () -> Unit
) {
    val siBajo = trabajador.siBajo
    val cardBorder = if (siBajo) EmeraldSuccess.copy(alpha = 0.55f) else BorderNavy
    val bgColor = if (siBajo) EmeraldContainer else ElevatedNavySurface
    val strokeColor = if (siBajo) EmeraldSuccess else BorderNavy
    val contentColor = if (siBajo) EmeraldSuccess else SlateMutedText

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardNavySurface),
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("worker_card_${trabajador.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = trabajador.nombreCompleto,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                CompanyBadge(empresa = trabajador.empresa)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MeetingRoom,
                            contentDescription = "Habitación",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Hab. ${trabajador.habitacionMayusculas.ifBlank { "S/N" }}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (trabajador.pabellonMayusculas.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Apartment,
                                contentDescription = "Pabellón o piso",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Pab. ${trabajador.pabellonMayusculas}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Botón grande de estado SÍ BAJÓ / NO BAJÓ sin animaciones pesadas por ítem
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = bgColor,
                border = BorderStroke(1.5.dp, strokeColor),
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .widthIn(min = 104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Switch, onClick = onToggleSiBajo)
                    .testTag("toggle_estado_${trabajador.id}")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (siBajo) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (siBajo) "SÍ BAJÓ" else "NO BAJÓ",
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (siBajo) "SÍ BAJÓ" else "NO BAJÓ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyListadoCard(hayTrabajadoresEnTurno: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardNavySurface,
        border = BorderStroke(1.dp, BorderNavy),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = if (hayTrabajadoresEnTurno) {
                    "Sin resultados para este filtro"
                } else {
                    "No hay trabajadores registrados en este turno"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (hayTrabajadoresEnTurno) {
                    "Pruebe limpiando la búsqueda o seleccionando otra empresa."
                } else {
                    "Agregue trabajadores manualmente o importe un archivo Excel (.xlsx) o CSV."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
