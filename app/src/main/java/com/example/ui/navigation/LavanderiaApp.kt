package com.example.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.importer.FormatoArchivo
import com.example.data.ocr.OcrImageProcessor
import com.example.domain.model.EmpresaOficial
import com.example.lucy.ui.LucyPhotoIntake
import com.example.lucy.ui.LucyScreen
import com.example.lucy.ui.LucyViewModel
import com.example.ui.components.ShiftSelectorHeader
import com.example.ui.screens.AdministrarTrabajadoresScreen
import com.example.ui.screens.AjustesScreen
import com.example.ui.screens.ControlDescargaScreen
import com.example.ui.screens.EmpresaDetalleScreen
import com.example.ui.screens.EmpresasResumenScreen
import com.example.ui.screens.ImportarScreen
import com.example.ui.screens.ListadoMaestroScreen
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SlateMutedText
import com.example.ui.viewmodel.BackupViewModel
import com.example.ui.viewmodel.DescargaViewModel
import com.example.ui.viewmodel.ImportacionViewModel
import com.example.ui.viewmodel.MainViewModel

const val ROUTE_LISTADO = "listado_maestro"
const val ROUTE_ADMINISTRAR = "administrar_trabajadores"
const val ROUTE_EMPRESAS = "empresas_resumen"
const val ROUTE_DESCARGA = "control_descarga"
const val ROUTE_LUCY = "lucy_asistente"
const val ROUTE_EMPRESA_DETALLE_PATTERN = "empresa_detalle/{codigoEmpresa}"
const val ROUTE_AJUSTES = "ajustes"
const val ROUTE_IMPORTAR = "importar_listado"

private fun routeEmpresaDetalle(codigoEmpresa: String): String = "empresa_detalle/$codigoEmpresa"

private data class DestinoPrincipal(
    val ruta: String,
    val etiqueta: String,
    val iconoActivo: ImageVector,
    val iconoInactivo: ImageVector,
    val testTag: String
)

private val destinosPrincipales = listOf(
    DestinoPrincipal(
        ruta = ROUTE_LISTADO,
        etiqueta = "Listado",
        iconoActivo = Icons.Filled.Checklist,
        iconoInactivo = Icons.Outlined.Checklist,
        testTag = "nav_listado"
    ),
    DestinoPrincipal(
        ruta = ROUTE_ADMINISTRAR,
        etiqueta = "Trabajadores",
        iconoActivo = Icons.Filled.Group,
        iconoInactivo = Icons.Outlined.Group,
        testTag = "nav_trabajadores"
    ),
    DestinoPrincipal(
        ruta = ROUTE_EMPRESAS,
        etiqueta = "Empresas",
        iconoActivo = Icons.Filled.Apartment,
        iconoInactivo = Icons.Outlined.Apartment,
        testTag = "nav_empresas"
    ),
    DestinoPrincipal(
        ruta = ROUTE_DESCARGA,
        etiqueta = "Descarga",
        iconoActivo = Icons.Filled.DocumentScanner,
        iconoInactivo = Icons.Outlined.DocumentScanner,
        testTag = "nav_descarga"
    ),
    DestinoPrincipal(
        ruta = ROUTE_LUCY,
        etiqueta = "Lucy",
        iconoActivo = Icons.Filled.AutoAwesome,
        iconoInactivo = Icons.Outlined.AutoAwesome,
        testTag = "nav_lucy"
    )
)

