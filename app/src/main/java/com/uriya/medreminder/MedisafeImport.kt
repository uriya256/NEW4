package com.uriya.medreminder

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Imports the CSV report exported by Medisafe:
 *   סוג,שם,נרשם ב,נקבע ל,ערך,הערות
 *   "תרופה","sertraline","21.10.2026 10:23","21.10.2026 10:30","נלקח",""
 */
object MedisafeImport {

    data class Row(
        val name: String,
        val scheduled: LocalDateTime,
        val recorded: LocalDateTime?,
        val status: DoseStatus
    )

    data class MedPlan(
        val name: String,
        val existing: Medication?,
        /** schedule the medication will have after import */
        val times: List<String>,
        val rows: List<Row>
    ) {
        val taken get() = rows.count { it.status == DoseStatus.TAKEN }
        val skipped get() = rows.count { it.status == DoseStatus.SKIPPED }
        val missed get() = rows.count { it.status == DoseStatus.MISSED }
        val from: LocalDate get() = rows.minOf { it.scheduled.toLocalDate() }
        val to: LocalDate get() = rows.maxOf { it.scheduled.toLocalDate() }
    }

    data class Plan(val meds: List<MedPlan>, val ignoredRows: Int) {
        val totalRows get() = meds.sumOf { it.rows.size }
    }

    // ------------------------------------------------------------------ CSV

