package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.AvisoDuplicado
import com.example.domain.model.BuscadorTrabajadores
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.HardwarePerformanceConfig
import com.example.domain.model.ResumenEmpresaTurno
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FiltroEstadoMarcacion(val etiqueta: String) {
    TODOS("Todos"),
    SI_BAJO("SÍ BAJÓ"),
    NO_BAJO("NO BAJÓ")
}

@Immutable
data class MainUiState(
    val inicializado: Boolean = false,
    val turnoActivo: Turno = Turno.A,
    val conteoTurnoA: Int = 0,
    val conteoTurnoB: Int = 0,
    val conteoDatosPrueba: Int = 0,
    val conteoDatosReales: Int = 0,
    val busqueda: String = "",
    val empresaFiltro: EmpresaOficial? = null,
    val filtroEstado: FiltroEstadoMarcacion = FiltroEstadoMarcacion.TODOS,
    val trabajadoresTurno: List<TrabajadorConEstado> = emptyList(),
    val trabajadoresFiltrados: List<TrabajadorConEstado> = emptyList(),
    val resumenEmpresas: List<ResumenEmpresaTurno> = emptyList(),
    val totalSiBajo: Int = 0,
    val totalNoBajo: Int = 0
)

class MainViewModel(
    private val repository: LavanderiaRepository
) : ViewModel() {

    private val inicializadoFlow = MutableStateFlow(false)
    private val busquedaTextoFlow = MutableStateFlow("")

    @OptIn(FlowPreview::class)
    private val busquedaDebouncedFlow = busquedaTextoFlow
        .debounce { query ->
            if (query.isBlank()) 0L else HardwarePerformanceConfig.searchDebounceMs
        }
        .distinctUntilChanged()

    private val empresaFiltroFlow = MutableStateFlow<EmpresaOficial?>(null)
    private val filtroEstadoFlow = MutableStateFlow(FiltroEstadoMarcacion.TODOS)

    private val _eventosMensaje = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val eventosMensaje: SharedFlow<String> = _eventosMensaje.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.ensureInitialized()
            inicializadoFlow.value = true
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val baseTurnoFlow = repository.turnoActivo
        .flatMapLatest { turno ->
            repository.observeTrabajadoresConEstado(turno).map { trabajadores ->
                val ordenados = trabajadores.sortedWith(
                    compareBy(
                        { it.searchIndex.normPrimerApellido },
                        { it.searchIndex.normSegundoApellido },
                        { it.searchIndex.normNombre }
                    )
                )

                val totalesPorEmpresa = HashMap<Int, Int>(EmpresaOficial.todas.size * 2)
                val bajaronPorEmpresa = HashMap<Int, Int>(EmpresaOficial.todas.size * 2)
                var siBajoTotal = 0

                for (t in ordenados) {
                    val empId = t.empresa.id
                    totalesPorEmpresa[empId] = (totalesPorEmpresa[empId] ?: 0) + 1
                    if (t.siBajo) {
                        siBajoTotal++
                        bajaronPorEmpresa[empId] = (bajaronPorEmpresa[empId] ?: 0) + 1
                    }
                }

                val resumenEmpresas = EmpresaOficial.todas.map { emp ->
                    val totalEmp = totalesPorEmpresa[emp.id] ?: 0
                    val bajaronEmp = bajaronPorEmpresa[emp.id] ?: 0
                    ResumenEmpresaTurno(
                        empresa = emp,
                        totalTrabajadores = totalEmp,
                        siBajoCount = bajaronEmp,
                        noBajoCount = totalEmp - bajaronEmp
                    )
                }

                BaseTurnoSnapshot(
                    turno = turno,
                    ordenados = ordenados,
                    resumenEmpresas = resumenEmpresas,
                    totalSiBajo = siBajoTotal,
                    totalNoBajo = ordenados.size - siBajoTotal
                )
            }
        }
        .flowOn(Dispatchers.Default)

    private val resultadoFiltradoFlow = combine(
        baseTurnoFlow,
        busquedaDebouncedFlow,
        empresaFiltroFlow,
        filtroEstadoFlow
    ) { base, busquedaQuery, empresaFiltro, estadoFiltro ->
        val filtradosPorChip = if (empresaFiltro == null && estadoFiltro == FiltroEstadoMarcacion.TODOS) {
            base.ordenados
        } else {
            val acc = ArrayList<TrabajadorConEstado>(base.ordenados.size)
            for (t in base.ordenados) {
                val cumpleEmpresa = empresaFiltro == null || t.empresa == empresaFiltro
                val cumpleEstado = when (estadoFiltro) {
                    FiltroEstadoMarcacion.TODOS -> true
                    FiltroEstadoMarcacion.SI_BAJO -> t.siBajo
                    FiltroEstadoMarcacion.NO_BAJO -> !t.siBajo
                }
                if (cumpleEmpresa && cumpleEstado) {
                    acc.add(t)
                }
            }
            acc
        }

        val filtrados = BuscadorTrabajadores.filtrarYOrdenar(filtradosPorChip, busquedaQuery)

        ResultadoFiltradoSnapshot(
            base = base,
            empresaFiltro = empresaFiltro,
            estadoFiltro = estadoFiltro,
            filtrados = filtrados
        )
    }.flowOn(Dispatchers.Default)

    private val conteosFlow = combine(
        repository.observeConteoTurno(Turno.A),
        repository.observeConteoTurno(Turno.B),
        repository.observeConteoDatosPrueba(),
        repository.observeConteoDatosReales()
    ) { cA, cB, prueba, reales ->
        QuadConteos(cA, cB, prueba, reales)
    }

    val uiState: StateFlow<MainUiState> = combine(
        inicializadoFlow,
        busquedaTextoFlow,
        resultadoFiltradoFlow,
        conteosFlow
    ) { inicializado, busquedaTexto, resultado, conteos ->
        val base = resultado.base
        MainUiState(
            inicializado = inicializado,
            turnoActivo = base.turno,
            conteoTurnoA = conteos.turnoA,
            conteoTurnoB = conteos.turnoB,
            conteoDatosPrueba = conteos.prueba,
            conteoDatosReales = conteos.reales,
            busqueda = busquedaTexto,
            empresaFiltro = resultado.empresaFiltro,
            filtroEstado = resultado.estadoFiltro,
            trabajadoresTurno = base.ordenados,
            trabajadoresFiltrados = resultado.filtrados,
            resumenEmpresas = base.resumenEmpresas,
            totalSiBajo = base.totalSiBajo,
            totalNoBajo = base.totalNoBajo
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    fun seleccionarTurno(turno: Turno) {
        viewModelScope.launch {
            repository.setTurnoActivo(turno)
        }
    }

    fun actualizarBusqueda(texto: String) {
        busquedaTextoFlow.value = texto
    }

    fun seleccionarEmpresaFiltro(empresa: EmpresaOficial?) {
        empresaFiltroFlow.value = empresa
    }

    fun seleccionarEstadoFiltro(filtro: FiltroEstadoMarcacion) {
        filtroEstadoFlow.value = filtro
    }

    fun alternarSiBajo(trabajador: TrabajadorConEstado) {
        viewModelScope.launch {
            repository.alternarMarcacionSiBajo(trabajador)
        }
    }

    suspend fun verificarDuplicado(
        turno: Turno,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        empresa: EmpresaOficial,
        excluirId: String?
    ): AvisoDuplicado? {
        return repository.verificarDuplicadoEnTurno(
            turno = turno,
            nombre = nombre,
            primerApellido = primerApellido,
            segundoApellido = segundoApellido,
            habitacion = habitacion,
            empresa = empresa,
            excluirTrabajadorId = excluirId
        )
    }

    fun guardarTrabajador(
        trabajadorExistenteId: String?,
        turno: Turno,
        empresa: EmpresaOficial,
        nombre: String,
        primerApellido: String,
        segundoApellido: String,
        habitacion: String,
        pabellon: String
    ) {
        viewModelScope.launch {
            if (trabajadorExistenteId == null) {
                repository.agregarTrabajador(
                    turno = turno,
                    empresa = empresa,
                    nombre = nombre,
                    primerApellido = primerApellido,
                    segundoApellido = segundoApellido,
                    habitacion = habitacion,
                    pabellon = pabellon
                )
                _eventosMensaje.emit("Trabajador agregado al ${turno.nombre}.")
            } else {
                repository.actualizarTrabajador(
                    trabajadorId = trabajadorExistenteId,
                    empresa = empresa,
                    nombre = nombre,
                    primerApellido = primerApellido,
                    segundoApellido = segundoApellido,
                    habitacion = habitacion,
                    pabellon = pabellon
                )
                _eventosMensaje.emit("Datos del trabajador actualizados.")
            }
        }
    }

    fun eliminarTrabajador(trabajador: TrabajadorConEstado) {
        viewModelScope.launch {
            repository.eliminarTrabajador(trabajador.id)
            _eventosMensaje.emit("${trabajador.nombreCompleto} eliminado del ${trabajador.turno.nombre}.")
        }
    }

    fun eliminarTrabajadores(trabajadores: List<TrabajadorConEstado>) {
        if (trabajadores.isEmpty()) return
        val turno = uiState.value.turnoActivo
        val ids = trabajadores.filter { it.turno == turno }.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val eliminados = repository.eliminarTrabajadoresPorIds(ids)
            _eventosMensaje.emit("Se eliminaron $eliminados trabajadores de ${turno.nombre}.")
        }
    }

    fun limpiarRegistroDePrueba() {
        val turno = uiState.value.turnoActivo
        viewModelScope.launch {
            val marcacionesLimpiadas = repository.limpiarRegistroDePruebaPorTurno(turno)
            _eventosMensaje.emit(
                "Registro temporal de ${turno.nombre} limpiado ($marcacionesLimpiadas marcas reiniciadas). Trabajadores conservados."
            )
        }
    }

    fun emitirMensaje(mensaje: String) {
        viewModelScope.launch {
            _eventosMensaje.emit(mensaje)
        }
    }

    private data class QuadConteos(
        val turnoA: Int,
        val turnoB: Int,
        val prueba: Int,
        val reales: Int
    )

    private data class BaseTurnoSnapshot(
        val turno: Turno,
        val ordenados: List<TrabajadorConEstado>,
        val resumenEmpresas: List<ResumenEmpresaTurno>,
        val totalSiBajo: Int,
        val totalNoBajo: Int
    )

    private data class ResultadoFiltradoSnapshot(
        val base: BaseTurnoSnapshot,
        val empresaFiltro: EmpresaOficial?,
        val estadoFiltro: FiltroEstadoMarcacion,
        val filtrados: List<TrabajadorConEstado>
    )

    class Factory(private val repository: LavanderiaRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}
