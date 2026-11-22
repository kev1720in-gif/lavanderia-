package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.domain.model.AvisoDuplicado
import com.example.domain.model.BuscadorTrabajadores
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.HardwarePerformanceConfig
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.example.ui.components.CompanyBadge
import com.example.ui.components.WorkerFormDialog
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseError
import com.example.ui.theme.SlateMutedText
import com.example.ui.viewmodel.MainUiState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdministrarTrabajadoresScreen(
    uiState: MainUiState,
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
    onEliminarTrabajadores: (List<TrabajadorConEstado>) -> Unit,
    modifier: Modifier = Modifier
) {
    var busquedaLocal by remember { mutableStateOf("") }
    var mostrarFormulario by remember { mutableStateOf(false) }
    var trabajadorEditar by remember { mutableStateOf<TrabajadorConEstado?>(null) }
    var trabajadorEliminar by remember { mutableStateOf<TrabajadorConEstado?>(null) }

    var modoSeleccion by remember { mutableStateOf(false) }
    var seleccionadosIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmarEliminarSeleccionados by remember { mutableStateOf(false) }

    // Al cambiar de turno, salir del modo selección para evitar cruces entre turnos
    LaunchedEffect(uiState.turnoActivo) {
        modoSeleccion = false
        seleccionadosIds = emptySet()
        confirmarEliminarSeleccionados = false
    }

    if (modoSeleccion) {
        BackHandler {
            modoSeleccion = false
            seleccionadosIds = emptySet()
        }
    }

    var busquedaDebounced by remember { mutableStateOf("") }
    LaunchedEffect(busquedaLocal) {
        if (busquedaLocal.isBlank()) {
            busquedaDebounced = ""
        } else {
            delay(HardwarePerformanceConfig.searchDebounceMs)
            busquedaDebounced = busquedaLocal
        }
    }

    val listaFiltrada by produceState(
        initialValue = uiState.trabajadoresTurno,
        uiState.trabajadoresTurno,
        busquedaDebounced
    ) {
        value = if (busquedaDebounced.isBlank()) {
            uiState.trabajadoresTurno
        } else {
            withContext(Dispatchers.Default) {
                BuscadorTrabajadores.filtrarYOrdenar(uiState.trabajadoresTurno, busquedaDebounced)
            }
        }
    }

    val trabajadoresSeleccionados = remember(uiState.trabajadoresTurno, seleccionadosIds) {
        uiState.trabajadoresTurno.filter { it.id in seleccionadosIds }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (modoSeleccion) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CardNavySurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 840.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("barra_seleccion_multiple")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${trabajadoresSeleccionados.size} seleccionados (${uiState.turnoActivo.nombre})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("conteo_seleccionados_text")
                        )
                        OutlinedButton(
                            onClick = {
                                modoSeleccion = false
                                seleccionadosIds = emptySet()
                            },
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .testTag("cancelar_seleccion_button")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancelar")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val todosIds = listaFiltrada.map { it.id }.toSet()
                                seleccionadosIds = if (todosIds.isNotEmpty() && seleccionadosIds.containsAll(todosIds)) {
                                    emptySet()
                                } else {
                                    seleccionadosIds + todosIds
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("seleccionar_todos_button")
                        ) {
                            Icon(Icons.Filled.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SELECCIONAR TODOS", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Button(
                            onClick = { confirmarEliminarSeleccionados = true },
                            enabled = trabajadoresSeleccionados.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RoseError,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("eliminar_seleccionados_button")
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ELIMINAR SELECCIONADOS", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 840.dp)
                .testTag("admin_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "header_admin") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!modoSeleccion) {
                        Button(
                            onClick = {
                                trabajadorEditar = null
                                mostrarFormulario = true
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .testTag("agregar_trabajador_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Agregar trabajador al ${uiState.turnoActivo.nombre}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedTextField(
                        value = busquedaLocal,
                        onValueChange = { busquedaLocal = it },
                        placeholder = { Text("Buscar nombre, apellido, habitación o pabellón…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Buscar") },
                        trailingIcon = {
                            if (busquedaLocal.isNotEmpty()) {
                                IconButton(onClick = { busquedaLocal = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Limpiar")
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
                            .testTag("buscar_admin_input")
                    )

                    if (busquedaLocal.isNotBlank()) {
                        Text(
                            text = "${listaFiltrada.size} coincidencia(s) en ${uiState.turnoActivo.nombre}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("admin_coincidencias_count")
                        )
                    }
                }
            }

            items(
                items = listaFiltrada,
                key = { it.id },
                contentType = { "admin_worker_card" }
            ) { trabajador ->
                val estaSeleccionado = trabajador.id in seleccionadosIds
                val bordeTarjeta = if (estaSeleccionado) {
                    MaterialTheme.colorScheme.primary
                } else {
                    BorderNavy
                }
                val fondoTarjeta = if (estaSeleccionado) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    CardNavySurface
                }

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = fondoTarjeta),
                    border = BorderStroke(if (estaSeleccionado) 1.5.dp else 1.dp, bordeTarjeta),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .combinedClickable(
                            onClick = {
                                if (modoSeleccion) {
                                    seleccionadosIds = if (estaSeleccionado) {
                                        seleccionadosIds - trabajador.id
                                    } else {
                                        seleccionadosIds + trabajador.id
                                    }
                                }
                            },
                            onLongClick = {
                                modoSeleccion = true
                                seleccionadosIds = seleccionadosIds + trabajador.id
                            }
                        )
                        .testTag("admin_worker_card_${trabajador.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (modoSeleccion) {
                            Checkbox(
                                checked = estaSeleccionado,
                                onCheckedChange = { checked ->
                                    seleccionadosIds = if (checked) {
                                        seleccionadosIds + trabajador.id
                                    } else {
                                        seleccionadosIds - trabajador.id
                                    }
                                },
                                modifier = Modifier.testTag("checkbox_worker_${trabajador.id}")
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = trabajador.nombreCompleto,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CompanyBadge(empresa = trabajador.empresa)
                                Text(
                                    text = trabajador.ubicacionTexto,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateMutedText
                                )
                            }
                        }

                        if (!modoSeleccion) {
                            IconButton(
                                onClick = {
                                    trabajadorEditar = trabajador
                                    mostrarFormulario = true
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("editar_worker_${trabajador.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Editar a ${trabajador.nombreCompleto}",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            IconButton(
                                onClick = { trabajadorEliminar = trabajador },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("eliminar_worker_${trabajador.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Eliminar a ${trabajador.nombreCompleto}",
                                    tint = RoseError
                                )
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
            onVerificarDuplicado = onVerificarDuplicado,
            onDismiss = { mostrarFormulario = false },
            onGuardar = { empresa, nombre, primerApellido, segundoApellido, habitacion, pabellon ->
                onGuardarTrabajador(
                    trabajadorEditar?.id,
                    trabajadorEditar?.turno ?: uiState.turnoActivo,
                    empresa,
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
                        .testTag("confirmar_eliminar_trabajador_button")
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

    if (confirmarEliminarSeleccionados && trabajadoresSeleccionados.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmarEliminarSeleccionados = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(horizontal = 20.dp),
            containerColor = CardNavySurface,
            title = {
                Text("¿Eliminar ${trabajadoresSeleccionados.size} trabajadores seleccionados?")
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Se eliminarán del listado maestro de ${uiState.turnoActivo.nombre} únicamente estos ${trabajadoresSeleccionados.size} trabajadores:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = RoseContainer.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, RoseError.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            trabajadoresSeleccionados.forEach { t ->
                                Text(
                                    text = "• ${t.nombreCompleto} (${t.empresa.nombre})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Text(
                        text = "Los trabajadores no seleccionados, el otro turno, las empresas, configuraciones y registros históricos permanecerán intactos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEliminarTrabajadores(trabajadoresSeleccionados)
                        confirmarEliminarSeleccionados = false
                        modoSeleccion = false
                        seleccionadosIds = emptySet()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirmar_eliminar_seleccionados_button")
                ) {
                    Text("Eliminar (${trabajadoresSeleccionados.size})", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { confirmarEliminarSeleccionados = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}
