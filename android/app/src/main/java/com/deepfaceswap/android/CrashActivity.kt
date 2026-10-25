package com.deepfaceswap.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * The crash report, in full, with a Copy button.
 *
 * A monospace panel that scrolls, because a stack trace is the one thing in this
 * app that should never be truncated. Copy puts the whole report on the
 * clipboard so it can be pasted into a message; Share sends it as text instead.
 */
class CrashActivity : android.app.Activity() {

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = intent.getStringExtra(TasviApp.EXTRA_REPORT)
            ?: TasviApp.lastReport
            ?: runCatching { File(filesDir, TasviApp.REPORT_FILE).readText() }.getOrNull()
            ?: "No crash report was found."

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.canvas))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "Crash report"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.ink))
        })

        root.addView(TextView(this).apply {
            text = "Copy this and send it. It names the exact line that failed."
            textSize = 13f
            setTextColor(getColor(R.color.ink_secondary))
            setPadding(0, dp(6), 0, dp(14))
        })

        val panel = TextView(this).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.ink))
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(getColor(R.color.surface_soft))
        }
        val scroller = ScrollView(this).apply {
            addView(panel, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(scroller)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }

        actions.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                val clip = getSystemService(ClipboardManager::class.java)
                clip?.setPrimaryClip(ClipData.newPlainText("Tasvi crash report", report))
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(10)
        })

        actions.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Tasvi crash report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                runCatching { startActivity(Intent.createChooser(send, "Share the report")) }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })

        root.addView(actions)
        setContentView(root)
    }
}
