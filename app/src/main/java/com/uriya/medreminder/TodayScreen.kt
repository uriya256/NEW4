package com.uriya.medreminder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.LocalDateTime

@Composable
fun TodayScreen(now: LocalDateTime, meds: List<Medication>, logs: Map<String, LogEntry>) {
    val ctx = LocalContext.current
    var date by rememberSaveable { mutableStateOf(LocalDate.now()) }
    val doses = remember(meds, logs, date, now) { computeDoses(meds, logs, date, now) }
    val canAct = !date.isAfter(now.toLocalDate())
    var takeAllTime by remember { mutableStateOf<String?>(null) }

    takeAllTime?.let { t ->
        val open = doses.filter { it.time == t && !it.logged }
        TakenWhenDialog(
            title = "מתי לקחת את כל ה-${open.size} תרופות של $t?",
            date = date,
            time = t,
            onDismiss = { takeAllTime = null },
            onPicked = { at ->
                takeAllTime = null
                Repo.takeAll(ctx, date.toString(), t, at)
            }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { date = date.minusDays(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "יום קודם")
                }
                Text(
                    dayTitle(date),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = { date = date.plusDays(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "יום הבא")
                }
            }
            if (date != LocalDate.now()) {
                TextButton(onClick = { date = LocalDate.now() }, modifier = Modifier.fillMaxWidth()) {
                    Text("חזרה להיום")
                }
            }
        }

        item { ProgressCard(doses, canAct) }

        if (doses.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🌿", fontSize = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (meds.isEmpty()) "עוד לא הוספת תרופות.\nלחץ על \"תרופה חדשה\" כדי להתחיל."
                        else "אין תרופות מתוכננות ליום הזה",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        doses.groupBy { it.time }.forEach { (time, list) ->
            item(key = "h$time") {
                val open = list.count { !it.logged }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        time,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (canAct && open >= 2) {
                        FilledTonalButton(onClick = { takeAllTime = time }) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("לקחתי הכול ($open)")
                        }
                    }
                }
            }
            items(list, key = { it.key }) { d ->
                DoseCard(
                    d, canAct,
                    onSet = { st, at -> Repo.setStatus(ctx, d.med.id, d.date.toString(), d.time, st, at) },
                    onChangeTime = { at -> Repo.setTakenTime(ctx, d.med.id, d.date.toString(), d.time, at) }
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(doses: List<Dose>, canAct: Boolean) {
    val total = doses.size
    val taken = doses.count { it.status == DoseStatus.TAKEN }
    val missed = doses.count { it.status == DoseStatus.MISSED }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            if (!canAct) {
                Text("$total מנות מתוכננות", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            } else {
                Text(
                    if (total == 0) "אין מנות" else "נלקחו $taken מתוך $total",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else taken / total.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                if (missed > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("⚠️ $missed מנות הוחמצו", color = statusColor(DoseStatus.MISSED), fontWeight = FontWeight.SemiBold)
                } else if (total > 0 && taken == total) {
                    Spacer(Modifier.height(8.dp))
                    Text("🎉 כל הכבוד! לקחת הכול", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DoseCard(
    d: Dose,
    canAct: Boolean,
    onSet: (DoseStatus?, Long) -> Unit,
    onChangeTime: (Long) -> Unit
) {
    val ctx = LocalContext.current
    var askWhen by remember { mutableStateOf(false) }
    val scheduled = LocalTime.parse(d.time)

    fun changeTime() {
        val current = d.loggedAt?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
        } ?: scheduled
        pickTakenTime(ctx, d.date, current, onChangeTime)
    }

    if (askWhen) {
        TakenWhenDialog(
            title = "מתי לקחת את ${d.med.name}?",
            date = d.date,
            time = d.time,
            onDismiss = { askWhen = false },
            onPicked = { at ->
                askWhen = false
                onSet(DoseStatus.TAKEN, at)
            }
        )
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MedAvatar(d.med)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.med.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val sub = listOf(
                        d.med.dose,
                        if (d.med.perDose > 1) "${d.med.perDose} יח׳" else "",
                        if (d.med.food != FoodRule.NONE) d.med.food.label else ""
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (d.med.notes.isNotBlank()) {
                        Text(d.med.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StatusChip(d.status)
            }
            if (canAct) {
                Spacer(Modifier.height(10.dp))
                if (d.logged) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isTaken = d.status == DoseStatus.TAKEN
                        Text(
                            if (isTaken) "נלקח בשעה ${takenText(d.date, d.loggedAt ?: 0L)}" else "סומן כדילוג",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isTaken) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = isTaken) { changeTime() }
                        )
                        if (isTaken) {
                            TextButton(onClick = { changeTime() }) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("שינוי שעה")
                            }
                        }
                        TextButton(onClick = { onSet(null, 0L) }) { Text("ביטול") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { askWhen = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("נלקח")
                        }
                        OutlinedButton(onClick = { onSet(DoseStatus.SKIPPED, System.currentTimeMillis()) }, modifier = Modifier.weight(1f)) {
                            Text("דלג")
                        }
                    }
                }
            }
        }
    }
}

/** "When did you take it?" — now / at the scheduled time / another time. */
@Composable
fun TakenWhenDialog(
    title: String,
    date: LocalDate,
    time: String,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit
) {
    val ctx = LocalContext.current
    val scheduled = LocalTime.parse(time)
    val isToday = date == LocalDate.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isToday) {
                    Button(
                        onClick = { onPicked(System.currentTimeMillis()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("עכשיו (${hhmm(System.currentTimeMillis())})") }
                }
                OutlinedButton(
                    onClick = { onPicked(toMillis(date, scheduled)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("בשעה המתוכננת ($time)") }
                OutlinedButton(
                    onClick = {
                        val initial = if (isToday) LocalTime.now().withSecond(0).withNano(0) else scheduled
                        pickTakenTime(ctx, date, initial) { onPicked(it) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("בשעה אחרת…") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
