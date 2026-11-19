package com.uriya.medreminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CH_DOSE = "doses"
    private const val CH_REFILL = "refill"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val dose = NotificationChannel(CH_DOSE, "תזכורות לתרופות", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "התראה בזמן שצריך לקחת תרופה"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
        }
        val refill = NotificationChannel(CH_REFILL, "מלאי נמוך", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "התראה כשהמלאי של תרופה עומד להיגמר"
        }
        nm.createNotificationChannels(listOf(dose, refill))
    }

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(ctx: Context, req: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun dose(ctx: Context, d: Dose) {
        if (!hasPermission(ctx)) return
        val id = d.key.hashCode()
        fun action(act: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, ReminderReceiver::class.java)
                .setAction(act)
                .putExtra(Scheduler.EXTRA_MED, d.med.id)
                .putExtra(Scheduler.EXTRA_DATE, d.date.toString())
                .putExtra(Scheduler.EXTRA_TIME, d.time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val details = listOf(
            d.med.dose,
            if (d.med.perDose > 1) "${d.med.perDose} יח׳" else "",
            if (d.med.food != FoodRule.NONE) d.med.food.label else "",
            d.med.notes
        ).filter { it.isNotBlank() }.joinToString(" · ")

        val n = NotificationCompat.Builder(ctx, CH_DOSE)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(MED_COLORS[d.med.colorIndex.mod(MED_COLORS.size)].toInt())
            .setContentTitle("${d.med.form.emoji} הגיע הזמן: ${d.med.name}")
            .setContentText(listOf(d.time, details).filter { it.isNotBlank() }.joinToString(" · "))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(ctx, id))
            .setAutoCancel(false)
            .addAction(0, "✓ נלקח", action(Scheduler.ACTION_TAKE))
            .addAction(0, "⏰ עוד ${Scheduler.SNOOZE_MINUTES} דק׳", action(Scheduler.ACTION_SNOOZE))
            .addAction(0, "דלג", action(Scheduler.ACTION_SKIP))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    /** One notification per time slot: single dose → regular, several → combined with "take all". */
    fun notifyDoses(ctx: Context, doses: List<Dose>) {
        doses.groupBy { it.date to it.time }.values.forEach { list ->
            if (list.size == 1) dose(ctx, list.first()) else group(ctx, list)
        }
    }

    private fun groupId(date: String, time: String) = "group|$date|$time".hashCode()

    fun group(ctx: Context, list: List<Dose>) {
        if (!hasPermission(ctx) || list.isEmpty()) return
        val date = list.first().date.toString()
        val time = list.first().time
        val id = groupId(date, time)
        fun action(act: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, ReminderReceiver::class.java)
                .setAction(act)
                .putExtra(Scheduler.EXTRA_MED, Scheduler.ALL)
                .putExtra(Scheduler.EXTRA_DATE, date)
                .putExtra(Scheduler.EXTRA_TIME, time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val style = NotificationCompat.InboxStyle()
        list.forEach { d ->
            val extra = listOf(
                d.med.dose,
                if (d.med.perDose > 1) "${d.med.perDose} יח׳" else "",
                if (d.med.food != FoodRule.NONE) d.med.food.label else ""
            ).filter { it.isNotBlank() }.joinToString(" · ")
            style.addLine("${d.med.form.emoji} ${d.med.name}" + if (extra.isNotEmpty()) " — $extra" else "")
        }
        val title = "הגיע הזמן ל-${list.size} תרופות ($time)"
        style.setBigContentTitle(title)
        val n = NotificationCompat.Builder(ctx, CH_DOSE)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(MED_COLORS[0].toInt())
            .setContentTitle("💊 $title")
            .setContentText(list.joinToString(", ") { it.med.name })
            .setStyle(style)
            .setNumber(list.size)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(ctx, id))
            .setAutoCancel(false)
            .addAction(0, "✓ לקחתי הכול (${list.size})", action(Scheduler.ACTION_TAKE_ALL))
            .addAction(0, "⏰ עוד ${Scheduler.SNOOZE_MINUTES} דק׳", action(Scheduler.ACTION_SNOOZE))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelGroup(ctx: Context, date: String, time: String) {
        NotificationManagerCompat.from(ctx).cancel(groupId(date, time))
    }

    fun refill(ctx: Context, med: Medication) {
        if (!hasPermission(ctx)) return
        val id = "refill|${med.id}".hashCode()
        val n = NotificationCompat.Builder(ctx, CH_REFILL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("המלאי של ${med.name} נמוך")
            .setContentText("נשארו ${med.stock} יחידות — כדאי לחדש מרשם / לקנות")
            .setContentIntent(openAppIntent(ctx, id))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun test(ctx: Context) {
        if (!hasPermission(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_DOSE)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("💊 התראת בדיקה")
            .setContentText("ההתראות עובדות כמו שצריך")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(ctx, 7))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(7, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(ctx: Context, key: String) {
        NotificationManagerCompat.from(ctx).cancel(key.hashCode())
    }
}
