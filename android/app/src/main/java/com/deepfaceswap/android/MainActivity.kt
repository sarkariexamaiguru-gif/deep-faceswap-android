package com.deepfaceswap.android

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dark, card-based UI: bottom navigation (Swap / Settings), sub-tabs in
 * Settings (Models / Quality / Device / About), and a real staged progress bar
 * fed by the native engine while a swap runs.
 *
 * Framework widgets only — no AndroidX — so the build stays dependency-free.
 */
class MainActivity : Activity() {

    private val io = Handler(Looper.getMainLooper())
    private val models by lazy { ModelManager(this) }

    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT

    private val sourceFiles = mutableListOf<File>()
    private var targetFile: File? = null

    private var pendingCaptureUri: Uri? = null
    private var pendingCameraIsSource = true
    private var pendingAfterPermission: (() -> Unit)? = null

    // chrome
    private lateinit var content: FrameLayout
    private val tabs = mutableListOf<View>()
    private val navItems = mutableListOf<LinearLayout>()
    private val navIcons = mutableListOf<ImageView>()
    private val navLabels = mutableListOf<TextView>()

    // swap tab
    private lateinit var sourcePreview: ImageView
    private lateinit var sourceHint: View
    private lateinit var sourceCaption: TextView
    private lateinit var targetPreview: ImageView
    private lateinit var targetHint: View
    private lateinit var targetCaption: TextView
    private lateinit var mouthMask: Switch
    private lateinit var multiFace: Switch
    private lateinit var startButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var stageLabel: TextView
    private var polling = false
    private lateinit var resultPreview: ImageView
    private lateinit var logView: TextView

    // settings tab
    private val subtabLabels = mutableListOf<TextView>()
    private val subtabIndicators = mutableListOf<View>()
    private val subtabPages = mutableListOf<View>()
    private lateinit var modelsRows: LinearLayout
    private lateinit var modelsSummary: TextView
    private lateinit var deviceRows: LinearLayout

    private class Picker(
        val card: View,
        val box: FrameLayout,
        val preview: ImageView,
        val hint: View,
    )

