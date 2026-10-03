package app.newspaper.source.garmin

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** OAuth 1.0a HMAC-SHA1 request signing (RFC 5849), as Garmin's token endpoints require. Pure. */
object OAuth1 {

    /** RFC 3986 percent-encoding. */
    fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    fun parseForm(s: String): Map<String, String> = s.split('&').filter { it.contains('=') }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    fun signature(method: String, url: String, params: List<Pair<String, String>>, consumerSecret: String, tokenSecret: String): String {
        val uri = URI(url)
        val base = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}${uri.rawPath}"
        val query = uri.rawQuery?.let { parseForm(it).toList() }.orEmpty()
        val normalized = (params + query).map { encode(it.first) to encode(it.second) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        val text = "${method.uppercase()}&${encode(base)}&${encode(normalized)}"
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec("${encode(consumerSecret)}&${encode(tokenSecret)}".toByteArray(), "HmacSHA1"))
        }
        return Base64.getEncoder().encodeToString(mac.doFinal(text.toByteArray()))
    }

    /** The Authorization header value. [formParams] are body parameters that take part in the signature. */
    fun header(
        method: String, url: String, consumerKey: String, consumerSecret: String,
        token: String? = null, tokenSecret: String = "", formParams: List<Pair<String, String>> = emptyList(),
        nonce: String = newNonce(), timestamp: Long = System.currentTimeMillis() / 1000,
    ): String {
        val oauth = listOfNotNull(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to timestamp.toString(),
            token?.let { "oauth_token" to it },
            "oauth_version" to "1.0",
        )
        val sig = signature(method, url, oauth + formParams, consumerSecret, tokenSecret)
        return "OAuth " + (oauth + ("oauth_signature" to sig)).sortedBy { it.first }
            .joinToString(", ") { "${encode(it.first)}=\"${encode(it.second)}\"" }
    }

    private val random = SecureRandom()
    private fun newNonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
