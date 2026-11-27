package com.example.lucy.memory

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.Turno
import com.example.lucy.core.LucyEstadoEscrituraMemoria
import com.example.lucy.core.LucyMemoryPort
import com.example.lucy.core.LucyModuleHealth
import com.example.lucy.core.LucyResultadoEliminacionMemoria
import com.example.lucy.core.LucyResultadoEscrituraMemoria
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.util.UUID

class DataStoreLucyMemoryPort(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : LucyMemoryPort {

    @Volatile
    private var lastWriteError: String? = null

    override suspend fun healthCheck(): LucyModuleHealth = withContext(ioDispatcher) {
        try {
            dataStore.data.first()
            val errorEscritura = lastWriteError
            if (errorEscritura != null) {
                LucyModuleHealth.Degraded("Error reciente al guardar memoria: $errorEscritura")
            } else {
                LucyModuleHealth.Ready
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (e: IOException) {
            LucyModuleHealth.Unavailable("No se pudo leer DataStore de Lucy: ${e.message ?: "error de E/S"}")
        } catch (e: CorruptionException) {
            LucyModuleHealth.Degraded("Archivo de memoria dañado: ${e.message ?: "corrupción"}")
        } catch (e: Exception) {
            LucyModuleHealth.Unavailable("Error al consultar memoria: ${e.message ?: e::class.java.simpleName}")
        }
    }

    override suspend fun recordInteraction(
        turno: Turno,
        userInput: String,
        assistantResponse: String
    ) {
        // Por privacidad y para evitar almacenar basura/comentarios casuales,
        // no se persisten transcripciones crudas en DataStore automáticamente.
    }

    override suspend fun rememberReferencedWorker(turno: Turno, trabajadorId: String) {
        val idLimpio = trabajadorId.trim()
        if (idLimpio.isEmpty()) return
        safeEdit { prefs ->
            prefs[lastWorkerKey(turno)] = idLimpio
        }
    }

    override suspend fun resolveLastReferencedWorkerId(turno: Turno): String? {
        val prefs = safeReadPreferences() ?: return null
        return prefs[lastWorkerKey(turno)]?.takeIf { it.isNotBlank() }
    }

    override suspend fun recordarEmpresa(turno: Turno, empresa: EmpresaOficial) {
        safeEdit { prefs ->
            prefs[lastEmpresaKey(turno)] = empresa.codigo
        }
    }

    override suspend fun resolveLastReferencedEmpresa(turno: Turno): EmpresaOficial? {
        val prefs = safeReadPreferences() ?: return null
        val codigo = prefs[lastEmpresaKey(turno)] ?: return null
        return EmpresaOficial.porCodigo(codigo)
    }

    override suspend fun obtenerPreferencias(): LucyPreferenciasConversacion {
        val prefs = safeReadPreferences() ?: return LucyPreferenciasConversacion()
        return extractPreferencias(prefs)
    }

    override suspend fun actualizarVelocidadRespuesta(
        velocidad: LucyVelocidadRespuesta
    ): LucyResultadoEscrituraMemoria {
        var resultado = LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR)
        val ok = safeEdit { prefs ->
            val actual = LucyVelocidadRespuesta.fromStored(prefs[KEY_PREF_VELOCIDAD])
            if (actual == velocidad && prefs.contains(KEY_PREF_VELOCIDAD)) {
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.YA_EXISTIA,
                    "La velocidad ya estaba configurada en ${velocidad.etiqueta}."
                )
            } else {
                val habiaValor = prefs.contains(KEY_PREF_VELOCIDAD) && actual != LucyVelocidadRespuesta.NORMAL
                prefs[KEY_PREF_VELOCIDAD] = velocidad.name
                prefs[KEY_PREF_ULTIMO_TIPO] = LucyTipoPreferencia.VELOCIDAD.name
                resultado = LucyResultadoEscrituraMemoria(
                    if (habiaValor) LucyEstadoEscrituraMemoria.ACTUALIZADO else LucyEstadoEscrituraMemoria.GUARDADO,
                    "Velocidad configurada en ${velocidad.etiqueta}."
                )
            }
        }
        return if (ok) resultado else LucyResultadoEscrituraMemoria(
            LucyEstadoEscrituraMemoria.ERROR,
            lastWriteError ?: "No se pudo guardar la preferencia."
        )
    }

    override suspend fun actualizarEstiloRespuesta(
        estilo: LucyEstiloRespuesta
    ): LucyResultadoEscrituraMemoria {
        var resultado = LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR)
        val ok = safeEdit { prefs ->
            val actual = LucyEstiloRespuesta.fromStored(prefs[KEY_PREF_ESTILO])
            if (actual == estilo && prefs.contains(KEY_PREF_ESTILO)) {
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.YA_EXISTIA,
                    "El estilo de respuesta ya estaba en modo ${estilo.etiqueta}."
                )
            } else {
                val habiaValor = prefs.contains(KEY_PREF_ESTILO) && actual != LucyEstiloRespuesta.NORMAL
                prefs[KEY_PREF_ESTILO] = estilo.name
                prefs[KEY_PREF_ULTIMO_TIPO] = LucyTipoPreferencia.ESTILO.name
                resultado = LucyResultadoEscrituraMemoria(
                    if (habiaValor) LucyEstadoEscrituraMemoria.ACTUALIZADO else LucyEstadoEscrituraMemoria.GUARDADO,
                    "Estilo de respuesta configurado en ${estilo.etiqueta}."
                )
            }
        }
        return if (ok) resultado else LucyResultadoEscrituraMemoria(
            LucyEstadoEscrituraMemoria.ERROR,
            lastWriteError ?: "No se pudo guardar el estilo."
        )
    }

    override suspend fun actualizarVozPreferida(vozId: String): LucyResultadoEscrituraMemoria {
        val limpio = vozId.trim()
        var resultado = LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR)
        val ok = safeEdit { prefs ->
            val actual = prefs[KEY_PREF_VOZ_ID].orEmpty()
            if (actual == limpio && (limpio.isNotEmpty() || !prefs.contains(KEY_PREF_VOZ_ID))) {
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.YA_EXISTIA,
                    "Esa voz ya estaba seleccionada."
                )
            } else {
                val habiaValor = actual.isNotBlank()
                if (limpio.isEmpty()) {
                    prefs.remove(KEY_PREF_VOZ_ID)
                } else {
                    prefs[KEY_PREF_VOZ_ID] = limpio
                }
                prefs[KEY_PREF_ULTIMO_TIPO] = LucyTipoPreferencia.VOZ_PREFERIDA.name
                resultado = LucyResultadoEscrituraMemoria(
                    if (habiaValor) LucyEstadoEscrituraMemoria.ACTUALIZADO else LucyEstadoEscrituraMemoria.GUARDADO,
                    if (limpio.isEmpty()) "Voz configurada en modo automático." else "Voz preferida actualizada."
                )
            }
        }
        return if (ok) resultado else LucyResultadoEscrituraMemoria(
            LucyEstadoEscrituraMemoria.ERROR,
            lastWriteError ?: "No se pudo guardar la voz preferida."
        )
    }

    override suspend fun actualizarEstiloVoz(estiloVoz: LucyEstiloVoz): LucyResultadoEscrituraMemoria {
        var resultado = LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR)
        val ok = safeEdit { prefs ->
            val actual = LucyEstiloVoz.fromStored(prefs[KEY_PREF_ESTILO_VOZ])
            if (actual == estiloVoz && prefs.contains(KEY_PREF_ESTILO_VOZ)) {
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.YA_EXISTIA,
                    "El estilo de voz ya estaba en modo ${estiloVoz.etiqueta}."
                )
            } else {
                val habiaValor = prefs.contains(KEY_PREF_ESTILO_VOZ) && actual != LucyEstiloVoz.NATURAL_CERCANA
                prefs[KEY_PREF_ESTILO_VOZ] = estiloVoz.name
                prefs[KEY_PREF_ULTIMO_TIPO] = LucyTipoPreferencia.ESTILO_VOZ.name
                resultado = LucyResultadoEscrituraMemoria(
                    if (habiaValor) LucyEstadoEscrituraMemoria.ACTUALIZADO else LucyEstadoEscrituraMemoria.GUARDADO,
                    "Estilo de voz configurado en ${estiloVoz.etiqueta}."
                )
            }
        }
        return if (ok) resultado else LucyResultadoEscrituraMemoria(
            LucyEstadoEscrituraMemoria.ERROR,
            lastWriteError ?: "No se pudo guardar el estilo de voz."
        )
    }

    override suspend fun guardarInstruccionPermanente(texto: String): LucyResultadoEscrituraMemoria {
        val limpio = texto.trim().replace(Regex("\\s+"), " ")
        if (limpio.isBlank()) {
            return LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR, "Instrucción vacía.")
        }
        val clave = LucyMemoryStorageSerializer.normalizarClaveSemantica(limpio)
        if (clave.isBlank()) {
            return LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR, "La instrucción no contiene datos útiles.")
        }
        val normTexto = TextoNormalizer.normalizar(limpio)
        var resultado = LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR)

        val ok = safeEdit { prefs ->
            val actuales = parseInstruccionesSafe(prefs[KEY_PREF_INSTRUCCIONES]).toMutableList()
            val idxExacto = actuales.indexOfFirst { TextoNormalizer.normalizar(it.texto) == normTexto }
            if (idxExacto >= 0) {
                prefs[KEY_PREF_ULTIMO_TIPO] = LucyTipoPreferencia.INSTRUCCION.name
                prefs[KEY_PREF_ULTIMA_INSTRUCCION_ID] = actuales[idxExacto].id
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.YA_EXISTIA,
                    "Ya tenía guardada esa instrucción."
                )
                return@safeEdit
            }

            val ahora = clock()
            val idxSimilar = actuales.indexOfFirst { it.claveNormalizada == clave }
            if (idxSimilar >= 0) {
                val actualizada = actuales[idxSimilar].copy(
                    texto = limpio,
                    actualizadoEn = ahora
                )
                actuales[idxSimilar] = actualizada
                prefs[KEY_PREF_INSTRUCCIONES] = LucyMemoryStorageSerializer.serializarInstrucciones(actuales)
                prefs[KEY_PREF_ULTIMO_TIPO] = LucyTipoPreferencia.INSTRUCCION.name
                prefs[KEY_PREF_ULTIMA_INSTRUCCION_ID] = actualizada.id
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.ACTUALIZADO,
                    "Instrucción actualizada: $limpio"
                )
            } else {
                val nueva = LucyInstruccionMemoria(
                    id = UUID.randomUUID().toString(),
                    claveNormalizada = clave,
                    texto = limpio,
                    actualizadoEn = ahora
                )
                actuales.add(nueva)
                val acotada = actuales.takeLast(MAX_INSTRUCCIONES)
                prefs[KEY_PREF_INSTRUCCIONES] = LucyMemoryStorageSerializer.serializarInstrucciones(acotada)
                prefs[KEY_PREF_ULTIMO_TIPO] = LucyTipoPreferencia.INSTRUCCION.name
                prefs[KEY_PREF_ULTIMA_INSTRUCCION_ID] = nueva.id
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.GUARDADO,
                    "Instrucción guardada: $limpio"
                )
            }
        }
        return if (ok) resultado else LucyResultadoEscrituraMemoria(
            LucyEstadoEscrituraMemoria.ERROR,
            lastWriteError ?: "No se pudo guardar la instrucción."
        )
    }

    override suspend fun guardarNotaOperativa(
        turnoDestino: Turno,
        texto: String,
        empresa: EmpresaOficial?
    ): LucyResultadoEscrituraMemoria {
        val limpio = texto.trim().replace(Regex("\\s+"), " ")
        if (limpio.isBlank()) {
            return LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR, "Recuerdo vacío.")
        }
        val clave = LucyMemoryStorageSerializer.normalizarClaveSemantica(limpio, empresa)
        if (clave.isBlank()) {
            return LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR, "No se detectó contenido útil para recordar.")
        }
        val normTexto = TextoNormalizer.normalizar(limpio)
        var resultado = LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR)

        val ok = safeEdit { prefs ->
            val notas = parseNotasSafe(prefs[notesKey(turnoDestino)], turnoDestino).toMutableList()
            val idxExacto = notas.indexOfFirst {
                it.empresaCodigo == empresa?.codigo && TextoNormalizer.normalizar(it.texto) == normTexto
            }
            if (idxExacto >= 0) {
                prefs[lastNoteIdKey(turnoDestino)] = notas[idxExacto].id
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.YA_EXISTIA,
                    "Ese recuerdo ya estaba guardado en ${turnoDestino.nombre}."
                )
                return@safeEdit
            }

            val ahora = clock()
            val idxClave = notas.indexOfFirst {
                it.empresaCodigo == empresa?.codigo && it.claveNormalizada == clave
            }
            if (idxClave >= 0) {
                val actualizada = notas[idxClave].copy(
                    texto = limpio,
                    actualizadoEn = ahora
                )
                notas[idxClave] = actualizada
                prefs[notesKey(turnoDestino)] = LucyMemoryStorageSerializer.serializarNotas(notas)
                prefs[lastNoteIdKey(turnoDestino)] = actualizada.id
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.ACTUALIZADO,
                    "Recuerdo actualizado en ${turnoDestino.nombre}: $limpio"
                )
            } else {
                val nueva = LucyNotaMemoria(
                    id = UUID.randomUUID().toString(),
                    claveNormalizada = clave,
                    texto = limpio,
                    empresaCodigo = empresa?.codigo,
                    turno = turnoDestino,
                    creadoEn = ahora,
                    actualizadoEn = ahora
                )
                notas.add(nueva)
                val acotadas = notas.takeLast(MAX_NOTAS_POR_TURNO)
                prefs[notesKey(turnoDestino)] = LucyMemoryStorageSerializer.serializarNotas(acotadas)
                prefs[lastNoteIdKey(turnoDestino)] = nueva.id
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.GUARDADO,
                    "Recordado en ${turnoDestino.nombre}: $limpio"
                )
            }
        }
        return if (ok) resultado else LucyResultadoEscrituraMemoria(
            LucyEstadoEscrituraMemoria.ERROR,
            lastWriteError ?: "No se pudo guardar el recuerdo."
        )
    }

    override suspend fun consultarNotasOperativas(
        turno: Turno,
        empresa: EmpresaOficial?,
        query: String?
    ): List<LucyNotaMemoria> {
        val prefs = safeReadPreferences() ?: return emptyList()
        val todasTurno = parseNotasSafe(prefs[notesKey(turno)], turno)
        if (todasTurno.isEmpty()) return emptyList()

        val tokensQuery = query
            ?.let { LucyMemoryStorageSerializer.normalizarClaveSemantica(it) }
            ?.split(' ')
            ?.filter { it.length >= 2 }
            .orEmpty()

        return todasTurno.filter { nota ->
            val matchEmpresa = empresa == null || nota.empresaCodigo == empresa.codigo
            val matchQuery = if (tokensQuery.isEmpty()) {
                true
            } else {
                val normNota = TextoNormalizer.normalizar(nota.texto)
                tokensQuery.all { normNota.contains(it) }
            }
            matchEmpresa && matchQuery
        }.sortedByDescending { it.actualizadoEn }
    }

    override suspend fun olvidarUltimaPreferencia(): LucyResultadoEliminacionMemoria {
        var resultado = LucyResultadoEliminacionMemoria(0, huboError = true)
        val ok = safeEdit { prefs ->
            val prefActual = extractPreferencias(prefs)
            val tipoObjetivo = prefActual.ultimoTipoModificado ?: inferirPreferenciaActiva(prefActual)
            when (tipoObjetivo) {
                LucyTipoPreferencia.VELOCIDAD -> {
                    prefs.remove(KEY_PREF_VELOCIDAD)
                    prefs.remove(KEY_PREF_ULTIMO_TIPO)
                    resultado = LucyResultadoEliminacionMemoria(1, "Olvidé la preferencia de velocidad; volví a velocidad normal.")
                }
                LucyTipoPreferencia.ESTILO -> {
                    prefs.remove(KEY_PREF_ESTILO)
                    prefs.remove(KEY_PREF_ULTIMO_TIPO)
                    resultado = LucyResultadoEliminacionMemoria(1, "Olvidé la preferencia de extensión; volví al estilo normal.")
                }
                LucyTipoPreferencia.ESTILO_VOZ -> {
                    prefs.remove(KEY_PREF_ESTILO_VOZ)
                    prefs.remove(KEY_PREF_ULTIMO_TIPO)
                    resultado = LucyResultadoEliminacionMemoria(1, "Olvidé el estilo de voz; volví al tono natural.")
                }
                LucyTipoPreferencia.VOZ_PREFERIDA -> {
                    prefs.remove(KEY_PREF_VOZ_ID)
                    prefs.remove(KEY_PREF_ULTIMO_TIPO)
                    resultado = LucyResultadoEliminacionMemoria(1, "Olvidé la voz específica; volví a selección automática.")
                }
                LucyTipoPreferencia.INSTRUCCION -> {
                    val lista = prefActual.instruccionesPermanentes.toMutableList()
                    val targetIdx = prefActual.ultimaInstruccionId
                        ?.let { idBuscado -> lista.indexOfFirst { it.id == idBuscado } }
                        ?.takeIf { it >= 0 }
                        ?: (lista.size - 1)
                    if (targetIdx >= 0) {
                        val removida = lista.removeAt(targetIdx)
                        if (lista.isEmpty()) {
                            prefs.remove(KEY_PREF_INSTRUCCIONES)
                        } else {
                            prefs[KEY_PREF_INSTRUCCIONES] = LucyMemoryStorageSerializer.serializarInstrucciones(lista)
                        }
                        prefs.remove(KEY_PREF_ULTIMO_TIPO)
                        prefs.remove(KEY_PREF_ULTIMA_INSTRUCCION_ID)
                        resultado = LucyResultadoEliminacionMemoria(1, "Olvidé la instrucción: \"${removida.texto}\".")
                    } else {
                        resultado = LucyResultadoEliminacionMemoria(0, "No había preferencias activas para olvidar.")
                    }
                }
                null -> {
                    resultado = LucyResultadoEliminacionMemoria(0, "No había preferencias personalizadas guardadas.")
                }
            }
        }
        return if (ok) resultado else LucyResultadoEliminacionMemoria(
            eliminados = 0,
            detalle = lastWriteError ?: "No se pudo eliminar la preferencia.",
            huboError = true
        )
    }

    override suspend fun olvidarTodasPreferencias(): LucyResultadoEliminacionMemoria {
        var eliminados = 0
        val ok = safeEdit { prefs ->
            val prefActual = extractPreferencias(prefs)
            if (prefActual.velocidad != LucyVelocidadRespuesta.NORMAL) eliminados++
            if (prefActual.estilo != LucyEstiloRespuesta.NORMAL) eliminados++
            if (prefActual.estiloVoz != LucyEstiloVoz.NATURAL_CERCANA) eliminados++
            if (prefActual.vozPreferidaId.isNotBlank()) eliminados++
            eliminados += prefActual.instruccionesPermanentes.size
            prefs.remove(KEY_PREF_VELOCIDAD)
            prefs.remove(KEY_PREF_ESTILO)
            prefs.remove(KEY_PREF_ESTILO_VOZ)
            prefs.remove(KEY_PREF_VOZ_ID)
            prefs.remove(KEY_PREF_INSTRUCCIONES)
            prefs.remove(KEY_PREF_ULTIMO_TIPO)
            prefs.remove(KEY_PREF_ULTIMA_INSTRUCCION_ID)
        }
        return if (ok) {
            LucyResultadoEliminacionMemoria(
                eliminados = eliminados,
                detalle = if (eliminados > 0) "Restablecí $eliminados preferencia(s) de conversación." else "Las preferencias ya estaban en valores normales."
            )
        } else {
            LucyResultadoEliminacionMemoria(0, lastWriteError ?: "Error al restablecer preferencias.", huboError = true)
        }
    }

    override suspend fun olvidarNotas(
        turno: Turno,
        empresa: EmpresaOficial?,
        referenciaTexto: String?,
        soloUltimaSiSinFiltro: Boolean
    ): LucyResultadoEliminacionMemoria {
        var eliminados = 0
        val tokensRef = referenciaTexto
            ?.let { LucyMemoryStorageSerializer.normalizarClaveSemantica(it) }
            ?.split(' ')
            ?.filter { it.length >= 2 }
            .orEmpty()

        val ok = safeEdit { prefs ->
            val actuales = parseNotasSafe(prefs[notesKey(turno)], turno).toMutableList()
            if (actuales.isEmpty()) {
                eliminados = 0
                return@safeEdit
            }

            if (empresa == null && tokensRef.isEmpty() && soloUltimaSiSinFiltro) {
                val ultimaId = prefs[lastNoteIdKey(turno)]
                val idx = ultimaId
                    ?.let { id -> actuales.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 }
                    ?: (actuales.size - 1)
                if (idx >= 0) {
                    actuales.removeAt(idx)
                    eliminados = 1
                }
            } else {
                val restantes = actuales.filterNot { nota ->
                    val matchEmpresa = empresa == null || nota.empresaCodigo == empresa.codigo
                    val matchRef = tokensRef.isEmpty() ||
                        tokensRef.all { TextoNormalizer.normalizar(nota.texto).contains(it) }
                    val coincide = matchEmpresa && matchRef
                    if (coincide) eliminados++
                    coincide
                }
                actuales.clear()
                actuales.addAll(restantes)
            }

            if (actuales.isEmpty()) {
                prefs.remove(notesKey(turno))
                prefs.remove(lastNoteIdKey(turno))
            } else {
                prefs[notesKey(turno)] = LucyMemoryStorageSerializer.serializarNotas(actuales)
                if (prefs[lastNoteIdKey(turno)] !in actuales.map { it.id }) {
                    prefs[lastNoteIdKey(turno)] = actuales.last().id
                }
            }
        }
        return if (ok) {
            LucyResultadoEliminacionMemoria(
                eliminados = eliminados,
                detalle = if (eliminados > 0) {
                    "Eliminé $eliminados recuerdo(s) de ${turno.nombre}."
                } else {
                    "No encontré recuerdos coincidentes en ${turno.nombre}."
                }
            )
        } else {
            LucyResultadoEliminacionMemoria(0, lastWriteError ?: "No se pudieron eliminar recuerdos.", huboError = true)
        }
    }

    override suspend fun limpiarContextoTurno(turno: Turno): LucyResultadoEliminacionMemoria {
        var eliminados = 0
        val ok = safeEdit { prefs ->
            val notas = parseNotasSafe(prefs[notesKey(turno)], turno)
            val correcciones = parseCorreccionesSafe(prefs[correccionesKey(turno)], turno)
            val cambios = parseCambiosSafe(prefs[cambiosMaestroKey(turno)], turno)
            eliminados = notas.size +
                correcciones.size +
                cambios.size +
                (if (prefs.contains(lastWorkerKey(turno))) 1 else 0) +
                (if (prefs.contains(lastEmpresaKey(turno))) 1 else 0)
            prefs.remove(notesKey(turno))
            prefs.remove(lastNoteIdKey(turno))
            prefs.remove(lastWorkerKey(turno))
            prefs.remove(lastEmpresaKey(turno))
            prefs.remove(correccionesKey(turno))
            prefs.remove(cambiosMaestroKey(turno))
        }
        return if (ok) {
            LucyResultadoEliminacionMemoria(eliminados, "Contexto de memoria limpiado en ${turno.nombre}.")
        } else {
            LucyResultadoEliminacionMemoria(0, lastWriteError ?: "Error al limpiar contexto.", huboError = true)
        }
    }

    override suspend fun obtenerResumenMemoria(): LucyResumenMemoria? {
        val prefs = safeReadPreferences() ?: return null
        return LucyResumenMemoria(
            preferencias = extractPreferencias(prefs),
            turnoA = extractTurnoSnapshot(prefs, Turno.A),
            turnoB = extractTurnoSnapshot(prefs, Turno.B)
        )
    }

    override suspend fun guardarCorreccionAprendida(
        correccion: LucyCorreccionAprendida
    ): LucyResultadoEscrituraMemoria {
        val claveNorm = LucyMemoryStorageSerializer.normalizarClaveCorreccion(correccion.textoErroneo)
        if (claveNorm.isBlank() || correccion.trabajadorId.isBlank()) {
            return LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR, "Corrección incompleta.")
        }
        var resultado = LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR)
        val ok = safeEdit { prefs ->
            val actuales = parseCorreccionesSafe(prefs[correccionesKey(correccion.turno)], correccion.turno).toMutableList()
            val ahora = clock()
            val idx = actuales.indexOfFirst {
                it.claveErroneaNorm == claveNorm &&
                    it.tipo == correccion.tipo &&
                    (it.empresaCodigo == correccion.empresaCodigo || correccion.empresaCodigo == null)
            }
            if (idx >= 0) {
                val existente = actuales[idx]
                if (existente.trabajadorId == correccion.trabajadorId &&
                    existente.valorCorregido == correccion.valorCorregido
                ) {
                    resultado = LucyResultadoEscrituraMemoria(
                        LucyEstadoEscrituraMemoria.YA_EXISTIA,
                        "Corrección ya registrada."
                    )
                    return@safeEdit
                }
                actuales[idx] = existente.copy(
                    trabajadorId = correccion.trabajadorId,
                    valorCorregido = correccion.valorCorregido,
                    empresaCodigo = correccion.empresaCodigo ?: existente.empresaCodigo,
                    actualizadoEn = ahora
                )
                prefs[correccionesKey(correccion.turno)] = LucyMemoryStorageSerializer.serializarCorrecciones(actuales)
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.ACTUALIZADO,
                    "Corrección actualizada."
                )
            } else {
                val nueva = correccion.copy(
                    claveErroneaNorm = claveNorm,
                    creadoEn = ahora,
                    actualizadoEn = ahora
                )
                actuales.add(nueva)
                prefs[correccionesKey(correccion.turno)] = LucyMemoryStorageSerializer.serializarCorrecciones(
                    actuales.takeLast(MAX_CORRECCIONES_POR_TURNO)
                )
                resultado = LucyResultadoEscrituraMemoria(
                    LucyEstadoEscrituraMemoria.GUARDADO,
                    "Corrección aprendida."
                )
            }
        }
        return if (ok) resultado else LucyResultadoEscrituraMemoria(
            LucyEstadoEscrituraMemoria.ERROR,
            lastWriteError ?: "No se pudo guardar la corrección."
        )
    }

    override suspend fun obtenerCorreccionesAprendidas(
        turno: Turno,
        tipo: LucyTipoCorreccionAprendida?
    ): List<LucyCorreccionAprendida> {
        val prefs = safeReadPreferences() ?: return emptyList()
        val todas = parseCorreccionesSafe(prefs[correccionesKey(turno)], turno)
        return if (tipo == null) {
            todas.sortedByDescending { it.actualizadoEn }
        } else {
            todas.filter { it.tipo == tipo }.sortedByDescending { it.actualizadoEn }
        }
    }

    override suspend fun olvidarCorreccionesAprendidas(turno: Turno): LucyResultadoEliminacionMemoria {
        var eliminados = 0
        val ok = safeEdit { prefs ->
            val actuales = parseCorreccionesSafe(prefs[correccionesKey(turno)], turno)
            eliminados = actuales.size
            prefs.remove(correccionesKey(turno))
        }
        return if (ok) {
            LucyResultadoEliminacionMemoria(eliminados, "Olvidé $eliminados corrección(es) en ${turno.nombre}.")
        } else {
            LucyResultadoEliminacionMemoria(0, lastWriteError ?: "Error al olvidar correcciones.", huboError = true)
        }
    }

    override suspend fun registrarCambioMaestro(
        cambio: LucyRegistroCambioMaestro
    ): LucyResultadoEscrituraMemoria {
        var resultado = LucyResultadoEscrituraMemoria(LucyEstadoEscrituraMemoria.ERROR)
        val ok = safeEdit { prefs ->
            val actuales = parseCambiosSafe(prefs[cambiosMaestroKey(cambio.turno)], cambio.turno).toMutableList()
            val registro = cambio.copy(fechaMs = if (cambio.fechaMs > 0L) cambio.fechaMs else clock())
            actuales.add(registro)
            prefs[cambiosMaestroKey(cambio.turno)] = LucyMemoryStorageSerializer.serializarCambiosMaestro(
                actuales.takeLast(MAX_CAMBIOS_MAESTRO_POR_TURNO)
            )
            resultado = LucyResultadoEscrituraMemoria(
                LucyEstadoEscrituraMemoria.GUARDADO,
                "Cambio registrado en ${cambio.turno.nombre}."
            )
        }
        return if (ok) resultado else LucyResultadoEscrituraMemoria(
            LucyEstadoEscrituraMemoria.ERROR,
            lastWriteError ?: "No se pudo registrar el cambio maestro."
        )
    }

    override suspend fun obtenerHistorialCambiosMaestro(
        turno: Turno,
        trabajadorId: String?
    ): List<LucyRegistroCambioMaestro> {
        val prefs = safeReadPreferences() ?: return emptyList()
        val todos = parseCambiosSafe(prefs[cambiosMaestroKey(turno)], turno)
        return if (trabajadorId.isNullOrBlank()) {
            todos.sortedByDescending { it.fechaMs }
        } else {
            todos.filter { it.trabajadorId == trabajadorId }.sortedByDescending { it.fechaMs }
        }
    }

    private suspend fun safeReadPreferences(): Preferences? = withContext(ioDispatcher) {
        try {
            dataStore.data.first()
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (_: IOException) {
            null
        } catch (_: CorruptionException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun safeEdit(block: (MutablePreferences) -> Unit): Boolean = withContext(ioDispatcher) {
        try {
            dataStore.edit { prefs ->
                block(prefs)
            }
            lastWriteError = null
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (e: IOException) {
            lastWriteError = e.message ?: "error de E/S"
            false
        } catch (e: CorruptionException) {
            lastWriteError = e.message ?: "archivo corrupto"
            false
        } catch (e: Exception) {
            lastWriteError = e.message ?: e::class.java.simpleName
            false
        }
    }

    private fun extractPreferencias(prefs: Preferences): LucyPreferenciasConversacion {
        val velocidad = LucyVelocidadRespuesta.fromStored(prefs[KEY_PREF_VELOCIDAD])
        val estilo = LucyEstiloRespuesta.fromStored(prefs[KEY_PREF_ESTILO])
        val instrucciones = parseInstruccionesSafe(prefs[KEY_PREF_INSTRUCCIONES])
        val ultimoTipo = prefs[KEY_PREF_ULTIMO_TIPO]?.let { stored ->
            LucyTipoPreferencia.entries.firstOrNull { it.name == stored }
        }
        val ultimaInstrId = prefs[KEY_PREF_ULTIMA_INSTRUCCION_ID]
        val vozId = prefs[KEY_PREF_VOZ_ID].orEmpty()
        val estiloVoz = LucyEstiloVoz.fromStored(prefs[KEY_PREF_ESTILO_VOZ])
        return LucyPreferenciasConversacion(
            velocidad = velocidad,
            estilo = estilo,
            instruccionesPermanentes = instrucciones,
            ultimoTipoModificado = ultimoTipo,
            ultimaInstruccionId = ultimaInstrId,
            vozPreferidaId = vozId,
            estiloVoz = estiloVoz
        )
    }

    private fun extractTurnoSnapshot(prefs: Preferences, turno: Turno): LucyMemoriaTurnoSnapshot {
        return LucyMemoriaTurnoSnapshot(
            turno = turno,
            ultimoTrabajadorId = prefs[lastWorkerKey(turno)],
            ultimaEmpresaCodigo = prefs[lastEmpresaKey(turno)],
            ultimaNotaId = prefs[lastNoteIdKey(turno)],
            notas = parseNotasSafe(prefs[notesKey(turno)], turno)
        )
    }

    private fun parseInstruccionesSafe(json: String?): List<LucyInstruccionMemoria> {
        return try {
            LucyMemoryStorageSerializer.deserializarInstrucciones(json)
        } catch (_: JSONException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseNotasSafe(json: String?, turno: Turno): List<LucyNotaMemoria> {
        return try {
            LucyMemoryStorageSerializer.deserializarNotas(json, turno)
        } catch (_: JSONException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCorreccionesSafe(json: String?, turno: Turno): List<LucyCorreccionAprendida> {
        return try {
            LucyMemoryStorageSerializer.deserializarCorrecciones(json, turno)
        } catch (_: JSONException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCambiosSafe(json: String?, turno: Turno): List<LucyRegistroCambioMaestro> {
        return try {
            LucyMemoryStorageSerializer.deserializarCambiosMaestro(json, turno)
        } catch (_: JSONException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun inferirPreferenciaActiva(pref: LucyPreferenciasConversacion): LucyTipoPreferencia? = when {
        pref.instruccionesPermanentes.isNotEmpty() -> LucyTipoPreferencia.INSTRUCCION
        pref.vozPreferidaId.isNotBlank() -> LucyTipoPreferencia.VOZ_PREFERIDA
        pref.estiloVoz != LucyEstiloVoz.NATURAL_CERCANA -> LucyTipoPreferencia.ESTILO_VOZ
        pref.estilo != LucyEstiloRespuesta.NORMAL -> LucyTipoPreferencia.ESTILO
        pref.velocidad != LucyVelocidadRespuesta.NORMAL -> LucyTipoPreferencia.VELOCIDAD
        else -> null
    }

    companion object {
        private const val STORE_FILE_NAME = "lucy_memory_prefs"
        private const val MAX_INSTRUCCIONES = 20
        private const val MAX_NOTAS_POR_TURNO = 40
        private const val MAX_CORRECCIONES_POR_TURNO = 120
        private const val MAX_CAMBIOS_MAESTRO_POR_TURNO = 120

        private val KEY_PREF_VELOCIDAD = stringPreferencesKey("lucy_pref_velocidad")
        private val KEY_PREF_ESTILO = stringPreferencesKey("lucy_pref_estilo")
        private val KEY_PREF_ESTILO_VOZ = stringPreferencesKey("lucy_pref_estilo_voz")
        private val KEY_PREF_VOZ_ID = stringPreferencesKey("lucy_pref_voz_id")
        private val KEY_PREF_INSTRUCCIONES = stringPreferencesKey("lucy_pref_instrucciones_json")
        private val KEY_PREF_ULTIMO_TIPO = stringPreferencesKey("lucy_pref_ultimo_tipo")
        private val KEY_PREF_ULTIMA_INSTRUCCION_ID = stringPreferencesKey("lucy_pref_ultima_instruccion_id")

        private val KEY_TURNO_A_LAST_WORKER = stringPreferencesKey("lucy_turno_a_ultimo_trabajador")
        private val KEY_TURNO_B_LAST_WORKER = stringPreferencesKey("lucy_turno_b_ultimo_trabajador")
        private val KEY_TURNO_A_LAST_EMPRESA = stringPreferencesKey("lucy_turno_a_ultima_empresa")
        private val KEY_TURNO_B_LAST_EMPRESA = stringPreferencesKey("lucy_turno_b_ultima_empresa")
        private val KEY_TURNO_A_NOTES = stringPreferencesKey("lucy_turno_a_notas_json")
        private val KEY_TURNO_B_NOTES = stringPreferencesKey("lucy_turno_b_notas_json")
        private val KEY_TURNO_A_LAST_NOTE_ID = stringPreferencesKey("lucy_turno_a_ultima_nota_id")
        private val KEY_TURNO_B_LAST_NOTE_ID = stringPreferencesKey("lucy_turno_b_ultima_nota_id")
        private val KEY_TURNO_A_CORRECCIONES = stringPreferencesKey("lucy_turno_a_correcciones_json")
        private val KEY_TURNO_B_CORRECCIONES = stringPreferencesKey("lucy_turno_b_correcciones_json")
        private val KEY_TURNO_A_CAMBIOS_MAESTRO = stringPreferencesKey("lucy_turno_a_cambios_maestro_json")
        private val KEY_TURNO_B_CAMBIOS_MAESTRO = stringPreferencesKey("lucy_turno_b_cambios_maestro_json")

        private fun lastWorkerKey(turno: Turno) =
            if (turno == Turno.A) KEY_TURNO_A_LAST_WORKER else KEY_TURNO_B_LAST_WORKER

        private fun lastEmpresaKey(turno: Turno) =
            if (turno == Turno.A) KEY_TURNO_A_LAST_EMPRESA else KEY_TURNO_B_LAST_EMPRESA

        private fun notesKey(turno: Turno) =
            if (turno == Turno.A) KEY_TURNO_A_NOTES else KEY_TURNO_B_NOTES

        private fun lastNoteIdKey(turno: Turno) =
            if (turno == Turno.A) KEY_TURNO_A_LAST_NOTE_ID else KEY_TURNO_B_LAST_NOTE_ID

        private fun correccionesKey(turno: Turno) =
            if (turno == Turno.A) KEY_TURNO_A_CORRECCIONES else KEY_TURNO_B_CORRECCIONES

        private fun cambiosMaestroKey(turno: Turno) =
            if (turno == Turno.A) KEY_TURNO_A_CAMBIOS_MAESTRO else KEY_TURNO_B_CAMBIOS_MAESTRO

        @Volatile
        private var appDataStoreInstance: DataStore<Preferences>? = null

        fun getOrCreateAppDataStore(context: Context): DataStore<Preferences> {
            return appDataStoreInstance ?: synchronized(this) {
                appDataStoreInstance ?: PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    produceFile = { context.applicationContext.preferencesDataStoreFile(STORE_FILE_NAME) }
                ).also { appDataStoreInstance = it }
            }
        }

        fun createForApp(context: Context): DataStoreLucyMemoryPort {
            return DataStoreLucyMemoryPort(getOrCreateAppDataStore(context))
        }
    }
}
