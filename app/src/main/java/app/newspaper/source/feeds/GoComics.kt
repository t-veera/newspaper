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

    suspend fun imageUrl(stripId: String, date: LocalDate): String = withContext(Dispatchers.Main) {
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
            found
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
        private const val TIMEOUT_MS = 30_000L
        val DOMAINS = setOf("gocomics.com", "amuniversal.com")
        private val pageHosts = DOMAINS + "challenges.cloudflare.com"

        fun isAllowed(url: String): Boolean {
            val u = runCatching { java.net.URL(url) }.getOrNull() ?: return false
            return u.protocol == "https" && pageHosts.any { u.host == it || u.host.endsWith(".$it") }
        }

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
