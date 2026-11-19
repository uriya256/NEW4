@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.uriya.medreminder

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// ---------------------------------------------------------------- list

@Composable
fun MedsScreen(meds: List<Medication>, onEdit: (Medication) -> Unit) {
    if (meds.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("💊", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "הרשימה ריקה.\nהוסף את התרופה הראשונה שלך עם הכפתור למטה.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val sorted = meds.sortedWith(compareBy({ !it.active }, { it.name }))
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(sorted, key = { it.id }) { m -> MedCard(m) { onEdit(m) } }
    }
}

@Composable
private fun MedCard(m: Medication, onClick: () -> Unit) {
    val low = m.tracksStock && m.stock <= m.refillAt
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MedAvatar(m)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (!m.active) {
                        Spacer(Modifier.width(8.dp))
                        Text("(מושהה)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val line1 = listOf(m.dose, m.form.label).filter { it.isNotBlank() }.joinToString(" · ")
                Text(line1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "⏰ ${m.times.joinToString(", ")} · ${daysText(m.days)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (m.tracksStock) {
                    val left = m.daysLeft()
                    Text(
                        (if (low) "⚠️ " else "📦 ") + "במלאי: ${m.stock}" + (left?.let { " (בערך $it ימים)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (low) FontWeight.Bold else FontWeight.Normal,
                        color = if (low) statusColor(DoseStatus.MISSED) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- editor

@Composable
fun EditMedScreen(
    initial: Medication,
    isNew: Boolean,
    onSave: (Medication) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current

    var name by remember { mutableStateOf(initial.name) }
    var dose by remember { mutableStateOf(initial.dose) }
    var form by remember { mutableStateOf(initial.form) }
    var color by remember { mutableStateOf(initial.colorIndex) }
    val times = remember { mutableStateListOf<String>().apply { addAll(initial.times) } }
    var everyDay by remember { mutableStateOf(initial.days.isEmpty() || initial.days.size == 7) }
    val days = remember {
        mutableStateListOf<Int>().apply { addAll(if (initial.days.isEmpty()) (1..7).toList() else initial.days.toList()) }
    }
    var food by remember { mutableStateOf(initial.food) }
    var notes by remember { mutableStateOf(initial.notes) }
    var track by remember { mutableStateOf(initial.tracksStock) }
    var stock by remember { mutableStateOf(if (initial.tracksStock) initial.stock.toString() else "30") }
    var perDose by remember { mutableStateOf(initial.perDose.toString()) }
    var refillAt by remember { mutableStateOf(initial.refillAt.toString()) }
    var active by remember { mutableStateOf(initial.active) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun setTime(index: Int?, value: String) {
        val list = times.toMutableList()
        if (index != null) list.removeAt(index)
        list.add(value)
        val sorted = list.distinct().sorted()
        times.clear()
        times.addAll(sorted)
    }

    fun pickTime(index: Int?) {
        val parts = (index?.let { times[it] } ?: "08:00").split(":")
        TimePickerDialog(
            ctx,
            { _, h, m -> setTime(index, String.format(Locale.US, "%02d:%02d", h, m)) },
            parts[0].toInt(), parts[1].toInt(), true
        ).show()
    }

    val valid = name.isNotBlank() && times.isNotEmpty() && (everyDay || days.isNotEmpty())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "תרופה חדשה" else "עריכת תרופה", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "מחיקה", tint = statusColor(DoseStatus.MISSED))
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        onSave(
                            initial.copy(
                                name = name.trim(),
                                dose = dose.trim(),
                                form = form,
                                colorIndex = color,
                                times = times.toList(),
                                days = if (everyDay) emptySet() else days.toSet(),
                                food = food,
                                notes = notes.trim(),
                                stock = if (track) (stock.toIntOrNull() ?: 0).coerceAtLeast(0) else -1,
                                perDose = (perDose.toIntOrNull() ?: 1).coerceAtLeast(1),
                                refillAt = (refillAt.toIntOrNull() ?: 5).coerceAtLeast(0),
                                active = active
                            )
                        )
                    },
                    enabled = valid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(16.dp)
                        .height(52.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("שמירה", fontSize = 17.sp)
                }
            }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("שם התרופה *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = dose, onValueChange = { dose = it },
                label = { Text("מינון (למשל 500 מ\"ג)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SectionTitle("סוג")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MedForm.entries.forEach { f ->
                    FilterChip(
                        selected = form == f,
                        onClick = { form = f },
                        label = { Text("${f.emoji} ${f.label}") }
                    )
                }
            }

            SectionTitle("צבע")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MED_COLORS.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(MED_COLORS[i]))
                            .border(
                                width = if (color == i) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape
                            )
                            .clickable { color = i },
                        contentAlignment = Alignment.Center
                    ) {
                        if (color == i) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            SectionTitle("שעות נטילה")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                times.forEachIndexed { i, t ->
                    InputChip(
                        selected = false,
                        onClick = { pickTime(i) },
                        label = { Text(t, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close, contentDescription = "הסרה",
                                modifier = Modifier.size(18.dp).clickable { times.removeAt(i) }
                            )
                        }
                    )
                }
                AssistChip(
                    onClick = { pickTime(null) },
                    label = { Text("הוספת שעה") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            SectionTitle("ימים")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("כל יום", modifier = Modifier.weight(1f))
                Switch(checked = everyDay, onCheckedChange = { everyDay = it })
            }
            if (!everyDay) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_ORDER.forEach { d ->
                        FilterChip(
                            selected = d in days,
                            onClick = { if (d in days) days.remove(d) else days.add(d) },
                            label = { Text(DAY_SHORT.getValue(d)) }
                        )
                    }
                }
            }

            SectionTitle("ביחס לאוכל")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodRule.entries.forEach { r ->
                    FilterChip(selected = food == r, onClick = { food = r }, label = { Text(r.label) })
                }
            }

            SectionTitle("מלאי")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("מעקב מלאי והתראה לפני שנגמר", modifier = Modifier.weight(1f))
                Switch(checked = track, onCheckedChange = { track = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = perDose, onValueChange = { perDose = it.filter(Char::isDigit) },
                    label = { Text("יח׳ למנה") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                if (track) {
                    OutlinedTextField(
                        value = stock, onValueChange = { stock = it.filter(Char::isDigit) },
                        label = { Text("כמות במלאי") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = refillAt, onValueChange = { refillAt = it.filter(Char::isDigit) },
                        label = { Text("התרעה כשנשארו") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SectionTitle("הערות")
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("למשל: עם כוס מים מלאה") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("פעילה")
                    Text(
                        "כבה כדי להשהות תזכורות בלי למחוק",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = active, onCheckedChange = { active = it })
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("למחוק את ${initial.name}?") },
            text = { Text("גם ההיסטוריה של התרופה תימחק. אפשר במקום זה לכבות את \"פעילה\".") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("מחיקה", color = statusColor(DoseStatus.MISSED))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } }
        )
    }
}
