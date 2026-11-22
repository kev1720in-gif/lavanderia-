package com.example.data.importer

import com.example.data.local.entities.TrabajadorEntity
import com.example.domain.model.AccionImportacion
import com.example.domain.model.CandidatoExistente
import com.example.domain.model.ClasificacionImportacion
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.FilaImportacionPreview
import com.example.domain.model.MapeoColumnas
import com.example.domain.model.ProblemaImportacion
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.TipoProblemaImportacion
import com.example.domain.model.Turno

object ImportacionValidator {

    fun detectarMapeoAutomatico(encabezados: List<String>): MapeoColumnas {
        val norm = encabezados.map { TextoNormalizer.normalizar(it) }

        fun buscar(predicado: (String) -> Boolean): Int? =
            norm.indexOfFirst(predicado).takeIf { it >= 0 }

        val idxPrimerApellido = buscar {
            it == "primer apellido" || it == "apellido 1" || it == "1er apellido" ||
                it == "apellido paterno" || it == "primer_apellido" || it == "lastname" ||
                it == "last name"
        } ?: buscar { it == "apellido" || it == "apellidos" }

        val idxSegundoApellido = buscar {
            it == "segundo apellido" || it == "apellido 2" || it == "2do apellido" ||
                it == "apellido materno" || it == "segundo_apellido"
        }

        val idxNombre = buscar {
            it == "nombre" || it == "nombres" || it == "nombre(s)" ||
                it == "nombre completo" || it == "first name" || it == "firstname"
        }

        val idxHabitacion = buscar {
            it == "habitacion" || it == "hab" || it == "hab." ||
                it == "nro habitacion" || it == "numero habitacion" ||
                it == "pieza" || it == "cuarto" || it == "room"
        }

        val idxPabellon = buscar {
            it == "pabellon" || it == "pabellon o piso" || it == "piso" ||
                it == "modulo" || it == "bloque" || it == "sector" || it == "pab"
        }

        val idxEmpresa = buscar {
            it == "empresa" || it == "compania" || it == "contratista" ||
                it == "cliente" || it == "company"
        }

        val idxTurno = buscar {
            it == "turno" || it == "shift" || it == "jornada"
        }

        return MapeoColumnas(
            indicePrimerApellido = idxPrimerApellido,
            indiceSegundoApellido = idxSegundoApellido,
            indiceNombre = idxNombre,
            indiceHabitacion = idxHabitacion,
            indicePabellon = idxPabellon,
            indiceEmpresa = idxEmpresa,
            indiceTurno = idxTurno
        )
    }

    fun construirPrevisualizacion(
        filasCrudas: List<List<String>>,
        mapeo: MapeoColumnas,
        usarColumnaTurnoArchivo: Boolean,
        turnoDestinoPorDefecto: Turno,
        existentesTurnoA: List<TrabajadorEntity>,
        existentesTurnoB: List<TrabajadorEntity>
    ): List<FilaImportacionPreview> {
        val iniciales = filasCrudas.mapIndexed { idx, celdas ->
            val primerApellido = mapeo.indicePrimerApellido?.let { celdas.getOrNull(it) }?.trim().orEmpty()
            val segundoApellido = mapeo.indiceSegundoApellido?.let { celdas.getOrNull(it) }?.trim().orEmpty()
            val nombre = mapeo.indiceNombre?.let { celdas.getOrNull(it) }?.trim().orEmpty()
            val habitacion = mapeo.indiceHabitacion?.let { celdas.getOrNull(it) }?.trim().orEmpty()
            val pabellon = mapeo.indicePabellon?.let { celdas.getOrNull(it) }?.trim().orEmpty()
            val empresaTexto = mapeo.indiceEmpresa?.let { celdas.getOrNull(it) }?.trim().orEmpty()
            val turnoTexto = mapeo.indiceTurno?.let { celdas.getOrNull(it) }?.trim().orEmpty()

            val empresaResuelta = EmpresaOficial.buscarExactaPorNombre(empresaTexto)
            val turnoResuelto = if (usarColumnaTurnoArchivo && mapeo.indiceTurno != null) {
                Turno.parsearOpcional(turnoTexto)
            } else {
                turnoDestinoPorDefecto
            }

            FilaImportacionPreview(
                numeroFila = idx + 2,
                nombre = nombre,
                primerApellido = primerApellido,
                segundoApellido = segundoApellido,
                habitacion = habitacion,
                pabellon = pabellon,
                empresaTextoOriginal = empresaTexto,
                empresaResuelta = empresaResuelta,
                turnoTextoOriginal = turnoTexto,
                turnoResuelto = turnoResuelto,
                problemas = emptyList(),
                incluida = true
            )
        }

        return revalidarFilas(
            filas = iniciales,
            existentesTurnoA = existentesTurnoA,
            existentesTurnoB = existentesTurnoB,
            mantenerInclusionUsuario = false
        )
    }

