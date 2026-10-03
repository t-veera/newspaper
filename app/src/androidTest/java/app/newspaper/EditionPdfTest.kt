package app.newspaper

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.newspaper.model.Edition
import app.newspaper.model.Masthead
import app.newspaper.render.EditionPdfRenderer
import app.newspaper.render.RenderResult
import app.newspaper.ui.MainActivity
import app.newspaper.ui.SampleEdition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditionPdfTest {

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After
    fun close() = scenario.close()

    private fun sample(): RenderResult = runBlocking { SampleEdition.generate(activity) }

    @Test
    fun sampleEditionIsTwoA4PagesInUnderTenSeconds() {
        val result = sample()
        assertTrue("took ${result.elapsedMs} ms", result.elapsedMs < 10_000)
        withPdf(result.pdf) { pdf ->
            assertEquals(2, pdf.pageCount)
            for (i in 0 until pdf.pageCount) pdf.openPage(i).use { page ->
                // A4 is 595.28 x 841.89 pt; PdfRenderer reports whole points.
                assertTrue("page ${i + 1} is ${page.width}x${page.height}", EditionPdfRenderer.isA4(page.width, page.height))
            }
        }
        assertEquals("warnings: ${result.warnings}", emptyList<String>(), result.warnings)
    }

    @Test
    fun leftAndRightMarginsMatchWithinOneMillimetre() {
        val result = sample()
        val dpi = 150
        val pxPerMm = dpi / 25.4
        withPdf(result.pdf) { pdf ->
            for (i in 0 until pdf.pageCount) pdf.openPage(i).use { page ->
                val bmp = Bitmap.createBitmap(page.width * dpi / 72, page.height * dpi / 72, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                val (left, right) = inkBounds(bmp)
                val leftMm = left / pxPerMm
                val rightMm = (bmp.width - 1 - right) / pxPerMm
                assertTrue("page ${i + 1}: left %.2f mm, right %.2f mm".format(leftMm, rightMm),
                    kotlin.math.abs(leftMm - rightMm) <= 1.0)
                bmp.recycle()
            }
        }
    }

    @Test
    fun pageOneNewsReachesBottomWithoutClipping() {
        val layout = sample().layout
        assertFalse("news clipped", layout.newsClipped)
        val gap = layout.newsGapMm
        assertNotNull(gap)
        assertTrue("news ends %.2f mm above the bottom padding line".format(gap), gap!! in -0.1..2.0)
        layout.pages.forEachIndexed { i, p -> assertTrue("page ${i + 1} overflows ${p.overflowMm} mm", p.overflowMm < 0.5) }
    }

    @Test
    fun emptyEditionRendersTwoPages() {
        val empty = Edition(Masthead(name = "The First Light", date = "2026-10-03", volume = 1, number = 276))
        val out = File(activity.cacheDir, "empty-edition.pdf")
        val result = runBlocking { EditionPdfRenderer(activity).render(empty, out) }
        assertEquals(2, result.pageCount)
        assertTrue(result.layout.ok)
        out.delete()
    }

    private fun withPdf(file: File, block: (PdfRenderer) -> Unit) =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use(block) }

    /** Leftmost and rightmost columns holding any pixel noticeably darker than white paper. */
    private fun inkBounds(bmp: Bitmap): Pair<Int, Int> {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var left = w
        var right = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (lum < 200) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        assertTrue("page is blank", right >= left)
        return left to right
    }
}
