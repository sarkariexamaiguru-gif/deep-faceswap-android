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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * The instant tools, as a screen.
 *
 * Crop, rotate, flip, light, colour, sharpen, mono and auto-colour — all
 * arithmetic, no model, nothing to download. They run in milliseconds, which is
 * what makes a live preview possible: every slider move re-runs the whole chain
 * through the engine on a downscaled copy.
 *
 * Its own activity rather than another tab, deliberately. The main shell is due
 * to be rebuilt around a tool grid; putting this behind an intent now means it
 * works today and survives that rebuild untouched.
 */
class EditActivity : Activity() {

    private var source: Bitmap? = null
    private var preview: Bitmap? = null
    private var shown: Bitmap? = null

    private var brightness = 0f
    private var contrast = 0f
    private var saturation = 0f
    private var temperature = 0f
    private var sharpen = 0f
    private var mono = false
    private var autoColour = false
    private var look = 0

    /**
     * A .cube file the user picked.
     *
     * The thirteen built-in looks are arithmetic and invented here. A LUT is
     * somebody else's colour science - a real Amaro or Clarendon - which is the
     * difference between "a filter" and a name people recognise.
     */
    private var lutPath = ""

    private val lutRequest = 7306

    /**
     * Pick a .cube through the system picker.
     *
     * The file is copied into the app's own storage first, because the picker's
     * grant is temporary and the path has to survive being handed to the engine
     * on every render.
     */
    private fun chooseLut() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, lutRequest) }
    }

    private fun adoptLut(uri: android.net.Uri) {
        val copied = runCatching {
            val target = File(cacheDir, "look.cube")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
        if (copied == null) {
            statusView.text = "That file could not be read as a LUT."
            return
        }
        lutPath = copied.absolutePath
        statusView.text = "LUT applied. Tap LUT again to pick another."
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == lutRequest && resultCode == RESULT_OK) {
            data?.data?.let { adoptLut(it) }
        }
    }
    private var rotation = 0
    private var flipH = false

    private lateinit var previewView: ImageView
    private lateinit var statusView: TextView
    private lateinit var stripRow: LinearLayout

    /** Longest edge of the working copy. Small enough to feel instant. */
    private val previewEdge = 720

    private val pickRequest = 7301

    private fun choosePhoto() {
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
        if (requestCode == pickRequest && resultCode == RESULT_OK) {
            data?.data?.let { load(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F8FD"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // ---- header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            label("Edit", 20f, "#0E1424", bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(button(if (lutPath.isEmpty()) "LUT" else "LUT \u00b7 on") { chooseLut() })
        header.addView(space(8))
        header.addView(button("Choose") { choosePhoto() })
        header.addView(space(8))
        header.addView(button("Save") { save() })
        root.addView(header)

        statusView = label("Pick a photo to begin", 12f, "#7C88A6")
        root.addView(statusView)

        // ---- preview
        previewView = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(10); bottomMargin = dp(10) },
        )

        // ---- looks
        root.addView(TextView(this).apply {
            text = "Looks"
            textSize = 11.5f
            setTextColor(Color.parseColor("#4E5C7C"))
        })
        stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(stripRow)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) },
        )
        buildLooks()

        // ---- one-tap geometry and looks
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quick.addView(button("Rotate") { rotation = (rotation + 90) % 360; render() })
        quick.addView(space(6))
        quick.addView(button("Flip") { flipH = !flipH; render() })
        quick.addView(space(6))
        quick.addView(button("Mono") { mono = !mono; render() })
        quick.addView(space(6))
        quick.addView(button("Auto") { autoColour = !autoColour; render() })
        quick.addView(space(6))
        quick.addView(button("Reset") { reset() })
        root.addView(quick)

        // ---- sliders
        root.addView(slider("Light") { brightness = it })
        root.addView(slider("Contrast") { contrast = it })
        root.addView(slider("Colour") { saturation = it })
        root.addView(slider("Warmth") { temperature = it })
        root.addView(slider("Sharpen") { sharpen = it })

        setContentView(root)
    }

    // ------------------------------------------------------------ loading --

    private fun load(uri: Uri) {
        val decoded = runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

        if (decoded == null) {
            statusView.text = "That file could not be read"
            return
        }
        source = decoded
        preview = downscale(decoded, previewEdge)
        statusView.text = "${decoded.width} × ${decoded.height}"
        render()
    }

    private fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    // -------------------------------------------------------------- engine --

    private fun params(): String = JSONObject().apply {
        put("brightness", brightness.toDouble())
        put("contrast", contrast.toDouble())
        put("saturation", saturation.toDouble())
        put("temperature", temperature.toDouble())
        put("sharpen", sharpen.toDouble())
        put("mono", mono)
        put("auto_colour", autoColour)
        put("look", look)
        put("lut_path", lutPath)
        put("rotate", rotation)
        put("flip_h", flipH)
    }.toString()

    /** Run the chain over one bitmap. Null means the engine refused. */
    private fun runOn(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        val buffer = ByteBuffer.allocate(w * h * 4)
        bitmap.copyPixelsToBuffer(buffer)

        val out = NativeBridge.editImage(buffer.array(), w, h, params()) ?: return null
        if (out.size < w * h * 4) return null

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(out))
        }
    }

    private fun render() {
        val base = preview ?: return
        val edited = runOn(base) ?: base
        shown = edited
        previewView.setImageBitmap(edited)
    }

    private fun reset() {
        brightness = 0f; contrast = 0f; saturation = 0f
        temperature = 0f; sharpen = 0f
        mono = false; autoColour = false; rotation = 0; flipH = false; look = 0
        // Rebuild the sliders so their thumbs match the values again.
        recreate()
    }

    /** The looks, in the same order as the engine's table. */
    private val looks = listOf(
        "Original", "Vivid", "Warm", "Cool", "Fade", "Vintage", "Sepia",
        "Dramatic", "Film", "Punch", "Matte", "Noir", "Golden", "Forest",
    )

    private fun buildLooks() {
        stripRow.removeAllViews()
        looks.forEachIndexed { index, name ->
            stripRow.addView(TextView(this).apply {
                text = name
                textSize = 12f
                setPadding(dp(14), dp(9), dp(14), dp(9))
                val on = index == look
                setTextColor(if (on) Color.WHITE else Color.parseColor("#4E5C7C"))
                setBackgroundColor(
                    if (on) Color.parseColor("#1677FF") else Color.parseColor("#FFFFFF"),
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    look = index
                    buildLooks()
                    render()
                }
            })
        }
    }

    // ---------------------------------------------------------------- save --

    private fun save() {
        val original = source
        if (original == null) {
            statusView.text = "Pick a photo first"
            return
        }
        // Full resolution this time, not the preview copy.
        val result = runOn(original)
        if (result == null) {
            statusView.text = "The engine could not finish that edit"
            return
        }
        val where = writeToGallery(result)
        statusView.text = if (where != null) "Saved · $where" else "Could not save"
        Toast.makeText(
            this,
            if (where != null) "Saved to $where" else "Could not save",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun writeToGallery(bitmap: Bitmap): String? {
        val name = "LabPic_${System.currentTimeMillis()}.png"
        return runCatching {
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
    }

    // ------------------------------------------------------------- widgets --

    private fun slider(title: String, onValue: (Float) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val caption = label(title, 11.5f, "#4E5C7C")
        row.addView(caption)

        val bar = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    onValue((value - 100) / 100f)
                    render()
                }

                override fun onStartTrackingTouch(b: SeekBar?) = Unit
                override fun onStopTrackingTouch(b: SeekBar?) = Unit
            })
        }
        row.addView(bar)
        return row
    }

    private fun label(text: String, size: Float, colour: String, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(colour))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

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