    fun revalidarFilas(
        filas: List<FilaImportacionPreview>,
        existentesTurnoA: List<TrabajadorEntity>,
        existentesTurnoB: List<TrabajadorEntity>,
        mantenerInclusionUsuario: Boolean = true
    ): List<FilaImportacionPreview> {
        val clavesArchivoConteo = mutableMapOf<String, Int>()
        val nombresArchivoConteo = mutableMapOf<String, Int>()

        for (fila in filas) {
            val turno = fila.turnoResuelto ?: continue
            val nombreClave = TextoNormalizer.claveNombreCompleto(
                fila.nombre,
                fila.primerApellido,
                fila.segundoApellido
            )
            if (nombreClave.isNotBlank()) {
                val claveBase = "${turno.id}|${TextoNormalizer.claveNombreBase(fila.nombre, fila.primerApellido)}"
                nombresArchivoConteo[claveBase] = (nombresArchivoConteo[claveBase] ?: 0) + 1

                val claveExacta = "${turno.id}|$nombreClave|${TextoNormalizer.normalizar(fila.habitacion)}|${fila.empresaResuelta?.id ?: -1}"
                clavesArchivoConteo[claveExacta] = (clavesArchivoConteo[claveExacta] ?: 0) + 1
            }
        }

        return filas.map { fila ->
            val problemas = mutableListOf<ProblemaImportacion>()

            if (fila.nombre.isBlank() || fila.primerApellido.isBlank()) {
                problemas.add(
                    ProblemaImportacion(
                        tipo = TipoProblemaImportacion.NOMBRE_INCOMPLETO,
                        detalle = "Falta nombre o primer apellido."
                    )
                )
            }

            if (fila.empresaResuelta == null) {
                problemas.add(
                    ProblemaImportacion(
                        tipo = TipoProblemaImportacion.EMPRESA_DESCONOCIDA,
                        detalle = if (fila.empresaTextoOriginal.isBlank()) {
                            "Empresa vacía."
                        } else {
                            "Empresa no reconocida: \"${fila.empresaTextoOriginal}\"."
                        }
                    )
                )
            }

            val turno = fila.turnoResuelto
            if (turno == null) {
                problemas.add(
                    ProblemaImportacion(
                        tipo = TipoProblemaImportacion.TURNO_NO_RECONOCIDO,
                        detalle = "Turno inválido: \"${fila.turnoTextoOriginal}\" (use Turno A o Turno B)."
                    )
                )
            }

            if (fila.habitacion.isBlank()) {
                problemas.add(
                    ProblemaImportacion(
                        tipo = TipoProblemaImportacion.HABITACION_VACIA,
                        detalle = "La habitación está vacía."
                    )
                )
            }

            var clasificacion = ClasificacionImportacion.NUEVO
            var candidato: CandidatoExistente? = null

            val tieneErrorDatosBasicos = problemas.any { it.tipo.bloqueantePorDefecto }

            if (turno != null && fila.nombre.isNotBlank() && fila.primerApellido.isNotBlank()) {
                val existentesTurno = if (turno == Turno.A) existentesTurnoA else existentesTurnoB
                val claveCompletaFila = TextoNormalizer.claveNombreCompleto(
                    fila.nombre,
                    fila.primerApellido,
                    fila.segundoApellido
                )
                val claveBaseFila = TextoNormalizer.claveNombreBase(fila.nombre, fila.primerApellido)
                val habNorm = TextoNormalizer.normalizar(fila.habitacion)
                val pabNorm = TextoNormalizer.normalizar(fila.pabellon)
                val empId = fila.empresaResuelta?.id

                // 1. Verificar duplicado exacto dentro del mismo archivo
                val claveExactaArchivo = "${turno.id}|$claveCompletaFila|$habNorm|${empId ?: -1}"
                val duplicadoEnArchivo = (clavesArchivoConteo[claveExactaArchivo] ?: 0) > 1
                if (duplicadoEnArchivo) {
                    problemas.add(
                        ProblemaImportacion(
                            tipo = TipoProblemaImportacion.DUPLICADO_EN_ARCHIVO,
                            detalle = "Registro repetido dentro del mismo archivo para ${turno.nombre}."
                        )
                    )
                }

                // 2. Verificar coincidencias contra el registro maestro único en este turno
                val coincidenciaIdentica = existentesTurno.firstOrNull { ex ->
                    TextoNormalizer.claveNombreCompleto(
                        ex.nombre,
                        ex.primerApellido,
                        ex.segundoApellido
                    ) == claveCompletaFila &&
                        (empId == null || ex.empresaId == empId) &&
                        TextoNormalizer.normalizar(ex.habitacion) == habNorm &&
                        TextoNormalizer.normalizar(ex.pabellon) == pabNorm
                }

                val coincidenciaMismaPersonaEmpresa = if (coincidenciaIdentica == null && empId != null) {
                    existentesTurno.firstOrNull { ex ->
                        TextoNormalizer.claveNombreCompleto(
                            ex.nombre,
                            ex.primerApellido,
                            ex.segundoApellido
                        ) == claveCompletaFila && ex.empresaId == empId
                    }
                } else {
                    null
                }

                val coincidenciaAmbigua = if (coincidenciaIdentica == null && coincidenciaMismaPersonaEmpresa == null) {
                    existentesTurno.firstOrNull { ex ->
                        val claveEx = TextoNormalizer.claveNombreCompleto(
                            ex.nombre,
                            ex.primerApellido,
                            ex.segundoApellido
                        )
                        val baseEx = TextoNormalizer.claveNombreBase(ex.nombre, ex.primerApellido)
                        claveEx == claveCompletaFila ||
                            (baseEx == claveBaseFila &&
                                (fila.segundoApellido.isBlank() || ex.segundoApellido.isBlank()))
                    }
                } else {
                    null
                }

                when {
                    tieneErrorDatosBasicos || duplicadoEnArchivo -> {
                        clasificacion = ClasificacionImportacion.ERROR_DATOS
                    }
                    coincidenciaIdentica != null -> {
                        clasificacion = ClasificacionImportacion.YA_EXISTE_IDENTICO
                        candidato = coincidenciaIdentica.toCandidato()
                        problemas.add(
                            ProblemaImportacion(
                                tipo = TipoProblemaImportacion.DUPLICADO_EN_TURNO,
                                detalle = "Ya existe exactamente igual en ${turno.nombre}: ${candidato.nombreCompletoMayusculas}."
                            )
                        )
                    }
                    coincidenciaMismaPersonaEmpresa != null -> {
                        clasificacion = ClasificacionImportacion.EXISTENTE_CON_CAMBIOS
                        candidato = coincidenciaMismaPersonaEmpresa.toCandidato()
                        problemas.add(
                            ProblemaImportacion(
                                tipo = TipoProblemaImportacion.DUPLICADO_EN_TURNO,
                                detalle = "Posible actualización de ${candidato.nombreCompletoMayusculas} (Hab. ${candidato.habitacionMayusculas} -> ${TextoNormalizer.formatearMayusculas(fila.habitacion)}). Confirme si desea actualizar sin duplicar."
                            )
                        )
                    }
                    coincidenciaAmbigua != null -> {
                        clasificacion = ClasificacionImportacion.REQUIERE_REVISION
                        candidato = coincidenciaAmbigua.toCandidato()
                        problemas.add(
                            ProblemaImportacion(
                                tipo = TipoProblemaImportacion.NOMBRE_IGUAL_POSIBLE_CONFUSION,
                                detalle = "Coincidencia ambigua en ${turno.nombre} con ${candidato.nombreCompletoMayusculas} (Hab. ${candidato.habitacionMayusculas}, ${candidato.empresa?.nombre ?: "—"}). Confirme si es la misma persona o un homónimo."
                            )
                        )
                    }
                    (nombresArchivoConteo["${turno.id}|$claveBaseFila"] ?: 0) > 1 -> {
                        clasificacion = ClasificacionImportacion.REQUIERE_REVISION
                        problemas.add(
                            ProblemaImportacion(
                                tipo = TipoProblemaImportacion.NOMBRE_IGUAL_POSIBLE_CONFUSION,
                                detalle = "Nombre igual o similar repetido dentro del archivo para ${turno.nombre}. Verifique segundo apellido y habitación."
                            )
                        )
                    }
                    else -> {
                        clasificacion = ClasificacionImportacion.NUEVO
                    }
                }
            } else {
                clasificacion = ClasificacionImportacion.ERROR_DATOS
            }

            val tieneBloqueante = clasificacion == ClasificacionImportacion.ERROR_DATOS ||
                problemas.any { it.tipo.bloqueantePorDefecto }

            val accionFinal: AccionImportacion
            val revisionResueltaFinal: Boolean
            val incluidaFinal: Boolean

            if (tieneBloqueante) {
                accionFinal = AccionImportacion.OMITIR
                revisionResueltaFinal = false
                incluidaFinal = false
            } else if (mantenerInclusionUsuario && fila.revisionResuelta) {
                accionFinal = when {
                    fila.accion == AccionImportacion.ACTUALIZAR_EXISTENTE && candidato == null -> AccionImportacion.OMITIR
                    else -> fila.accion
                }
                revisionResueltaFinal = true
                incluidaFinal = fila.incluida && accionFinal != AccionImportacion.OMITIR
            } else {
                when (clasificacion) {
                    ClasificacionImportacion.NUEVO -> {
                        accionFinal = AccionImportacion.AGREGAR_NUEVO
                        revisionResueltaFinal = true
                        incluidaFinal = true
                    }
                    ClasificacionImportacion.YA_EXISTE_IDENTICO -> {
                        accionFinal = AccionImportacion.OMITIR
                        revisionResueltaFinal = true
                        incluidaFinal = false
                    }
                    ClasificacionImportacion.EXISTENTE_CON_CAMBIOS -> {
                        accionFinal = AccionImportacion.OMITIR
                        revisionResueltaFinal = false
                        incluidaFinal = false
                    }
                    ClasificacionImportacion.REQUIERE_REVISION -> {
                        accionFinal = AccionImportacion.OMITIR
                        revisionResueltaFinal = false
                        incluidaFinal = false
                    }
                    ClasificacionImportacion.ERROR_DATOS -> {
                        accionFinal = AccionImportacion.OMITIR
                        revisionResueltaFinal = false
                        incluidaFinal = false
                    }
                }
            }

            fila.copy(
                problemas = problemas,
                clasificacion = clasificacion,
                candidatoExistente = candidato,
                accion = accionFinal,
                revisionResuelta = revisionResueltaFinal,
                incluida = incluidaFinal
            )
        }
    }

    private fun TrabajadorEntity.toCandidato(): CandidatoExistente {
        return CandidatoExistente(
            id = id,
            nombreCompletoMayusculas = TextoNormalizer.formatearNombreObligatorio(
                primerApellido = primerApellido,
                segundoApellido = segundoApellido,
                nombre = nombre
            ),
            habitacionMayusculas = TextoNormalizer.formatearMayusculas(habitacion).ifBlank { "S/N" },
            pabellonMayusculas = TextoNormalizer.formatearMayusculas(pabellon),
            empresa = EmpresaOficial.porId(empresaId)
        )
    }
}
