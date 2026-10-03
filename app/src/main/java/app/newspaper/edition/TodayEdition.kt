package app.newspaper.edition

import android.app.Activity
import android.content.Context
import app.newspaper.render.EditionPdfRenderer
import app.newspaper.render.RenderResult
import app.newspaper.settings.SettingsStore
import app.newspaper.source.EditionBuilder
import app.newspaper.ui.SampleEdition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Today's edition from the real sources; used by the Generate button and the daily alarm. */
object TodayEdition {

    /** Editions are private data: keep only the last week in app storage. */
    private const val KEEP_DAYS = 7L

    fun directory(context: Context) = File(context.applicationContext.filesDir, "editions")

    fun file(context: Context, date: LocalDate) = File(directory(context), "first-light-$date.pdf")

    fun latest(context: Context): File? =
        directory(context).listFiles { f -> f.name.startsWith("first-light-") && f.name.endsWith(".pdf") }
            ?.maxByOrNull { it.name }

    /** With an [activity] the WebView is attached to its window; without one it renders detached. */
    suspend fun generate(context: Context, activity: Activity? = null): RenderResult {
        val app = context.applicationContext
        val settings = SettingsStore(app).load()
        val fixture = withContext(Dispatchers.IO) { SampleEdition.load(app) }
        val now = LocalDateTime.now()
        val assembled = EditionBuilder(app, settings, fixture).build(now)
        val renderer = if (activity != null) EditionPdfRenderer(activity) else EditionPdfRenderer(app)
        val result = renderer.render(assembled.edition, file(app, now.toLocalDate()))
        withContext(Dispatchers.IO) { prune(app, now.toLocalDate()) }
        return RenderResult(result.pdf, result.layout, result.pageCount, result.elapsedMs, assembled.warnings + result.warnings)
    }

    private fun prune(context: Context, today: LocalDate) {
        val oldest = "first-light-${today.minusDays(KEEP_DAYS - 1)}.pdf"
        directory(context).listFiles()?.forEach { f ->
            if (f.name.startsWith("first-light-") && f.name < oldest) f.delete()
            if (f.name.endsWith(".part")) f.delete()
        }
    }
}
