package com.axiel7.anihyou.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.axiel7.anihyou.core.base.ACTION_RELEASE_DATA_UPDATED
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import com.axiel7.anihyou.release.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.axiel7.anihyou.core.base.APP_PACKAGE_NAME
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.feature.calendar.CalendarEntry
import com.axiel7.anihyou.feature.calendar.calendarEntries
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import java.util.concurrent.ConcurrentHashMap
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.core.model.DeepLink
import com.axiel7.anihyou.core.network.NetworkVariables
import com.axiel7.anihyou.core.resources.ColorUtils.colorFromHex
import com.axiel7.anihyou.core.resources.R
import com.materialkolor.ktx.darken
import com.materialkolor.ktx.from
import com.materialkolor.ktx.harmonize
import com.materialkolor.ktx.toneColor
import com.materialkolor.palettes.TonalPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val DEVICE_ZONE: ZoneId get() = ZoneId.systemDefault()

class AiringWidget : GlanceAppWidget(), KoinComponent {

    private val networkVariables: NetworkVariables by inject()
    private val defaultPreferencesRepository: DefaultPreferencesRepository by inject()
    private val mediaRepository: MediaRepository by inject()
    private val releasePresentationRepository: ReleasePresentationRepository by inject()
    private val clock: Clock by inject()

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        networkVariables.accessToken = defaultPreferencesRepository.accessToken.first()
        // What the release source knows and what is remembered of AniList is there at once; AniList's own answers follow.
        val first = loadEntries(withAniList = false, fresh = false)
        provideContent {
            val prefs = currentState<Preferences>()
            val isColored = prefs[IS_COLORED_KEY] ?: true
            val entries = remember { mutableStateOf(first) }
            LaunchedEffect(Unit) {
                val fresh = takeFreshRequest()
                entries.value = loadEntries(withAniList = true, fresh = fresh)
            }
            GlanceTheme(colors = DynamicThemeColorProviders) {
                Content(entries = entries.value, isColored = isColored, now = clock.instant())
            }
        }
    }

    /**
     * The entries the calendar tab shows from today on, by the calendar's own code and with the calendar's own settings
     * (list filter, AniList entries without a source match, simultaneous tracks). Nothing here is a setting of the widget.
     */
    private suspend fun loadEntries(withAniList: Boolean, fresh: Boolean): List<CalendarEntry> {
        val now = clock.instant()
        val today = now.atZone(DEVICE_ZONE).toLocalDate()
        val accountId = defaultPreferencesRepository.userId.first()?.toLong()
        val onMyList = defaultPreferencesRepository.calendarOnMyList.first()
        val showExtras = defaultPreferencesRepository.calendarShowAniListExtras.first() == true
        val combine = defaultPreferencesRepository.calendarCombineTracks.first() != false
        val rows = runCatching {
            releasePresentationRepository.currentCalendar(accountId = accountId, range = today..today.plusDays(RELEASE_DAYS))
        }.onFailure { AppLog.w("widget", it) { "release rows could not be read" } }.getOrDefault(emptyList())
        val sourceIsMain = rows.any { it.isAuthoritative }
        val media = if (withAniList) loadMedia(rows.mapNotNull { it.mediaId }.toSet(), fresh) else cachedMedia(rows.mapNotNull { it.mediaId }.toSet())
        // AniList's schedule is only part of the calendar when no source supplies rows or the user asked for the extras.
        val airing = if (withAniList && (!sourceIsMain || showExtras)) {
            loadAiring(today, onMyList, fresh)
        } else emptyMap()
        val entries = calendarEntries(
            today = today, now = now, onMyList = onMyList, showAniListExtras = showExtras, combineSimultaneousTracks = combine,
            airingByDate = airing, media = media + airing.values.flatten().associate { it.media.id to it.media }, releaseRows = rows,
        )
        AppLog.i("widget") {
            "entries from $today on: ${entries.size} (rows=${rows.size} source=$sourceIsMain extras=$showExtras onMyList=$onMyList " +
                "combine=$combine media=${media.size} airing=${airing.values.sumOf { it.size }} aniList=$withAniList fresh=$fresh)"
        }
        return entries
    }

    private fun cachedMedia(ids: Set<Int>): Map<Int, ExploreMedia> =
        ids.mapNotNull { id -> mediaCache[id]?.let { id to it.first } }.toMap()

    /** Cover, title and list status of the media the source names: asked by id, once per half hour for each. */
    private suspend fun loadMedia(ids: Set<Int>, fresh: Boolean): Map<Int, ExploreMedia> {
        val now = clock.instant()
        val missing = ids.filter { id ->
            val cached = mediaCache[id]
            fresh || cached == null || cached.second.plus(MEDIA_TTL).isBefore(now)
        }
        missing.chunked(MEDIA_BY_IDS_PAGE).forEach { chunk ->
            val result = runCatching {
                mediaRepository.getMediaByIdsPage(chunk, page = 1, perPage = MEDIA_BY_IDS_PAGE)
                    .first { it !is PagedResult.Loading }
            }.getOrNull()
            if (result is PagedResult.Success) result.list.forEach { mediaCache[it.id] = it to now }
            else AppLog.w("widget") { "cover and title for ${chunk.size} entries unavailable: ${(result as? PagedResult.Error)?.message?.take(120)}" }
        }
        return cachedMedia(ids)
    }

    /** AniList's own schedule of the next days, the way the calendar tab loads a day. */
    private suspend fun loadAiring(today: LocalDate, onMyList: Boolean?, fresh: Boolean): Map<LocalDate, List<CalendarAiringEvent>> {
        val adult = defaultPreferencesRepository.displayAdult.first() == true
        val byDay = LinkedHashMap<LocalDate, List<CalendarAiringEvent>>()
        for (offset in 0 until AIRING_DAYS) {
            val day = today.plusDays(offset.toLong())
            val from = day.atStartOfDay(DEVICE_ZONE).toEpochSecond()
            val to = day.plusDays(1).atStartOfDay(DEVICE_ZONE).toEpochSecond() - 1
            val events = ArrayList<CalendarAiringEvent>()
            var page = 1
            while (page <= AIRING_PAGES) {
                val result = runCatching {
                    mediaRepository.getCalendarAiringEventsPage(
                        airingAtGreater = from, airingAtLesser = to, onMyList = onMyList, isAdult = adult,
                        page = page, perPage = 50, fetchFromNetwork = fresh,
                    ).first { it !is PagedResult.Loading }
                }.getOrNull()
                if (result !is PagedResult.Success) {
                    AppLog.w("widget") { "AniList schedule of $day unavailable: ${(result as? PagedResult.Error)?.message?.take(120)}" }
                    break
                }
                events += result.list
                if (!result.hasNextPage) break
                page++
            }
            byDay[day] = events
        }
        return byDay
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        networkVariables.accessToken = defaultPreferencesRepository.accessToken.first()
        val day = clock.instant().atZone(DEVICE_ZONE).toLocalDate()
        val sample = listOf("Anime One" to 1, "Anime Two" to 2, "Anime Three" to 3).mapIndexed { index, (title, episode) ->
            CalendarEntry(
                date = day, key = "preview-$index", media = null, releases = emptyList(), airingEvent = null, title = title,
                dimmed = index == 0, checked = index == 0, episode = episode, timeText = "1${index}:10", trackLabel = "SUB",
            )
        }
        provideContent {
            GlanceTheme(colors = DynamicThemeColorProviders) {
                Content(entries = sample, isColored = true, now = clock.instant())
            }
        }
    }

    @Composable
    private fun Content(entries: List<CalendarEntry>, isColored: Boolean, now: Instant) {
        val today = now.atZone(DEVICE_ZONE).toLocalDate()
        RoundedDrawableBox(
            shapeRes = R.drawable.widget_background_24,
            color = GlanceTheme.colors.widgetBackground,
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .widgetCornerRadius()
        ) {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                item(itemId = 0) {
                    Header()
                }
                itemsIndexed(
                    items = entries,
                    itemId = { _, entry -> stableWidgetId(entry.key) },
                ) { index, entry ->
                    ItemView(
                        entry = entry,
                        showDate = index == 0 || entries[index - 1].date != entry.date,
                        isToday = entry.date == today,
                        isColored = isColored,
                    )
                }
            }
        }
    }

    @Composable
    private fun Header() {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = GlanceModifier.width(20.dp))
            Text(
                text = glanceStringResource(R.string.upcoming),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
                maxLines = 1,
                modifier = GlanceModifier
                    .defaultWeight()
                    .padding(vertical = 10.dp)
                    .clickable(
                        onClick = LocalContext.current.openDeepLink(
                            DeepLink(type = DeepLink.Type.CALENDAR, id = "")
                        )
                    )
            )

            RoundedDrawableBox(
                shapeRes = R.drawable.widget_refresh_bg_20,
                color = GlanceTheme.colors.primary,
                modifier = GlanceModifier
                    .width(54.dp)
                    .height(32.dp)
                    .cornerRadius(20.dp)
                    // A background action, not an activity: it runs from the lock screen without unlocking the phone. It asks
                    // the release source and AniList for new data and the widget redraws with what comes back.
                    .clickable(actionRunCallback<RefreshAiringWidgetAction>()),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.replay_20),
                    contentDescription = glanceStringResource(R.string.refresh),
                    modifier = GlanceModifier.size(20.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary)
                )
            }
            Spacer(modifier = GlanceModifier.width(12.dp))
        }
    }

    @Composable
    private fun ItemView(
        entry: CalendarEntry,
        showDate: Boolean,
        isToday: Boolean,
        isColored: Boolean,
    ) {
        val dayOfWeek = entry.date.format(DateTimeFormatter.ofPattern("E", Locale.getDefault()))
        val dayOfMonth = entry.date.format(DateTimeFormatter.ofPattern("d", Locale.getDefault()))
        val item = entry.media

        val baseMediaColor = remember(item?.coverImage?.color, isColored) {
            if (isColored) {
                item?.coverImage?.color?.takeIf { it.isNotBlank() }?.let { hex ->
                    runCatching { colorFromHex(hex) }.getOrNull()
                }
            } else null
        }

        val context = LocalContext.current
        val primaryColor = GlanceTheme.colors.primary.getColor(context)
        val surfaceColor = GlanceTheme.colors.widgetBackground.getColor(context)

        val rawBackground = baseMediaColor?.harmonize(primaryColor)?.darken(2f)
            ?: GlanceTheme.colors.secondaryContainer.getColor(context)
        val rawText = baseMediaColor?.let { TonalPalette.from(it).toneColor(95) }
            ?: GlanceTheme.colors.onSecondaryContainer.getColor(context)
        // An entry whose planned time has passed is dimmed as in the calendar tab: its colors give way to the widget's.
        val backgroundColor = ColorProvider(
            day = if (entry.dimmed) rawBackground.dimmedInto(surfaceColor) else rawBackground,
            night = if (entry.dimmed) rawBackground.dimmedInto(surfaceColor) else rawBackground,
        )
        val textColor = ColorProvider(
            day = if (entry.dimmed) rawText.dimmedInto(surfaceColor) else rawText,
            night = if (entry.dimmed) rawText.dimmedInto(surfaceColor) else rawText,
        )

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .padding(end = 12.dp, start = 2.dp)
        ) {
            Column(
                modifier = GlanceModifier
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val dateSize = 38.dp
                if (showDate) {
                    if (isToday) {
                        RoundedDrawableBox(
                            shapeRes = R.drawable.widget_date_circle_bg,
                            color = GlanceTheme.colors.primary,
                            modifier = GlanceModifier
                                .size(dateSize)
                                .cornerRadius(38.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$dayOfWeek\n$dayOfMonth",
                                style = TextStyle(
                                    color = GlanceTheme.colors.onPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                ),
                                maxLines = 2,
                            )
                        }
                    } else {
                        Column(
                            modifier = GlanceModifier
                                .size(dateSize)
                                .background(Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = dayOfWeek,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                ),
                                maxLines = 1,
                            )
                            Text(
                                text = dayOfMonth,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                )
                            )
                        }
                    }
                } else {
                    Spacer(modifier = GlanceModifier.width(dateSize))
                }
            }

            RoundedDrawableBox(
                shapeRes = R.drawable.widget_airing_bg_12,
                color = backgroundColor,
                modifier = GlanceModifier
                    .defaultWeight()
                    .cornerRadius(12.dp)
                    .then(
                        (item?.id ?: entry.releases.firstOrNull()?.mediaId)?.let { mediaId ->
                            GlanceModifier.clickable(
                                onClick = LocalContext.current.openDeepLink(
                                    DeepLink(
                                        type = DeepLink.Type.ANIME,
                                        id = mediaId.toString(),
                                    )
                                )
                            )
                        } ?: GlanceModifier
                    )
            ) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = entry.title ?: glanceStringResource(R.string.release_provider_only),
                        style = TextStyle(
                            color = textColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = entry.scheduleLine(context),
                        style = TextStyle(
                            color = textColor,
                            fontSize = 13.sp
                        ),
                        maxLines = 1
                    )
                }
            }

        }
    }

    private fun Context.openDeepLink(deepLink: DeepLink) =
        actionStartActivity(
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                action = deepLink.type.intentAction
                putExtra("content_id", deepLink.id)
                putExtra("widget", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addCategory(deepLink.id)
            } ?: Intent()
        )

    @OptIn(ExperimentalGlancePreviewApi::class)
    @Preview(widthDp = 255, heightDp = 150)
    @Composable
    private fun Preview() {
        val day = LocalDate.of(2026, 10, 17)
        val sample = listOf("Anime One", "Anime Two").mapIndexed { index, title ->
            CalendarEntry(
                date = day, key = "preview-$index", media = null, releases = emptyList(), airingEvent = null, title = title,
                dimmed = index == 0, checked = index == 0, episode = index + 1, timeText = "1$index:10", trackLabel = "SUB",
            )
        }
        GlanceTheme {
            Content(entries = sample, isColored = true, now = Instant.parse("2026-10-17T12:00:00Z"))
        }
    }

    companion object {
        val IS_COLORED_KEY = booleanPreferencesKey("is_colored")

        private const val RELEASE_DAYS = 14L
        private const val AIRING_DAYS = 5
        private const val AIRING_PAGES = 3
        private const val MEDIA_BY_IDS_PAGE = 50
        private val MEDIA_TTL: java.time.Duration = java.time.Duration.ofMinutes(30)

        /** Cover and title of what the source names, kept for the widget's process; the refresh button asks again. */
        private val mediaCache = ConcurrentHashMap<Int, Pair<ExploreMedia, Instant>>()

        @Volatile private var freshRequested = false

        /** The refresh button asks for AniList's current answer; the next redraw takes it and clears the request. */
        internal fun requestFresh() { freshRequested = true }
        private fun takeFreshRequest(): Boolean = freshRequested.also { freshRequested = false }
    }
}


