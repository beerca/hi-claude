package app.lightsout

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Computes the night shortly after it ends and posts the result. */
class FinalizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val night = Sessions.finalizeIfDue(applicationContext, System.currentTimeMillis())
        if (night != null) Notifications.showResult(applicationContext, night)
        return Result.success()
    }
}

/** The system sends this when any app's next setAlarmClock() alarm changes. */
class AlarmChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) return
        Sessions.onNextAlarmChanged(context, System.currentTimeMillis())
    }
}

object Notifications {
    private const val CHANNEL = "results"

    fun showResult(context: Context, night: NightRecord) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Night results", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("${Format.duration(night.attemptedMs)} attempted sleep")
            .setContentText(Format.breakdown(night))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(1, notification)
    }
}
