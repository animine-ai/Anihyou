package com.axiel7.anihyou.feature.settings

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.axiel7.anihyou.core.base.state.UiState
import com.axiel7.anihyou.core.model.AppColorMode
import com.axiel7.anihyou.core.model.DefaultTab
import com.axiel7.anihyou.core.model.ItemsPerRow
import com.axiel7.anihyou.core.model.ListStyle
import com.axiel7.anihyou.core.model.Theme
import com.axiel7.anihyou.core.model.TranslatorApp
import com.axiel7.anihyou.release.core.api.ReleaseGermanTrack
import com.axiel7.anihyou.release.core.api.ReleaseMappingStatus
import com.axiel7.anihyou.core.model.notification.NotificationInterval
import com.axiel7.anihyou.core.network.fragment.UserSettings
import com.axiel7.anihyou.core.network.type.ScoreFormat
import com.materialkolor.PaletteStyle

@Immutable
data class SettingsUiState(
    val theme: Theme? = null,
    val useBlackColors: Boolean = false,
    val appColorMode: AppColorMode? = null,
    val appColor: Color? = null,
    val colorPaletteStyle: String = PaletteStyle.Expressive.name,
    val coloredMedia: Boolean = true,
    val blurAdultContent: Boolean = true,
    val showLowPriority: Boolean = false,
    val useGeneralListStyle: Boolean? = null,
    val generalListStyle: ListStyle? = null,
    val gridItemsPerRow: ItemsPerRow? = null,
    val airingOnMyList: Boolean? = null,
    val scoreFormat: ScoreFormat? = null,
    val scoreStep: Double = 1.0,
    val defaultTab: DefaultTab = DefaultTab.LAST_USED,
    val isNotificationsEnabled: Boolean? = null,
    val notificationCheckInterval: NotificationInterval = NotificationInterval.DAILY,
    val userSettings: UserSettings? = null,
    val translatorApp: TranslatorApp = TranslatorApp.DEFAULT,
    val hideScores: Boolean = false,
    val useFuzzySearch: Boolean = false,
    val separateNovelsAndManga: Boolean = false,
    val releaseProviderEnabled: Boolean = false,
    val preferredGermanTrack: ReleaseGermanTrack = ReleaseGermanTrack.DE_SUB,
    val releaseNotificationsEnabled: Boolean = false,
    val releaseStatus: String = "disabled",
    val releaseManualMappingCount: Int = 0,
    val releaseAutomaticMappingCount: Int = 0,
    val releaseUnresolvedMappingCount: Int = 0,
    val releaseMappings: List<ReleaseMappingStatus> = emptyList(),
    val isLoggedIn: Boolean = false,
    override val error: String? = null,
    override val isLoading: Boolean = false,
) : UiState() {
    override fun setError(value: String?) = copy(error = value)
    override fun setLoading(value: Boolean) = copy(isLoading = value)
}
