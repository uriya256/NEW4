package com.uriya.medreminder

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    // re-read system state whenever the user comes back from the system settings
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    val notifOk = remember(refresh) { Notifier.hasPermission(ctx) }
    val exactOk = remember(refresh) { Scheduler.canExact(ctx) }
    val batteryOk = remember(refresh) {
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(Repo.exportJson().toByteArray()) }
            }.isSuccess
            Toast.makeText(ctx, if (ok) "הגיבוי נשמר" else "שמירת הגיבוי נכשלה", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            val ok = text != null && Repo.importJson(ctx, text)
            Toast.makeText(ctx, if (ok) "הנתונים שוחזרו" else "הקובץ לא תקין", Toast.LENGTH_SHORT).show()
        }
    }

    var importPlan by remember { mutableStateOf<MedisafeImport.Plan?>(null) }
    val medisafeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            val plan = text?.let { runCatching { MedisafeImport.plan(it, Repo.meds.value) }.getOrNull() }
            if (plan == null || plan.meds.isEmpty()) {
                Toast.makeText(ctx, "לא נמצאו בקובץ נתוני תרופות של Medisafe", Toast.LENGTH_LONG).show()
            } else {
                importPlan = plan
            }
        }
    }

    importPlan?.let { plan ->
        MedisafeImportDialog(
            plan = plan,
            onDismiss = { importPlan = null },
            onConfirm = {
                importPlan = null
                val added = MedisafeImport.apply(ctx, plan)
                Toast.makeText(ctx, "הייבוא הושלם: נוספו $added רשומות", Toast.LENGTH_LONG).show()
            }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("כדי שהתזכורות יגיעו בזמן", Modifier.padding(top = 0.dp))

        StatusRow(
            ok = notifOk,
            title = "הרשאת התראות",
            okText = "מאושר",
            badText = "ההתראות חסומות — לא תקבל תזכורות",
            button = "פתח הגדרות התראות"
        ) {
            open(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }

        StatusRow(
            ok = exactOk,
            title = "התראות בשעה מדויקת",
            okText = "מאושר",
            badText = "בלי זה התזכורת עלולה להתעכב בכמה דקות",
            button = "אשר שעונים מדויקים"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
            }
        }

        StatusRow(
            ok = batteryOk,
            title = "חיסכון בסוללה",
            okText = "האפליקציה לא מוגבלת",
            badText = "בחלק מהמכשירים (שיאומי, סמסונג וכו׳) חיסכון בסוללה מעכב תזכורות. מומלץ לבחור \"ללא הגבלה\".",
            button = "פתח הגדרות סוללה"
        ) {
            open(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        FilledTonalButton(onClick = { Notifier.test(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("שלח התראת בדיקה")
        }

        SectionTitle("ייבוא מ-Medisafe")
        Text(
            "ב-Medisafe: דוחות ← ייצוא לקובץ CSV. כאן בוחרים את הקובץ, והתרופות וההיסטוריה ייכנסו לאפליקציה. רשומות שכבר קיימות לא ישוכפלו.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FilledTonalButton(
            onClick = {
                medisafeLauncher.launch(
                    arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream", "*/*")
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ייבוא קובץ דוח של Medisafe (CSV)") }

        SectionTitle("גיבוי ושחזור")
        Text(
            "כל הנתונים נשמרים רק בטלפון שלך, בלי אינטרנט ובלי חשבון. כדאי לשמור גיבוי מדי פעם.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { exportLauncher.launch("medreminder-backup-${LocalDate.now()}.json") },
                modifier = Modifier.weight(1f)
            ) { Text("שמירת גיבוי") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.weight(1f)
            ) { Text("שחזור מגיבוי") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "תרופה בזמן · גרסה 1.4\nהאפליקציה היא כלי תזכורת בלבד ואינה מחליפה ייעוץ רפואי.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusRow(
    ok: Boolean,
    title: String,
    okText: String,
    badText: String,
    button: String,
    onFix: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ok) "✅" else "⚠️")
                Text("  $title", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text(
                if (ok) okText else badText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else statusColor(DoseStatus.MISSED),
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!ok) {
                FilledTonalButton(onClick = onFix, modifier = Modifier.padding(top = 8.dp)) { Text(button) }
            }
        }
    }
}

@Composable
private fun MedisafeImportDialog(plan: MedisafeImport.Plan, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val f = DateTimeFormatter.ofPattern("dd/MM/yy")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ייבוא מ-Medisafe") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("נמצאו ${plan.totalRows} רשומות של ${plan.meds.size} תרופות:")
                plan.meds.forEach { m ->
                    Column {
                        Text(
                            "💊 ${m.name}  " + if (m.existing != null) "(קיימת — תתעדכן)" else "(חדשה)",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "שעות: ${m.times.joinToString(", ")} · ${m.from.format(f)}–${m.to.format(f)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "נלקחו ${m.taken} · דולגו ${m.skipped} · הוחמצו ${m.missed}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (plan.ignoredRows > 0) {
                    Text(
                        "${plan.ignoredRows} שורות לא זוהו ולא ייובאו",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(DoseStatus.MISSED)
                    )
                }
                Text(
                    "תרופות חדשות ייווצרו עם השעות שלמעלה. אפשר לערוך אחר כך מינון, סוג ומלאי במסך \"תרופות\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("ייבוא") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
