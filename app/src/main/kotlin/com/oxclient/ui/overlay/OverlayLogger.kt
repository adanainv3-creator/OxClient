package com.oxclient.ui.overlay

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object OverlayLogger {

    enum class Level(val label: String, val priority: Int) {
        VERBOSE("V", 0),
        DEBUG  ("D", 1),
        INFO   ("I", 2),
        WARN   ("W", 3),
        ERROR  ("E", 4)
    }

    data class LogEntry(
        val id       : Long,
        val timestamp: String,
        val epochMs  : Long,
        val level    : Level,
        val tag      : String,
        val message  : String
    ) {
        fun toPlainString() = "[${timestamp}] ${level.label}/$tag: $message"
        fun toCsvRow()      = "\"$timestamp\",\"${level.label}\",\"$tag\",\"${message.replace("\"","'")}\""
    }

    private const val MAX_ENTRIES = 1000

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _levelCounts = MutableStateFlow(mapOf<Level, Int>())
    val levelCounts: StateFlow<Map<Level, Int>> = _levelCounts.asStateFlow()

    private var idCounter  = 0L
    private val fmt        = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFmt    = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    @Volatile var minimumLevel: Level = Level.DEBUG

    @Synchronized
    private fun append(level: Level, tag: String, message: String) {
        if (level.priority < minimumLevel.priority) return
        val now = System.currentTimeMillis()
        val entry = LogEntry(
            id        = idCounter++,
            timestamp = fmt.format(Date(now)),
            epochMs   = now,
            level     = level,
            tag       = tag,
            message   = message
        )
        val current = _entries.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_ENTRIES) {
            current.subList(0, current.size - MAX_ENTRIES).clear()
        }
        _entries.value = current
        updateCounts(current)
    }

    private fun updateCounts(entries: List<LogEntry>) {
        val counts = mutableMapOf<Level, Int>()
        entries.forEach { counts[it.level] = (counts[it.level] ?: 0) + 1 }
        _levelCounts.value = counts
    }

    fun v(tag: String, msg: String) { Log.v(tag, msg); append(Level.VERBOSE, tag, msg) }
    fun d(tag: String, msg: String) { Log.d(tag, msg); append(Level.DEBUG,   tag, msg) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); append(Level.INFO,    tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); append(Level.WARN,    tag, msg) }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        append(Level.ERROR, tag, if (t != null) "$msg — ${t.message}" else msg)
    }

    fun clear() {
        _entries.value  = emptyList()
        _levelCounts.value = emptyMap()
    }

    fun filterByLevel(level: Level?): List<LogEntry> =
        if (level == null) _entries.value
        else _entries.value.filter { it.level == level }

    fun filterByTag(tag: String): List<LogEntry> =
        _entries.value.filter { it.tag.equals(tag, ignoreCase = true) }

    fun filterByLevelAndTag(level: Level?, tag: String?): List<LogEntry> {
        var result = _entries.value
        if (level != null) result = result.filter { it.level == level }
        if (!tag.isNullOrBlank()) result = result.filter { it.tag.contains(tag, ignoreCase = true) }
        return result
    }

    fun search(query: String): List<LogEntry> =
        _entries.value.filter {
            it.message.contains(query, ignoreCase = true) ||
            it.tag.contains(query, ignoreCase = true)
        }

    fun allAsText(): String =
        _entries.value.joinToString("\n") { it.toPlainString() }

    fun allAsCsv(): String = buildString {
        appendLine("Timestamp,Level,Tag,Message")
        _entries.value.forEach { appendLine(it.toCsvRow()) }
    }

    fun getUniqueTags(): List<String> =
        _entries.value.map { it.tag }.distinct().sorted()

    fun getCount(level: Level): Int =
        _levelCounts.value[level] ?: 0

    fun getTotalCount(): Int = _entries.value.size

    fun getErrorCount(): Int = getCount(Level.ERROR)
    fun getWarnCount() : Int = getCount(Level.WARN)
}
