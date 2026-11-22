package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.importer.ArchivoTabular
import com.example.data.importer.FormatoArchivo
import com.example.data.importer.ImportacionValidator
import com.example.data.importer.SpreadsheetParser
import com.example.data.local.entities.TrabajadorEntity
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.AccionImportacion
import com.example.domain.model.CampoImportacion
import com.example.domain.model.ClasificacionImportacion
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.FilaImportacionConfirmada
import com.example.domain.model.FilaImportacionPreview
import com.example.domain.model.MapeoColumnas
import com.example.domain.model.ResultadoImportacion
import com.example.domain.model.Turno
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

enum class PasoImportacion {
    SELECCION_ARCHIVO,
    MAPEO_Y_REVISION
}

enum class FiltroImportacion(val etiqueta: String) {
    TODAS("Todas"),
    VALIDAS("Listas"),
    CON_ALERTAS("Por revisar / alertas")
}

data class ImportacionUiState(
    val paso: PasoImportacion = PasoImportacion.SELECCION_ARCHIVO,
    val formatoPreferido: FormatoArchivo? = null,
    val cargando: Boolean = false,
    val errorMensaje: String? = null,
    val archivo: ArchivoTabular? = null,
    val hojaSeleccionadaIdx: Int = 0,
    val mapeo: MapeoColumnas = MapeoColumnas(),
    val usarColumnaTurnoArchivo: Boolean = true,
    val turnoDestinoPorDefecto: Turno = Turno.A,
    val filasPreview: List<FilaImportacionPreview> = emptyList(),
    val filtroPreview: FiltroImportacion = FiltroImportacion.TODAS,
    val resultadoImportacion: ResultadoImportacion? = null
) {
    val hojaActual get() = archivo?.hojas?.getOrNull(hojaSeleccionadaIdx)
    val totalIncluidas: Int get() = filasPreview.count { it.seraProcesada }
    val totalNuevos: Int get() = filasPreview.count { it.clasificacion == ClasificacionImportacion.NUEVO }
    val totalYaExistentes: Int get() = filasPreview.count { it.clasificacion == ClasificacionImportacion.YA_EXISTE_IDENTICO }
    val totalPosiblesActualizaciones: Int get() = filasPreview.count { it.clasificacion == ClasificacionImportacion.EXISTENTE_CON_CAMBIOS }
    val totalRequierenRevision: Int get() = filasPreview.count { it.clasificacion == ClasificacionImportacion.REQUIERE_REVISION }
    val totalRevisionPendiente: Int get() = filasPreview.count {
        !it.tieneErrorBloqueante &&
            (it.clasificacion == ClasificacionImportacion.REQUIERE_REVISION ||
                it.clasificacion == ClasificacionImportacion.EXISTENTE_CON_CAMBIOS) &&
            !it.revisionResuelta
    }
    val totalConErrores: Int get() = filasPreview.count { it.tieneErrorBloqueante }
    val totalConAdvertencias: Int get() = filasPreview.count { it.tieneAdvertencia }
}

