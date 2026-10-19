package com.mrmoeini.smsforwarder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Keeps one alarm set for the next time any measurement rule is due. */
object MeasureScheduler {

    private const val REQ = 5151

    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            app, REQ,
            Intent(app, MeasureAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)

        val now = System.currentTimeMillis()
        var next = Long.MAX_VALUE
        for (r in MeasureStore.load(app)) {
            if (!r.enabled || !r.isConfigured()) continue
            if (r.dateTo > 0L && now > r.dateTo) continue
            val last = MeasureStore.lastRun(app, r.id)
            var due = if (last > 0L) last + r.intervalMin * 60_000L else now
            if (r.dateFrom > 0L && due < r.dateFrom) due = r.dateFrom
            if (due < next) next = due
        }
        if (next == Long.MAX_VALUE) return
        val trigger = maxOf(next, now + 2000L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }
}

/** Fires when a measurement is due. */
class MeasureAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync(context)
    }
}

/** Re-schedules after reboot, app update, or clock/timezone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) return
        MeasureScheduler.schedule(context)
        if ((action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) &&
            AgentStore.isEnabled(context)
        ) {
            try {
                AgentService.start(context)
            } catch (_: Exception) {
            }
        }
    }
}

private fun BroadcastReceiver.runAsync(context: Context) {
    val pending = goAsync()
    Thread {
        try {
            MeasureRunner.runDue(context, 9000L)
        } catch (e: Exception) {
            RuleStore.addLog(context, "measure ERROR ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            pending.finish()
        }
    }.start()
}
