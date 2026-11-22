package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ocr.OcrImageProcessor
import com.example.data.ocr.ResultadoOcrFoto
import com.example.data.ocr.ResultadoPreparacionTrabajo
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.AjusteFotoPlanilla
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.EstadoFotoDescarga
import com.example.domain.model.FotoLoteDescargaUi
import com.example.domain.model.OcrNombreMatcher
import com.example.domain.model.PendienteDescargaUi
import com.example.domain.model.PrevisualizacionConciliacion
import com.example.domain.model.ResumenEmpresaTurno
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Immutable
data class DescargaUiState(
    val turnoActivo: Turno = Turno.A,
    val empresaSeleccionada: EmpresaOficial = EmpresaOficial.KOMATSU,
    val trabajadoresEmpresa: List<TrabajadorConEstado> = emptyList(),
    val identificadosEnFotos: List<TrabajadorConEstado> = emptyList(),
    val siBajoEmpresaCount: Int = 0,
    val noBajoEmpresaCount: Int = 0,
    val confirmadosEnFotosIds: Set<String> = emptySet(),
    val pendientes: List<PendienteDescargaUi> = emptyList(),
    val fotosLote: List<FotoLoteDescargaUi> = emptyList(),
    val tieneFotoIlegible: Boolean = false,
    val procesandoOcr: Boolean = false,
    val procesandoDetalle: String = "",
    val correccionesAprendidasCount: Int = 0,
    val resumenEmpresasTurno: List<ResumenEmpresaTurno> = emptyList(),
    val previsualizacionConciliacion: PrevisualizacionConciliacion? = null,
    val puedeFinalizarConciliacion: Boolean = false
)

