package app.newspaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.newspaper.edition.TodayEdition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live end-to-end run with the phone's real settings and feeds (network). Logs only counts and
 * warning texts, never edition content. Writes today's edition like the Generate button does.
 */
@RunWith(AndroidJUnit4::class)
class LiveEditionTest {
    @Test
    fun generatesTodaysEdition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val result = runBlocking { TodayEdition.generate(context) }
        android.util.Log.i("LiveEditionTest", "pages=${result.pageCount} ms=${result.elapsedMs} warnings=${result.warnings}")
        assertTrue(result.pageCount >= 2)
    }
}