class ImportacionViewModel(
    private val repository: LavanderiaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportacionUiState())
    val uiState: StateFlow<ImportacionUiState> = _uiState.asStateFlow()

    private var existentesA: List<TrabajadorEntity> = emptyList()
    private var existentesB: List<TrabajadorEntity> = emptyList()

    fun establecerFormatoPreferido(formato: FormatoArchivo?) {
        _uiState.update { it.copy(formatoPreferido = formato, errorMensaje = null) }
    }

    fun cargarArchivo(
        nombreArchivo: String,
        abrirStream: () -> InputStream?,
        mimeType: String?,
        turnoActivo: Turno
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    cargando = true,
                    errorMensaje = null,
                    resultadoImportacion = null
                )
            }
            try {
                val parsed = withContext(Dispatchers.IO) {
                    abrirStream()?.use { stream ->
                        SpreadsheetParser.parsear(nombreArchivo, stream, mimeType)
                    } ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado.")
                }

                if (parsed.hojas.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            cargando = false,
                            errorMensaje = "El archivo no contiene filas ni hojas con datos."
                        )
                    }
                    return@launch
                }

                existentesA = repository.obtenerTrabajadoresPorTurnoOnce(Turno.A)
                existentesB = repository.obtenerTrabajadoresPorTurnoOnce(Turno.B)

                val primeraHoja = parsed.hojas.first()
                val mapeoInicial = ImportacionValidator.detectarMapeoAutomatico(primeraHoja.encabezados)
                val usarColumnaTurno = mapeoInicial.indiceTurno != null

                val preview = ImportacionValidator.construirPrevisualizacion(
                    filasCrudas = primeraHoja.filas,
                    mapeo = mapeoInicial,
                    usarColumnaTurnoArchivo = usarColumnaTurno,
                    turnoDestinoPorDefecto = turnoActivo,
                    existentesTurnoA = existentesA,
                    existentesTurnoB = existentesB
                )

                _uiState.update {
                    it.copy(
                        paso = PasoImportacion.MAPEO_Y_REVISION,
                        cargando = false,
                        archivo = parsed,
                        hojaSeleccionadaIdx = 0,
                        mapeo = mapeoInicial,
                        usarColumnaTurnoArchivo = usarColumnaTurno,
                        turnoDestinoPorDefecto = turnoActivo,
                        filasPreview = preview,
                        filtroPreview = FiltroImportacion.TODAS
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        cargando = false,
                        errorMensaje = "Error al leer el archivo: ${e.message ?: "formato inválido"}"
                    )
                }
            }
        }
    }

    fun seleccionarHoja(indice: Int) {
        val state = _uiState.value
        val hoja = state.archivo?.hojas?.getOrNull(indice) ?: return
        val nuevoMapeo = ImportacionValidator.detectarMapeoAutomatico(hoja.encabezados)
        val usarColTurno = nuevoMapeo.indiceTurno != null
        val preview = ImportacionValidator.construirPrevisualizacion(
            filasCrudas = hoja.filas,
            mapeo = nuevoMapeo,
            usarColumnaTurnoArchivo = usarColTurno,
            turnoDestinoPorDefecto = state.turnoDestinoPorDefecto,
            existentesTurnoA = existentesA,
            existentesTurnoB = existentesB
        )
        _uiState.update {
            it.copy(
                hojaSeleccionadaIdx = indice,
                mapeo = nuevoMapeo,
                usarColumnaTurnoArchivo = usarColTurno,
                filasPreview = preview
            )
        }
    }

    fun actualizarMapeo(campo: CampoImportacion, indiceColumna: Int?) {
        val state = _uiState.value
        val hoja = state.hojaActual ?: return
        val nuevoMapeo = state.mapeo.actualizarCampo(campo, indiceColumna)
        val preview = ImportacionValidator.construirPrevisualizacion(
            filasCrudas = hoja.filas,
            mapeo = nuevoMapeo,
            usarColumnaTurnoArchivo = state.usarColumnaTurnoArchivo && nuevoMapeo.indiceTurno != null,
            turnoDestinoPorDefecto = state.turnoDestinoPorDefecto,
            existentesTurnoA = existentesA,
            existentesTurnoB = existentesB
        )
        _uiState.update { it.copy(mapeo = nuevoMapeo, filasPreview = preview) }
    }

    fun cambiarModoTurno(usarColumnaArchivo: Boolean, turnoPorDefecto: Turno) {
        val state = _uiState.value
        val hoja = state.hojaActual ?: return
        val preview = ImportacionValidator.construirPrevisualizacion(
            filasCrudas = hoja.filas,
            mapeo = state.mapeo,
            usarColumnaTurnoArchivo = usarColumnaArchivo && state.mapeo.indiceTurno != null,
            turnoDestinoPorDefecto = turnoPorDefecto,
            existentesTurnoA = existentesA,
            existentesTurnoB = existentesB
        )
        _uiState.update {
            it.copy(
                usarColumnaTurnoArchivo = usarColumnaArchivo,
                turnoDestinoPorDefecto = turnoPorDefecto,
                filasPreview = preview
            )
        }
    }

    fun cambiarFiltroPreview(filtro: FiltroImportacion) {
        _uiState.update { it.copy(filtroPreview = filtro) }
    }

    fun toggleIncluirFila(numeroFila: Int) {
        _uiState.update { state ->
            val actualizadas = state.filasPreview.map { fila ->
                if (fila.numeroFila == numeroFila && !fila.tieneErrorBloqueante) {
                    val nuevaInclusion = !fila.incluida
                    val nuevaAccion = when {
                        !nuevaInclusion -> AccionImportacion.OMITIR
                        fila.accion == AccionImportacion.OMITIR && fila.candidatoExistente != null &&
                            fila.clasificacion == ClasificacionImportacion.EXISTENTE_CON_CAMBIOS ->
                            AccionImportacion.ACTUALIZAR_EXISTENTE
                        fila.accion == AccionImportacion.OMITIR -> AccionImportacion.AGREGAR_NUEVO
                        else -> fila.accion
                    }
                    fila.copy(
                        incluida = nuevaInclusion,
                        accion = nuevaAccion,
                        revisionResuelta = true
                    )
                } else {
                    fila
                }
            }
            state.copy(filasPreview = actualizadas)
        }
    }

    fun resolverRevisionFila(numeroFila: Int, accion: AccionImportacion) {
        _uiState.update { state ->
            val actualizadas = state.filasPreview.map { fila ->
                if (fila.numeroFila == numeroFila && !fila.tieneErrorBloqueante) {
                    val accionSegura = if (accion == AccionImportacion.ACTUALIZAR_EXISTENTE && fila.candidatoExistente == null) {
                        AccionImportacion.AGREGAR_NUEVO
                    } else {
                        accion
                    }
                    fila.copy(
                        accion = accionSegura,
                        revisionResuelta = true,
                        incluida = accionSegura != AccionImportacion.OMITIR
                    )
                } else {
                    fila
                }
            }
            state.copy(filasPreview = actualizadas)
        }
    }

    fun corregirFila(
        numeroFila: Int,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        pabellon: String,
        empresa: EmpresaOficial?,
        turno: Turno?
    ) {
        _uiState.update { state ->
            val editadas = state.filasPreview.map { fila ->
                if (fila.numeroFila == numeroFila) {
                    fila.copy(
                        nombre = nombre.trim(),
                        primerApellido = primerApellido.trim(),
                        segundoApellido = segundoApellido.trim(),
                        habitacion = habitacion.trim(),
                        pabellon = pabellon.trim(),
                        empresaResuelta = empresa,
                        turnoResuelto = turno,
                        accion = AccionImportacion.AGREGAR_NUEVO,
                        revisionResuelta = false,
                        incluida = true
                    )
                } else {
                    fila
                }
            }
            val revalidadas = ImportacionValidator.revalidarFilas(
                filas = editadas,
                existentesTurnoA = existentesA,
                existentesTurnoB = existentesB,
                mantenerInclusionUsuario = true
            )
            state.copy(filasPreview = revalidadas)
        }
    }

    fun confirmarImportacion(onCompletado: (ResultadoImportacion) -> Unit) {
        val state = _uiState.value
        val confirmadas = state.filasPreview
            .filter { it.seraProcesada }
            .mapNotNull { fila ->
                val emp = fila.empresaResuelta ?: return@mapNotNull null
                val turno = fila.turnoResuelto ?: return@mapNotNull null
                FilaImportacionConfirmada(
                    turno = turno,
                    empresa = emp,
                    nombre = fila.nombre,
                    primerApellido = fila.primerApellido,
                    segundoApellido = fila.segundoApellido,
                    habitacion = fila.habitacion,
                    pabellon = fila.pabellon,
                    accion = fila.accion,
                    trabajadorExistenteId = fila.candidatoExistente?.id
                )
            }

        if (confirmadas.isEmpty()) {
            _uiState.update {
                it.copy(
                    errorMensaje = if (state.totalRevisionPendiente > 0) {
                        "Tiene ${state.totalRevisionPendiente} registros pendientes de revisión o sin filas confirmadas."
                    } else {
                        "No hay filas nuevas ni actualizaciones confirmadas para importar."
                    }
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(cargando = true, errorMensaje = null) }
            val resultado = repository.importarTrabajadores(confirmadas)
            existentesA = repository.obtenerTrabajadoresPorTurnoOnce(Turno.A)
            existentesB = repository.obtenerTrabajadoresPorTurnoOnce(Turno.B)
            _uiState.update {
                ImportacionUiState(
                    paso = PasoImportacion.SELECCION_ARCHIVO,
                    formatoPreferido = state.formatoPreferido,
                    resultadoImportacion = resultado
                )
            }
            onCompletado(resultado)
        }
    }

    fun reiniciar() {
        val formato = _uiState.value.formatoPreferido
        _uiState.value = ImportacionUiState(formatoPreferido = formato)
    }

    fun limpiarResultado() {
        _uiState.update { it.copy(resultadoImportacion = null, errorMensaje = null) }
    }

    class Factory(private val repository: LavanderiaRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ImportacionViewModel(repository) as T
        }
    }
}
