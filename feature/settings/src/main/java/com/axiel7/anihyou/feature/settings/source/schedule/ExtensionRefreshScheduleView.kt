package com.axiel7.anihyou.feature.settings.source.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.feature.settings.source.SelectionOption
import com.axiel7.anihyou.release.core.api.ExtensionRefreshInterval
import com.axiel7.anihyou.release.core.api.ExtensionRefreshSchedule
import com.axiel7.anihyou.release.core.api.ExtensionRefreshScheduleRepository
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.koin.compose.koinInject

@Composable
fun ExtensionRefreshScheduleView() {
    val repository = koinInject<ExtensionRefreshScheduleRepository>()
    val scheduler = koinInject<ExtensionReleaseRefreshScheduler>()
    val model: ExtensionRefreshScheduleViewModel = viewModel(factory = remember(repository, scheduler) {
        viewModelFactory { initializer { ExtensionRefreshScheduleViewModel(repository, scheduler) } }
    })
    val schedule by model.schedule.collectAsStateWithLifecycle()
    ExtensionRefreshScheduleScreen(schedule, model)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionRefreshScheduleScreen(schedule: ExtensionRefreshSchedule, event: ExtensionRefreshScheduleEvent) {
    val nav = LocalNavActionManager.current
    var picking by remember { mutableStateOf(false) }
    DefaultScaffoldWithSmallTopAppBar(title = stringResource(R.string.extension_schedule_title),
        navigationIcon = { BackIconButton(nav::goBack) }, scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.extension_schedule_explanation), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.extension_schedule_interval), style = MaterialTheme.typography.titleSmall)
            ExtensionRefreshInterval.entries.forEach { interval ->
                SelectionOption(intervalLabel(interval), schedule.interval == interval, true,
                    { event.setInterval(interval) }, "extension-schedule-interval-${interval.minutes}")
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.extension_schedule_start), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { picking = true }, modifier = Modifier.testTag("extension-schedule-start")) {
                Text(timeLabel(schedule.anchorMinuteOfDay))
            }
            Text(stringResource(R.string.extension_schedule_next, nextSlotLabel(schedule)),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("extension-schedule-next"))
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = event::reset, modifier = Modifier.testTag("extension-schedule-reset")) {
                Text(stringResource(R.string.extension_schedule_reset))
            }
        }
    }
    if (picking) StartTimeDialog(schedule.anchorMinuteOfDay, { picking = false }) { minute ->
        picking = false
        event.setAnchor(minute)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartTimeDialog(anchorMinute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(anchorMinute / 60, anchorMinute % 60, is24Hour = true)
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }, modifier = Modifier.testTag("extension-schedule-start-confirm")) {
                Text(stringResource(R.string.extension_schedule_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.extension_schedule_cancel)) } },
        text = { TimePicker(state) })
}

@Composable
private fun intervalLabel(interval: ExtensionRefreshInterval): String = when (interval.minutes) {
    30 -> stringResource(R.string.extension_schedule_every_minutes, 30)
    else -> stringResource(R.string.extension_schedule_every_hours, interval.minutes / 60)
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun timeLabel(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** The planned time in device-local wall-clock time, as the worker computes it. */
private fun nextSlotLabel(schedule: ExtensionRefreshSchedule): String {
    val zone = ZoneId.systemDefault()
    return schedule.nextSlotAfter(Clock.systemUTC().instant(), zone).atZone(zone).format(timeFormat)
}
