package com.axiel7.anihyou

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.axiel7.anihyou.feature.settings.source.ExtensionDataSourcePreferences
import com.axiel7.anihyou.feature.settings.source.ExtensionDiagnostics
import com.axiel7.anihyou.feature.settings.source.ExtensionProviderDisplay
import com.axiel7.anihyou.feature.settings.source.ExtensionSourcesEvent
import com.axiel7.anihyou.feature.settings.source.ExtensionSourcesSettingsSection
import com.axiel7.anihyou.feature.settings.source.ExtensionSourcesUiState
import com.axiel7.anihyou.feature.settings.source.ExtensionStatistics
import com.axiel7.anihyou.feature.settings.source.ExtensionTrustUnavailableNotice
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionRollbackTarget
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.ExtensionUpdateFailure
import com.axiel7.anihyou.release.core.source.ExtensionUpdateState
import com.axiel7.anihyou.release.core.source.InstalledPackageStatus
import com.axiel7.anihyou.release.core.source.SourceExtension
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtensionUpdateComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun currentUpdateInstallProgressAndSourceBusyStateAreExplicit() {
        val currentKey = key("current")
        val updateKey = key("update")
        val installKey = key("install")
        val blockedKey = key("blocked")
        val sources = listOf(
            source(currentKey.sourceId, current(currentKey)),
            source(updateKey.sourceId, update(updateKey)),
            source(installKey.sourceId, notInstalled(installKey)),
            source(blockedKey.sourceId, notInstalled(blockedKey).copy(activationAllowed = false)),
        )
        val event = RecordingEvent()
        val state = mutableStateOf(ExtensionSourcesUiState(sources = sources))
        composeManage(state, event)

        composeRule.onNodeWithText("Signed name: Signed: current", substring = true).assertIsDisplayedCompat()
        composeRule.onNodeWithTag("extension-installed-version-${currentKey.extensionId}")
            .assertTextContains("Installed version: 1.0.0")
        composeRule.onNodeWithTag("extension-latest-version-${currentKey.extensionId}")
            .assertTextContains("Latest authenticated version: 1.0.0")
        composeRule.onNodeWithTag("extension-action-${currentKey.extensionId}").assertTextContains("Check")
        composeRule.onNodeWithTag("extension-action-${updateKey.extensionId}").assertTextContains("Update")
        composeRule.onNodeWithTag("extension-action-${installKey.extensionId}").assertTextContains("Install extension")
        composeRule.onNodeWithTag("extension-action-${blockedKey.extensionId}").assertDoesNotExist()

        composeRule.onNodeWithTag("extension-action-${updateKey.extensionId}").performScrollTo().performClick()
        assertEquals(listOf(updateKey.sourceId to updateKey.extensionId), event.activations)
        composeRule.onNodeWithTag("extension-action-${installKey.extensionId}").performScrollTo().performClick()
        assertEquals(installKey.sourceId to installKey.extensionId, event.activations.last())

        state.value = state.value.copy(sources = sources.map { item ->
            if (item.id == updateKey.sourceId) item.copy(extensions = listOf(
                update(updateKey).copy(updateState = ExtensionUpdateState.DOWNLOADING),
            )) else item
        })
        composeRule.onNodeWithTag("extension-progress-${updateKey.extensionId}").performScrollTo().assertIsDisplayedCompat()
        val downloadingStatus = composeRule.onNodeWithTag("extension-update-state-${updateKey.extensionId}")
            .fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, downloadingStatus[SemanticsProperties.LiveRegion])
        assertEquals("Downloading signed package", downloadingStatus[SemanticsProperties.StateDescription])

        state.value = state.value.copy(busySourceIds = setOf(currentKey.sourceId))
        composeRule.onNodeWithTag("extension-action-${currentKey.extensionId}").assertDoesNotExist()
        composeRule.onNodeWithTag("extension-progress-${currentKey.extensionId}").performScrollTo().assertIsDisplayedCompat()
        val currentStatus = composeRule.onNodeWithTag("extension-update-state-${currentKey.extensionId}")
            .fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, currentStatus[SemanticsProperties.LiveRegion])
        assertEquals("Installed and current", currentStatus[SemanticsProperties.StateDescription])
    }

    @Test
    fun failedUpdateKeepsLkgCheckAndRollsBackOnlyAfterExactConfirmation() {
        val key = key("failed")
        val target = ExtensionRollbackTarget("1.0.0", "previous-good-digest-123456", "TRUSTED")
        val failing = current(key).copy(
            installedVersion = "1.1.0",
            installedDigest = "current-package-digest",
            packageGeneration = 17,
            updateState = ExtensionUpdateState.UPDATE_FAILED,
            lastUpdateFailure = ExtensionUpdateFailure.SMOKE,
            rollbackTarget = target,
            lastUpdateAt = Instant.parse("2026-10-01T12:30:00Z"),
            lastUpdateResult = "FAILED",
        )
        val state = mutableStateOf(ExtensionSourcesUiState(sources = listOf(source(
            key.sourceId, failing, ExtensionSourceStatus.ERROR, ExtensionSourceFailure.NETWORK,
        ))))
        val event = RecordingEvent()
        composeManage(state, event)

        composeRule.onNodeWithTag("extension-update-failure-${key.extensionId}")
            .assertTextContains("Package health check failed", substring = true)
        composeRule.onNodeWithTag("extension-update-state-${key.extensionId}")
            .assertTextContains("Update failed", substring = true)
        composeRule.onNodeWithTag("extension-action-${key.extensionId}").assertTextContains("Check")
        composeRule.onNodeWithTag("extension-rollback-${key.extensionId}").performScrollTo().performClick()
        composeRule.onNodeWithTag("extension-installed-version-${key.extensionId}")
            .assertTextContains("Installed version: 1.1.0")
        composeRule.onNodeWithText("Previous Good version: 1.0.0", substring = true).assertIsDisplayedCompat()
        composeRule.onNodeWithText("Previous Good trust: TRUSTED").assertIsDisplayedCompat()
        composeRule.onNodeWithText("Reason: Package health check failed").assertIsDisplayedCompat()
        composeRule.onNodeWithText("This restores the exact listed package.", substring = true).assertIsDisplayedCompat()
        captureScreenshot("rollback-confirmation") {
            composeRule.onNodeWithText("This restores the exact listed package.", substring = true).assertIsDisplayedCompat()
            composeRule.onNodeWithTag("extension-rollback-confirm-${key.extensionId}").assertIsDisplayedCompat()
        }
        composeRule.onNodeWithTag("extension-rollback-confirm-${key.extensionId}").performClick()
        assertEquals(listOf(RecordingEvent.Rollback(key.sourceId, key.extensionId, 17, target.digest)), event.rollbacks)
    }

    @Test
    fun rollbackConfirmationClosesWhenGenerationActiveDigestOrTargetTrustChanges() {
        val key = key("changed-target")
        val target = ExtensionRollbackTarget("1.0.0", "previous-good-digest", "TRUSTED")
        val failing = current(key).copy(
            installedVersion = "1.1.0",
            packageGeneration = 17,
            updateState = ExtensionUpdateState.ROLLBACK_AVAILABLE,
            lastUpdateFailure = ExtensionUpdateFailure.SMOKE,
            rollbackTarget = target,
        )
        val state = mutableStateOf(ExtensionSourcesUiState(sources = listOf(source(
            key.sourceId, failing, ExtensionSourceStatus.ERROR, ExtensionSourceFailure.NETWORK,
        ))))
        val event = RecordingEvent()
        composeManage(state, event)
        for (changed in listOf(
            failing.copy(packageGeneration = 18),
            failing.copy(installedDigest = "replaced-active-package"),
            failing.copy(rollbackTarget = target.copy(trustState = "REVOKED")),
            failing.copy(rollbackTarget = null, updateState = ExtensionUpdateState.UPDATE_FAILED),
        )) {
            state.value = state.value.copy(sources = listOf(source(key.sourceId, failing)))
            composeRule.onNodeWithTag("extension-rollback-${key.extensionId}").performScrollTo().performClick()
            composeRule.onNodeWithTag("extension-rollback-confirm-${key.extensionId}").assertIsEnabled()
            state.value = state.value.copy(sources = listOf(source(key.sourceId, changed)))
            composeRule.onNodeWithTag("extension-rollback-confirm-${key.extensionId}").assertDoesNotExist()
            assertTrue(event.rollbacks.isEmpty())
        }
    }

    @Test
    fun extensionAndRepositoryRemovalEachRequireConfirmation() {
        val key = key("remove")
        val state = mutableStateOf(ExtensionSourcesUiState(sources = listOf(source(key.sourceId, current(key)))))
        val event = RecordingEvent()
        composeManage(state, event)

        composeRule.onNodeWithTag("extension-remove-${key.extensionId}").performScrollTo().performClick()
        composeRule.onNodeWithText("Remove Signed: remove and its installed package", substring = true).assertIsDisplayedCompat()
        assertTrue(event.removedExtensions.isEmpty())
        composeRule.onNodeWithTag("extension-remove-cancel-${key.extensionId}").performClick()
        assertTrue(event.removedExtensions.isEmpty())
        composeRule.onNodeWithTag("extension-remove-${key.extensionId}").performScrollTo().performClick()
        composeRule.onNodeWithTag("extension-remove-confirm-${key.extensionId}").performClick()
        assertEquals(listOf(key.sourceId to key.extensionId), event.removedExtensions)

        composeRule.onNodeWithTag("extension-source-remove").performScrollTo().performClick()
        composeRule.onNodeWithText("Remove repository?").assertIsDisplayedCompat()
        assertTrue(event.removedSources.isEmpty())
        composeRule.onNodeWithTag("extension-source-remove-confirm").performClick()
        assertEquals(listOf(key.sourceId), event.removedSources)
    }

    @Test
    fun capabilityDetailsKeepReleaseDataAndNavigationSeparate() {
        val key = key("capabilities")
        val extension = current(key).copy(capabilities = listOf(
            "CALENDAR", "RECENT", "DIRECT", "POSTPONEMENT", "OVERVIEW_NAVIGATION", "EPISODE_NAVIGATION",
        ))
        composeManage(mutableStateOf(ExtensionSourcesUiState(sources = listOf(source(key.sourceId, extension)))), RecordingEvent())

        composeRule.onNodeWithTag("extension-details-${key.extensionId}").performScrollTo().performClick()
        composeRule.onNodeWithText("Release-data capabilities").assertIsDisplayedCompat()
        composeRule.onNodeWithText("CALENDAR, RECENT, DIRECT, POSTPONEMENT").assertIsDisplayedCompat()
        composeRule.onNodeWithText("Navigation capabilities").assertIsDisplayedCompat()
        composeRule.onNodeWithText("OVERVIEW_NAVIGATION, EPISODE_NAVIGATION").assertIsDisplayedCompat()
        composeRule.onNodeWithText("Navigation access does not grant release-data authority.").assertIsDisplayedCompat()
    }

    @Test
    fun statisticsExposeUpdatePackageAndPreviousGoodFields() {
        val key = key("statistics")
        val extension = current(key).copy(
            updateState = ExtensionUpdateState.UPDATE_FAILED,
            lastUpdateFailure = ExtensionUpdateFailure.SMOKE,
            packageGeneration = 27,
            rollbackTarget = ExtensionRollbackTarget("0.9.0", "previous-good-digest", "TRUSTED"),
        )
        val state = ExtensionSourcesUiState(
            sources = listOf(source(key.sourceId, extension,
                ExtensionSourceStatus.ERROR, ExtensionSourceFailure.NETWORK)),
            productPolicy = ExtensionProductPolicy(activeReleaseSource = key),
        )
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ExtensionStatistics(state)
                }
            }
        }

        composeRule.onNodeWithText("Update state: Update failed").performScrollTo().assertIsDisplayedCompat()
        composeRule.onNodeWithText("Active package generation: 27").performScrollTo().assertIsDisplayedCompat()
        composeRule.onNodeWithText("Last update failure: Package health check failed").performScrollTo().assertIsDisplayedCompat()
        composeRule.onNodeWithText("Previous Good version: 0.9.0 · TRUSTED · previous-goo")
            .performScrollTo().assertIsDisplayedCompat()
    }

    @Test
    fun statisticsShowEachUpdateFactOnceWithoutRawDuplicates() {
        val key = key("statistics-once")
        val extension = current(key).copy(packageGeneration = 27, installedReleaseSequence = 7)
        val state = ExtensionSourcesUiState(
            sources = listOf(source(key.sourceId, extension)),
            productPolicy = ExtensionProductPolicy(activeReleaseSource = key),
            diagnostics = mapOf(key to mapOf(
                "Last Update Check" to "2026-10-01T10:00:00Z",
                "Last Successful Update" to "2026-10-01T09:00:00Z",
                "Release Sequence" to "7",
                "Rollback Available" to "true",
                "Revocation" to "",
                "Last successful sync" to "2026-10-01T09:30:00Z",
                "Role health" to "CALENDAR: HEALTHY/SUCCESS",
            )),
        )
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ExtensionStatistics(state)
                }
            }
        }
        // The localized row carries each fact exactly once.
        composeRule.onAllNodesWithText("Last update check: 2026-10-01T10:00:00Z").assertCountEquals(1)
        composeRule.onAllNodesWithText("Last successful update: 2026-10-01T09:00:00Z").assertCountEquals(1)
        composeRule.onAllNodesWithText("Installed release sequence: 7").assertCountEquals(1)
        composeRule.onAllNodesWithText("Active package generation: 27").assertCountEquals(1)
        // The raw English twins of those facts are gone from the statistics page.
        for (rawLabel in listOf("Last Update Check:", "Last Successful Update:", "Release Sequence:", "Rollback Available:", "Revocation:")) {
            composeRule.onAllNodesWithText(rawLabel, substring = true).assertCountEquals(0)
        }
        // Facts that exist only as raw values stay visible.
        composeRule.onNodeWithText("Last successful sync: 2026-10-01T09:30:00Z").performScrollTo().assertIsDisplayedCompat()
        composeRule.onNodeWithText("Role health: CALENDAR: HEALTHY/SUCCESS").performScrollTo().assertIsDisplayedCompat()
    }

    @Test
    fun unavailableTrustOffersNoAddFlowButKeepsLocalCleanup() {
        val key = key("unavailable")
        val state = mutableStateOf(ExtensionSourcesUiState(
            sources = listOf(source(key.sourceId, notInstalled(key), ExtensionSourceStatus.TRUST_UNAVAILABLE)
                .copy(extensions = emptyList())),
            trustAvailable = false,
        ))
        val event = RecordingEvent()
        composeManage(state, event)

        composeRule.onNodeWithTag("extension-source-url").assertDoesNotExist()
        composeRule.onNodeWithTag("extension-source-add").assertDoesNotExist()
        composeRule.onNodeWithTag("extension-source-refresh").performScrollTo().assertIsNotEnabled()
        // Removing local data must stay possible so nothing becomes unreachable.
        composeRule.onNodeWithTag("extension-source-remove").performScrollTo().assertIsEnabled()
        captureScreenshot("trust-unavailable-manage") {
            composeRule.onNodeWithTag("extension-source-remove").assertIsDisplayedCompat()
        }
    }

    @Test
    fun availableTrustKeepsAddAndRefreshFlow() {
        val key = key("available")
        val state = mutableStateOf(ExtensionSourcesUiState(sources = listOf(source(key.sourceId, current(key)))))
        composeManage(state, RecordingEvent())

        composeRule.onNodeWithTag("extension-source-url").assertIsDisplayedCompat()
        composeRule.onNodeWithTag("extension-source-add").assertIsDisplayedCompat()
        composeRule.onNodeWithTag("extension-source-refresh").performScrollTo().assertIsEnabled()
    }

    @Test
    fun unavailableNoticeStatesTheLimitInPlainLanguage() {
        composeRule.setContent { MaterialTheme { ExtensionTrustUnavailableNotice() } }
        composeRule.onNodeWithTag("extension-trust-unavailable").assertIsDisplayedCompat()
        composeRule.onNodeWithText("Not available in this build").assertIsDisplayedCompat()
        composeRule.onNodeWithText("cannot be added, installed or updated", substring = true).assertIsDisplayedCompat()
        captureScreenshot("trust-unavailable-notice") {
            composeRule.onNodeWithTag("extension-trust-unavailable").assertIsDisplayedCompat()
        }
    }

    @Test
    fun diagnosticCopyRemovesCredentialsAndKeepsSafeTechnicalCode() {
        val key = key("diagnostics")
        val state = ExtensionSourcesUiState(
            sources = listOf(source(key.sourceId, current(key))),
            diagnostics = mapOf(key to mapOf(
                "Key ID" to "public-key-id",
                "Trust status" to "TRUSTED",
                "Authorization" to "Bearer should-not-copy",
                "Transport status" to "Authorization: Basic private-value",
                "Repository" to "https://username:password@example.test/repo.json",
                "Last Update Failure Code" to "SMOKE_CHECK_FAILED",
            )),
        )
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ExtensionDiagnostics(state)
                }
            }
        }
        composeRule.onNodeWithTag("diagnostics-copy-${key.testTagPart()}").performScrollTo().performClick()

        val clipboard = composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text.toString()
        assertTrue(copied.contains("Key ID: public-key-id"))
        assertTrue(copied.contains("Trust status: TRUSTED"))
        assertTrue(copied.contains("Last Update Failure Code: SMOKE_CHECK_FAILED"))
        assertFalse(copied.contains("should-not-copy"))
        assertFalse(copied.contains("private-value"))
        assertFalse(copied.contains("username:password"))
    }

    @Test
    fun capturesManageOverviewWithCurrentAvailableBusyAndFriendlyFailure() {
        val sourceId = "source-overview"
        val currentKey = key("overview-current").copy(sourceId = sourceId)
        val updateKey = key("overview-update").copy(sourceId = sourceId)
        val currentExtension = current(currentKey)
        val availableExtension = update(updateKey).copy(lastUpdateFailure = ExtensionUpdateFailure.NETWORK)
        val overviewSource = source(sourceId, currentExtension).copy(
            status = ExtensionSourceStatus.ERROR,
            lastFailure = ExtensionSourceFailure.NETWORK,
            extensions = listOf(currentExtension, availableExtension),
        )
        val state = mutableStateOf(ExtensionSourcesUiState(
            sources = listOf(overviewSource),
            busySourceIds = setOf(sourceId),
        ))
        composeManage(state, RecordingEvent())

        composeRule.onNodeWithTag("extension-update-state-${currentKey.extensionId}")
            .assertTextContains("Installed and current", substring = true)
        composeRule.onNodeWithTag("extension-update-state-${updateKey.extensionId}")
            .assertTextContains("Update available", substring = true)
        composeRule.onNodeWithTag("extension-progress-${updateKey.extensionId}")
            .performScrollTo().assertIsDisplayedCompat()
        composeRule.onNodeWithTag("extension-update-failure-${updateKey.extensionId}")
            .performScrollTo().assertTextContains("Network unavailable", substring = true)
        captureScreenshot("manage-overview") {
            composeRule.onNodeWithTag("extension-update-failure-${updateKey.extensionId}").assertIsDisplayedCompat()
        }
    }

    @Test
    fun capturesManageOverviewInDarkThemeOnANarrowScreenWithLargeText() {
        val sourceId = "source-overview-narrow"
        val currentKey = key("overview-current").copy(sourceId = sourceId)
        val updateKey = key("overview-update").copy(sourceId = sourceId)
        val currentExtension = current(currentKey)
        val availableExtension = update(updateKey).copy(lastUpdateFailure = ExtensionUpdateFailure.NETWORK)
        val state = mutableStateOf(ExtensionSourcesUiState(
            sources = listOf(source(sourceId, currentExtension).copy(
                status = ExtensionSourceStatus.ERROR,
                lastFailure = ExtensionSourceFailure.NETWORK,
                extensions = listOf(currentExtension, availableExtension),
            )),
        ))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                            ExtensionSourcesSettingsSection(state.value, RecordingEvent())
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("extension-update-state-${updateKey.extensionId}")
            .performScrollTo().assertTextContains("Update available", substring = true)
        composeRule.onNodeWithTag("extension-update-failure-${updateKey.extensionId}")
            .performScrollTo().assertTextContains("Network unavailable", substring = true)
        captureScreenshot("manage-overview-dark-narrow-large-text") {
            composeRule.onNodeWithTag("extension-update-failure-${updateKey.extensionId}").assertIsDisplayedCompat()
        }
    }

    @Test
    fun capturesDiagnosticsFromPublicSignedMetadataFixturesOnly() {
        val key = key("ui-review-signed")
        val extension = current(key).copy(
            displayName = "Signed UI Review Extension",
            latestAvailableVersion = "1.1.0",
            installedReleaseSequence = 5,
            packageGeneration = 42,
            rollbackTarget = ExtensionRollbackTarget("0.9.0", "public-previous-good-digest", "TRUSTED"),
        )
        val state = ExtensionSourcesUiState(
            sources = listOf(source(key.sourceId, extension)),
            diagnostics = mapOf(key to mapOf(
                "Repository" to "https://public.example.test/repository.json",
                "Publisher" to "public-signed-publisher",
                "Key ID" to "ed25519:ui-review-public",
                "Trust status" to "TRUSTED",
                "Allowed Hosts" to "api.example.test",
                "Last metadata success" to "2026-10-02T12:00:00Z",
            )),
        )
        composeDiagnostics(state)
        composeRule.onNodeWithText("Active package generation: 42").performScrollTo().assertIsDisplayedCompat()
        captureScreenshot("diagnostics") {
            composeRule.onNodeWithText("Active package generation: 42").assertIsDisplayedCompat()
        }
    }

    @Test
    fun capturesDiagnosticsInDarkTheme() {
        val key = key("ui-review-dark")
        val extension = current(key).copy(
            displayName = "Signed UI Review Extension",
            latestAvailableVersion = "1.1.0",
            installedReleaseSequence = 5,
            packageGeneration = 43,
            rollbackTarget = ExtensionRollbackTarget("0.9.0", "public-previous-good-digest", "TRUSTED"),
        )
        val state = ExtensionSourcesUiState(
            sources = listOf(source(key.sourceId, extension)),
            diagnostics = mapOf(key to mapOf(
                "Repository" to "https://public.example.test/repository.json",
                "Publisher" to "public-signed-publisher",
                "Trust status" to "TRUSTED",
                "Allowed Hosts" to "api.example.test",
            )),
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ExtensionDiagnostics(state)
                    }
                }
            }
        }
        composeRule.onNodeWithText("Active package generation: 43").performScrollTo().assertIsDisplayedCompat()
        captureScreenshot("diagnostics-dark") {
            composeRule.onNodeWithText("Active package generation: 43").assertIsDisplayedCompat()
        }
    }

    @Test
    fun releaseSourceChoicesAreSingleSelectRadioOptions() {
        val keyA = key("a")
        val keyB = key("b")
        val state = mutableStateOf(ExtensionSourcesUiState(
            sources = listOf(source(keyA.sourceId, current(keyA)), source(keyB.sourceId, current(keyB))),
            canEditProductPolicy = true,
            productPolicy = ExtensionProductPolicy(activeReleaseSource = keyA),
        ))
        val event = RecordingEvent(onSelectActive = { key ->
            state.value = state.value.copy(productPolicy = state.value.productPolicy.copy(activeReleaseSource = key))
        })
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ExtensionDataSourcePreferences(state.value, event)
                }
            }
        }

        val aTag = "extension-product-active-${keyA.testTagPart()}"
        val bTag = "extension-product-active-${keyB.testTagPart()}"
        state.value = state.value.copy(busySourceIds = setOf(keyA.sourceId))
        composeRule.onNodeWithTag(aTag).assertIsNotEnabled()
        composeRule.onNodeWithTag("extension-preference-sub-${keyA.testTagPart()}").assertIsNotEnabled()
        state.value = state.value.copy(busySourceIds = emptySet())
        composeRule.onNodeWithTag(aTag).assertIsSelected()
        composeRule.onNodeWithTag(bTag).assertIsNotSelected()
        composeRule.onNodeWithTag(aTag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithTag(bTag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithTag(bTag).performScrollTo().performClick()
        composeRule.onNodeWithTag(aTag).assertIsNotSelected()
        composeRule.onNodeWithTag(bTag).assertIsSelected()
        assertEquals(listOf(keyB), event.selectedActiveSources)
    }

    @Test
    fun sourceOperationVisuallyDisablesProviderSelectionsAndOrdering() {
        val keyA = key("provider-a")
        val keyB = key("provider-b")
        val state = mutableStateOf(ExtensionSourcesUiState(
            sources = listOf(source(keyA.sourceId, current(keyA)), source(keyB.sourceId, current(keyB))),
            canEditProductPolicy = true,
            busySourceIds = setOf(keyA.sourceId),
        ))
        val event = RecordingEvent()
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ExtensionProviderDisplay(state.value, event)
                }
            }
        }

        val providerTag = "extension-product-navigation-${keyB.testTagPart()}"
        val visibleTag = "extension-preference-provider-visible-${keyB.testTagPart()}"
        val moveUpTag = "provider-up-${keyB.testTagPart()}"
        composeRule.onNodeWithTag(providerTag).assertIsNotEnabled()
        composeRule.onNodeWithTag(visibleTag).assertIsNotEnabled()
        composeRule.onNodeWithTag(moveUpTag).assertIsNotEnabled()
        state.value = state.value.copy(busySourceIds = emptySet())
        composeRule.onNodeWithTag(providerTag).assertIsEnabled()
        composeRule.onNodeWithTag(visibleTag).assertIsEnabled()
        composeRule.onNodeWithTag(moveUpTag).assertIsEnabled()
    }

    @Test
    fun twoVisibleProvidersStayVisibleWhenThePreferredProviderChanges() {
        val keyA = key("provider-a")
        val keyB = key("provider-b")
        val state = mutableStateOf(ExtensionSourcesUiState(
            sources = listOf(source(keyA.sourceId, current(keyA)), source(keyB.sourceId, current(keyB))),
            canEditProductPolicy = true,
            productPolicy = ExtensionProductPolicy(preferredNavigationProvider = keyA,
                navigationProviderOrder = listOf(keyA, keyB)),
        ))
        val event = object : ExtensionSourcesEvent by RecordingEvent() {
            override fun selectNavigationProvider(key: ExtensionSelectionKey?) {
                state.value = state.value.copy(productPolicy = state.value.productPolicy.copy(preferredNavigationProvider = key))
            }
            override fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) {
                state.value = state.value.copy(productPolicy = state.value.productPolicy.copy(
                    preferences = state.value.productPolicy.preferences + (key to preferences)))
            }
        }
        composeRule.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ExtensionProviderDisplay(state.value, event)
            }
        } }
        val visibleA = "extension-preference-provider-visible-${keyA.testTagPart()}"
        val visibleB = "extension-preference-provider-visible-${keyB.testTagPart()}"
        composeRule.onNodeWithTag(visibleA).assertIsOn()
        composeRule.onNodeWithTag(visibleB).assertIsOn()
        composeRule.onNodeWithTag("extension-product-navigation-${keyB.testTagPart()}")
            .performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag(visibleA).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(visibleB).assertIsOn()
        captureScreenshot("providers-both-visible") {
            composeRule.onNodeWithTag(visibleA).assertIsDisplayed().assertIsOn()
            composeRule.onNodeWithTag(visibleB).assertIsDisplayed().assertIsOn()
        }
        composeRule.onNodeWithTag(visibleA).performClick().assertIsOff()
        composeRule.onNodeWithTag(visibleB).assertIsOn()
        captureScreenshot("providers-one-disabled") {
            composeRule.onNodeWithTag(visibleA).assertIsDisplayed().assertIsOff()
            composeRule.onNodeWithTag(visibleB).assertIsDisplayed().assertIsOn()
        }
    }

    @Test
    fun providerReorderButtonsNameTheirProviderAndDisableAtTheEdges() {
        val keyA = key("provider-a")
        val keyB = key("provider-b")
        val state = ExtensionSourcesUiState(
            sources = listOf(source(keyA.sourceId, current(keyA)), source(keyB.sourceId, current(keyB))),
            canEditProductPolicy = true,
        )
        val event = RecordingEvent()
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ExtensionProviderDisplay(state, event)
                }
            }
        }

        // Provider A is first, B second. The spoken name carries the provider, not just an arrow glyph.
        composeRule.onNodeWithContentDescription("Move Signed: provider-a up").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Move Signed: provider-a down").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithContentDescription("Move Signed: provider-b up").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithContentDescription("Move Signed: provider-b down").performScrollTo().assertIsNotEnabled()
        // The visible arrow text is not announced a second time.
        composeRule.onAllNodesWithText("↑").assertCountEquals(0)

        composeRule.onNodeWithContentDescription("Move Signed: provider-b up").performClick()
        assertEquals(listOf(listOf(keyB, keyA)), event.providerOrders)
    }

    @Test
    fun withdrawnInstalledVersionKeepsRunningAndIsExplainedWithoutAnError() {
        val key = key("withdrawn-installed")
        val withdrawn = current(key).copy(candidateYanked = true, digest = "installed-digest-current", activationAllowed = false)
        val state = mutableStateOf(ExtensionSourcesUiState(sources = listOf(source(key.sourceId, withdrawn))))
        composeManage(state, RecordingEvent())

        composeRule.onNodeWithTag("extension-yank-note-${key.extensionId}")
            .performScrollTo().assertTextContains("keeps running", substring = true)
        composeRule.onNodeWithTag("extension-update-state-${key.extensionId}")
            .assertTextContains("Installed and current", substring = true)
        composeRule.onNodeWithTag("extension-installed-version-${key.extensionId}")
            .assertTextContains("Installed version: 1.0.0")
        captureScreenshot("manage-installed-version-withdrawn") {
            composeRule.onNodeWithTag("extension-yank-note-${key.extensionId}").assertIsDisplayedCompat()
        }
    }

    @Test
    fun withdrawnCandidateThatIsNotInstalledIsStillShownAsAProblem() {
        val key = key("withdrawn-candidate")
        val withdrawn = update(key).copy(candidateYanked = true, updateAvailable = false, digest = "other-candidate-digest")
        val state = mutableStateOf(ExtensionSourcesUiState(sources = listOf(source(key.sourceId, withdrawn))))
        composeManage(state, RecordingEvent())

        composeRule.onNodeWithTag("extension-yank-note-${key.extensionId}")
            .performScrollTo().assertTextContains("cannot be installed", substring = true)
    }

    private fun composeManage(
        state: androidx.compose.runtime.State<ExtensionSourcesUiState>,
        event: RecordingEvent,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ExtensionSourcesSettingsSection(state.value, event)
                }
            }
        }
    }

    private fun composeDiagnostics(state: ExtensionSourcesUiState) {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ExtensionDiagnostics(state)
                }
            }
        }
    }

    private fun captureScreenshot(name: String, assertTarget: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val externalFiles = requireNotNull(context.getExternalFilesDir(null)) {
            "App-specific external files directory is unavailable"
        }
        // The capture refuses to store anything unless this app screen is the foreground and shows the target.
        composeRule.storeVerifiedScreenshot(composeRule.activity, File(externalFiles, "ep07-ui"), name, assertTarget)
    }

    private fun key(name: String) = ExtensionSelectionKey(
        sourceId = "source-$name",
        extensionId = "$name.extension",
        publisherId = "$name.publisher",
        providerId = "$name.provider",
    )

    private fun source(
        sourceId: String,
        extension: SourceExtension,
        status: ExtensionSourceStatus = ExtensionSourceStatus.CURRENT,
        lastFailure: ExtensionSourceFailure? = null,
    ) = ExtensionSource(
        id = sourceId,
        url = "https://$sourceId.example.test/repository.json",
        origin = "authenticated-origin",
        enabled = true,
        status = status,
        lastFailure = lastFailure,
        extensions = listOf(extension),
    )

    private fun current(key: ExtensionSelectionKey) = SourceExtension(
        extensionId = key.extensionId,
        displayName = "Signed: ${key.extensionId.substringBefore('.')}",
        version = "1.0.0",
        digest = "candidate-digest",
        releaseSequence = 5,
        capabilities = listOf("CALENDAR", "OVERVIEW_NAVIGATION"),
        installedVersion = "1.0.0",
        installedDigest = "installed-digest-current",
        activationAllowed = true,
        providerId = key.providerId,
        publisherId = key.publisherId,
        installedUsable = true,
        installedStatus = InstalledPackageStatus.USABLE,
        updateState = ExtensionUpdateState.INSTALLED_CURRENT,
        latestAvailableVersion = "1.0.0",
        metadataFresh = true,
        packageGeneration = 3,
    )

    private fun update(key: ExtensionSelectionKey) = current(key).copy(
        version = "1.1.0",
        installedVersion = "1.0.0",
        updateAvailable = true,
        updateState = ExtensionUpdateState.UPDATE_AVAILABLE,
        latestAvailableVersion = "1.1.0",
    )

    private fun notInstalled(key: ExtensionSelectionKey) = current(key).copy(
        installedVersion = null,
        installedDigest = null,
        installedUsable = false,
        installedStatus = InstalledPackageStatus.NOT_INSTALLED,
        updateAvailable = false,
        updateState = ExtensionUpdateState.NOT_INSTALLED,
        latestAvailableVersion = "1.0.0",
        packageGeneration = 0,
    )

    private class RecordingEvent(
        private val onSelectActive: (ExtensionSelectionKey?) -> Unit = {},
    ) : ExtensionSourcesEvent {
        val activations = mutableListOf<Pair<String, String>>()
        val removedExtensions = mutableListOf<Pair<String, String>>()
        val removedSources = mutableListOf<String>()
        val rollbacks = mutableListOf<Rollback>()
        val selectedActiveSources = mutableListOf<ExtensionSelectionKey?>()
        val providerOrders = mutableListOf<List<ExtensionSelectionKey>>()

        override fun onUrlChanged(value: String) = Unit
        override fun addSource() = Unit
        override fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override fun removeSource(sourceId: String) { removedSources += sourceId }
        override fun refreshSource(sourceId: String) = Unit
        override fun activate(sourceId: String, extensionId: String) { activations += sourceId to extensionId }
        override fun selectActiveSource(key: ExtensionSelectionKey?) {
            selectedActiveSources += key
            onSelectActive(key)
        }
        override fun selectNavigationProvider(key: ExtensionSelectionKey?) = Unit
        override fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) = Unit
        override fun removeExtension(sourceId: String, extensionId: String) {
            removedExtensions += sourceId to extensionId
        }
        override fun rollback(sourceId: String, extensionId: String, expectedGeneration: Long, targetDigest: String) {
            rollbacks += Rollback(sourceId, extensionId, expectedGeneration, targetDigest)
        }
        override fun setProviderOrder(keys: List<ExtensionSelectionKey>) { providerOrders += keys }
        override fun clearActionFailure() = Unit

        data class Rollback(
            val sourceId: String,
            val extensionId: String,
            val expectedGeneration: Long,
            val targetDigest: String,
        )
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsDisplayedCompat() =
        assertExists().assertIsDisplayed()

    private fun ExtensionSelectionKey.testTagPart(): String =
        listOf(sourceId, extensionId, publisherId, providerId).joinToString("-")
}