class DescargaViewModel(
    private val repository: LavanderiaRepository
) : ViewModel() {

    private val empresaSeleccionadaFlow = MutableStateFlow(EmpresaOficial.KOMATSU)
    private val fotosPorContextoFlow = MutableStateFlow<Map<Pair<String, Int>, List<FotoLoteDescargaUi>>>(emptyMap())
    private val procesandoOcrFlow = MutableStateFlow(false)
    private val procesandoDetalleFlow = MutableStateFlow("")
    private val previsualizacionFlow = MutableStateFlow<PrevisualizacionConciliacion?>(null)

    private val _eventosMensaje = MutableSharedFlow<String>(extraBufferCapacity = 12)
    val eventosMensaje: SharedFlow<String> = _eventosMensaje.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val contextoActivoFlow = combine(
        repository.turnoActivo,
        empresaSeleccionadaFlow
    ) { turno, empresa -> turno to empresa }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val trabajadoresTurnoFlow = repository.turnoActivo.flatMapLatest { turno ->
        repository.observeTrabajadoresConEstado(turno)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recepcionesTurnoFlow = repository.turnoActivo.flatMapLatest { turno ->
        repository.observeRecepcionesSesionAbierta(turno)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val correccionesContextoFlow = contextoActivoFlow.flatMapLatest { (turno, empresa) ->
        repository.observeCorreccionesOcr(turno, empresa)
    }

    private val extrasUiFlow = combine(
        fotosPorContextoFlow,
        procesandoOcrFlow,
        procesandoDetalleFlow,
        previsualizacionFlow
    ) { fotosMap, procesando, detalle, prev ->
        ExtrasDescargaUi(
            fotosPorContexto = fotosMap,
            procesandoOcr = procesando,
            procesandoDetalle = detalle,
            previsualizacion = prev
        )
    }

    val uiState: StateFlow<DescargaUiState> = combine(
        contextoActivoFlow,
        trabajadoresTurnoFlow,
        recepcionesTurnoFlow,
        correccionesContextoFlow,
        extrasUiFlow
    ) { (turno, empresa), trabajadoresTurno, recepcionesTurno, correcciones, extras ->
        val totalesPorEmp = HashMap<Int, Int>(EmpresaOficial.todas.size * 2)
        val siBajoPorEmp = HashMap<Int, Int>(EmpresaOficial.todas.size * 2)
        val trabajadoresEmpresaRaw = mutableListOf<TrabajadorConEstado>()

        for (t in trabajadoresTurno) {
            val empId = t.empresa.id
            totalesPorEmp[empId] = (totalesPorEmp[empId] ?: 0) + 1
            if (t.siBajo) {
                siBajoPorEmp[empId] = (siBajoPorEmp[empId] ?: 0) + 1
            }
            if (t.empresa == empresa) {
                trabajadoresEmpresaRaw.add(t)
            }
        }

        val resumenEmpresas = EmpresaOficial.todas.map { emp ->
            val total = totalesPorEmp[emp.id] ?: 0
            val siBajo = siBajoPorEmp[emp.id] ?: 0
            ResumenEmpresaTurno(
                empresa = emp,
                totalTrabajadores = total,
                siBajoCount = siBajo,
                noBajoCount = total - siBajo
            )
        }

        val trabajadoresEmpresa = TextoNormalizer.ordenarPorHabitacion(trabajadoresEmpresaRaw)
        val siBajoEmpresa = trabajadoresEmpresa.count { it.siBajo }
        val noBajoEmpresa = trabajadoresEmpresa.size - siBajoEmpresa

        val recepcionesEmpresa = recepcionesTurno.filter { it.empresaIdSnapshot == empresa.id }
        val confirmadosEnFotosIds = recepcionesEmpresa
            .filter { it.confirmado && it.trabajadorId != null }
            .mapNotNull { it.trabajadorId }
            .toSet()

        val identificadosEnFotos = trabajadoresEmpresa.filter { it.id in confirmadosEnFotosIds }

        val pendientesUi = recepcionesEmpresa
            .filter { !it.confirmado }
            .map { reg ->
                val (limpio, _) = OcrNombreMatcher.limpiarTextoNombreDesdeLinea(reg.textoOriginal)
                val clave = limpio.ifBlank { OcrNombreMatcher.normalizarClaveOcr(reg.textoOriginal) }
                val candidatos = OcrNombreMatcher.buscarCandidatosParaTextoPendiente(
                    textoPendiente = reg.textoOriginal,
                    trabajadoresEmpresaTurno = trabajadoresEmpresa
                )
                PendienteDescargaUi(
                    registroId = reg.id,
                    textoOriginal = reg.textoOriginal,
                    textoNormalizado = clave,
                    motivo = reg.nombreConfirmadoSnapshot.ifBlank { "Requiere confirmación manual" },
                    candidatosSugeridos = candidatos
                )
            }

        val fotosLote = extras.fotosPorContexto[turno.id to empresa.id].orEmpty()
        val tieneFotoIlegible = fotosLote.any { it.estado == EstadoFotoDescarga.ERROR_ILEGIBLE }
        val puedeFinalizar = confirmadosEnFotosIds.isNotEmpty() &&
            pendientesUi.isEmpty() &&
            !tieneFotoIlegible &&
            !extras.procesandoOcr

        DescargaUiState(
            turnoActivo = turno,
            empresaSeleccionada = empresa,
            trabajadoresEmpresa = trabajadoresEmpresa,
            identificadosEnFotos = identificadosEnFotos,
            siBajoEmpresaCount = siBajoEmpresa,
            noBajoEmpresaCount = noBajoEmpresa,
            confirmadosEnFotosIds = confirmadosEnFotosIds,
            pendientes = pendientesUi,
            fotosLote = fotosLote,
            tieneFotoIlegible = tieneFotoIlegible,
            procesandoOcr = extras.procesandoOcr,
            procesandoDetalle = extras.procesandoDetalle,
            correccionesAprendidasCount = correcciones.size,
            resumenEmpresasTurno = resumenEmpresas,
            previsualizacionConciliacion = extras.previsualizacion,
            puedeFinalizarConciliacion = puedeFinalizar
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DescargaUiState()
        )

    fun seleccionarEmpresa(empresa: EmpresaOficial) {
        empresaSeleccionadaFlow.value = empresa
        previsualizacionFlow.value = null
    }

    fun procesarFotoCamara(
        context: Context,
        uri: Uri,
        archivoTemporalCamara: File?
    ) {
        viewModelScope.launch {
            val appCtx = context.applicationContext
            procesandoOcrFlow.value = true
            procesandoDetalleFlow.value = "Preparando captura de cámara…"
            try {
                val turno = repository.turnoActivo.first()
                val empresa = empresaSeleccionadaFlow.value
                val numFoto = (fotosPorContextoFlow.value[turno.id to empresa.id]?.size ?: 0) + 1
                val etiqueta = "Foto cámara #$numFoto"

                when (val prep = OcrImageProcessor.prepararArchivoTrabajo(appCtx, uri, archivoTemporalCamara)) {
                    is ResultadoPreparacionTrabajo.ErrorCarga -> {
                        registrarErrorCargaFoto(turno, empresa, etiqueta, prep.motivo)
                    }
                    is ResultadoPreparacionTrabajo.Lista -> {
                        val resultado = OcrImageProcessor.procesarArchivoTrabajo(
                            context = appCtx,
                            archivoTrabajo = prep.archivoTrabajo,
                            ajuste = AjusteFotoPlanilla(),
                            onProgreso = { msg -> procesandoDetalleFlow.value = "$etiqueta: $msg" }
                        )
                        manejarResultadoOcr(
                            turno = turno,
                            empresa = empresa,
                            etiqueta = etiqueta,
                            archivoTrabajoPath = prep.archivoTrabajo.absolutePath,
                            ajuste = AjusteFotoPlanilla(),
                            fotoIdExistente = null,
                            resultado = resultado
                        )
                    }
                }
            } finally {
                OcrImageProcessor.eliminarArchivoTemporalSiEsInterno(appCtx, archivoTemporalCamara)
                procesandoOcrFlow.value = false
                procesandoDetalleFlow.value = ""
            }
        }
    }

    fun cancelarFotoCamara(context: Context, archivoTemporalCamara: File?) {
        OcrImageProcessor.eliminarArchivoTemporalSiEsInterno(context.applicationContext, archivoTemporalCamara)
    }

    fun procesarFotosGaleria(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val appCtx = context.applicationContext
            procesandoOcrFlow.value = true
            try {
                val turno = repository.turnoActivo.first()
                val empresa = empresaSeleccionadaFlow.value
                var baseNum = (fotosPorContextoFlow.value[turno.id to empresa.id]?.size ?: 0)
                val totalUris = uris.size

                uris.forEachIndexed { idx, uri ->
                    baseNum += 1
                    val etiqueta = "Foto galería #$baseNum"
                    procesandoDetalleFlow.value = "Cargando imagen ${idx + 1} de $totalUris…"
                    when (val prep = OcrImageProcessor.prepararArchivoTrabajo(appCtx, uri, null)) {
                        is ResultadoPreparacionTrabajo.ErrorCarga -> {
                            registrarErrorCargaFoto(turno, empresa, etiqueta, prep.motivo)
                        }
                        is ResultadoPreparacionTrabajo.Lista -> {
                            val resultado = OcrImageProcessor.procesarArchivoTrabajo(
                                context = appCtx,
                                archivoTrabajo = prep.archivoTrabajo,
                                ajuste = AjusteFotoPlanilla(),
                                onProgreso = { msg ->
                                    procesandoDetalleFlow.value = if (totalUris > 1) {
                                        "Foto ${idx + 1}/$totalUris · $msg"
                                    } else {
                                        "$etiqueta · $msg"
                                    }
                                }
                            )
                            manejarResultadoOcr(
                                turno = turno,
                                empresa = empresa,
                                etiqueta = etiqueta,
                                archivoTrabajoPath = prep.archivoTrabajo.absolutePath,
                                ajuste = AjusteFotoPlanilla(),
                                fotoIdExistente = null,
                                resultado = resultado
                            )
                        }
                    }
                }
            } finally {
                procesandoOcrFlow.value = false
                procesandoDetalleFlow.value = ""
            }
        }
    }

    fun reprocesarFotoConAjuste(
        context: Context,
        fotoId: String,
        nuevoAjuste: AjusteFotoPlanilla
    ) {
        viewModelScope.launch {
            val appCtx = context.applicationContext
            val turno = repository.turnoActivo.first()
            val empresa = empresaSeleccionadaFlow.value
            val key = turno.id to empresa.id
            val fotoActual = fotosPorContextoFlow.value[key]?.firstOrNull { it.id == fotoId } ?: return@launch
            val path = fotoActual.archivoTrabajoPath
            if (path.isNullOrBlank()) {
                _eventosMensaje.emit("Esta entrada no tiene archivo temporal editable.")
                return@launch
            }
            val archivoTrabajo = File(path)
            if (!archivoTrabajo.exists() || archivoTrabajo.length() <= 0L) {
                _eventosMensaje.emit("El archivo temporal expiró. Carga o toma la fotografía nuevamente.")
                return@launch
            }

            procesandoOcrFlow.value = true
            procesandoDetalleFlow.value = "Reprocesando ${fotoActual.etiqueta} con nuevo encuadre…"
            try {
                val resultado = OcrImageProcessor.procesarArchivoTrabajo(
                    context = appCtx,
                    archivoTrabajo = archivoTrabajo,
                    ajuste = nuevoAjuste,
                    onProgreso = { msg -> procesandoDetalleFlow.value = "${fotoActual.etiqueta}: $msg" }
                )
                manejarResultadoOcr(
                    turno = turno,
                    empresa = empresa,
                    etiqueta = fotoActual.etiqueta,
                    archivoTrabajoPath = path,
                    ajuste = nuevoAjuste,
                    fotoIdExistente = fotoId,
                    resultado = resultado
                )
            } finally {
                procesandoOcrFlow.value = false
                procesandoDetalleFlow.value = ""
            }
        }
    }

    suspend fun procesarLineasOcrManualesOPrueba(
        turno: Turno,
        empresa: EmpresaOficial,
        etiqueta: String,
        lineas: List<String>
    ) {
        val resultado = if (lineas.isEmpty()) {
            ResultadoOcrFoto.ErrorOcrSinTexto("La fotografía no contiene texto legible.")
        } else {
            ResultadoOcrFoto.Exito(
                lineasOrdenadas = lineas,
                detalle = com.example.data.ocr.DetalleOcrPlanilla(
                    filasDetectadas = lineas.size,
                    filasLegibles = lineas.size,
                    filasIlegibles = 0,
                    correccionPerspectivaAplicada = false,
                    usoTablaGrid = false,
                    usoOpenCv = false,
                    columnaNombreRecortada = false,
                    motorDescripcion = "Prueba OCR"
                )
            )
        }
        manejarResultadoOcr(
            turno = turno,
            empresa = empresa,
            etiqueta = etiqueta,
            archivoTrabajoPath = null,
            ajuste = AjusteFotoPlanilla(),
            fotoIdExistente = null,
            resultado = resultado
        )
    }

    private suspend fun registrarErrorCargaFoto(
        turno: Turno,
        empresa: EmpresaOficial,
        etiqueta: String,
        motivo: String
    ) {
        agregarOActualizarFotoEnLote(
            turno = turno,
            empresa = empresa,
            fotoIdExistente = null,
            foto = FotoLoteDescargaUi(
                id = UUID.randomUUID().toString(),
                etiqueta = etiqueta,
                estado = EstadoFotoDescarga.ERROR_ILEGIBLE,
                mensajeDetalle = motivo
            )
        )
        _eventosMensaje.emit("$etiqueta: $motivo")
    }

    private suspend fun manejarResultadoOcr(
        turno: Turno,
        empresa: EmpresaOficial,
        etiqueta: String,
        archivoTrabajoPath: String?,
        ajuste: AjusteFotoPlanilla,
        fotoIdExistente: String?,
        resultado: ResultadoOcrFoto
    ) {
        val errorMsg = resultado.mensajeErrorOrNull
        if (errorMsg != null) {
            agregarOActualizarFotoEnLote(
                turno = turno,
                empresa = empresa,
                fotoIdExistente = fotoIdExistente,
                foto = FotoLoteDescargaUi(
                    id = fotoIdExistente ?: UUID.randomUUID().toString(),
                    etiqueta = etiqueta,
                    estado = EstadoFotoDescarga.ERROR_ILEGIBLE,
                    mensajeDetalle = errorMsg,
                    archivoTrabajoPath = archivoTrabajoPath,
                    ajuste = ajuste
                )
            )
            _eventosMensaje.emit("$etiqueta: $errorMsg")
            return
        }

        val exito = resultado as ResultadoOcrFoto.Exito
        val analisis = withContext(Dispatchers.Default) {
            val trabajadoresEmpresa = repository.observeTrabajadoresConEstado(turno)
                .first()
                .filter { it.empresa == empresa }
            val correcciones = repository.getCorreccionesOcrOnce(turno, empresa)
            OcrNombreMatcher.analizarLineas(
                lineasCrudas = exito.lineasOrdenadas,
                trabajadoresEmpresaTurno = trabajadoresEmpresa,
                correccionesEmpresaTurno = correcciones
            )
        }

        if (analisis.autoConfirmados.isEmpty() && analisis.pendientes.isEmpty()) {
            val msgNoMatch = "Se leyeron ${exito.detalle.filasDetectadas} fila(s), pero ninguna coincidió con ${empresa.nombre}. Prueba ajustar recorte o rotación."
            agregarOActualizarFotoEnLote(
                turno = turno,
                empresa = empresa,
                fotoIdExistente = fotoIdExistente,
                foto = FotoLoteDescargaUi(
                    id = fotoIdExistente ?: UUID.randomUUID().toString(),
                    etiqueta = etiqueta,
                    estado = EstadoFotoDescarga.ERROR_ILEGIBLE,
                    mensajeDetalle = msgNoMatch,
                    archivoTrabajoPath = archivoTrabajoPath,
                    ajuste = ajuste,
                    filasDetectadas = exito.detalle.filasDetectadas,
                    filasLegibles = exito.detalle.filasLegibles,
                    filasIlegibles = exito.detalle.filasIlegibles
                )
            )
            _eventosMensaje.emit("$etiqueta: sin nombres reconocibles para ${empresa.nombre}.")
            return
        }

        val resumen = repository.aplicarResultadoFotografiaOcr(turno, empresa, analisis)
        val detalle = buildString {
            append("${exito.detalle.filasDetectadas} fila(s) · ")
            append("${resumen.autoMarcadosNuevos} marcados SÍ BAJÓ")
            if (resumen.yaConfirmadosEnLote > 0) {
                append(", ${resumen.yaConfirmadosEnLote} ya en lote")
            }
            if (resumen.nuevosPendientes > 0) {
                append(", ${resumen.nuevosPendientes} pendientes")
            }
        }
        agregarOActualizarFotoEnLote(
            turno = turno,
            empresa = empresa,
            fotoIdExistente = fotoIdExistente,
            foto = FotoLoteDescargaUi(
                id = fotoIdExistente ?: UUID.randomUUID().toString(),
                etiqueta = etiqueta,
                estado = EstadoFotoDescarga.PROCESADA_OK,
                mensajeDetalle = detalle,
                archivoTrabajoPath = archivoTrabajoPath,
                ajuste = ajuste,
                filasDetectadas = exito.detalle.filasDetectadas,
                filasLegibles = exito.detalle.filasLegibles,
                filasIlegibles = exito.detalle.filasIlegibles,
                identificadosEnFotoCount = analisis.autoConfirmados.size,
                pendientesEnFotoCount = analisis.pendientes.size
            )
        )
        _eventosMensaje.emit("$etiqueta procesada: $detalle.")
    }

    private fun agregarOActualizarFotoEnLote(
        turno: Turno,
        empresa: EmpresaOficial,
        fotoIdExistente: String?,
        foto: FotoLoteDescargaUi
    ) {
        val key = turno.id to empresa.id
        fotosPorContextoFlow.update { actual ->
            val listaActual = actual[key].orEmpty()
            val nuevaLista = if (fotoIdExistente != null && listaActual.any { it.id == fotoIdExistente }) {
                listaActual.map { if (it.id == fotoIdExistente) foto else it }
            } else {
                listaActual + foto
            }
            actual + (key to nuevaLista)
        }
    }

    fun descartarFotoLote(context: Context, fotoId: String) {
        viewModelScope.launch {
            val turno = repository.turnoActivo.first()
            val empresa = empresaSeleccionadaFlow.value
            val key = turno.id to empresa.id
            var archivoABorrar: File? = null
            fotosPorContextoFlow.update { actual ->
                val lista = actual[key].orEmpty()
                archivoABorrar = lista.firstOrNull { it.id == fotoId }?.archivoTrabajoPath?.let { File(it) }
                actual + (key to lista.filterNot { it.id == fotoId })
            }
            OcrImageProcessor.eliminarArchivoTemporalSiEsInterno(context.applicationContext, archivoABorrar)
        }
    }

    fun descartarFotoLote(fotoId: String) {
        viewModelScope.launch {
            val turno = repository.turnoActivo.first()
            val empresa = empresaSeleccionadaFlow.value
            val key = turno.id to empresa.id
            fotosPorContextoFlow.update { actual ->
                val filtrada = actual[key].orEmpty().filterNot { it.id == fotoId }
                actual + (key to filtrada)
            }
        }
    }

    fun confirmarPendiente(pendienteId: String, trabajador: TrabajadorConEstado) {
        viewModelScope.launch {
            repository.confirmarPendienteDescarga(
                pendienteId = pendienteId,
                trabajador = trabajador,
                guardarEnMemoria = true
            )
            _eventosMensaje.emit(
                "${trabajador.nombreCompleto} confirmado como SÍ BAJÓ y guardado en memoria OCR."
            )
        }
    }

    fun descartarPendiente(pendienteId: String) {
        viewModelScope.launch {
            repository.descartarPendienteDescarga(pendienteId)
            _eventosMensaje.emit("Línea pendiente descartada.")
        }
    }

    fun solicitarPrevisualizacionConciliacion() {
        viewModelScope.launch {
            val current = uiState.value
            if (current.tieneFotoIlegible) {
                _eventosMensaje.emit("Corrige (recorta/rota) o descarta las fotografías con error antes de finalizar la descarga.")
                return@launch
            }
            if (current.pendientes.isNotEmpty()) {
                _eventosMensaje.emit("Resuelve los ${current.pendientes.size} nombres pendientes antes de conciliar.")
                return@launch
            }
            if (current.confirmadosEnFotosIds.isEmpty()) {
                _eventosMensaje.emit("Escanea al menos una fotografía legible antes de conciliar.")
                return@launch
            }
            val preview = repository.previsualizarConciliacionDescarga(
                turno = current.turnoActivo,
                empresa = current.empresaSeleccionada
            )
            previsualizacionFlow.value = preview
        }
    }

    fun cancelarPrevisualizacionConciliacion() {
        previsualizacionFlow.value = null
    }

    fun confirmarConciliacionFinal() {
        val preview = previsualizacionFlow.value ?: return
        viewModelScope.launch {
            try {
                val resultado = repository.finalizarYConciliarDescarga(
                    turno = preview.turno,
                    empresa = preview.empresa
                )
                previsualizacionFlow.value = null
                val extraRevertidos = if (resultado.revertidosANoBajo > 0) {
                    " (${resultado.revertidosANoBajo} pasaron a NO BAJÓ al no figurar en fotos)"
                } else {
                    ""
                }
                _eventosMensaje.emit(
                    "Conciliación ${resultado.empresa.nombre}: ${resultado.totalSiBajo} SÍ BAJÓ, ${resultado.totalNoBajo} NO BAJÓ$extraRevertidos."
                )
            } catch (e: Exception) {
                previsualizacionFlow.value = null
                _eventosMensaje.emit(e.message ?: "No se pudo completar la conciliación.")
            }
        }
    }

    fun limpiarRegistroOperativoTurno(context: Context? = null) {
        viewModelScope.launch {
            val turno = repository.turnoActivo.first()
            val eliminadas = repository.limpiarRegistroDePruebaPorTurno(turno)
            val archivosABorrar = mutableListOf<File>()
            fotosPorContextoFlow.update { actual ->
                actual.filterKeys { (tId, _) -> tId == turno.id }
                    .values
                    .flatten()
                    .mapNotNullTo(archivosABorrar) { it.archivoTrabajoPath?.let(::File) }
                actual.filterKeys { (tId, _) -> tId != turno.id }
            }
            if (context != null) {
                val appCtx = context.applicationContext
                archivosABorrar.forEach { file ->
                    OcrImageProcessor.eliminarArchivoTemporalSiEsInterno(appCtx, file)
                }
            }
            previsualizacionFlow.value = null
            _eventosMensaje.emit(
                "Registro operativo de ${turno.nombre} limpiado ($eliminadas marcas reiniciadas). Maestro y memoria OCR conservados."
            )
        }
    }

    fun reiniciarLoteFotosEmpresaActual(context: Context? = null) {
        viewModelScope.launch {
            val turno = repository.turnoActivo.first()
            val empresa = empresaSeleccionadaFlow.value
            repository.reiniciarLoteFotosEmpresa(turno, empresa)
            var archivosABorrar: List<File> = emptyList()
            fotosPorContextoFlow.update { actual ->
                archivosABorrar = actual[turno.id to empresa.id].orEmpty()
                    .mapNotNull { it.archivoTrabajoPath?.let(::File) }
                actual - (turno.id to empresa.id)
            }
            if (context != null) {
                val appCtx = context.applicationContext
                archivosABorrar.forEach { file ->
                    OcrImageProcessor.eliminarArchivoTemporalSiEsInterno(appCtx, file)
                }
            }
            previsualizacionFlow.value = null
            _eventosMensaje.emit("Lote de fotos de ${empresa.nombre} reiniciado para nueva lectura.")
        }
    }

    private data class ExtrasDescargaUi(
        val fotosPorContexto: Map<Pair<String, Int>, List<FotoLoteDescargaUi>>,
        val procesandoOcr: Boolean,
        val procesandoDetalle: String,
        val previsualizacion: PrevisualizacionConciliacion?
    )

    class Factory(private val repository: LavanderiaRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DescargaViewModel(repository) as T
        }
    }
}
