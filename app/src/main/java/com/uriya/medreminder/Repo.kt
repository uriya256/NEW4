package com.uriya.medreminder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** Simple offline storage: one JSON file in the app's private folder. */
object Repo {
    private var file: File? = null

    private val _meds = MutableStateFlow<List<Medication>>(emptyList())
    val meds: StateFlow<List<Medication>> = _meds

    private val _logs = MutableStateFlow<Map<String, LogEntry>>(emptyMap())
    val logs: StateFlow<Map<String, LogEntry>> = _logs

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "data.json")
        file = f
        if (f.exists()) {
            runCatching { parse(f.readText()) }.onSuccess { (m, l) ->
                _meds.value = m
                _logs.value = l
            }
        }
    }

    private fun parse(text: String): Pair<List<Medication>, Map<String, LogEntry>> {
        val o = JSONObject(text)
        val ma = o.optJSONArray("meds") ?: JSONArray()
        val la = o.optJSONArray("logs") ?: JSONArray()
        val meds = (0 until ma.length()).map { Medication.fromJson(ma.getJSONObject(it)) }
        val logs = (0 until la.length())
            .mapNotNull { runCatching { LogEntry.fromJson(la.getJSONObject(it)) }.getOrNull() }
            .associateBy { it.key }
        return meds to logs
    }

    fun exportJson(): String = JSONObject().apply {
        put("version", 1)
        put("meds", JSONArray().apply { _meds.value.forEach { put(it.toJson()) } })
        put("logs", JSONArray().apply { _logs.value.values.forEach { put(it.toJson()) } })
    }.toString(2)

    fun importJson(ctx: Context, text: String): Boolean {
        init(ctx)
        val parsed = runCatching { parse(text) }.getOrNull() ?: return false
        _meds.value = parsed.first
        _logs.value = parsed.second
        save()
        Scheduler.reschedule(ctx)
        return true
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        // keep about two years of history (enough for yearly reports)
        val cutoff = LocalDate.now().minusDays(800).toString()
        _logs.value = _logs.value.filterValues { it.date >= cutoff }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(exportJson())
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun med(id: Long): Medication? = _meds.value.firstOrNull { it.id == id }

    fun upsert(ctx: Context, med: Medication) {
        init(ctx)
        val list = _meds.value
        _meds.value = if (list.any { it.id == med.id }) list.map { if (it.id == med.id) med else it } else list + med
        save()
        Scheduler.reschedule(ctx)
    }

    fun delete(ctx: Context, id: Long) {
        init(ctx)
        _meds.value = _meds.value.filterNot { it.id == id }
        _logs.value = _logs.value.filterValues { it.medId != id }
        save()
        Scheduler.reschedule(ctx)
    }

    /** status == null clears the record (undo). Also updates stock. */
    fun setStatus(
        ctx: Context,
        medId: Long,
        date: String,
        time: String,
        status: DoseStatus?,
        at: Long = System.currentTimeMillis()
    ) {
        init(ctx)
        val key = doseKey(medId, date, time)
        val prev = _logs.value[key]
        val newLogs = _logs.value.toMutableMap()
        if (status == null) newLogs.remove(key)
        else newLogs[key] = LogEntry(medId, date, time, status, at)
        _logs.value = newLogs

        val wasTaken = prev?.status == DoseStatus.TAKEN
        val isTaken = status == DoseStatus.TAKEN
        val m = med(medId)
        if (m != null && m.tracksStock && wasTaken != isTaken) {
            val delta = if (isTaken) -m.perDose else m.perDose
            val updated = m.copy(stock = (m.stock + delta).coerceAtLeast(0))
            _meds.value = _meds.value.map { if (it.id == medId) updated else it }
            if (isTaken && updated.stock <= updated.refillAt) Notifier.refill(ctx, updated)
        }
        save()
        Notifier.cancel(ctx, key)
        runCatching {
            val stillOpen = dosesFor(LocalDate.parse(date)).any { it.time == time && !it.logged }
            if (!stillOpen) Notifier.cancelGroup(ctx, date, time)
        }
        Scheduler.reschedule(ctx)
    }

    /** Marks every not-yet-marked dose at this date+time as taken. Returns how many. */
    fun takeAll(ctx: Context, date: String, time: String, at: Long = System.currentTimeMillis()): Int {
        init(ctx)
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return 0
        val open = dosesFor(d).filter { it.time == time && !it.logged }
        open.forEach { setStatus(ctx, it.med.id, date, time, DoseStatus.TAKEN, at) }
        Notifier.cancelGroup(ctx, date, time)
        return open.size
    }

    /**
     * Adds/updates medications and adds history records that don't exist yet
     * (records already in the app are kept). Returns how many records were added.
     */
    fun mergeImport(ctx: Context, medsIn: List<Medication>, logsIn: List<LogEntry>): Int {
        init(ctx)
        val meds = _meds.value.toMutableList()
        medsIn.forEach { m ->
            val i = meds.indexOfFirst { it.id == m.id }
            if (i >= 0) meds[i] = m else meds.add(m)
        }
        val logs = _logs.value.toMutableMap()
        var added = 0
        logsIn.forEach { e ->
            if (e.key !in logs) {
                logs[e.key] = e
                added++
            }
        }
        _meds.value = meds
        _logs.value = logs
        save()
        Scheduler.reschedule(ctx)
        return added
    }

    /** Change only the recorded time a dose was taken (no stock change). */
    fun setTakenTime(ctx: Context, medId: Long, date: String, time: String, at: Long) {
        init(ctx)
        val key = doseKey(medId, date, time)
        val e = _logs.value[key] ?: return
        _logs.value = _logs.value + (key to e.copy(at = at))
        save()
    }

    fun dosesFor(date: LocalDate, now: LocalDateTime = LocalDateTime.now()): List<Dose> =
        computeDoses(_meds.value, _logs.value, date, now)
}
