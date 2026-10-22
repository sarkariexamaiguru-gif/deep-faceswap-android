package com.deepfaceswap.android

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
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
 * Dark, card-based UI with a bottom navigation bar: Swap / Live / Settings.
 *
 * Only framework widgets are used (no AndroidX), so the build stays
 * dependency-free.
 */
class MainActivity : Activity() {

    private val io = Handler(Looper.getMainLooper())
    private val models by lazy { ModelManager(this) }

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT

    private val sourceFiles = mutableListOf<File>()
    private var targetFile: File? = null

    private lateinit var content: FrameLayout
    private val tabs = mutableListOf<View>()
    private val navItems = mutableListOf<LinearLayout>()
    private val navIcons = mutableListOf<ImageView>()
    private val navLabels = mutableListOf<TextView>()

    // Swap tab
    private lateinit var sourcePreview: ImageView
    private lateinit var sourceHint: View
    private lateinit var sourceCaption: TextView
    private lateinit var targetPreview: ImageView
    private lateinit var targetHint: View
    private lateinit var targetCaption: TextView
    private lateinit var mouthMask: CheckBox
    private lateinit var multiFace: CheckBox
    private lateinit var startButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var resultPreview: ImageView
    private lateinit var logView: TextView

    // Settings tab
    private lateinit var modelsStatus: TextView

    private class Picker(val box: FrameLayout, val preview: ImageView, val hint: View)

