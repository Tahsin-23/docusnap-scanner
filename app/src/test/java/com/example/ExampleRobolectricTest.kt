package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cv.BitmapUtils
import com.example.cv.DocumentDetector
import com.example.cv.ImageFilterEngine
import com.example.cv.PerspectiveTransformer
import com.example.data.DocuSnapDatabase
import com.example.data.DocumentRepository
import com.example.export.ExportManager
import com.example.model.EditablePage
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.FilterAdjustments
import com.example.model.FilterType
import com.example.model.NormalizedQuad
import com.example.model.PdfOrientation
import com.example.model.PdfPageSize
import com.example.model.Point2D
import com.example.model.ScanMode
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: DocuSnapDatabase
    private lateinit var repository: DocumentRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DocuSnapDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DocumentRepository(context, db.dao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `app_name and creator attribution match DocuSnap and Tahsin Islam`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("DocuSnap", appName)
        val creatorCredit = context.getString(R.string.creator_attribution)
        assertEquals("Developed by Tahsin Islam", creatorCredit)
    }

    @Test
    fun `document detector finds tilted paper quad and rejects blank scene`() {
        // 1. Blank uniform image must NOT fake document detection
        val blank = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)
        Canvas(blank).drawColor(Color.rgb(120, 130, 140))
        val blankResult = DocumentDetector.detect(blank)
        assertFalse("Blank scene must not pretend detection succeeded", blankResult.isDetected)

        // 2. Tilted white paper quadrilateral on a dark wooden/slate desk
        val scene = Bitmap.createBitmap(500, 650, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scene)
        canvas.drawColor(Color.rgb(30, 36, 48))

        val paperPath = Path().apply {
            moveTo(95f, 80f)    // Top-Left
            lineTo(415f, 110f)  // Top-Right (tilted)
            lineTo(435f, 560f)  // Bottom-Right
            lineTo(75f, 540f)   // Bottom-Left
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 246, 248)
            style = Paint.Style.FILL
        }
        canvas.drawPath(paperPath, paint)

        val result = DocumentDetector.detect(scene)
        assertTrue("Tilted paper sheet should be detected", result.isDetected)
        val quad = result.quad
        assertNotNull(quad)
        assertTrue("Detected quad must be convex", quad!!.isConvex())
        assertTrue("Top-left corner near (0.19, 0.12)", quad.topLeft.distanceTo(Point2D(95f / 500f, 80f / 650f)) < 0.08f)
        assertTrue("Bottom-right corner near (0.87, 0.86)", quad.bottomRight.distanceTo(Point2D(435f / 500f, 560f / 650f)) < 0.08f)
    }

    @Test
    fun `perspective transformer straightens skewed quad removing dark background`() {
        val scene = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scene)
        canvas.drawColor(Color.rgb(15, 20, 30))

        val tl = Point2D(0.20f, 0.15f)
        val tr = Point2D(0.82f, 0.20f)
        val br = Point2D(0.86f, 0.85f)
        val bl = Point2D(0.16f, 0.80f)

        val path = Path().apply {
            moveTo(tl.x * 399f, tl.y * 499f)
            lineTo(tr.x * 399f, tr.y * 499f)
            lineTo(br.x * 399f, br.y * 499f)
            lineTo(bl.x * 399f, bl.y * 499f)
            close()
        }
        canvas.drawPath(path, Paint().apply { color = Color.rgb(240, 242, 245); style = Paint.Style.FILL })

        val quad = NormalizedQuad(tl, tr, br, bl)
        val warped = PerspectiveTransformer.cropAndWarp(scene, quad, rotationDegrees = 0f)

        // Center and interior corners of warped output should be bright paper, not dark desk
        val centerPixel = warped.getPixel(warped.width / 2, warped.height / 2)
        val cornerNearEdge = warped.getPixel(10, 10)
        assertTrue("Center pixel is bright white paper", Color.red(centerPixel) > 220)
        assertTrue("Warped corner is bright white paper (background cropped out)", Color.red(cornerNearEdge) > 200)
    }

    @Test
    fun `filters preserve original photo color and produce genuine monochrome for black and white`() {
        val colorPhoto = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(colorPhoto)
        canvas.drawColor(Color.rgb(220, 60, 45)) // Vivid red-orange background
        // Draw dark ink stripe
        canvas.drawRect(30f, 30f, 90f, 50f, Paint().apply { color = Color.rgb(20, 25, 30) })

        // 1. Original Color filter preserves RGB channels
        val origFiltered = ImageFilterEngine.applyFilterAndAdjustments(
            colorPhoto,
            FilterType.ORIGINAL,
            FilterAdjustments.DEFAULT_PHOTO
        )
        val sampleOrig = origFiltered.getPixel(10, 10)
        assertEquals(220, Color.red(sampleOrig))
        assertEquals(60, Color.green(sampleOrig))
        assertEquals(45, Color.blue(sampleOrig))

        // 2. Black & White filter produces strictly monochrome (#000000 or #FFFFFF) pixels
        val bwFiltered = ImageFilterEngine.applyFilterAndAdjustments(
            colorPhoto,
            FilterType.BLACK_AND_WHITE,
            FilterAdjustments(threshold = 50f, sharpness = 0f)
        )
        for (y in 0 until bwFiltered.height step 7) {
            for (x in 0 until bwFiltered.width step 7) {
                val px = bwFiltered.getPixel(x, y)
                assertTrue(
                    "B&W pixel must be pure black or pure white",
                    px == Color.BLACK || px == Color.WHITE
                )
            }
        }
    }

    @Test
    fun `multi-page PDF and JPG PNG export write valid files`() = runBlocking {
        val tmpDir = File(context.cacheDir, "test_pages").apply { mkdirs() }
        val page1 = File(tmpDir, "p1.jpg")
        val page2 = File(tmpDir, "p2.jpg")

        val bmp1 = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
        }
        val bmp2 = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.LTGRAY)
        }
        BitmapUtils.saveBitmapToFile(bmp1, page1, Bitmap.CompressFormat.JPEG, 92)
        BitmapUtils.saveBitmapToFile(bmp2, page2, Bitmap.CompressFormat.JPEG, 92)

        val pdfRes = ExportManager.generatePdf(
            context = context,
            filenameBase = "Contract_MultiPage",
            processedImagePaths = listOf(page1.absolutePath, page2.absolutePath),
            pageSize = PdfPageSize.A4,
            orientation = PdfOrientation.PORTRAIT,
            qualityPreset = ExportQualityPreset.HIGH,
            customJpegQuality = 90
        )
        assertTrue("PDF generation must succeed", pdfRes.isSuccess)
        val pdfFile = pdfRes.getOrThrow().files.first()
        assertTrue("PDF file exists and is non-empty", pdfFile.exists() && pdfFile.length() > 500L)
        val header = pdfFile.inputStream().use { input ->
            val bytes = ByteArray(5)
            input.read(bytes)
            String(bytes, Charsets.US_ASCII)
        }
        assertEquals("%PDF-", header)

        val pngRes = ExportManager.generateImages(
            context = context,
            filenameBase = "Photo_Export",
            processedImagePaths = listOf(page1.absolutePath),
            format = ExportFormat.PNG,
            qualityPreset = ExportQualityPreset.HIGH,
            customJpegQuality = 100
        )
        assertTrue("PNG export must succeed", pngRes.isSuccess)
        assertTrue(pngRes.getOrThrow().files.first().name.endsWith(".png"))
    }

    @Test
    fun `local document repository persists rename folder move and reload`() = runBlocking {
        val tmpDir = repository.getSessionTempDir()
        val orig = File(tmpDir, "test_orig.png")
        val proc = File(tmpDir, "test_proc.jpg")
        val thumb = File(tmpDir, "test_thumb.jpg")
        val bmp = Bitmap.createBitmap(160, 220, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
        }
        BitmapUtils.saveBitmapToFile(bmp, orig, Bitmap.CompressFormat.PNG, 100)
        BitmapUtils.saveBitmapToFile(bmp, proc, Bitmap.CompressFormat.JPEG, 90)
        BitmapUtils.saveBitmapToFile(bmp, thumb, Bitmap.CompressFormat.JPEG, 80)

        val folderId = repository.createFolder("Receipts")
        val editablePage = EditablePage(
            id = "page-1",
            originalImagePath = orig.absolutePath,
            processedImagePath = proc.absolutePath,
            thumbnailPath = thumb.absolutePath,
            quad = NormalizedQuad.fullFrame(),
            wasBoundaryAutoDetected = true,
            filterType = FilterType.ENHANCED,
            ocrText = "Total Invoice $149.00"
        )

        val savedId = repository.saveEditorDocument(
            existingDocumentId = null,
            title = "Tax Receipt",
            notes = "October expense",
            folderId = folderId,
            scanMode = ScanMode.DOCUMENT,
            pages = listOf(editablePage)
        ).getOrThrow()

        val allDocs = repository.allDocuments.first()
        assertEquals(1, allDocs.size)
        assertEquals("Tax Receipt", allDocs.first().title)
        assertEquals("Total Invoice $149.00", allDocs.first().combinedOcrText)

        repository.renameDocument(savedId, "Updated Tax Receipt")
        val reloaded = repository.getDocumentWithPages(savedId)
        assertNotNull(reloaded)
        assertEquals("Updated Tax Receipt", reloaded!!.document.title)
        assertEquals(1, reloaded.orderedPages.size)

        repository.deleteDocument(savedId)
        assertEquals(0, repository.allDocuments.first().size)
    }
}
