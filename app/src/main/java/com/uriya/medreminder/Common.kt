package com.uriya.medreminder

import android.app.TimePickerDialog
import android.content.Context
import java.time.LocalDateTime
import java.time.LocalTime
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val HE: Locale = Locale.forLanguageTag("he")

/** Sunday first, as in Israel. Values are java.time.DayOfWeek numbers. */
val DAY_ORDER = listOf(7, 1, 2, 3, 4, 5, 6)
val DAY_SHORT = mapOf(7 to "א׳", 1 to "ב׳", 2 to "ג׳", 3 to "ד׳", 4 to "ה׳", 5 to "ו׳", 6 to "ש׳")

fun daysText(days: Set<Int>): String =
    if (days.isEmpty() || days.size == 7) "כל יום"
    else "ימים " + DAY_ORDER.filter { it in days }.joinToString(", ") { DAY_SHORT.getValue(it) }

fun medColor(i: Int) = Color(MED_COLORS[i.mod(MED_COLORS.size)])

fun hhmm(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))

fun toMillis(date: LocalDate, time: LocalTime): Long =
    LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Shows a clock to choose when a dose was actually taken. */
fun pickTakenTime(ctx: Context, date: LocalDate, initial: LocalTime, onPicked: (Long) -> Unit) {
    TimePickerDialog(
        ctx,
        { _, h, m -> onPicked(toMillis(date, LocalTime.of(h, m))) },
        initial.hour, initial.minute, true
    ).show()
}

/** "HH:mm", or "dd/MM HH:mm" when it was taken on a different day than scheduled. */
fun takenText(scheduledDate: LocalDate, ms: Long): String {
    val dt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()
    return if (dt.toLocalDate() == scheduledDate) dt.format(DateTimeFormatter.ofPattern("HH:mm"))
    else dt.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
}

fun dayTitle(date: LocalDate): String {
    val today = LocalDate.now()
    val formatted = date.format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", HE))
    return when (date) {
        today -> "היום · $formatted"
        today.minusDays(1) -> "אתמול · $formatted"
        today.plusDays(1) -> "מחר · $formatted"
        else -> formatted
    }
}

fun statusColor(s: DoseStatus): Color = when (s) {
    DoseStatus.TAKEN -> Color(0xFF30A46C)
    DoseStatus.SKIPPED -> Color(0xFF8B8D98)
    DoseStatus.MISSED -> Color(0xFFE5484D)
    DoseStatus.DUE -> Color(0xFFF59E0B)
    DoseStatus.UPCOMING -> Color(0xFF2F6FED)
}

@Composable
fun MedAvatar(med: Medication, size: Dp = 46.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(medColor(med.colorIndex).copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(med.form.emoji, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
fun StatusChip(s: DoseStatus) {
    Surface(shape = RoundedCornerShape(50), color = statusColor(s).copy(alpha = 0.14f)) {
        Text(
            s.label,
            color = statusColor(s),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}
