package app.lightsout.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lightsout.R
import app.lightsout.SleepTileService
import app.lightsout.Store
import app.lightsout.UsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenVideoApps: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Store.settings(context)) }
    fun update(new: app.lightsout.Settings) {
        settings = new
        Store.saveSettings(context, new)
    }

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = settings.name,
                onValueChange = { update(settings.copy(name = it)) },
                label = { Text("Your name (shown when sharing)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Video counts by default", Modifier.weight(1f))
                Switch(
                    checked = settings.defaultVideoCounts,
                    onCheckedChange = { update(settings.copy(defaultVideoCounts = it)) },
                )
            }

            ListItem(
                headlineContent = { Text("Video apps") },
                supportingContent = { Text("${settings.videoPackages.size} selected") },
                modifier = Modifier.clickable(onClick = onOpenVideoApps),
            )

            Column {
                Text("Ignore lock-screen glances shorter than ${settings.glanceSeconds}s")
                Slider(
                    value = settings.glanceSeconds.toFloat(),
                    onValueChange = { update(settings.copy(glanceSeconds = it.roundToInt())) },
                    valueRange = 0f..120f,
                    steps = 11,
                )
            }

            Column {
                Text("Max night length: ${settings.maxNightHours}h")
                Text(
                    "Alarms further away are ignored, and earlier time isn't counted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.maxNightHours.toFloat(),
                    onValueChange = { update(settings.copy(maxNightHours = it.roundToInt())) },
                    valueRange = 6f..16f,
                    steps = 9,
                )
            }

            Text(
                "These settings are locked into a night when it starts. Agree on the same values with " +
                    "your challenge partner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(onClick = { requestTile(context) }) { Text("Add Quick Settings tile") }
            } else {
                Text("To add the tile: swipe down twice, tap the pencil, and drag \"Appinsbetti\" into place.")
            }
            OutlinedButton(onClick = { UsageAccess.openSettings(context) }) { Text("Usage access settings") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(Store.settings(context).videoPackages) }
    val apps by produceState<List<Pair<String, String>>?>(null) {
        value = withContext(Dispatchers.IO) { launchableApps(context) }
    }

    Scaffold(topBar = { BackTopBar("Video apps", onBack) }) { padding ->
        val list = apps ?: emptyList()
        // Keep selected apps that aren't installed, so they can still be unticked.
        val installed = list.map { it.first }.toSet()
        val rows = list + selected.filter { it !in installed }.sorted().map { it to it }
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            item {
                Text(
                    "Screen time in these apps counts as video. Whether video counts towards your sleep " +
                        "attempt is chosen each night. This list needs Usage access; with live tracking, video " +
                        "means audio playing while the phone is unlocked.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (apps == null) item { Text("Loading apps…", Modifier.padding(16.dp)) }
            items(rows, key = { it.first }) { (pkg, label) ->
                val toggle = {
                    selected = if (pkg in selected) selected - pkg else selected + pkg
                    Store.saveSettings(context, Store.settings(context).copy(videoPackages = selected))
                }
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { Text(pkg) },
                    trailingContent = { Checkbox(checked = pkg in selected, onCheckedChange = { toggle() }) },
                    modifier = Modifier.clickable { toggle() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
    )
}

/** (package, label) of every app with a launcher icon, sorted by label. */
private fun launchableApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != context.packageName }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}

private fun requestTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, SleepTileService::class.java),
        context.getString(R.string.tile_label),
        AndroidIcon.createWithResource(context, R.drawable.ic_moon),
        context.mainExecutor,
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Tile is already there"
            else -> "Tile not added"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