    // ------------------------------------------------------------------ setup --

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        selectTab(0)
        log("Engine ${NativeBridge.version()} · arm64-v8a")
        models.copyEmapFromAssetsIfPresent()
        refreshModels()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        root.addView(buildHeader())

        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
        }
        root.addView(content)
        root.addView(buildBottomNav())

        tabs.add(buildSwapTab())
        tabs.add(buildLiveTab())
        tabs.add(buildSettingsTab())
        tabs.forEach {
            it.visibility = View.GONE
            content.addView(it)
        }
        return root
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        })
        row.addView(TextView(this).apply {
            text = "DEEP FACESWAP"
            setTextColor(getColor(R.color.text))
            textSize = 17f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(10), 0, 0, 0)
        })
        return row
    }

    private fun buildBottomNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.surface))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val labels = listOf("Swap", "Live", "Settings")
        val icons = listOf(
            android.R.drawable.ic_menu_gallery,
            android.R.drawable.ic_menu_camera,
            android.R.drawable.ic_menu_preferences,
        )
        for (i in labels.indices) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
                isClickable = true
                setOnClickListener { selectTab(i) }
            }
            val icon = ImageView(this).apply {
                setImageResource(icons[i])
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            val label = TextView(this).apply {
                text = labels[i]
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            }
            item.addView(icon)
            item.addView(label)
            navItems.add(item)
            navIcons.add(icon)
            navLabels.add(label)
            bar.addView(item)
        }
        return bar
    }

    private fun selectTab(index: Int) {
        tabs.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { i, item ->
            val on = i == index
            item.setBackgroundResource(if (on) R.drawable.bg_pill else 0)
            navIcons[i].setColorFilter(getColor(if (on) R.color.accent else R.color.text_dim))
            navLabels[i].setTextColor(getColor(if (on) R.color.accent else R.color.text_dim))
        }
    }

    // ------------------------------------------------------------- swap tab --

    private fun buildSwapTab(): View {
        val col = column()
        col.addView(sectionTitle("Swap"))

        val sourceCard = card()
        sourceCard.addView(cardHeader("SOURCE FACE"))
        val sourcePicker = makePicker("Pick source face") { pickImages(REQ_SOURCE, true) }
        sourcePreview = sourcePicker.preview
        sourceHint = sourcePicker.hint
        sourceCard.addView(sourcePicker.box)
        col.addView(sourceCard)

        sourceCaption = caption("The face to copy FROM")
        col.addView(sourceCaption)

        val targetCard = card()
        targetCard.addView(cardHeader("TARGET IMAGE"))
        val targetPicker = makePicker("Pick target image") { pickImages(REQ_TARGET, false) }
        targetPreview = targetPicker.preview
        targetHint = targetPicker.hint
        targetCard.addView(targetPicker.box)
        col.addView(targetCard)

        targetCaption = caption("The photo to swap the face ONTO")
        col.addView(targetCaption)

        mouthMask = CheckBox(this).apply {
            text = "Preserve target mouth"
            setTextColor(getColor(R.color.text))
        }
        multiFace = CheckBox(this).apply {
            text = "Multi-face (interactive mapping not on device)"
            setTextColor(getColor(R.color.text))
        }
        col.addView(mouthMask)
        col.addView(multiFace)

        startButton = Button(this).apply {
            text = "Start swap"
            isAllCaps = false
            textSize = 16f
            setTextColor(getColor(R.color.bg))
            background = getDrawable(R.drawable.btn_primary)
            layoutParams = LinearLayout.LayoutParams(mp, dp(52)).apply { topMargin = dp(14) }
            setOnClickListener { runSwap() }
        }
        col.addView(startButton)

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
        }
        col.addView(progress)

        resultPreview = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(12) }
        }
        col.addView(resultPreview)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.text_dim))
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(logView)
        return scroll(col)
    }

    // ------------------------------------------------------------- live tab --

    private fun buildLiveTab(): View {
        val col = column()
        col.addView(sectionTitle("Live"))
        val c = card()
        c.addView(cardHeader("LIVE CAMERA"))
        c.addView(TextView(this).apply {
            text = "Real-time camera swapping isn't part of this Android port.\n\n" +
                "This build runs the Rust engine on still images. A live mode would " +
                "need a camera pipeline plus per-frame inference, which is a much " +
                "larger piece of work than the image path.\n\n" +
                "Use the Swap tab for photos. For live swapping, the upstream " +
                "desktop build is the right tool."
            setTextColor(getColor(R.color.text))
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        col.addView(c)
        return scroll(col)
    }

    // --------------------------------------------------------- settings tab --

    private fun buildSettingsTab(): View {
        val col = column()
        col.addView(sectionTitle("Settings"))

        val modelsCard = card()
        modelsCard.addView(cardHeader("MODELS"))
        modelsStatus = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(getColor(R.color.text))
        }
        modelsCard.addView(modelsStatus)
        modelsCard.addView(primaryButton("Download models") { downloadModels() })
        modelsCard.addView(secondaryButton("Check models") {
            refreshModels()
            log(models.statusText())
        })
        col.addView(modelsCard)

        val aboutCard = card()
        aboutCard.addView(cardHeader("ABOUT"))
        aboutCard.addView(TextView(this).apply {
            text = "Deep FaceSwap ${NativeBridge.version()}\n" +
                "Rust core via JNI · arm64-v8a\n" +
                "Port of LdDl/deep_faceswap (MIT)"
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        col.addView(aboutCard)
        return scroll(col)
    }

    // ------------------------------------------------------------- widgets --

    private fun scroll(inner: View): View = ScrollView(this).apply {
        addView(inner)
        isFillViewport = true
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text))
        textSize = 22f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(6), 0, dp(12))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.bg_card)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(mp, wc)
    }

    private fun cardHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_dim))
        textSize = 12f
        letterSpacing = 0.15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, 0, 0, dp(10))
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_dim))
        textSize = 13f
        setPadding(dp(4), dp(8), dp(4), dp(14))
    }

    private fun makePicker(hintText: String, onClick: () -> Unit): Picker {
        val box = FrameLayout(this).apply {
            background = getDrawable(R.drawable.bg_card_inner)
            layoutParams = LinearLayout.LayoutParams(mp, dp(200))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val preview = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(mp, mp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(mp, mp)
        }
        hint.addView(TextView(this).apply {
            text = "+"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_dim))
        })
        hint.addView(TextView(this).apply {
            text = hintText
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_dim))
            setPadding(0, dp(6), 0, 0)
        })
        box.addView(preview)
        box.addView(hint)
        return Picker(box, preview, hint)
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(getColor(R.color.bg))
        background = getDrawable(R.drawable.btn_primary)
        layoutParams = LinearLayout.LayoutParams(mp, dp(48)).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(getColor(R.color.text))
        background = getDrawable(R.drawable.btn_secondary)
        layoutParams = LinearLayout.LayoutParams(mp, dp(48)).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- picking --

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
                copied.firstOrNull()?.let { showPreview(sourcePreview, sourceHint, it) }
                sourceCaption.text = "Source: ${sourceFiles.joinToString(", ") { it.name }}"
            }
            REQ_TARGET -> {
                targetFile = copied.firstOrNull()
                targetFile?.let { showPreview(targetPreview, targetHint, it) }
                targetCaption.text = "Target: ${targetFile?.name ?: "?"}"
            }
        }
    }

    private fun showPreview(preview: ImageView, hint: View, file: File) {
        preview.setImageURI(Uri.fromFile(file))
        preview.visibility = View.VISIBLE
        hint.visibility = View.GONE
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

    // ---------------------------------------------------------------- swap --

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
            toast("Models missing — see Settings")
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "outputs").apply { mkdirs() }
        val output = File(outDir, "swapped_$stamp.jpg")

        setBusy(true, "Swapping …")
        Thread {
            val result = NativeBridge.swap(
                this,
                sourceFiles.map { it.absolutePath },
                target.absolutePath,
                output.absolutePath,
                mouthMask.isChecked,
                multiFace.isChecked,
            )
            io.post {
                setBusy(false, null)
                if (result.ok) {
                    log("Swap complete → ${output.absolutePath}")
                    resultPreview.setImageURI(Uri.fromFile(output))
                    resultPreview.visibility = View.VISIBLE
                    toast("Done")
                } else {
                    log("Swap failed: ${result.error}")
                    toast("Swap failed")
                }
            }
        }.start()
    }

    private fun downloadModels() {
        setBusy(true, "Downloading models …")
        Thread {
            val summary = try {
                models.downloadAll { msg -> io.post { log(msg) } }
            } catch (e: Exception) {
                "Download error: ${e.message}"
            }
            io.post {
                setBusy(false, null)
                log(summary)
                refreshModels()
            }
        }.start()
    }

    private fun refreshModels() {
        if (::modelsStatus.isInitialized) modelsStatus.text = models.statusText()
    }

    // -------------------------------------------------------------- helpers --

    private fun setBusy(busy: Boolean, message: String?) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        startButton.isEnabled = !busy
        startButton.alpha = if (busy) 0.5f else 1f
        message?.let { log(it) }
    }

    private fun log(message: String) {
        if (::logView.isInitialized) logView.append("$message\n")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_SOURCE = 1
        const val REQ_TARGET = 2
    }
}
