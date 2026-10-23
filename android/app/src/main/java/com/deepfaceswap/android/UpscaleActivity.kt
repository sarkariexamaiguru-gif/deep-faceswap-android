package com.deepfaceswap.android

import android.app.Activity
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
 * Upscale, end to end.
 *
 * The engine and the 5 MB model already existed — this is the screen that
 * reaches them, and the first tool that proves the whole path: registry entry →
 * download → run → save.
 *
 * The engine caps input at 1024 px on a side, and that is not timidity: 4x of a
 * 4000 px photo is a 16000x12000 image, which is 768 MB before any model work.
 * So a large photo is told why, rather than being killed part-way through.
 */
class UpscaleActivity : Activity() {

    private var source: Bitmap? = null
    private var shown: Bitmap? = null
    private var busy = false

    private lateinit var previewView: ImageView
    private lateinit var statusView: TextView
    private lateinit var runButton: Button

    private val pickRequest = 7302

    /** The engine's own cap, mirrored here so the message can be specific. */
    private val maxEdge = 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                text = "Upscale"
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
            text = "Pick a photo to begin"
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

        runButton = button("Upscale 4x") { run() }.apply {
            textSize = 14.5f
            setBackgroundColor(Color.parseColor("#1677FF"))
            setTextColor(Color.WHITE)
        }
        root.addView(
            runButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50),
            ),
        )

        setContentView(root)
    }

    // ------------------------------------------------------------ picking --

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

        val longest = maxOf(original.width, original.height)
        if (longest > maxEdge) {
            statusView.text = "This photo is ${original.width} x ${original.height}. Upscaling " +
                "runs up to $maxEdge px on a side — 4x of anything larger will not fit in " +
                "memory. Crop it first."
            return
        }

        busy = true
        runButton.isEnabled = false
        statusView.text = "Working …"

        // Seconds of compute, and a download on first use. Never on the UI thread.
        Thread {
            val model = ensureModel()
            if (model == null) {
                ui {
                    statusView.text = "The upscaler model could not be downloaded."
                    busy = false
                    runButton.isEnabled = true
                }
                return@Thread
            }
            val result = upscale(original, model)
            ui {
                if (result != null) {
                    shown = result
                    previewView.setImageBitmap(result)
                    statusView.text =
                        "${original.width} x ${original.height}  →  ${result.width} x ${result.height}"
                } else {
                    statusView.text = "The engine could not upscale that image."
                }
                busy = false
                runButton.isEnabled = true
            }
        }.start()
    }

    /** The model file, downloading it first if this is the first use. */
    private fun ensureModel(): File? {
        val manager = ModelManager(this)
        val model = manager.models()
            .firstOrNull { it.fileName == ModelManager.UPSCALE_FILE } ?: return null
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

    private fun upscale(bitmap: Bitmap, model: File): Bitmap? {
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

        val out = NativeBridge.runTool(
            "upscale", model.absolutePath, w, h, buffer.array(),
        ) ?: return null

        // 4x on each side is 16x the pixels, 4 bytes each.
        if (out.size < w * h * 4 * 16) return null

        return Bitmap.createBitmap(w * 4, h * 4, Bitmap.Config.ARGB_8888).apply {
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
        val name = "LabPic_upscaled_${System.currentTimeMillis()}.png"
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
