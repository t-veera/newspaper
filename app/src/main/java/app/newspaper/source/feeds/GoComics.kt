package app.newspaper.source.feeds

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDate

/**
 * GoComics only serves real browsers, so its strip page is opened in a throwaway WebView.
 *
 * This is the one WebView allowed on the network (the user's explicit exception). It may only
 * load gocomics.com, its image CDN and Cloudflare's browser check; every other request (ads,
 * trackers) gets an empty response. No JavaScript interface, no file access; cookies are
 * cleared and the WebView destroyed afterwards. The newspaper renderer stays offline.
 */
class GoComicsFetcher(private val context: Context) {

    /** A strip image and the date of the page it came from. */
    data class Strip(val imageUrl: String, val date: LocalDate)

    /**
     * The strip on [date]'s page. GoComics publishes around midnight US time (mid-morning in India) and
     * redirects a date that is not out yet to its newest strip, so the returned date may be earlier.
     */
    suspend fun strip(stripId: String, date: LocalDate): Strip = withContext(Dispatchers.Main) {
        val page = "https://www.gocomics.com/$stripId/${date.year}/%02d/%02d".format(date.monthValue, date.dayOfMonth)
        val web = create()
        try {
            web.loadUrl(page)
            var found = ""
            withTimeout(TIMEOUT_MS) {
                while (found.isEmpty()) {
                    delay(1_000)
                    found = evaluate(web, FIND_IMAGE).takeIf { it.startsWith("https://") && isAllowed(it) }.orEmpty()
                }
            }
            // The page title and image arrive together; the URL is corrected to the strip's date only later.
            Strip(found, titleDate(evaluate(web, OG_TITLE)) ?: pageDate(evaluate(web, "location.href")) ?: date)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("GoComics page did not load (browser check or no strip for $date)")
        } finally {
            web.stopLoading()
            web.destroy()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true // the Cloudflare browser check needs it
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkImage = true // only the page and scripts; the strip itself is downloaded by Kotlin
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        // Offscreen (the scheduled edition) the renderer would drop to background priority and the browser check stalls.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                !isAllowed(request.url.toString())

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (isAllowed(request.url.toString())) null
                else WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        val w = (390 * context.resources.displayMetrics.density).toInt()
        val h = (844 * context.resources.displayMetrics.density).toInt()
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
    }

    private suspend fun evaluate(web: WebView, js: String): String {
        val result = kotlinx.coroutines.CompletableDeferred<String>()
        web.evaluateJavascript(js) { result.complete(it ?: "") }
        return result.await().trim('"').replace("\\u0026", "&").replace("\\/", "/")
    }

    companion object {
        private const val TIMEOUT_MS = 45_000L
        val DOMAINS = setOf("gocomics.com", "amuniversal.com")
        private val pageHosts = DOMAINS + "challenges.cloudflare.com"

        private val datedPath = Regex("/(\\d{4})/(\\d{2})/(\\d{2})(?:[/?#]|$)")

        /** The date in a strip page URL ("…/calvinandhobbes/2026/10/03"), or null. */
        fun pageDate(url: String): LocalDate? = datedPath.find(url)?.destructured?.let { (y, m, d) ->
            runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
        }

        private val titledDate = Regex("for (\\p{L}+ \\d{1,2}, \\d{4})")
        private val titleFormat = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH)

        /** The date in a strip page title ("Calvin and Hobbes by Bill Watterson for October 3, 2026 | GoComics"), or null. */
        fun titleDate(title: String): LocalDate? = titledDate.find(title)?.groupValues?.get(1)?.let {
            runCatching { LocalDate.parse(it, titleFormat) }.getOrNull()
        }

        fun isAllowed(url: String): Boolean {
            val u = runCatching { java.net.URL(url) }.getOrNull() ?: return false
            return u.protocol == "https" && pageHosts.any { u.host == it || u.host.endsWith(".$it") }
        }

        private const val OG_TITLE = """(function(){var m=document.querySelector('meta[property="og:title"]');return m&&m.content||document.title;})()"""

        /** The strip is the og:image of a dated strip page; fall back to the first strip-CDN image. */
        private const val FIND_IMAGE = """(function(){
            var m=document.querySelector('meta[property="og:image"]');
            var u=m&&m.content||'';
            if(!/featureassets|amuniversal/.test(u)){
              var i=[].slice.call(document.images).map(function(x){return x.currentSrc||x.src;})
                .filter(function(s){return /featureassets\.gocomics\.com|assets\.amuniversal\.com/.test(s);});
              u=i[0]||'';
            }
            return u;})()"""
    }
}
