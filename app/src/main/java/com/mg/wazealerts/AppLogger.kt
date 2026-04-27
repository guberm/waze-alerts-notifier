package com.mg.wazealerts

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    data class Entry(
        val timeMs: Long,
        val level: Char,
        val tag: String,
        val message: String
    )

    private const val MAX = 1000
    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()

    fun d(tag: String, msg: String) = append('D', tag, msg)
    fun i(tag: String, msg: String) = append('I', tag, msg)
    fun w(tag: String, msg: String) = append('W', tag, msg)
    fun e(tag: String, msg: String) = append('E', tag, msg)

    private fun append(level: Char, tag: String, msg: String) {
        android.util.Log.println(
            when (level) { 'E' -> android.util.Log.ERROR; 'W' -> android.util.Log.WARN; 'I' -> android.util.Log.INFO; else -> android.util.Log.DEBUG },
            tag, msg
        )
        synchronized(lock) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), level, tag, msg))
        }
    }

    fun all(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) { buffer.clear() }

    fun formatted(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return all().reversed().joinToString("\n") { e ->
            "${fmt.format(Date(e.timeMs))} [${e.level}] ${e.tag}: ${e.message}"
        }
    }

    fun exportToFile(context: Context): Uri? = runCatching {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "waze_log_${System.currentTimeMillis()}.txt")
        file.writeText(formatted())
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
