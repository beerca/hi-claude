package app.lightsout.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lightsout.ActiveSession
import app.lightsout.Alarms
import app.lightsout.Apps
import app.lightsout.Format
import app.lightsout.LiveTracking
import app.lightsout.NightRecord
import app.lightsout.Sessions
import app.lightsout.Store
import app.lightsout.TrackingMode
import app.lightsout.UsageAccess
import app.lightsout.core.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    refreshKey: Int,
    askWakeTime: Boolean,
    onWakeTimeAsked: () -> Unit,
    onChanged: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val now = remember(refreshKey) { System.currentTimeMillis() }
    val settings = remember(refreshKey) { Store.settings(context) }
    val session = remember(refreshKey) { Store.activeSession(context) }
    val nights = remember(refreshKey) { Store.nights(context) }
    val usageAccess = remember(refreshKey) { UsageAccess.granted(context) }
    val usageSkipped = remember(refreshKey) { Store.usageAccessSkipped(context) }
    val batteryNeeded = remember(refreshKey) {
        !usageAccess && usageSkipped && !Store.batterySkipped(context) && !LiveTracking.batteryUnrestricted(context)
    }
    val notificationsNeeded = remember(refreshKey) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Store.notificationsSkipped(context) &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
    var showWakeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(askWakeTime) {
        if (askWakeTime) {
            showWakeDialog = true
            onWakeTimeAsked()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Abinsbetti") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!usageAccess && !usageSkipped) {
                item { UsageAccessCard(onChanged) }
            }
            if (batteryNeeded) {
                item { BatteryCard(onChanged) }
            }
            if (notificationsNeeded) {
                item { NotificationsCard(liveTracking = !usageAccess, onChanged = onChanged) }
            }
            item {
                if (session != null) {
                    ActiveCard(session, now, usageAccess, onChanged)
                } else {
                    StartCard(
                        nowMs = now,
                        maxNightMs = settings.maxNightMs,
                        videoCounts = settings.defaultVideoCounts,
                        liveTracking = !usageAccess,
                        onVideoCountsChange = {
                            Store.saveSettings(context, settings.copy(defaultVideoCounts = it))
                            onChanged()
                        },
                        onStart = {
                            val result = Sessions.startWithAlarm(
                                context, settings.defaultVideoCounts, System.currentTimeMillis(),
                            )
                            if (result == Sessions.StartResult.NeedsWakeTime) showWakeDialog = true
                            onChanged()
                        },
                    )
                }
            }
            if (nights.isNotEmpty()) {
                item { Text("Past nights", style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(nights, key = { _, night -> night.session.startMs }) { index, night ->
                    NightCard(night, onShare = {
                        share(context, Format.shareText(context, settings.name, night, nights.drop(index)))
                    })
                }
            }
        }
    }

    if (showWakeDialog) {
        WakeTimeDialog(
            initialMinutes = Store.lastManualWakeMinutes(context),
            onDismiss = { showWakeDialog = false },
            onConfirm = { hour, minute ->
                showWakeDialog = false
                val nowMs = System.currentTimeMillis()
                val wakeMs = Times.nextOccurrence(hour, minute, nowMs)
                Store.setLastManualWakeMinutes(context, hour * 60 + minute)
                Sessions.startWithWakeTime(context, wakeMs, Store.settings(context).defaultVideoCounts, nowMs)
                if (wakeMs - nowMs > settings.maxNightMs) {
                    Toast.makeText(
                        context, "Only the last ${settings.maxNightHours}h before waking count", Toast.LENGTH_LONG,
                    ).show()
                }
                onChanged()
            },
        )
    }
}

@Composable
private fun UsageAccessCard(onChanged: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How should nights be tracked?", style = MaterialTheme.typography.titleMedium)
            Text(
                "With Usage access, Abinsbetti reads Android's screen history in the morning. It's the most " +
                    "accurate option, knows which app was a video app, and runs nothing overnight.",
            )
            Text(
                "Without it, a notification stays on overnight while the app records screen on/off itself. " +
                    "Video then means \"something was playing audio while unlocked\".",
            )
            Text(
                "Either way, everything stays on this phone; only what you share leaves it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { UsageAccess.openSettings(context) }) { Text("Grant usage access") }
                TextButton(onClick = { Store.skipUsageAccess(context); onChanged() }) { Text("Track live") }
            }
        }
    }
}

