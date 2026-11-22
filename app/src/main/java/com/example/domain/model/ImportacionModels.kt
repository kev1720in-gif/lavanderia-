package com.example.domain.model

enum class CampoImportacion(val etiqueta: String, val obligatorio: Boolean) {
    PRIMER_APELLIDO("Primer apellido", true),
    SEGUNDO_APELLIDO("Segundo apellido", false),
    NOMBRE("Nombre", true),
    HABITACION("Habitación", false),
    PABELLON("Pabellón o piso", false),
    EMPRESA("Empresa", true),
    TURNO("Turno", false)
}

data class MapeoColumnas(
    val indicePrimerApellido: Int? = null,
    val indiceSegundoApellido: Int? = null,
    val indiceNombre: Int? = null,
    val indiceHabitacion: Int? = null,
    val indicePabellon: Int? = null,
    val indiceEmpresa: Int? = null,
    val indiceTurno: Int? = null
) {
    fun obtenerIndice(campo: CampoImportacion): Int? = when (campo) {
        CampoImportacion.PRIMER_APELLIDO -> indicePrimerApellido
        CampoImportacion.SEGUNDO_APELLIDO -> indiceSegundoApellido
        CampoImportacion.NOMBRE -> indiceNombre
        CampoImportacion.HABITACION -> indiceHabitacion
        CampoImportacion.PABELLON -> indicePabellon
        CampoImportacion.EMPRESA -> indiceEmpresa
        CampoImportacion.TURNO -> indiceTurno
    }

    fun actualizarCampo(campo: CampoImportacion, indiceColumna: Int?): MapeoColumnas = when (campo) {
        CampoImportacion.PRIMER_APELLIDO -> copy(indicePrimerApellido = indiceColumna)
        CampoImportacion.SEGUNDO_APELLIDO -> copy(indiceSegundoApellido = indiceColumna)
        CampoImportacion.NOMBRE -> copy(indiceNombre = indiceColumna)
        CampoImportacion.HABITACION -> copy(indiceHabitacion = indiceColumna)
        CampoImportacion.PABELLON -> copy(indicePabellon = indiceColumna)
        CampoImportacion.EMPRESA -> copy(indiceEmpresa = indiceColumna)
        CampoImportacion.TURNO -> copy(indiceTurno = indiceColumna)
    }

    fun faltantesObligatorios(): List<CampoImportacion> {
        return CampoImportacion.entries.filter { it.obligatorio && obtenerIndice(it) == null }
    }
}

enum class TipoProblemaImportacion(val etiqueta: String, val bloqueantePorDefecto: Boolean) {
    NOMBRE_INCOMPLETO("Nombre incompleto", true),
    EMPRESA_DESCONOCIDA("Empresa desconocida", true),
    TURNO_NO_RECONOCIDO("Turno no reconocido", true),
    DUPLICADO_EN_TURNO("Ya existe en el turno", false),
    DUPLICADO_EN_ARCHIVO("Duplicado en el archivo", true),
    HABITACION_VACIA("Habitación vacía", false),
    NOMBRE_IGUAL_POSIBLE_CONFUSION("Requiere revisión", false)
}

enum class ClasificacionImportacion(val etiqueta: String) {
    NUEVO("Nuevo"),
    YA_EXISTE_IDENTICO("Ya existe"),
    EXISTENTE_CON_CAMBIOS("Posible existente"),
    REQUIERE_REVISION("Requiere revisión"),
    ERROR_DATOS("Datos inválidos")
}

enum class AccionImportacion(val etiqueta: String) {
    AGREGAR_NUEVO("Agregar como nuevo"),
    ACTUALIZAR_EXISTENTE("Actualizar existente"),
    OMITIR("Omitir")
}

data class CandidatoExistente(
    val id: String,
    val nombreCompletoMayusculas: String,
    val habitacionMayusculas: String,
    val pabellonMayusculas: String,
    val empresa: EmpresaOficial?
)

data class ProblemaImportacion(
    val tipo: TipoProblemaImportacion,
    val detalle: String
)

data class FilaImportacionPreview(
    val numeroFila: Int,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String,
    val habitacion: String,
    val pabellon: String,
    val empresaTextoOriginal: String,
    val empresaResuelta: EmpresaOficial?,
    val turnoTextoOriginal: String,
    val turnoResuelto: Turno?,
    val problemas: List<ProblemaImportacion>,
    val clasificacion: ClasificacionImportacion = ClasificacionImportacion.NUEVO,
    val candidatoExistente: CandidatoExistente? = null,
    val accion: AccionImportacion = AccionImportacion.AGREGAR_NUEVO,
    val revisionResuelta: Boolean = true,
    val incluida: Boolean
) {
    val tieneErrorBloqueante: Boolean
        get() = clasificacion == ClasificacionImportacion.ERROR_DATOS ||
            problemas.any { it.tipo.bloqueantePorDefecto } ||
            nombre.isBlank() ||
            primerApellido.isBlank() ||
            empresaResuelta == null ||
            turnoResuelto == null

    val requiereRevisionPendiente: Boolean
        get() = clasificacion == ClasificacionImportacion.REQUIERE_REVISION && !revisionResuelta

    val tieneAdvertencia: Boolean
        get() = !tieneErrorBloqueante && (
            clasificacion == ClasificacionImportacion.REQUIERE_REVISION ||
                clasificacion == ClasificacionImportacion.EXISTENTE_CON_CAMBIOS ||
                clasificacion == ClasificacionImportacion.YA_EXISTE_IDENTICO ||
                problemas.isNotEmpty()
            )

    val seraProcesada: Boolean
        get() = incluida &&
            !tieneErrorBloqueante &&
            !requiereRevisionPendiente &&
            accion != AccionImportacion.OMITIR

    val nombreCompletoPreview: String
        get() = TextoNormalizer.formatearNombreObligatorio(
            primerApellido = primerApellido,
            segundoApellido = segundoApellido,
            nombre = nombre
        ).ifBlank { "(SIN NOMBRE)" }
}

data class FilaImportacionConfirmada(
    val turno: Turno,
    val empresa: EmpresaOficial,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String,
    val habitacion: String,
    val pabellon: String,
    val accion: AccionImportacion = AccionImportacion.AGREGAR_NUEVO,
    val trabajadorExistenteId: String? = null
)

data class ResultadoImportacion(
    val insertados: Int,
    val actualizados: Int = 0,
    val omitidos: Int = 0,
    val insertadosTurnoA: Int,
    val insertadosTurnoB: Int,
    val actualizadosTurnoA: Int = 0,
    val actualizadosTurnoB: Int = 0
)
