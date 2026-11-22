package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LavanderiaDatabase
import com.example.data.ocr.MotorOcrManuscrito
import com.example.data.ocr.OcrImageProcessor
import com.example.data.ocr.PlanillaPreprocessor
import com.example.data.ocr.ResultadoOcrFoto
import com.example.data.ocr.ResultadoPreparacionTrabajo
import com.example.data.repository.LavanderiaRepository
import com.example.domain.model.AjusteFotoPlanilla
import com.example.domain.model.EmpresaOficial
import com.example.domain.model.OcrNombreMatcher
import com.example.domain.model.Turno
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OcrPlanillaRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: LavanderiaDatabase
    private lateinit var repository: LavanderiaRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LavanderiaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LavanderiaRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        OcrImageProcessor.limpiarTodosLosTemporales(context)
    }

    @Test
    fun `19 - diagnostico separado carga vs decodificacion, rotacion recorte y limpieza segura de temporales`() = runBlocking {
        // 1. Archivo vacío -> ErrorCarga (no enmascarado como escritura ilegible)
        val dirTemp = OcrImageProcessor.obtenerDirectorioTemporal(context)
        val archivoVacio = File(dirTemp, "vacio_test.jpg").apply { writeBytes(ByteArray(0)) }
        val prepVacio = OcrImageProcessor.prepararArchivoTrabajo(
            context = context,
            uri = Uri.fromFile(archivoVacio),
            archivoTemporalCamara = archivoVacio
        )
        assertTrue("Un archivo de 0 bytes debe reportar ErrorCarga", prepVacio is ResultadoPreparacionTrabajo.ErrorCarga)

        // 2. Archivo corrupto -> ErrorDecodificacionImagen
        val archivoCorrupto = File(dirTemp, "work_corrupto.img").apply {
            writeBytes(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55))
        }
        val resCorrupto = OcrImageProcessor.procesarArchivoTrabajo(
            context = context,
            archivoTrabajo = archivoCorrupto
        )
        assertTrue(
            "Bytes inválidos deben reportar ErrorDecodificacionImagen, fue: $resCorrupto",
            resCorrupto is ResultadoOcrFoto.ErrorDecodificacionImagen
        )

        // 3. Archivo JPEG válido con rotación 90° + recorte personalizado
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val jpgValido = File(dirTemp, "work_valido_rot.jpg")
        jpgValido.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()

        val decodificado = OcrImageProcessor.decodificarArchivoSeguro(jpgValido, 1200)
        assertNotNull(decodificado)
        val ajustado = OcrImageProcessor.aplicarRotacionYRecorte(
            src = decodificado!!,
            ajuste = AjusteFotoPlanilla(
                rotacionGrados = 90,
                cropLeft = 0.1f,
                cropTop = 0.1f,
                cropRight = 0.9f,
                cropBottom = 0.9f
            ),
            aplicarRecorte = true
        )
        // 400x200 rotado 90° pasa a 200x400; recortado al 80% central -> ~160x320
        assertTrue(ajustado.width in 155..165)
        assertTrue(ajustado.height in 315..325)
        ajustado.recycle()

        // 4. Limpieza selectiva de huérfanos conserva fotos activas del lote
        val archivoHuerfanoViejo = File(dirTemp, "work_viejo.img").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        }
        OcrImageProcessor.limpiarTemporalesHuerfanos(
            context = context,
            rutasActivas = setOf(jpgValido.absolutePath),
            edadMinimaBorradoMs = 15 * 60 * 1000L
        )
        assertTrue("El archivo activo en lote debe conservarse", jpgValido.exists())
        assertFalse("El huérfano antiguo debe limpiarse", archivoHuerfanoViejo.exists())
    }

    @Test
    fun `20 - PaddleOCR PP-OCRv5 ONNX Runtime offline real sobre planilla multi-fila con grilla, numeracion lateral, tildes y n`() = runBlocking {
        val detFile = localizarAssetOcr("ppocrv5_mobile_det.onnx")
        val recFile = localizarAssetOcr("latin_ppocrv5_mobile_rec.onnx")
        val dictFile = localizarAssetOcr("latin_dict.txt")
        assertTrue("Debe existir ppocrv5_mobile_det.onnx en assets/ocr", detFile.exists() && detFile.length() > 1_000_000L)
        assertTrue("Debe existir latin_ppocrv5_mobile_rec.onnx en assets/ocr", recFile.exists() && recFile.length() > 1_000_000L)
        assertTrue("Debe existir latin_dict.txt en assets/ocr", dictFile.exists() && dictFile.length() > 100L)

        // Preparar maestro real en Turno B Komatsu con tildes, ñ y trabajadores que NO bajan
        repository.ensureInitialized()
        val w1 = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Victor", "Pino", "", "1", "4 SUR")
        val w2 = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "José", "Nuñez", "", "10B", "23 SUR")
        val w3 = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Álvaro", "Muñoz", "", "23", "4 SUR")
        val w4 = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Pablo", "Aguilera", "", "16B", "22 SUR")
        val wAusente = repository.agregarTrabajador(Turno.B, EmpresaOficial.KOMATSU, "Luis", "Alvarez", "", "30", "4 SUR")

        // Planilla rayada con tinta azul cursiva-itálica, numeración lateral 31..34 y encabezado
        val planillaBmp = crearBitmapPlanillaTabla(
            filas = listOf(
                "31" to "PINO VICTOR",
                "32" to "NUÑEZ JOSÉ",
                "33" to "MUÑOZ ÁLVARO",
                "34" to "AGUILERA PABLO"
            )
        )

        val preprocesado = PlanillaPreprocessor.procesarPlanillaEnFilas(
            bitmapOriginal = planillaBmp,
            forzarFallbackSinOpenCv = true
        )
        assertTrue(
            "Debe segmentar al menos 4 filas independientes (obtenidas: ${preprocesado.filas.size})",
            preprocesado.filas.size >= 4
        )

        val motor = MotorOcrManuscrito.crearParaPruebas()
        motor.inicializarDesdeArchivos(detFile, recFile, dictFile)

        val resMotor = motor.procesarFilasPlanilla(preprocesado)
        planillaBmp.recycle()
        preprocesado.bitmapPreprocesado.recycle()

        val textosLeidos = resMotor.filas.filter { it.legible }.map { it.textoReconocido }
        println("PP-OCRv5 textos leídos por fila: $textosLeidos")
        assertTrue("PP-OCRv5 debe leer múltiples filas (${textosLeidos.size})", textosLeidos.size >= 4)

        val komatsuB = repository.observeTrabajadoresConEstado(Turno.B).first()
            .filter { it.empresa == EmpresaOficial.KOMATSU }
        val analisis = OcrNombreMatcher.analizarLineas(
            lineasCrudas = textosLeidos,
            trabajadoresEmpresaTurno = komatsuB,
            correccionesEmpresaTurno = emptyList()
        )
        println("AutoConfirmados=${analisis.autoConfirmados.map { it.trabajador.nombreCompleto }}, Pendientes=${analisis.pendientes.map { it.textoOcrOriginal }}")

        val idsReconocidosOEnCandidatos = buildSet {
            analisis.autoConfirmados.forEach { add(it.trabajador.id) }
            analisis.pendientes.forEach { p -> p.candidatos.firstOrNull()?.let { add(it.trabajador.id) } }
        }
        assertTrue("Debe reconocer a PINO VICTOR", w1.id in idsReconocidosOEnCandidatos)
        assertTrue("Debe reconocer a NUÑEZ JOSÉ (ñ y tilde)", w2.id in idsReconocidosOEnCandidatos)
        assertTrue("Debe reconocer a MUÑOZ ÁLVARO (ñ y tilde)", w3.id in idsReconocidosOEnCandidatos)
        assertTrue("Debe reconocer a AGUILERA PABLO", w4.id in idsReconocidosOEnCandidatos)
        assertFalse("El trabajador ausente NO debe marcarse", wAusente.id in idsReconocidosOEnCandidatos)

        // Aislamiento de fila ilegible junto a filas válidas (no descarta las filas legibles)
        val analisisConFilaIlegible = OcrNombreMatcher.analizarLineas(
            lineasCrudas = listOf("31 PINO VICTOR", "[FILA 2 ILEGIBLE]", "34 AGUILERA PABLO"),
            trabajadoresEmpresaTurno = komatsuB,
            correccionesEmpresaTurno = emptyList()
        )
        assertEquals(listOf(w1.id, w4.id), analisisConFilaIlegible.autoConfirmados.map { it.trabajador.id })
        assertEquals(1, analisisConFilaIlegible.pendientes.size)
        assertTrue(analisisConFilaIlegible.pendientes.first().textoOcrNormalizado.contains("fila 2"))
    }

    private fun localizarAssetOcr(nombreArchivo: String): File {
        val candidatos = listOf(
            File("src/main/assets/ocr/$nombreArchivo"),
            File("app/src/main/assets/ocr/$nombreArchivo")
        )
        return candidatos.firstOrNull { it.exists() } ?: candidatos.first()
    }

    private fun crearBitmapPlanillaTabla(filas: List<Pair<String, String>>): Bitmap {
        val width = 1000
        val rowH = 90
        val topHeader = 110
        val height = topHeader + (filas.size + 1) * rowH + 40
        val img = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )

            g.color = java.awt.Color(245, 246, 248)
            g.fillRect(0, 0, width, height)

            g.color = java.awt.Color(65, 70, 80)
            g.stroke = java.awt.BasicStroke(3f)

            val xNumDiv = 105
            val xRightCol = 780
            g.drawLine(30, topHeader, width - 30, topHeader)
            for (i in 0..filas.size) {
                val yLine = topHeader + (i + 1) * rowH
                g.drawLine(30, yLine, width - 30, yLine)
            }
            g.drawLine(xNumDiv, topHeader, xNumDiv, height - 40)
            g.drawLine(xRightCol, topHeader, xRightCol, height - 40)

            g.color = java.awt.Color(30, 35, 45)
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 30)
            g.drawString("CONTROL DE LAVANDERIA - KOMATSU", 120, 65)

            val textFont = java.awt.Font(java.awt.Font.SERIF, java.awt.Font.ITALIC, 42)
            val numFont = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 34)
            filas.forEachIndexed { idx, (numero, nombre) ->
                val baselineY = topHeader + idx * rowH + 60
                g.color = java.awt.Color(40, 40, 45)
                g.font = numFont
                g.drawString(numero, 45, baselineY)
                g.color = java.awt.Color(18, 45, 138)
                g.font = textFont
                g.drawString(nombre, 130, baselineY)
            }
        } finally {
            g.dispose()
        }

        val pixels = img.getRGB(0, 0, width, height, null, 0, width)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }
}