    /** RFC-4180 style parser: quoted fields, "" escapes, line breaks inside quotes. */
    fun parseCsv(input: String): List<List<String>> {
        val text = input.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else inQuotes = false
                } else field.append(c)
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',', ';', '\t' -> { row.add(field.toString()); field.clear() }
                    '\r' -> {}
                    '\n' -> {
                        row.add(field.toString()); field.clear()
                        rows.add(row); row = mutableListOf()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString()); rows.add(row)
        }
        return rows
    }

    private val DT = Regex("""(\d{1,4})[./\-](\d{1,2})[./\-](\d{1,4})[ ,T]+(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp][Mm])?""")

    /** "21.10.2026 10:23", "21/10/2026 9:05", "10/21/2026 9:05 AM", "2026-10-21 09:05" */
    fun parseDateTime(s: String): LocalDateTime? {
        val m = DT.find(s.trim()) ?: return null
        val g = m.groupValues
        val a = g[1].toInt(); val b = g[2].toInt(); val c = g[3].toInt()
        var hour = g[4].toInt(); val minute = g[5].toInt()
        val ampm = g[6].lowercase(Locale.ROOT)
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        val (y, mo, d) = when {
            a > 31 -> Triple(a, b, c)                       // yyyy-MM-dd
            ampm.isNotEmpty() && a <= 12 && b > 12 -> Triple(c, a, b) // US M/d/yyyy
            ampm.isNotEmpty() && a <= 12 && s.contains('/') -> Triple(c, a, b)
            else -> Triple(c, b, a)                           // d.M.yyyy (Israel)
        }
        return runCatching { LocalDateTime.of(y, mo, d, hour, minute) }.getOrNull()
    }

    fun parseStatus(raw: String): DoseStatus? {
        val s = raw.trim().lowercase(Locale.ROOT)
        return when {
            s.isEmpty() -> null
            s.contains("לא נלקח") || s.contains("not taken") -> DoseStatus.MISSED
            s.contains("הוחמץ") || s.contains("פוספס") || s.contains("החמצ") || s.contains("miss") -> DoseStatus.MISSED
            s.contains("דולג") || s.contains("דילג") || s.contains("דלג") || s.contains("skip") -> DoseStatus.SKIPPED
            s.contains("נלקח") || s.contains("נלקחה") || s.contains("taken") || s == "take" -> DoseStatus.TAKEN
            else -> null
        }
    }

    private fun fmt(t: LocalTime) = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)

    /** Maps a time from the file to the closest scheduled time (within 3 hours). */
    private fun nearest(t: LocalTime, times: List<String>): String {
        val best = times.minByOrNull { abs(LocalTime.parse(it).toSecondOfDay() - t.toSecondOfDay()) }
        if (best != null && abs(LocalTime.parse(best).toSecondOfDay() - t.toSecondOfDay()) <= 3 * 3600) return best
        return fmt(t)
    }

    // ------------------------------------------------------------------ plan

    fun plan(text: String, existing: List<Medication>): Plan {
        val rows = parseCsv(text)
        // find the header row and the columns we need (fallback: Medisafe's default order)
        val headerIdx = rows.indexOfFirst { r -> r.any { it.trim() == "שם" || it.trim().equals("name", true) } }
        val header = if (headerIdx >= 0) rows[headerIdx].map { it.trim().lowercase(Locale.ROOT) } else emptyList()
        fun col(vararg names: String, fallback: Int): Int {
            val i = header.indexOfFirst { h -> names.any { h == it || h.startsWith(it) } }
            return if (i >= 0) i else fallback
        }
        val cName = col("שם", "name", fallback = 1)
        val cRecorded = col("נרשם", "recorded", "taken at", "actual", fallback = 2)
        val cScheduled = col("נקבע", "scheduled", "schedule", fallback = 3)
        val cValue = col("ערך", "value", "status", "action", fallback = 4)

        var ignored = 0
        val parsed = mutableListOf<Row>()
        rows.drop(headerIdx + 1).forEach { r ->
            if (r.all { it.isBlank() }) return@forEach
            val name = r.getOrNull(cName)?.trim().orEmpty()
            val scheduled = r.getOrNull(cScheduled)?.let { parseDateTime(it) }
            val status = r.getOrNull(cValue)?.let { parseStatus(it) }
            if (name.isEmpty() || scheduled == null || status == null) {
                // footer lines ("Generated by Medisafe") have no date — don't count them as errors
                if (scheduled != null || name.isNotEmpty() && r.size > 3) ignored++
                return@forEach
            }
            val recorded = r.getOrNull(cRecorded)?.let { parseDateTime(it) }
            parsed.add(Row(name, scheduled, recorded, status))
        }

        val meds = parsed.groupBy { it.name }.map { (name, list) ->
            val match = existing.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
            val times = match?.times ?: run {
                // schedule = times used in the last two weeks of the file
                val last = list.maxOf { it.scheduled.toLocalDate() }
                list.filter { !it.scheduled.toLocalDate().isBefore(last.minusDays(14)) }
                    .map { fmt(it.scheduled.toLocalTime()) }
                    .distinct().sorted()
            }
            MedPlan(name, match, times, list.sortedBy { it.scheduled })
        }.sortedBy { it.name }

        return Plan(meds, ignored)
    }

    // ------------------------------------------------------------------ apply

    /** Returns the number of new history records added. */
    fun apply(ctx: Context, plan: Plan): Int {
        Repo.init(ctx)
        val allMeds = Repo.meds.value
        val medsOut = mutableListOf<Medication>()
        val logsOut = mutableListOf<LogEntry>()
        var newIndex = 0
        plan.meds.forEach { p ->
            val start = p.from.toString()
            val existing: Medication? = p.existing
            val med = if (existing != null) {
                if (start < existing.startDate) existing.copy(startDate = start) else existing
            } else {
                Medication(
                    id = System.currentTimeMillis() + newIndex,
                    name = p.name,
                    form = MedForm.PILL,
                    colorIndex = (allMeds.size + newIndex).mod(MED_COLORS.size),
                    times = p.times.ifEmpty { listOf("08:00") },
                    startDate = start,
                    notes = ""
                ).also { newIndex++ }
            }
            medsOut.add(med)
            p.rows.forEach { r ->
                if (r.status == DoseStatus.MISSED) return@forEach // missed = no record
                val date = r.scheduled.toLocalDate().toString()
                val time = nearest(r.scheduled.toLocalTime(), med.times)
                val at = (r.recorded ?: r.scheduled).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                logsOut.add(LogEntry(med.id, date, time, r.status, at))
            }
        }
        return Repo.mergeImport(ctx, medsOut, logsOut)
    }
}
