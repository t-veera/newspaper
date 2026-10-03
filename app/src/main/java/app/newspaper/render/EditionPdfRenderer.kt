package app.newspaper.render

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.print.PrintAdapterDriver
import android.print.PrintAttributes
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import app.newspaper.model.Edition
import app.newspaper.model.EditionJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File

/** What render.js measured after fitting the layout. */
@Serializable
data class LayoutReport(
    val ok: Boolean,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val titlePt: Double? = null,
    val newsPt: Double? = null,
    val newsLeading: Double? = null,
    /** Distance from the last line of page-1 news to the bottom padding line. */
    val newsGapMm: Double? = null,
    val newsClipped: Boolean = false,
    val pages: List<PageReport> = emptyList(),
    /** 2, or more when messages flow onto extra pages. */
    val pageCount: Int = 2,
    val renderMs: Long? = null,
)

@Serializable
data class PageReport(val widthMm: Double, val heightMm: Double, val overflowMm: Double)

class RenderResult(
    val pdf: File,
    val layout: LayoutReport,
    val pageCount: Int,
    val elapsedMs: Long,
    /** Layout warnings plus PDF checks. Empty means the edition fits exactly. */
    val warnings: List<String>,
)

class RenderException(message: String) : Exception(message)

/**
 * Renders an [Edition] to a 2-page A4 PDF using the bundled HTML template in an invisible
 * WebView. With a [host] (an activity's decor view) the WebView is attached behind the
 * content; without one it is measured and laid out by hand, detached from any window,
 * which is what scheduled background runs use.
 *
 * Security: JavaScript runs only on the bundled template; every request outside
 * file:///android_asset/newspaper/ is refused, network loads are blocked, and the only
 * JavaScript interface is [Bridge.onReady].
 */
class EditionPdfRenderer(private val context: Context, private val host: ViewGroup? = null) {

    constructor(activity: Activity) : this(activity, activity.window.decorView as ViewGroup)

    suspend fun render(edition: Edition, output: File): RenderResult = withContext(Dispatchers.Main) {
        val started = SystemClock.elapsedRealtime()
        val webView = createWebView()
        try {
            if (host != null) {
                host.addView(webView, 0, FrameLayout.LayoutParams(dp(VIEW_WIDTH_DP), dp(VIEW_HEIGHT_DP)))
            } else {
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(dp(VIEW_WIDTH_DP), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(VIEW_HEIGHT_DP), View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, dp(VIEW_WIDTH_DP), dp(VIEW_HEIGHT_DP))
            }
            val report = withTimeout(TIMEOUT_MS) { loadAndLayout(webView, edition) }
            if (!report.ok) throw RenderException("Template failed: ${report.error}")

            output.parentFile?.mkdirs()
            val partial = File(output.path + ".part")
            withTimeout(TIMEOUT_MS) { print(webView, partial) }
            if (!partial.renameTo(output)) throw RenderException("Could not move PDF into place")

            val warnings = report.warnings.toMutableList()
            val pageCount = checkPdf(output, report.pageCount, warnings)
            val elapsed = SystemClock.elapsedRealtime() - started
            Log.i(TAG, "Rendered $pageCount pages in $elapsed ms, ${warnings.size} warnings")
            RenderResult(output, report, pageCount, elapsed, warnings)
        } finally {
            host?.removeView(webView)
            webView.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(context).apply {
        alpha = 0f
        isFocusable = false
        isClickable = false
        importantForAccessibility = WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            blockNetworkImage = true
            allowFileAccess = false // file:///android_asset stays readable regardless
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            safeBrowsingEnabled = false // avoids a network lookup; we only load bundled files
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = 100
            setSupportZoom(false)
            useWideViewPort = false
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
    }

    private suspend fun loadAndLayout(webView: WebView, edition: Edition): LayoutReport {
        val ready = CompletableDeferred<String>()
        webView.addJavascriptInterface(Bridge(ready), "NewspaperBridge")
        webView.webViewClient = object : WebViewClient() {
            private var started = false

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (url.startsWith(ASSET_ROOT) && !url.contains("..")) return null
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (started || url != TEMPLATE_URL) return
                started = true
                val json = EditionJson.encodeToString(Edition.serializer(), edition)
                val literal = Json.encodeToString(String.serializer(), json)
                view.evaluateJavascript("window.renderEdition(JSON.parse($literal));", null)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ready.completeExceptionally(RenderException("WebView renderer crashed"))
                return true
            }
        }
        webView.loadUrl(TEMPLATE_URL)
        return reportJson.decodeFromString(LayoutReport.serializer(), ready.await())
    }

    private suspend fun print(webView: WebView, file: File) {
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        val adapter = webView.createPrintDocumentAdapter("edition")
        adapter.onStart()
        try {
            PrintAdapterDriver.layout(adapter, attributes)
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE,
            ).use { pfd ->
                if (PrintAdapterDriver.write(adapter, pfd).isEmpty()) throw RenderException("No pages written")
            }
        } finally {
            adapter.onFinish()
        }
    }

    /** The PDF itself is the source of truth: the pages the template laid out, each A4. */
    private fun checkPdf(file: File, expected: Int, warnings: MutableList<String>): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { pdf ->
                if (pdf.pageCount != expected) warnings += "PDF has ${pdf.pageCount} pages, expected $expected"
                for (i in 0 until pdf.pageCount) {
                    pdf.openPage(i).use { page ->
                        if (!isA4(page.width, page.height)) {
                            warnings += "PDF page ${i + 1} is ${page.width}x${page.height} pt, expected A4 595x842"
                        }
                    }
                }
                pdf.pageCount
            }
        }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private class Bridge(private val ready: CompletableDeferred<String>) {
        @JavascriptInterface
        fun onReady(reportJson: String) {
            ready.complete(reportJson)
        }
    }

    companion object {
        private const val TAG = "EditionPdfRenderer"
        private const val ASSET_ROOT = "file:///android_asset/newspaper/"
        private const val TEMPLATE_URL = ASSET_ROOT + "template.html"
        private const val TIMEOUT_MS = 20_000L

        // Wide enough for a 210 mm page plus its screen margins (CSS px == dp here).
        private const val VIEW_WIDTH_DP = 860
        private const val VIEW_HEIGHT_DP = 1200

        private val reportJson = Json { ignoreUnknownKeys = true }

        /** A4 is 595.28 x 841.89 pt; PdfRenderer reports whole points, so allow 1 pt. */
        fun isA4(widthPt: Int, heightPt: Int) =
            kotlin.math.abs(widthPt - 595) <= 1 && kotlin.math.abs(heightPt - 842) <= 1
    }
}
