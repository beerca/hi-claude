package app.lightsout

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile as QsTile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Quick Settings tile: one tap at bedtime starts the night. */
class SleepTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val now = System.currentTimeMillis()
        Sessions.finalizeIfDue(this, now)
        if (Store.activeSession(this) != null) {
            openApp(null)
            return
        }
        when (val result = Sessions.startWithAlarm(this, Store.settings(this).defaultVideoCounts, now)) {
            is Sessions.StartResult.Started -> {
                refresh()
                // Android may not let a tile start the tracking service; opening the app does.
                if (!result.trackingStarted) {
                    openApp(null)
                    return
                }
                Toast.makeText(this, "Good night. Counting until ${Format.clock(result.session.endMs)}", Toast.LENGTH_LONG)
                    .show()
            }
            Sessions.StartResult.NeedsWakeTime -> openApp(MainActivity.ACTION_ASK_WAKE_TIME)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val session = Store.activeSession(this)
        tile.state = if (session != null) QsTile.STATE_ACTIVE else QsTile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = session?.let { "Until ${Format.clock(it.endMs)}" } ?: "Tap at bedtime"
        }
        tile.updateTile()
    }

    private fun openApp(action: String?) {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
