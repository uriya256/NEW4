package com.uriya.medreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Keeps exactly one alarm armed: the next upcoming dose that hasn't been
 * taken/skipped yet. When it fires, ReminderReceiver shows the notifications
 * and calls reschedule() again.
 */
object Scheduler {
    const val ACTION_FIRE = "com.uriya.medreminder.FIRE"
    const val ACTION_SNOOZE_FIRE = "com.uriya.medreminder.SNOOZE_FIRE"
    const val ACTION_TAKE = "com.uriya.medreminder.TAKE"
    const val ACTION_SKIP = "com.uriya.medreminder.SKIP"
    const val ACTION_SNOOZE = "com.uriya.medreminder.SNOOZE"
    const val ACTION_TAKE_ALL = "com.uriya.medreminder.TAKE_ALL"

    /** medId used for actions that apply to every medication due at the same time */
    const val ALL = -1L

    const val EXTRA_AT = "at"
    const val EXTRA_MED = "med"
    const val EXTRA_DATE = "date"
    const val EXTRA_TIME = "time"

    const val SNOOZE_MINUTES = 10L
    private const val REQ_MAIN = 1000

    fun reschedule(ctx: Context) {
        Repo.init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now()
        var next: LocalDateTime? = null
        for (d in 0L..8L) {
            val date = now.toLocalDate().plusDays(d)
            for (dose in Repo.dosesFor(date, now)) {
                if (dose.logged) continue
                val dt = dose.dateTime
                if (dt.isAfter(now) && (next == null || dt.isBefore(next))) next = dt
            }
            if (next != null) break
        }
        val intent = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE)
        if (next != null) intent.putExtra(EXTRA_AT, next.toString())
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_MAIN, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        if (next == null) return
        val ms = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setExact(am, ms, pi)
    }

    fun snooze(ctx: Context, medId: Long, date: String, time: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .setAction(ACTION_SNOOZE_FIRE)
            .putExtra(EXTRA_MED, medId)
            .putExtra(EXTRA_DATE, date)
            .putExtra(EXTRA_TIME, time)
        val pi = PendingIntent.getBroadcast(
            ctx, doseKey(medId, date, time).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setExact(am, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L, pi)
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    private fun setExact(am: AlarmManager, ms: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }
}
