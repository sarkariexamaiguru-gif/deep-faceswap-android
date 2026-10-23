package com.deepfaceswap.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * One screen, every tool.
 *
 * Twenty bespoke screens would be twenty places for the same three bugs to hide.
 * A tool differs from another only in its name, the model it needs and what the
 * engine does with it — so this screen takes those as parameters and the rest is
 * shared: pick, fetch the model, run, show, save.
 *
 * Adding a tool is therefore a registry entry, a runner, and one line on Home.
 */
class ToolActivity : Activity() {

    companion object {
        private const val EXTRA_TOOL = "tool"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BLURB = "blurb"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_SCALE = "scale"

        /** Launch any tool. The only thing a caller has to know. */
        fun intent(
            context: Context,
            toolId: String,
            title: String,
            blurb: String,
            modelFile: String,
            scale: Int = 1,
        ): Intent = Intent(context, ToolActivity::class.java).apply {
            putExtra(EXTRA_TOOL, toolId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BLURB, blurb)
            putExtra(EXTRA_MODEL, modelFile)
            putExtra(EXTRA_SCALE, scale)
        }
    }

    private var toolId = ""
    private var title = ""
    private var blurb = ""
    private var modelFile = ""
    private var scale = 1

    private var source: Bitmap? = null
    private var shown: Bitmap? = null
    private var busy = false

    private lateinit var previewView: ImageView
    private lateinit var statusView: TextView
    private lateinit var runButton: Button

    private val pickRequest = 7303

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolId = intent.getStringExtra(EXTRA_TOOL) ?: "upscale"
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Tool"
        blurb = intent.getStringExtra(EXTRA_BLURB) ?: ""
        modelFile = intent.getStringExtra(EXTRA_MODEL) ?: ""
        scale = intent.getIntExtra(EXTRA_SCALE, 1).coerceAtLeast(1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F8FD"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = title
                textSize = 20f
                setTextColor(Color.parseColor("#0E1424"))
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(button("Choose") { choosePhoto() })
        header.addView(space(8))
        header.addView(button("Save") { save() })
        root.addView(header)

        statusView = TextView(this).apply {
            text = if (blurb.isEmpty()) "Pick a photo to begin" else blurb
            textSize = 12f
            setTextColor(Color.parseColor("#7C88A6"))
        }
        root.addView(statusView)

        previewView = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { choosePhoto() }
        }
        root.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(10); bottomMargin = dp(10) },
        )

        runButton = button(title) { run() }.apply {
            textSize = 14.5f
            setBackgroundColor(Color.parseColor("#1677FF"))
            setTextColor(Color.WHITE)
        }
        root.addView(
            runButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)),
        )

        setContentView(root)
    }

    private fun choosePhoto() {
        if (busy) return
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            pickRequest,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickRequest || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return

        val decoded = runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (decoded == null) {
            statusView.text = "That file could not be read"
            return
        }
        source = decoded
        shown = decoded
        previewView.setImageBitmap(decoded)
        statusView.text = "${decoded.width} x ${decoded.height}"
    }

    // ------------------------------------------------------------- running --

    private fun run() {
        val original = source
        if (original == null) {
            statusView.text = "Pick a photo first"
            return
        }
        if (busy) return

        busy = true
        runButton.isEnabled = false
        statusView.text = "Working …"

        Thread {
            val model = ensureModel()
            if (model == null) {
                ui {
                    statusView.text = "The model for $title could not be downloaded."
                    busy = false
                    runButton.isEnabled = true
                }
                return@Thread
            }
            val result = process(original, model)
            ui {
                if (result != null) {
                    shown = result
                    previewView.setImageBitmap(result)
                    statusView.text =
                        "${original.width} x ${original.height}  →  ${result.width} x ${result.height}"
                } else {
                    statusView.text = "The engine could not run $title on that image."
                }
                busy = false
                runButton.isEnabled = true
            }
        }.start()
    }

    private fun ensureModel(): File? {
        if (modelFile.isEmpty()) return null
        val manager = ModelManager(this)
        val model = manager.models().firstOrNull { it.fileName == modelFile } ?: return null
        if (model.installed) return model.file

        runCatching {
            manager.download(model) { progress ->
                val message = when {
                    progress.failed != null -> "Download failed: ${progress.failed}"
                    progress.finished -> "Model ready"
                    progress.percent >= 0 ->
                        "Downloading ${progress.label} — ${progress.percent}% (${progress.text})"
                    else -> "Downloading ${progress.label} — ${progress.text}"
                }
                ui { statusView.text = message }
            }
        }
        return if (model.installed) model.file else null
    }

    private fun process(bitmap: Bitmap, model: File): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        }

        val buffer = ByteBuffer.allocate(w * h * 4)
        src.copyPixelsToBuffer(buffer)

        val out = NativeBridge.runTool(toolId, model.absolutePath, w, h, buffer.array())
            ?: return null

        val outW = w * scale
        val outH = h * scale
        if (out.size < outW * outH * 4) return null

        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(out))
        }
    }

    // ---------------------------------------------------------------- save --

    private fun save() {
        val result = shown
        if (result == null || busy) {
            statusView.text = "Nothing to save yet"
            return
        }
        val where = writeToGallery(result)
        statusView.text = if (where != null) "Saved · $where" else "Could not save"
        Toast.makeText(
            this, if (where != null) "Saved to $where" else "Could not save", Toast.LENGTH_LONG,
        ).show()
    }

    private fun writeToGallery(bitmap: Bitmap): String? = runCatching {
        val name = "LabPic_${toolId}_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/LabPic",
                )
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
            ) ?: return null
            contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            "Pictures/LabPic"
        } else {
            val dir = File(getExternalFilesDir(null), "LabPic").apply { mkdirs() }
            FileOutputStream(File(dir, name)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(dir, name).absolutePath
        }
    }.getOrNull()

    // ------------------------------------------------------------- widgets --

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            textSize = 12.5f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun space(px: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(px), 1)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
