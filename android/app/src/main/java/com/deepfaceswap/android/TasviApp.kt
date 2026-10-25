package com.deepfaceswap.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The application object.
 *
 * Its only job is to survive the app's own death and leave a readable note. The
 * handler is installed here rather than in an Activity because an Application's
 * onCreate runs BEFORE any Activity's - so a crash during startup, which is
 * exactly what is happening, is still caught.
 *
 * Three things happen on a crash, in order of how reliably they work:
 *
 *  1. The report is written to the app's own files directory. Always works.
 *  2. It is also written to Downloads through MediaStore. That needs no
 *     permission on Android 10 and later, so the file is findable in Files even
 *     when nothing else works.
 *  3. A notification is posted, tapping it opens the report with a Copy button.
 *     This one needs POST_NOTIFICATIONS on Android 13+, which an app that has
 *     never successfully opened cannot have been granted - so it is the third
 *     path, not the first.
 */
class TasviApp : Application() {

    companion object {
        const val CHANNEL = "tasvi-crash"
        const val REPORT_FILE = "tasvi-crash-report.txt"
        const val EXTRA_REPORT = "report"

        /** The report from the last run, if the last run died. */
        @Volatile
        var lastReport: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        installHandler()
        // A report left by a previous crash is offered on the next start.
        lastReport = runCatching {
            File(filesDir, REPORT_FILE).takeIf { it.isFile }?.readText()
        }.getOrNull()
        if (lastReport != null) {
            DebugLog.add("app", "a crash report from the last run is available")
        }
    }

    private fun installHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { handle(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun handle(thread: Thread, error: Throwable) {
        val stack = StringWriter()
        error.printStackTrace(PrintWriter(stack))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val report = buildString {
            appendLine("TASVI CRASH REPORT")
            appendLine("==================")
            appendLine()
            appendLine("When      : $stamp")
            appendLine("App       : ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            appendLine("Device    : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI       : ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Thread    : ${thread.name}")
            appendLine()
            appendLine("---- stack trace ----")
            append(stack.toString())
        }

        // 1. the app's own folder
        runCatching { File(filesDir, REPORT_FILE).writeText(report) }

        // 2. Downloads, which needs no permission on API 29+
        runCatching { writeToDownloads(report, stamp) }

        // 3. a notification, if the app is allowed to post one
        runCatching { notify(report) }
    }

    private fun writeToDownloads(report: String, stamp: String) {
        val name = "Tasvi-crash-" +
            stamp.replace("-", "").replace(":", "").replace(" ", "-") + ".txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return
            resolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private fun notify(report: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Crash reports",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Shown when Tasvi stops unexpectedly"
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_REPORT, report)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_info)
            .setContentTitle("Tasvi stopped")
            .setContentText("Tap to see the crash report and copy it")
            .setStyle(android.app.Notification.BigTextStyle().bigText(
                "Tap to see the crash report and copy it to the clipboard.",
            ))
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(1001, notification)
    }
}