@Composable
private fun BatteryCard(onChanged: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keep live tracking running", style = MaterialTheme.typography.titleMedium)
            Text(
                "Some phones stop background apps at night. Let Abinsbetti run unrestricted so the night " +
                    "isn't flagged as unmonitored. If yours still stops it, see dontkillmyapp.com for your phone.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { LiveTracking.requestBatteryUnrestricted(context) }) { Text("Allow") }
                TextButton(onClick = { Store.skipBattery(context); onChanged() }) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun NotificationsCard(liveTracking: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Morning summary", style = MaterialTheme.typography.titleMedium)
            Text(
                if (liveTracking) {
                    "Allow notifications to see the overnight tracking notification (with an \"I'm up\" button) " +
                        "and get your result shortly after your alarm."
                } else {
                    "Allow notifications to get your result shortly after your alarm."
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("Allow") }
                TextButton(onClick = { Store.skipNotifications(context); onChanged() }) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun StartCard(
    nowMs: Long,
    maxNightMs: Long,
    videoCounts: Boolean,
    liveTracking: Boolean,
    onVideoCountsChange: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val alarm = remember(nowMs) { Alarms.nextUsable(context, nowMs, maxNightMs) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ready for bed?", style = MaterialTheme.typography.titleLarge)
            Text(
                if (alarm != null) {
                    val app = Apps.label(context, alarm.packageName)?.let { " ($it)" } ?: ""
                    "Counts until your alarm at ${Format.clock(alarm.triggerMs)}$app."
                } else {
                    "No alarm found for tonight. You'll be asked when you expect to wake up."
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Video watching counts as sleep attempt")
                    Text(
                        "Locked in once you start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = videoCounts, onCheckedChange = onVideoCountsChange)
            }
            if (liveTracking) {
                Text(
                    "Live tracking: a notification stays on overnight. Video means audio playing while unlocked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start sleeping")
            }
            Text(
                "Tip: add the Abinsbetti tile to Quick Settings (see Settings) to start with one tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveCard(session: ActiveSession, nowMs: Long, usageAccess: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmCancel by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sleep attempt running", style = MaterialTheme.typography.titleLarge)
            Text(
                "Since ${Format.clock(session.startMs)}, until ${Format.clock(session.endMs)} " +
                    "(${Format.wake(context, session)})",
            )
            if (session.countedStartMs > session.startMs) {
                Text("Counting starts at ${Format.clock(session.countedStartMs)} (max night length).")
            }
            Text("Video ${if (session.videoCounts) "counts" else "doesn't count"} tonight.")
            if (session.mode == TrackingMode.LIVE) Text("Live tracking: keep the notification.")
            if (session.mode == TrackingMode.USAGE && nowMs >= session.endMs && !usageAccess) {
                Text("Grant usage access to compute this night.", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { Sessions.wakeUp(context, System.currentTimeMillis()) }
                        onChanged()
                    }
                }) { Text("I'm up") }
                OutlinedButton(onClick = { confirmCancel = true }) { Text("Cancel night") }
            }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel tonight?") },
            text = { Text("The night is discarded and won't appear in your history.") },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; Sessions.cancel(context); onChanged() }) {
                    Text("Discard")
                }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightCard(night: NightRecord, onShare: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Format.nightLabel(night.session.startMs), style = MaterialTheme.typography.labelLarge)
                    Text(Format.duration(night.attemptedMs), style = MaterialTheme.typography.headlineMedium)
                }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
            }
            Text(
                "${Format.clock(night.session.startMs)} → ${Format.clock(night.session.endMs)} · " +
                    Format.wake(context, night.session),
            )
            Text(
                Format.breakdown(night),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Format.tracking(night)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (night.unmonitoredMs > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                if (night.segments.isEmpty()) Text("No screen time at all.")
                night.segments.forEach { segment ->
                    val app = Apps.label(context, segment.packageName)?.let { " · $it" } ?: ""
                    Text(
                        "${Format.clock(segment.startMs)}–${Format.clock(segment.endMs)}  " +
                            "${Format.segmentKind(segment.kind)}$app  ${Format.duration(segment.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeTimeDialog(initialMinutes: Int, onDismiss: () -> Unit, onConfirm: (hour: Int, minute: Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When will you wake up?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No alarm was detected for tonight. Enter the time you expect to get up; your sleep attempt counts until then.")
                TimeInput(state = state)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}