    // ------------------------------------------------------------------ setup --

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        selectTab(0)
        selectSubTab(0)
        models.copyEmapFromAssetsIfPresent()
        renderModels()
        renderDevice()
        log("Engine ${NativeBridge.version()} · arm64-v8a")
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
        val labels = listOf("Swap", "Settings")
        val icons = listOf(R.drawable.ic_nav_swap, R.drawable.ic_nav_settings)
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
            navIcons[i].setColorFilter(getColor(if (on) R.color.white else R.color.text_dim))
            navLabels[i].setTextColor(getColor(if (on) R.color.white else R.color.text_dim))
        }
    }

    // ------------------------------------------------------------- swap tab --

    private fun buildSwapTab(): View {
        val col = column()
        col.addView(pageTitle("Swap"))

        val source = pickerCard(
            label = "SOURCE FACE",
            hint = "Pick source face",
            onPick = { pickImages(REQ_SOURCE, true) },
            onCamera = { capture(isSource = true) },
        )
        sourcePreview = source.preview
        sourceHint = source.hint
        col.addView(source.card)
        sourceCaption = caption("The face to copy FROM")
        col.addView(sourceCaption)

        val target = pickerCard(
            label = "TARGET IMAGE",
            hint = "Pick target image",
            onPick = { pickImages(REQ_TARGET, false) },
            onCamera = { capture(isSource = false) },
        )
        targetPreview = target.preview
        targetHint = target.hint
        col.addView(target.card)
        targetCaption = caption("The photo to swap the face ONTO")
        col.addView(targetCaption)

        col.addView(caption("Swap options live in Settings \u203A Quality"))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(14) }
        }
        startButton = Button(this).apply {
            text = "Start swap"
            isAllCaps = false
            textSize = 16f
            setTextColor(getColor(R.color.bg))
            background = getDrawable(R.drawable.btn_white)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            setOnClickListener { runSwap() }
        }
        val resetButton = Button(this).apply {
            text = "Reset"
            isAllCaps = false
            textSize = 16f
            setTextColor(getColor(R.color.text))
            background = getDrawable(R.drawable.btn_secondary)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(10) }
            setOnClickListener { reset() }
        }
        buttonRow.addView(startButton)
        buttonRow.addView(resetButton)
        col.addView(buttonRow)

        stageLabel = TextView(this).apply {
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
            visibility = View.GONE
            setPadding(0, dp(14), 0, dp(6))
        }
        col.addView(stageLabel)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = getColorStateList(R.color.white)
            progressBackgroundTintList = getColorStateList(R.color.divider)
            layoutParams = LinearLayout.LayoutParams(mp, dp(6))
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

    private fun pickerCard(
        label: String,
        hint: String,
        onPick: () -> Unit,
        onCamera: () -> Unit,
    ): Picker {
        val card = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        head.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_dim))
            textSize = 12f
            letterSpacing = 0.15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, wc, 1f))
        head.addView(iconButton(R.drawable.ic_people) { onPick() })
        head.addView(iconButton(R.drawable.ic_camera) { onCamera() })
        card.addView(head)

        val box = FrameLayout(this).apply {
            background = getDrawable(R.drawable.bg_card_inner)
            layoutParams = LinearLayout.LayoutParams(mp, dp(170))
            isClickable = true
            setOnClickListener { onPick() }
        }
        val preview = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(mp, mp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val hintBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(mp, mp)
        }
        hintBox.addView(TextView(this).apply {
            text = "+"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_dim))
        })
        hintBox.addView(TextView(this).apply {
            text = hint
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_dim))
            setPadding(0, dp(6), 0, 0)
        })
        box.addView(preview)
        box.addView(hintBox)
        card.addView(box)
        return Picker(card, box, preview, hintBox)
    }

    // ------------------------------------------------------------- live tab --
    // --------------------------------------------------------- settings tab --

    private fun buildSettingsTab(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(pageTitle("Settings", padH = 16, topPad = 14))
        wrap.addView(buildSubTabs())

        val host = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(mp, 0, 1f)
        }
        subtabPages.add(buildModelsPage())
        subtabPages.add(buildQualityPage())
        subtabPages.add(buildDevicePage())
        subtabPages.add(buildAboutPage())
        subtabPages.forEach {
            it.visibility = View.GONE
            host.addView(it)
        }
        wrap.addView(host)
        return wrap
    }

    private fun buildSubTabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.surface))
        }
        listOf("Models", "Quality", "Device", "About").forEachIndexed { i, name ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, wc, 1f)
                isClickable = true
                setOnClickListener { selectSubTab(i) }
            }
            val label = TextView(this).apply {
                text = name
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(10))
            }
            val indicator = View(this).apply {
                setBackgroundColor(getColor(R.color.white))
                layoutParams = LinearLayout.LayoutParams(mp, dp(2))
                visibility = View.INVISIBLE
            }
            col.addView(label)
            col.addView(indicator)
            subtabLabels.add(label)
            subtabIndicators.add(indicator)
            row.addView(col)
        }
        return row
    }

    private fun selectSubTab(index: Int) {
        subtabPages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        subtabLabels.forEachIndexed { i, l ->
            l.setTextColor(getColor(if (i == index) R.color.text else R.color.text_dim))
        }
        subtabIndicators.forEachIndexed { i, v ->
            v.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun buildQualityPage(): View {
        val col = column()
        col.addView(sectionLabel("SWAP OPTIONS"))

        val options = card()
        mouthMask = addToggle(
            options,
            "Preserve target mouth",
            "Keep the target's mouth expression",
            false,
        )
        options.addView(divider())
        multiFace = addToggle(
            options,
            "Swap every face",
            "Swap all detected faces, not just the largest",
            false,
        )
        col.addView(options)

        col.addView(sectionLabel("COMING NEXT"))
        val next = card()
        next.addView(TextView(this).apply {
            text = "Pixel boost and the enhancer (master) model arrive in the next phase, " +
                "together with the pipeline builder for chaining models."
            setTextColor(getColor(R.color.text_dim))
            textSize = 13.5f
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        col.addView(next)
        return scroll(col)
    }

    private fun buildModelsPage(): View {
        val col = column()
        col.addView(sectionLabel("ONNX MODELS"))
        modelsSummary = TextView(this).apply {
            setTextColor(getColor(R.color.text_dim))
            textSize = 13.5f
            setPadding(0, 0, 0, dp(12))
        }
        col.addView(modelsSummary)

        val listCard = card()
        modelsRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(modelsRows)
        col.addView(listCard)

        col.addView(primaryButton("Download all") { downloadAll() })
        return scroll(col)
    }

    private fun renderModels() {
        if (!::modelsRows.isInitialized) return
        modelsRows.removeAllViews()
        if (::modelsSummary.isInitialized) modelsSummary.text = models.summaryLine()
        val list = models.models()
        list.forEachIndexed { i, m ->
            modelsRows.addView(modelRow(m))
            if (i != list.lastIndex) modelsRows.addView(divider())
        }
    }

    private fun modelRow(m: ModelManager.Model): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = m.title
            setTextColor(getColor(R.color.text))
            textSize = 15f
        })

        val status = when {
            m.installed -> "installed"
            m.required -> "not installed (required)"
            else -> "not installed"
        }
        val line = SpannableString("${m.fileName}   $status")
        line.setSpan(
            ForegroundColorSpan(getColor(R.color.text_dim)),
            0, m.fileName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val statusColor = if (!m.installed && m.required) R.color.danger else R.color.text_dim
        line.setSpan(
            ForegroundColorSpan(getColor(statusColor)),
            m.fileName.length, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        col.addView(TextView(this).apply {
            text = line
            textSize = 12.5f
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, wc, 1f))

        if (m.installed) {
            row.addView(TextView(this).apply {
                text = "\u2713"
                setTextColor(getColor(R.color.ok))
                textSize = 16f
            })
        } else {
            row.addView(TextView(this).apply {
                text = "Download"
                setTextColor(getColor(R.color.white))
                textSize = 15f
                setPadding(dp(10), dp(6), 0, dp(6))
                isClickable = true
                setOnClickListener { downloadModel(m) }
            })
        }
        return row
    }

    private fun buildDevicePage(): View {
        val col = column()
        col.addView(sectionLabel("DEVICE"))
        val c = card()
        deviceRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(deviceRows)
        col.addView(c)
        return scroll(col)
    }

    private fun renderDevice() {
        if (!::deviceRows.isInitialized) return
        deviceRows.removeAllViews()
        val rows = listOf(
            "ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Engine" to NativeBridge.version(),
            "App" to APP_VERSION,
            "Models dir" to models.modelsDir.absolutePath,
            "On disk" to ModelManager.humanSize(models.totalBytes()),
        )
        rows.forEachIndexed { i, pair ->
            deviceRows.addView(infoRow(pair.first, pair.second))
            if (i != rows.lastIndex) deviceRows.addView(divider())
        }
    }

    private fun infoRow(label: String, value: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(11), 0, dp(11))
        }
        col.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_dim))
            textSize = 12.5f
        })
        col.addView(TextView(this).apply {
            text = value
            setTextColor(getColor(R.color.text))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(3), 0, 0)
        })
        return col
    }

    private fun buildAboutPage(): View {
        val col = column()
        col.addView(sectionLabel("ABOUT"))
        val c = card()
        c.addView(TextView(this).apply {
            text = "Deep FaceSwap ${NativeBridge.version()}"
            setTextColor(getColor(R.color.text))
            textSize = 16f
        })
        c.addView(TextView(this).apply {
            text = "Native Android port of LdDl/deep_faceswap (MIT). " +
                "The Rust engine runs on-device through JNI; ONNX Runtime is " +
                "statically linked and inference is CPU-only on arm64-v8a.\n\n" +
                "Still images only — video needs FFmpeg, and live camera swapping " +
                "needs a camera pipeline, neither of which is in this port."
            setTextColor(getColor(R.color.text_dim))
            textSize = 13.5f
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(8), 0, 0)
        })
        col.addView(c)
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

    private fun pageTitle(text: String, padH: Int = 0, topPad: Int = 6): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text))
        textSize = 22f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(padH), dp(topPad), dp(padH), dp(12))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_dim))
        textSize = 12f
        letterSpacing = 0.15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.bg_card)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(mp, wc)
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_dim))
        textSize = 13f
        setPadding(dp(4), dp(8), dp(4), dp(14))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(mp, dp(1))
    }

    private fun iconButton(res: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(getColor(R.color.text_dim))
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(16) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun addToggle(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        checked: Boolean,
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text))
            textSize = 15f
        })
        col.addView(TextView(this).apply {
            text = subtitle
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, wc, 1f))

        val toggle = Switch(this).apply {
            isChecked = checked
            trackTintList = getColorStateList(R.color.switch_track)
            thumbTintList = getColorStateList(R.color.switch_thumb)
        }
        row.addView(toggle)
        parent.addView(row)
        return toggle
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(getColor(R.color.bg))
        background = getDrawable(R.drawable.btn_white)
        layoutParams = LinearLayout.LayoutParams(mp, dp(50)).apply { topMargin = dp(12) }
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
        startActivityForResult(intent, requestCode)
    }

    /** Take a photo straight into MediaStore (no FileProvider needed). */
    @Suppress("DEPRECATION")
    private fun capture(isSource: Boolean) {
        val go: () -> Unit = {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "dfs_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            val uri = try {
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                null
            }
            if (uri == null) {
                toast("Camera capture unavailable — use the gallery")
            } else {
                pendingCaptureUri = uri
                pendingCameraIsSource = isSource
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                }
                try {
                    startActivityForResult(intent, REQ_CAMERA)
                } catch (e: ActivityNotFoundException) {
                    pendingCaptureUri = null
                    toast("No camera app found — use the gallery")
                }
            }
        }

        val granted = Build.VERSION.SDK_INT >= 29 ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            go()
        } else {
            pendingAfterPermission = go
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return
        val cb = pendingAfterPermission
        pendingAfterPermission = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            cb?.invoke()
        } else {
            toast("Storage permission denied — use the gallery instead")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_CAMERA) {
            val uri = pendingCaptureUri
            pendingCaptureUri = null
            if (resultCode != RESULT_OK || uri == null) return
            val file = copyToCache(uri)
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
                // best effort — leaving the capture in the gallery is harmless
            }
            if (file == null) return
            if (pendingCameraIsSource) {
                sourceFiles.clear()
                sourceFiles.add(file)
                showPreview(sourcePreview, sourceHint, file)
                sourceCaption.text = "Source: ${file.name}"
            } else {
                targetFile = file
                showPreview(targetPreview, targetHint, file)
                targetCaption.text = "Target: ${file.name}"
            }
            return
        }

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

    private fun reset() {
        sourceFiles.clear()
        targetFile = null
        sourcePreview.setImageDrawable(null)
        sourcePreview.visibility = View.GONE
        sourceHint.visibility = View.VISIBLE
        sourceCaption.text = "The face to copy FROM"
        targetPreview.setImageDrawable(null)
        targetPreview.visibility = View.GONE
        targetHint.visibility = View.VISIBLE
        targetCaption.text = "The photo to swap the face ONTO"
        resultPreview.setImageDrawable(null)
        resultPreview.visibility = View.GONE
        log("Reset.")
    }

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
            log("Cannot swap — missing: " + models.missing().joinToString(", "))
            toast("Models missing — see Settings")
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "outputs").apply { mkdirs() }
        val output = File(outDir, "swapped_$stamp.jpg")

        setBusy(true, "Swapping …")
        startProgressPolling()
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
                stopProgressPolling()
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

    private fun downloadModel(model: ModelManager.Model) {
        setBusy(true, "Downloading ${model.title} …")
        Thread {
            val msg = try {
                models.download(model) { p -> io.post { log(p) } }
                "Installed ${model.title}."
            } catch (e: Exception) {
                "Failed (${model.title}): ${e.message}"
            }
            io.post {
                setBusy(false, null)
                log(msg)
                renderModels()
                renderDevice()
            }
        }.start()
    }

    private fun downloadAll() {
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
                renderModels()
                renderDevice()
            }
        }.start()
    }

    // ------------------------------------------------------------- progress --

    /**
     * Polls the native stage counter while a swap runs. A still image is a single
     * inference pass, so there is no true percentage — we show which stage is
     * running and how many faces are done, and only ever move forward.
     */
    private val progressPoll = object : Runnable {
        override fun run() {
            if (!polling) return
            renderStage(NativeBridge.progressSnapshot())
            io.postDelayed(this, 150L)
        }
    }

    private fun startProgressPolling() {
        polling = true
        if (::progress.isInitialized) progress.progress = 0
        if (::stageLabel.isInitialized) stageLabel.text = "Starting"
        io.post(progressPoll)
    }

    private fun stopProgressPolling() {
        polling = false
        io.removeCallbacks(progressPoll)
    }

    private fun renderStage(p: NativeBridge.Progress) {
        val label: String
        val fraction: Float
        when (p.stage) {
            NativeBridge.Stage.LOADING_MODELS -> { label = "Loading models"; fraction = 0.08f }
            NativeBridge.Stage.LOADING_SOURCE -> { label = "Scanning source"; fraction = 0.25f }
            NativeBridge.Stage.DETECTING -> { label = "Detecting faces"; fraction = 0.45f }
            NativeBridge.Stage.SWAPPING -> {
                fraction = if (p.facesTotal > 0) {
                    0.55f + 0.35f * (p.facesDone.toFloat() / p.facesTotal.toFloat())
                } else {
                    0.55f
                }
                label = if (p.facesTotal > 1) {
                    "Swapping faces ${p.facesDone}/${p.facesTotal}"
                } else {
                    "Swapping"
                }
            }
            NativeBridge.Stage.SAVING -> { label = "Saving"; fraction = 0.95f }
            NativeBridge.Stage.DONE -> { label = "Done"; fraction = 1f }
            NativeBridge.Stage.IDLE -> { label = "Working"; fraction = 0.05f }
        }
        if (::stageLabel.isInitialized) stageLabel.text = label
        if (::progress.isInitialized) progress.progress = (fraction * 100).toInt()
    }

    // -------------------------------------------------------------- helpers --

    private fun setBusy(busy: Boolean, message: String?) {
        if (::progress.isInitialized) progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (::stageLabel.isInitialized) stageLabel.visibility = if (busy) View.VISIBLE else View.GONE
        if (::startButton.isInitialized) {
            startButton.isEnabled = !busy
            startButton.alpha = if (busy) 0.5f else 1f
        }
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
        const val REQ_CAMERA = 3
        const val REQ_PERMISSION = 4
        const val APP_VERSION = "0.4.0"
    }
}
