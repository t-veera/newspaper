package app.newspaper.source.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import app.newspaper.model.HourlyForecast
import app.newspaper.model.Moon
import app.newspaper.model.Weather
import app.newspaper.model.WeatherIcon
import app.newspaper.net.FeedCache
import app.newspaper.net.Http
import app.newspaper.source.WeatherSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

@Serializable
data class Place(val name: String, val latitude: Double, val longitude: Double)

/** Pure parsing and astronomy, unit-tested on the JVM. */
object WeatherRules {

    private val json = Json { ignoreUnknownKeys = true }
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    val HOURS = listOf(6, 9, 12, 15, 18, 21)

    @Serializable private data class Forecast(val current: Current? = null, val hourly: Hourly? = null, val daily: Daily? = null)
    @Serializable private data class Current(
        val temperature_2m: Double? = null, val weather_code: Int? = null, val is_day: Int? = null,
    )
    @Serializable private data class Hourly(
        val time: List<String> = emptyList(),
        val temperature_2m: List<Double?> = emptyList(),
        val weather_code: List<Int?> = emptyList(),
        val precipitation_probability: List<Int?> = emptyList(),
    )
    @Serializable private data class Daily(
        val temperature_2m_max: List<Double?> = emptyList(),
        val temperature_2m_min: List<Double?> = emptyList(),
        val sunrise: List<String?> = emptyList(),
        val sunset: List<String?> = emptyList(),
        val uv_index_max: List<Double?> = emptyList(),
    )
    @Serializable private data class GeoResults(val results: List<GeoResult> = emptyList())
    @Serializable private data class GeoResult(val name: String, val latitude: Double, val longitude: Double)
    @Serializable private data class MoonDoc(val properties: MoonProps = MoonProps())
    @Serializable private data class MoonProps(val moonrise: TimeAt? = null)
    @Serializable private data class TimeAt(val time: String? = null)

    /** WMO weather code to icon and wording. */
    fun describe(code: Int?, isDay: Boolean = true): Pair<WeatherIcon, String> = when (code) {
        0 -> (if (isDay) WeatherIcon.SUN else WeatherIcon.MOON) to "Clear"
        1 -> (if (isDay) WeatherIcon.SUN else WeatherIcon.MOON) to "Mainly clear"
        2 -> WeatherIcon.PARTLY_CLOUDY to "Partly cloudy"
        3 -> WeatherIcon.CLOUD to "Overcast"
        45, 48 -> WeatherIcon.CLOUD to "Fog"
        51, 53, 55, 56, 57 -> WeatherIcon.RAIN to "Drizzle"
        61, 63, 65, 66, 67 -> WeatherIcon.RAIN to "Rain"
        71, 73, 75, 77, 85, 86 -> WeatherIcon.CLOUD to "Snow"
        80, 81, 82 -> WeatherIcon.RAIN to "Showers"
        95, 96, 99 -> WeatherIcon.RAIN to "Thunderstorms"
        else -> WeatherIcon.CLOUD to "Cloudy"
    }

    fun parseForecast(body: String, date: LocalDate, moon: Moon?): Weather {
        val f = json.decodeFromString(Forecast.serializer(), body)
        val (icon, condition) = describe(f.current?.weather_code, f.current?.is_day != 0)
        val hourly = f.hourly
        val hours = if (hourly == null) emptyList() else HOURS.mapNotNull { h ->
            val i = hourly.time.indexOf(date.atTime(h, 0).toString())
            if (i < 0) return@mapNotNull null
            val pct = hourly.precipitation_probability.getOrNull(i)
            HourlyForecast(
                hour = "%02d".format(h),
                icon = describe(hourly.weather_code.getOrNull(i), h in 6..17).first,
                tempC = hourly.temperature_2m.getOrNull(i),
                precipPct = pct?.takeIf { it >= 20 },
            )
        }
        val d = f.daily
        return Weather(
            tempC = f.current?.temperature_2m,
            condition = condition,
            icon = icon,
            highC = d?.temperature_2m_max?.firstOrNull(),
            lowC = d?.temperature_2m_min?.firstOrNull(),
            hourly = hours,
            sunrise = d?.sunrise?.firstOrNull()?.let(::clock),
            sunset = d?.sunset?.firstOrNull()?.let(::clock),
            uvIndex = d?.uv_index_max?.firstOrNull()?.roundToInt(),
            moon = moon,
        )
    }