/**
 * The item id of a row. Glance reserves the ids below Long.MIN_VALUE / 2 and throws for them, and a 64-bit hash lands there
 * about a quarter of the time: the widget did not draw at all then. Shifted into 1..2^62 the id is always allowed, and 0
 * stays the header's.
 */
internal fun stableWidgetId(eventKey: String): Long {
    var hash = 1_125_899_906_842_597L
    eventKey.forEach { character ->
        hash = hash * 31L + character.code
    }
    return (hash ushr 2) + 1L
}

/** "Ep 3 airing at 03:10 · SUB/DUB ✓", the wording of the calendar tab. */
private fun CalendarEntry.scheduleLine(context: Context): String {
    val episode = episode
    val time = timeText
    if (episode == null || time == null) return time.orEmpty().ifBlank { context.getString(R.string.unknown) }
    return context.getString(R.string.episode_airing_at, episode, time) +
        (trackLabel?.let { " · $it" } ?: "") + if (checked) " ✓" else ""
}

/** The color of [this] moved toward [surface], the way the calendar tab lets a dimmed entry give way to its background. */
internal fun Color.dimmedInto(surface: Color, amount: Float = DIM_AMOUNT): Color = Color(
    red = red + (surface.red - red) * amount,
    green = green + (surface.green - green) * amount,
    blue = blue + (surface.blue - blue) * amount,
    alpha = 1f,
)

