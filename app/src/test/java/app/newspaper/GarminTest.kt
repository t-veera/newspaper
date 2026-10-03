package app.newspaper

import app.newspaper.source.garmin.OAuth1
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.jsonObject

class GarminTest {

    @Test
    fun oauth1SignatureMatchesPublishedExample() {
        // Twitter's documented OAuth 1.0a example ("Creating a signature").
        val sig = OAuth1.signature(
            "POST", "https://api.twitter.com/1.1/statuses/update.json?include_entities=true",
            listOf(
                "status" to "Hello Ladies + Gentlemen, a signed OAuth request!",
                "oauth_consumer_key" to "xvz1evFS4wEEPTGEFPHBog",
                "oauth_nonce" to "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg",
                "oauth_signature_method" to "HMAC-SHA1",
                "oauth_timestamp" to "1318622958",
                "oauth_token" to "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
                "oauth_version" to "1.0",
            ),
            consumerSecret = "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
            tokenSecret = "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE",
        )
        assertEquals("hCtSmYh+iHYCEqBWrE7C7hYmtUk=", sig)
    }

    @Test
    fun findsTicketInSuccessPage() {
        val html = """<script>var response_url = "https:\\/\\/sso.garmin.com\\/sso\\/embed?ticket=ST-0123456-AbCdEf-cas";</script>"""
        assertEquals("ST-0123456-AbCdEf-cas", app.newspaper.source.garmin.GarminClient.findTicket(html))
        assertEquals(null, app.newspaper.source.garmin.GarminClient.findTicket("<html>Sign in</html>"))
    }

    @Test
    fun healthFromGarminDocuments() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val day = java.time.LocalDate.of(2026, 10, 2)
        val t0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val hour = 3_600_000L
        val j = kotlinx.serialization.json.Json
        val hr = "[[${t0 + hour}, 56], [${t0 + 90 * 60_000}, 58], [${t0 + 14 * hour}, null], [${t0 + 14 * hour + 600_000}, 90]]"
        val stress = "[[${t0 + 14 * hour + 300_000}, 78], [${t0 + 3 * hour}, -1], [${t0 + 9 * hour}, 30]]"
        val g = app.newspaper.source.garmin.GarminDay(
            summary = j.parseToJsonElement("""{"restingHeartRate":58,"averageStressLevel":34,"maxStressLevel":78,"totalKilocalories":2340.0,"activeKilocalories":610}""").jsonObject,
            heartRate = j.parseToJsonElement("""{"heartRateValues":$hr}""").jsonObject,
            stress = j.parseToJsonElement("""{"stressValuesArray":$stress}""").jsonObject,
        )
        val h = app.newspaper.source.garmin.GarminRules.health(g, day, zone)
        assertEquals("2 October", h.dateLabel)
        assertEquals(58, h.restingHr)
        assertEquals(68, h.avgHr) // (56 + 58 + 90) / 3
        assertEquals(78, h.peakStress)
        assertEquals("14:05", h.peakStressTime)
        assertEquals(2340, h.totalKcal)
        assertEquals(12, h.hrTwoHourly.size)
        assertEquals(57, h.hrTwoHourly[0])
        assertEquals(90, h.hrTwoHourly[7])
        assertEquals(null, h.hrTwoHourly[3])
        assertEquals(null, h.stressTwoHourly[1]) // -1 means "no data"
        assertEquals(30, h.stressTwoHourly[4])
    }
}
