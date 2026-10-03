package app.newspaper.source.garmin

import app.newspaper.model.Health
import app.newspaper.net.Http
import app.newspaper.net.HttpException
import app.newspaper.net.SecretStore
import app.newspaper.source.HealthSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Long-lived Garmin credentials (about a year). Stored encrypted; the password is never stored. */
@Serializable
data class GarminTokens(val token: String, val secret: String, val mfaToken: String? = null, val displayName: String? = null)

class GarminSignInNeeded(message: String) : IOException(message)

/**
 * Garmin Connect via the mobile app's token flow (as the garth library does):
 * SSO ticket -> OAuth1 token (long-lived) -> OAuth2 access token (short-lived) -> connectapi.
 */
class GarminClient(private val http: Http) {

    @Serializable private data class Consumer(val consumer_key: String, val consumer_secret: String)
    @Serializable private data class OAuth2(val access_token: String)

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun consumer(): Consumer =
        json.decodeFromString(Consumer.serializer(), http.getText(CONSUMER_URL))

    /** Trades the one-time SSO ticket from the sign-in page for long-lived tokens. */
    suspend fun exchangeTicket(ticket: String): GarminTokens {
        val c = consumer()
        val url = "$API/oauth-service/oauth/preauthorized?ticket=${enc(ticket)}&login-url=${enc(SSO_EMBED)}&accepts-mfa-tokens=true"
        val auth = OAuth1.header("GET", url, c.consumer_key, c.consumer_secret)
        val form = OAuth1.parseForm(http.getText(url, mapOf("Authorization" to auth, "User-Agent" to OAUTH_UA)))
        val tokens = GarminTokens(
            token = form["oauth_token"] ?: throw IOException("Garmin sent no token"),
            secret = form["oauth_token_secret"] ?: throw IOException("Garmin sent no token secret"),
            mfaToken = form["mfa_token"],
        )
        val access = accessToken(tokens)
        val profile = json.parseToJsonElement(get(access, "/userprofile-service/socialProfile")).jsonObject
        return tokens.copy(displayName = profile["displayName"]?.jsonPrimitive?.content)
    }

    /** A fresh short-lived bearer token from the long-lived OAuth1 token. */
    suspend fun accessToken(t: GarminTokens): String {
        val c = consumer()
        val url = "$API/oauth-service/oauth/exchange/user/2.0"
        val form = listOfNotNull(t.mfaToken?.let { "mfa_token" to it })
        val auth = OAuth1.header("POST", url, c.consumer_key, c.consumer_secret, t.token, t.secret, form)
        val body = form.joinToString("&") { "${OAuth1.encode(it.first)}=${OAuth1.encode(it.second)}" }.toByteArray()
        val raw = try {
            http.post(url, body, mapOf("Authorization" to auth, "User-Agent" to OAUTH_UA,
                "Content-Type" to "application/x-www-form-urlencoded"))
        } catch (e: HttpException) {
            if (e.code == 401 || e.code == 403) throw GarminSignInNeeded("Garmin sign-in expired; sign in again in Settings")
            throw e
        }
        return json.decodeFromString(OAuth2.serializer(), raw.decodeToString()).access_token
    }

    suspend fun get(access: String, path: String): String =
        http.getText(API + path, mapOf("Authorization" to "Bearer $access", "User-Agent" to API_UA, "Accept" to "application/json"))

