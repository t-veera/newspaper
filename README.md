# The First Light

![Vibe coded](https://img.shields.io/badge/vibe-coded-ff69b4?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square)
![Analytics](https://img.shields.io/badge/analytics-none-lightgrey?style=flat-square)

**A personal morning newspaper, typeset on your phone.** Every morning, The First Light gathers
your day (calendar, to-dos, birthdays, unread messages, yesterday's health) and the world
(weather, news, a science paper, your teams' scores, a comic strip), then lays it out as a printed
A4 broadsheet in a 90s-style design, ready to share or print.

Personal data never leaves the phone. Built with heavy AI assistance (hence the badge).

---

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [Privacy and security](#privacy-and-security)
- [Development](#development)
- [Roadmap](#roadmap)
- [Credits](#credits)

## Features

**Page 1: the world**
- **Masthead** that sizes itself to fit, with the date, volume/issue number, weather and moon phase.
- **Weather** from Open-Meteo: current conditions, highs and lows, an hourly strip, sunrise, sunset, UV,
  plus moonrise from met.no. Uses a city you type in, or the phone's approximate location.
- **Science lead**: the newest research paper from Nature, Science Advances, PLOS Biology, arXiv or
  ScienceDaily that matches your interests. Prints the paper's abstract with a QR code to the source,
  or a newspaper-style rewrite by Claude if you add an Anthropic API key.
- **News**: two Indian and two world outlets of your choice. Stories are ranked by your topics, or by
  what several outlets are carrying when you have none. The same story is never printed twice, and
  live blogs, galleries and round-ups are skipped.

**Page 2: your day**
- **Schedule, to-dos, birthdays and renewals** from any calendars on the phone (Google, Samsung,
  Todoist's calendar sync, …).
- **Health** from Garmin Connect: resting and average heart rate, stress, calories, and 2-hourly charts.
- **Messages**: unread WhatsApp, Signal and Telegram messages, grouped by app. Long lists continue on
  page 3 and beyond.
- **Sports**: one line per team you follow (any sport). Shows the live score during a match, the
  latest result, or the next fixture.
- **The funnies**: Calvin and Hobbes and other GoComics strips, xkcd or SMBC.

**The app**
- **Daily edition** at a time you choose, generated in the background, with a notification when ready.
- **Page viewer** with swipe and pinch-to-zoom; share or save to Downloads.
- **Layout safety**: text is fitted to the page and never silently clipped; problems show as notes.
- **Standard Android settings** with a category list, plus per-category screens and dialogs.

## How it works

```
 Sources (Kotlin)                          Template (offline WebView)                 Output
 ─────────────────                         ──────────────────────────                 ──────
 Calendar ─┐                               template.html + style.css
 Messages ─┤                               render.js:
 Garmin ───┤   EditionBuilder ─► Edition ─►  renderEdition(json)          PrintDocumentAdapter
 Weather ──┤   (parallel, cached,  (JSON)    fonts → fit masthead     ─►  (ISO A4, 300 dpi)  ─►  PDF
 News ─────┤    failures → notes)            → fill news → paginate       checked with PdfRenderer
 Science ──┤                                 → measure → report
 Sports ───┤
 Comic ────┘
```

1. **Gather.** Each section has a source interface (`source/`). Network sections run concurrently.
   A failing feed prints its last good copy (up to 3 days old) with a note, or hides. One broken
   source never blocks the paper.
2. **Render.** The edition is serialized to JSON and handed to `window.renderEdition()` in a bundled
   HTML template running in an off-screen WebView with **all network access blocked**. The template
   reproduces the approved design (`docs/reference/newspaper.html`), sizes the masthead to fit, grows
   the news text until page 1 is full, shortens news stories sentence by sentence if needed (never
   the science lead), and moves extra messages onto further pages.
3. **Print.** The WebView's print adapter writes the PDF without the print dialog. The PDF is then
   re-opened to check the page count and A4 size.

The design uses UnifrakturMaguntia, Libre Caslon Text and Playfair Display, bundled as asset files
(SIL Open Font License, see `app/src/main/assets/newspaper/fonts/OFL.txt`).

## Getting started

**Requirements:** JDK 17, Android SDK (platform 35, build-tools 35), and a phone or emulator
running Android 10 or newer.

```sh
git clone https://github.com/t-veera/newspaper.git
cd newspaper
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # path to your Android SDK
./gradlew installDebug                                 # build and install on the connected device
```

Open the app, go to **Settings → Permissions** and allow what you want to use, then tap
**Generate**.

## Configuration

Everything is set in the app under **Settings**:

| Category | What you set |
|---|---|
| Daily edition | On/off and the time (clock picker); runs in the background and notifies you |
| Permissions | Calendar, notification access (for messages), notifications, exact alarms, approximate location |
| Masthead | Paper name and the place printed on the dateline |
| Calendars | Which calendars feed **Schedule**, **Birthdays**, **Renewals** and **Tasks** (one calendar may serve several) |
| Messages | Which apps to read (WhatsApp, Signal, Telegram) |
| Health | Sign in to Garmin (on Garmin's own page, 2FA included), or print sample data / dashes |
| Weather | Phone location or a typed city |
| News | Two India and two World sources, topics per region, and words to skip |
| Science | Journal, interests, optional Anthropic API key for Claude summaries |
| Sports | Search for teams and follow them |
| Comic | Calvin and Hobbes, Peanuts, Garfield, Pearls Before Swine, xkcd, SMBC |

**How calendar roles read events:** birthdays are events titled like "Meera's birthday" or yearly
all-day events (Google keeps these in your main calendar). Renewals are events in the next 14 days;
in a shared calendar, only titles mentioning renew, subscription, expiry, premium or due. Tasks are
today's events in the chosen calendars; titles starting with ✓ are skipped. Google Tasks itself
can't be read on the device, so use a calendar sync such as Todoist's.

## Privacy and security

- **Personal data stays on the phone.** Calendar, messages, tasks and health data are never sent
  anywhere. Generated PDFs stay in app-private storage until you share or save them.
- **No analytics, crash reporting or tracking SDKs.**
- **Backups off:** `allowBackup=false`, and the data-extraction rules exclude everything.
  `FLAG_SECURE` is set on the app window.
- **Network access is narrow.** All feed traffic goes through one client (`net/Http.kt`):
  HTTPS only, a host allow-list built from your chosen sources, every redirect re-checked, and
  responses capped at 2 MB.
- **The rendering WebView never touches the network.** It loads only the bundled template, refuses
  every other request, and has a single "ready" callback as its only JavaScript bridge. Images are
  passed in as data URIs.
- **Two scoped exceptions, both explicit:**
  - Garmin sign-in shows Garmin's own page (limited to Garmin's domains). The app never sees your
    password. It keeps only Garmin's long-lived token, encrypted with Android Keystore.
  - GoComics only serves real browsers, so a throwaway WebView limited to gocomics.com fetches the
    strip, then is destroyed and its cookies cleared.
- **Secrets** (API keys, the Garmin token) are encrypted with an AES-GCM key that never leaves the
  Android Keystore.
- **AI:** only the science paper's public title and abstract are sent to Anthropic, and only if
  you add a key.

## Development

```sh
./gradlew testDebugUnitTest            # 41 JVM tests: parsing, ranking, layout rules, OAuth, …
./gradlew connectedDebugAndroidTest    # on-device: 2 A4 pages, margins within 1 mm, news fill, background render
python3 tools/preview.py [edition.json]  # render the template on a desktop Chromium, no Android needed
```

`fixtures/sample_edition.json` is the reference edition, used by the tests and the desktop preview.
The data model is `app/src/main/java/app/newspaper/model/Edition.kt`; unknown JSON keys fail loudly.

```
app/src/main/
├── assets/newspaper/      template.html, style.css, render.js, fonts/
└── java/app/newspaper/
    ├── model/             Edition data classes (the template's JSON)
    ├── source/            source interfaces, EditionBuilder, EditionAssembler
    │   ├── calendar/      CalendarContract reader and calendar-role rules
    │   ├── messages/      notification listener and unread-message store
    │   ├── feeds/         news, science, sports (TheSportsDB), comics, Claude summary
    │   ├── weather/       Open-Meteo, met.no, moon phase
    │   └── garmin/        Garmin sign-in tokens, OAuth1 signing, health aggregation
    ├── render/            off-screen WebView → PDF, QR codes
    ├── edition/           today's edition, daily alarm, notifications
    ├── net/               HTTP allow-list client, feed cache, Keystore secrets
    └── ui/                Compose screens (main, settings, Garmin sign-in)
```

**Layout notes**
- Android's `ISO_A4` paper is 0.07 mm shorter than 297 mm, so pages are 296.9 mm tall (`--page-h`).
- The template's Content Security Policy blocks inline `style=""` attributes; put styles in `style.css`.
- `PrintAdapterDriver` lives in the `android.print` package because the print callbacks' constructors
  are hidden from the SDK.

## Roadmap

- [ ] **Print on a Raspberry Pi**: send the morning PDF to a Pi-connected printer automatically
- [ ] Optional paid TheSportsDB key in Settings for fresher scores
- [ ] Google Tasks via Google's API (needs a Google Cloud project)
- [ ] Release signing and a proper launcher icon
- [ ] Onboarding flow for first-run permissions

## Credits

- Fonts: [UnifrakturMaguntia](https://fonts.google.com/specimen/UnifrakturMaguntia),
  [Libre Caslon Text](https://fonts.google.com/specimen/Libre+Caslon+Text),
  [Playfair Display](https://fonts.google.com/specimen/Playfair+Display) (SIL OFL 1.1)
- Data: [Open-Meteo](https://open-meteo.com), [MET Norway](https://api.met.no),
  [TheSportsDB](https://www.thesportsdb.com), [xkcd](https://xkcd.com), [SMBC](https://www.smbc-comics.com),
  [GoComics](https://www.gocomics.com), and the news outlets and journals you choose
- Garmin's mobile token flow as documented by the [garth](https://github.com/matin/garth) project
- Libraries: Jetpack Compose, kotlinx.serialization, kotlinx.coroutines, [ZXing](https://github.com/zxing/zxing),
  [jsoup](https://jsoup.org)

Comics and articles belong to their creators and publishers. This app is for personal use.