    fun parsePlace(body: String): Place? =
        json.decodeFromString(GeoResults.serializer(), body).results.firstOrNull()?.let { Place(it.name, it.latitude, it.longitude) }

    /** met.no gives moonrise as "2026-10-03T23:48+05:30"; absent on days without one. */
    fun parseMoonrise(body: String): String? =
        json.decodeFromString(MoonDoc.serializer(), body).properties.moonrise?.time
            ?.let { runCatching { OffsetDateTime.parse(it).format(hhmm) }.getOrNull() }

    private fun clock(isoLocal: String) = runCatching { LocalDateTime.parse(isoLocal).format(hhmm) }.getOrNull()

    private const val SYNODIC_DAYS = 29.530588853
    private val KNOWN_NEW_MOON = Instant.parse("2000-01-06T18:14:00Z")

    /** Phase 0..1 (0 new, 0.5 full) from the mean synodic month; within a few hours of the true phase. */
    fun moonPhase(at: Instant): Double {
        val days = (at.epochSecond - KNOWN_NEW_MOON.epochSecond) / 86_400.0
        return ((days / SYNODIC_DAYS) % 1 + 1) % 1
    }

    fun moon(at: Instant, moonrise: String?): Moon {
        val p = moonPhase(at)
        val names = listOf("New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
            "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent")
        return Moon(
            phaseName = names[((p * 8) + 0.5).toInt() % 8],
            phase = (p * 1000).roundToInt() / 1000.0,
            illuminationPct = ((1 - cos(2 * PI * p)) / 2 * 100).roundToInt(),
            moonrise = moonrise,
        )
    }

    /** ~1 km precision is plenty for a forecast and keeps the exact position private. */
    fun round(v: Double) = (v * 100).roundToInt() / 100.0
}

/** Open-Meteo forecast for a typed place or the phone's coarse location. */
class WeatherFeed(
    private val context: Context,
    private val http: Http,
    private val cache: FeedCache,
    private val placeName: String,
    private val usePhoneLocation: Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : WeatherSource {

    override suspend fun weather(date: LocalDate): Weather {
        val place = place()
        val lat = WeatherRules.round(place.latitude)
        val lon = WeatherRules.round(place.longitude)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max" +
            "&timezone=auto&forecast_days=1"
        val moonrise = runCatching {
            val offset = zone.rules.getOffset(date.atStartOfDay(zone).toInstant()).id.replace("Z", "+00:00")
            WeatherRules.parseMoonrise(http.getText("https://api.met.no/weatherapi/sunrise/3.0/moon?lat=$lat&lon=$lon" +
                "&date=$date&offset=${URLEncoder.encode(offset, "UTF-8")}"))
        }.getOrNull()
        val moon = WeatherRules.moon(date.atTime(6, 0).atZone(zone).toInstant(), moonrise)
        return WeatherRules.parseForecast(http.getText(url), date, moon)
    }

    private suspend fun place(): Place {
        if (usePhoneLocation) lastLocation()?.let { return Place("Here", it.latitude, it.longitude) }
        val name = placeName.trim().ifEmpty { throw IOException("No weather location set") }
        val key = "geo-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        cache.get(key, Place.serializer())?.let { return it.value }
        val found = WeatherRules.parsePlace(http.getText("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" +
            URLEncoder.encode(name, "UTF-8"))) ?: throw IOException("Place not found: $name")
        cache.put(key, Place.serializer(), found)
        return found
    }

    private fun lastLocation(): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    companion object {
        val DOMAINS = setOf("api.open-meteo.com", "geocoding-api.open-meteo.com", "api.met.no")
    }
}
