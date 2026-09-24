package com.axiel7.anihyou.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.apollographql.cache.normalized.FetchPolicy
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
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.core.model.DeepLink
import com.axiel7.anihyou.core.model.media.exampleAiringWidgetEntry
import com.axiel7.anihyou.core.network.AiringWidgetQuery
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

private val ANI_WORLD_SOURCE_ZONE: ZoneId = ReleaseSourceTimePolicy.ANI_WORLD_ZONE

private data class WidgetAiringItem(
    val eventKey: String,
    val media: AiringWidgetQuery.Medium?,
    val releaseRows: List<ReleaseUiCalendarItem>,
)

class AiringWidget : GlanceAppWidget(), KoinComponent {

    private val networkVariables: NetworkVariables by inject()
    private val defaultPreferencesRepository: DefaultPreferencesRepository by inject()
    private val mediaRepository: MediaRepository by inject()
    private val releasePresentationRepository: ReleasePresentationRepository by inject()
    private val clock: Clock by inject()

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        networkVariables.accessToken = defaultPreferencesRepository.accessToken.first()

        val today = clock.instant().atZone(ANI_WORLD_SOURCE_ZONE).toLocalDate()
        val accountId = defaultPreferencesRepository.userId.first()?.toLong()
        val providerRows = accountId?.let {
            runCatching {
                releasePresentationRepository.currentCalendar(
                    accountId = it,
                    range = today..today.plusDays(14),
                )
            }.getOrDefault(emptyList())
        }.orEmpty()
            .filter { it.isAuthoritative }

