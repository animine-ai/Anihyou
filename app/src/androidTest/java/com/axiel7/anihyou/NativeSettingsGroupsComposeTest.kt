package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.*
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.axiel7.anihyou.feature.settings.*
import com.axiel7.anihyou.feature.settings.R as SettingsR
import com.axiel7.anihyou.core.resources.R as CoreR
import com.axiel7.anihyou.feature.settings.source.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import java.io.File
import java.util.Locale
import android.content.res.Configuration
import android.content.Context
import com.axiel7.anihyou.release.core.source.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalPermissionsApi::class)
class NativeSettingsGroupsComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun loggedOutRootKeepsSeparateNavigationAndSourcesGroups() = root(false, false)
    @Test fun loggedInDarkRootKeepsSeparateNavigationAndSourcesGroups() = root(true, true)
    private fun root(loggedIn: Boolean, dark: Boolean) {
        rule.setContent {
            val state = rememberNavigationState(Route.Settings, MainNavigationResolver.allRoutes + Route.Settings)
            val nav = remember(state) { Navigator(state) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(nav)) {
                AniHyouTheme(darkTheme = dark, dynamicColor = false) {
                    SettingsViewContent(SettingsUiState(isLoggedIn = loggedIn), null, null)
                }
            }
        }
        for (old in listOf(CoreR.string.release_provider_enabled, CoreR.string.release_provider_status, CoreR.string.release_preferred_track,
            CoreR.string.release_mapping_title, CoreR.string.release_notifications_enabled)) {
            rule.onAllNodesWithText(rule.activity.getString(old)).assertCountEquals(0)
        }
        val sourceTitle = rule.activity.getString(SettingsR.string.settings_sources_section)
        val navigationTitle = rule.activity.getString(SettingsR.string.settings_navigation_section)
        rule.onNodeWithText(sourceTitle).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(navigationTitle).assertIsDisplayed()
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"),
            "native-root-${if(loggedIn) "account" else "guest"}-${if(dark) "dark" else "light"}") {
            rule.onNodeWithText(sourceTitle).assertIsDisplayed()
            rule.onNodeWithText(navigationTitle).assertIsDisplayed()
        }
    }
    @Test fun centerSeparatesManagementFromDiagnostics() {
        rule.setContent { AniHyouTheme(darkTheme = false, dynamicColor = false) { Column(Modifier.verticalScroll(rememberScrollState())) {
            ExtensionCenterMenu { }
        } } }
        rule.onNodeWithText(rule.activity.getString(SettingsR.string.extension_center_management_section)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(SettingsR.string.extension_center_diagnostics_section)).assertIsDisplayed()
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"), "native-center-groups") {
            rule.onNodeWithTag("extension-center-source").assertIsDisplayed()
            rule.onNodeWithTag("extension-center-diagnostics").assertIsDisplayed()
        }
    }
    @Test fun rootOpensExtensionsMatcherAndProvidersDirectlyAndBackReturnsToSettings() {
        lateinit var state: NavigationState
        lateinit var nav: Navigator
        rule.setContent {
            state = rememberNavigationState(Route.Settings, MainNavigationResolver.allRoutes + Route.Settings)
            nav = remember(state) { Navigator(state) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(nav)) {
                AniHyouTheme(darkTheme = false, dynamicColor = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { SettingsSourceNavigationPreferences() }
                }
            }
        }
        listOf("manage", "matching", "providers").forEach { id ->
            rule.onNodeWithTag("extension-center-$id").performScrollTo().performClick()
            rule.runOnIdle { org.junit.Assert.assertEquals(Route.ExtensionCenterPage(id), state.getCurrentRoute())
                nav.goBack(); org.junit.Assert.assertEquals(Route.Settings, state.getCurrentRoute()) }
        }
        rule.onNodeWithTag("extension-center-root").performScrollTo().performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals(Route.ExtensionCenter, state.getCurrentRoute()) }
    }

    @Test fun centerWithLargeGermanTextUsesTheOriginalBlackTheme() {
        val context = germanBlackContent { ExtensionCenterMenu { } }
        rule.onNodeWithTag("extension-center-diagnostics").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(SettingsR.string.extension_center_diagnostics_section)).assertIsDisplayed()
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"),
            "native-center-german-black-large") {
            rule.onNodeWithTag("extension-center-diagnostics").assertIsDisplayed()
        }
    }

    @Test fun emptySourcesDisableSelectionAndKeepScheduleRoute() {
        lateinit var navigation: NavigationState
        val context = germanBlackContent {
            navigation = rememberNavigationState(Route.Home, MainNavigationResolver.allRoutes)
            val nav = remember(navigation) { Navigator(navigation) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(nav)) {
                ExtensionDataSourcePreferences(ExtensionSourcesUiState(canEditProductPolicy = false), noSourceEvent)
            }
        }
        rule.onNodeWithTag("extension-product-active-none").assertIsNotEnabled()
        rule.onNodeWithText(context.getString(SettingsR.string.extension_sources_no_active_source)).assertIsDisplayed()
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"),
            "native-sources-empty-german-black-large") {
            rule.onNodeWithTag("extension-source-schedule").assertIsDisplayed()
            rule.onNodeWithTag("extension-product-active-none").assertIsNotEnabled()
        }
        rule.onNodeWithTag("extension-source-schedule").performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals(Route.ExtensionCenterPage("schedule"), navigation.getCurrentRoute()) }
    }

    @Test fun dataSourcePageOwnsTheReleaseNotificationSwitch() {
        var changed: Boolean? = null
        rule.setContent { AniHyouTheme(darkTheme = false, dynamicColor = false) { Column(Modifier.verticalScroll(rememberScrollState())) {
            val event = object : ExtensionSourcesEvent by noSourceEvent { override fun setReleaseNotificationsEnabled(enabled: Boolean) { changed = enabled } }
            ExtensionDataSourcePreferences(ExtensionSourcesUiState(releaseNotificationsEnabled = false), event)
        } } }
        rule.onNodeWithTag("extension-preference-release-notifications").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals(true, changed) }
    }

    @Test fun emptyProvidersExplainTheStateWithLargeGermanText() {
        val context = germanBlackContent { ExtensionProviderDisplay(ExtensionSourcesUiState(), noSourceEvent) }
        rule.onNodeWithText(context.getString(SettingsR.string.extension_provider_empty)).assertIsDisplayed()
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"),
            "native-providers-empty-german-black-large") {
            rule.onNodeWithText(context.getString(SettingsR.string.extension_provider_empty)).assertIsDisplayed()
        }
    }
    @Test fun twoProvidersInGermanBlackLargeTextKeepControlsReadable() {
        val sources = (1..2).map { n -> ExtensionSource("provider-$n", "https://provider-$n.example/repo", "fixture", true,
            ExtensionSourceStatus.CURRENT, extensions = listOf(SourceExtension("extension-$n", "Anbieter $n", "1.0", "digest-$n", 1,
                listOf("OVERVIEW_NAVIGATION"), installedDigest = "digest-$n", activationAllowed = true,
                publisherId = "publisher-$n", providerId = "provider-$n"))) }
        germanBlackContent { ExtensionProviderDisplay(ExtensionSourcesUiState(sources = sources, canEditProductPolicy = true), noSourceEvent) }
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"), "providers-two-german-black-large") {
            rule.onNodeWithText("Anbieter 1").assertIsDisplayed()
            rule.onNodeWithText("Anbieter 2").assertIsDisplayed()
        }
    }

    @Test fun userStatisticsStayReadableInGermanBlackLargeText() {
        val key = ExtensionSelectionKey("stats", "stats-extension", "stats-publisher", "stats-provider")
        val source = ExtensionSource(key.sourceId, "https://stats.example/repo", "fixture", true,
            ExtensionSourceStatus.CURRENT, extensions = listOf(SourceExtension(key.extensionId, "Anbieter", "1.0", "digest", 1,
                listOf("CALENDAR"), installedDigest = "digest", activationAllowed = true,
                publisherId = key.publisherId, providerId = key.providerId)))
        val state = ExtensionSourcesUiState(sources = listOf(source),
            productPolicy = ExtensionProductPolicy(activeReleaseSource = key),
            statistics = mapOf(key to ExtensionUserStatistics(ExtensionMatchingStatistics(73, 65, 8),
                java.time.Instant.parse("2026-10-05T09:00:00Z"), ExtensionDataUpdateStatus.PARTIAL)))
        val context = germanBlackContent { ExtensionStatistics(state) }
        rule.onNodeWithTag("extension-stat-found").performScrollTo().assertTextContains("73")
        rule.onNodeWithTag("extension-stat-matched").performScrollTo().assertTextContains("65")
        rule.onNodeWithTag("extension-stat-unmatched").performScrollTo().assertTextContains("8")
        rule.onNodeWithTag("extension-stat-errors").performScrollTo()
            .assertTextContains(context.getString(SettingsR.string.extension_stats_errors_partial))
        rule.onNodeWithTag("extension-stat-updated").performScrollTo().assertTextContains("2026", substring = true)
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"),
            "statistics-user-german-black-large") {
            rule.onNodeWithTag("extension-stat-updated").assertIsDisplayed()
            rule.onAllNodesWithText("Package SHA", substring = true).assertCountEquals(0)
        }
    }

    private fun germanBlackContent(content: @Composable () -> Unit): Context {
        val context = rule.activity.createConfigurationContext(Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.GERMAN)
        })
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                AniHyouTheme(darkTheme = true, dynamicColor = false, blackColors = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { content() } }
                    }
                }
            }
        }
        return context
    }

    private val noSourceEvent = object : ExtensionSourcesEvent {
        override fun onUrlChanged(value: String) = Unit
        override fun addSource() = Unit
        override fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override fun removeSource(sourceId: String) = Unit
        override fun refreshSource(sourceId: String) = Unit
        override fun activate(sourceId: String, extensionId: String) = Unit
        override fun selectActiveSource(key: ExtensionSelectionKey?) = Unit
        override fun selectNavigationProvider(key: ExtensionSelectionKey?) = Unit
        override fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) = Unit
        override fun removeExtension(sourceId: String, extensionId: String) = Unit
        override fun rollback(sourceId: String, extensionId: String, expectedGeneration: Long, targetDigest: String) = Unit
        override fun setProviderOrder(keys: List<ExtensionSelectionKey>) = Unit
        override fun clearActionFailure() = Unit
    }
}
