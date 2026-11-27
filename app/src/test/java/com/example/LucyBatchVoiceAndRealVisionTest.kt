package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.example.lucy.LucyAppContainer
import com.example.lucy.LucyDependenciesBundle
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyIntent
import com.example.lucy.core.LucyIntentParserOffline
import com.example.lucy.core.LucyOperationOrigin
import com.example.lucy.memory.DataStoreLucyMemoryPort
import com.example.lucy.ui.LucyViewModel
import com.example.lucy.vision.AndroidLucyVisionImageLoader
import com.example.lucy.vision.FirebaseLucyVisionPort
import com.example.lucy.vision.LucyVisionDocumentPreprocessor
import com.example.lucy.vision.LucyVisionDocumentStatus
import com.example.lucy.vision.LucyVisionGatewayStatus
import com.example.lucy.vision.LucyVisionImagePayload
import com.example.lucy.vision.LucyVisionMatchStatus
import com.example.lucy.vision.LucyVisionModelGateway
import com.example.lucy.vision.LucyVisionRawGatewayResult
import com.example.lucy.vision.LucyVisionRequestContext
import com.example.lucy.vision.LucyVisionTextOriginType
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
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LucyBatchVoiceAndRealVisionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: LavanderiaDatabase
    private lateinit var repository: LavanderiaRepository
    private val scopesToClose = mutableListOf<CoroutineScope>()

    @Before
    fun setup() {
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
    fun `1 - marcacion multiple de 5 trabajadores con empresa sin pedir confirmacion y sin habitaciones ni pabellones`() = runBlocking {
        val w1 = agregar("Sebastián", "Morales", "Díaz", EmpresaOficial.KOMATSU, Turno.A, "101", "18 SUR")
        val w2 = agregar("Pedro", "González", "Rojas", EmpresaOficial.KOMATSU, Turno.A, "102", "18 SUR")
        val w3 = agregar("Juan", "Pérez", "Soto", EmpresaOficial.KOMATSU, Turno.A, "103", "18 SUR")
        val w4 = agregar("Carlos", "Rojas", "Silva", EmpresaOficial.KOMATSU, Turno.A, "104", "18 SUR")
        val w5 = agregar("Andrés", "Soto", "Vera", EmpresaOficial.KOMATSU, Turno.A, "105", "18 SUR")

        val bundle = crearBundleTest()
        val resp = bundle.coordinator.procesarEntrada(
            "Lucy, estos subieron de Komatsu: Sebastián Morales, Pedro González, Juan Pérez, Carlos Rojas y Andrés Soto"
        )

        assertNull("Marcación múltiple no debe pedir tarjeta de confirmación", resp.confirmacionRequerida)
        assertTrue(resp.candidatosDesambiguacion.isEmpty())
        assertTrue("Debe reportar 5 trabajadores marcados: ${resp.texto}", resp.texto.contains("5 trabajadores de Komatsu"))
        assertTrue(resp.texto.contains("SÍ SUBIÓ"))

        // No debe incluir habitación ni pabellón si no se pidió
        listOf("101", "102", "103", "104", "105", "18 SUR", "Hab.", "Pab.").forEach { prohibido ->
            assertFalse("No debe mencionar '$prohibido' en respuesta normal: ${resp.texto}", resp.texto.contains(prohibido))
        }

        val estadoFinal = repository.observeTrabajadoresConEstado(Turno.A).first()
            .associateBy { it.id }
        listOf(w1, w2, w3, w4, w5).forEach { w ->
            assertTrue("${w.nombreCompleto} debe quedar marcado como siBajo=true", estadoFinal[w.id]?.siBajo == true)
        }
    }

    @Test
    fun `2 - marcacion multiple de 10 y 20 trabajadores en una sola orden`() = runBlocking {
        val apellidosBase = listOf(
            "Araya", "Bustamante", "Castillo", "Díaz", "Espinoza",
            "Fuentes", "Garrido", "Herrera", "Ibarra", "Jara",
            "Lagos", "Medina", "Navarro", "Olivares", "Paredes",
            "Quintero", "Riquelme", "Salazar", "Tapia", "Urrutia"
        )
        val nombresBase = listOf(
            "Mateo", "Lucas", "Tomás", "Diego", "Gabriel",
            "Martín", "Joaquín", "Benjamín", "Vicente", "Alonso",
            "Cristóbal", "Ignacio", "Rodrigo", "Felipe", "Matías",
            "Nicolás", "Esteban", "Mauricio", "Patricio", "Sergio"
        )
        val todos = (0 until 20).map { i ->
            agregar(
                nombre = nombresBase[i],
                paterno = apellidosBase[i],
                materno = "Mena",
                empresa = EmpresaOficial.KOMATSU,
                turno = Turno.A,
                hab = "${200 + i}",
                pab = "PAB B"
            )
        }

        val bundle = crearBundleTest()

        // Lote de 10 trabajadores
        val lista10 = (0 until 10).joinToString(", ") { "${nombresBase[it]} ${apellidosBase[it]}" }
        val resp10 = bundle.coordinator.procesarEntrada("Estos subieron de Komatsu: $lista10")
        assertTrue("Debe marcar 10: ${resp10.texto}", resp10.texto.contains("10 trabajadores de Komatsu"))

        // Lote completo de 20 trabajadores (10 ya marcados + 10 nuevos)
        val lista20 = (0 until 20).joinToString(", ") { "${nombresBase[it]} ${apellidosBase[it]}" }
        val resp20 = bundle.coordinator.procesarEntrada("Estos subieron de Komatsu: $lista20")
        assertTrue("Debe marcar los 10 faltantes sin duplicar: ${resp20.texto}", resp20.texto.contains("10 trabajadores de Komatsu"))
        assertTrue("Debe informar los 10 que ya estaban marcados: ${resp20.texto}", resp20.texto.contains("10 ya estaban marcados"))

        val estado = repository.observeTrabajadoresConEstado(Turno.A).first().associateBy { it.id }
        todos.forEach { w ->
            assertTrue("${w.nombreCompleto} debe quedar marcado", estado[w.id]?.siBajo == true)
        }
    }

    @Test
    fun `3 - dictado por voz sin comas y con variaciones foneticas via SpeechRecognizer y TTS`() = runBlocking {
        val w1 = agregar("Sebastián", "Morales", "Díaz", EmpresaOficial.KOMATSU, Turno.A, "101", "A1")
        val w2 = agregar("Pedro", "González", "Rojas", EmpresaOficial.KOMATSU, Turno.A, "102", "A1")
        val w3 = agregar("Juan", "Pérez", "Soto", EmpresaOficial.KOMATSU, Turno.A, "103", "A1")
        val w4 = agregar("Carlos", "Rojas", "Silva", EmpresaOficial.KOMATSU, Turno.A, "104", "A1")
        val w5 = agregar("Andrés", "Soto", "Vera", EmpresaOficial.KOMATSU, Turno.A, "105", "A1")

        val fakeStt = FakeSttGateway()
        val fakeTts = FakeTtsGateway()
        val bundle = crearBundleTest(sttGateway = fakeStt, ttsGateway = fakeTts)
        val viewModel = LucyViewModel(
            repository = repository,
            coordinator = bundle.coordinator,
            voicePort = bundle.voicePort
        )

        viewModel.iniciarEscuchaVoz(preferOffline = false)
        esperarHasta { fakeStt.callbacks != null }
        // Simula Android SpeechRecognizer sin comas, sin tildes y con s/z fonéticas
        fakeStt.callbacks?.onFinalResult(
            "estos subieron de komatsu sebastian moralez pedro gonsalez juan perez carlos rojas andres soto"
        )

        esperarHasta {
            val hist = viewModel.uiState.value.historialTurnoActivo
            hist.any { !it.esUsuario && it.texto.contains("5 trabajadores", ignoreCase = true) }
        }

        val ultimaRespuesta = viewModel.uiState.value.historialTurnoActivo.last { !it.esUsuario }
        assertTrue("Respuesta voz debe marcar 5: ${ultimaRespuesta.texto}", ultimaRespuesta.texto.contains("5 trabajadores de Komatsu"))
        assertTrue("TTS debe haber pronunciado 'sí subió' natural: ${fakeTts.spokenTexts}", fakeTts.spokenTexts.any { it.contains("sí subió", ignoreCase = true) })

        val estado = repository.observeTrabajadoresConEstado(Turno.A).first().associateBy { it.id }
        listOf(w1, w2, w3, w4, w5).forEach { w ->
            assertTrue("${w.nombreCompleto} debe estar marcado por voz", estado[w.id]?.siBajo == true)
        }
        bundle.voicePort.release()
        bundle.coordinator.shutdown()
    }

    @Test
    fun `4 - ambiguedad empresa incorrecta inexistente e idempotencia sin duplicados`() = runBlocking {
        // Dos Sebastián Morales en Komatsu -> ambiguo
        val seb1 = agregar("Sebastián", "Morales", "Díaz", EmpresaOficial.KOMATSU, Turno.A, "101", "A1")
        val seb2 = agregar("Sebastián", "Morales", "Vera", EmpresaOficial.KOMATSU, Turno.A, "102", "A1")
        // Un Pedro González ya marcado en Komatsu
        val pedro = agregar("Pedro", "González", "Rojas", EmpresaOficial.KOMATSU, Turno.A, "103", "A1")
        // Un Carlos Rojas en Komatsu (nuevo)
        val carlos = agregar("Carlos", "Rojas", "Silva", EmpresaOficial.KOMATSU, Turno.A, "104", "A1")
        // Un trabajador de Finning (otra empresa)
        val marioFinning = agregar("Mario", "Araya", "Pino", EmpresaOficial.FINNING, Turno.A, "105", "B2")

        // Pre-marcar a Pedro una vez
        repository.alternarMarcacionSiBajo(pedro)

        val bundle = crearBundleTest()
        val resp = bundle.coordinator.procesarEntrada(
            "Estos subieron de Komatsu: Sebastián Morales, Pedro González, Carlos Rojas, Carlos Rojas, Mario Araya y Nadie Inexistente"
        )

        // Carlos debe quedar marcado, Pedro sigue marcado sin duplicar fila en Room
        val estado = repository.observeTrabajadoresConEstado(Turno.A).first().associateBy { it.id }
        assertTrue("Carlos debe quedar marcado", estado[carlos.id]?.siBajo == true)
        assertTrue("Pedro debe seguir marcado", estado[pedro.id]?.siBajo == true)

        // Ninguno de los ambiguos ni el de otra empresa debe ser marcado
        assertFalse("seb1 ambiguo NO debe marcarse", estado[seb1.id]?.siBajo == true)
        assertFalse("seb2 ambiguo NO debe marcarse", estado[seb2.id]?.siBajo == true)
        assertFalse("Mario de Finning NO debe marcarse al dictar Komatsu", estado[marioFinning.id]?.siBajo == true)

        // Verificar idempotencia real en Room (0 duplicados en tabla marcaciones)
        val sesion = db.sesionDao().getOpenSessionByTurno(Turno.A.id)!!
        val marcacionesPedro = db.marcacionDao().getBySesionAndTurnoOnce(sesion.id, Turno.A.id)
            .filter { it.trabajadorId == pedro.id }
        assertEquals("No debe haber filas duplicadas en Room para Pedro", 1, marcacionesPedro.size)

        // Mensajes claros
        assertTrue("Debe informar ambigüedad de Sebastián Morales: ${resp.texto}", resp.texto.contains("2 coincidencias"))
        assertTrue("Debe informar que Mario Araya pertenece a Finning: ${resp.texto}", resp.texto.contains("Finning"))
        assertTrue("Debe informar que Nadie Inexistente no existe: ${resp.texto}", resp.texto.contains("No encontré"))
    }

    @Test
    fun `5 - continuidad conversacional hereda empresa y turno y respeta separacion Turno A y Turno B`() = runBlocking {
        val k1 = agregar("Sebastián", "Morales", "Díaz", EmpresaOficial.KOMATSU, Turno.A, "101", "A1")
        val k2 = agregar("Pedro", "González", "Rojas", EmpresaOficial.KOMATSU, Turno.A, "102", "A1")
        val k3 = agregar("Juan", "Pérez", "Soto", EmpresaOficial.KOMATSU, Turno.A, "103", "A1")
        val k4 = agregar("Carlos", "Rojas", "Silva", EmpresaOficial.KOMATSU, Turno.A, "104", "A1")
        val k5 = agregar("Andrés", "Soto", "Vera", EmpresaOficial.KOMATSU, Turno.A, "105", "A1")
        val k6 = agregar("Felipe", "Muñoz", "Castro", EmpresaOficial.KOMATSU, Turno.A, "106", "A1")
        val kTurnoB = agregar("Carlos", "Rojas", "Silva", EmpresaOficial.KOMATSU, Turno.B, "204", "B1")

        val bundle = crearBundleTest()

        val turno1 = bundle.coordinator.procesarEntrada(
            "Estos subieron de Komatsu: Sebastián Morales, Pedro González y Juan Pérez"
        )
        assertTrue(turno1.texto.contains("3 trabajadores de Komatsu"))

        // Turno 2 de continuación sin repetir "Komatsu" ni "estos subieron"
        val turno2 = bundle.coordinator.procesarEntrada(
            "También Carlos Rojas, Andrés Soto y Felipe Muñoz"
        )
        assertTrue("Continuación debe heredar Komatsu y marcar 3: ${turno2.texto}", turno2.texto.contains("3 trabajadores de Komatsu"))

        val estadoA = repository.observeTrabajadoresConEstado(Turno.A).first().associateBy { it.id }
        val estadoB = repository.observeTrabajadoresConEstado(Turno.B).first().associateBy { it.id }
        listOf(k1, k2, k3, k4, k5, k6).forEach { w ->
            assertTrue("${w.nombreCompleto} debe quedar marcado en Turno A", estadoA[w.id]?.siBajo == true)
        }
        assertFalse("Turno B NO debe verse afectado", estadoB[kTurnoB.id]?.siBajo == true)

        // Bloqueo de seguridad si pide Turno B estando en Turno A
        val conflicto = bundle.coordinator.procesarEntrada(
            "Estos subieron de Komatsu en Turno B: Carlos Rojas y Pedro González"
        )
        assertTrue(conflicto.esErrorRecuperable)
        assertTrue(conflicto.texto.contains("Turno B"))
        assertFalse(repository.observeTrabajadoresConEstado(Turno.B).first().any { it.siBajo })
    }

    @Test
    fun `6 - comando individual marca a mantiene flujo de confirmacion y si subio marca directo`() = runBlocking {
        val juan = agregar("Juan", "Pérez", "Soto", EmpresaOficial.KOMATSU, Turno.A, "103", "A1")
        val bundle = crearBundleTest()

        // Comando clásico individual sigue pidiendo confirmación
        val singleConfirm = bundle.coordinator.procesarEntrada("marca a Juan Pérez")
        assertNotNull("marca a <uno> sigue requiriendo confirmación", singleConfirm.confirmacionRequerida)

        // Significado explícito "sí subió" marca directamente como SÍ SUBIÓ
        val directSiSubio = bundle.coordinator.procesarEntrada("sí subió Juan Pérez de Komatsu")
        assertNull(directSiSubio.confirmacionRequerida)
        assertTrue(directSiSubio.texto.contains("SÍ SUBIÓ"))

        val estado = repository.observeTrabajadoresConEstado(Turno.A).first().first { it.id == juan.id }
        assertTrue(estado.siBajo)

        // Pregunta "¿ya subió?" NO marca, solo consulta
        val parserPregunta = LucyIntentParserOffline.parse("¿ya subió Juan Pérez?", Turno.A)
        assertTrue(parserPregunta is LucyIntent.ConsultarEstadoTrabajador)
    }

    @Test
    fun `7 - LucyVision preprocesamiento y extraccion tabular con paterno materno impreso manuscrito y marcas`() = runBlocking {
        val w1 = agregar("Sebastián", "Morales", "Díaz", EmpresaOficial.KOMATSU, Turno.A, "101", "18 SUR")
        val w2 = agregar("Pedro", "González", "Rojas", EmpresaOficial.KOMATSU, Turno.A, "205", "19 NORTE")

        // Crear fotografía real de planilla en disco y verificar preprocesador Kotlin (fallback sin OpenCV en JVM)
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(245, 245, 240))
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 22f
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(20f, 20f, 620f, 230f, linePaint)
        canvas.drawLine(20f, 75f, 620f, 75f, linePaint)
        canvas.drawLine(20f, 150f, 620f, 150f, linePaint)
        canvas.drawText("KOMATSU - TURNO A", 30f, 50f, paint)
        canvas.drawText("1  MORALES DIAZ SEBASTIAN   101   18 SUR   [✓]", 30f, 120f, paint)
        canvas.drawText("2  GONZALEZ ROJAS PEDRO     205   19 NORTE [X]", 30f, 190f, paint)
        val photoFile = tempFolder.newFile("planilla_real.jpg")
        FileOutputStream(photoFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bitmap.recycle()

        val preproc = LucyVisionDocumentPreprocessor.prepararDocumento(
            archivo = photoFile,
            maxDimPx = 1200,
            forzarFallbackSinOpenCv = true
        )
        assertNotNull("Preprocesador debe entregar imagen preparada", preproc)
        assertTrue(preproc!!.contrasteYNitidezAplicados)
        assertFalse(preproc.bitmap.isRecycled)
        preproc.bitmap.recycle()

        val fakeGateway = FakeRealVisionGateway().apply {
            nextResult = LucyVisionRawGatewayResult.Success(
                modelName = "gemini-3.8-flash",
                rawJson = """
                    {
                      "isReadable": true,
                      "legibilityStatus": "PARCIALMENTE_LEGIBLE",
                      "documentTextType": "MIXTO",
                      "detectedCompanyText": "KOMATSU",
                      "documentNotes": ["Fila 2 presenta anotación manuscrita en columna de observaciones"],
                      "rows": [
                        {
                          "rowNumber": 1,
                          "rawText": "1 MORALES DIAZ SEBASTIAN 101 18 SUR ✓",
                          "detectedPaternalLastName": "MORALES",
                          "detectedMaternalLastName": "DIAZ",
                          "detectedFirstName": "SEBASTIAN",
                          "detectedName": "MORALES DIAZ SEBASTIAN",
                          "textOriginType": "IMPRESO",
                          "detectedCompanyText": "KOMATSU",
                          "detectedRoom": "101",
                          "detectedPavilion": "18 SUR",
                          "visualMarkingText": "✓",
                          "rowAnnotations": null,
                          "hasManualMarks": true,
                          "interpretedMarkingStatus": "SI_BAJO",
                          "confidence": 0.94,
                          "ambiguityNotes": [],
                          "isIncompleteOrIllegible": false
                        },
                        {
                          "rowNumber": 2,
                          "rawText": "2 GONZALEZ ROJAS PEDRO 205 19 NORTE X revisado",
                          "detectedPaternalLastName": "GONZALEZ",
                          "detectedMaternalLastName": "ROJAS",
                          "detectedFirstName": "PEDRO",
                          "detectedName": "GONZALEZ ROJAS PEDRO",
                          "textOriginType": "MANUSCRITO",
                          "detectedRoom": "205",
                          "detectedPavilion": "19 NORTE",
                          "visualMarkingText": "X",
                          "rowAnnotations": "revisado a mano",
                          "hasManualMarks": true,
                          "interpretedMarkingStatus": "NO_BAJO",
                          "confidence": 0.72,
                          "ambiguityNotes": ["Trazo manual sobre apellido materno"],
                          "isIncompleteOrIllegible": false
                        }
                      ]
                    }
                """.trimIndent()
            )
        }

        val port = FirebaseLucyVisionPort(
            repository = repository,
            gateway = fakeGateway,
            imageLoader = AndroidLucyVisionImageLoader()
        )
        val bundle = crearBundleTest(visionPort = port)
        val resp = bundle.coordinator.analizarPlanillaConLucyVision(
            imagePath = photoFile.absolutePath
        )

        val analysis = resp.visionAnalysis
        assertNotNull(analysis)
        assertTrue(analysis!!.esDocumentoOperativoValido)
        assertEquals(2, analysis.trabajadoresDetectados.size)
        assertEquals("Firebase AI Logic (gemini-3.8-flash)", analysis.motorUtilizado)
        assertTrue(
            "Gateway debe haber recibido resumen de preprocesamiento",
            !fakeGateway.lastRequest?.preprocessingSummary.isNullOrBlank()
        )

        // Fila 1: alineación exacta, impreso, paterno/materno/nombre, marca manual
        val det1 = analysis.trabajadoresDetectados[0]
        assertEquals("MORALES", det1.apellidoPaternoDetectado)
        assertEquals("DIAZ", det1.apellidoMaternoDetectado)
        assertEquals("SEBASTIAN", det1.nombrePilaDetectado)
        assertEquals("101", det1.datosReconocidos.habitacionEscritaEnImagen)
        assertEquals("18 SUR", det1.datosReconocidos.pabellonEscritoEnImagen)
        assertEquals(LucyVisionTextOriginType.IMPRESO, det1.tipoEscritura)
        assertTrue(det1.tieneMarcasManuales)
        assertEquals(LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA, det1.coincidenciaProbable.estado)
        assertEquals(w1.id, det1.coincidenciaProbable.candidatoPrincipal?.trabajador?.id)

        // Fila 2: alineación exacta con SU habitación 205 y SU pabellón 19 NORTE (sin mezclarse con Fila 1)
        val det2 = analysis.trabajadoresDetectados[1]
        assertEquals("GONZALEZ", det2.apellidoPaternoDetectado)
        assertEquals("ROJAS", det2.apellidoMaternoDetectado)
        assertEquals("205", det2.datosReconocidos.habitacionEscritaEnImagen)
        assertEquals("19 NORTE", det2.datosReconocidos.pabellonEscritoEnImagen)
        assertEquals(LucyVisionTextOriginType.MANUSCRITO, det2.tipoEscritura)
        assertEquals("revisado a mano", det2.anotacionesFila)
        assertEquals(w2.id, det2.coincidenciaProbable.candidatoPrincipal?.trabajador?.id)

        // Verificar que Lucy Vision NO modificó marcaciones en Room
        val marcacionesEnRoom = repository.observeTrabajadoresConEstado(Turno.A).first().filter { it.siBajo }
        assertTrue("Lucy Vision no debe auto-marcar en Room", marcacionesEnRoom.isEmpty())

        // Caso de imagen ilegible o sin filas legibles -> reporte honesto
        fakeGateway.nextResult = LucyVisionRawGatewayResult.Success(
            modelName = "gemini-3.8-flash",
            rawJson = """
                {
                  "isReadable": true,
                  "legibilityStatus": "LEGIBLE",
                  "documentNotes": ["No se observan filas de trabajadores"],
                  "rows": []
                }
            """.trimIndent()
        )
        val respVacia = bundle.coordinator.analizarPlanillaConLucyVision(photoFile.absolutePath)
        assertEquals(LucyVisionDocumentStatus.ILEGIBLE, respVacia.visionAnalysis?.estadoDocumento)
        assertFalse(respVacia.visionAnalysis?.esDocumentoOperativoValido == true)
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

    private fun crearBundleTest(
        sttGateway: FakeSttGateway = FakeSttGateway(),
        ttsGateway: FakeTtsGateway = FakeTtsGateway(),
        visionPort: FirebaseLucyVisionPort? = null
    ): LucyDependenciesBundle {
        val dsFile = tempFolder.newFile("lucy_batch_test_${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopesToClose.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { dsFile }
        )
        val memoryPort = DataStoreLucyMemoryPort(dataStore = dataStore)
        val voicePort = AndroidLucyVoicePort(
            sttController = LucySttController(sttGateway, mainDispatcher = Dispatchers.Unconfined),
            ttsEngine = LucyTtsEngine(ttsGateway, mainDispatcher = Dispatchers.Unconfined),
            memoryPort = memoryPort,
            initialVoiceOutputEnabled = true
        )
        val imageLoader = AndroidLucyVisionImageLoader()
        val resolvedVisionPort = visionPort ?: FirebaseLucyVisionPort(
            repository = repository,
            gateway = FakeRealVisionGateway(),
            imageLoader = imageLoader
        )
        val coordinator = LucyCoreCoordinator(
            repository = repository,
            visionPort = resolvedVisionPort,
            voicePort = voicePort,
            memoryPort = memoryPort
        )
        return LucyDependenciesBundle(
            coordinator = coordinator,
            memoryPort = memoryPort,
            voicePort = voicePort,
            visionPort = resolvedVisionPort,
            imageLoader = imageLoader
        )
    }

    private suspend fun esperarHasta(timeoutMs: Long = 4500L, condicion: suspend () -> Boolean) {
        val inicio = System.currentTimeMillis()
        while (!condicion()) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() - inicio > timeoutMs) {
                throw AssertionError("Condición no alcanzada en ${timeoutMs}ms")
            }
            kotlinx.coroutines.delay(25)
        }
    }

    private class FakeRealVisionGateway : LucyVisionModelGateway {
        override val providerName: String = "FakeRealVisionGateway"
        var nextResult: LucyVisionRawGatewayResult = LucyVisionRawGatewayResult.Failure(
            status = LucyVisionDocumentStatus.ILEGIBLE,
            reason = "Sin configurar",
            modelName = "gemini-3.8-flash"
        )
        var lastRequest: LucyVisionRequestContext? = null

        override suspend fun checkStatus(): LucyVisionGatewayStatus {
            return LucyVisionGatewayStatus(
                isConfigured = true,
                isNetworkAvailable = true,
                activeModelName = "gemini-3.8-flash",
                statusMessage = "OK"
            )
        }

        override suspend fun analyzeSheetImage(
            payload: LucyVisionImagePayload,
            request: LucyVisionRequestContext
        ): LucyVisionRawGatewayResult {
            lastRequest = request
            return nextResult
        }
    }

    private class FakeSttGateway : LucySttGateway {
        var callbacks: LucySttGatewayCallbacks? = null
        override fun hasRecordAudioPermission(): Boolean = true
        override fun isRecognitionAvailable(): Boolean = true
        override fun isOnDeviceRecognitionAvailable(): Boolean = true
        override fun startSession(
            preferOffline: Boolean,
            localeTag: String,
            callbacks: LucySttGatewayCallbacks
        ): LucySttSessionHandle {
            this.callbacks = callbacks
            callbacks.onReadyForSpeech()
            return object : LucySttSessionHandle {
                override fun stopListening() = Unit
                override fun cancel() = Unit
                override fun destroy() = Unit
            }
        }
    }

    private class FakeTtsGateway : LucyTtsGateway {
        val spokenTexts = mutableListOf<String>()
        override fun initialize(callbacks: LucyTtsGatewayCallbacks) {
            callbacks.onInitialized(true)
        }
        override fun availableVoices(): List<LucyTtsVoiceInfo> = listOf(
            LucyTtsVoiceInfo("es-cl-local", "es-CL", isNetworkRequired = false, quality = 400)
        )
        override fun selectVoiceByName(voiceName: String): Boolean = true
        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun setSpeechRate(rate: Float) = Unit
        override fun setPitch(pitch: Float) = Unit
        override fun speak(text: String, utteranceId: String, flushQueue: Boolean): Int {
            spokenTexts.add(text)
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }
}
