package app.lightsout

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import app.lightsout.core.EventType
import app.lightsout.core.RawEvent
import java.io.File
import kotlin.concurrent.thread

/** Events recorded by [NightService], in the same format the analyzer reads from usage history. */
object LiveLog {
    private fun file(context: Context) = File(context.filesDir, "live-events.csv")

    @Synchronized
    fun append(context: Context, timeMs: Long, type: Int) = file(context).appendText("$timeMs,$type\n")

    @Synchronized
    fun read(context: Context): List<RawEvent> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split(',')
            val time = parts.getOrNull(0)?.toLongOrNull()
            val type = parts.getOrNull(1)?.toIntOrNull()
            if (time != null && type != null) RawEvent(time, type) else null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}

object LiveTracking {
    const val NOTIFICATION_ID = 2
    private const val CHANNEL = "tracking"

    /** Returns false if Android refuses to start the service from here (e.g. from the tile). */
    fun start(context: Context): Boolean = try {
        context.startForegroundService(Intent(context, NightService::class.java))
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException; the app starts it when next opened.
        false
    }

    /** Called when the app is opened and on boot: restarts tracking if a live night is running. */
    fun ensureRunning(context: Context) {
        val session = Store.activeSession(context) ?: return
        if (session.mode == TrackingMode.LIVE && System.currentTimeMillis() < session.endMs) start(context)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, NightService::class.java))
    }

    fun updateNotification(context: Context, session: ActiveSession) {
        if (!canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, session))
    }

    fun notification(context: Context, session: ActiveSession?): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Overnight tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a night is tracked without Usage access"
            }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val wakeUp = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, NightActionReceiver::class.java).setAction(NightActionReceiver.ACTION_WAKE_UP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = session?.let {
            "Until ${Format.clock(it.endMs)} · video ${if (it.videoCounts) "counts" else "doesn't count"}"
        } ?: "Starting…"
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("Sleep attempt running")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_moon), "I'm up", wakeUp)
                    .build()
            )
            .build()
    }

    /** When the app's process died between [fromMs] and [toMs]; empty before Android 11. */
    fun processDeaths(context: Context, fromMs: Long, toMs: Long): List<Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .map { it.timestamp }
            .filter { it in fromMs..toMs }
    }

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun requestBatteryUnrestricted(context: Context) {
        val intent = Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * Records screen on/off, lock/unlock and media playback overnight. Android only delivers these
 * broadcasts to a running app, hence a foreground service. It never holds a wake lock: it only
 * reacts to events, and polls media state only while the phone is unlocked (and therefore awake).
 */
class NightService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var keyguard: KeyguardManager
    private var interactive = false
    private var unlocked = false
    private var mediaPlaying = false
    private var listening = false

    private val mediaCheck = Runnable { checkMedia() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    interactive = true
                    record(now, EventType.SCREEN_INTERACTIVE)
                    recordLockState(now)
                    if (stopIfNightOver(now)) return
                }
                Intent.ACTION_SCREEN_OFF -> {
                    interactive = false
                    record(now, EventType.SCREEN_NON_INTERACTIVE)
                }
                Intent.ACTION_USER_PRESENT -> {
                    unlocked = true
                    record(now, EventType.KEYGUARD_HIDDEN)
                }
                Intent.ACTION_SHUTDOWN -> record(now, EventType.DEVICE_SHUTDOWN)
            }
            checkMedia()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            // The stream may only report active a moment after a player starts.
            handler.postDelayed(mediaCheck, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        // A start after the first one means Android killed and restarted the service.
        val now = System.currentTimeMillis()
        record(now, EventType.SERVICE_STARTED)
        interactive = getSystemService(PowerManager::class.java).isInteractive
        record(now, if (interactive) EventType.SCREEN_INTERACTIVE else EventType.SCREEN_NON_INTERACTIVE)
        recordLockState(now)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly after every startForegroundService(), even if we stop right away.
        val session = Store.activeSession(this)
        val notification = LiveTracking.notification(this, session)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(LiveTracking.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(LiveTracking.NOTIFICATION_ID, notification)
        }
        if (session == null || session.mode != TrackingMode.LIVE || stopIfNightOver(System.currentTimeMillis())) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listening) {
            listening = true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SHUTDOWN)
            }
            // System broadcasts only, so no exported flag is needed.
            registerReceiver(screenReceiver, filter)
            audio.registerAudioPlaybackCallback(playbackCallback, handler)
            checkMedia()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (listening) {
            unregisterReceiver(screenReceiver)
            audio.unregisterAudioPlaybackCallback(playbackCallback)
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun record(timeMs: Long, type: Int) = LiveLog.append(this, timeMs, type)

    private fun recordLockState(timeMs: Long) {
        unlocked = !keyguard.isKeyguardLocked
        record(timeMs, if (unlocked) EventType.KEYGUARD_HIDDEN else EventType.KEYGUARD_SHOWN)
    }

    /** "Something is playing audio" is the only media signal available without special access. */
    private fun checkMedia() {
        handler.removeCallbacks(mediaCheck)
        if (!interactive || !unlocked) return
        val playing = audio.isMusicActive
        if (playing != mediaPlaying) {
            mediaPlaying = playing
            record(System.currentTimeMillis(), if (playing) EventType.MEDIA_STARTED else EventType.MEDIA_STOPPED)
        }
        handler.postDelayed(mediaCheck, MEDIA_POLL_MS)
    }

    /** The alarm turning the screen on usually ends the night; hand over to the finalize worker. */
    private fun stopIfNightOver(nowMs: Long): Boolean {
        val session = Store.activeSession(this) ?: return false
        if (nowMs < session.endMs) return false
        Sessions.finalizeSoon(this)
        stopSelf()
        return true
    }

    private companion object {
        const val MEDIA_POLL_MS = 10_000L
    }
}

/** "I'm up" from the tracking notification. */
class NightActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE_UP) return
        val pending = goAsync()
        thread {
            try {
                Sessions.wakeUp(context, System.currentTimeMillis())?.let { Notifications.showResult(context, it) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WAKE_UP = "app.lightsout.WAKE_UP"
    }
}

/** Tracking services don't survive a reboot; restart it if the night is still running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) LiveTracking.ensureRunning(context)
    }
}
