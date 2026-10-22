package com.deepfaceswap.android

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
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
    private lateinit var startButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var stageLabel: TextView
    private var polling = false

    // pipeline state (P2/P3) — what the swap will actually do
    private var pixelBoost = 2
    private var enhancerOn = true
    private var enhancerStrength = 0.75f
    private var presetName = "Balanced"
    private var syncing = false
    private val presetChips = mutableListOf<TextView>()
    private val boostChips = mutableListOf<TextView>()
    private val swapperChips = mutableListOf<TextView>()
    private var swapperFile = ModelManager.INSWAPPER_FILE
    private lateinit var boostRowHost: LinearLayout
    private lateinit var costLabel: TextView
    private lateinit var strengthLabel: TextView
    private lateinit var strengthBar: SeekBar
    private lateinit var enhancerSwitch: Switch

    // multi-face mode: 0 = off, 1 = swap every face, 2 = map manually
    private var multiFaceMode = 1
    private val modeChips = mutableListOf<TextView>()

    // manual mapping
    private var detectedFaces: NativeBridge.FaceOptions? = null
    private var activeSourceIndex = 0
    private val mappings = mutableListOf<Pair<Int, Int>>()
    private lateinit var mappingCard: LinearLayout
    private lateinit var mappingStatus: TextView
    private lateinit var sourceCrops: LinearLayout
    private lateinit var targetCrops: LinearLayout
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
        targetCaption = caption(
            "The photo to swap the face ONTO \u00B7 JPEG \u00B7 PNG \u00B7 WebP \u00B7 GIF " +
                "\u00B7 BMP \u00B7 TIFF \u00B7 AVIF"
        )
        col.addView(targetCaption)

        col.addView(caption("Swap options live in Settings \u203A Quality"))

        mappingCard = card().apply { visibility = View.GONE }
        mappingCard.addView(sectionLabel("FACE MAPPING"))
        mappingStatus = TextView(this).apply {
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        mappingCard.addView(mappingStatus)
        mappingCard.addView(primaryButton("Detect faces") { detectFacesForMapping() })
        mappingCard.addView(sectionLabel("SOURCE FACES"))
        sourceCrops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mappingCard.addView(hScroll(sourceCrops))
        mappingCard.addView(sectionLabel("TARGET FACES"))
        targetCrops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mappingCard.addView(hScroll(targetCrops))
        col.addView(mappingCard)

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

        col.addView(sectionLabel("SWAPPER MODEL"))
        val swapperCard = card()
        swapperCard.addView(
            chipGrid(
                listOf("inswapper 128", "HyperSwap 1a", "HyperSwap 1c", "Ghost 2", "UniFace"),
                swapperChips,
                3,
            ) { setSwapper(it) }
        )
        swapperCard.addView(TextView(this).apply {
            text = "inswapper is 128px and the lightest. The others are 256px — sharper on " +
                "large faces at roughly 4x the work per pass. Ghost needs its converter " +
                "downloaded too; UniFace needs no converter but is licensed 'Unknown'. " +
                "Get them in Settings \u203A Models."
            setTextColor(getColor(R.color.text_dim))
            textSize = 12.5f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        })
        col.addView(swapperCard)

        col.addView(sectionLabel("PIPELINE PRESET"))
        val presetCard = card()
        presetCard.addView(
            chipGrid(PRESETS, presetChips, 3) { applyPreset(it) }
        )
        costLabel = TextView(this).apply {
            setTextColor(getColor(R.color.text_dim))
            textSize = 12.5f
            setPadding(0, dp(12), 0, 0)
        }
        presetCard.addView(costLabel)
        col.addView(presetCard)

        col.addView(sectionLabel("PIXEL BOOST"))
        val boostCard = card()
        boostRowHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        boostCard.addView(boostRowHost)
        boostCard.addView(TextView(this).apply {
            text = "A swapper always runs at its native size. Above that the face is split " +
                "into an interleaved grid and swapped once per tile, then stitched back " +
                "together — sharper on large faces, and slower by the square of the grid."
            setTextColor(getColor(R.color.text_dim))
            textSize = 12.5f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        })
        col.addView(boostCard)
        rebuildBoostChips()

        col.addView(sectionLabel("MASTER MODEL \u00B7 RESTORE"))
        val enhCard = card()
        enhancerSwitch = addToggle(
            enhCard,
            "Restore faces",
            "Run an enhancer after the swap to recover detail",
            enhancerOn,
        )
        enhancerSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            enhancerOn = checked
            markCustom()
            syncPipelineUi()
        }
        enhCard.addView(divider())

        val strengthRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val strengthHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strengthHead.addView(TextView(this).apply {
            text = "Strength"
            setTextColor(getColor(R.color.text))
            textSize = 15f
        }, LinearLayout.LayoutParams(0, wc, 1f))
        strengthLabel = TextView(this).apply {
            setTextColor(getColor(R.color.text_dim))
            textSize = 14f
        }
        strengthHead.addView(strengthLabel)
        strengthRow.addView(strengthHead)
        strengthBar = SeekBar(this).apply {
            max = 100
            progress = (enhancerStrength * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    enhancerStrength = value / 100f
                    markCustom()
                    syncPipelineUi()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        strengthRow.addView(strengthBar)
        enhCard.addView(strengthRow)
        col.addView(enhCard)

        col.addView(sectionLabel("SWAP OPTIONS"))
        val options = card()
        mouthMask = addToggle(
            options,
            "Preserve target mouth",
            "Keep the target's mouth expression",
            false,
        )
        options.addView(divider())

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        modeRow.addView(TextView(this).apply {
            text = "Faces to swap"
            setTextColor(getColor(R.color.text))
            textSize = 15f
        })
        modeRow.addView(TextView(this).apply {
            text = "Off swaps only the largest face. Manual lets you pair each source " +
                "face with a target face."
            setTextColor(getColor(R.color.text_dim))
            textSize = 13f
            setPadding(0, dp(2), 0, dp(8))
        })
        modeRow.addView(chipGrid(listOf("Off", "Swap all", "Manual"), modeChips, 3) { setMultiFaceMode(it) })
        options.addView(modeRow)
        col.addView(options)

        syncPipelineUi()
        return scroll(col)
    }

    // ------------------------------------------------------------- pipeline --

    /** Chips laid out in rows of [perRow]; [onSelect] gets the flat index. */
    private fun chipGrid(
        labels: List<String>,
        store: MutableList<TextView>,
        perRow: Int,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        store.clear()
        labels.chunked(perRow).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex > 0) setPadding(0, dp(6), 0, 0)
            }
            chunk.forEachIndexed { colIndex, name ->
                val flatIndex = rowIndex * perRow + colIndex
                val chip = TextView(this).apply {
                    text = name
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, wc, 1f).apply {
                        if (colIndex > 0) marginStart = dp(6)
                    }
                    isClickable = true
                    setOnClickListener { onSelect(flatIndex) }
                }
                store.add(chip)
                row.addView(chip)
            }
            container.addView(row)
        }
        return container
    }

    private fun hScroll(inner: View): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(inner)
    }

    private fun paintChips(chips: List<TextView>, selected: Int) {
        chips.forEachIndexed { i, chip ->
            val on = i == selected
            chip.setBackgroundResource(if (on) R.drawable.bg_pill else R.drawable.bg_card_inner)
            chip.setTextColor(getColor(if (on) R.color.white else R.color.text_dim))
        }
    }

    private fun applyPreset(index: Int) {
        when (index) {
            0 -> { presetName = "Fast"; pixelBoost = 1; enhancerOn = false; enhancerStrength = 0.0f }
            1 -> { presetName = "Balanced"; pixelBoost = 2; enhancerOn = true; enhancerStrength = 0.75f }
            2 -> { presetName = "Quality"; pixelBoost = 3; enhancerOn = true; enhancerStrength = 0.80f }
            3 -> { presetName = "Max"; pixelBoost = 4; enhancerOn = true; enhancerStrength = 0.90f }
            else -> { presetName = "Custom" }
        }
        syncPipelineUi()
    }

    private fun setBoost(chipIndex: Int) {
        pixelBoost = boostGrids().getOrElse(chipIndex) { 2 }
        markCustom()
        syncPipelineUi()
    }

    /** Pixel-boost grids valid for the selected swapper (capped at 1024px). */
    private fun boostGrids(): List<Int> =
        if (swapperFile == ModelManager.INSWAPPER_FILE) listOf(1, 2, 3, 4, 6, 8)
        else listOf(1, 2, 3, 4)

    /** Boost chips are labelled in real pixels, so they change with the model. */
    private fun boostLabels(): List<String> {
        val base = if (swapperFile == ModelManager.INSWAPPER_FILE) 128 else 256
        return boostGrids().map { (it * base).toString() }
    }

    private val swapperFiles = listOf(
        ModelManager.INSWAPPER_FILE,
        ModelManager.HYPERSWAP_1A,
        ModelManager.HYPERSWAP_1C,
        ModelManager.GHOST_2,
        ModelManager.UNIFACE,
    )

    private fun swapperIndex(): Int = swapperFiles.indexOf(swapperFile).coerceAtLeast(0)

    private fun setSwapper(index: Int) {
        swapperFile = swapperFiles.getOrElse(index) { ModelManager.INSWAPPER_FILE }
        rebuildBoostChips()
        markCustom()
        syncPipelineUi()
    }

    /** Rebuild the boost chips — their count depends on the swapper's native size. */
    private fun rebuildBoostChips() {
        if (!::boostRowHost.isInitialized) return
        val grids = boostGrids()
        if (pixelBoost !in grids) {
            pixelBoost = grids.lastOrNull { it <= pixelBoost } ?: grids.first()
        }
        boostRowHost.removeAllViews()
        boostRowHost.addView(chipGrid(boostLabels(), boostChips, 3) { setBoost(it) })
        paintChips(boostChips, grids.indexOf(pixelBoost))
    }

    private fun setMultiFaceMode(index: Int) {
        multiFaceMode = index
        if (index != 2) clearMapping()
        syncPipelineUi()
    }

    private fun markCustom() {
        presetName = "Custom"
    }

    /** Push the pipeline state into every control. Guarded so it cannot re-enter. */
    private fun syncPipelineUi() {
        syncing = true
        val presetIndex = PRESETS.indexOf(presetName)
        paintChips(presetChips, presetIndex)
        paintChips(boostChips, boostGrids().indexOf(pixelBoost))
        paintChips(swapperChips, swapperIndex())
        paintChips(modeChips, multiFaceMode)
        if (::mappingCard.isInitialized) {
            mappingCard.visibility = if (multiFaceMode == 2) View.VISIBLE else View.GONE
        }
        if (::enhancerSwitch.isInitialized) enhancerSwitch.isChecked = enhancerOn
        if (::strengthBar.isInitialized) {
            strengthBar.progress = (enhancerStrength * 100).toInt()
            strengthBar.isEnabled = enhancerOn
            strengthBar.alpha = if (enhancerOn) 1f else 0.4f
        }
        if (::strengthLabel.isInitialized) {
            strengthLabel.text = if (enhancerOn) "${(enhancerStrength * 100).toInt()}%" else "off"
        }
        syncing = false
        if (::costLabel.isInitialized) costLabel.text = costLine()
    }

    /**
     * Relative cost, derived arithmetically from the model's native size, the
     * number of tiled passes and whether a restore runs — not a measured time.
     */
    private fun costLine(): String {
        val native = if (swapperFile == ModelManager.INSWAPPER_FILE) 128 else 256
        val passes = pixelBoost * pixelBoost
        val pixelFactor = (native * native).toFloat() / (128f * 128f)
        val enhancerFactor = if (enhancerOn) 1.6f else 1.0f
        val relative = passes * pixelFactor * enhancerFactor
        val plural = if (passes == 1) "pass" else "passes"
        val restore = if (enhancerOn) " + restore" else ""
        val factor = String.format(Locale.US, "%.1f", relative)
        val missing = if (!models.isSwapperInstalled(swapperFile)) " \u00B7 not downloaded" else ""
        return "$presetName \u00B7 ${native}px \u00D7 $pixelBoost \u00B7 $passes $plural$restore" +
            " \u00B7 ~${factor}\u00D7 the inswapper baseline$missing"
    }

    // ------------------------------------------------------ face mapping (P4) --

    private fun clearMapping() {
        detectedFaces = null
        mappings.clear()
        activeSourceIndex = 0
        if (::sourceCrops.isInitialized) {
            sourceCrops.removeAllViews()
            targetCrops.removeAllViews()
        }
        if (::mappingStatus.isInitialized) {
            mappingStatus.text = "No faces detected yet \u2014 tap Detect faces."
        }
    }

    private fun detectFacesForMapping() {
        val target = targetFile
        if (sourceFiles.isEmpty() || target == null) {
            toast("Pick a source and a target first")
            return
        }

        // Fresh directory each time so a stale crop can never be shown.
        val cropsDir = File(cacheDir, "faces")
        cropsDir.deleteRecursively()
        cropsDir.mkdirs()

        setBusy(true, "Detecting faces \u2026")
        val sources = sourceFiles.map { it.absolutePath }
        Thread {
            val faces = NativeBridge.detectFaces(this, sources, target.absolutePath, cropsDir.absolutePath)
            io.post {
                setBusy(false, null)
                if (faces == null) {
                    log("Face detection failed.")
                    toast("Detection failed")
                    return@post
                }
                detectedFaces = faces
                mappings.clear()
                activeSourceIndex = faces.source.firstOrNull()?.index ?: 0
                renderMappingFaces()
            }
        }.start()
    }

    private fun renderMappingFaces() {
        if (!::sourceCrops.isInitialized) return
        sourceCrops.removeAllViews()
        targetCrops.removeAllViews()

        val faces = detectedFaces
        if (faces == null) {
            mappingStatus.text = "No faces detected yet \u2014 tap Detect faces."
            return
        }
        if (faces.source.isEmpty() || faces.target.isEmpty()) {
            mappingStatus.text = "Found ${faces.source.size} source and ${faces.target.size} target faces."
            return
        }

        faces.source.forEach { addFaceChip(sourceCrops, it, isSource = true) }
        faces.target.forEach { addFaceChip(targetCrops, it, isSource = false) }
        mappingStatus.text = mappingStatusText(faces)
    }

    private fun mappingStatusText(faces: NativeBridge.FaceOptions): String {
        val pairs = if (mappings.isEmpty()) {
            "none yet"
        } else {
            mappings.joinToString(", ") { "${it.first}\u2192${it.second}" }
        }
        return "Active source #$activeSourceIndex of ${faces.source.size} source \u00B7 " +
            "${faces.target.size} target\nTap a target face to pair it. Pairs: $pairs"
    }

    private fun addFaceChip(parent: LinearLayout, face: NativeBridge.FaceOption, isSource: Boolean) {
        val selected = isSource && face.index == activeSourceIndex
        val box = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(66)).apply { marginEnd = dp(8) }
            background = getDrawable(R.drawable.bg_card_inner)
            if (selected) foreground = getDrawable(R.drawable.bg_face_ring)
            alpha = if (isSource && !selected) 0.55f else 1f
            isClickable = true
            setOnClickListener { onFaceTapped(face, isSource) }
        }
        box.addView(ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(mp, mp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            BitmapFactory.decodeFile(face.cropPath)?.let { setImageBitmap(it) }
        })
        parent.addView(box)
    }

    private fun onFaceTapped(face: NativeBridge.FaceOption, isSource: Boolean) {
        if (isSource) {
            activeSourceIndex = face.index
        } else {
            val pair = activeSourceIndex to face.index
            if (!mappings.remove(pair)) mappings.add(pair)
        }
        renderMappingFaces()
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

    /**
     * Extension for the output file. Formats the `image` crate can encode keep
     * their extension; anything else (AVIF, for instance, which we can read but
     * not write) falls back to PNG so nothing is silently re-encoded to JPEG.
     */
    private fun outputExtensionFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return if (ext in ENCODABLE_OUTPUT) ext else "png"
    }

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
            clearMapping()
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
                clearMapping()
                sourceFiles.clear()
                sourceFiles.addAll(copied)
                copied.firstOrNull()?.let { showPreview(sourcePreview, sourceHint, it) }
                sourceCaption.text = "Source: ${sourceFiles.joinToString(", ") { it.name }}"
            }
            REQ_TARGET -> {
                clearMapping()
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
        clearMapping()
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
        if (enhancerOn && !models.enhancerInstalled) {
            log("Restore is on but the enhancer isn't downloaded — swapping without it.")
        }
        if (!models.isSwapperInstalled(swapperFile)) {
            log("The selected swapper ($swapperFile) isn't downloaded — Settings › Models.")
            toast("Download the selected swapper first")
            return
        }
        if (swapperFile == ModelManager.GHOST_2 &&
            !models.isSwapperInstalled(ModelManager.GHOST_CONVERTER)
        ) {
            log("Ghost needs its embedding converter — Settings › Models.")
            toast("Download the Ghost converter too")
            return
        }
        if (multiFaceMode == 2 && mappings.isEmpty()) {
            toast("Map at least one source → target face")
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "outputs").apply { mkdirs() }
        // Output follows the input format where we can encode it, else PNG.
        val output = File(outDir, "swapped_$stamp.${outputExtensionFor(target.name)}")

        setBusy(true, "Swapping …")
        startProgressPolling()
        val useMouthMask = mouthMask.isChecked
        val strength = if (enhancerOn) enhancerStrength else 1.0f
        val manualPairs = mappings.toList()
        val manual = multiFaceMode == 2
        val sources = sourceFiles.map { it.absolutePath }
        Thread {
            val result = if (manual) {
                NativeBridge.swapMapped(
                    this,
                    sources,
                    target.absolutePath,
                    output.absolutePath,
                    manualPairs,
                    swapperFile,
                    useMouthMask,
                    pixelBoost,
                    strength,
                )
            } else {
                NativeBridge.swap(
                    this,
                    sources,
                    target.absolutePath,
                    output.absolutePath,
                    swapperFile,
                    useMouthMask,
                    multiFaceMode == 1,
                    pixelBoost,
                    strength,
                )
            }
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
        if (::progress.isInitialized) {
            progress.visibility = if (busy) View.VISIBLE else View.GONE
            if (busy && !polling) progress.progress = 0
        }
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
        const val APP_VERSION = "0.8.0"

        /** Preset chip labels; the last one is a state, not a value set. */
        val PRESETS = listOf("Fast", "Balanced", "Quality", "Max", "Custom")

        /**
         * Formats we can write back out. AVIF and HEIC are readable-but-not-writable
         * (AVIF) or not supported at all (HEIC), so those fall back to PNG.
         */
        val ENCODABLE_OUTPUT = setOf("jpg", "jpeg", "png", "bmp", "tif", "tiff", "webp", "gif")
    }
}
