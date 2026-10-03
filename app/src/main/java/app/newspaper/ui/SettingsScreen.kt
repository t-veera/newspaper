package app.newspaper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.newspaper.edition.DailySchedule
import app.newspaper.net.Http
import app.newspaper.net.SecretStore
import app.newspaper.settings.AppSettings
import app.newspaper.settings.MessagingApps
import app.newspaper.settings.SettingsStore
import app.newspaper.source.calendar.AndroidCalendar
import app.newspaper.source.calendar.CalendarInfo
import app.newspaper.source.feeds.Comics
import app.newspaper.source.feeds.FollowedTeam
import app.newspaper.source.feeds.Journals
import app.newspaper.source.feeds.Outlets
import app.newspaper.source.feeds.Region
import app.newspaper.source.feeds.TheSportsDb
import app.newspaper.source.messages.MessageListener
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/** Settings, laid out like Android's own: a category list, one screen per category. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val calendar = remember { AndroidCalendar(context) }
    val secrets = remember { SecretStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    var stack by rememberSaveable { mutableStateOf(listOf("root")) }
    var resumes by remember { mutableIntStateOf(0) }
    var hasKey by remember { mutableStateOf(secrets.has(SecretStore.ANTHROPIC_API_KEY)) }
    var garminName by remember { mutableStateOf(garminDisplayName(secrets)) }
    var garminSignIn by remember { mutableStateOf(false) }

    fun update(change: AppSettings.() -> AppSettings) {
        settings = settings.change()
        store.save(settings)
        DailySchedule.sync(context)
    }
    fun open(route: String) { stack = stack + route }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) else onBack() }
    BackHandler(onBack = ::back)

    // Permission states refresh whenever we come back from system settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val access = remember(resumes) { Access.read(context) }
    LaunchedEffect(access.calendar) { calendars = calendar.calendars() }

    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }
    fun calName(id: Long?) = calendars.firstOrNull { it.id == id }?.let(::label)

    val route = stack.last()
    Page(title = titles[route] ?: "Settings", onBack = ::back) {
        when (route) {
            "root" -> {
                NavRow("Daily edition", if (settings.dailyEnabled) "Every day at ${settings.editionTime.format(hhmm)}" +
                    (if (!access.exactAlarms) " · may run late" else "") else "Off", Icons.Filled.Notifications) { open("daily") }
                NavRow("Permissions", access.missing.let { if (it == 0) "All granted" else "$it need your attention" },
                    Icons.Filled.Lock) { open("access") }
                NavRow("Masthead", "${settings.mastheadName} · ${settings.place}", Icons.Filled.Edit) { open("masthead") }
                SectionHeader("Your day")
                NavRow("Calendars", calendarSummary(settings, calendars), Icons.Filled.DateRange) { open("calendars") }
                NavRow("Messages", if (!access.listener) "Notification access is off" else settings.messageApps
                    .sortedBy { MessagingApps.names.indexOf(it) }.joinToString().ifEmpty { "None" }, Icons.Filled.Email) { open("messages") }
                NavRow("Health", garminName?.let { "Garmin · $it" }
                    ?: if (settings.sampleFallback) "Sample data until Garmin is connected" else "Dashes until Garmin is connected",
                    Icons.Filled.Favorite) { open("health") }
                SectionHeader("The world")
                NavRow("Weather", if (settings.weatherUseLocation) "Phone location (${settings.weatherPlace} as fallback)"
                    else settings.weatherPlace, Icons.Filled.Place) { open("weather") }
                NavRow("News", newsSummary(settings), Icons.AutoMirrored.Filled.List) { open("news") }
                NavRow("Science", listOfNotNull(Journals.byId(settings.scienceJournal).name,
                    settings.scienceInterests.takeIf { it.isNotBlank() },
                    if (hasKey) "Claude summary" else "abstract").joinToString(" · "), Icons.Filled.Info) { open("science") }
                NavRow("Sports", if (settings.followedTeams.isEmpty()) "Not following any teams"
                    else settings.followedTeams.joinToString { it.name }, Icons.Filled.Star) { open("sports") }
                NavRow("Comic", Comics.byId(settings.comic).name, Icons.Filled.Face) { open("comic") }
            }

            "daily" -> {
                SwitchRow("Generate every day", "Builds the paper in the background and notifies you",
                    settings.dailyEnabled) { on -> update { copy(dailyEnabled = on) } }
                TimeRow("Time", settings.editionTime, enabled = settings.dailyEnabled) { t -> update { copy(editionTime = t) } }
                if (!access.exactAlarms) Footnote("Exact alarms are off, so Android may run the edition a little late. " +
                    "Turn them on under Permissions.")
            }

            "access" -> {
                PermissionRow("Calendar", "Schedule, birthdays, renewals and tasks", access.calendar) {
                    askCalendar.launch(Manifest.permission.READ_CALENDAR)
                }
                PermissionRow("Notification access", "Reads WhatsApp, Signal and Telegram messages", access.listener) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow("Notifications", "“Today's edition is ready”", access.notifications) {
                        askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PermissionRow("Exact alarms", "Prints at exactly the time you set", access.exactAlarms) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }
                PermissionRow("Approximate location", "Only if weather uses your phone's location", access.location) {
                    askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }

            "masthead" -> {
                TextRow("Name", settings.mastheadName) { v -> if (v.isNotBlank()) update { copy(mastheadName = v) } }
                TextRow("Place", settings.place, summary = "${settings.place.ifBlank { "Not set" }} · printed on the dateline") { v ->
                    update { copy(place = v) }
                }
            }

            "calendars" -> {
                if (!access.calendar) {
                    Footnote("Calendar access is off.")
                    TextButton(onClick = { askCalendar.launch(Manifest.permission.READ_CALENDAR) },
                        modifier = Modifier.padding(horizontal = 8.dp)) { Text("Allow calendar access") }
                } else {
                    NavRow("Schedule", settings.scheduleCalendarIds.mapNotNull(::calName).joinToString().ifEmpty { "None" }) { open("cal-schedule") }
                    NavRow("Birthdays", calName(settings.birthdayCalendarId) ?: "None") { open("cal-birthdays") }
                    NavRow("Renewals", calName(settings.renewalsCalendarId) ?: "None") { open("cal-renewals") }
                    NavRow("Tasks", settings.tasksCalendarIds.mapNotNull(::calName).joinToString().ifEmpty { "None" }) { open("cal-tasks") }
                    Footnote("A calendar can serve several roles. Birthdays are events titled like “Meera's birthday” or " +
                        "yearly all-day events. Renewals from a shared calendar must mention renew, subscription, expiry, premium or due.")
                }
            }
            "cal-schedule" -> CalendarPicker(calendars, multi = true, selected = settings.scheduleCalendarIds,
                note = "Today's events print in the schedule.") { id, on ->
                update { copy(scheduleCalendarIds = if (on) scheduleCalendarIds + id else scheduleCalendarIds - id) }
            }
            "cal-tasks" -> CalendarPicker(calendars, multi = true, selected = settings.tasksCalendarIds,
                note = "Today's events print as to-dos, for example the Todoist calendar. Titles starting with ✓ are skipped.") { id, on ->
                update { copy(tasksCalendarIds = if (on) tasksCalendarIds + id else tasksCalendarIds - id) }
            }
            "cal-birthdays" -> CalendarPicker(calendars, multi = false, selected = setOfNotNull(settings.birthdayCalendarId),
                note = "Google keeps birthdays in your main calendar.") { id, on ->
                update { copy(birthdayCalendarId = if (on) id else null) }
            }
            "cal-renewals" -> CalendarPicker(calendars, multi = false, selected = setOfNotNull(settings.renewalsCalendarId),
                note = "Events in the next ${settings.renewalsLookaheadDays} days.") { id, on ->
                update { copy(renewalsCalendarId = if (on) id else null) }
            }

            "messages" -> {
                if (!access.listener) NavRow("Notification access is off", "Tap to allow, then come back") {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                SectionHeader("Apps")
                MessagingApps.names.forEach { app ->
                    CheckRow(app, checked = app in settings.messageApps) { on ->
                        update { copy(messageApps = if (on) messageApps + app else messageApps - app) }
                    }
                }
                Footnote("A message stays until you read or dismiss its notification. Many messages continue on page 3.")
            }

            "health" -> {
                SectionHeader("Garmin")
                if (garminName != null) {
                    NavRow("Connected", "$garminName · yesterday's heart rate, stress and calories. Tap to sign out.") {
                        secrets.put(SecretStore.GARMIN_TOKENS, null); garminName = null
                    }
                } else {
                    NavRow("Sign in to Garmin", "On Garmin's own page, 2FA included. The app never sees your password.") {
                        garminSignIn = true
                    }
                    SwitchRow("Sample data until connected", "Off prints dashes", settings.sampleFallback) { on ->
                        update { copy(sampleFallback = on) }
                    }
                }
                Footnote("Only Garmin's sign-in token is kept, encrypted on this phone. If Garmin can't be reached, " +
                    "the health box prints dashes.")
                if (garminSignIn) GarminSignIn(secrets) { name ->
                    garminSignIn = false
                    if (name != null) garminName = name
                }
            }

            "weather" -> {
                SwitchRow("Use phone location", "Approximate, rounded to about 1 km", settings.weatherUseLocation) { on ->
                    update { copy(weatherUseLocation = on) }
                    if (on && !access.location) askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                TextRow(if (settings.weatherUseLocation) "Fallback place" else "Place", settings.weatherPlace,
                    placeholder = "City, e.g. Hyderabad") { v -> if (v.isNotBlank()) update { copy(weatherPlace = v) } }
            }

            "news" -> {
                val india = Outlets.region(Region.INDIA).map { it.id to it.name }
                val world = Outlets.region(Region.WORLD).map { it.id to it.name }
                SectionHeader("India")
                for (slot in 0..1) ChoiceRow("Source ${slot + 1}", india, settings.newsIndia.getOrNull(slot)) { id ->
                    update { copy(newsIndia = newsIndia.withSlot(slot, id)) }
                }
                TextRow("Topics", settings.newsIndiaTopics, placeholder = "e.g. economy, ISRO, Hyderabad",
                    summary = settings.newsIndiaTopics.ifBlank { "Trending (stories several outlets carry)" }) { v ->
                    update { copy(newsIndiaTopics = v) }
                }
                SectionHeader("World")
                for (slot in 0..1) ChoiceRow("Source ${slot + 1}", world, settings.newsWorld.getOrNull(slot)) { id ->
                    update { copy(newsWorld = newsWorld.withSlot(slot, id)) }
                }
                TextRow("Topics", settings.newsWorldTopics, placeholder = "e.g. climate, AI, elections",
                    summary = settings.newsWorldTopics.ifBlank { "Trending (stories several outlets carry)" }) { v ->
                    update { copy(newsWorldTopics = v) }
                }
                SectionHeader("Both")
                TextRow("Skip stories about", settings.newsSkip, placeholder = "e.g. crime, celebrity, attack",
                    summary = settings.newsSkip.ifBlank { "Nothing skipped" }) { v -> update { copy(newsSkip = v) } }
                Footnote("Topics are comma-separated. Stories matching them come first; otherwise the story most outlets " +
                    "are carrying wins.")
            }

            "science" -> {
                ChoiceRow("Journal", Journals.all.map { it.id to it.name }, Journals.byId(settings.scienceJournal).id) { id ->
                    update { copy(scienceJournal = id) }
                }
                TextRow("Interests", settings.scienceInterests, placeholder = "e.g. neuroscience, quantum, climate",
                    summary = settings.scienceInterests.ifBlank { "None: the newest paper" }) { v -> update { copy(scienceInterests = v) } }
                SectionHeader("Claude summary")
                if (hasKey) {
                    NavRow("Anthropic API key", "Saved, encrypted on this phone. Tap to remove.") {
                        secrets.put(SecretStore.ANTHROPIC_API_KEY, null); hasKey = false
                    }
                } else {
                    TextRow("Anthropic API key", "", placeholder = "sk-ant-…", summary = "Not set: the abstract is printed",
                        secret = true, validate = { it.startsWith("sk-ant-") }) { v ->
                        secrets.put(SecretStore.ANTHROPIC_API_KEY, v); hasKey = true
                    }
                }
                Footnote("With a key, Claude rewrites the abstract as a news story. Only the paper's public text is sent. " +
                    "About 1–2 US cents a day.")
            }

            "sports" -> {
                if (settings.followedTeams.isEmpty()) Footnote("Follow teams to get their latest result or next fixture.")
                settings.followedTeams.forEach { t ->
                    ListItem(
                        headlineContent = { Text(t.name) },
                        supportingContent = { Text(listOf(t.sport, t.league).filter { it.isNotBlank() }.joinToString(" · ")) },
                        trailingContent = {
                            IconButton(onClick = { update { copy(followedTeams = followedTeams.filter { it.id != t.id }) } }) {
                                Icon(Icons.Filled.Close, contentDescription = "Unfollow ${t.name}")
                            }
                        },
                    )
                }
                NavRow("Follow a team", "Search football, cricket and more", Icons.Filled.Add) { open("sports-search") }
            }
            "sports-search" -> TeamSearch(settings.followedTeams) { t ->
                update {
                    copy(followedTeams = if (followedTeams.any { it.id == t.id }) followedTeams.filter { it.id != t.id }
                    else followedTeams + t)
                }
            }

            "comic" -> Comics.all.forEach { c ->
                RadioRow(c.name, if (c.goComicsSlug != null) "GoComics" else null, Comics.byId(settings.comic).id == c.id) {
                    update { copy(comic = c.id) }
                }
            }
        }
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

private val tokenJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun garminDisplayName(secrets: SecretStore): String? = secrets.get(SecretStore.GARMIN_TOKENS)?.let { raw ->
    runCatching { tokenJson.decodeFromString(app.newspaper.source.garmin.GarminTokens.serializer(), raw).displayName ?: "Garmin" }
        .getOrNull()
}

private val titles = mapOf(
    "root" to "Settings", "daily" to "Daily edition", "access" to "Permissions", "masthead" to "Masthead",
    "calendars" to "Calendars", "cal-schedule" to "Schedule calendars", "cal-birthdays" to "Birthdays calendar",
    "cal-renewals" to "Renewals calendar", "cal-tasks" to "Task calendars", "messages" to "Messages", "health" to "Health",
    "weather" to "Weather", "news" to "News", "science" to "Science", "sports" to "Sports",
    "sports-search" to "Follow a team", "comic" to "Comic",
)

private class Access(val calendar: Boolean, val listener: Boolean, val notifications: Boolean, val exactAlarms: Boolean,
                     val location: Boolean) {
    val missing = listOf(calendar, listener, notifications, exactAlarms).count { !it }

    companion object {
        fun read(context: android.content.Context): Access {
            fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            return Access(
                calendar = granted(Manifest.permission.READ_CALENDAR),
                listener = MessageListener.isEnabled(context),
                notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS),
                exactAlarms = DailySchedule.canUseExactAlarms(context),
                location = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }
}

private fun label(c: CalendarInfo) = c.name.ifBlank { "(unnamed)" }

private fun calendarSummary(s: AppSettings, cals: List<CalendarInfo>): String {
    fun name(id: Long?) = cals.firstOrNull { it.id == id }?.let(::label)
    return listOfNotNull(
        "Schedule: ${s.scheduleCalendarIds.size}",
        name(s.birthdayCalendarId)?.let { "Birthdays" },
        name(s.renewalsCalendarId)?.let { "Renewals" },
        s.tasksCalendarIds.mapNotNull(::name).takeIf { it.isNotEmpty() }?.let { "Tasks: ${it.joinToString()}" },
    ).joinToString(" · ")
}

private fun newsSummary(s: AppSettings): String =
    (s.newsIndia + s.newsWorld).mapNotNull { Outlets.byId(it)?.name }.joinToString()

private fun List<String>.withSlot(slot: Int, id: String): List<String> =
    (0..maxOf(1, lastIndex)).map { i -> if (i == slot) id else getOrNull(i).orEmpty() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onAllow: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(why) },
        trailingContent = {
            if (granted) Text("Allowed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            else FilledTonalButton(onClick = onAllow) { Text("Allow") }
        },
    )
}

/** Calendars grouped by account, each with its colour and stored event count. */
@Composable
private fun CalendarPicker(calendars: List<CalendarInfo>, multi: Boolean, selected: Set<Long>, note: String,
                           onToggle: (Long, Boolean) -> Unit) {
    Footnote(note)
    calendars.groupBy { it.account }.forEach { (account, list) ->
        SectionHeader(account)
        list.forEach { c ->
            val on = c.id in selected
            val summary = "${c.eventCount} events"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 16.dp).size(12.dp).background(Color(c.color or 0xFF000000.toInt()), CircleShape))
                Box(Modifier.weight(1f)) {
                    if (multi) CheckRow(label(c), summary, on) { onToggle(c.id, it) }
                    else RadioRow(label(c), summary, on) { onToggle(c.id, !on) }
                }
            }
        }
    }
}

