package app.newspaper.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.newspaper.R
import app.newspaper.edition.DailySchedule
import app.newspaper.edition.TodayEdition
import app.newspaper.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(activity: Activity, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var warnings by rememberSaveable { mutableStateOf(listOf<String>()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfPath by rememberSaveable { mutableStateOf(TodayEdition.latest(activity)?.path) }
    var version by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val pdf = pdfPath?.let(::File)?.takeIf { it.exists() }

    fun generate() {
        busy = true
        error = null
        scope.launch {
            try {
                val result = TodayEdition.generate(activity, activity)
                pdfPath = result.pdf.path
                version++
                warnings = result.warnings
                snackbar.showSnackbar("Edition ready · ${result.pageCount} pages")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (pdf != null) IconButton(onClick = { EditionExport.share(activity, pdf) }, enabled = !busy) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = onSettings, enabled = !busy) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    if (pdf != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Save to Downloads") }, onClick = {
                                menu = false
                                scope.launch {
                                    val msg = try {
                                        "Saved to Downloads/${EditionExport.saveToDownloads(activity, pdf)}"
                                    } catch (e: Exception) {
                                        "Save failed: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                    snackbar.showSnackbar(msg)
                                }
                            })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!busy) generate() },
                icon = {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, contentDescription = null)
                },
                text = { Text(if (busy) "Generating…" else if (pdf == null) "Generate" else "Regenerate") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            EditionCard(activity, pdf, version, warnings, error)
            if (pdf == null) EmptyState() else PageViewer(pdf, version)
            Spacer(Modifier.height(96.dp)) // room above the floating button
        }
    }
}

/** Today's edition at a glance: date, when it was made, the next run, and any warnings. */
@Composable
private fun EditionCard(activity: Activity, pdf: File?, version: Int, warnings: List<String>, error: String?) {
    val settings = remember(version) { SettingsStore(activity).load() }
    var showWarnings by rememberSaveable { mutableStateOf(false) }
    val made = pdf?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.lastModified()), ZoneId.systemDefault()) }
    val pages = remember(pdf?.path, version) { pdf?.let(::pageCount) ?: 0 }
    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                (made?.toLocalDate() ?: LocalDate.now()).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    made == null -> "No edition yet"
                    made.toLocalDate() == LocalDate.now() -> "Generated today at ${made.format(hhmm)} · $pages pages"
                    else -> "Last edition ${made.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH))} · $pages pages"
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (settings.dailyEnabled) {
                    val next = DailySchedule.nextRun(LocalDateTime.now(), settings.editionTime)
                    "Next edition " + (if (next.toLocalDate() == LocalDate.now()) "today" else "tomorrow") + " at ${next.format(hhmm)}"
                } else "Daily edition is off (Settings → Daily edition)",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text("Couldn't generate: $error", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (warnings.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { showWarnings = !showWarnings }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Text("${warnings.size} note${if (warnings.size > 1) "s" else ""} about this edition",
                        Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    Icon(if (showWarnings) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                AnimatedVisibility(showWarnings) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your paper will appear here", style = MaterialTheme.typography.titleMedium)
        Text("Tap Generate to build today's edition, or turn on the daily edition in Settings.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Swipe between pages; tap one to read it full screen with pinch-to-zoom. */
@Composable
private fun PageViewer(pdf: File, version: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { (maxWidth - 48.dp).roundToPx() }
        val pages = rememberPdfPages(pdf, version, widthPx)
        var zoomed by remember { mutableStateOf<Int?>(null) }
        if (pages.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@BoxWithConstraints
        }
        val pager = rememberPagerState { pages.size }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalPager(state = pager, contentPadding = PaddingValues(horizontal = 24.dp), pageSpacing = 12.dp) { i ->
                val page = pages[i]
                Image(
                    bitmap = page.asImageBitmap(), contentDescription = "Page ${i + 1}",
                    modifier = Modifier.fillMaxWidth().aspectRatio(page.width.toFloat() / page.height)
                        .shadow(4.dp, RoundedCornerShape(2.dp)).clickable { zoomed = i },
                )
            }
            Text("Page ${pager.currentPage + 1} of ${pages.size} · tap to zoom", Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        zoomed?.let { i -> ZoomDialog(pdf, i) { zoomed = null } }
    }
}

@Composable
private fun ZoomDialog(pdf: File, index: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() } * 2 // sharp when zoomed in
                var page by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(pdf, index) { page = withContext(Dispatchers.IO) { renderPage(pdf, index, widthPx) } }
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                val state = rememberTransformableState { zoom, pan, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
                page?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = "Page ${index + 1}",
                        modifier = Modifier.fillMaxSize().transformable(state).graphicsLayer(
                            scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y,
                        ),
                    )
                } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(50))) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
        }
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

private fun pageCount(pdf: File): Int = runCatching {
    ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
}.getOrDefault(0)

private fun renderPage(pdf: File, index: Int, widthPx: Int): Bitmap =
    ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            renderer.openPage(index).use { page ->
                Bitmap.createBitmap(widthPx, widthPx * page.height / page.width, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

/** Renders every page of [pdf] to a bitmap [widthPx] wide, off the main thread. */
@Composable
private fun rememberPdfPages(pdf: File, version: Int, widthPx: Int): List<Bitmap> {
    var pages by remember { mutableStateOf(emptyList<Bitmap>()) }
    LaunchedEffect(pdf.path, version, widthPx) {
        pages = if (widthPx <= 0) emptyList() else withContext(Dispatchers.IO) {
            val count = pageCount(pdf)
            (0 until count).map { renderPage(pdf, it, widthPx) }
        }
    }
    return pages
}
