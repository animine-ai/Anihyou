package com.axiel7.anihyou.feature.settings.appicon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.axiel7.anihyou.core.resources.R as CoreR
import com.axiel7.anihyou.feature.settings.R

/** The choice of the icon on the home screen: the icons side by side, the one in use marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppIconDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { AppIconManager(context) }
    var current by remember { mutableStateOf(manager.current()) }
    var failed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("app-icon-dialog"),
        title = { Text(stringResource(R.string.app_icon_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AppIcon.entries.forEach { icon ->
                        AppIconChoice(
                            icon = icon,
                            selected = icon == current,
                            onClick = {
                                if (manager.select(icon)) {
                                    current = icon
                                    failed = false
                                } else {
                                    failed = true
                                }
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(if (failed) R.string.app_icon_failed else R.string.app_icon_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_icon_done)) }
        },
    )
}

/** How an icon is previewed: its name and its two layers, as the launcher draws them. */
private class AppIconArt(
    @StringRes val label: Int,
    @DrawableRes val background: Int?,
    @DrawableRes val foreground: Int,
    /** The icon has no background of its own, like an icon pack: it is shown on a dark disc, as on a dark home screen. */
    val needsDarkBackdrop: Boolean = false,
)

private val AppIcon.art: AppIconArt
    get() = when (this) {
        AppIcon.KIYORI -> AppIconArt(R.string.app_icon_kiyori, CoreR.drawable.ic_kiyori_background, CoreR.drawable.ic_kiyori_foreground)
        AppIcon.KIYORI_LIGHT -> AppIconArt(
            R.string.app_icon_kiyori_light, CoreR.drawable.ic_kiyori_light_background, CoreR.drawable.ic_kiyori_light_foreground,
        )
        AppIcon.ARCTIC -> AppIconArt(R.string.app_icon_arctic, null, CoreR.drawable.ic_arctic_foreground, needsDarkBackdrop = true)
        AppIcon.ARCTIC_CALENDAR -> AppIconArt(R.string.app_icon_arctic_calendar, null, CoreR.drawable.ic_arctic_calendar_foreground, needsDarkBackdrop = true)
        AppIcon.ARCTIC_MOON -> AppIconArt(R.string.app_icon_arctic_moon, null, CoreR.drawable.ic_arctic_moon_foreground, needsDarkBackdrop = true)
        AppIcon.ARCTIC_LIST -> AppIconArt(R.string.app_icon_arctic_list, null, CoreR.drawable.ic_arctic_list_foreground, needsDarkBackdrop = true)
        AppIcon.CLASSIC -> AppIconArt(R.string.app_icon_classic, CoreR.drawable.ic_classic_background, CoreR.drawable.ic_classic_foreground)
    }

@Composable
private fun AppIconChoice(icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
    val art = icon.art
    val label = stringResource(art.label)
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .testTag("app-icon-${icon.aliasName}")
            .semantics { this.selected = selected; contentDescription = label }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (art.needsDarkBackdrop) Color(0xFF14151B) else Color.Transparent)
                .border(
                    border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(20.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // The two layers of the icon as the launcher shows them: the center 2/3 of the 108 dp canvas.
            art.background?.let {
                Image(painter = painterResource(it), contentDescription = null, modifier = Modifier.size(64.dp).scale(1.5f))
            }
            Image(painter = painterResource(art.foreground), contentDescription = null, modifier = Modifier.size(64.dp).scale(1.5f))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
