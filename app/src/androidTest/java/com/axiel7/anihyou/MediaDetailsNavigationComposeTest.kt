package com.axiel7.anihyou

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.resources.R as CoreR
import com.axiel7.anihyou.feature.mediadetails.EpisodeMappingSaveState
import com.axiel7.anihyou.feature.mediadetails.composables.ProviderEpisodeMappingDialog
import com.axiel7.anihyou.feature.mediadetails.composables.ProviderOverviewField
import com.axiel7.anihyou.feature.mediadetails.composables.ProviderWatchNextFloatingActionButton
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ProviderNavigationTargetV1
import com.axiel7.anihyou.release.core.navigation.NavigationProvider
import com.axiel7.anihyou.release.core.navigation.NavigationUnavailableReason
import com.axiel7.anihyou.release.core.navigation.ProviderCoordinate
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationCoordinator
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationGateway
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationProductState
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationResult
import com.axiel7.anihyou.release.core.navigation.WatchNextState
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import androidx.activity.ComponentActivity
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDetailsNavigationComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun watchNextButtonKeepsPositiveBehindVisibleWhileTargetIsResolving() = runBlocking {
        val fixture = navigationFixture()
        val target = fixture.coordinator.resolve(fixture.coordinate, NavigationTargetKind.EPISODE)
            .let { (it as ProviderNavigationResult.Ready).target }
        val candidate = WatchNextState.Candidate(
            behindCount = 3,
            episode = BigDecimal("15"),
            provider = fixture.provider,
            coordinate = fixture.coordinate,
            tracks = listOf("DE_SUB"),
        )
        val navigationState = mutableStateOf(
            ProviderNavigationProductState(watchNext = candidate, watchTarget = null)
        )
        val clicks = AtomicInteger()
        val label = "3"

        composeRule.setContent {
            MaterialTheme {
                ProviderWatchNextFloatingActionButton(navigationState.value) { clicks.incrementAndGet() }
            }
        }

        composeRule.onNodeWithText(label).assertIsDisplayed()
        composeRule.runOnIdle {
            navigationState.value = navigationState.value.copy(watchTarget = target)
        }
        composeRule.onNodeWithText(label).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, clicks.get()) }

        composeRule.runOnIdle {
            navigationState.value = navigationState.value.copy(watchNext = candidate.copy(behindCount = 0))
        }
        composeRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun unavailableWatchNextNeverShowsAnAiringFallbackButton() {
        val clicks = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                ProviderWatchNextFloatingActionButton(
                    ProviderNavigationProductState(watchNext = WatchNextState.Unavailable(NavigationUnavailableReason.MISSING_MAPPING)),
                ) { clicks.incrementAndGet() }
            }
        }
        composeRule.onNodeWithTag("provider-watch-next-fab").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, clicks.get()) }
    }

    @Test
    fun sourceChipPrecedesExternalStreamingChipsWithoutSeparateProviderHeading() {
        val fixture = navigationFixture()
        composeRule.setContent {
            MaterialTheme {
                ProviderOverviewField(
                    navigationState = ProviderNavigationProductState(providers = listOf(fixture.provider), activeReleaseSource = fixture.provider.key),
                    onOpenProvider = {}, onChooseProvider = {},
                    streamingLinks = { androidx.compose.material3.AssistChip(onClick = {}, label = { androidx.compose.material3.Text("HIDIVE") }) },
                )
            }
        }
        val provider = composeRule.onNodeWithTag("provider-overview-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val external = composeRule.onNodeWithText("HIDIVE").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(provider.top <= external.top)
        if (provider.top == external.top) org.junit.Assert.assertTrue(provider.left < external.left)
        composeRule.onNodeWithText(appContext().getString(CoreR.string.provider_sources)).assertDoesNotExist()
        composeRule.onNodeWithText(appContext().getString(CoreR.string.active_release_source)).assertDoesNotExist()
    }

    @Test
    fun compactWatchNextIsLeftOfEditAtTheSameHeightAndShowsOnlyTheCount() {
        val fixture = navigationFixture()
        val candidate = WatchNextState.Candidate(2, BigDecimal("15"), fixture.provider,
            fixture.coordinate, listOf("DE_SUB"))
        val state = mutableStateOf(ProviderNavigationProductState(watchNext = candidate))
        val clicks = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.material3.Scaffold(
                    floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
                    floatingActionButton = {
                        com.axiel7.anihyou.feature.mediadetails.composables.MediaDetailsFloatingActions(
                            state.value, { clicks.incrementAndGet() },
                        ) {
                            androidx.compose.material3.ExtendedFloatingActionButton(
                                onClick = {}, modifier = androidx.compose.ui.Modifier.testTag("media-edit-fab"),
                            ) { androidx.compose.material3.Text("Bearbeiten") }
                        }
                    },
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding))
                }
            }
        }
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText(appContext().getString(CoreR.string.watch_next_behind_count, 2)).assertDoesNotExist()
        val next = composeRule.onNodeWithTag("provider-watch-next-fab").fetchSemanticsNode().boundsInRoot
        val edit = composeRule.onNodeWithTag("media-edit-fab").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(next.left >= 0 && next.right < edit.left)
        org.junit.Assert.assertTrue(kotlin.math.abs(next.center.y - edit.center.y) <= 1f)
        composeRule.storeVerifiedScreenshot(composeRule.activity,
            java.io.File(composeRule.activity.getExternalFilesDir(null), "ep07-ui"), "details-floating-actions") {
            composeRule.onNodeWithTag("provider-watch-next-fab").assertIsDisplayed()
            composeRule.onNodeWithTag("media-edit-fab").assertIsDisplayed()
        }
        composeRule.runOnIdle { state.value = state.value.copy(loading = true) }
        composeRule.onNodeWithTag("provider-watch-next-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("provider-watch-next-fab").performClick()
        composeRule.runOnIdle { assertEquals(0, clicks.get()) }
        composeRule.runOnIdle { state.value = state.value.copy(loading = false,
            watchNext = WatchNextState.Unavailable(NavigationUnavailableReason.NO_RELEASED_UNWATCHED)) }
        composeRule.onNodeWithTag("provider-watch-next-fab").assertDoesNotExist()
        composeRule.onNodeWithTag("media-edit-fab").assertIsDisplayed()
    }

    @Test
    fun passiveMissingEpisodeMappingAddsNoNoiseBelowStreamingChips() {
        val fixture = navigationFixture()
        composeRule.setContent {
            MaterialTheme {
                ProviderOverviewField(ProviderNavigationProductState(providers = listOf(fixture.provider),
                    activeReleaseSource = fixture.provider.key,
                    watchNext = WatchNextState.Unavailable(NavigationUnavailableReason.MISSING_MAPPING)), {}, {})
            }
        }
        composeRule.onNodeWithText(fixture.provider.displayName).assertIsDisplayed()
        composeRule.onNodeWithText(appContext().getString(CoreR.string.active_release_source)).assertDoesNotExist()
        composeRule.onNodeWithText(appContext().getString(CoreR.string.navigation_missing_mapping)).assertDoesNotExist()
    }

    @Test
    fun episodeMappingDialogRejectsUrlsAndEmitsExactSeasonOffset() {
        val fixture = navigationFixture()
        val result = AtomicReference<MappingInput?>(null)

        composeRule.setContent {
            MaterialTheme {
                ProviderEpisodeMappingDialog(
                    provider = fixture.provider,
                    mediaId = 42,
                    saveState = EpisodeMappingSaveState.IDLE,
                    onDismiss = {},
                    onSave = { seriesKey, season, providerFirst, anilistFirst, count ->
                        result.set(MappingInput(seriesKey, season, providerFirst, anilistFirst, count))
                    },
                )
            }
        }

        composeRule.onNodeWithText(
            appContext().getString(CoreR.string.episode_mapping_dialog_title, "Fixture provider")
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("provider-episode-series-key")
            .performTextInput("https://watch.example.org/anime/fixture")
        composeRule.onNodeWithText(appContext().getString(CoreR.string.save)).assertIsNotEnabled()

        composeRule.onNodeWithTag("provider-episode-series-key").performTextClearance()
        composeRule.onNodeWithTag("provider-episode-series-key").performTextInput("fixture-series")
        composeRule.onNodeWithTag("provider-episode-season").performScrollTo().performTextInput("9999")
        composeRule.onNodeWithTag("provider-episode-provider-first").performScrollTo().performTextInput("9999")
        composeRule.onNodeWithTag("provider-episode-anilist-first").performScrollTo().performTextInput("9999")
        composeRule.onNodeWithTag("provider-episode-count").performScrollTo().performTextInput("2")
        composeRule.onNodeWithText(appContext().getString(CoreR.string.save)).assertIsNotEnabled()

        composeRule.onNodeWithTag("provider-episode-season").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("provider-episode-provider-first").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("provider-episode-anilist-first").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("provider-episode-count").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("provider-episode-season").performScrollTo().performTextInput("2")
        composeRule.onNodeWithTag("provider-episode-provider-first").performScrollTo().performTextInput("3")
        composeRule.onNodeWithTag("provider-episode-anilist-first").performScrollTo().performTextInput("15")
        composeRule.onNodeWithTag("provider-episode-count").performScrollTo().performTextInput("12")
        composeRule.onNodeWithText(appContext().getString(CoreR.string.save)).performClick()

        assertEquals(MappingInput("fixture-series", 2, 3, 15, 12), result.get())
    }

    @Test
    fun providerOverviewFieldRendersSignedNamesAndOnlyTheActiveSourceMarker() {
        val overviewOne = provider("overview-one", "Signed provider one")
        val overviewTwo = provider("overview-two", "Signed provider two")
        val episodeOnly = provider(
            "episode-only",
            "Episode only provider",
            capabilities = setOf(NavigationCapability.EPISODE_NAVIGATION),
        )
        val disabledProvider = provider("disabled", "Disabled provider")
        val state = ProviderNavigationProductState(
            // The product repository passes only visible installed providers to the UI.
            providers = listOf(overviewOne, overviewTwo, episodeOnly),
            activeReleaseSource = overviewTwo.key,
        )
        val openedProvider = AtomicReference<ExtensionSelectionKey?>(null)
        val activeLabel = appContext().getString(CoreR.string.active_release_source)

        composeRule.setContent {
            MaterialTheme {
                ProviderOverviewField(
                    navigationState = state,
                    onOpenProvider = { openedProvider.set(it) },
                    onChooseProvider = {},
                )
            }
        }

        composeRule.onNodeWithTag("provider-overview-field").assertIsDisplayed()
        composeRule.onNodeWithText(overviewOne.displayName).assertIsDisplayed()
        composeRule.onNodeWithText(overviewTwo.displayName).assertIsDisplayed()
        composeRule.onNodeWithText("Episode only provider").assertDoesNotExist()
        composeRule.onNodeWithText(disabledProvider.displayName).assertDoesNotExist()
        composeRule.onAllNodesWithText(activeLabel).assertCountEquals(0)
        composeRule.onNodeWithText(
            appContext().getString(CoreR.string.episode_mapping_for_provider, overviewOne.displayName)
        ).assertDoesNotExist()
        composeRule.onNodeWithTag("provider-overview-0").performClick()
        composeRule.runOnIdle { assertEquals(overviewTwo.key, openedProvider.get()) }
    }

    @Test
    fun providerOverviewFieldShowsAnExplicitNavigationFailure() {
        val state = ProviderNavigationProductState(
            failure = NavigationUnavailableReason.TRACK_UNAVAILABLE,
            providers = listOf(provider("fixture", "Fixture provider")),
        )

        composeRule.setContent {
            MaterialTheme {
                ProviderOverviewField(
                    navigationState = state,
                    onOpenProvider = {},
                    onChooseProvider = {},
                )
            }
        }

        composeRule.onNodeWithText(appContext().getString(CoreR.string.navigation_track_unavailable))
            .assertIsDisplayed()
    }

    @Test
    fun providerChooserAppearsOnlyAfterExplicitActionAndSelectsTheChosenProvider() {
        val first = provider("watch-one", "Watch provider one", setOf(NavigationCapability.EPISODE_NAVIGATION))
        val second = provider("watch-two", "Watch provider two", setOf(NavigationCapability.EPISODE_NAVIGATION))
        val state = ProviderNavigationProductState(
            watchNext = WatchNextState.ChooseProvider(listOf(first, second)),
        )
        val chosenProvider = AtomicReference<ExtensionSelectionKey?>(null)

        composeRule.setContent {
            MaterialTheme {
                ProviderOverviewField(
                    navigationState = state,
                    onOpenProvider = {},
                    onChooseProvider = { chosenProvider.set(it) },
                )
            }
        }

        composeRule.onNodeWithTag("provider-watch-next-chooser-dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("provider-watch-next-chooser").performClick()
        composeRule.onNodeWithTag("provider-watch-next-chooser-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(second.displayName).performClick()
        composeRule.runOnIdle { assertEquals(second.key, chosenProvider.get()) }
        composeRule.onNodeWithTag("provider-watch-next-chooser-dialog").assertDoesNotExist()
    }

    private fun appContext() = ApplicationProvider.getApplicationContext<Context>()

    private fun navigationFixture(): NavigationFixture {
        val provider = provider()
        val key = provider.key
        val coordinate = ProviderCoordinate(
            key = key,
            mediaId = 42,
            canonicalEpisode = BigDecimal("15"),
            seriesKey = "fixture-series",
            sourceSeason = 2,
            providerEpisode = "3",
            availableTracks = setOf("DE_SUB"),
        )
        val policy = FakeProductPolicyRepository(key)
        val gateway = object : ProviderNavigationGateway {
            override suspend fun providers() = listOf(provider)

            override suspend fun dispatch(
                provider: NavigationProvider,
                request: NavigationContextV1,
                generation: String,
            ) = ProviderNavigationTargetV1(
                schemaVersion = 1,
                extensionId = request.extensionId,
                providerId = request.providerId,
                targetKind = request.targetKind,
                providerSeriesKey = request.providerSeriesKey,
                sourceSeason = request.sourceSeason,
                providerEpisode = request.providerEpisode,
                track = request.track,
                url = "https://watch.example.org/anime/fixture/season/2/episode/3",
                requestId = "fixture-navigation-request",
                sourceHash = "b".repeat(64),
                diagnostics = emptyList(),
            )
        }
        return NavigationFixture(provider, coordinate, ProviderNavigationCoordinator(gateway, policy))
    }

    private fun provider(
        suffix: String = "fixture",
        displayName: String = "Fixture provider",
        capabilities: Set<NavigationCapability> = setOf(
            NavigationCapability.OVERVIEW_NAVIGATION,
            NavigationCapability.EPISODE_NAVIGATION,
        ),
    ) = NavigationProvider(
        key = ExtensionSelectionKey(
            sourceId = "fixture-source-$suffix",
            extensionId = "example.extension.$suffix",
            publisherId = "fixture-publisher",
            providerId = "example.provider.$suffix",
        ),
        displayName = displayName,
        packageDigest = "a".repeat(64),
        capabilities = capabilities,
        allowedHosts = setOf("watch.example.org"),
        supportedTracks = setOf("DE_SUB"),
    )

    private data class NavigationFixture(
        val provider: NavigationProvider,
        val coordinate: ProviderCoordinate,
        val coordinator: ProviderNavigationCoordinator,
    )

    private data class MappingInput(
        val seriesKey: String,
        val providerSeason: Int,
        val providerFirst: Int,
        val anilistFirst: Int,
        val count: Int,
    )

    private class FakeProductPolicyRepository(key: ExtensionSelectionKey) : ExtensionProductPolicyRepository {
        private val mutablePolicy = MutableStateFlow(
            ExtensionProductPolicy(
                generation = 1,
                activeReleaseSource = key,
                preferredNavigationProvider = key,
                preferences = mapOf(
                    key to ExtensionPreferences(
                        enabledTracks = setOf("DE_SUB"),
                        preferredTrackOrder = listOf("DE_SUB"),
                    )
                ),
            )
        )
        override val policy: StateFlow<ExtensionProductPolicy> = mutablePolicy.asStateFlow()

        override suspend fun selectActiveSource(key: ExtensionSelectionKey?) {
            mutablePolicy.value = mutablePolicy.value.copy(activeReleaseSource = key)
        }

        override suspend fun selectNavigationProvider(key: ExtensionSelectionKey?) {
            mutablePolicy.value = mutablePolicy.value.copy(preferredNavigationProvider = key)
        }

        override suspend fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) {
            mutablePolicy.value = mutablePolicy.value.copy(preferences = mutablePolicy.value.preferences + (key to preferences))
        }

        override suspend fun invalidateSource(sourceId: String) {
            mutablePolicy.value = mutablePolicy.value.copy(
                activeReleaseSource = mutablePolicy.value.activeReleaseSource?.takeUnless { it.sourceId == sourceId },
                preferredNavigationProvider = mutablePolicy.value.preferredNavigationProvider
                    ?.takeUnless { it.sourceId == sourceId },
            )
        }

        override suspend fun <T> withCurrentSelection(snapshot: ExtensionProductPolicy, block: suspend () -> T): T? =
            if (mutablePolicy.value == snapshot) block() else null
    }
}
