package com.oxclient.ui.overlay

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Uygulama genelinde kullanılabilen merkezi log sistemi.
 * Log.d/i/w/e çağrılarının yanına buraya da yazarak overlay'den izleyebilirsin.
 *
 * Kullanım:
 *   OverlayLogger.d("TAG", "mesaj")
 *   OverlayLogger.e("TAG", "hata", exception)
 */
object OverlayLogger {

    enum class Level(val label: String) {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E")
    }

    data class LogEntry(
        val id: Long,
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        /** Kopyalanabilir tek satır format */
        fun toPlainString() = "[$timestamp] ${level.label}/$tag: $message"
    }

    private const val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var idCounter = 0L
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    private fun append(level: Level, tag: String, message: String) {
        val entry = LogEntry(
            id        = idCounter++,
            timestamp = fmt.format(Date()),
            level     = level,
            tag       = tag,
            message   = message
        )
        val current = _entries.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_ENTRIES) current.removeAt(0)
        _entries.value = current
    }

    fun d(tag: String, msg: String) { Log.d(tag, msg);           append(Level.DEBUG, tag, msg) }
    fun i(tag: String, msg: String) { Log.i(tag, msg);           append(Level.INFO,  tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg);           append(Level.WARN,  tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        append(Level.ERROR, tag, if (t != null) "$msg — ${t.message}" else msg)
    }

    fun clear() { _entries.value = emptyList() }

    /** Tüm logları tek string olarak döndür (kopyalama için) */
    fun allAsText(): String = _entries.value.joinToString("\n") { it.toPlainString() }
}

