package app.newspaper.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, message: String) : IOException(message)

/**
 * The app's only network client. HTTPS only, hosts must be on [allowedDomains] (a domain also
 * allows its subdomains), redirects are followed by hand so each hop is checked, and responses
 * are capped. Never logs URLs with query data or response bodies.
 */
class Http(
    private val allowedDomains: Set<String>,
    private val maxBytes: Int = 2 * 1024 * 1024,
    private val timeoutMs: Int = 12_000,
) {

    fun isAllowed(url: URL): Boolean =
        url.protocol == "https" && allowedDomains.any { d -> url.host == d || url.host.endsWith(".$d") }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        request("GET", url, headers, null)

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        get(url, headers).decodeToString()

    suspend fun post(url: String, body: ByteArray, headers: Map<String, String>): ByteArray =
        request("POST", url, headers, body)

    private suspend fun request(method: String, start: String, headers: Map<String, String>, body: ByteArray?): ByteArray =
        withContext(Dispatchers.IO) {
            var url = URL(start)
            repeat(MAX_REDIRECTS + 1) {
                if (!isAllowed(url)) throw IOException("Blocked host ${url.host}")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.requestMethod = method
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    if (body != null) {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body) }
                    }
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location") ?: throw HttpException(code, "Redirect without Location")
                        url = URL(url, location)
                        return@repeat
                    }
                    if (code !in 200..299) {
                        val detail = conn.errorStream?.use { readPrefix(it, 300) }.orEmpty()
                        throw HttpException(code, "HTTP $code from ${url.host} $detail".trim())
                    }
                    return@withContext conn.inputStream.use { readCapped(it, maxBytes) }
                } finally {
                    conn.disconnect()
                }
            }
            throw IOException("Too many redirects")
        }

    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > cap) throw IOException("Response larger than ${cap / 1024} KB")
        }
        return out.toByteArray()
    }

    private fun readPrefix(input: java.io.InputStream, n: Int): String {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val k = input.read(buf, read, n - read)
            if (k < 0) break
            read += k
        }
        return buf.copyOf(read).decodeToString()
    }

    companion object {
        const val USER_AGENT = "TheFirstLight/0.3 (personal printed newspaper; Android)"
        private const val MAX_REDIRECTS = 4
    }
}