    /** Yesterday's raw documents: daily summary, heart rate and stress. */
    suspend fun day(t: GarminTokens, date: LocalDate): GarminDay {
        val access = accessToken(t)
        val name = enc(t.displayName ?: throw GarminSignInNeeded("Garmin profile missing; sign in again"))
        return GarminDay(
            summary = json.parseToJsonElement(get(access, "/usersummary-service/usersummary/daily/$name?calendarDate=$date")).jsonObject,
            heartRate = json.parseToJsonElement(get(access, "/wellness-service/wellness/dailyHeartRate/$name?date=$date")).jsonObject,
            stress = json.parseToJsonElement(get(access, "/wellness-service/wellness/dailyStress/$date")).jsonObject,
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val SSO = "https://sso.garmin.com/sso"
        const val SSO_EMBED = "$SSO/embed"
        private const val API = "https://connectapi.garmin.com"
        /** The OAuth consumer the garth library publishes for Garmin's mobile token flow. */
        private const val CONSUMER_URL = "https://thegarth.s3.amazonaws.com/oauth_consumer.json"
        private const val OAUTH_UA = "com.garmin.android.apps.connectmobile"
        private const val API_UA = "GCM-iOS-5.7.2.1"
        val DOMAINS = setOf("garmin.com", "thegarth.s3.amazonaws.com")

        /** Garmin's compact sign-in widget; on success it shows a page carrying "embed?ticket=ST-...". */
        val SIGN_IN_URL = "$SSO/signin?" + listOf(
            "id" to "gauth-widget", "embedWidget" to "true", "gauthHost" to SSO_EMBED, "service" to SSO_EMBED,
            "source" to SSO_EMBED, "redirectAfterAccountLoginUrl" to SSO_EMBED, "redirectAfterAccountCreationUrl" to SSO_EMBED,
        ).joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }

        private val ticketPattern = Regex("""embed\?ticket=(ST-[A-Za-z0-9.\-]+)""")

        /** Finds the one-time ticket in the success page's HTML or URL. */
        fun findTicket(text: String): String? = ticketPattern.find(text.replace("\\/", "/").replace("\\u003d", "="))?.groupValues?.get(1)
    }
}

class GarminDay(val summary: JsonObject, val heartRate: JsonObject, val stress: JsonObject)

/** Turns Garmin's documents into the paper's health box. Pure, unit-tested. */
object GarminRules {

    private val dayLabel = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.roundToInt() }

    /** [[epochMillis, value], ...] pairs; nulls and Garmin's negative "no data" codes are dropped. */
    fun samples(array: JsonArray?): List<Pair<Long, Int>> = array.orEmpty().mapNotNull { row ->
        val pair = row as? JsonArray ?: return@mapNotNull null
        val t = (pair.getOrNull(0) as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val v = pair.getOrNull(1)?.takeIf { it !is JsonNull }?.jsonPrimitive?.let { it.intOrNull ?: it.doubleOrNull?.roundToInt() }
        if (v == null || v < 0) null else t to v
    }

    /** Twelve 2-hour averages for [date] in [zone], starting at 00:00; null where there is no data. */
    fun twoHourly(samples: List<Pair<Long, Int>>, date: LocalDate, zone: ZoneId): List<Int?> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val buckets = List(12) { mutableListOf<Int>() }
        samples.forEach { (t, v) ->
            val i = ((t - start) / (2 * 3_600_000L)).toInt()
            if (i in 0..11) buckets[i] += v
        }
        return buckets.map { b -> if (b.isEmpty()) null else b.average().roundToInt() }
    }

    fun health(day: GarminDay, date: LocalDate, zone: ZoneId): Health {
        val hr = samples(day.heartRate["heartRateValues"] as? JsonArray)
        val stress = samples(day.stress["stressValuesArray"] as? JsonArray)
        val peak = stress.maxByOrNull { it.second }
        return Health(
            source = "Garmin",
            dateLabel = date.format(dayLabel),
            restingHr = day.summary.int("restingHeartRate") ?: day.heartRate.int("restingHeartRate"),
            avgHr = hr.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.roundToInt(),
            avgStress = day.summary.int("averageStressLevel")?.takeIf { it >= 0 } ?: day.stress.int("avgStressLevel")?.takeIf { it >= 0 },
            peakStress = peak?.second ?: day.summary.int("maxStressLevel")?.takeIf { it >= 0 },
            peakStressTime = peak?.let { Instant.ofEpochMilli(it.first).atZone(zone).format(hhmm) },
            totalKcal = day.summary.int("totalKilocalories"),
            activeKcal = day.summary.int("activeKilocalories"),
            hrTwoHourly = twoHourly(hr, date, zone),
            stressTwoHourly = twoHourly(stress, date, zone),
        )
    }
}

/** [HealthSource] for yesterday from Garmin; throws (so the box prints dashes) when not signed in or offline. */
class GarminHealthSource(
    private val secrets: SecretStore,
    private val http: Http,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : HealthSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun yesterday(date: LocalDate): Health {
        val raw = secrets.get(SecretStore.GARMIN_TOKENS) ?: throw GarminSignInNeeded("Not signed in to Garmin")
        val tokens = json.decodeFromString(GarminTokens.serializer(), raw)
        val day = date.minusDays(1)
        return GarminRules.health(GarminClient(http).day(tokens, day), day, zone)
    }
}