/** Google-style follow: search any sport, tap to follow or unfollow. */
@Composable
private fun TeamSearch(followed: List<FollowedTeam>, onToggle: (FollowedTeam) -> Unit) {
    val scope = rememberCoroutineScope()
    val db = remember { TheSportsDb(Http(TheSportsDb.DOMAINS)) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<TheSportsDb.Team>()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    fun search() {
        if (query.isBlank() || busy) return
        scope.launch {
            busy = true
            status = try {
                results = db.search(query)
                if (results.isEmpty()) "No teams found. Try the club's full name, e.g. “Manchester United”." else null
            } catch (e: Exception) {
                "Search failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        placeholder = { Text("Team name, e.g. Man Utd Women") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    status?.let { Footnote(it) }
    results.take(15).forEach { r ->
        val team = FollowedTeam(r.idTeam, r.strTeam, r.strSport.orEmpty(), r.strLeague.orEmpty())
        val isFollowed = followed.any { it.id == r.idTeam }
        ListItem(
            headlineContent = { Text(r.strTeam) },
            supportingContent = { Text(listOfNotNull(r.strSport, r.strLeague, r.strCountry).filter { it.isNotBlank() }.joinToString(" · ")) },
            trailingContent = {
                if (isFollowed) TextButton(onClick = { onToggle(team) }) { Text("Following") }
                else FilledTonalButton(onClick = { onToggle(team) }) { Text("Follow") }
            },
        )
    }
}
