package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.*
import com.axiel7.anihyou.feature.settings.*
import com.axiel7.anihyou.feature.settings.R as SettingsR
import com.axiel7.anihyou.feature.settings.source.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import java.io.File
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
            val state = rememberNavigationState(Route.Settings, MainNavigationResolver.allRoutes)
            val nav = remember(state) { Navigator(state) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(nav)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    SettingsViewContent(SettingsUiState(isLoggedIn = loggedIn), null, null)
                }
            }
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
        rule.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ExtensionCenterMenu { }
        } } }
        rule.onNodeWithText(rule.activity.getString(SettingsR.string.extension_center_management_section)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(SettingsR.string.extension_center_diagnostics_section)).assertIsDisplayed()
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"), "native-center-groups") {
            rule.onNodeWithTag("extension-center-manage").assertIsDisplayed()
            rule.onNodeWithTag("extension-center-diagnostics").assertIsDisplayed()
        }
    }
}
