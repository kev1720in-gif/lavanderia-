package com.example

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.data.backup.BackupSerializer
import com.example.data.backup.ResultadoRestauracionBackup
import com.example.data.backup.ResultadoValidacionBackup
import com.example.data.local.LavanderiaDatabase
import com.example.data.local.entities.TrabajadorEntity
import com.example.data.ocr.OcrImageProcessor
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.AccionImportacion
import com.example.domain.model.BuscadorTrabajadores
import com.example.domain.model.ClasificacionImportacion
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.OcrNombreMatcher
import com.example.domain.model.TextoNormalizer
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.ui.navigation.LavanderiaApp
import com.example.ui.theme.LavanderiaControlTheme
import com.example.ui.viewmodel.BackupViewModel
import com.example.ui.viewmodel.DescargaViewModel
import com.example.ui.viewmodel.ImportacionViewModel
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class LavanderiaStage1RobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: LavanderiaDatabase
    private lateinit var repository: LavanderiaRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LavanderiaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LavanderiaRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `1 - inicio limpio tiene 0 trabajadores y nunca reinserta ficticios al reiniciar`() = runTest {
        val dbFile = File(context.filesDir, "test_cero_ficticios.db")
        if (dbFile.exists()) dbFile.delete()

        val db1 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val repo1 = LavanderiaRepository(db1)
        repo1.ensureInitialized()

        assertEquals(0, repo1.observeConteoTurno(Turno.A).first())
        assertEquals(0, repo1.observeConteoTurno(Turno.B).first())
        assertEquals(0, db1.trabajadorDao().getAllOnce().size)

        // Usuario agrega 1 trabajador real
        val real = repo1.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "Luis",
            primerApellido = "Mendoza",
            segundoApellido = "Quispe",
            habitacion = "108",
            pabellon = "B"
        )
        db1.close()

        // Reabrir DB: sigue habiendo 0 en Turno A y solo el trabajador real en Turno B
        val db2 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val repo2 = LavanderiaRepository(db2)
        repo2.ensureInitialized()

        assertEquals(0, repo2.observeConteoTurno(Turno.A).first())
        assertEquals(1, repo2.observeConteoTurno(Turno.B).first())
        val todos = db2.trabajadorDao().getAllOnce()
        assertEquals(1, todos.size)
        assertEquals(real.id, todos.first().id)

        db2.close()
        dbFile.delete()
    }

    @Test
    fun `2 - proteccion de 657 trabajadores reales en Turno B y Turno A tras reiniciar base de datos`() = runTest {
        val dbFile = File(context.filesDir, "test_657_reales.db")
        if (dbFile.exists()) dbFile.delete()

        val db1 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val repo1 = LavanderiaRepository(db1)
        repo1.ensureInitialized()

        val trabajadoresTurnoB = generarTrabajadoresRealesMasivos(Turno.B, 657)
        val trabajadoresTurnoA = generarTrabajadoresRealesMasivos(Turno.A, 18)
        db1.trabajadorDao().insertAll(trabajadoresTurnoB + trabajadoresTurnoA)
        db1.close()

        // Simular actualización/reapertura de la aplicación
        val db2 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val repo2 = LavanderiaRepository(db2)
        repo2.ensureInitialized()

        val recuperadosB = repo2.obtenerTrabajadoresPorTurnoOnce(Turno.B)
        val recuperadosA = repo2.obtenerTrabajadoresPorTurnoOnce(Turno.A)
        assertEquals(657, recuperadosB.size)
        assertEquals(18, recuperadosA.size)
        assertEquals(0, db2.trabajadorDao().getAllOnce().count { it.esDatoPrueba })

        val muestraOriginal = trabajadoresTurnoB[320]
        val muestraRecuperada = recuperadosB.first { it.id == muestraOriginal.id }
        assertEquals(muestraOriginal.codigoInterno, muestraRecuperada.codigoInterno)
        assertEquals(muestraOriginal.empresaId, muestraRecuperada.empresaId)
        assertEquals(muestraOriginal.habitacion, muestraRecuperada.habitacion)
        assertEquals(muestraOriginal.pabellon, muestraRecuperada.pabellon)

        db2.close()
        dbFile.delete()
    }

    @Test
    fun `3 - exportar validar rechazar corruptos y restaurar copia de seguridad completa con 657 trabajadores`() {
        val dbFile = File(context.filesDir, "test_backup_restore_657.db")
        val safetyDir = File(context.filesDir, "test_safety_backups")
        if (dbFile.exists()) dbFile.delete()
        if (safetyDir.exists()) safetyDir.deleteRecursively()

        val db1 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val repo1 = LavanderiaRepository(db1)

        val trabajadoresB = generarTrabajadoresRealesMasivos(Turno.B, 657)
        val trabajadoresA = generarTrabajadoresRealesMasivos(Turno.A, 12)
        lateinit var backupJson: String

        runBlocking {
            repo1.ensureInitialized()
            db1.trabajadorDao().insertAll(trabajadoresB + trabajadoresA)
            repo1.setTurnoActivo(Turno.B)

            // Marcar SÍ BAJÓ en un trabajador de Turno B y otro de Turno A
            val estadoB0 = repo1.observeTrabajadoresConEstado(Turno.B).first().first { it.id == trabajadoresB[0].id }
            val estadoA0 = repo1.observeTrabajadoresConEstado(Turno.A).first().first { it.id == trabajadoresA[0].id }
            repo1.alternarMarcacionSiBajo(estadoB0)
            repo1.alternarMarcacionSiBajo(estadoA0)

            val (snapshot, json) = repo1.exportarBackupJson()
            backupJson = json

            assertEquals(669, snapshot.resumen.totalTrabajadores)
            assertEquals(12, snapshot.resumen.totalTurnoA)
            assertEquals(657, snapshot.resumen.totalTurnoB)
            assertEquals(2, snapshot.resumen.totalMarcaciones)
            assertEquals(8, snapshot.resumen.totalEmpresas)
        }

        // Verificar que la validación rechaza archivos corruptos, vacíos, incompletos o incompatibles sin tocar DB
        assertTrue(repo1.validarBackupJson("") is ResultadoValidacionBackup.Invalido)
        assertTrue(repo1.validarBackupJson("{corrupto") is ResultadoValidacionBackup.Invalido)

        val versionIncompatible = JSONObject(backupJson).apply { put("backupFormatVersion", 99) }.toString()
        assertTrue(repo1.validarBackupJson(versionIncompatible) is ResultadoValidacionBackup.Invalido)

        val sinTablaTrabajadores = JSONObject(backupJson).apply {
            getJSONObject("tablas").remove("trabajadores")
        }.toString()
        assertTrue(repo1.validarBackupJson(sinTablaTrabajadores) is ResultadoValidacionBackup.Invalido)

        val resumenAlterado = JSONObject(backupJson).apply {
            getJSONObject("resumen").put("totalTurnoB", 10)
        }.toString()
        assertTrue(repo1.validarBackupJson(resumenAlterado) is ResultadoValidacionBackup.Invalido)

        // Verificar rechazo desde BackupViewModel y que DB siga con 657 en Turno B
        val backupVm = BackupViewModel(repo1)
        backupVm.prepararRestauracionDesdeContenido(sinTablaTrabajadores, "invalido.json")
        waitForState { !backupVm.uiState.value.procesando && backupVm.uiState.value.ultimoMensaje != null }
        assertTrue(backupVm.uiState.value.ultimoEsError)
        assertEquals(657, runBlocking { repo1.observeConteoTurno(Turno.B).first() })

        // Simular pérdida/alteración accidental en DB actual (ej. borrar 500 de Turno B y agregar uno temporal)
        runBlocking {
            repo1.eliminarTrabajadoresPorIds(trabajadoresB.take(500).map { it.id })
            repo1.agregarTrabajador(
                turno = Turno.A,
                empresa = EmpresaOficial.KOMATSU,
                nombre = "Temporal",
                primerApellido = "Borrado",
                segundoApellido = "",
                habitacion = "999",
                pabellon = "Z"
            )
            assertEquals(157, repo1.observeConteoTurno(Turno.B).first())
        }

        // Preparar restauración válida -> debe pedir confirmación SIN alterar aún la DB
        backupVm.prepararRestauracionDesdeContenido(backupJson, "Lavanderia_Control_Backup_ok.json")
        waitForState { backupVm.uiState.value.confirmacionPendiente != null }
        val pendiente = backupVm.uiState.value.confirmacionPendiente!!
        assertEquals(657, pendiente.validado.resumen.totalTurnoB)
        assertEquals(12, pendiente.validado.resumen.totalTurnoA)
        assertEquals(157, runBlocking { repo1.observeConteoTurno(Turno.B).first() })

        // Confirmar restauración -> crea respaldo preventivo automático + restaura 657 + 12 + marcas
        backupVm.confirmarRestauracion(safetyDir)
        waitForState { !backupVm.uiState.value.procesando && backupVm.uiState.value.confirmacionPendiente == null }
        assertFalse(backupVm.uiState.value.ultimoEsError)

        // Verificar que se generó el respaldo preventivo del estado previo (157 B + 13 A)
        val archivosPreventivos = safetyDir.listFiles { f -> f.name.startsWith("AutoSafetyBackup_") && f.name.endsWith(".json") }
        assertNotNull(archivosPreventivos)
        assertEquals(1, archivosPreventivos!!.size)
        val validacionPreventivo = BackupSerializer.validarYParsear(archivosPreventivos.first().readText(Charsets.UTF_8))
        assertTrue(validacionPreventivo is ResultadoValidacionBackup.Valido)
        assertEquals(157, (validacionPreventivo as ResultadoValidacionBackup.Valido).validado.resumen.totalTurnoB)

        db1.close()

        // Reabrir DB desde disco para verificar persistencia real post-restauración
        val db2 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val repo2 = LavanderiaRepository(db2)
        runBlocking {
            repo2.ensureInitialized()
            assertEquals(Turno.B, repo2.turnoActivo.first())
            val listaB = repo2.observeTrabajadoresConEstado(Turno.B).first()
            val listaA = repo2.observeTrabajadoresConEstado(Turno.A).first()
            assertEquals(657, listaB.size)
            assertEquals(12, listaA.size)
            assertTrue(listaB.first { it.id == trabajadoresB[0].id }.siBajo)
            assertTrue(listaA.first { it.id == trabajadoresA[0].id }.siBajo)
            assertEquals(trabajadoresB[499].codigoInterno, listaB.first { it.id == trabajadoresB[499].id }.codigoInterno)
            assertEquals(trabajadoresB[499].habitacion, listaB.first { it.id == trabajadoresB[499].id }.habitacion)
        }

        db2.close()
        dbFile.delete()
        safetyDir.deleteRecursively()
    }

    @Test
    fun `4 - limpiar registro Turno A y Turno B borra marcas pero conserva trabajadores y separa turnos`() = runTest {
        repository.ensureInitialized()

        val joseA = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "José",
            primerApellido = "Pérez",
            segundoApellido = "Tapia",
            habitacion = "22",
            pabellon = "C"
        )
        val kevinB = repository.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.CUMMINS,
            nombre = "Kevin",
            primerApellido = "Quispe",
            segundoApellido = "Linares",
            habitacion = "22",
            pabellon = "C"
        )

        val joseEstado = repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == joseA.id }
        val kevinEstado = repository.observeTrabajadoresConEstado(Turno.B).first().first { it.id == kevinB.id }
        repository.alternarMarcacionSiBajo(joseEstado)
        repository.alternarMarcacionSiBajo(kevinEstado)

        assertTrue(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == joseA.id }.siBajo)
        assertTrue(repository.observeTrabajadoresConEstado(Turno.B).first().first { it.id == kevinB.id }.siBajo)

        repository.limpiarRegistroDePruebaPorTurno(Turno.A)
        val listaADespues = repository.observeTrabajadoresConEstado(Turno.A).first()
        val listaBDespues = repository.observeTrabajadoresConEstado(Turno.B).first()
        assertEquals(1, listaADespues.size)
        assertEquals(1, listaBDespues.size)
        assertFalse(listaADespues.first { it.id == joseA.id }.siBajo)
        assertTrue(listaBDespues.first { it.id == kevinB.id }.siBajo)
    }

    @Test
    fun `5 - persistencia real tras cerrar y reabrir base de datos con manuales y estados`() = runTest {
        val dbFile = File(context.filesDir, "test_persistencia_completa.db")
        if (dbFile.exists()) dbFile.delete()

        val db1 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val repo1 = LavanderiaRepository(db1)
        repo1.ensureInitialized()

        val agregado = repo1.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.KOMATSU_ESOP,
            nombre = "Karol",
            primerApellido = "Huamash",
            segundoApellido = "",
            habitacion = "410",
            pabellon = "D"
        )
        val trabajadorState = repo1.observeTrabajadoresConEstado(Turno.B).first().first { it.id == agregado.id }
        repo1.alternarMarcacionSiBajo(trabajadorState)
        repo1.setTurnoActivo(Turno.B)
        db1.close()

        val db2 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val repo2 = LavanderiaRepository(db2)
        repo2.ensureInitialized()

        assertEquals(Turno.B, repo2.turnoActivo.first())
        assertEquals(0, repo2.observeConteoTurno(Turno.A).first())
        assertEquals(1, repo2.observeConteoTurno(Turno.B).first())

        val recuperado = repo2.observeTrabajadoresConEstado(Turno.B).first().first { it.id == agregado.id }
        assertEquals("HUAMASH KAROL", recuperado.nombreCompleto)
        assertTrue(recuperado.siBajo)

        db2.close()
        dbFile.delete()
    }

    @Test
    fun `6 - importacion unificada Excel y CSV al maestro sin duplicar, sin borrar ausentes y con revision ambigua`() {
        runBlocking {
            repository.ensureInitialized()
            repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "José", "Pérez", "Tapia", "22", "C")
            repository.agregarTrabajador(Turno.A, EmpresaOficial.CUMMINS, "Matías", "Rojas", "Contreras", "104", "B")
            repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Juan", "González", "Vega", "301", "A")
        }
        val importVm = ImportacionViewModel(repository)

        val csv = """
            Primer Apellido;Segundo Apellido;Nombre;Habitación;Pabellón;Empresa;Turno
            Huamash;;Karol;2;a;Komatsu ESOP;Turno A
            Pérez;Tapia;José;22;C;Komatsu;Turno A
            Rojas;Contreras;Matías;555;B;Cummins;Turno A
            González;;Juan;888;A;Komatsu;Turno A
        """.trimIndent()

        importVm.cargarArchivo(
            nombreArchivo = "prueba.csv",
            abrirStream = { ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)) },
            mimeType = "text/csv",
            turnoActivo = Turno.A
        )

        waitForState { importVm.uiState.value.filasPreview.isNotEmpty() }
        val filas = importVm.uiState.value.filasPreview
        assertEquals(4, filas.size)

        assertEquals(ClasificacionImportacion.NUEVO, filas[0].clasificacion)
        assertEquals(AccionImportacion.AGREGAR_NUEVO, filas[0].accion)

        assertEquals(ClasificacionImportacion.YA_EXISTE_IDENTICO, filas[1].clasificacion)
        assertEquals(AccionImportacion.OMITIR, filas[1].accion)

        assertEquals(ClasificacionImportacion.EXISTENTE_CON_CAMBIOS, filas[2].clasificacion)
        assertFalse(filas[2].revisionResuelta)

        assertEquals(ClasificacionImportacion.REQUIERE_REVISION, filas[3].clasificacion)
        assertFalse(filas[3].revisionResuelta)

        val matiasAntes = runBlocking { repository.obtenerTrabajadoresPorTurnoOnce(Turno.A) }
            .first { it.primerApellido == "Rojas" && it.nombre == "Matías" }
        importVm.resolverRevisionFila(filas[2].numeroFila, AccionImportacion.ACTUALIZAR_EXISTENTE)
        importVm.resolverRevisionFila(filas[3].numeroFila, AccionImportacion.AGREGAR_NUEVO)

        var resultadoConfirmado = false
        importVm.confirmarImportacion { res ->
            assertEquals(2, res.insertados)
            assertEquals(1, res.actualizados)
            resultadoConfirmado = true
        }
        waitForState { importVm.uiState.value.resultadoImportacion != null }
        assertTrue(resultadoConfirmado)

        val turnoADespues = runBlocking { repository.obtenerTrabajadoresPorTurnoOnce(Turno.A) }
        assertEquals(5, turnoADespues.size)

        val matiasDespues = turnoADespues.first { it.id == matiasAntes.id }
        assertEquals(matiasAntes.codigoInterno, matiasDespues.codigoInterno)
        assertEquals("555", matiasDespues.habitacion)

        // Importación Excel (.xlsx) real a Turno B
        val xlsxBytes = crearXlsxPrueba(
            listOf(
                listOf("Primer Apellido", "Segundo Apellido", "Nombre", "Habitación", "Pabellón", "Empresa", "Turno"),
                listOf("Molina", "Paredes", "Camila", "912", "D", "Finning Pala", "Turno B")
            )
        )
        importVm.cargarArchivo(
            nombreArchivo = "turno_b.xlsx",
            abrirStream = { ByteArrayInputStream(xlsxBytes) },
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            turnoActivo = Turno.B
        )
        waitForState { importVm.uiState.value.archivo?.nombreArchivo == "turno_b.xlsx" }

        importVm.confirmarImportacion { res ->
            assertEquals(1, res.insertados)
            assertEquals(1, res.insertadosTurnoB)
        }
        waitForState { importVm.uiState.value.resultadoImportacion?.insertadosTurnoB == 1 }

        val turnoBDespues = runBlocking { repository.observeTrabajadoresConEstado(Turno.B).first() }
        assertEquals(1, turnoBDespues.size)
        val camila = turnoBDespues.first()
        assertEquals("MOLINA PAREDES CAMILA", camila.nombreCompleto)
        assertEquals(EmpresaOficial.FINNING_PALA, camila.empresa)
    }

    @Test
    fun `7 - eliminacion multiple conserva historial en marcaciones y no afecta no seleccionados ni otro turno`() = runTest {
        repository.ensureInitialized()
        val a1 = repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Ana", "Díaz", "", "101", "A")
        val a2 = repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Luis", "Vera", "", "102", "A")
        val a3 = repository.agregarTrabajador(Turno.A, EmpresaOficial.CUMMINS, "Rosa", "Soto", "", "103", "B")
        repository.agregarTrabajador(Turno.A, EmpresaOficial.CUMMINS, "Mario", "Ríos", "", "104", "B")
        repository.agregarTrabajador(Turno.B, EmpresaOficial.FINNING, "Pedro", "Lagos", "", "201", "C")

        val turnoA = repository.observeTrabajadoresConEstado(Turno.A).first()
        val seleccion = turnoA.filter { it.id in setOf(a1.id, a2.id, a3.id) }
        seleccion.forEach { repository.alternarMarcacionSiBajo(it) }

        val sesionA = db.sesionDao().getOpenSessionByTurno(Turno.A.id)!!
        assertEquals(3, db.marcacionDao().observeBySesionAndTurno(sesionA.id, Turno.A.id).first().size)

        val eliminados = repository.eliminarTrabajadoresPorIds(seleccion.map { it.id })
        assertEquals(3, eliminados)

        assertEquals(1, repository.observeConteoTurno(Turno.A).first())
        assertEquals(1, repository.observeConteoTurno(Turno.B).first())

        val marcasDespues = db.marcacionDao().observeBySesionAndTurno(sesionA.id, Turno.A.id).first()
        assertEquals(3, marcasDespues.size)
        assertTrue(marcasDespues.all { it.trabajadorId == null && it.siBajo })
    }

    @Test
    fun `8 - formato mayusculas, orden natural habitacion, TSV y 8 colores distintos`() {
        assertEquals(
            "HUAMASH LLASHAG KAROL",
            TextoNormalizer.formatearNombreObligatorio("Huamash", "Llashag", "Karol")
        )
        assertEquals(
            "CASTILLO ALEJANDRO",
            TextoNormalizer.formatearNombreObligatorio("Castillo", "", "Alejandro")
        )

        val t103 = TrabajadorConEstado("1", Turno.A, EmpresaOficial.KOMATSU, "ana", "soto", "", "103", "a", false, false, "C1")
        val t2 = TrabajadorConEstado("2", Turno.A, EmpresaOficial.KOMATSU, "alejandro", "castillo", "", "2", "b", false, false, "C2")
        val t15 = TrabajadorConEstado("3", Turno.A, EmpresaOficial.KOMATSU, "karol", "huamash", "llashag", "15", "c", false, false, "C3")

        val ordenados = TextoNormalizer.ordenarPorHabitacion(listOf(t103, t2, t15))
        assertEquals(listOf("2", "15", "103"), ordenados.map { it.habitacionMayusculas })

        val tsv = TextoNormalizer.generarListadoTsvParaExcel(ordenados)
        assertEquals(
            listOf(
                "APELLIDOS\tNOMBRE\tHABITACIÓN\tPABELLÓN",
                "CASTILLO\tALEJANDRO\t2\tB",
                "HUAMASH LLASHAG\tKAROL\t15\tC",
                "SOTO\tANA\t103\tA"
            ).joinToString("\n"),
            tsv
        )

        assertEquals(8, EmpresaOficial.todas.map { it.colorHex }.toSet().size)
        assertNotEquals(EmpresaOficial.KOMATSU.colorHex, EmpresaOficial.KOMATSU_ESOP.colorHex)
        assertNotEquals(EmpresaOficial.FINNING.colorHex, EmpresaOficial.FINNING_PALA.colorHex)
    }

    @Test
    fun `9 - UI Ajustes COPIAS DE SEGURIDAD sin Administracion de datos y mantenimiento de Empresas-Listado-Trabajadores`() {
        val joseId: String
        runBlocking {
            repository.ensureInitialized()
            val jose = repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "José", "Pérez", "Tapia", "22", "C")
            joseId = jose.id
            repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Raúl", "Gómez", "Paz", "30", "A")
            repository.agregarTrabajador(Turno.B, EmpresaOficial.CUMMINS, "Elena", "Vargas", "", "45", "B")
        }

        val mainVm = MainViewModel(repository)
        val importVm = ImportacionViewModel(repository)
        val backupVm = BackupViewModel(repository)
        val descargaVm = DescargaViewModel(repository)
        var backDispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LavanderiaControlTheme {
                LavanderiaApp(
                    mainViewModel = mainVm,
                    importacionViewModel = importVm,
                    backupViewModel = backupVm,
                    descargaViewModel = descargaVm
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
        }

        // Verificar búsqueda inteligente en UI por orden inverso ("José Pérez") y selección de sugerencia
        composeTestRule.onNodeWithTag("buscar_listado_input").performTextInput("José Pérez")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("sugerencia_worker_$joseId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("sugerencia_worker_$joseId").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("worker_card_$joseId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("buscar_listado_input").performTextReplacement("")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("listado_coincidencias_count").fetchSemanticsNodes().isEmpty()
        }

        // Navegar a Ajustes mediante engranaje superior
        composeTestRule.onNodeWithTag("abrir_ajustes_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isNotEmpty()
        }

        // Mantener importación, copias de seguridad y base local; eliminar encabezado redundante y "Administración de datos"
        composeTestRule.onNodeWithTag("ajustes_importar_excel_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ajustes_importar_csv_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ajustes_exportar_backup_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ajustes_restaurar_backup_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("COPIAS DE SEGURIDAD").assertIsDisplayed()
        composeTestRule.onNodeWithText("EXPORTAR COPIA DE SEGURIDAD").assertIsDisplayed()
        composeTestRule.onNodeWithText("RESTAURAR COPIA DE SEGURIDAD").assertIsDisplayed()
        composeTestRule.onNodeWithText("Información de la base de datos").assertIsDisplayed()

        assertTrue(composeTestRule.onAllNodesWithTag("volver_desde_ajustes_button").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("Importación, copias de seguridad y base local").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_limpiar_registro_button").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("Administración de datos").fetchSemanticsNodes().isEmpty())

        // Verificar diálogo de confirmación previa al cargar un backup válido en Ajustes
        val (_, jsonValido) = runBlocking { repository.exportarBackupJson() }
        backupVm.prepararRestauracionDesdeContenido(jsonValido, "Lavanderia_Control_Backup_UI.json")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("dialogo_confirmar_restauracion").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("confirmar_restauracion_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cancelar_restauracion_button").performClick()
        composeTestRule.waitForIdle()

        // Volver usando el botón atrás del sistema Android
        composeTestRule.runOnUiThread {
            backDispatcher?.onBackPressed()
        }
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("nav_trabajadores").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("agregar_trabajador_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("+ AGREGAR SEGUNDO APELLIDO").assertIsDisplayed()
        composeTestRule.onNodeWithTag("input_nombre").performTextInput("Karol")
        composeTestRule.onNodeWithTag("input_primer_apellido").performTextInput("Huamash")
        composeTestRule.onNodeWithTag("input_habitacion").performTextInput("777")
        composeTestRule.onNodeWithTag("guardar_trabajador_button").performClick()
        composeTestRule.waitForIdle()

        // Marcar SÍ BAJÓ a José en LISTADO y verificar sincronización y COPIAR LISTADO en EMPRESAS
        composeTestRule.onNodeWithTag("nav_listado").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("listado_lazy_column").performScrollToNode(hasTestTag("toggle_estado_$joseId"))
        composeTestRule.onNodeWithTag("toggle_estado_$joseId").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_empresas").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("RESUMEN TURNO A").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 trabajadores").assertIsDisplayed()
        composeTestRule.onNodeWithTag("limpiar_registro_empresas_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("empresa_card_KOMATSU").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("empresa_detalle_table").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Total: 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("SÍ BAJÓ: 1").assertIsDisplayed()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        composeTestRule.onNodeWithTag("empresa_filtro_estado_SI_BAJO").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("copiar_listado_empresa_button").performClick()
        composeTestRule.waitForIdle()
        val copiedSiBajo = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        assertEquals(listOf("PÉREZ TAPIA JOSÉ 22 - C"), copiedSiBajo.lines())
        assertFalse(copiedSiBajo.contains("HUAMASH"))

        // Verificar filtro NO BAJÓ en UI (incluye trabajador sin pabellón y trabajador con pabellón)
        composeTestRule.onNodeWithTag("empresa_filtro_estado_NO_BAJO").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("copiar_listado_empresa_button").performClick()
        composeTestRule.waitForIdle()
        val copiedNoBajo = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        assertEquals(
            listOf(
                "GÓMEZ PAZ RAÚL 30 - A",
                "HUAMASH KAROL 777"
            ),
            copiedNoBajo.lines()
        )

        // Probar LIMPIAR REGISTRO en Empresas
        composeTestRule.onNodeWithTag("volver_empresas_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("limpiar_registro_empresas_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("limpiar_registro_empresas_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("confirmar_limpiar_empresas_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("confirmar_limpiar_empresas_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("SÍ BAJÓ: 0").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("3 trabajadores").assertIsDisplayed()

        // Probar selección múltiple por pulsación larga en Trabajadores
        composeTestRule.onNodeWithTag("nav_trabajadores").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("admin_lazy_column").performScrollToNode(hasTestTag("admin_worker_card_$joseId"))
        composeTestRule.onNodeWithTag("admin_worker_card_$joseId").performTouchInput { longClick() }
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("barra_seleccion_multiple").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("eliminar_seleccionados_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("confirmar_eliminar_seleccionados_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("confirmar_eliminar_seleccionados_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("admin_worker_card_$joseId").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `10 - COPIAR LISTADO formato compacto numericos alfanumericos pabellones sin segundo apellido sin pabellon o habitacion`() {
        val listaEjemplo = listOf(
            TrabajadorConEstado("1", Turno.A, EmpresaOficial.KOMATSU, "Victor", "Pino", "", "1", "4 sur", true, false, "TA-1"),
            TrabajadorConEstado("2", Turno.A, EmpresaOficial.KOMATSU, "Pablo", "Aguilera", "", "10b", "23 sur", false, false, "TA-2"),
            TrabajadorConEstado("3", Turno.A, EmpresaOficial.KOMATSU, "Luis", "Alvarez", "", "23", "4 SUR", true, false, "TA-3"),
            TrabajadorConEstado("4", Turno.A, EmpresaOficial.KOMATSU, "Alfonso", "Araya", "", "16b", "22 sur", false, false, "TA-4"),
            TrabajadorConEstado("5", Turno.A, EmpresaOficial.KOMATSU, "Marcos", "Diaz", "Rojas", "30", "4 sur", true, false, "TA-5"),
            TrabajadorConEstado("6", Turno.A, EmpresaOficial.KOMATSU, "Carlos", "Soto", "", "23c", "", false, false, "TA-6"),
            TrabajadorConEstado("7", Turno.A, EmpresaOficial.KOMATSU, "Elena", "Vera", "", "", "22 sur", false, false, "TA-7")
        )

        val compacto = TextoNormalizer.generarListadoCompactoParaCopiar(listaEjemplo)
        assertEquals(
            listOf(
                "PINO VICTOR 1 - 4 SUR",
                "AGUILERA PABLO 10B - 23 SUR",
                "ALVAREZ LUIS 23 - 4 SUR",
                "ARAYA ALFONSO 16B - 22 SUR",
                "DIAZ ROJAS MARCOS 30 - 4 SUR",
                "SOTO CARLOS 23C",
                "VERA ELENA  - 22 SUR"
            ).joinToString("\n"),
            compacto
        )

        // Verificar que la función tabular para Excel sigue intacta y compatible
        val tsv = TextoNormalizer.generarListadoTsvParaExcel(listaEjemplo.take(2))
        assertEquals(
            listOf(
                "APELLIDOS\tNOMBRE\tHABITACIÓN\tPABELLÓN",
                "PINO\tVICTOR\t1\t4 SUR",
                "AGUILERA\tPABLO\t10B\t23 SUR"
            ).joinToString("\n"),
            tsv
        )
    }

    @Test
    fun `11 - busqueda inteligente sin orden, parciales, acentos, 25C, 18 SUR, combinada, filtros y rendimiento 657 trabajadores`() = runBlocking {
        val w1 = TrabajadorConEstado("w1", Turno.B, EmpresaOficial.KOMATSU, "Miguel", "Abarca", "", "25C", "18 SUR", false, false, "TB-1")
        val w2 = TrabajadorConEstado("w2", Turno.B, EmpresaOficial.CUMMINS, "Miguel Ángel", "Pérez", "Soto", "10B", "22 SUR", true, false, "TB-2")
        val w3 = TrabajadorConEstado("w3", Turno.B, EmpresaOficial.KOMATSU, "Carlos", "Abarca", "Rojas", "18", "4 SUR", false, false, "TB-3")
        val w4 = TrabajadorConEstado("w4", Turno.B, EmpresaOficial.FINNING, "Luis", "Alvarez", "", "25", "18 SUR", false, false, "TB-4")
        val w5 = TrabajadorConEstado("w5", Turno.B, EmpresaOficial.KOMATSU, "Ana", "Gómez", "", "25C", "4 SUR", false, false, "TB-5")
        val base = listOf(w1, w2, w3, w4, w5)

        // 1. Buscar "Abarca Miguel"
        val r1 = BuscadorTrabajadores.filtrarYOrdenar(base, "Abarca Miguel")
        assertEquals(listOf("w1"), r1.map { it.id })

        // 2. Buscar "Miguel Abarca" (orden invertido + minúsculas + espacios extra)
        val r2 = BuscadorTrabajadores.filtrarYOrdenar(base, "  miguel   abarca  ")
        assertEquals(listOf("w1"), r2.map { it.id })

        // 3. Buscar solamente "Miguel" (incluye múltiples coincidencias y sin importar acentos en "Ángel")
        val r3 = BuscadorTrabajadores.filtrarYOrdenar(base, "Miguel")
        assertEquals(setOf("w1", "w2"), r3.map { it.id }.toSet())

        // 4. Buscar solamente "Abarca"
        val r4 = BuscadorTrabajadores.filtrarYOrdenar(base, "Abarca")
        assertEquals(setOf("w1", "w3"), r4.map { it.id }.toSet())

        // 5. Buscar una parte de un apellido ("Abar" y "barca")
        assertEquals(setOf("w1", "w3"), BuscadorTrabajadores.filtrarYOrdenar(base, "Abar").map { it.id }.toSet())
        assertEquals(setOf("w1", "w3"), BuscadorTrabajadores.filtrarYOrdenar(base, "barca").map { it.id }.toSet())

        // 6. Buscar habitación alfanumérica "25C" (no confunde con "25")
        val r6 = BuscadorTrabajadores.filtrarYOrdenar(base, "25C")
        assertEquals(setOf("w1", "w5"), r6.map { it.id }.toSet())
        assertFalse(r6.any { it.id == "w4" })

        // 7. Buscar pabellón "18 SUR" (NO confunde habitación "18" + pabellón "4 SUR" de w3)
        val r7 = BuscadorTrabajadores.filtrarYOrdenar(base, "18 SUR")
        assertEquals(setOf("w1", "w4"), r7.map { it.id }.toSet())
        assertFalse(r7.any { it.id == "w3" })

        // 8. Buscar habitación "25C" junto con pabellón "18 SUR" (encuentra únicamente a w1)
        val r8 = BuscadorTrabajadores.filtrarYOrdenar(base, "25C 18 SUR")
        assertEquals(listOf("w1"), r8.map { it.id })

        // 9. Filtros por turno y empresa en MainViewModel con 657 trabajadores reales en Turno B + Turno A
        repository.ensureInitialized()
        val masivosB = generarTrabajadoresRealesMasivos(Turno.B, 655)
        db.trabajadorDao().insertAll(masivosB)
        repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Miguel", "Abarca", "", "25C", "18 SUR")
        repository.agregarTrabajador(Turno.B, EmpresaOficial.CUMMINS, "Carlos", "Abarca", "Rojas", "18", "4 SUR")
        repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Miguel", "Abarca", "", "25C", "18 SUR")

        assertEquals(657, repository.observeConteoTurno(Turno.B).first())
        assertEquals(1, repository.observeConteoTurno(Turno.A).first())

        val vm = MainViewModel(repository)
        val collectJob = launch(Dispatchers.Default) { vm.uiState.collect {} }
        try {
            vm.seleccionarTurno(Turno.B)
            waitForState { vm.uiState.value.inicializado && vm.uiState.value.turnoActivo == Turno.B && vm.uiState.value.trabajadoresTurno.size == 657 }

            // Rendimiento sobre los 657 trabajadores pre-indexados
            val inicioNs = System.nanoTime()
            val encontrados657 = BuscadorTrabajadores.filtrarYOrdenar(vm.uiState.value.trabajadoresTurno, "Miguel Abarca")
            val duracionMs = (System.nanoTime() - inicioNs) / 1_000_000.0
            assertEquals(1, encontrados657.size)
            assertTrue("La búsqueda sobre 657 trabajadores debe ser < 100ms (fue ${duracionMs}ms)", duracionMs < 100.0)

            // Búsqueda reactiva en MainViewModel respetando turno y filtro de empresa
            vm.actualizarBusqueda("Abarca")
            waitForState { vm.uiState.value.trabajadoresFiltrados.size == 2 }
            vm.seleccionarEmpresaFiltro(EmpresaOficial.CUMMINS)
            waitForState { vm.uiState.value.trabajadoresFiltrados.size == 1 }
            assertEquals(EmpresaOficial.CUMMINS, vm.uiState.value.trabajadoresFiltrados.first().empresa)
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `12 - pruebas A B C D navegacion Ajustes temporal sin persistencia de estado y transiciones inmediatas`() {
        runBlocking {
            repository.ensureInitialized()
            db.trabajadorDao().insertAll(generarTrabajadoresRealesMasivos(Turno.B, 657))
            repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Miguel", "Abarca", "", "25C", "18 SUR")
        }

        val mainVm = MainViewModel(repository)
        val importVm = ImportacionViewModel(repository)
        val backupVm = BackupViewModel(repository)
        val descargaVm = DescargaViewModel(repository)
        var backDispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LavanderiaControlTheme {
                LavanderiaApp(
                    mainViewModel = mainVm,
                    importacionViewModel = importVm,
                    backupViewModel = backupVm,
                    descargaViewModel = descargaVm
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
        }

        // PRUEBA A: LISTADO -> AJUSTES -> TRABAJADORES -> LISTADO (debe aparecer LISTADO y NO AJUSTES)
        composeTestRule.onNodeWithTag("abrir_ajustes_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("nav_trabajadores").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("admin_lazy_column").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("nav_listado").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())

        // PRUEBA B: LISTADO -> AJUSTES -> EMPRESAS -> LISTADO (AJUSTES no debe reaparecer)
        composeTestRule.onNodeWithTag("abrir_ajustes_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("nav_empresas").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("empresas_lazy_column").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("nav_listado").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())

        // Verificar también apertura desde TRABAJADORES -> AJUSTES -> IMPORTAR -> EMPRESAS -> TRABAJADORES
        composeTestRule.onNodeWithTag("nav_trabajadores").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("admin_lazy_column").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("abrir_ajustes_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("ajustes_importar_excel_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("seleccionar_archivo_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("nav_empresas").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("empresas_lazy_column").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithTag("seleccionar_archivo_button").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("nav_trabajadores").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("admin_lazy_column").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())

        // PRUEBA C: Abrir AJUSTES desde TRABAJADORES y pulsar Atrás de Android -> vuelve a TRABAJADORES
        composeTestRule.onNodeWithTag("abrir_ajustes_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.runOnUiThread { backDispatcher?.onBackPressed() }
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("admin_lazy_column").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())

        // PRUEBA D: Cambios repetidos e inmediatos entre LISTADO, TRABAJADORES y EMPRESAS (< 40ms, sin disolución de 700ms)
        composeTestRule.mainClock.autoAdvance = false
        try {
            val secuencia = listOf(
                Triple("nav_listado", "buscar_listado_input", "admin_lazy_column"),
                Triple("nav_trabajadores", "admin_lazy_column", "buscar_listado_input"),
                Triple("nav_empresas", "empresas_lazy_column", "admin_lazy_column"),
                Triple("nav_listado", "buscar_listado_input", "empresas_lazy_column"),
                Triple("nav_empresas", "empresas_lazy_column", "buscar_listado_input"),
                Triple("nav_trabajadores", "admin_lazy_column", "empresas_lazy_column"),
                Triple("nav_listado", "buscar_listado_input", "admin_lazy_column")
            )
            for ((navTag, expectedScreenTag, previousScreenTag) in secuencia) {
                composeTestRule.onNodeWithTag(navTag).performClick()
                composeTestRule.mainClock.advanceTimeBy(50L)
                composeTestRule.waitForIdle()
                composeTestRule.mainClock.advanceTimeByFrame()
                composeTestRule.waitForIdle()
                assertTrue(
                    "La transición hacia $expectedScreenTag debe completarse de inmediato (~66ms << 700ms)",
                    composeTestRule.onAllNodesWithTag(expectedScreenTag).fetchSemanticsNodes().isNotEmpty()
                )
                assertTrue(
                    "La pantalla anterior $previousScreenTag no debe quedar disolviéndose (~66ms << 700ms)",
                    composeTestRule.onAllNodesWithTag(previousScreenTag).fetchSemanticsNodes().isEmpty()
                )
                assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())
            }
        } finally {
            composeTestRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `13 - prueba E persistencia de 657 trabajadores reales y navegacion limpia tras cerrar y reabrir base de datos`() {
        val dbFile = File(context.cacheDir, "prueba_e_reopen_${System.nanoTime()}.db")
        val db1 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val repo1 = LavanderiaRepository(db1)
            runBlocking {
                repo1.ensureInitialized()
                db1.trabajadorDao().insertAll(generarTrabajadoresRealesMasivos(Turno.B, 657))
                repo1.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Miguel", "Abarca", "", "25C", "18 SUR")
            }
        } finally {
            db1.close()
        }

        val db2 = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val repo2 = LavanderiaRepository(db2)
            val mainVm2 = MainViewModel(repo2)
            val importVm2 = ImportacionViewModel(repo2)
            val backupVm2 = BackupViewModel(repo2)
            val descargaVm2 = DescargaViewModel(repo2)

            composeTestRule.setContent {
                LavanderiaControlTheme {
                    LavanderiaApp(
                        mainViewModel = mainVm2,
                        importacionViewModel = importVm2,
                        backupViewModel = backupVm2,
                        descargaViewModel = descargaVm2
                    )
                }
            }

            // Verificar que inicia directamente en LISTADO y conserva ambos turnos (1 Turno A, 657 Turno B)
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())
            assertEquals(657, mainVm2.uiState.value.conteoTurnoB)
            assertEquals(1, mainVm2.uiState.value.conteoTurnoA)

            // Navegar tras reapertura: LISTADO -> AJUSTES -> LISTADO directo -> TRABAJADORES -> EMPRESAS -> LISTADO
            composeTestRule.onNodeWithTag("abrir_ajustes_button").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("nav_listado").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())

            composeTestRule.onNodeWithTag("nav_trabajadores").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                composeTestRule.onAllNodesWithTag("admin_lazy_column").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("nav_empresas").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                composeTestRule.onAllNodesWithTag("empresas_lazy_column").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("nav_listado").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            db2.close()
            dbFile.delete()
        }
    }

    @Test
    fun `14 - migracion Room v1 a v2 conserva los 657 trabajadores de Turno B, Turno A y marcaciones previas`() {
        val dbFile = File(context.cacheDir, "mig_v1_v2_${System.nanoTime()}.db")
        if (dbFile.exists()) dbFile.delete()

        val configV1 = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `turnos` (`id` TEXT NOT NULL, `nombre` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `empresas` (`id` INTEGER NOT NULL, `codigo` TEXT NOT NULL, `nombre` TEXT NOT NULL, `colorHex` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `trabajadores` (`id` TEXT NOT NULL, `turnoId` TEXT NOT NULL, `empresaId` INTEGER NOT NULL, `codigoInterno` TEXT NOT NULL, `nombre` TEXT NOT NULL, `primerApellido` TEXT NOT NULL, `segundoApellido` TEXT NOT NULL, `habitacion` TEXT NOT NULL, `pabellon` TEXT NOT NULL, `esDatoPrueba` INTEGER NOT NULL, `creadoEn` INTEGER NOT NULL, `actualizadoEn` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`turnoId`) REFERENCES `turnos`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`empresaId`) REFERENCES `empresas`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_trabajadores_turnoId` ON `trabajadores` (`turnoId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_trabajadores_empresaId` ON `trabajadores` (`empresaId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_trabajadores_turnoId_primerApellido_nombre` ON `trabajadores` (`turnoId`, `primerApellido`, `nombre`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `sesiones` (`id` TEXT NOT NULL, `turnoId` TEXT NOT NULL, `fechaIso` TEXT NOT NULL, `estado` TEXT NOT NULL, `creadaEn` INTEGER NOT NULL, `finalizadaEn` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`turnoId`) REFERENCES `turnos`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_sesiones_turnoId_estado` ON `sesiones` (`turnoId`, `estado`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `marcaciones` (`id` TEXT NOT NULL, `sesionId` TEXT NOT NULL, `turnoId` TEXT NOT NULL, `trabajadorId` TEXT, `nombreCompletoSnapshot` TEXT NOT NULL, `primerApellidoSnapshot` TEXT NOT NULL, `segundoApellidoSnapshot` TEXT NOT NULL, `habitacionSnapshot` TEXT NOT NULL, `pabellonSnapshot` TEXT NOT NULL, `empresaIdSnapshot` INTEGER NOT NULL, `empresaNombreSnapshot` TEXT NOT NULL, `siBajo` INTEGER NOT NULL, `esDatoPrueba` INTEGER NOT NULL, `origen` TEXT NOT NULL, `marcadoEn` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sesionId`) REFERENCES `sesiones`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`trabajadorId`) REFERENCES `trabajadores`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_marcaciones_sesionId` ON `marcaciones` (`sesionId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_marcaciones_turnoId` ON `marcaciones` (`turnoId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_marcaciones_trabajadorId` ON `marcaciones` (`trabajadorId`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_marcaciones_sesionId_trabajadorId` ON `marcaciones` (`sesionId`, `trabajadorId`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `registros_recepcion` (`id` TEXT NOT NULL, `sesionId` TEXT NOT NULL, `turnoId` TEXT NOT NULL, `trabajadorId` TEXT, `textoOriginal` TEXT NOT NULL, `nombreConfirmadoSnapshot` TEXT NOT NULL, `empresaIdSnapshot` INTEGER, `empresaNombreSnapshot` TEXT, `confirmado` INTEGER NOT NULL, `origen` TEXT NOT NULL, `creadoEn` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sesionId`) REFERENCES `sesiones`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`trabajadorId`) REFERENCES `trabajadores`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_registros_recepcion_sesionId` ON `registros_recepcion` (`sesionId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_registros_recepcion_turnoId` ON `registros_recepcion` (`turnoId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_registros_recepcion_trabajadorId` ON `registros_recepcion` (`trabajadorId`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `comparaciones` (`id` TEXT NOT NULL, `sesionId` TEXT NOT NULL, `turnoId` TEXT NOT NULL, `trabajadorId` TEXT, `empresaIdSnapshot` INTEGER, `empresaNombreSnapshot` TEXT NOT NULL, `nombreCompletoSnapshot` TEXT NOT NULL, `habitacionSnapshot` TEXT NOT NULL, `pabellonSnapshot` TEXT NOT NULL, `categoria` TEXT NOT NULL, `calculadoEn` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sesionId`) REFERENCES `sesiones`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`trabajadorId`) REFERENCES `trabajadores`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_comparaciones_sesionId` ON `comparaciones` (`sesionId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_comparaciones_turnoId` ON `comparaciones` (`turnoId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_comparaciones_trabajadorId` ON `comparaciones` (`trabajadorId`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `configuracion` (`clave` TEXT NOT NULL, `valor` TEXT NOT NULL, PRIMARY KEY(`clave`))")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val rawHelper = FrameworkSQLiteOpenHelperFactory().create(configV1)
        try {
            val wdb = rawHelper.writableDatabase
            wdb.execSQL("INSERT INTO turnos VALUES ('A','Turno A'),('B','Turno B')")
            EmpresaOficial.todas.forEach { emp ->
                wdb.execSQL("INSERT INTO empresas VALUES (${emp.id}, '${emp.codigo}', '${emp.nombre}', ${emp.colorHex})")
            }
            wdb.execSQL("INSERT INTO configuracion VALUES ('turno_activo', 'B')")
            wdb.execSQL("INSERT INTO sesiones VALUES ('ses-b', 'B', '2026-11-21', 'ABIERTA', 1710000000000, NULL)")
            wdb.execSQL("INSERT INTO sesiones VALUES ('ses-a', 'A', '2026-11-21', 'ABIERTA', 1710000000000, NULL)")

            val masivosB = generarTrabajadoresRealesMasivos(Turno.B, 657)
            for (w in masivosB) {
                wdb.execSQL(
                    "INSERT INTO trabajadores VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                    arrayOf<Any>(w.id, w.turnoId, w.empresaId, w.codigoInterno, w.nombre, w.primerApellido, w.segundoApellido, w.habitacion, w.pabellon, w.creadoEn, w.actualizadoEn)
                )
            }
            wdb.execSQL(
                "INSERT INTO trabajadores VALUES ('ta-real-1', 'A', 1, 'TA-0001', 'Miguel', 'Abarca', '', '25C', '18 SUR', 0, 1710000000000, 1710000000000)"
            )
            wdb.execSQL(
                "INSERT INTO marcaciones VALUES ('m-b-1', 'ses-b', 'B', '${masivosB.first().id}', 'APELLIDO1 MATERNO1 TRABAJADOR1', 'Apellido1', 'Materno1', '101', 'A', 1, 'Komatsu', 1, 0, 'VOZ', 1710000001000)"
            )
        } finally {
            rawHelper.close()
        }

        val migratedDb = Room.databaseBuilder(context, LavanderiaDatabase::class.java, dbFile.absolutePath)
            .addMigrations(LavanderiaDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = LavanderiaRepository(migratedDb)
            runBlocking {
                repo.ensureInitialized()
                assertEquals(657, repo.observeConteoTurno(Turno.B).first())
                assertEquals(1, repo.observeConteoTurno(Turno.A).first())
                val trabajadoresB = repo.observeTrabajadoresConEstado(Turno.B).first()
                assertEquals(657, trabajadoresB.size)
                assertTrue(trabajadoresB.first { it.id == "b_real_0001" }.siBajo)
                assertEquals(0, migratedDb.correccionOcrDao().getAllOnce().size)
            }
        } finally {
            migratedDb.close()
            dbFile.delete()
        }
    }

    @Test
    fun `15 - compatibilidad de copias de seguridad v1 y v2 incluyendo correcciones_ocr`() = runBlocking {
        repository.ensureInitialized()
        val w1 = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Victor", "Pino", "", "1", "4 SUR")
        val w2 = repository.agregarTrabajador(Turno.A, EmpresaOficial.CUMMINS, "Pablo", "Aguilera", "", "10B", "23 SUR")

        // Confirmar un pendiente OCR para generar una entrada real en correcciones_ocr
        val listaB = repository.observeTrabajadoresConEstado(Turno.B).first()
        val pino = listaB.first { it.id == w1.id }
        val analisisDudoso = OcrNombreMatcher.analizarLineas(
            lineasCrudas = listOf("PIN0 V1CTOR"),
            trabajadoresEmpresaTurno = listaB,
            correccionesEmpresaTurno = emptyList()
        )
        repository.aplicarResultadoFotografiaOcr(Turno.B, EmpresaOficial.KOMATSU, analisisDudoso)
        val recepcionPendiente = repository.observeRecepcionesSesionAbierta(Turno.B).first().first { !it.confirmado }
        repository.confirmarPendienteDescarga(recepcionPendiente.id, pino, guardarEnMemoria = true)

        // Exportar v2 y verificar que incluye correcciones_ocr
        val (snapV2, jsonV2) = repository.exportarBackupJson()
        assertEquals(2, snapV2.backupFormatVersion)
        assertEquals(1, snapV2.correccionesOcr.size)
        assertEquals(1, snapV2.resumen.totalCorreccionesOcr)

        // Construir un respaldo estilo v1 (sin correcciones_ocr ni totalCorreccionesOcr) y validar compatibilidad
        val rootV1 = JSONObject(jsonV2)
        rootV1.put("backupFormatVersion", 1)
        rootV1.getJSONObject("resumen").remove("totalCorreccionesOcr")
        rootV1.getJSONObject("tablas").remove("correcciones_ocr")

        val validV1 = repository.validarBackupJson(rootV1.toString(2))
        assertTrue("Un backup v1 válido debe aceptarse en v2", validV1 is ResultadoValidacionBackup.Valido)
        val snapV1 = (validV1 as ResultadoValidacionBackup.Valido).validado.snapshot

        val safetyDir = File(context.cacheDir, "safety_v1_v2_${System.nanoTime()}").apply { mkdirs() }
        try {
            val resRestoreV1 = repository.restaurarBackupValidado(snapV1) { content ->
                File(safetyDir, "preventivo.json").apply { writeText(content) }.absolutePath
            }
            assertTrue(resRestoreV1 is ResultadoRestauracionBackup.Exito)
            assertEquals(1, repository.observeConteoTurno(Turno.B).first())
            assertEquals(1, repository.observeConteoTurno(Turno.A).first())
            assertEquals(0, db.correccionOcrDao().getAllOnce().size)

            // Restaurar ahora el respaldo v2 completo y verificar que regresa correcciones_ocr + trabajadores
            val validV2 = repository.validarBackupJson(jsonV2) as ResultadoValidacionBackup.Valido
            val resRestoreV2 = repository.restaurarBackupValidado(validV2.validado.snapshot) { content ->
                File(safetyDir, "preventivo2.json").apply { writeText(content) }.absolutePath
            }
            assertTrue(resRestoreV2 is ResultadoRestauracionBackup.Exito)
            val corrRestauradas = db.correccionOcrDao().getAllOnce()
            assertEquals(1, corrRestauradas.size)
            assertEquals(w1.id, corrRestauradas.first().trabajadorId)
            assertEquals(w2.id, repository.observeTrabajadoresConEstado(Turno.A).first().first().id)
        } finally {
            safetyDir.deleteRecursively()
        }
    }

    @Test
    fun `16 - OcrNombreMatcher memoria aprendida por turno y empresa, homonimos a pendientes y CASCADE al eliminar trabajador`() = runBlocking {
        repository.ensureInitialized()
        val pinoB = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Victor", "Pino", "", "1", "4 SUR")
        // Mismo nombre en distinta empresa y distinto turno: no debe interferir ni crear falso positivo
        repository.agregarTrabajador(Turno.B, EmpresaOficial.CUMMINS, "Victor", "Pino", "", "2", "4 SUR")
        repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Victor", "Pino", "", "3", "4 SUR")
        // Homónimos dentro de Komatsu Turno B
        repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Juan", "Soto", "", "10", "4 SUR")
        repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Juan", "Soto", "", "11", "4 SUR")
        // Otro trabajador con error típico de lectura OCR ("MIGU3L ABARCA")
        val miguelB = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Miguel", "Abarca", "", "25C", "18 SUR")

        val komatsuB = repository.observeTrabajadoresConEstado(Turno.B).first().filter { it.empresa == EmpresaOficial.KOMATSU }
        val analisis1 = OcrNombreMatcher.analizarLineas(
            lineasCrudas = listOf(
                "1. PINO VICTOR 1 - 4 SUR",
                "JUAN SOTO",
                "MIGU3L ABARCA 25C",
                "CONTROL DE LAVANDERIA"
            ),
            trabajadoresEmpresaTurno = komatsuB,
            correccionesEmpresaTurno = emptyList()
        )

        // Exacto único -> autoConfirmado; homónimo + fuzzy -> pendientes; encabezado -> ruido descartado
        assertEquals(listOf(pinoB.id), analisis1.autoConfirmados.map { it.trabajador.id })
        assertEquals(2, analisis1.pendientes.size)
        assertTrue(analisis1.pendientes.any { it.textoOcrNormalizado == "juan soto" && it.candidatos.size == 2 })
        assertTrue(analisis1.pendientes.any { it.textoOcrNormalizado == "migu3l abarca" && it.candidatos.first().trabajador.id == miguelB.id })
        assertEquals(1, analisis1.lineasDescartadasRuido)

        // Aplicar foto 1 y confirmar manualmente "MIGU3L ABARCA" para entrenarlo en memoria
        repository.aplicarResultadoFotografiaOcr(Turno.B, EmpresaOficial.KOMATSU, analisis1)
        val pendMiguel = repository.observeRecepcionesSesionAbierta(Turno.B).first()
            .first { !it.confirmado && it.textoOriginal.contains("MIGU3L") }
        val miguelState = komatsuB.first { it.id == miguelB.id }
        repository.confirmarPendienteDescarga(pendMiguel.id, miguelState, guardarEnMemoria = true)

        // En una segunda lectura de Komatsu B, "MIGU3L ABARCA" se confirma automáticamente vía memoria
        val corrKomatsuB = repository.getCorreccionesOcrOnce(Turno.B, EmpresaOficial.KOMATSU)
        val analisis2 = OcrNombreMatcher.analizarLineas(
            lineasCrudas = listOf("MIGU3L ABARCA"),
            trabajadoresEmpresaTurno = komatsuB,
            correccionesEmpresaTurno = corrKomatsuB
        )
        assertEquals(1, analisis2.autoConfirmados.size)
        assertTrue(analisis2.autoConfirmados.first().viaMemoria)
        assertEquals(miguelB.id, analisis2.autoConfirmados.first().trabajador.id)

        // En Komatsu Turno A, NO existe esa memoria (estrictamente aislada por turno y empresa)
        assertEquals(0, repository.getCorreccionesOcrOnce(Turno.A, EmpresaOficial.KOMATSU).size)

        // Al eliminar el trabajador del maestro, ON DELETE CASCADE limpia su corrección aprendida
        repository.eliminarTrabajador(miguelB.id)
        assertEquals(0, repository.getCorreccionesOcrOnce(Turno.B, EmpresaOficial.KOMATSU).size)
    }

    @Test
    fun `17 - lote multifoto, bloqueo con pendientes o foto ilegible, conciliacion con reverso desde MANUAL-VOZ preservando origen y LIMPIAR REGISTRO`() = runBlocking {
        repository.ensureInitialized()
        val w1Voz = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Victor", "Pino", "", "1", "4 SUR")
        val w2Fuzzy = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Pablo", "Aguilera", "", "10B", "23 SUR")
        val w3ManualAusente = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Luis", "Alvarez", "", "23", "4 SUR")
        val w4OtraEmpresa = repository.agregarTrabajador(Turno.B, EmpresaOficial.CUMMINS, "Alfonso", "Araya", "", "16B", "22 SUR")
        val w5OtroTurno = repository.agregarTrabajador(Turno.A, EmpresaOficial.KOMATSU, "Carlos", "Soto", "", "5", "4 SUR")

        // Marcar w1 con VOZ, w3 con MANUAL, w4 con MANUAL y w5 en Turno A con MANUAL
        val sesB = db.sesionDao().getOpenSessionByTurno(Turno.B.id)!!
        db.marcacionDao().upsert(
            com.example.data.local.entities.MarcacionEntity(
                id = "m-voz-w1", sesionId = sesB.id, turnoId = Turno.B.id, trabajadorId = w1Voz.id,
                nombreCompletoSnapshot = "PINO VICTOR", primerApellidoSnapshot = "Pino", segundoApellidoSnapshot = "",
                habitacionSnapshot = "1", pabellonSnapshot = "4 SUR", empresaIdSnapshot = EmpresaOficial.KOMATSU.id,
                empresaNombreSnapshot = EmpresaOficial.KOMATSU.nombre, siBajo = true, esDatoPrueba = false,
                origen = "VOZ", marcadoEn = 1000L
            )
        )
        val stateB = repository.observeTrabajadoresConEstado(Turno.B).first()
        repository.alternarMarcacionSiBajo(stateB.first { it.id == w3ManualAusente.id })
        repository.alternarMarcacionSiBajo(stateB.first { it.id == w4OtraEmpresa.id })
        val stateA = repository.observeTrabajadoresConEstado(Turno.A).first()
        repository.alternarMarcacionSiBajo(stateA.first { it.id == w5OtroTurno.id })

        val descargaVm = DescargaViewModel(repository)
        val collectJob = launch(Dispatchers.Default) { descargaVm.uiState.collect {} }
        try {
            repository.setTurnoActivo(Turno.B)
            descargaVm.seleccionarEmpresa(EmpresaOficial.KOMATSU)
            waitForState {
                descargaVm.uiState.value.turnoActivo == Turno.B &&
                    descargaVm.uiState.value.empresaSeleccionada == EmpresaOficial.KOMATSU &&
                    descargaVm.uiState.value.trabajadoresEmpresa.size == 3
            }

            // Foto 1: detecta a w1 (ya marcado por VOZ) y deja a w2 en Pendientes; w3 sigue SÍ BAJÓ (aún no se concilia)
            descargaVm.procesarLineasOcrManualesOPrueba(
                turno = Turno.B,
                empresa = EmpresaOficial.KOMATSU,
                etiqueta = "Foto 1",
                lineas = listOf("PINO VICTOR", "AGU1LERA PABLO")
            )
            waitForState { descargaVm.uiState.value.pendientes.size == 1 }
            assertFalse("No debe permitir finalizar con pendientes sin resolver", descargaVm.uiState.value.puedeFinalizarConciliacion)
            val marcaW1 = db.marcacionDao().getBySesionAndTrabajador(sesB.id, w1Voz.id)!!
            assertEquals("VOZ+FOTO", marcaW1.origen)
            assertTrue(marcaW1.siBajo)
            assertTrue("No debe borrar marcación MANUAL previa mientras se agregan fotos", db.marcacionDao().getBySesionAndTrabajador(sesB.id, w3ManualAusente.id)!!.siBajo)

            // Foto 2 ilegible -> también bloquea conciliación hasta descartar/reemplazar
            descargaVm.procesarLineasOcrManualesOPrueba(
                turno = Turno.B,
                empresa = EmpresaOficial.KOMATSU,
                etiqueta = "Foto 2 borrosa",
                lineas = emptyList()
            )
            waitForState { descargaVm.uiState.value.tieneFotoIlegible }
            assertFalse(descargaVm.uiState.value.puedeFinalizarConciliacion)

            // Descartar aviso de foto borrosa y confirmar a w2 pendiente -> desbloquea conciliación
            val idFotoError = descargaVm.uiState.value.fotosLote.first { it.mensajeDetalle.contains("legible", ignoreCase = true) }.id
            descargaVm.descartarFotoLote(idFotoError)
            val pendW2 = descargaVm.uiState.value.pendientes.first()
            descargaVm.confirmarPendiente(pendW2.registroId, descargaVm.uiState.value.trabajadoresEmpresa.first { it.id == w2Fuzzy.id })
            waitForState { descargaVm.uiState.value.puedeFinalizarConciliacion }

            // Previsualizar conciliación -> detecta que w3 (MANUAL) pasará a NO BAJÓ
            descargaVm.solicitarPrevisualizacionConciliacion()
            waitForState { descargaVm.uiState.value.previsualizacionConciliacion != null }
            val preview = descargaVm.uiState.value.previsualizacionConciliacion!!
            assertEquals(2, preview.confirmadosEnFotosCount)
            assertEquals(listOf(w3ManualAusente.id), preview.pasaranANoBajoDesdeManualOVoz.map { it.id })

            // Confirmar conciliación final -> w3 cambia a siBajo=false conservando origen="MANUAL"; Cummins y Turno A intactos
            descargaVm.confirmarConciliacionFinal()
            waitForState {
                descargaVm.uiState.value.previsualizacionConciliacion == null &&
                    descargaVm.uiState.value.siBajoEmpresaCount == 2 &&
                    descargaVm.uiState.value.noBajoEmpresaCount == 1
            }

            val marcaW3Final = db.marcacionDao().getBySesionAndTrabajador(sesB.id, w3ManualAusente.id)!!
            assertFalse("El ausente del lote legible completo debe quedar NO BAJÓ", marcaW3Final.siBajo)
            assertEquals("Debe conservar trazabilidad del origen previo", "MANUAL", marcaW3Final.origen)
            assertTrue("Otra empresa debe permanecer intacta", db.marcacionDao().getBySesionAndTrabajador(sesB.id, w4OtraEmpresa.id)!!.siBajo)

            val sesA = db.sesionDao().getOpenSessionByTurno(Turno.A.id)!!
            assertTrue("Turno A debe permanecer intacto", db.marcacionDao().getBySesionAndTrabajador(sesA.id, w5OtroTurno.id)!!.siBajo)

            // Reflejado exactamente en Listado / formato COPIAR LISTADO
            val komatsuActualizados = repository.observeTrabajadoresConEstado(Turno.B).first().filter { it.empresa == EmpresaOficial.KOMATSU }
            val textoCopiadoSiBajo = TextoNormalizer.generarListadoCompactoParaCopiar(komatsuActualizados.filter { it.siBajo })
            assertEquals(setOf("PINO VICTOR 1 - 4 SUR", "AGUILERA PABLO 10B - 23 SUR"), textoCopiadoSiBajo.lines().toSet())

            // LIMPIAR REGISTRO en Turno B borra marcas y recepciones del turno B, conservando maestro, Turno A y correcciones_ocr
            descargaVm.limpiarRegistroOperativoTurno()
            waitForState { descargaVm.uiState.value.siBajoEmpresaCount == 0 && descargaVm.uiState.value.confirmadosEnFotosIds.isEmpty() }
            assertEquals(4, repository.observeConteoTurno(Turno.B).first())
            assertEquals(1, repository.observeConteoTurno(Turno.A).first())
            assertTrue("Turno A conserva sus marcas operativas", db.marcacionDao().getBySesionAndTrabajador(sesA.id, w5OtroTurno.id)!!.siBajo)
            assertEquals("La memoria aprendida OCR debe conservarse tras limpiar registro", 1, db.correccionOcrDao().getAllOnce().size)
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `18 - eliminacion estricta solo de archivos temporales internos y navegacion a 4ta pestana Descarga`() {
        runBlocking {
            repository.ensureInitialized()
            repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Victor", "Pino", "", "1", "4 SUR")
        }

        // Verificar aislamiento estricto de archivos temporales vs archivos externos de galería
        val (tempCamFile, tempUri) = OcrImageProcessor.crearArchivoTemporalCamara(context)
        tempCamFile.writeText("temporal_camara")
        assertTrue(tempCamFile.exists())
        assertTrue(tempUri.toString().contains("ocr_temp"))

        val carpetaExternaSimulada = File(context.cacheDir, "galeria_externa_test").apply { mkdirs() }
        val archivoExterno = File(carpetaExternaSimulada, "foto_usuario.jpg").apply { writeText("no_tocar") }

        try {
            OcrImageProcessor.eliminarArchivoTemporalSiEsInterno(context, archivoExterno)
            assertTrue("NUNCA debe eliminar archivos fuera de ocr_temp", archivoExterno.exists())

            OcrImageProcessor.eliminarArchivoTemporalSiEsInterno(context, tempCamFile)
            assertFalse("Debe eliminar el archivo temporal de cámara dentro de ocr_temp", tempCamFile.exists())

            val residual = File(OcrImageProcessor.obtenerDirectorioTemporal(context), "residual.jpg").apply { writeText("x") }
            OcrImageProcessor.limpiarTodosLosTemporales(context)
            assertFalse(residual.exists())
            assertTrue(archivoExterno.exists())
        } finally {
            carpetaExternaSimulada.deleteRecursively()
        }

        val mainVm = MainViewModel(repository)
        val importVm = ImportacionViewModel(repository)
        val backupVm = BackupViewModel(repository)
        val descargaVm = DescargaViewModel(repository)

        composeTestRule.setContent {
            LavanderiaControlTheme {
                LavanderiaApp(
                    mainViewModel = mainVm,
                    importacionViewModel = importVm,
                    backupViewModel = backupVm,
                    descargaViewModel = descargaVm
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
        }

        // Navegar a la 4ª pestaña Descarga
        composeTestRule.onNodeWithTag("nav_descarga").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("descarga_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("descarga_tomar_foto_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("descarga_elegir_fotos_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("descarga_finalizar_conciliar_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("descarga_limpiar_registro_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("descarga_resumen_identificados").assertIsDisplayed()
        composeTestRule.onNodeWithTag("descarga_memoria_badge").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTag("descarga_copiar_listado_button").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithTag("descarga_filtro_TODOS").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithTag("descarga_filtro_SI_BAJO").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithTag("descarga_filtro_NO_BAJO").fetchSemanticsNodes().isEmpty())

        // Abrir Ajustes desde Descarga y saltar a Listado: Ajustes debe cerrarse limpiamente y no volver a aparecer
        composeTestRule.onNodeWithTag("abrir_ajustes_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("nav_listado").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("buscar_listado_input").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("nav_descarga").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag("descarga_screen").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeTestRule.onAllNodesWithTag("ajustes_screen").fetchSemanticsNodes().isEmpty())
    }

    private fun generarTrabajadoresRealesMasivos(turno: Turno, cantidad: Int): List<TrabajadorEntity> {
        val empresas = EmpresaOficial.todas
        val pabellones = listOf("A", "B", "C", "D")
        val baseTime = 1_710_000_000_000L
        return (1..cantidad).map { idx ->
            val emp = empresas[(idx - 1) % empresas.size]
            val prefijo = if (turno == Turno.A) "TA" else "TB"
            val tieneSegundoApellido = idx % 3 != 0
            TrabajadorEntity(
                id = "${turno.id.lowercase()}_real_${idx.toString().padStart(4, '0')}",
                turnoId = turno.id,
                empresaId = emp.id,
                codigoInterno = "$prefijo-${idx.toString().padStart(4, '0')}",
                nombre = "Trabajador$idx",
                primerApellido = "Apellido$idx",
                segundoApellido = if (tieneSegundoApellido) "Materno$idx" else "",
                habitacion = (100 + idx).toString(),
                pabellon = pabellones[(idx - 1) % pabellones.size],
                esDatoPrueba = false,
                creadoEn = baseTime + idx,
                actualizadoEn = baseTime + idx
            )
        }
    }

    private fun waitForState(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timeout esperando condición después de ${timeoutMs}ms")
            }
            ShadowLooper.idleMainLooper()
            Thread.sleep(15)
        }
    }

    private fun crearXlsxPrueba(filas: List<List<String>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>""".trimIndent().toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""".trimIndent().toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Trabajadores" sheetId="1" r:id="rId1"/>
                  </sheets>
                </workbook>""".trimIndent().toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>""".trimIndent().toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()

            val sheetXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                filas.forEachIndexed { rIdx, celdas ->
                    append("""<row r="${rIdx + 1}">""")
                    celdas.forEachIndexed { cIdx, valor ->
                        val colLetter = ('A' + cIdx)
                        val escaped = valor
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                        append("""<c r="$colLetter${rIdx + 1}" t="inlineStr"><is><t>$escaped</t></is></c>""")
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            }
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheetXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
