package app.newspaper.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One day's newspaper. Serializes to the JSON object that `window.renderEdition(data)` in
 * assets/newspaper/render.js consumes. Every section except the masthead is optional:
 * empty lists and nulls hide the section, except [health], which renders dashes when null.
 */
@Serializable
data class Edition(
    val masthead: Masthead,
    val footer: Footer = Footer(),
    val weather: Weather? = null,
    val science: ScienceLead? = null,
    val news: News = News(),
    val sports: List<SportLine> = emptyList(),
    val schedule: List<ScheduleItem> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val birthdays: List<Birthday> = emptyList(),
    val renewals: List<Renewal> = emptyList(),
    val health: Health? = null,
    val messages: List<Message> = emptyList(),
    val comic: Comic? = null,
)

@Serializable
data class Masthead(
    /** Shown as the masthead title, the page-2 mini title and the PDF title. */
    val name: String,
    /** ISO date, e.g. "2026-10-03". The template formats it as "Saturday, 3 October 2026". */
    val date: String,
    /** Printed as a Roman numeral: "Vol. I". */
    val volume: Int,
    val number: Int,
    val place: String? = null,
    /** "06:00", shown in the page-2 footer. */
    val printedAt: String? = null,
)

@Serializable
data class Footer(
    val publicNote: String? = null,
    val privateNote: String? = null,
)

@Serializable
enum class WeatherIcon {
    @SerialName("sun") SUN,
    @SerialName("part") PARTLY_CLOUDY,
    @SerialName("cloud") CLOUD,
    @SerialName("rain") RAIN,
    @SerialName("moon") MOON,
}

@Serializable
data class Weather(
    val tempC: Double? = null,
    val condition: String? = null,
    val icon: WeatherIcon = WeatherIcon.CLOUD,
    val highC: Double? = null,
    val lowC: Double? = null,
    val hourly: List<HourlyForecast> = emptyList(),
    /** "06:02" */
    val sunrise: String? = null,
    val sunset: String? = null,
    val uvIndex: Int? = null,
    val moon: Moon? = null,
)

@Serializable
data class HourlyForecast(
    /** "06" */
    val hour: String,
    val icon: WeatherIcon = WeatherIcon.CLOUD,
    val tempC: Double? = null,
    /** Chance of rain; omitted when negligible. */
    val precipPct: Int? = null,
)

@Serializable
data class Moon(
    val phaseName: String? = null,
    /** 0 = new, 0.25 = first quarter, 0.5 = full, 0.75 = last quarter. Drives the icon. */
    val phase: Double? = null,
    val illuminationPct: Int? = null,
    val moonrise: String? = null,
)

@Serializable
data class ScienceLead(
    val headline: String,
    val deck: String? = null,
    val paragraphs: List<String> = emptyList(),
    val sourceUrl: String? = null,
    /** Filled in by the app from [sourceUrl] before rendering; never stored in fixtures. */
    val qrDataUri: String? = null,
)

@Serializable
data class News(
    /** Up to 2 stories are printed. */
    val india: List<NewsItem> = emptyList(),
    /** Up to 2 stories are printed. */
    val world: List<NewsItem> = emptyList(),
)

@Serializable
data class NewsItem(
    val headline: String,
    val source: String? = null,
    val text: String,
)

@Serializable
data class SportLine(
    val sport: String,
    val text: String,
)

@Serializable
data class ScheduleItem(
    /** "08:30" */
    val time: String,
    val title: String,
)

@Serializable
data class TaskItem(
    val text: String,
    val done: Boolean = false,
)

@Serializable
data class Birthday(
    val name: String,
    /** "Today" */
    val whenLabel: String? = null,
)

@Serializable
data class Renewal(
    /** "In 4 days" */
    val whenLabel: String,
    val text: String,
)

/** Yesterday's health summary. Any null value prints as a dash. */
@Serializable
data class Health(
    /** "Garmin" */
    val source: String? = null,
    /** "2 October" */
    val dateLabel: String? = null,
    val restingHr: Int? = null,
    val avgHr: Int? = null,
    val avgStress: Int? = null,
    val peakStress: Int? = null,
    /** "14:00" */
    val peakStressTime: String? = null,
    val totalKcal: Int? = null,
    val activeKcal: Int? = null,
    /** 12 two-hour averages starting at 00:00; null for missing slots. */
    val hrTwoHourly: List<Int?> = emptyList(),
    /** 12 two-hour averages (0-100) starting at 00:00; null for missing slots. */
    val stressTwoHourly: List<Int?> = emptyList(),
)

@Serializable
data class Message(
    /** "WhatsApp"; messages are grouped by app in first-seen order. */
    val app: String,
    val sender: String,
    /** "22:14" */
    val time: String,
    val text: String,
)

@Serializable
data class Comic(
    /** "CALVIN AND HOBBES" */
    val title: String? = null,
    /** A base64 image data URI fetched by the app; the WebView never loads remote images. */
    val imageDataUri: String? = null,
    /** Alt text, also shown in place of the image when there is none. */
    val altText: String? = null,
)

/** JSON settings shared by fixtures, tests and the renderer. Unknown keys are errors. */
val EditionJson: Json = Json {
    explicitNulls = false
    prettyPrint = false
}