private const val DIM_AMOUNT = 0.45f

private val REDRAW_ACTIONS = setOf(
    ACTION_RELEASE_DATA_UPDATED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
)

class AiringWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AiringWidget()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REDRAW_ACTIONS) {
            super.onReceive(context, intent)
            return
        }
        // A refresh of the release source ended, or the day changed: every widget reads the rows again. At midnight the
        // day before drops out and the new day moves to the top.
        val pending = goAsync()
        scope.launch {
            try {
                AppLog.i("widget") { "redraw for ${intent.action}" }
                glanceAppWidget.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The refresh button of the widget. It runs in the app without opening it: the release source is asked now (the same manual
 * refresh as in the app, which also plans the checks and notifications), and the widget redraws at once with AniList's data.
 * The widget redraws again when the release work reports that it has ended.
 */
class RefreshAiringWidgetAction : ActionCallback, KoinComponent {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val scheduler: ExtensionReleaseRefreshScheduler by inject()
        AppLog.i("widget") { "refresh tapped: release source and AniList are asked" }
        AiringWidget.requestFresh()
        runCatching { scheduler.scheduleNow() }.onFailure {
            AppLog.w("widget", it) { "release refresh could not be scheduled: ${it.javaClass.simpleName}" }
        }
        AiringWidget().update(context, glanceId)
    }
}
