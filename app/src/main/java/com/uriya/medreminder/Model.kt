package com.uriya.medreminder

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class MedForm(val label: String, val emoji: String) {
    PILL("כדור", "💊"),
    CAPSULE("כמוסה", "💊"),
    LIQUID("סירופ", "🥄"),
    DROPS("טיפות", "💧"),
    INJECTION("זריקה", "💉"),
    INHALER("משאף", "🌬️"),
    CREAM("משחה", "🧴"),
    OTHER("אחר", "✚")
}

enum class FoodRule(val label: String) {
    NONE("ללא הגבלה"),
    BEFORE("לפני האוכל"),
    WITH("עם האוכל"),
    AFTER("אחרי האוכל")
}

enum class DoseStatus(val label: String) {
    TAKEN("נלקח"),
    SKIPPED("דולג"),
    MISSED("הוחמץ"),
    DUE("הגיע הזמן"),
    UPCOMING("מתוכנן")
}

val MED_COLORS: List<Long> = listOf(
    0xFF2F6FED, 0xFFE5484D, 0xFF30A46C, 0xFFF59E0B,
    0xFF8E4EC6, 0xFF12A594, 0xFFE93D82, 0xFF6B7280
)

data class Medication(
    val id: Long,
    val name: String,
    val dose: String = "",
    val form: MedForm = MedForm.PILL,
    val colorIndex: Int = 0,
    /** "HH:mm", sorted */
    val times: List<String> = listOf("08:00"),
    /** java.time.DayOfWeek values 1..7 (Mon..Sun). Empty = every day */
    val days: Set<Int> = emptySet(),
    val food: FoodRule = FoodRule.NONE,
    val notes: String = "",
    /** -1 = stock not tracked */
    val stock: Int = -1,
    val perDose: Int = 1,
    val refillAt: Int = 5,
    val active: Boolean = true,
    val startDate: String = LocalDate.now().toString()
) {
    val tracksStock: Boolean get() = stock >= 0

    fun isScheduledOn(date: LocalDate): Boolean {
        if (!active) return false
        val start = runCatching { LocalDate.parse(startDate) }.getOrDefault(LocalDate.MIN)
        if (date.isBefore(start)) return false
        return days.isEmpty() || date.dayOfWeek.value in days
    }

    /** Approximate number of days the current stock will last. */
    fun daysLeft(): Int? {
        if (!tracksStock || times.isEmpty()) return null
        val daysPerWeek = if (days.isEmpty()) 7 else days.size
        val perDay = perDose * times.size * daysPerWeek / 7.0
        if (perDay <= 0.0) return null
        return (stock / perDay).toInt()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("dose", dose)
        put("form", form.name)
        put("color", colorIndex)
        put("times", JSONArray(times))
        put("days", JSONArray(days.toList()))
        put("food", food.name)
        put("notes", notes)
        put("stock", stock)
        put("perDose", perDose)
        put("refillAt", refillAt)
        put("active", active)
        put("startDate", startDate)
    }

    companion object {
        fun fromJson(o: JSONObject): Medication {
            val t = o.optJSONArray("times") ?: JSONArray()
            val d = o.optJSONArray("days") ?: JSONArray()
            return Medication(
                id = o.getLong("id"),
                name = o.getString("name"),
                dose = o.optString("dose", ""),
                form = runCatching { MedForm.valueOf(o.optString("form")) }.getOrDefault(MedForm.PILL),
                colorIndex = o.optInt("color", 0),
                times = (0 until t.length()).map { t.getString(it) }.sorted(),
                days = (0 until d.length()).map { d.getInt(it) }.toSet(),
                food = runCatching { FoodRule.valueOf(o.optString("food")) }.getOrDefault(FoodRule.NONE),
                notes = o.optString("notes", ""),
                stock = o.optInt("stock", -1),
                perDose = o.optInt("perDose", 1),
                refillAt = o.optInt("refillAt", 5),
                active = o.optBoolean("active", true),
                startDate = o.optString("startDate", LocalDate.now().toString())
            )
        }
    }
}

data class LogEntry(
    val medId: Long,
    val date: String,
    val time: String,
    val status: DoseStatus,
    val at: Long
) {
    val key: String get() = doseKey(medId, date, time)

    fun toJson(): JSONObject = JSONObject().apply {
        put("m", medId); put("d", date); put("t", time); put("s", status.name); put("a", at)
    }

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(
            medId = o.getLong("m"),
            date = o.getString("d"),
            time = o.getString("t"),
            status = DoseStatus.valueOf(o.getString("s")),
            at = o.optLong("a", 0L)
        )
    }
}

data class Dose(
    val med: Medication,
    val date: LocalDate,
    val time: String,
    val status: DoseStatus,
    val loggedAt: Long?
) {
    val key: String get() = doseKey(med.id, date.toString(), time)
    val logged: Boolean get() = status == DoseStatus.TAKEN || status == DoseStatus.SKIPPED
    val dateTime: LocalDateTime get() = LocalDateTime.of(date, LocalTime.parse(time))
}

fun doseKey(medId: Long, date: String, time: String) = "$medId|$date|$time"

/** Doses after this many minutes without action are counted as missed. */
const val MISSED_AFTER_MINUTES = 60L

fun computeDoses(
    meds: List<Medication>,
    logs: Map<String, LogEntry>,
    date: LocalDate,
    now: LocalDateTime
): List<Dose> =
    meds.filter { it.isScheduledOn(date) }
        .flatMap { m ->
            m.times.map { t ->
                val log = logs[doseKey(m.id, date.toString(), t)]
                val dt = LocalDateTime.of(date, LocalTime.parse(t))
                val status = when {
                    log != null -> log.status
                    dt.plusMinutes(MISSED_AFTER_MINUTES).isBefore(now) -> DoseStatus.MISSED
                    !dt.isAfter(now) -> DoseStatus.DUE
                    else -> DoseStatus.UPCOMING
                }
                Dose(m, date, t, status, log?.at)
            }
        }
        .sortedWith(compareBy({ it.time }, { it.med.name }))
