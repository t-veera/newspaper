package app.newspaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.newspaper.source.feeds.ComicFeed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Live network check: today's Calvin and Hobbes arrives as an image data URI. */
@RunWith(AndroidJUnit4::class)
class GoComicsTest {
    @Test
    fun fetchesCalvinAndHobbes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val http = app.newspaper.net.Http(app.newspaper.source.feeds.Comics.DOMAINS)
        val comic = runBlocking { ComicFeed(context, http, "gocomics:calvinandhobbes").comic(LocalDate.now()) }
        assertTrue(comic.imageDataUri!!.startsWith("data:image/"))
        android.util.Log.i("GoComicsTest", "image data URI ${comic.imageDataUri!!.length} chars")
    }
}