@Composable
fun LavanderiaApp(
    mainViewModel: MainViewModel,
    importacionViewModel: ImportacionViewModel,
    backupViewModel: BackupViewModel,
    descargaViewModel: DescargaViewModel,
    lucyViewModel: LucyViewModel? = null
) {
    val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val importState by importacionViewModel.uiState.collectAsStateWithLifecycle()
    val backupState by backupViewModel.uiState.collectAsStateWithLifecycle()
    val descargaState by descargaViewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val rutaActual = navBackStackEntry?.destination?.route ?: ROUTE_LISTADO
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        val rutasActivas = descargaState.fotosLote.mapNotNull { it.archivoTrabajoPath }.toSet()
        OcrImageProcessor.limpiarTemporalesHuerfanos(
            context = context.applicationContext,
            rutasActivas = rutasActivas
        )
        val rutaFotoLucy = lucyViewModel?.uiState?.value?.fotoSeleccionada?.tempFilePath
        LucyPhotoIntake.cleanupOrphanedFiles(
            context = context.applicationContext,
            activePaths = setOfNotNull(rutaFotoLucy)
        )
    }

    LaunchedEffect(Unit) {
        mainViewModel.eventosMensaje.collect { mensaje ->
            snackbarHostState.showSnackbar(mensaje)
        }
    }

    LaunchedEffect(Unit) {
        backupViewModel.eventosMensaje.collect { mensaje ->
            snackbarHostState.showSnackbar(mensaje)
        }
    }

    LaunchedEffect(Unit) {
        descargaViewModel.eventosMensaje.collect { mensaje ->
            snackbarHostState.showSnackbar(mensaje)
        }
    }

    fun cerrarPantallasTemporales() {
        navController.popBackStack(ROUTE_IMPORTAR, inclusive = true, saveState = false)
        navController.popBackStack(ROUTE_AJUSTES, inclusive = true, saveState = false)
        navController.clearBackStack(ROUTE_IMPORTAR)
        navController.clearBackStack(ROUTE_AJUSTES)
    }

    fun navegarA(ruta: String) {
        cerrarPantallasTemporales()
        val destinoActual = navController.currentBackStackEntry?.destination?.route
        if (ruta == ROUTE_EMPRESAS && destinoActual?.startsWith("empresa_detalle/") == true) {
            navController.popBackStack(ROUTE_EMPRESAS, inclusive = false, saveState = false)
            return
        }
        if (destinoActual == ruta) return
        if (ruta == ROUTE_LISTADO && navController.popBackStack(ROUTE_LISTADO, inclusive = false, saveState = false)) {
            return
        }
        navController.navigate(ruta) {
            popUpTo(navController.graph.findStartDestination().id) {
                inclusive = false
                saveState = false
            }
            launchSingleTop = true
            restoreState = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val esPantallaAncha = maxWidth >= 600.dp

        Scaffold(
            containerColor = ObsidianBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                ShiftSelectorHeader(
                    turnoActivo = mainState.turnoActivo,
                    conteoTurnoA = mainState.conteoTurnoA,
                    conteoTurnoB = mainState.conteoTurnoB,
                    onSeleccionarTurno = { mainViewModel.seleccionarTurno(it) },
                    onAbrirAjustes = {
                        when (rutaActual) {
                            ROUTE_AJUSTES -> Unit
                            ROUTE_IMPORTAR -> {
                                navController.popBackStack(ROUTE_IMPORTAR, inclusive = true, saveState = false)
                                navController.clearBackStack(ROUTE_IMPORTAR)
                            }
                            else -> {
                                navController.navigate(ROUTE_AJUSTES) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!esPantallaAncha) {
                    NavigationBar(
                        containerColor = DeepNavySurface,
                        modifier = Modifier.navigationBarsPadding()
                    ) {
                        destinosPrincipales.forEach { destino ->
                            val seleccionado = rutaActual == destino.ruta ||
                                (destino.ruta == ROUTE_EMPRESAS && rutaActual.startsWith("empresa_detalle/"))
                            NavigationBarItem(
                                selected = seleccionado,
                                onClick = { navegarA(destino.ruta) },
                                icon = {
                                    Icon(
                                        imageVector = if (seleccionado) destino.iconoActivo else destino.iconoInactivo,
                                        contentDescription = destino.etiqueta
                                    )
                                },
                                label = {
                                    Text(
                                        text = destino.etiqueta,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = SlateMutedText,
                                    unselectedTextColor = SlateMutedText
                                ),
                                modifier = Modifier.testTag(destino.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            if (!mainState.inicializado) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Inicializando base de datos local…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlateMutedText
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (esPantallaAncha) {
                        NavigationRail(containerColor = DeepNavySurface) {
                            destinosPrincipales.forEach { destino ->
                                val seleccionado = rutaActual == destino.ruta ||
                                    (destino.ruta == ROUTE_EMPRESAS && rutaActual.startsWith("empresa_detalle/"))
                                NavigationRailItem(
                                    selected = seleccionado,
                                    onClick = { navegarA(destino.ruta) },
                                    icon = {
                                        Icon(
                                            imageVector = if (seleccionado) destino.iconoActivo else destino.iconoInactivo,
                                            contentDescription = destino.etiqueta
                                        )
                                    },
                                    label = { Text(destino.etiqueta) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = SlateMutedText,
                                        unselectedTextColor = SlateMutedText
                                    ),
                                    modifier = Modifier.testTag(destino.testTag)
                                )
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_LISTADO,
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                        modifier = Modifier.weight(1f)
                    ) {
                        composable(ROUTE_LISTADO) {
                            ListadoMaestroScreen(
                                uiState = mainState,
                                onBusquedaChange = { mainViewModel.actualizarBusqueda(it) },
                                onEmpresaFiltroChange = { mainViewModel.seleccionarEmpresaFiltro(it) },
                                onEstadoFiltroChange = { mainViewModel.seleccionarEstadoFiltro(it) },
                                onAlternarSiBajo = { mainViewModel.alternarSiBajo(it) }
                            )
                        }

                        composable(ROUTE_ADMINISTRAR) {
                            AdministrarTrabajadoresScreen(
                                uiState = mainState,
                                onVerificarDuplicado = { turno, nom, ap1, ap2, hab, emp, exId ->
                                    mainViewModel.verificarDuplicado(turno, nom, ap1, ap2, hab, emp, exId)
                                },
                                onGuardarTrabajador = { id, turno, emp, nom, ap1, ap2, hab, pab ->
                                    mainViewModel.guardarTrabajador(id, turno, emp, nom, ap1, ap2, hab, pab)
                                },
                                onEliminarTrabajador = { mainViewModel.eliminarTrabajador(it) },
                                onEliminarTrabajadores = { mainViewModel.eliminarTrabajadores(it) }
                            )
                        }

                        composable(ROUTE_EMPRESAS) {
                            EmpresasResumenScreen(
                                uiState = mainState,
                                onAbrirEmpresa = { empresa ->
                                    navController.navigate(routeEmpresaDetalle(empresa.codigo)) {
                                        launchSingleTop = true
                                    }
                                },
                                onLimpiarRegistroOperativo = { mainViewModel.limpiarRegistroDePrueba() }
                            )
                        }

                        composable(ROUTE_DESCARGA) {
                            ControlDescargaScreen(
                                uiState = descargaState,
                                onSeleccionarEmpresa = { descargaViewModel.seleccionarEmpresa(it) },
                                onProcesarFotoCamara = { uri, tempFile ->
                                    descargaViewModel.procesarFotoCamara(context, uri, tempFile)
                                },
                                onCancelarFotoCamara = { tempFile ->
                                    descargaViewModel.cancelarFotoCamara(context, tempFile)
                                },
                                onProcesarFotosGaleria = { uris ->
                                    descargaViewModel.procesarFotosGaleria(context, uris)
                                },
                                onReprocesarFotoConAjuste = { fotoId, ajuste ->
                                    descargaViewModel.reprocesarFotoConAjuste(context, fotoId, ajuste)
                                },
                                onDescartarFotoLote = { descargaViewModel.descartarFotoLote(context, it) },
                                onConfirmarPendiente = { pendienteId, trabajador ->
                                    descargaViewModel.confirmarPendiente(pendienteId, trabajador)
                                },
                                onDescartarPendiente = { descargaViewModel.descartarPendiente(it) },
                                onSolicitarPrevisualizacionConciliacion = {
                                    descargaViewModel.solicitarPrevisualizacionConciliacion()
                                },
                                onCancelarPrevisualizacionConciliacion = {
                                    descargaViewModel.cancelarPrevisualizacionConciliacion()
                                },
                                onConfirmarConciliacionFinal = {
                                    descargaViewModel.confirmarConciliacionFinal()
                                },
                                onLimpiarRegistroOperativo = {
                                    descargaViewModel.limpiarRegistroOperativoTurno(context)
                                },
                                onMostrarMensaje = { msg -> mainViewModel.emitirMensaje(msg) }
                            )
                        }

                        composable(ROUTE_LUCY) {
                            if (lucyViewModel != null) {
                                LucyScreen(lucyViewModel = lucyViewModel)
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Lucy no está conectada en esta vista.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = SlateMutedText
                                    )
                                }
                            }
                        }

                        composable(
                            route = ROUTE_EMPRESA_DETALLE_PATTERN,
                            arguments = listOf(
                                navArgument("codigoEmpresa") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val codigo = backStackEntry.arguments?.getString("codigoEmpresa") ?: ""
                            val empresa = EmpresaOficial.porCodigo(codigo) ?: EmpresaOficial.KOMATSU
                            EmpresaDetalleScreen(
                                empresa = empresa,
                                uiState = mainState,
                                onVolver = {
                                    navController.popBackStack(ROUTE_EMPRESA_DETALLE_PATTERN, inclusive = true, saveState = false)
                                },
                                onAlternarSiBajo = { mainViewModel.alternarSiBajo(it) },
                                onVerificarDuplicado = { turno, nom, ap1, ap2, hab, emp, exId ->
                                    mainViewModel.verificarDuplicado(turno, nom, ap1, ap2, hab, emp, exId)
                                },
                                onGuardarTrabajador = { id, turno, emp, nom, ap1, ap2, hab, pab ->
                                    mainViewModel.guardarTrabajador(id, turno, emp, nom, ap1, ap2, hab, pab)
                                },
                                onEliminarTrabajador = { mainViewModel.eliminarTrabajador(it) },
                                onMostrarMensaje = { msg -> mainViewModel.emitirMensaje(msg) }
                            )
                        }

                        composable(ROUTE_AJUSTES) {
                            AjustesScreen(
                                uiState = mainState,
                                backupUiState = backupState,
                                onVolver = {
                                    navController.popBackStack(ROUTE_AJUSTES, inclusive = true, saveState = false)
                                    navController.clearBackStack(ROUTE_AJUSTES)
                                },
                                onImportarExcel = {
                                    importacionViewModel.establecerFormatoPreferido(FormatoArchivo.XLSX)
                                    navController.navigate(ROUTE_IMPORTAR) {
                                        launchSingleTop = true
                                    }
                                },
                                onImportarCsv = {
                                    importacionViewModel.establecerFormatoPreferido(FormatoArchivo.CSV)
                                    navController.navigate(ROUTE_IMPORTAR) {
                                        launchSingleTop = true
                                    }
                                },
                                onGenerarNombreBackup = {
                                    backupViewModel.generarNombreArchivoBackup()
                                },
                                onExportarBackupUri = { uri, nombre ->
                                    backupViewModel.exportarBackupHaciaUri(
                                        contentResolver = context.contentResolver,
                                        uri = uri,
                                        nombreArchivo = nombre
                                    )
                                },
                                onPrepararRestauracionUri = { uri, nombre ->
                                    backupViewModel.prepararRestauracionDesdeUri(
                                        contentResolver = context.contentResolver,
                                        uri = uri,
                                        nombreArchivo = nombre
                                    )
                                },
                                onConfirmarRestauracion = { safetyDir ->
                                    backupViewModel.confirmarRestauracion(safetyDir)
                                },
                                onCancelarRestauracion = {
                                    backupViewModel.cancelarRestauracionPendiente()
                                }
                            )
                        }

                        composable(ROUTE_IMPORTAR) {
                            ImportarScreen(
                                uiState = importState,
                                turnoActivo = mainState.turnoActivo,
                                onVolver = {
                                    navController.popBackStack(ROUTE_IMPORTAR, inclusive = true, saveState = false)
                                    navController.clearBackStack(ROUTE_IMPORTAR)
                                },
                                onCargarArchivo = { nombre, abrir, mime, turno ->
                                    importacionViewModel.cargarArchivo(nombre, abrir, mime, turno)
                                },
                                onSeleccionarHoja = { importacionViewModel.seleccionarHoja(it) },
                                onActualizarMapeo = { campo, idx ->
                                    importacionViewModel.actualizarMapeo(campo, idx)
                                },
                                onCambiarModoTurno = { usarCol, turnoDef ->
                                    importacionViewModel.cambiarModoTurno(usarCol, turnoDef)
                                },
                                onCambiarFiltroPreview = { importacionViewModel.cambiarFiltroPreview(it) },
                                onToggleIncluirFila = { importacionViewModel.toggleIncluirFila(it) },
                                onResolverRevisionFila = { n, accion ->
                                    importacionViewModel.resolverRevisionFila(n, accion)
                                },
                                onCorregirFila = { n, nom, ap1, ap2, hab, pab, emp, t ->
                                    importacionViewModel.corregirFila(n, nom, ap1, ap2, hab, pab, emp, t)
                                },
                                onConfirmarImportacion = { cb -> importacionViewModel.confirmarImportacion(cb) },
                                onReiniciar = { importacionViewModel.reiniciar() },
                                onImportacionExitosaMensaje = { msg -> mainViewModel.emitirMensaje(msg) }
                            )
                        }
                    }
                }
            }
        }
    }
}
