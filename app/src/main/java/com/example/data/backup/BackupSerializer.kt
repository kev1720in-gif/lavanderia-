package com.example.data.backup

import com.example.data.local.entities.ComparacionEntity
import com.example.data.local.entities.ConfiguracionEntity
import com.example.data.local.entities.CorreccionOcrEntity
import com.example.data.local.entities.EmpresaEntity
import com.example.data.local.entities.MarcacionEntity
import com.example.data.local.entities.RegistroRecepcionEntity
import com.example.data.local.entities.SesionEntity
import com.example.data.local.entities.TrabajadorEntity
import com.example.data.local.entities.TurnoEntity
import com.example.domain.model.Turno
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object BackupSerializer {

    private val TABLAS_REQUERIDAS = listOf(
        "turnos",
        "empresas",
        "trabajadores",
        "sesiones",
        "marcaciones",
        "registros_recepcion",
        "comparaciones",
        "configuracion"
    )

    fun serializar(snapshot: BackupSnapshot): String {
        val root = JSONObject()
        root.put("backupFormatVersion", snapshot.backupFormatVersion)
        root.put("appName", snapshot.appName)
        root.put("generadoEn", snapshot.generadoEn)
        root.put("generadoEnIso", snapshot.generadoEnIso)

        val resumenObj = JSONObject().apply {
            put("totalTrabajadores", snapshot.resumen.totalTrabajadores)
            put("totalTurnoA", snapshot.resumen.totalTurnoA)
            put("totalTurnoB", snapshot.resumen.totalTurnoB)
            put("totalEmpresas", snapshot.resumen.totalEmpresas)
            put("totalTurnos", snapshot.resumen.totalTurnos)
            put("totalSesiones", snapshot.resumen.totalSesiones)
            put("totalMarcaciones", snapshot.resumen.totalMarcaciones)
            put("totalRecepciones", snapshot.resumen.totalRecepciones)
            put("totalComparaciones", snapshot.resumen.totalComparaciones)
            put("totalConfiguraciones", snapshot.resumen.totalConfiguraciones)
            put("totalCorreccionesOcr", snapshot.resumen.totalCorreccionesOcr)
        }
        root.put("resumen", resumenObj)

        val tablasObj = JSONObject().apply {
            put("turnos", JSONArray().apply {
                snapshot.turnos.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("nombre", t.nombre)
                    })
                }
            })

            put("empresas", JSONArray().apply {
                snapshot.empresas.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("codigo", e.codigo)
                        put("nombre", e.nombre)
                        put("colorHex", e.colorHex)
                    })
                }
            })

            put("trabajadores", JSONArray().apply {
                snapshot.trabajadores.forEach { w ->
                    put(JSONObject().apply {
                        put("id", w.id)
                        put("turnoId", w.turnoId)
                        put("empresaId", w.empresaId)
                        put("codigoInterno", w.codigoInterno)
                        put("nombre", w.nombre)
                        put("primerApellido", w.primerApellido)
                        put("segundoApellido", w.segundoApellido)
                        put("habitacion", w.habitacion)
                        put("pabellon", w.pabellon)
                        put("esDatoPrueba", w.esDatoPrueba)
                        put("creadoEn", w.creadoEn)
                        put("actualizadoEn", w.actualizadoEn)
                    })
                }
            })

            put("sesiones", JSONArray().apply {
                snapshot.sesiones.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("turnoId", s.turnoId)
                        put("fechaIso", s.fechaIso)
                        put("estado", s.estado)
                        put("creadaEn", s.creadaEn)
                        put("finalizadaEn", s.finalizadaEn ?: JSONObject.NULL)
                    })
                }
            })

            put("marcaciones", JSONArray().apply {
                snapshot.marcaciones.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("sesionId", m.sesionId)
                        put("turnoId", m.turnoId)
                        put("trabajadorId", m.trabajadorId ?: JSONObject.NULL)
                        put("nombreCompletoSnapshot", m.nombreCompletoSnapshot)
                        put("primerApellidoSnapshot", m.primerApellidoSnapshot)
                        put("segundoApellidoSnapshot", m.segundoApellidoSnapshot)
                        put("habitacionSnapshot", m.habitacionSnapshot)
                        put("pabellonSnapshot", m.pabellonSnapshot)
                        put("empresaIdSnapshot", m.empresaIdSnapshot)
                        put("empresaNombreSnapshot", m.empresaNombreSnapshot)
                        put("siBajo", m.siBajo)
                        put("esDatoPrueba", m.esDatoPrueba)
                        put("origen", m.origen)
                        put("marcadoEn", m.marcadoEn)
                    })
                }
            })

            put("registros_recepcion", JSONArray().apply {
                snapshot.registrosRecepcion.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("sesionId", r.sesionId)
                        put("turnoId", r.turnoId)
                        put("trabajadorId", r.trabajadorId ?: JSONObject.NULL)
                        put("textoOriginal", r.textoOriginal)
                        put("nombreConfirmadoSnapshot", r.nombreConfirmadoSnapshot)
                        put("empresaIdSnapshot", r.empresaIdSnapshot ?: JSONObject.NULL)
                        put("empresaNombreSnapshot", r.empresaNombreSnapshot ?: JSONObject.NULL)
                        put("confirmado", r.confirmado)
                        put("origen", r.origen)
                        put("creadoEn", r.creadoEn)
                    })
                }
            })

            put("comparaciones", JSONArray().apply {
                snapshot.comparaciones.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("sesionId", c.sesionId)
                        put("turnoId", c.turnoId)
                        put("trabajadorId", c.trabajadorId ?: JSONObject.NULL)
                        put("empresaIdSnapshot", c.empresaIdSnapshot ?: JSONObject.NULL)
                        put("empresaNombreSnapshot", c.empresaNombreSnapshot)
                        put("nombreCompletoSnapshot", c.nombreCompletoSnapshot)
                        put("habitacionSnapshot", c.habitacionSnapshot)
                        put("pabellonSnapshot", c.pabellonSnapshot)
                        put("categoria", c.categoria)
                        put("calculadoEn", c.calculadoEn)
                    })
                }
            })

            put("configuracion", JSONArray().apply {
                snapshot.configuracion.forEach { cfg ->
                    put(JSONObject().apply {
                        put("clave", cfg.clave)
                        put("valor", cfg.valor)
                    })
                }
            })

            put("correcciones_ocr", JSONArray().apply {
                snapshot.correccionesOcr.forEach { cor ->
                    put(JSONObject().apply {
                        put("id", cor.id)
                        put("turnoId", cor.turnoId)
                        put("empresaId", cor.empresaId)
                        put("textoOcrNormalizado", cor.textoOcrNormalizado)
                        put("textoOcrOriginal", cor.textoOcrOriginal)
                        put("trabajadorId", cor.trabajadorId)
                        put("vecesUsada", cor.vecesUsada)
                        put("creadoEn", cor.creadoEn)
                        put("actualizadoEn", cor.actualizadoEn)
                    })
                }
            })
        }
        root.put("tablas", tablasObj)

        return root.toString(2)
    }

    fun validarYParsear(jsonRaw: String): ResultadoValidacionBackup {
        if (jsonRaw.isBlank()) {
            return ResultadoValidacionBackup.Invalido("El archivo seleccionado está vacío.")
        }

        val root = try {
            JSONObject(jsonRaw)
        } catch (e: JSONException) {
            return ResultadoValidacionBackup.Invalido(
                "El archivo no tiene formato JSON válido (${e.message ?: "error de sintaxis"})."
            )
        }

        if (!root.has("backupFormatVersion") || root.isNull("backupFormatVersion")) {
            return ResultadoValidacionBackup.Invalido(
                "El archivo no incluye 'backupFormatVersion'; no es una copia de seguridad válida de Lavandería Control."
            )
        }

        val version = try {
            root.getInt("backupFormatVersion")
        } catch (_: JSONException) {
            return ResultadoValidacionBackup.Invalido("La versión del formato de copia de seguridad no es numérica.")
        }

        if (version !in BACKUP_FORMAT_MIN_SUPPORTED..BACKUP_FORMAT_VERSION) {
            return ResultadoValidacionBackup.Invalido(
                "Versión de copia de seguridad incompatible (encontrada: v$version, soportada: v$BACKUP_FORMAT_MIN_SUPPORTED..v$BACKUP_FORMAT_VERSION)."
            )
        }

        val appName = root.optString("appName", "").trim()
        if (appName.isBlank()) {
            return ResultadoValidacionBackup.Invalido("Falta el identificador de aplicación ('appName') en la copia de seguridad.")
        }

        val generadoEn = root.optNullableLong("generadoEn")
            ?: return ResultadoValidacionBackup.Invalido("Falta la marca temporal ('generadoEn') en la copia de seguridad.")
        val generadoEnIso = root.optString("generadoEnIso", "").trim()

        val tablas = root.optJSONObject("tablas")
            ?: return ResultadoValidacionBackup.Invalido("Falta la sección obligatoria 'tablas' en el archivo de respaldo.")

        for (tabla in TABLAS_REQUERIDAS) {
            if (!tablas.has(tabla) || tablas.optJSONArray(tabla) == null) {
                return ResultadoValidacionBackup.Invalido(
                    "La copia de seguridad está incompleta: falta la tabla '$tabla'."
                )
            }
        }

        if (version >= 2 && (!tablas.has("correcciones_ocr") || tablas.optJSONArray("correcciones_ocr") == null)) {
            return ResultadoValidacionBackup.Invalido(
                "La copia de seguridad está incompleta: falta la tabla 'correcciones_ocr'."
            )
        }

        return try {
            val turnos = parseTurnos(tablas.getJSONArray("turnos"))
            val empresas = parseEmpresas(tablas.getJSONArray("empresas"))
            val trabajadores = parseTrabajadores(tablas.getJSONArray("trabajadores"))
            val sesiones = parseSesiones(tablas.getJSONArray("sesiones"))
            val marcaciones = parseMarcaciones(tablas.getJSONArray("marcaciones"))
            val recepciones = parseRecepciones(tablas.getJSONArray("registros_recepcion"))
            val comparaciones = parseComparaciones(tablas.getJSONArray("comparaciones"))
            val configuracion = parseConfiguracion(tablas.getJSONArray("configuracion"))
            val correccionesOcr = tablas.optJSONArray("correcciones_ocr")?.let { parseCorreccionesOcr(it) } ?: emptyList()

            val turnoIds = turnos.map { it.id }.toSet()
            if (turnoIds.size != turnos.size) {
                return ResultadoValidacionBackup.Invalido("Existen identificadores de turno duplicados en la copia.")
            }

            val empresaIds = empresas.map { it.id }.toSet()
            if (empresaIds.size != empresas.size) {
                return ResultadoValidacionBackup.Invalido("Existen identificadores de empresa duplicados en la copia.")
            }

            val trabajadorIds = trabajadores.map { it.id }.toSet()
            if (trabajadorIds.size != trabajadores.size) {
                return ResultadoValidacionBackup.Invalido("Existen identificadores de trabajadores duplicados en la copia.")
            }

            for (w in trabajadores) {
                if (w.turnoId !in turnoIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "El trabajador '${w.id}' referencia un turno inexistente ('${w.turnoId}')."
                    )
                }
                if (w.empresaId !in empresaIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "El trabajador '${w.id}' referencia una empresa inexistente (${w.empresaId})."
                    )
                }
            }

            val sesionIds = sesiones.map { it.id }.toSet()
            if (sesionIds.size != sesiones.size) {
                return ResultadoValidacionBackup.Invalido("Existen identificadores de sesión duplicados en la copia.")
            }
            for (s in sesiones) {
                if (s.turnoId !in turnoIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "La sesión '${s.id}' referencia un turno inexistente ('${s.turnoId}')."
                    )
                }
            }

            val marcacionIds = marcaciones.map { it.id }.toSet()
            if (marcacionIds.size != marcaciones.size) {
                return ResultadoValidacionBackup.Invalido("Existen identificadores de marcación duplicados en la copia.")
            }
            val paresSesionTrabajador = HashSet<Pair<String, String>>()
            for (m in marcaciones) {
                if (m.sesionId !in sesionIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "La marcación '${m.id}' referencia una sesión inexistente ('${m.sesionId}')."
                    )
                }
                if (m.trabajadorId != null) {
                    if (m.trabajadorId !in trabajadorIds) {
                        return ResultadoValidacionBackup.Invalido(
                            "La marcación '${m.id}' referencia un trabajador inexistente ('${m.trabajadorId}')."
                        )
                    }
                    if (!paresSesionTrabajador.add(m.sesionId to m.trabajadorId)) {
                        return ResultadoValidacionBackup.Invalido(
                            "Existen marcaciones duplicadas para la misma sesión '${m.sesionId}' y trabajador '${m.trabajadorId}'."
                        )
                    }
                }
            }

            val recepcionIds = recepciones.map { it.id }.toSet()
            if (recepcionIds.size != recepciones.size) {
                return ResultadoValidacionBackup.Invalido("Existen identificadores de recepción duplicados en la copia.")
            }
            for (r in recepciones) {
                if (r.sesionId !in sesionIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "El registro de recepción '${r.id}' referencia una sesión inexistente ('${r.sesionId}')."
                    )
                }
                if (r.trabajadorId != null && r.trabajadorId !in trabajadorIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "El registro de recepción '${r.id}' referencia un trabajador inexistente ('${r.trabajadorId}')."
                    )
                }
            }

            val comparacionIds = comparaciones.map { it.id }.toSet()
            if (comparacionIds.size != comparaciones.size) {
                return ResultadoValidacionBackup.Invalido("Existen identificadores de comparación duplicados en la copia.")
            }
            for (c in comparaciones) {
                if (c.sesionId !in sesionIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "La comparación '${c.id}' referencia una sesión inexistente ('${c.sesionId}')."
                    )
                }
                if (c.trabajadorId != null && c.trabajadorId !in trabajadorIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "La comparación '${c.id}' referencia un trabajador inexistente ('${c.trabajadorId}')."
                    )
                }
            }

            val clavesConfig = configuracion.map { it.clave }.toSet()
            if (clavesConfig.size != configuracion.size) {
                return ResultadoValidacionBackup.Invalido("Existen claves de configuración duplicadas en la copia.")
            }

            val correccionesIds = correccionesOcr.map { it.id }.toSet()
            if (correccionesIds.size != correccionesOcr.size) {
                return ResultadoValidacionBackup.Invalido("Existen identificadores de correcciones OCR duplicados en la copia.")
            }
            val clavesCorreccion = HashSet<Triple<String, Int, String>>()
            for (cor in correccionesOcr) {
                if (cor.turnoId !in turnoIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "La corrección OCR '${cor.id}' referencia un turno inexistente ('${cor.turnoId}')."
                    )
                }
                if (cor.empresaId !in empresaIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "La corrección OCR '${cor.id}' referencia una empresa inexistente (${cor.empresaId})."
                    )
                }
                if (cor.trabajadorId !in trabajadorIds) {
                    return ResultadoValidacionBackup.Invalido(
                        "La corrección OCR '${cor.id}' referencia un trabajador inexistente ('${cor.trabajadorId}')."
                    )
                }
                if (!clavesCorreccion.add(Triple(cor.turnoId, cor.empresaId, cor.textoOcrNormalizado))) {
                    return ResultadoValidacionBackup.Invalido(
                        "Existen correcciones OCR duplicadas para el mismo turno, empresa y texto."
                    )
                }
            }

            val resumenCalculado = BackupResumen(
                totalTrabajadores = trabajadores.size,
                totalTurnoA = trabajadores.count { it.turnoId == Turno.A.id },
                totalTurnoB = trabajadores.count { it.turnoId == Turno.B.id },
                totalEmpresas = empresas.size,
                totalTurnos = turnos.size,
                totalSesiones = sesiones.size,
                totalMarcaciones = marcaciones.size,
                totalRecepciones = recepciones.size,
                totalComparaciones = comparaciones.size,
                totalConfiguraciones = configuracion.size,
                totalCorreccionesOcr = correccionesOcr.size
            )

            val resumenDeclarado = root.optJSONObject("resumen")
            if (resumenDeclarado != null) {
                val declarados = mapOf(
                    "totalTrabajadores" to resumenCalculado.totalTrabajadores,
                    "totalTurnoA" to resumenCalculado.totalTurnoA,
                    "totalTurnoB" to resumenCalculado.totalTurnoB,
                    "totalEmpresas" to resumenCalculado.totalEmpresas,
                    "totalTurnos" to resumenCalculado.totalTurnos,
                    "totalSesiones" to resumenCalculado.totalSesiones,
                    "totalMarcaciones" to resumenCalculado.totalMarcaciones,
                    "totalRecepciones" to resumenCalculado.totalRecepciones,
                    "totalComparaciones" to resumenCalculado.totalComparaciones,
                    "totalConfiguraciones" to resumenCalculado.totalConfiguraciones,
                    "totalCorreccionesOcr" to resumenCalculado.totalCorreccionesOcr
                )
                for ((campo, realValor) in declarados) {
                    if (resumenDeclarado.has(campo) && resumenDeclarado.getInt(campo) != realValor) {
                        return ResultadoValidacionBackup.Invalido(
                            "Inconsistencia en el resumen del archivo ('$campo' declarado=${resumenDeclarado.getInt(campo)}, real=$realValor). El archivo puede estar truncado o dañado."
                        )
                    }
                }
            }

            val snapshot = BackupSnapshot(
                backupFormatVersion = version,
                appName = appName,
                generadoEn = generadoEn,
                generadoEnIso = generadoEnIso,
                resumen = resumenCalculado,
                turnos = turnos,
                empresas = empresas,
                trabajadores = trabajadores,
                sesiones = sesiones,
                marcaciones = marcaciones,
                registrosRecepcion = recepciones,
                comparaciones = comparaciones,
                configuracion = configuracion,
                correccionesOcr = correccionesOcr
            )

            ResultadoValidacionBackup.Valido(
                BackupValidado(
                    snapshot = snapshot,
                    resumen = resumenCalculado
                )
            )
        } catch (e: IllegalArgumentException) {
            ResultadoValidacionBackup.Invalido(e.message ?: "Estructura de datos inválida en la copia de seguridad.")
        } catch (e: JSONException) {
            ResultadoValidacionBackup.Invalido("Campo corrupto o tipo de dato inválido en la copia (${e.message ?: "JSON"}). ")
        }
    }

    private fun parseTurnos(arr: JSONArray): List<TurnoEntity> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        TurnoEntity(
            id = o.requireNonBlankString("id", "turnos[$i]"),
            nombre = o.requireNonBlankString("nombre", "turnos[$i]")
        )
    }

    private fun parseEmpresas(arr: JSONArray): List<EmpresaEntity> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        EmpresaEntity(
            id = o.requireInt("id", "empresas[$i]"),
            codigo = o.requireNonBlankString("codigo", "empresas[$i]"),
            nombre = o.requireNonBlankString("nombre", "empresas[$i]"),
            colorHex = o.requireLong("colorHex", "empresas[$i]")
        )
    }

    private fun parseTrabajadores(arr: JSONArray): List<TrabajadorEntity> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        TrabajadorEntity(
            id = o.requireNonBlankString("id", "trabajadores[$i]"),
            turnoId = o.requireNonBlankString("turnoId", "trabajadores[$i]"),
            empresaId = o.requireInt("empresaId", "trabajadores[$i]"),
            codigoInterno = o.requireNonBlankString("codigoInterno", "trabajadores[$i]"),
            nombre = o.requireNonBlankString("nombre", "trabajadores[$i]"),
            primerApellido = o.requireNonBlankString("primerApellido", "trabajadores[$i]"),
            segundoApellido = o.requireString("segundoApellido", "trabajadores[$i]"),
            habitacion = o.requireString("habitacion", "trabajadores[$i]"),
            pabellon = o.requireString("pabellon", "trabajadores[$i]"),
            esDatoPrueba = o.requireBoolean("esDatoPrueba", "trabajadores[$i]"),
            creadoEn = o.requireLong("creadoEn", "trabajadores[$i]"),
            actualizadoEn = o.requireLong("actualizadoEn", "trabajadores[$i]")
        )
    }

    private fun parseSesiones(arr: JSONArray): List<SesionEntity> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        SesionEntity(
            id = o.requireNonBlankString("id", "sesiones[$i]"),
            turnoId = o.requireNonBlankString("turnoId", "sesiones[$i]"),
            fechaIso = o.requireNonBlankString("fechaIso", "sesiones[$i]"),
            estado = o.requireNonBlankString("estado", "sesiones[$i]"),
            creadaEn = o.requireLong("creadaEn", "sesiones[$i]"),
            finalizadaEn = o.optNullableLong("finalizadaEn")
        )
    }

    private fun parseMarcaciones(arr: JSONArray): List<MarcacionEntity> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        MarcacionEntity(
            id = o.requireNonBlankString("id", "marcaciones[$i]"),
            sesionId = o.requireNonBlankString("sesionId", "marcaciones[$i]"),
            turnoId = o.requireNonBlankString("turnoId", "marcaciones[$i]"),
            trabajadorId = o.optNullableString("trabajadorId"),
            nombreCompletoSnapshot = o.requireString("nombreCompletoSnapshot", "marcaciones[$i]"),
            primerApellidoSnapshot = o.requireString("primerApellidoSnapshot", "marcaciones[$i]"),
            segundoApellidoSnapshot = o.requireString("segundoApellidoSnapshot", "marcaciones[$i]"),
            habitacionSnapshot = o.requireString("habitacionSnapshot", "marcaciones[$i]"),
            pabellonSnapshot = o.requireString("pabellonSnapshot", "marcaciones[$i]"),
            empresaIdSnapshot = o.requireInt("empresaIdSnapshot", "marcaciones[$i]"),
            empresaNombreSnapshot = o.requireString("empresaNombreSnapshot", "marcaciones[$i]"),
            siBajo = o.requireBoolean("siBajo", "marcaciones[$i]"),
            esDatoPrueba = o.requireBoolean("esDatoPrueba", "marcaciones[$i]"),
            origen = o.requireNonBlankString("origen", "marcaciones[$i]"),
            marcadoEn = o.requireLong("marcadoEn", "marcaciones[$i]")
        )
    }

    private fun parseRecepciones(arr: JSONArray): List<RegistroRecepcionEntity> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        RegistroRecepcionEntity(
            id = o.requireNonBlankString("id", "registros_recepcion[$i]"),
            sesionId = o.requireNonBlankString("sesionId", "registros_recepcion[$i]"),
            turnoId = o.requireNonBlankString("turnoId", "registros_recepcion[$i]"),
            trabajadorId = o.optNullableString("trabajadorId"),
            textoOriginal = o.requireString("textoOriginal", "registros_recepcion[$i]"),
            nombreConfirmadoSnapshot = o.requireString("nombreConfirmadoSnapshot", "registros_recepcion[$i]"),
            empresaIdSnapshot = o.optNullableInt("empresaIdSnapshot"),
            empresaNombreSnapshot = o.optNullableString("empresaNombreSnapshot"),
            confirmado = o.requireBoolean("confirmado", "registros_recepcion[$i]"),
            origen = o.requireNonBlankString("origen", "registros_recepcion[$i]"),
            creadoEn = o.requireLong("creadoEn", "registros_recepcion[$i]")
        )
    }

    private fun parseComparaciones(arr: JSONArray): List<ComparacionEntity> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        ComparacionEntity(
            id = o.requireNonBlankString("id", "comparaciones[$i]"),
            sesionId = o.requireNonBlankString("sesionId", "comparaciones[$i]"),
            turnoId = o.requireNonBlankString("turnoId", "comparaciones[$i]"),
            trabajadorId = o.optNullableString("trabajadorId"),
            empresaIdSnapshot = o.optNullableInt("empresaIdSnapshot"),
            empresaNombreSnapshot = o.requireString("empresaNombreSnapshot", "comparaciones[$i]"),
            nombreCompletoSnapshot = o.requireString("nombreCompletoSnapshot", "comparaciones[$i]"),
            habitacionSnapshot = o.requireString("habitacionSnapshot", "comparaciones[$i]"),
            pabellonSnapshot = o.requireString("pabellonSnapshot", "comparaciones[$i]"),
            categoria = o.requireNonBlankString("categoria", "comparaciones[$i]"),
            calculadoEn = o.requireLong("calculadoEn", "comparaciones[$i]")
        )
    }

    private fun parseConfiguracion(arr: JSONArray): List<ConfiguracionEntity> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        ConfiguracionEntity(
            clave = o.requireNonBlankString("clave", "configuracion[$i]"),
            valor = o.requireString("valor", "configuracion[$i]")
        )
    }

    private fun parseCorreccionesOcr(arr: JSONArray): List<CorreccionOcrEntity> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        CorreccionOcrEntity(
            id = o.requireNonBlankString("id", "correcciones_ocr[$i]"),
            turnoId = o.requireNonBlankString("turnoId", "correcciones_ocr[$i]"),
            empresaId = o.requireInt("empresaId", "correcciones_ocr[$i]"),
            textoOcrNormalizado = o.requireNonBlankString("textoOcrNormalizado", "correcciones_ocr[$i]"),
            textoOcrOriginal = o.requireString("textoOcrOriginal", "correcciones_ocr[$i]"),
            trabajadorId = o.requireNonBlankString("trabajadorId", "correcciones_ocr[$i]"),
            vecesUsada = o.requireInt("vecesUsada", "correcciones_ocr[$i]"),
            creadoEn = o.requireLong("creadoEn", "correcciones_ocr[$i]"),
            actualizadoEn = o.requireLong("actualizadoEn", "correcciones_ocr[$i]")
        )
    }

    private fun JSONObject.requireString(key: String, ctx: String): String {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Falta el campo obligatorio '$key' en $ctx.")
        }
        val value = get(key)
        if (value !is String) {
            throw IllegalArgumentException("El campo '$key' en $ctx debe ser texto.")
        }
        return value
    }

    private fun JSONObject.requireNonBlankString(key: String, ctx: String): String {
        val value = requireString(key, ctx)
        if (value.isBlank()) {
            throw IllegalArgumentException("El campo obligatorio '$key' en $ctx no puede estar vacío.")
        }
        return value
    }

    private fun JSONObject.requireInt(key: String, ctx: String): Int {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Falta el campo numérico obligatorio '$key' en $ctx.")
        }
        return getInt(key)
    }

    private fun JSONObject.requireLong(key: String, ctx: String): Long {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Falta el campo numérico obligatorio '$key' en $ctx.")
        }
        return getLong(key)
    }

    private fun JSONObject.requireBoolean(key: String, ctx: String): Boolean {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException("Falta el campo booleano obligatorio '$key' en $ctx.")
        }
        return getBoolean(key)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = get(key)
        return if (value is String) value else throw JSONException("Campo '$key' inválido")
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return getInt(key)
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return getLong(key)
    }
}
