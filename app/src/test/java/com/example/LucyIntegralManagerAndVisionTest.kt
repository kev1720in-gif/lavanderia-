package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.speech.tts.TextToSpeech
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LavanderiaDatabase
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.TrabajadorConEstado
import com.example.domain.model.Turno
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyIntent
import com.example.lucy.core.LucyIntentParserOffline
import com.example.lucy.core.LucyOperationOrigin
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.ui.LucyPhotoIntake
import com.example.lucy.ui.LucyViewModel
import com.example.lucy.vision.AndroidLucyVisionImageLoader
import com.example.lucy.vision.FirebaseLucyVisionPort
import com.example.lucy.vision.LucyVisionDocumentStatus
import com.example.lucy.vision.LucyVisionGatewayStatus
import com.example.lucy.vision.LucyVisionImagePayload
import com.example.lucy.vision.LucyVisionMatchStatus
import com.example.lucy.vision.LucyVisionModelGateway
import com.example.lucy.vision.LucyVisionRawGatewayResult
import com.example.lucy.vision.LucyVisionRequestContext
import com.example.lucy.voice.AndroidLucyVoicePort
import com.example.lucy.voice.LucySttController
import com.example.lucy.voice.LucySttGateway
import com.example.lucy.voice.LucySttGatewayCallbacks
import com.example.lucy.voice.LucySttSessionHandle
import com.example.lucy.voice.LucyTtsEngine
import com.example.lucy.voice.LucyTtsGateway
import com.example.lucy.voice.LucyTtsGatewayCallbacks
import com.example.lucy.voice.LucyTtsVoiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LucyIntegralManagerAndVisionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: LavanderiaDatabase
    private lateinit var repository: LavanderiaRepository
    private val scopesToClose = mutableListOf<CoroutineScope>()

    @Before
    fun setup() {
        runCatching {
            val cacheField = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            cacheField.isAccessible = true
            (cacheField.get(null) as? MutableMap<*, *>)?.clear()
        }
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LavanderiaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LavanderiaRepository(db)
        runBlocking {
            repository.ensureInitialized()
        }
    }

    @After
    fun teardown() {
        runBlocking {
            scopesToClose.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
        }
        scopesToClose.clear()
        db.close()
    }

    @Test
    fun `1 - flujo conversacional breve y marcacion en contexto sin auto-marcar al mencionar`() = runBlocking {
        val sebastian = agregar("Sebastián", "Morales", "Díaz", EmpresaOficial.KOMATSU, Turno.A, "101", "18 SUR")
        val env = crearEntorno()

        val r1 = env.coordinator.procesarEntrada("Sebastián Morales")
        assertEquals("Lo tengo.", r1.texto)
        assertNull(r1.confirmacionRequerida)
        val estadoTrasMencion = repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == sebastian.id }
        assertFalse("Mención pasiva no debe marcar en Room", estadoTrasMencion.siBajo)

        val r2 = env.coordinator.procesarEntrada("¿Es de Komatsu?")
        assertEquals("Sí.", r2.texto)

        val r3 = env.coordinator.procesarEntrada("¿Es de Finning?")
        assertEquals("No, es de Komatsu.", r3.texto)

        val r4 = env.coordinator.procesarEntrada("¿En qué habitación está?")
        assertTrue(r4.texto.contains("101"))
        assertTrue(r4.texto.contains("18 SUR"))

        val r5 = env.coordinator.procesarEntrada("¿Subió?")
        assertEquals("No.", r5.texto)

        val r6 = env.coordinator.procesarEntrada("Ahora márcalo, porque sí subió")
        assertEquals("Listo.", r6.texto)
        val estadoTrasMarcar = repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == sebastian.id }
        assertTrue("Orden contextual debe persistir siBajo=true en Room", estadoTrasMarcar.siBajo)

        val r7 = env.coordinator.procesarEntrada("¿Subió?")
        assertEquals("Sí.", r7.texto)
    }

    @Test
    fun `2 - dictado natural con variaciones foneticas STT y sin comas`() = runBlocking {
        val w1 = agregar("Sebastián", "Morales", "Díaz", EmpresaOficial.KOMATSU, Turno.A, "101", "18 SUR")
        val w2 = agregar("Bryan", "González", "Rojas", EmpresaOficial.KOMATSU, Turno.A, "102", "18 SUR")
        val w3 = agregar("Pedro", "Rojas", "Silva", EmpresaOficial.KOMATSU, Turno.A, "103", "18 SUR")
        val w4 = agregar("Pedro", "González", "Soto", EmpresaOficial.KOMATSU, Turno.A, "104", "18 SUR")

        val env = crearEntorno()

        // Variación STT Brian -> Bryan y conectores mezclados con "subió" y "también"
        val r1 = env.coordinator.procesarEntrada(
            "Sebastián Morales subió, Brian González subió y Pedro Rojas también",
            origen = LucyOperationOrigin.VOICE
        )
        assertTrue("Debe marcar los 3 trabajadores: ${r1.texto}", r1.texto.contains("Marqué 3 trabajadores"))

        // Dictado sin comas separado por "subió"
        val r2 = env.coordinator.procesarEntrada(
            "Sebastián Morales subió Pedro González subió",
            origen = LucyOperationOrigin.VOICE
        )
        assertTrue("Debe marcar a Pedro González sin duplicar a Sebastián: ${r2.texto}", r2.texto.contains("Marqué 1 trabajador"))

        val estados = repository.observeTrabajadoresConEstado(Turno.A).first().associateBy { it.id }
        listOf(w1, w2, w3, w4).forEach { w ->
            assertTrue("${w.nombreCompleto} debe quedar siBajo=true", estados[w.id]?.siBajo == true)
        }
    }

    @Test
    fun `3 - coincidencia de 1 token unico fonetico vs ambiguedad segura cuando hay 2 homonimos`() = runBlocking {
        val brayanUnico = agregar("Bryan", "Gutiérrez", "Tapia", EmpresaOficial.KOMATSU, Turno.A, "201", "19 NORTE")
        val env = crearEntorno()

        val rUnico = env.coordinator.procesarEntrada("Brian subió", origen = LucyOperationOrigin.VOICE)
        assertTrue("1 token fonético único debe marcarse: ${rUnico.texto}", rUnico.texto.contains("Marqué 1 trabajador"))
        assertTrue(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == brayanUnico.id }.siBajo)

        // Agregar un segundo Bryan y probar con otro nombre único vs duplicado en Turno B
        val otro1 = agregar("Bryan", "Castillo", "Paredes", EmpresaOficial.KOMATSU, Turno.B, "301", "20 SUR")
        val otro2 = agregar("Brayan", "Araya", "Vega", EmpresaOficial.KOMATSU, Turno.B, "302", "20 SUR")
        repository.setTurnoActivo(Turno.B)

        val rAmbiguo = env.coordinator.procesarEntrada("Brian subió", origen = LucyOperationOrigin.VOICE)
        assertTrue("Con 2 posibles Bryan/Brayan debe abrir duda sin marcar: ${rAmbiguo.texto}", rAmbiguo.texto.contains("Tengo 1 duda"))
        val estadosB = repository.observeTrabajadoresConEstado(Turno.B).first().associateBy { it.id }
        assertFalse(estadosB[otro1.id]?.siBajo == true)
        assertFalse(estadosB[otro2.id]?.siBajo == true)
    }

    @Test
    fun `4 - dictados largos de 30, 50 y 60 trabajadores en una sola orden`() = runBlocking {
        val apellidos = listOf(
            "Alvarado", "Bustos", "Cárdenas", "Donoso", "Escobar", "Farías",
            "Gallardo", "Henríquez", "Inostroza", "Jélvez", "Keller", "Loyola",
            "Mardones", "Núñez", "Oyarzún", "Peñailillo", "Quezada", "Retamal",
            "Sepúlveda", "Toro", "Ulloa", "Valenzuela", "Yáñez", "Zamora",
            "Acuña", "Barraza", "Concha", "Delgado", "Espinoza", "Flores",
            "Guzmán", "Hidalgo", "Ibáñez", "Jofré", "Lagos", "Miranda",
            "Navarrete", "Osorio", "Pizarro", "Quinteros", "Riquelme", "Salinas",
            "Tapia", "Urbina", "Venegas", "Zúñiga", "Arancibia", "Berrios",
            "Cabrera", "Durán", "Echeverría", "Fuenzalida", "Guerra", "Herrera",
            "Iturriaga", "Jaramillo", "Lara", "Muñoz", "Neira", "Ortega"
        )
        val nombres = listOf(
            "Mateo", "Lucas", "Tomás", "Diego", "Gabriel", "Martín",
            "Joaquín", "Benjamín", "Vicente", "Alonso", "Cristóbal", "Ignacio",
            "Rodrigo", "Felipe", "Matías", "Nicolás", "Esteban", "Mauricio",
            "Patricio", "Sergio", "Andrés", "Claudio", "Eduardo", "Fernando",
            "Gonzalo", "Héctor", "Iván", "Jorge", "Leonardo", "Marcelo",
            "Néstor", "Óscar", "Pablo", "Raúl", "Simón", "Víctor",
            "Alejandro", "Bastián", "Camilo", "Daniel", "Emilio", "Fabián",
            "Germán", "Hugo", "Israel", "Javier", "Kevin", "Luis",
            "Manuel", "Nelson", "Orlando", "Pedro", "Rafael", "Samuel",
            "Tiago", "Ulises", "Valentin", "Walter", "Xavier", "Yair"
        )

        val todos = (0 until 60).map { i ->
            agregar(nombres[i], apellidos[i], "Mena", EmpresaOficial.FINNING, Turno.A, "${300 + i}", "PAB C")
        }

        val env = crearEntorno()

        // 30 trabajadores en una sola orden
        val lote30 = todos.take(30).joinToString(", ") { "${it.nombre} ${it.primerApellido}" }
        val r30 = env.coordinator.procesarEntrada("Estos subieron de Finning: $lote30")
        assertTrue("Debe marcar 30 trabajadores: ${r30.texto}", r30.texto.contains("Marqué 30 trabajadores de Finning"))

        // Resetear estados a NO BAJÓ para probar lote de 50
        todos.take(30).forEach { repository.alternarMarcacionSiBajo(it) }
        val lote50 = todos.take(50).joinToString(", ") { "${it.nombre} ${it.primerApellido}" }
        val r50 = env.coordinator.procesarEntrada("Estos subieron de Finning: $lote50")
        assertTrue("Debe marcar 50 trabajadores: ${r50.texto}", r50.texto.contains("Marqué 50 trabajadores de Finning"))

        // Resetear todos y probar 60 trabajadores con repetición accidental para validar anti-duplicados
        repository.observeTrabajadoresConEstado(Turno.A).first().filter { it.siBajo }.forEach {
            repository.alternarMarcacionSiBajo(it)
        }
        val lote60ConDup = (todos + todos.first()).joinToString(", ") { "${it.nombre} ${it.primerApellido}" }
        val r60 = env.coordinator.procesarEntrada("Estos subieron de Finning: $lote60ConDup")
        assertTrue("Debe marcar exactamente 60 trabajadores únicos: ${r60.texto}", r60.texto.contains("Marqué 60 trabajadores de Finning"))

        val marcadosFinal = repository.observeTrabajadoresConEstado(Turno.A).first().count { it.siBajo }
        assertEquals(60, marcadosFinal)
    }

    @Test
    fun `5 - cola silenciosa de dudas y resolucion multi-turno manteniendo contexto`() = runBlocking {
        val sebastian = agregar("Sebastián", "Morales", "Díaz", EmpresaOficial.KOMATSU, Turno.A, "101", "18 SUR")
        val perezKomatsu = agregar("Juan", "Pérez", "Soto", EmpresaOficial.KOMATSU, Turno.A, "102", "18 SUR")
        val perezFinning = agregar("Juan", "Pérez", "Rojas", EmpresaOficial.FINNING, Turno.A, "205", "19 NORTE")
        val marioFinning = agregar("Mario", "Araya", "Vega", EmpresaOficial.FINNING, Turno.A, "206", "19 NORTE")

        val env = crearEntorno()

        val respLote = env.coordinator.procesarEntrada(
            "Estos subieron: Sebastián Morales, Juan Pérez y Mario Araya de Komatsu",
            origen = LucyOperationOrigin.VOICE
        )
        // Sebastián Morales marcado de inmediato (pero sin empresa porque Mario Araya tiene "de Komatsu")
        assertTrue("Sebastián debe marcarse sin esperar dudas: ${respLote.texto}",
            repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == sebastian.id }.siBajo
        )
        assertTrue("Debe reportar 2 dudas (ambiguo Juan Pérez + Mario Araya de Finning): ${respLote.texto}",
            respLote.texto.contains("Tengo 2 dudas") &&
                respLote.texto.contains("2 coincidencias") &&
                respLote.texto.contains("Finning")
        )
        listOf("101", "102", "205", "206", "18 SUR", "19 NORTE", "Hab.", "Pab.").forEach { prohibido ->
            assertFalse("No debe mencionar '$prohibido' en dudas: ${respLote.texto}", respLote.texto.contains(prohibido))
        }

        // Resolver primera duda por empresa ("El de Komatsu")
        val rDuda1 = env.coordinator.procesarEntrada("El de Komatsu", origen = LucyOperationOrigin.VOICE)
        assertTrue("Debe marcar a Juan Pérez de Komatsu y avanzar a duda 2: ${rDuda1.texto}",
            rDuda1.texto.contains(perezKomatsu.nombreCompleto) && rDuda1.texto.contains("Siguiente duda")
        )
        assertTrue(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == perezKomatsu.id }.siBajo)
        assertFalse(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == perezFinning.id }.siBajo)

        // Resolver segunda duda dejándola pendiente
        val rDuda2 = env.coordinator.procesarEntrada("Déjalo pendiente", origen = LucyOperationOrigin.VOICE)
        assertTrue("Debe confirmar que quedó pendiente sin marcar a Mario: ${rDuda2.texto}",
            rDuda2.texto.contains("Dejé pendiente")
        )
        assertFalse(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == marioFinning.id }.siBajo)

        // Sin dudas pendientes activas después
        val rSinDudas = env.coordinator.procesarEntrada("Déjalo pendiente")
        assertFalse(rSinDudas.texto.contains("Dejé pendiente"))
    }

    @Test
    fun `6 - actualizacion de datos maestros con trazabilidad persistente y rechazo de preguntas hipoteticas`() = runBlocking {
        val sebastian = agregar("Sebastián", "Morales", "Díaz", EmpresaOficial.KOMATSU, Turno.A, "101", "18 SUR")
        val dsFile = tempFolder.newFile("lucy_master_audit_${System.nanoTime()}.preferences_pb")
        val env1 = crearEntorno(dsFile = dsFile)

        // Pregunta hipotética NO debe modificar Room
        val intentHipotetico = LucyIntentParserOffline.parse("¿Si lo cambio a habitación 9?")
        assertFalse(intentHipotetico is LucyIntent.ActualizarDatoTrabajador)
        env1.coordinator.procesarEntrada("Sebastián Morales")
        env1.coordinator.procesarEntrada("¿Si lo cambio a habitación 9?")
        val sinCambios = repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == sebastian.id }
        assertEquals("101", sinCambios.habitacionMayusculas)

        // Actualización real de habitación y pabellón
        val uHab = env1.coordinator.procesarEntrada("Sebastián Morales ya no está en la habitación 101. Ahora está en la habitación 7")
        assertEquals("Actualizado.", uHab.texto)

        val uPab = env1.coordinator.procesarEntrada("Cambia el pabellón de Sebastián Morales a 20 NORTE")
        assertEquals("Actualizado.", uPab.texto)

        val actualizado = repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == sebastian.id }
        assertEquals("7", actualizado.habitacionMayusculas)
        assertEquals("20 NORTE", actualizado.pabellonMayusculas)

        // Verificar que la entidad de trabajador en Room y la marcación en la sesión activa reflejan el cambio
        val entityDb = db.trabajadorDao().getById(sebastian.id)
        assertEquals("7", entityDb?.habitacion)
        assertEquals("20 NORTE", entityDb?.pabellon)

        // Simular reinicio de app recreando DataStoreLucyMemoryPort y LucyCoreCoordinator
        env1.dsScope.coroutineContext[Job]?.cancelAndJoin()
        val env2 = crearEntorno(dsFile = dsFile)
        val historial = env2.coordinator.procesarEntrada("¿Qué cambios hiciste?")
        assertTrue("Debe recordar cambio de habitación 101 -> 7: ${historial.texto}",
            historial.texto.contains("101") && historial.texto.contains("7")
        )
        assertTrue("Debe recordar cambio de pabellón 18 SUR -> 20 NORTE: ${historial.texto}",
            historial.texto.contains("18 SUR") && historial.texto.contains("20 NORTE")
        )
    }

    @Test
    fun `7 - aprendizaje persistente de correcciones en dictado y vision tras reinicio sin auto-marcar desde vision`() = runBlocking {
        val bryan = agregar("Bryan", "Gutiérrez", "Tapia", EmpresaOficial.KOMATSU, Turno.A, "201", "19 NORTE")
        val dsFile = tempFolder.newFile("lucy_learned_corr_${System.nanoTime()}.preferences_pb")

        val gateway1 = FakeConfigurableVisionGateway()
        val env1 = crearEntorno(dsFile = dsFile, visionGateway = gateway1)

        // 1) Corrección explícita por dictado/voz
        val corrVoz = env1.coordinator.procesarEntrada(
            "Cuando digo Brian G es Bryan Gutiérrez",
            origen = LucyOperationOrigin.VOICE
        )
        assertTrue("Debe confirmar aprendizaje: ${corrVoz.texto}", corrVoz.texto.contains("Aprendido"))

        // 2) Análisis de Vision con lectura manuscrita imperfecta y posterior corrección de fila
        val photoFile = createSheetPhotoFile("vision_learned_test.jpg")
        gateway1.nextResult = LucyVisionRawGatewayResult.Success(
            modelName = "gemini-3.5-flash-lite",
            rawJson = """
                {
                  "isReadable": true,
                  "legibilityStatus": "PARCIALMENTE_LEGIBLE",
                  "documentTextType": "MANUSCRITO",
                  "detectedCompanyText": "KOMATSU",
                  "documentNotes": [],
                  "rows": [
                    {
                      "rowNumber": 1,
                      "rawText": "1 | B. Gutierres | 201 | 19 NORTE | SI",
                      "detectedPaternalLastName": null,
                      "detectedMaternalLastName": null,
                      "detectedLastNames": null,
                      "detectedFirstName": null,
                      "detectedName": "B. Gutierres",
                      "detectedRoom": "201",
                      "detectedPavilion": "19 NORTE",
                      "detectedCompanyText": "KOMATSU",
                      "visualMarkingText": "SI",
                      "rowAnnotations": null,
                      "hasManualMarks": true,
                      "textOriginType": "MANUSCRITO",
                      "interpretedMarkingStatus": "SI_BAJO",
                      "confidence": 0.72,
                      "isIncompleteOrIllegible": false,
                      "ambiguityNotes": []
                    }
                  ]
                }
            """.trimIndent()
        )
        val visBefore = env1.coordinator.analizarPlanillaConLucyVision(photoFile.absolutePath)
        assertNotNull(visBefore.visionAnalysis)
        assertFalse("Vision jamás debe marcar en Room por sí sola",
            repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == bryan.id }.siBajo
        )

        val corrVis = env1.coordinator.procesarEntrada("En la fila 1 es Bryan Gutiérrez")
        assertTrue(corrVis.texto.contains("Aprendido"))

        // Simular reinicio completo con nuevas instancias de MemoryPort, Coordinator y VisionPort
        env1.dsScope.coroutineContext[Job]?.cancelAndJoin()
        val gateway2 = FakeConfigurableVisionGateway().apply {
            nextResult = gateway1.nextResult
        }
        val env2 = crearEntorno(dsFile = dsFile, visionGateway = gateway2)

        // Vision tras reinicio debe proponer a Bryan Gutiérrez gracias a la corrección aprendida, SIN marcar Room
        val visAfter = env2.coordinator.analizarPlanillaConLucyVision(photoFile.absolutePath)
        val filaAfter = visAfter.visionAnalysis?.trabajadoresDetectados?.firstOrNull()
        assertNotNull(filaAfter)
        assertEquals(LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA, filaAfter?.coincidenciaProbable?.estado)
        assertEquals(bryan.id, filaAfter?.coincidenciaProbable?.candidatoPrincipal?.trabajador?.id)
        assertEquals("Firebase AI Logic (gemini-3.5-flash-lite)", visAfter.visionAnalysis?.motorUtilizado)
        assertFalse("Vision tras reinicio sigue sin auto-marcar en Room",
            repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == bryan.id }.siBajo
        )

        // Dictado tras reinicio debe resolver "Brian G" a Bryan Gutiérrez y marcar en Room
        val loteTrasReinicio = env2.coordinator.procesarEntrada("Brian G subió", origen = LucyOperationOrigin.VOICE)
        assertTrue("Debe marcar a Bryan Gutiérrez usando corrección aprendida tras reinicio: ${loteTrasReinicio.texto}",
            loteTrasReinicio.texto.contains("Marqué 1 trabajador")
        )
        assertTrue(repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == bryan.id }.siBajo)
    }

    @Test
    fun `8 - camara temporal en lucy_vision_temp sin tocar ocr_temp y preservacion Room v2`() = runBlocking {
        // 1. Verificar Room versión 2 intacta
        assertEquals(2, db.openHelper.readableDatabase.version)

        // 2. Verificar archivo temporal de cámara en lucy_vision_temp + FileProvider URI válido
        val (camFile, camUri) = LucyPhotoIntake.crearArchivoTemporalCamara(context)
        assertTrue(camFile.exists())
        assertTrue(
            "Debe vivir en lucy_vision_temp aislado del OCR offline: ${camFile.absolutePath}",
            camFile.parentFile?.name == "lucy_vision_temp"
        )
        assertFalse(File(context.cacheDir, "ocr_temp").exists())
        assertEquals("${context.packageName}.fileprovider", camUri.authority)

        // Escribir imagen válida en el archivo temporal de cámara y validar adjunto en LucyViewModel
        val bmp = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        FileOutputStream(camFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }
        bmp.recycle()

        val env = crearEntorno()
        val vm = LucyViewModel(
            repository = repository,
            coordinator = env.coordinator,
            voicePort = env.voicePort,
            appContext = context
        )
        vm.adjuntarFotoDesdeCamara(context, camFile.absolutePath)
        esperarHasta { vm.uiState.value.fotoSeleccionada != null }

        val preview = vm.uiState.value.fotoSeleccionada
        assertNotNull(preview)
        assertEquals(800, preview?.widthPx)
        assertEquals(400, preview?.heightPx)
        assertTrue(preview?.displayName?.contains("Planilla cámara") == true)

        vm.limpiarFotoSeleccionada()
        assertNull(vm.uiState.value.fotoSeleccionada)
        assertFalse("Debe limpiar el temporal de cámara al quitar foto", camFile.exists())

        // 3. Verificar manejo de error al denegar permiso de cámara
        vm.reportarErrorCamara("Permiso de cámara denegado.")
        esperarHasta { vm.uiState.value.ultimoErrorRecuperable != null }
        assertTrue(vm.uiState.value.ultimoErrorRecuperable?.contains("Permiso de cámara denegado") == true)
    }

    private suspend fun agregar(
        nombre: String,
        paterno: String,
        materno: String,
        empresa: EmpresaOficial,
        turno: Turno,
        hab: String,
        pab: String
    ): TrabajadorConEstado {
        val entity = repository.agregarTrabajador(
            turno = turno,
            empresa = empresa,
            nombre = nombre,
            primerApellido = paterno,
            segundoApellido = materno,
            habitacion = hab,
            pabellon = pab
        )
        return repository.observeTrabajadoresConEstado(turno).first().first { it.id == entity.id }
    }

    private fun createSheetPhotoFile(name: String): File {
        val file = tempFolder.newFile(name)
        val bmp = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()
        return file
    }

    private data class EntornoTest(
        val coordinator: LucyCoreCoordinator,
        val memoryPort: DataStoreLucyMemoryPort,
        val voicePort: AndroidLucyVoicePort,
        val visionPort: FirebaseLucyVisionPort,
        val dsScope: CoroutineScope
    )

    private fun crearEntorno(
        dsFile: File = tempFolder.newFile("lucy_integral_${System.nanoTime()}.preferences_pb"),
        visionGateway: LucyVisionModelGateway = FakeConfigurableVisionGateway()
    ): EntornoTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopesToClose.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { dsFile }
        )
        val memoryPort = DataStoreLucyMemoryPort(dataStore = dataStore)
        val voicePort = AndroidLucyVoicePort(
            sttController = LucySttController(FakeSttGateway(), mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(FakeTtsGateway(), mainDispatcher = Dispatchers.Unconfined),
            memoryPort = memoryPort,
            initialVoiceOutputEnabled = true
        )
        val visionPort = FirebaseLucyVisionPort(
            repository = repository,
            gateway = visionGateway,
            imageLoader = AndroidLucyVisionImageLoader(),
            memoryPort = memoryPort
        )
        val coordinator = LucyCoreCoordinator(
            repository = repository,
            visionPort = visionPort,
            voicePort = voicePort,
            memoryPort = memoryPort
        )
        return EntornoTest(
            coordinator = coordinator,
            memoryPort = memoryPort,
            voicePort = voicePort,
            visionPort = visionPort,
            dsScope = scope
        )
    }

    private suspend fun esperarHasta(timeoutMs: Long = 4500L, condicion: suspend () -> Boolean) {
        val inicio = System.currentTimeMillis()
        while (!condicion()) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() - inicio > timeoutMs) {
                throw AssertionError("Condición no alcanzada en ${timeoutMs}ms")
            }
            kotlinx.coroutines.delay(25)
        }
    }

    private class FakeConfigurableVisionGateway : LucyVisionModelGateway {
        override val providerName: String = "FakeConfigurableVisionGateway"
        var nextResult: LucyVisionRawGatewayResult = LucyVisionRawGatewayResult.Failure(
            status = LucyVisionDocumentStatus.ILEGIBLE,
            reason = "Sin resultado",
            modelName = "gemini-3.8-flash"
        )

        override suspend fun checkStatus(): LucyVisionGatewayStatus = LucyVisionGatewayStatus(
            isConfigured = true,
            isNetworkAvailable = true,
            activeModelName = "gemini-3.8-flash",
            statusMessage = "OK"
        )

        override suspend fun analyzeSheetImage(
            payload: LucyVisionImagePayload,
            request: LucyVisionRequestContext
        ): LucyVisionRawGatewayResult = nextResult
    }

    private class FakeSttGateway : LucySttGateway {
        override fun hasRecordAudioPermission(): Boolean = true
        override fun isRecognitionAvailable(): Boolean = true
        override fun isOnDeviceRecognitionAvailable(): Boolean = true
        override fun startSession(
            preferOffline: Boolean,
            localeTag: String,
            callbacks: LucySttGatewayCallbacks
        ): LucySttSessionHandle {
            callbacks.onReadyForSpeech()
            return object : LucySttSessionHandle {
                override fun stopListening() = Unit
                override fun cancel() = Unit
                override fun destroy() = Unit
            }
        }
    }

    private class FakeTtsGateway : LucyTtsGateway {
        override fun initialize(callbacks: LucyTtsGatewayCallbacks) = callbacks.onInitialized(true)
        override fun availableVoices(): List<LucyTtsVoiceInfo> = emptyList()
        override fun selectVoiceByName(voiceName: String): Boolean = true
        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun setSpeechRate(rate: Float) = Unit
        override fun setPitch(pitch: Float) = Unit
        override fun speak(text: String, utteranceId: String, flushQueue: Boolean): Int = TextToSpeech.SUCCESS
        override fun stop() = Unit
        override fun shutdown() = Unit
    }
}
