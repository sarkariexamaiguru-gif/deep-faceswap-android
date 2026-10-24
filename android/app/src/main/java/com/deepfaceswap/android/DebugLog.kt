package com.deepfaceswap.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A debug log.
 *
 * The app has failed to complete a swap every single time it has been tried, and
 * each attempt has produced one sentence. That is not enough to work from: the
 * interesting part is not the error, it is the ORDER of events around it - which
 * models were chosen, which stage was reached, what the engine was told.
 *
 * Kept in memory in a ring buffer, so it costs nothing until it is asked for,
 * and written to a file when someone asks to share it.
 */
object DebugLog {

    private const val CAPACITY = 400

    /** On by default: a log nobody enabled is a log that is not there when it matters. */
    @Volatile
    var enabled: Boolean = true

    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        if (!enabled) return
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast("${clock.format(Date())}  $tag  $message")
    }

    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) {
            "Nothing logged yet. Run a swap first."
        } else {
            lines.joinToString("\n")
        }

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun size(): Int = lines.size

    /**
     * A header describing the build and the machine.
     *
     * The first question about any failure is what was running when it happened,
     * and this is the cheapest place to answer it.
     */
    fun environment(context: android.content.Context): String = buildString {
        append("LabPic ")
        append(runCatching { context.packageManager
            .getPackageInfo(context.packageName, 0).versionName }.getOrDefault("?"))
        append("  (build ")
        append(runCatching { context.packageManager
            .getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L))
        append(")\n")
        append("Native: ")
        append(NativeBridge.version())
        append("\n")
        append("Device: ")
        append(android.os.Build.MANUFACTURER)
        append(" ")
        append(android.os.Build.MODEL)
        append("  Android ")
        append(android.os.Build.VERSION.RELEASE)
        append("  abi ")
        append(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        append("\n")
        val runtime = Runtime.getRuntime()
        append("Memory: ")
        append((runtime.maxMemory() / 1_048_576).toInt())
        append(" MB max, ")
        append(((runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1_048_576).toInt())
        append(" MB free")
    }
}
