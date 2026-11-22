package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.DialogProperties
import com.example.domain.model.AvisoDuplicado
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BorderNavy
import com.example.ui.theme.CardNavySurface
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseError
import com.example.ui.theme.SlateMutedText

@Composable
fun WorkerFormDialog(
    turnoActivo: Turno,
    trabajadorEditar: TrabajadorConEstado?,
    empresaFija: EmpresaOficial? = null,
    onVerificarDuplicado: suspend (
        turno: Turno,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        empresa: EmpresaOficial,
        excluirId: String?
    ) -> AvisoDuplicado?,
    onDismiss: () -> Unit,
    onGuardar: (
        empresa: EmpresaOficial,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        pabellon: String
    ) -> Unit
) {
    val empresaBloqueada = empresaFija != null && trabajadorEditar == null
    var nombre by remember(trabajadorEditar) { mutableStateOf(trabajadorEditar?.nombre ?: "") }
    var primerApellido by remember(trabajadorEditar) { mutableStateOf(trabajadorEditar?.primerApellido ?: "") }
    var segundoApellido by remember(trabajadorEditar) { mutableStateOf(trabajadorEditar?.segundoApellido ?: "") }
    var mostrarSegundoApellido by remember(trabajadorEditar) {
        mutableStateOf(trabajadorEditar?.segundoApellido?.isNotBlank() == true)
    }
    var habitacion by remember(trabajadorEditar) { mutableStateOf(trabajadorEditar?.habitacion ?: "") }
    var pabellon by remember(trabajadorEditar) { mutableStateOf(trabajadorEditar?.pabellon ?: "") }
    var empresa by remember(trabajadorEditar, empresaFija) {
        mutableStateOf(trabajadorEditar?.empresa ?: empresaFija ?: EmpresaOficial.KOMATSU)
    }
    var empresaExpanded by remember { mutableStateOf(false) }
    var avisoDuplicado by remember { mutableStateOf<AvisoDuplicado?>(null) }
    var errorValidacion by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(nombre, primerApellido, segundoApellido, mostrarSegundoApellido, habitacion, empresa) {
        if (nombre.isNotBlank() && primerApellido.isNotBlank()) {
            avisoDuplicado = onVerificarDuplicado(
                trabajadorEditar?.turno ?: turnoActivo,
                nombre,
                primerApellido,
                if (mostrarSegundoApellido) segundoApellido else "",
                habitacion,
                empresa,
                trabajadorEditar?.id
            )
        } else {
            avisoDuplicado = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
            .padding(horizontal = 20.dp),
        containerColor = CardNavySurface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (trabajadorEditar == null) "Nuevo trabajador" else "Editar trabajador",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Registro exclusivo en ${(trabajadorEditar?.turno ?: turnoActivo).nombre}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = {
                        nombre = it
                        errorValidacion = null
                    },
                    label = { Text("Nombre *") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_nombre")
                )
                OutlinedTextField(
                    value = primerApellido,
                    onValueChange = {
                        primerApellido = it
                        errorValidacion = null
                    },
                    label = { Text("Primer apellido *") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_primer_apellido")
                )

                if (mostrarSegundoApellido) {
                    OutlinedTextField(
                        value = segundoApellido,
                        onValueChange = { segundoApellido = it },
                        label = { Text("Segundo apellido (opcional)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_segundo_apellido")
                    )
                } else {
                    OutlinedButton(
                        onClick = { mostrarSegundoApellido = true },
                        border = BorderStroke(1.dp, BorderNavy),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("agregar_segundo_apellido_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("+ AGREGAR SEGUNDO APELLIDO")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = habitacion,
                        onValueChange = {
                            habitacion = it
                            errorValidacion = null
                        },
                        label = { Text("Habitación *") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_habitacion")
                    )
                    OutlinedTextField(
                        value = pabellon,
                        onValueChange = { pabellon = it },
                        label = { Text("Pabellón / Piso") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_pabellon")
                    )
                }

                // Selector de empresa con colores distintivos
                val empresaColor = Color(empresa.colorHex)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DeepNavySurface,
                        border = BorderStroke(1.dp, empresaColor.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (!empresaBloqueada) {
                                    Modifier.clickable { empresaExpanded = true }
                                } else {
                                    Modifier
                                }
                            )
                            .testTag("dropdown_empresa")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                        .background(empresaColor)
                                )
                                Column {
                                    Text(
                                        text = if (empresaBloqueada) "Empresa (preseleccionada)" else "Empresa *",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SlateMutedText
                                    )
                                    Text(
                                        text = empresa.nombre,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = empresaColor
                                    )
                                }
                            }
                            if (!empresaBloqueada) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = "Seleccionar empresa",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (!empresaBloqueada) {
                        DropdownMenu(
                            expanded = empresaExpanded,
                            onDismissRequest = { empresaExpanded = false }
                        ) {
                            EmpresaOficial.todas.forEach { opcion ->
                                val opcionColor = Color(opcion.colorHex)
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(opcionColor)
                                            )
                                            Text(
                                                text = opcion.nombre,
                                                color = opcionColor,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    },
                                    onClick = {
                                        empresa = opcion
                                        empresaExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (avisoDuplicado != null) {
                    val esExacto = avisoDuplicado!!.esDuplicadoExacto
                    val bg = if (esExacto) RoseContainer else AmberContainer
                    val fg = if (esExacto) RoseError else AmberWarning
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = bg,
                        border = BorderStroke(1.dp, fg.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = fg,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = avisoDuplicado!!.mensaje,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (errorValidacion != null) {
                    Text(
                        text = errorValidacion!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = RoseError,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        nombre.isBlank() -> errorValidacion = "Ingrese el nombre del trabajador."
                        primerApellido.isBlank() -> errorValidacion = "Ingrese el primer apellido."
                        habitacion.isBlank() -> errorValidacion = "Ingrese la habitación."
                        avisoDuplicado?.esDuplicadoExacto == true -> {
                            errorValidacion = "No se puede guardar un registro idéntico duplicado en este turno."
                        }
                        else -> {
                            onGuardar(
                                empresa,
                                nombre,
                                primerApellido,
                                if (mostrarSegundoApellido) segundoApellido else "",
                                habitacion,
                                pabellon
                            )
                        }
                    }
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("guardar_trabajador_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Guardar", fontWeight = FontWeight.Bold)
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
