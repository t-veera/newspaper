package app.newspaper.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.newspaper.net.Http
import app.newspaper.net.SecretStore
import app.newspaper.source.garmin.GarminClient
import app.newspaper.source.garmin.GarminTokens
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream

/**
 * Garmin's own sign-in page (including 2FA), shown in a WebView that may only load Garmin's sign-in
 * pages and the bot check they rely on. The app never sees the password: it only picks up the
 * one-time ticket Garmin shows on success, trades it for tokens, then clears cookies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarminSignIn(secrets: SecretStore, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var exchanging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finish(ticket: String) {
        if (exchanging) return
        exchanging = true
        scope.launch {
            try {
                val tokens = GarminClient(Http(GarminClient.DOMAINS)).exchangeTicket(ticket)
                secrets.put(SecretStore.GARMIN_TOKENS, Json.encodeToString(GarminTokens.serializer(), tokens))
                onDone(tokens.displayName ?: "Garmin")
            } catch (e: Exception) {
                error = "Couldn't finish signing in: ${e.message ?: e.javaClass.simpleName}"
                exchanging = false
            }
        }
    }

    Dialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Sign in to Garmin") },
                navigationIcon = { IconButton(onClick = { onDone(null) }) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (loading || exchanging) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                Box(Modifier.fillMaxSize()) {
                    if (exchanging) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Connecting your Garmin account…", Modifier.padding(top = 16.dp))
                        }
                    } else {
                        SignInWebView(onLoading = { loading = it }, onTicket = ::finish)
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInWebView(onLoading: (Boolean) -> Unit, onTicket: (String) -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            web?.destroy()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true // Garmin's form and bot check need it
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setGeolocationEnabled(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        GarminClient.findTicket(url)?.let { onTicket(it); return true }
                        return !allowed(url)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (allowed(request.url.toString())) null
                        else WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = onLoading(true)

                    override fun onPageFinished(view: WebView, url: String) {
                        onLoading(false)
                        GarminClient.findTicket(url)?.let { onTicket(it); return }
                        // The embedded widget shows the ticket in the success page's script, not the URL.
                        view.evaluateJavascript("document.documentElement.outerHTML") { html ->
                            GarminClient.findTicket(html ?: "")?.let(onTicket)
                        }
                    }
                }
                loadUrl(GarminClient.SIGN_IN_URL)
                web = this
            }
        },
    )
}

/** Garmin's pages and static files, Cloudflare's check, and Google's reCAPTCHA if Garmin asks for one. */
private fun allowed(url: String): Boolean {
    val u = runCatching { java.net.URL(url) }.getOrNull() ?: return false
    if (u.protocol != "https") return false
    val h = u.host
    fun under(d: String) = h == d || h.endsWith(".$d")
    return under("garmin.com") || under("garmincdn.com") || under("challenges.cloudflare.com") ||
        ((h == "www.google.com" || h == "www.gstatic.com" || h == "www.recaptcha.net") && u.path.contains("recaptcha"))
}
