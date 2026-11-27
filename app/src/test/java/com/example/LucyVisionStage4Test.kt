package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LavanderiaDatabase
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.Turno
import com.example.lucy.core.LucyCoreCoordinator
import com.example.lucy.core.LucyExecutionMode
import com.example.lucy.core.LucyModuleHealth
import com.example.lucy.core.LucyModuleId
import com.example.lucy.core.LucyTurnContext
import com.example.lucy.core.LucyVisionOutcome
import com.example.lucy.core.LucyVisionPort
import com.example.lucy.core.NoOpLucyVisionPort
import com.example.lucy.vision.AndroidLucyVisionImageLoader
import com.example.lucy.vision.FirebaseLucyVisionPort
import com.example.lucy.vision.LucyVisionConfidenceLevel
import com.example.lucy.vision.LucyVisionDataOrigin
import com.example.lucy.vision.LucyVisionDocumentStatus
import com.example.lucy.vision.LucyVisionGatewayStatus
import com.example.lucy.vision.LucyVisionImageLoadResult
import com.example.lucy.vision.LucyVisionImagePayload
import com.example.lucy.vision.LucyVisionMarkingStatus
import com.example.lucy.vision.LucyVisionMatchStatus
import com.example.lucy.vision.LucyVisionModelGateway
import com.example.lucy.vision.LucyVisionRawGatewayResult
import com.example.lucy.vision.LucyVisionRequestContext
import com.example.lucy.vision.LucyVisionResponseParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LucyVisionStage4Test {

    private lateinit var context: Context
    private lateinit var db: LavanderiaDatabase
    private lateinit var repository: LavanderiaRepository
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LavanderiaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LavanderiaRepository(db)
        repository.ensureInitialized()
    }

    @After
    fun teardown() {
        db.close()
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    @Test
    fun `1 - cargador de imagen prepara JPEG bajo limite y analisis estructurado distingue datos reconocidos e inferidos`() = runTest {
        val juan = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU_ESOP,
            nombre = "JUAN",
            primerApellido = "ROJAS",
            segundoApellido = "DIAZ",
            habitacion = "205",
            pabellon = "18 SUR"
        )
        // Trabajador de Komatsu normal (NO debe confundirse con Komatsu ESOP)
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.KOMATSU,
            nombre = "JUAN",
            primerApellido = "ROJAS",
            segundoApellido = "SILVA",
            habitacion = "301",
            pabellon = "A1"
        )
        // Trabajador en Turno B (NO debe mezclarse con Turno A)
        repository.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.KOMATSU_ESOP,
            nombre = "PEDRO",
            primerApellido = "SILVA",
            segundoApellido = "SOTO",
            habitacion = "410",
            pabellon = "C2"
        )

        val foto = crearImagenPlanillaTemporal("planilla_komatsu_esop.jpg", width = 1200, height = 900)
        val jsonModelo = """
            {
              "isReadable": true,
              "legibilityStatus": "LEGIBLE",
              "detectedCompanyText": "Komatsu ESOP",
              "documentNotes": [],
              "rows": [
                {
                  "rowNumber": 1,
                  "rawText": "1. ROJAS DIAZ JUAN - Hab 205 [tic]",
                  "detectedName": "ROJAS DIAZ JUAN",
                  "detectedCompanyText": null,
                  "detectedPavilion": null,
                  "detectedRoom": "205",
                  "visualMarkingText": "tic verde",
                  "interpretedMarkingStatus": "SI_BAJO",
                  "confidence": 0.94,
                  "ambiguityNotes": [],
                  "isIncompleteOrIllegible": false
                },
                {
                  "rowNumber": 2,
                  "rawText": "2. SILVA SOTO PEDRO - Pab C2 [X]",
                  "detectedName": "SILVA SOTO PEDRO",
                  "detectedCompanyText": "Komatsu ESOP",
                  "detectedPavilion": "C2",
                  "detectedRoom": null,
                  "visualMarkingText": "X roja",
                  "interpretedMarkingStatus": "NO_BAJO",
                  "confidence": 0.86,
                  "ambiguityNotes": [],
                  "isIncompleteOrIllegible": false
                }
              ]
            }
        """.trimIndent()

        val gateway = FakeVisionGateway(
            status = LucyVisionGatewayStatus(
                isConfigured = true,
                isNetworkAvailable = true,
                activeModelName = "gemini-3.8-flash",
                statusMessage = "OK"
            ),
            nextResult = LucyVisionRawGatewayResult.Success(jsonModelo, "gemini-3.8-flash")
        )
        val port = FirebaseLucyVisionPort(
            repository = repository,
            gateway = gateway,
            imageLoader = AndroidLucyVisionImageLoader()
        )

        val analisis = port.analizarPlanillaDetallada(
            imagePath = foto.absolutePath,
            turnoContexto = Turno.A,
            empresaSugerida = EmpresaOficial.KOMATSU_ESOP
        )

        assertNotNull(gateway.lastPayload)
        assertTrue(gateway.lastPayload!!.jpegBytes.isNotEmpty())
        assertEquals(LucyVisionDocumentStatus.PARCIALMENTE_LEGIBLE, analisis.estadoDocumento)
        assertTrue(analisis.esDocumentoOperativoValido)
        assertTrue(analisis.esResultadoPropuestoNoConfirmado)
        assertEquals("Komatsu ESOP", analisis.empresaDocumentoReconocida)
        assertEquals(EmpresaOficial.KOMATSU_ESOP, analisis.empresaDocumentoInferida)
        assertEquals(2, analisis.trabajadoresDetectados.size)

        // Fila 1: Empresa reconocida del encabezado; pabellón NO estaba en imagen -> inferido del maestro
        val det1 = analisis.trabajadoresDetectados[0]
        assertEquals("ROJAS DIAZ JUAN", det1.nombreDetectado)
        assertEquals(EmpresaOficial.KOMATSU_ESOP, det1.empresaProbable)
        assertEquals(LucyVisionDataOrigin.RECONOCIDO_EN_IMAGEN, det1.origenEmpresa)
        assertNull(det1.datosReconocidos.pabellonEscritoEnImagen)
        assertEquals("18 SUR", det1.pabellon)
        assertEquals(LucyVisionDataOrigin.INFERIDO_POR_MAESTRO, det1.origenPabellon)
        assertEquals(LucyVisionMarkingStatus.SI_BAJO, det1.estadoMarcacion)
        assertEquals(LucyVisionDataOrigin.RECONOCIDO_EN_IMAGEN, det1.origenMarcacion)
        assertEquals(LucyVisionConfidenceLevel.ALTA, det1.nivelConfianza)
        assertEquals(LucyVisionMatchStatus.COINCIDENCIA_CANDIDATA_UNICA, det1.coincidenciaProbable.estado)
        assertEquals(juan.id, det1.coincidenciaProbable.candidatoPrincipal?.trabajador?.id)
        assertFalse(det1.coincidenciaProbable.esIdentidadConfirmada)

        // Fila 2: Pedro Silva está en Turno B, NO en Turno A -> jamás mezcla turnos
        val det2 = analisis.trabajadoresDetectados[1]
        assertEquals("C2", det2.datosReconocidos.pabellonEscritoEnImagen)
        assertEquals(LucyVisionDataOrigin.RECONOCIDO_EN_IMAGEN, det2.origenPabellon)
        assertEquals(LucyVisionMarkingStatus.NO_BAJO, det2.estadoMarcacion)
        assertEquals(LucyVisionMatchStatus.SIN_COINCIDENCIA, det2.coincidenciaProbable.estado)
        assertNull(det2.coincidenciaProbable.candidatoPrincipal)
    }

    @Test
    fun `2 - detecta nombres ambiguos homonimos y nombres incompletos sin confirmar identidad`() = runTest {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.FINNING,
            nombre = "CARLOS",
            primerApellido = "PEREZ",
            segundoApellido = "GOMEZ",
            habitacion = "104",
            pabellon = "B1"
        )
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.FINNING,
            nombre = "CARLOS",
            primerApellido = "PEREZ",
            segundoApellido = "ARAYA",
            habitacion = "108",
            pabellon = "B2"
        )

        val foto = crearImagenPlanillaTemporal("planilla_ambigua.jpg")
        val json = """
            {
              "isReadable": true,
              "legibilityStatus": "PARCIALMENTE_LEGIBLE",
              "detectedCompanyText": "Finning",
              "documentNotes": ["Tinta corrida en columna derecha"],
              "rows": [
                {
                  "rowNumber": 1,
                  "rawText": "PEREZ CARLOS (sin 2do apellido)",
                  "detectedName": "PEREZ CARLOS",
                  "detectedCompanyText": null,
                  "detectedPavilion": null,
                  "detectedRoom": null,
                  "visualMarkingText": "tic",
                  "interpretedMarkingStatus": "SI_BAJO",
                  "confidence": 0.72,
                  "ambiguityNotes": ["No se ve el segundo apellido"],
                  "isIncompleteOrIllegible": true
                },
                {
                  "rowNumber": 2,
                  "rawText": "PEREZ",
                  "detectedName": "PEREZ",
                  "detectedCompanyText": null,
                  "detectedPavilion": null,
                  "detectedRoom": null,
                  "visualMarkingText": null,
                  "interpretedMarkingStatus": "INDETERMINADO",
                  "confidence": 0.45,
                  "ambiguityNotes": ["Solo se distingue un apellido"],
                  "isIncompleteOrIllegible": true
                }
              ]
            }
        """.trimIndent()

        val port = crearPortConJsonFake(json)
        val analisis = port.analizarPlanillaDetallada(foto.absolutePath, Turno.A, EmpresaOficial.FINNING)

        assertEquals(LucyVisionDocumentStatus.PARCIALMENTE_LEGIBLE, analisis.estadoDocumento)
        assertTrue(analisis.requiereRevisionHumana)

        val detAmbiguo = analisis.trabajadoresDetectados[0]
        assertEquals(LucyVisionMatchStatus.AMBIGUA_MULTIPLE, detAmbiguo.coincidenciaProbable.estado)
        assertEquals(2, detAmbiguo.coincidenciaProbable.candidatos.size)
        assertFalse(detAmbiguo.coincidenciaProbable.esIdentidadConfirmada)
        assertTrue(detAmbiguo.observacionesYDudas.any { "ambigua" in it.lowercase() || "segundo apellido" in it.lowercase() })

        val detIncompleto = analisis.trabajadoresDetectados[1]
        assertTrue(
            detIncompleto.coincidenciaProbable.estado == LucyVisionMatchStatus.NOMBRE_INCOMPLETO ||
                detIncompleto.coincidenciaProbable.estado == LucyVisionMatchStatus.AMBIGUA_MULTIPLE
        )
        assertEquals(LucyVisionConfidenceLevel.BAJA, detIncompleto.nivelConfianza)
        assertEquals(LucyVisionMarkingStatus.INDETERMINADO, detIncompleto.estadoMarcacion)
    }

    @Test
    fun `3 - detecta posibles duplicados dentro de la misma planilla y distingue Finning vs Finning Pala`() = runTest {
        val trabPala = repository.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.FINNING_PALA,
            nombre = "MARCO",
            primerApellido = "MUNOZ",
            segundoApellido = "LEAL",
            habitacion = "502",
            pabellon = "N1"
        )
        repository.agregarTrabajador(
            turno = Turno.B,
            empresa = EmpresaOficial.FINNING,
            nombre = "MARCO",
            primerApellido = "MUNOZ",
            segundoApellido = "TAPIA",
            habitacion = "110",
            pabellon = "S1"
        )

        val foto = crearImagenPlanillaTemporal("planilla_duplicada.jpg")
        val json = """
            {
              "isReadable": true,
              "legibilityStatus": "LEGIBLE",
              "detectedCompanyText": "Finning Pala",
              "documentNotes": [],
              "rows": [
                {
                  "rowNumber": 1,
                  "rawText": "MUNOZ LEAL MARCO",
                  "detectedName": "MUNOZ LEAL MARCO",
                  "detectedCompanyText": null,
                  "detectedPavilion": "N1",
                  "detectedRoom": "502",
                  "visualMarkingText": "tic",
                  "interpretedMarkingStatus": "SI_BAJO",
                  "confidence": 0.91,
                  "ambiguityNotes": [],
                  "isIncompleteOrIllegible": false
                },
                {
                  "rowNumber": 4,
                  "rawText": "MARCO MUNOZ LEAL (anotado de nuevo)",
                  "detectedName": "MARCO MUNOZ LEAL",
                  "detectedCompanyText": null,
                  "detectedPavilion": "N1",
                  "detectedRoom": "502",
                  "visualMarkingText": "tic",
                  "interpretedMarkingStatus": "SI_BAJO",
                  "confidence": 0.88,
                  "ambiguityNotes": [],
                  "isIncompleteOrIllegible": false
                }
              ]
            }
        """.trimIndent()

        val port = crearPortConJsonFake(json)
        val analisis = port.analizarPlanillaDetallada(foto.absolutePath, Turno.B, null)

        assertEquals(EmpresaOficial.FINNING_PALA, analisis.empresaDocumentoInferida)
        assertTrue(analisis.posiblesDuplicados.isNotEmpty())
        assertEquals(trabPala.id, analisis.posiblesDuplicados.first().trabajadorCandidatoId)
        assertEquals(listOf(1, 2), analisis.posiblesDuplicados.first().indicesFilasDetectadas)
        assertTrue(analisis.trabajadoresDetectados.all { it.esPosibleDuplicadoEnPlanilla })
        assertTrue(analisis.trabajadoresDetectados.all { it.empresaProbable == EmpresaOficial.FINNING_PALA })
    }

    @Test
    fun `4 - maneja imagenes ilegibles y archivos corruptos o inexistentes sin fallar`() = runTest {
        val foto = crearImagenPlanillaTemporal("planilla_borrosa.jpg")
        val jsonIlegible = """
            {
              "isReadable": false,
              "legibilityStatus": "ILEGIBLE",
              "detectedCompanyText": null,
              "documentNotes": ["Imagen muy oscura y movida, no se distinguen nombres."],
              "rows": []
            }
        """.trimIndent()

        val portIlegible = crearPortConJsonFake(jsonIlegible)
        val resModelo = portIlegible.analizarPlanillaDetallada(foto.absolutePath, Turno.A, null)
        assertEquals(LucyVisionDocumentStatus.ILEGIBLE, resModelo.estadoDocumento)
        assertFalse(resModelo.esDocumentoOperativoValido)
        assertTrue(resModelo.notasIlegibilidad.isNotEmpty())

        val archivoInexistente = File(context.cacheDir, "no_existe_${System.currentTimeMillis()}.jpg")
        val resArchivo = portIlegible.analizarPlanillaDetallada(archivoInexistente.absolutePath, Turno.A, null)
        assertEquals(LucyVisionDocumentStatus.ILEGIBLE, resArchivo.estadoDocumento)
        assertFalse(resArchivo.esDocumentoOperativoValido)

        val archivoVacio = File.createTempFile("vacio_", ".jpg", context.cacheDir).also { tempFiles.add(it) }
        val loadVacio = AndroidLucyVisionImageLoader().loadPreparedImage(archivoVacio.absolutePath, 1600, 85, 4_000_000)
        assertTrue(loadVacio is LucyVisionImageLoadResult.Failed)
    }

    @Test
    fun `5 - maneja errores de conexion, timeout, cuota y App Check con mensajes claros`() = runTest {
        val foto = crearImagenPlanillaTemporal("planilla_red.jpg")

        val gwSinRed = FakeVisionGateway(
            status = LucyVisionGatewayStatus(
                isConfigured = true,
                isNetworkAvailable = false,
                activeModelName = "gemini-3.8-flash",
                statusMessage = "Sin conexión a Internet."
            ),
            nextResult = LucyVisionRawGatewayResult.Success("{}", "gemini-3.8-flash")
        )
        val portSinRed = FirebaseLucyVisionPort(repository, gwSinRed)
        assertTrue(portSinRed.healthCheck() is LucyModuleHealth.Degraded)
        val outSinRed = portSinRed.analyzePlanilla(foto.absolutePath, Turno.A)
        assertTrue(outSinRed is LucyVisionOutcome.Failed)
        assertEquals(
            LucyVisionDocumentStatus.SIN_CONEXION,
            portSinRed.analizarPlanillaDetallada(foto.absolutePath, Turno.A).estadoDocumento
        )

        val gwTimeout = FakeVisionGateway(
            status = LucyVisionGatewayStatus(true, true, "gemini-3.8-flash", statusMessage = "OK"),
            nextResult = LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.TIMEOUT,
                reason = "Tiempo de espera agotado (45 s).",
                modelName = "gemini-3.8-flash"
            )
        )
        val resTimeout = FirebaseLucyVisionPort(repository, gwTimeout).analizarPlanillaDetallada(foto.absolutePath, Turno.A)
        assertEquals(LucyVisionDocumentStatus.TIMEOUT, resTimeout.estadoDocumento)

        val gwAppCheck = FakeVisionGateway(
            status = LucyVisionGatewayStatus(true, true, "gemini-3.8-flash", statusMessage = "OK"),
            nextResult = LucyVisionRawGatewayResult.Failure(
                status = LucyVisionDocumentStatus.ERROR_APP_CHECK,
                reason = "App Check 403",
                modelName = "gemini-3.8-flash"
            )
        )
        val resAppCheck = FirebaseLucyVisionPort(repository, gwAppCheck).analizarPlanillaDetallada(foto.absolutePath, Turno.A)
        assertEquals(LucyVisionDocumentStatus.ERROR_APP_CHECK, resAppCheck.estadoDocumento)
    }

    @Test
    fun `6 - servicio no configurado reporta Unavailable y NotAvailable manteniendo NoOpLucyVisionPort`() = runTest {
        val foto = crearImagenPlanillaTemporal("planilla_noconfig.jpg")
        val gwNoConfig = FakeVisionGateway(
            status = LucyVisionGatewayStatus(
                isConfigured = false,
                isNetworkAvailable = true,
                activeModelName = "gemini-3.8-flash",
                statusMessage = "Falta google-services.json o inicialización Firebase."
            ),
            nextResult = LucyVisionRawGatewayResult.Success("{}", "gemini-3.8-flash")
        )
        val port = FirebaseLucyVisionPort(repository, gwNoConfig)

        val health = port.healthCheck()
        assertTrue(health is LucyModuleHealth.Unavailable)

        val outcome = port.analyzePlanilla(foto.absolutePath, Turno.A)
        assertTrue(outcome is LucyVisionOutcome.NotAvailable)

        assertEquals(LucyModuleHealth.NotConfigured, NoOpLucyVisionPort.healthCheck())
        assertTrue(NoOpLucyVisionPort.analyzePlanilla(foto.absolutePath, Turno.A) is LucyVisionOutcome.NotAvailable)
    }

    @Test
    fun `7 - rechaza respuestas invalidas o corruptas del modelo sin lanzar excepciones`() = runTest {
        val foto = crearImagenPlanillaTemporal("planilla_invalida.jpg")

        val casosInvalidos = listOf(
            "Texto libre sin JSON",
            "{ json roto",
            """{"isReadable": true}""",
            """{"isReadable": true, "legibilityStatus": "INVENTADO", "rows": []}""",
            """{"isReadable": true, "legibilityStatus": "LEGIBLE", "rows": [{"rawText": "X", "detectedName": "X", "interpretedMarkingStatus": "OTRO", "confidence": 0.9}]}""",
            """{"isReadable": true, "legibilityStatus": "LEGIBLE", "rows": [{"rawText": "X", "detectedName": "X", "interpretedMarkingStatus": "SI_BAJO", "confidence": "alta"}]}"""
        )

        for (raw in casosInvalidos) {
            val parse = LucyVisionResponseParser.parseStructuredJson(raw)
            assertTrue("Debió rechazar: $raw", parse is com.example.lucy.vision.LucyVisionJsonParseResult.Invalid)

            val port = crearPortConJsonFake(raw)
            val analisis = port.analizarPlanillaDetallada(foto.absolutePath, Turno.A)
            assertEquals(LucyVisionDocumentStatus.RESPUESTA_INVALIDA, analisis.estadoDocumento)
            assertFalse(analisis.esDocumentoOperativoValido)
        }
    }

    @Test
    fun `8 - integracion con LucyCoreCoordinator interpreta resultados y jamas modifica trabajadores ni marcaciones`() = runTest {
        val trabajador = repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.SOMACOR,
            nombre = "LUIS",
            primerApellido = "VERA",
            segundoApellido = "TORO",
            habitacion = "112",
            pabellon = "P1"
        )
        val totalAntes = repository.observeTrabajadoresConEstado(Turno.A).first()
        assertEquals(1, totalAntes.size)
        assertFalse(totalAntes.first().siBajo)

        val foto = crearImagenPlanillaTemporal("planilla_core.jpg")
        val json = """
            {
              "isReadable": true,
              "legibilityStatus": "LEGIBLE",
              "detectedCompanyText": "Somacor",
              "documentNotes": [],
              "rows": [
                {
                  "rowNumber": 1,
                  "rawText": "VERA TORO LUIS [SÍ]",
                  "detectedName": "VERA TORO LUIS",
                  "detectedCompanyText": null,
                  "detectedPavilion": "P1",
                  "detectedRoom": "112",
                  "visualMarkingText": "SÍ",
                  "interpretedMarkingStatus": "SI_BAJO",
                  "confidence": 0.95,
                  "ambiguityNotes": [],
                  "isIncompleteOrIllegible": false
                }
              ]
            }
        """.trimIndent()

        val coordinator = LucyCoreCoordinator(
            repository = repository,
            visionPort = crearPortConJsonFake(json)
        )
        try {
            val respuesta = coordinator.analizarPlanillaConLucyVision(
                imagePath = foto.absolutePath,
                contexto = LucyTurnContext(turnoActivo = Turno.A, empresaActiva = EmpresaOficial.SOMACOR)
            )

            assertEquals(LucyExecutionMode.HYBRID_CLOUD_OPTIONAL, respuesta.modoEjecucion)
            assertNotNull(respuesta.visionAnalysis)
            assertEquals(LucyVisionDocumentStatus.LEGIBLE, respuesta.visionAnalysis?.estadoDocumento)
            assertEquals(1, respuesta.visionAnalysis?.trabajadoresDetectados?.size)
            assertEquals(LucyModuleHealth.Ready, coordinator.saludModulos.value[LucyModuleId.VISION])
            assertTrue(respuesta.texto.contains("VERA TORO LUIS"))
            assertNull(respuesta.confirmacionRequerida)

            // Seguridad operativa estricta: Room NO debe sufrir inserciones, actualizaciones ni marcaciones
            val totalDespues = repository.observeTrabajadoresConEstado(Turno.A).first()
            assertEquals(1, totalDespues.size)
            assertEquals(trabajador.id, totalDespues.first().id)
            assertFalse("Lucy Vision jamás debe marcar SÍ BAJÓ automáticamente", totalDespues.first().siBajo)
            val recepciones = repository.observeRecepcionesSesionAbierta(Turno.A).first()
            assertTrue("Lucy Vision no debe escribir en registros_recepcion", recepciones.isEmpty())
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun `9 - LucyCoreCoordinator aisla UnsatisfiedLinkError en Vision y relanza VirtualMachineError`() = runTest {
        val portErrorNativo = object : LucyVisionPort {
            override val providerName: String = "Fallo Nativo Simulado"
            override suspend fun healthCheck(): LucyModuleHealth = LucyModuleHealth.Ready
            override suspend fun extractPlanillaLines(imagePath: String): LucyVisionOutcome {
                throw UnsatisfiedLinkError("libvision_fake.so missing")
            }
            override suspend fun analyzePlanilla(
                imagePath: String,
                turnoContexto: Turno,
                empresaSugerida: EmpresaOficial?
            ): LucyVisionOutcome {
                throw UnsatisfiedLinkError("libvision_fake.so missing")
            }
        }
        val coordinator = LucyCoreCoordinator(repository = repository, visionPort = portErrorNativo)
        try {
            val res = coordinator.analizarPlanillaConLucyVision("fake.jpg", LucyTurnContext(Turno.A))
            assertTrue(res.esErrorRecuperable)
            assertTrue(coordinator.saludModulos.value[LucyModuleId.VISION] is LucyModuleHealth.Unavailable)
        } finally {
            coordinator.shutdown()
        }

        val portVme = object : LucyVisionPort {
            override val providerName: String = "Fallo Fatal Simulado"
            override suspend fun healthCheck(): LucyModuleHealth = LucyModuleHealth.Ready
            override suspend fun extractPlanillaLines(imagePath: String): LucyVisionOutcome =
                LucyVisionOutcome.NotAvailable("N/A")
            override suspend fun analyzePlanilla(
                imagePath: String,
                turnoContexto: Turno,
                empresaSugerida: EmpresaOficial?
            ): LucyVisionOutcome {
                throw OutOfMemoryError("fatal jvm error")
            }
        }
        val coordFatal = LucyCoreCoordinator(repository = repository, visionPort = portVme)
        var relanzado = false
        try {
            coordFatal.analizarPlanillaConLucyVision("fake.jpg", LucyTurnContext(Turno.A))
        } catch (_: OutOfMemoryError) {
            relanzado = true
        } finally {
            coordFatal.shutdown()
        }
        assertTrue("VirtualMachineError debe relanzarse", relanzado)
    }

    @Test
    fun `10 - inferencia de empresa desde contexto cuando no esta escrita en la planilla`() = runTest {
        repository.agregarTrabajador(
            turno = Turno.A,
            empresa = EmpresaOficial.JOY_GLOBAL,
            nombre = "ANDRES",
            primerApellido = "SOTO",
            segundoApellido = "VERA",
            habitacion = "218",
            pabellon = "B4"
        )
        val foto = crearImagenPlanillaTemporal("planilla_sin_membrete.jpg")
        val jsonSinEmpresa = """
            {
              "isReadable": true,
              "legibilityStatus": "LEGIBLE",
              "detectedCompanyText": null,
              "documentNotes": [],
              "rows": [
                {
                  "rowNumber": 1,
                  "rawText": "SOTO VERA ANDRES",
                  "detectedName": "SOTO VERA ANDRES",
                  "detectedCompanyText": null,
                  "detectedPavilion": null,
                  "detectedRoom": null,
                  "visualMarkingText": "tic",
                  "interpretedMarkingStatus": "SI_BAJO",
                  "confidence": 0.90,
                  "ambiguityNotes": [],
                  "isIncompleteOrIllegible": false
                }
              ]
            }
        """.trimIndent()

        val port = crearPortConJsonFake(jsonSinEmpresa)
        val analisis = port.analizarPlanillaDetallada(
            imagePath = foto.absolutePath,
            turnoContexto = Turno.A,
            empresaSugerida = EmpresaOficial.JOY_GLOBAL
        )

        val det = analisis.trabajadoresDetectados.single()
        assertNull(det.datosReconocidos.empresaEscritaEnImagen)
        assertEquals(EmpresaOficial.JOY_GLOBAL, det.empresaProbable)
        assertEquals(LucyVisionDataOrigin.INFERIDO_POR_CONTEXTO, det.origenEmpresa)
        assertTrue(det.observacionesYDudas.any { "contexto" in it.lowercase() })
    }

    private fun crearPortConJsonFake(rawJson: String): FirebaseLucyVisionPort {
        val gateway = FakeVisionGateway(
            status = LucyVisionGatewayStatus(
                isConfigured = true,
                isNetworkAvailable = true,
                activeModelName = "gemini-3.8-flash",
                statusMessage = "OK"
            ),
            nextResult = LucyVisionRawGatewayResult.Success(rawJson, "gemini-3.8-flash")
        )
        return FirebaseLucyVisionPort(repository = repository, gateway = gateway)
    }

    private fun crearImagenPlanillaTemporal(
        nombreArchivo: String,
        width: Int = 800,
        height: Int = 600
    ): File {
        val file = File(context.cacheDir, "${System.currentTimeMillis()}_$nombreArchivo")
        tempFiles.add(file)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLUE
            textSize = 36f
        }
        canvas.drawText("PLANILLA CONTROL", 40f, 80f, paint)
        canvas.drawText("1. ROJAS DIAZ JUAN", 40f, 160f, paint)
        file.outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bmp.recycle()
        return file
    }

    private class FakeVisionGateway(
        private val status: LucyVisionGatewayStatus,
        private val nextResult: LucyVisionRawGatewayResult
    ) : LucyVisionModelGateway {
        override val providerName: String = "Firebase AI Logic (${status.activeModelName})"
        var lastPayload: LucyVisionImagePayload? = null
        var lastRequest: LucyVisionRequestContext? = null

        override suspend fun checkStatus(): LucyVisionGatewayStatus = status

        override suspend fun analyzeSheetImage(
            payload: LucyVisionImagePayload,
            request: LucyVisionRequestContext
        ): LucyVisionRawGatewayResult {
            lastPayload = payload
            lastRequest = request
            return nextResult
        }
    }
}
