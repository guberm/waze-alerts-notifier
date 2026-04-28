package com.mg.wazealerts

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

object AppLogger {

    data class Entry(
        val timeMs: Long,
        val level: Char,
        val tag: String,
        val message: String
    )

    private const val MAX_MEMORY = 1000
    private const val MAX_FILE_BYTES = 512_000L   // 512 KB, then rotate
    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    private val writeQueue = LinkedBlockingQueue<String>(4000)
    private var persistFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Start background disk writer once
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogger-disk").apply { isDaemon = true }
    }.also { exec ->
        exec.execute {
            while (true) {
                val line = writeQueue.take()
                val f = persistFile ?: continue
                runCatching {
                    if (f.exists() && f.length() > MAX_FILE_BYTES) {
                        // Keep last 300 lines on rotation
                        val kept = f.readLines().takeLast(300).joinToString("\n")
                        f.writeText(kept + "\n")
                    }
                    f.appendText(line + "\n")
                }
            }
        }
    }

    /** Call from Application or Service onCreate — enables persistent logging. */
    fun init(context: Context) {
        if (persistFile != null) return
        val f = File(context.filesDir, "persist_log.txt")
        persistFile = f
        i("AppLogger", "Persistent log started (file=${f.absolutePath})")
    }

    fun d(tag: String, msg: String) = append('D', tag, msg)
    fun i(tag: String, msg: String) = append('I', tag, msg)
    fun w(tag: String, msg: String) = append('W', tag, msg)
    fun e(tag: String, msg: String) = append('E', tag, msg)

    private fun append(level: Char, tag: String, msg: String) {
        android.util.Log.println(
            when (level) { 'E' -> android.util.Log.ERROR; 'W' -> android.util.Log.WARN; 'I' -> android.util.Log.INFO; else -> android.util.Log.DEBUG },
            tag, msg
        )
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (buffer.size >= MAX_MEMORY) buffer.removeFirst()
            buffer.addLast(Entry(now, level, tag, msg))
        }
        val line = "${fmt.format(Date(now))} [$level] $tag: $msg"
        writeQueue.offer(line)   // non-blocking; drops if queue full
    }

    fun all(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        persistFile?.delete()
    }

    fun formatted(): String {
        // Merge persisted file (pre-crash entries) + in-memory buffer
        val persisted = runCatching { persistFile?.readLines() ?: emptyList() }.getOrDefault(emptyList())
        val inMemory = all().map { e -> "${fmt.format(Date(e.timeMs))} [${e.level}] ${e.tag}: ${e.message}" }
        val merged = (persisted + inMemory).distinct().takeLast(1000)
        return merged.reversed().joinToString("\n")
    }

    fun exportToFile(context: Context): Uri? = runCatching {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "waze_log_${System.currentTimeMillis()}.txt")
        file.writeText(formatted())
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