        val cachedResult = mediaRepository.getAiringWidgetData(
            page = 1,
            perPage = 50,
            fetchPolicy = FetchPolicy.CacheOnly,
        )
        provideContent {
            val scope = rememberCoroutineScope()
            val prefs = currentState<Preferences>()
            val isColored = prefs[IS_COLORED_KEY] ?: true
            val resultState = remember { mutableStateOf(cachedResult) }
            LaunchedEffect(providerRows.isEmpty()) {
                if (providerRows.isEmpty()) {
                    resultState.value = mediaRepository.getAiringWidgetData(
                        page = 1,
                        perPage = 50,
                        fetchPolicy = FetchPolicy.NetworkFirst,
                    )
                }
            }

            GlanceTheme(colors = DynamicThemeColorProviders) {
                Content(
                    result = resultState.value,
                    isColored = isColored,
                    onRefresh = { scope.launch { update(context, id) } },
                    providerRows = providerRows,
                )
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        networkVariables.accessToken = defaultPreferencesRepository.accessToken.first()

        val result = mediaRepository.getAiringWidgetData(page = 1, perPage = 50)
            .takeIf { it is DataResult.Success }
            ?: DataResult.Success(
                data = listOf(
                    exampleAiringWidgetEntry,
                    exampleAiringWidgetEntry,
                    exampleAiringWidgetEntry,
                    exampleAiringWidgetEntry,
                )
            )

        provideContent {
            GlanceTheme(colors = DynamicThemeColorProviders) {
                Content(
                    result = result,
                    isColored = true,
                    onRefresh = {},
                )
            }
        }
    }

    @Composable
    private fun Content(
        result: DataResult<List<AiringWidgetQuery.Medium>>,
        isColored: Boolean,
        onRefresh: () -> Unit,
        providerRows: List<ReleaseUiCalendarItem> = emptyList(),
    ) {
        val metadataById = (result as? DataResult.Success)
            ?.data
            .orEmpty()
            .associateBy { it.id }
        val widgetItems = if (providerRows.isNotEmpty()) {
            providerRows.map { row ->
                WidgetAiringItem(
                    eventKey = row.eventKey,
                    media = row.mediaId?.let(metadataById::get),
                    releaseRows = listOf(row),
                )
            }
        } else {
            (result as? DataResult.Success)
                ?.data
                .orEmpty()
                .map { item ->
                    WidgetAiringItem(
                        eventKey = "anilist-media-" + item.id,
                        media = item,
                        releaseRows = emptyList(),
                    )
                }
        }

        fun providerTimestampFor(item: WidgetAiringItem): Long? {
            val providerTimestamp = item.releaseRows.minOfOrNull { release ->
                release.forecastAt?.epochSecond
                    ?: release.sourceDate?.atStartOfDay(ANI_WORLD_SOURCE_ZONE)?.toEpochSecond()
                    ?: Long.MAX_VALUE
            }?.takeIf { it != Long.MAX_VALUE }
            return if (item.releaseRows.isNotEmpty()) {
                providerTimestamp
            } else {
                item.media?.nextAiringEpisode?.airingAt?.toLong()
            }
        }

        val todayString = clock.instant().atZone(ANI_WORLD_SOURCE_ZONE).toLocalDate().toString()

        Scaffold(
            horizontalPadding = 0.dp
        ) {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                item(itemId = 0) {
                    Header(onRefresh = onRefresh)
                }
                if (result is DataResult.Success || providerRows.isNotEmpty()) {
                    itemsIndexed(
                        items = widgetItems,
                        itemId = { _, item -> stableWidgetId(item.eventKey) }
                    ) { index, item ->
                        val currentDay = providerTimestampFor(item)
                            ?.let { it.sourceDateString("yyyy-MM-dd") }
                        val previousDay = if (index > 0) {
                            providerTimestampFor(widgetItems[index - 1])
                                ?.let { it.sourceDateString("yyyy-MM-dd") }
                        } else null

                        val showDate = currentDay != previousDay
                        val isToday = currentDay == todayString
                        ItemView(
                            item = item.media,
                            showDate = showDate,
                            isToday = isToday,
                            isColored = isColored,
                            releasePresentations = item.releaseRows,
                        )
                    }
                } else {
                    item {
                        Column(
                            modifier = GlanceModifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (result is DataResult.Loading) {
                                CircularProgressIndicator(color = GlanceTheme.colors.primary)
                            } else if (result is DataResult.Error) {
                                Text(
                                    text = result.message,
                                    modifier = GlanceModifier.padding(bottom = 8.dp),
                                    style = TextStyle(color = GlanceTheme.colors.onSurface)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(onRefresh: () -> Unit) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
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
                modifier = GlanceModifier.defaultWeight()
            )

            Box(
                modifier = GlanceModifier
                    .width(54.dp)
                    .height(32.dp)
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(20.dp)
                    .clickable(onRefresh),
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
        item: AiringWidgetQuery.Medium?,
        showDate: Boolean,
        isToday: Boolean,
        isColored: Boolean,
        releasePresentations: List<ReleaseUiCalendarItem> = emptyList(),
        releasePresentation: ReleaseUiCalendarItem? = null,
    ) {
        val presentations = if (releasePresentations.isNotEmpty()) {
            releasePresentations
        } else {
            releasePresentation?.let(::listOf).orEmpty()
        }
        val providerTimestamp = presentations.minOfOrNull { release ->
            release.forecastAt?.epochSecond
                ?: release.sourceDate?.atStartOfDay(ANI_WORLD_SOURCE_ZONE)?.toEpochSecond()
                ?: Long.MAX_VALUE
        }?.takeIf { it != Long.MAX_VALUE }
        val timestamp = if (presentations.isNotEmpty()) {
            providerTimestamp
        } else {
            item?.nextAiringEpisode?.airingAt?.toLong()
        }
        val sourceDateTime = timestamp?.let {
            Instant.ofEpochSecond(it).atZone(ANI_WORLD_SOURCE_ZONE)
        }
        val dayOfWeek = sourceDateTime
            ?.format(DateTimeFormatter.ofPattern("E", Locale.getDefault()))
            .orEmpty()
        val dayOfMonth = sourceDateTime
            ?.format(DateTimeFormatter.ofPattern("d", Locale.getDefault()))
            .orEmpty()

        val baseMediaColor = remember(item?.coverImage?.color, isColored) {
            if (isColored) {
                item?.coverImage?.color?.takeIf { it.isNotBlank() }?.let { hex ->
                    runCatching { colorFromHex(hex) }.getOrNull()
                }
            } else null
        }

        val primaryColor = GlanceTheme.colors.primary.getColor(LocalContext.current)

        val backgroundModifier = if (baseMediaColor != null) {
            GlanceModifier.background(baseMediaColor.harmonize(primaryColor).darken(2f))
        } else {
            GlanceModifier.background(GlanceTheme.colors.secondaryContainer)
        }
        val defaultTextColor = GlanceTheme.colors.onSecondaryContainer

        val textColor = remember(baseMediaColor, defaultTextColor) {
            baseMediaColor?.let {
                val tone = TonalPalette.from(it).toneColor(95)
                ColorProvider(day = tone, night = tone)
            } ?: defaultTextColor
        }

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
                if (showDate) {
                    val dateModifier = if (isToday) {
                        GlanceModifier
                            .size(38.dp)
                            .background(GlanceTheme.colors.primary)
                            .cornerRadius(38.dp)
                    } else {
                        GlanceModifier
                            .size(38.dp)
                            .background(Color.Transparent)
                    }

                    Column(
                        modifier = dateModifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (isToday) {
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
                        } else {
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
                    Spacer(modifier = GlanceModifier.width(36.dp))
                }
            }

            Column(
                modifier = backgroundModifier
                    .defaultWeight()
                    .cornerRadius(12.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .then(
                        item?.id?.let { mediaId ->
                            GlanceModifier.clickable(
                                actionStartActivity(
                                    LocalContext.current.packageManager
                                        .getLaunchIntentForPackage(APP_PACKAGE_NAME)
                                        ?.apply {
                                            action = DeepLink.Type.ANIME.intentAction
                                            putExtra("content_id", mediaId)
                                            putExtra("widget", true)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                            addCategory(mediaId.toString())
                                        } ?: Intent()
                                )
                            )
                        } ?: GlanceModifier
                    )
            ) {
                Text(
                    text = item?.title?.userPreferred.orEmpty()
                        .ifBlank { glanceStringResource(R.string.release_provider_only) },
                    style = TextStyle(
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
                val providerText = presentations
                    .filter { it.isAuthoritative }
                    .takeIf { it.isNotEmpty() }
                    ?.let { releases ->
                        val context = LocalContext.current
                        releases.joinToString("\n") { it.widgetText(context) }
                    }
                Text(
                    text = providerText ?: item?.nextAiringEpisode?.let { nextAiringEpisode ->
                        glanceStringResource(
                            R.string.episode_airing_at,
                            nextAiringEpisode.episode,
                            nextAiringEpisode.airingAt.toLong().sourceTimeString()
                        )
                    } ?: glanceStringResource(R.string.unknown),
                    style = TextStyle(
                        color = textColor,
                        fontSize = 13.sp
                    ),
                    maxLines = 1
                )
            }

        }
    }

    @OptIn(ExperimentalGlancePreviewApi::class)
    @Preview(widthDp = 255, heightDp = 150)
    @Composable
    private fun Preview() {
        GlanceTheme {
            Content(
                result = DataResult.Success(
                    data = listOf(
                        exampleAiringWidgetEntry,
                        exampleAiringWidgetEntry,
                        exampleAiringWidgetEntry,
                        exampleAiringWidgetEntry,
                    )
                ),
                isColored = true,
                onRefresh = {}
            )
        }
    }

    companion object {
        val IS_COLORED_KEY = booleanPreferencesKey("is_colored")
    }
}


private fun Long.sourceDateString(pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .format(Instant.ofEpochSecond(this).atZone(ANI_WORLD_SOURCE_ZONE))

private fun Long.sourceTimeString(): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        .format(Instant.ofEpochSecond(this).atZone(ANI_WORLD_SOURCE_ZONE))

private fun stableWidgetId(eventKey: String): Long {
    var hash = 1_125_899_906_842_597L
    eventKey.forEach { character ->
        hash = hash * 31L + character.code
    }
    return hash.takeIf { it != 0L } ?: 1L
}

private fun ReleaseUiCalendarItem.widgetText(context: Context): String {
    val label = when (val value = installment) {
        is Installment.Episode -> value.fraction?.let {
            context.getString(
                R.string.release_installment_episode_fraction,
                value.number,
                it,
            )
        } ?: context.getString(R.string.release_installment_episode, value.number)
        is Installment.Film -> value.number?.let {
            context.getString(R.string.release_installment_film_number, it)
        } ?: context.getString(R.string.release_installment_film)
        is Installment.Special -> when (stream.releaseKind) {
            ReleaseKind.OVA -> context.getString(R.string.release_installment_ova)
            ReleaseKind.ONA -> context.getString(R.string.release_installment_ona)
            else -> value.number?.let {
                context.getString(R.string.release_installment_special_number, it)
            } ?: context.getString(R.string.release_installment_special)
        }
    }
    return if (confirmed) {
        context.getString(R.string.release_schedule_confirmed_installment, label)
    } else {
        forecastAt?.epochSecond?.let {
            context.getString(
                R.string.release_schedule_next_at,
                label,
                it.sourceTimeString(),
            )
        } ?: context.getString(R.string.release_schedule_next, label)
    }
}

class AiringWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AiringWidget()
}
