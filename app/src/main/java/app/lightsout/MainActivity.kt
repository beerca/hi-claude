package app.lightsout

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.lightsout.ui.HomeScreen
import app.lightsout.ui.LightsOutTheme
import app.lightsout.ui.SettingsScreen
import app.lightsout.ui.VideoAppsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** Bumped whenever stored data may have changed; screens reload on change. */
    private val refreshKey = mutableIntStateOf(0)
    private val askWakeTime = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            LightsOutTheme {
                App(
                    refreshKey = refreshKey.intValue,
                    askWakeTime = askWakeTime.value,
                    onWakeTimeAsked = { askWakeTime.value = false },
                    onChanged = { refreshKey.intValue++ },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { Sessions.finalizeIfDue(applicationContext, System.currentTimeMillis()) }
            refreshKey.intValue++
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.action == ACTION_ASK_WAKE_TIME) {
            askWakeTime.value = true
            intent.action = null
        }
    }

    companion object {
        const val ACTION_ASK_WAKE_TIME = "app.lightsout.ASK_WAKE_TIME"
    }
}

private enum class Screen { HOME, SETTINGS, VIDEO_APPS }

@Composable
private fun App(refreshKey: Int, askWakeTime: Boolean, onWakeTimeAsked: () -> Unit, onChanged: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) {
        screen = if (screen == Screen.VIDEO_APPS) Screen.SETTINGS else Screen.HOME
    }
    when (screen) {
        Screen.HOME -> HomeScreen(
            refreshKey = refreshKey,
            askWakeTime = askWakeTime,
            onWakeTimeAsked = onWakeTimeAsked,
            onChanged = onChanged,
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            onBack = { screen = Screen.HOME; onChanged() },
            onOpenVideoApps = { screen = Screen.VIDEO_APPS },
        )
        Screen.VIDEO_APPS -> VideoAppsScreen(onBack = { screen = Screen.SETTINGS })
    }
}
