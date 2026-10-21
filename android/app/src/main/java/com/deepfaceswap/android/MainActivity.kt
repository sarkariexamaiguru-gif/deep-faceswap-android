package com.deepfaceswap.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-screen UI: choose one or more source faces and a target image, then run
 * the native face swap and preview the result.
 */
class MainActivity : Activity() {

    private val io = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var targetLabel: TextView
    private lateinit var resultImage: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var mouthMaskBox: CheckBox
    private lateinit var multiFaceBox: CheckBox

    private val sourceFiles = mutableListOf<File>()
    private var targetFile: File? = null

    private val models by lazy { ModelManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        log("Engine: ${NativeBridge.version()}")
        log(models.statusText())
        models.copyEmapFromAssetsIfPresent()
    }

    // ------------------------------------------------------------------ UI ---

    private fun buildUi(): ViewGroup {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title("Deep FaceSwap (Android)"))
        root.addView(subtitle("Rust core via JNI · arm64-v8a"))

        root.addView(button("1 · Pick source face image(s)") { pickImages(REQ_SOURCE, multiple = true) })
        sourceLabel = subtitle("No source selected")
        root.addView(sourceLabel)

        root.addView(button("2 · Pick target image") { pickImages(REQ_TARGET, multiple = false) })
        targetLabel = subtitle("No target selected")
        root.addView(targetLabel)

        mouthMaskBox = CheckBox(this).apply { text = "Preserve target mouth (mouth mask)" }
        multiFaceBox = CheckBox(this).apply { text = "Multi-face (interactive mapping not available on device)" }
        root.addView(mouthMaskBox)
        root.addView(multiFaceBox)

        root.addView(button("3 · Run swap") { runSwap() })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = ProgressBar.GONE
        }
        root.addView(progress)

        root.addView(button("Check / download models") { downloadModels() })

        resultImage = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(resultImage)

        statusView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(statusView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.DKGRAY)
        setPadding(0, 0, 0, 8)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------- picking ---

    private fun pickImages(requestCode: Int, multiple: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return

        val copied = uris.mapNotNull { copyToCache(it) }
        when (requestCode) {
            REQ_SOURCE -> {
                sourceFiles.clear()
                sourceFiles.addAll(copied)
                sourceLabel.text = "Source: ${sourceFiles.joinToString(", ") { it.name }}"
            }
            REQ_TARGET -> {
                targetFile = copied.firstOrNull()
                targetLabel.text = "Target: ${targetFile?.name ?: "?"}"
            }
        }
    }

    private fun copyToCache(uri: Uri): File? = try {
        val name = queryName(uri) ?: "img_${System.currentTimeMillis()}.jpg"
        val dir = File(cacheDir, "inputs").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}_$name")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            FileOutputStream(out).use { input.copyTo(it) }
        }
        out
    } catch (e: Exception) {
        log("Failed to read $uri: ${e.message}")
        null
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    // ---------------------------------------------------------------- swap ---

    private fun runSwap() {
        if (sourceFiles.isEmpty()) {
            toast("Pick at least one source image")
            return
        }
        val target = targetFile
        if (target == null) {
            toast("Pick a target image")
            return
        }
        if (!models.isReady()) {
            log("Cannot swap — missing models:\n" + models.missing().joinToString("\n"))
            toast("Models missing — tap 'Check / download models'")
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "outputs").apply { mkdirs() }
        val output = File(outDir, "swapped_$stamp.jpg")

        setBusy(true, "Swapping ...")
        Thread {
            val result = NativeBridge.swap(
                this,
                sourceFiles.map { it.absolutePath },
                target.absolutePath,
                output.absolutePath,
                mouthMaskBox.isChecked,
                multiFaceBox.isChecked,
            )
            io.post {
                setBusy(false, null)
                if (result.ok) {
                    log("Swap complete -> ${output.absolutePath}")
                    resultImage.setImageURI(Uri.fromFile(output))
                    toast("Done")
                } else {
                    log("Swap failed: ${result.error}")
                    toast("Swap failed")
                }
            }
        }.start()
    }

    private fun downloadModels() {
        setBusy(true, "Downloading models ...")
        Thread {
            val summary = try {
                models.downloadAll { msg -> io.post { log(msg) } }
            } catch (e: Exception) {
                "Download error: ${e.message}"
            }
            io.post {
                setBusy(false, null)
                log(summary)
                log(models.statusText())
            }
        }.start()
    }

    // -------------------------------------------------------------- helpers --

    private fun setBusy(busy: Boolean, message: String?) {
        progress.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
        message?.let { log(it) }
    }

    private fun log(message: String) {
        statusView.append("$message\n")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_SOURCE = 1
        const val REQ_TARGET = 2
    }
}
