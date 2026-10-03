package app.newspaper.ui

import android.app.Activity
import android.content.Context
import app.newspaper.model.Edition
import app.newspaper.model.EditionJson
import app.newspaper.render.EditionPdfRenderer
import app.newspaper.render.RenderResult
import app.newspaper.source.EditionAssembler
import app.newspaper.source.FixtureSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Milestone 1 pipeline: fixture -> sources -> assembled edition -> PDF in app-private storage. */
object SampleEdition {

    const val FIXTURE_ASSET = "sample_edition.json"

    fun load(context: Context): Edition = context.assets.open(FIXTURE_ASSET).use {
        EditionJson.decodeFromString(Edition.serializer(), it.readBytes().decodeToString())
    }

    fun outputFile(context: Context, date: String) = File(context.cacheDir, "sample-edition-$date.pdf")

    suspend fun generate(activity: Activity): RenderResult {
        val fixture = withContext(Dispatchers.IO) { load(activity) }
        val date = LocalDate.parse(fixture.masthead.date)
        val assembled = EditionAssembler(FixtureSource(fixture).asSources())
            .assemble(date, fixture.masthead, fixture.footer)
        val result = EditionPdfRenderer(activity).render(assembled.edition, outputFile(activity, date.toString()))
        return RenderResult(result.pdf, result.layout, result.pageCount, result.elapsedMs,
            assembled.warnings + result.warnings)
    }
}
