package app.newspaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.newspaper.render.EditionPdfRenderer
import app.newspaper.source.EditionAssembler
import app.newspaper.source.FixtureSource
import app.newspaper.ui.SampleEdition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Scheduled editions render with no activity: a detached WebView on the application context. */
@RunWith(AndroidJUnit4::class)
class BackgroundRenderTest {

    @Test
    fun rendersWithoutAnActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val fixture = SampleEdition.load(context)
        val out = File(context.cacheDir, "background-edition.pdf")
        val result = runBlocking {
            val assembled = EditionAssembler(FixtureSource(fixture).asSources())
                .assemble(LocalDate.parse(fixture.masthead.date), fixture.masthead, fixture.footer)
            EditionPdfRenderer(context).render(assembled.edition, out)
        }
        assertEquals(2, result.pageCount)
        assertEquals("warnings: ${result.warnings}", emptyList<String>(), result.warnings)
        assertTrue("took ${result.elapsedMs} ms", result.elapsedMs < 10_000)
        out.delete()
    }
}
