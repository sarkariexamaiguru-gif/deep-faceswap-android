package com.deepfaceswap.android

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Roop.
 *
 * Four destinations — Swap, Video, History, Settings — sharing one shell: a brand
 * header, a top work-mode switcher and a persistent bottom navigation.
 *
 * Built against the Roop specification. Every control maps to a capability the
 * engine actually has; anything unimplemented is shown disabled with a reason
 * rather than as a working-looking fake.
 */
class MainActivity : Activity() {

    // ------------------------------------------------------------------ state

    private lateinit var prefs: Prefs
    private lateinit var history: HistoryManager
    private val models by lazy { ModelManager(this) }
    private val io = Handler(Looper.getMainLooper())

    // Swap job
    private val sourceFiles = mutableListOf<File>()
    private var targetFile: File? = null
    private var faces: NativeBridge.FaceOptions? = null
    private val mappings = mutableListOf<Pair<Int, Int>>()
    private var activeSource = 0
    private var faceMode = 1 // 0 largest face, 1 every face, 2 manual
    private var qualityIndex = 1
    private var restoreOn = true
    private var swapperFile = ModelManager.INSWAPPER_FILE

    // Video job
    private val vSources = mutableListOf<File>()
    private var vTarget: File? = null
    private var vMute = false

    // History view state
    private var hFilter = "All"
    private var hQuery = ""
    private var hSort = "Newest first"
    private var hSelection = mutableSetOf<String>()
    private var hSelecting = false

    // Shell
    private lateinit var content: FrameLayout
    private var switcherRow: LinearLayout? = null
    private lateinit var navRow: LinearLayout
    private val screens = linkedMapOf<String, View>()
    private var current = "home"

    // Swap UI
    private lateinit var swapSource: Upload
    private lateinit var swapTarget: Upload
    private lateinit var swapTiles: LinearLayout
    private lateinit var swapCta: LinearLayout
    private lateinit var swapStatus: TextView
    private lateinit var swapBar: ProgressBar
    private lateinit var swapResult: LinearLayout
    private lateinit var mappingCard: LinearLayout
    private lateinit var mappingBody: LinearLayout

    // Video UI
    private lateinit var vidSource: Upload
    private lateinit var vidTarget: Upload
    private lateinit var vidTiles: LinearLayout
    private lateinit var vidCta: LinearLayout
    private lateinit var vidStatus: TextView
    private lateinit var vidBar: ProgressBar
    private lateinit var vidResult: LinearLayout

    // History UI
    private lateinit var historyList: LinearLayout
    private lateinit var historySearch: EditText
    private lateinit var historyChips: LinearLayout
    private lateinit var historyBulk: LinearLayout

    // Settings UI
    private lateinit var modelRow: LinearLayout
    private lateinit var downloadCard: LinearLayout
    private var activeDownload: String? = null
    /** Held so it can actually be unregistered in onDestroy. */
    private val swapListener: (SwapBus.State) -> Unit = { state -> io.post { onSwapState(state) } }
    private var lastProgress: ModelManager.Progress? = null
    private lateinit var qualitySegmentHost: LinearLayout
    private lateinit var qualityHint: TextView
    private lateinit var deviceRow: LinearLayout
    private lateinit var storageRow: LinearLayout

    private var polling = false
    private var lastStep = ""

    // ---------------------------------------------------------------- life

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        history = HistoryManager(this)

        qualityIndex = Prefs.QUALITY_ORDER.indexOf(prefs.quality).coerceAtLeast(0)
        restoreOn = prefs.restore
        swapperFile = prefs.modelFile
        vMute = prefs.muteAudio
        models.ensureDirs()
        models.copyEmapFromAssetsIfPresent()

        // Occlusion is on when the XSeg model is present. A 256x256 pass per
        // face is cheap next to the swap, and painting the new face over a hand
        // or a microphone is the failure people notice first.
        NativeBridge.setOcclusion(occlusionModelPath())

        // Visibility, on top of pixels: which landmarks are behind something.
        // Also on when the model is present, for the same reason.
        NativeBridge.setLandmarkModel(landmarkModelPath())

        // Interpolation, for fluid GIFs. On when the model is present.
        rifeReady = NativeBridge.setInterpolation(interpolationModelPath())

        setContentView(buildShell())
        SwapBus.addListener(swapListener)
        DebugLog.add("app", "started")

        screens["home"] = buildHomeScreen().also { content.addView(it) }
        screens["swap"] = buildSwapScreen().also { content.addView(it) }
        screens["video"] = buildVideoScreen().also { content.addView(it) }
        screens["history"] = buildHistoryScreen().also { content.addView(it) }
        screens["settings"] = buildSettingsScreen().also { content.addView(it) }
        screens["tools"] = buildToolsScreen().also { content.addView(it) }
        screens["models"] = buildModelsScreen().also { content.addView(it) }

        show("home")
    }

    override fun onDestroy() {
        super.onDestroy()
        SwapBus.removeListener(swapListener)
    }

    /**
     * FrameLayout shell: the header/switcher/content column fills the window and
     * the navigation is pinned to the bottom. A LinearLayout with a weighted
     * middle child left the bar floating mid-screen, so this is explicit.
     */
    private fun buildShell(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.canvas))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildHeader())
        // The work-mode switcher used to live here. It listed the same
        // destinations as the bottom bar - Swap, Video, History - with Tools
        // instead of Settings, so the app had two navigations that disagreed.
        // One bar, one list.
        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        column.addView(content)
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            // The bar floats over the shell, so without this the last control on
            // every screen sits underneath it - which is exactly where the
            // "Swap the face" button was.
            bottomMargin = dp(94)
        })
        root.addView(buildBottomNav(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(12)
        })
        return root
    }

    // --------------------------------------------------------------- shell

    /**
     * The LabPic banner.
     *
     * It used to be a flat row: a logo, a wordmark, a pill and three icon
     * buttons competing for the same line. It is one gradient card now, with the
     * mark and the name inside it and the actions reduced to two glass buttons.
     * The Pro crown is gone - there is no paid tier, so a crown button was an
     * advertisement for something that does not exist.
     */
    private fun buildHeader(): View {
        val wrap = FrameLayout(this).apply {
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_banner)
            elevation = dp(5).toFloat()
            setPadding(dp(15), dp(15), dp(11), dp(15))
        }

        banner.addView(FrameLayout(this).apply {
            background = getDrawable(R.drawable.bg_glass_tile)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            addView(RoopUi.iconView(this@MainActivity, R.drawable.ic_flask, 25,
                R.color.surface_solid).apply {
                layoutParams = FrameLayout.LayoutParams(dp(25), dp(25)).apply {
                    gravity = Gravity.CENTER
                }
            })
        })

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 13, 0, 0, 0)
        }
        brand.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 21f
            setTextColor(getColor(R.color.surface_solid))
            setTypeface(typeface, Typeface.BOLD)
        })
        val sub = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sub.addView(TextView(this).apply {
            text = getString(R.string.tagline)
            textSize = 11.5f
            setTextColor(0xCCFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // The Private pill used to sit here. A third button needs the width, and
        // the same promise is already on Home in full - "Every tool runs on this
        // phone. Nothing is uploaded." - so the pill was the one that could go.
        brand.addView(sub)
        banner.addView(brand, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        banner.addView(glassButton(R.drawable.ic_history, "History") { show("history") })
        banner.addView(glassButton(R.drawable.ic_settings, "Settings") { show("settings") })
        banner.addView(glassButton(R.drawable.ic_dots, "More options") { showOverflow() })

        wrap.addView(banner)
        return wrap
    }

    /** A translucent round button, for use on the banner. */
    private fun glassButton(iconRes: Int, description: String, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            background = getDrawable(R.drawable.bg_glass_circle)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginStart = dp(7)
            }
            isClickable = true
            contentDescription = description
            addView(RoopUi.iconView(this@MainActivity, iconRes, 19, R.color.surface_solid)
                .apply {
                    layoutParams = FrameLayout.LayoutParams(dp(19), dp(19)).apply {
                        gravity = Gravity.CENTER
                    }
                })
            setOnClickListener { onClick() }
            RoopUi.pressScale(this)
        }

    private fun iconButton(
        iconRes: Int,
        label: String,
        backgroundRes: Int = R.drawable.bg_card,
        onClick: () -> Unit,
    ): FrameLayout =
        FrameLayout(this).apply {
            background = getDrawable(backgroundRes)
            layoutParams = RoopUi.margin(this@MainActivity, dp(44), dp(44), 8, 0, 0, 0)
            isClickable = true
            contentDescription = label
            addView(RoopUi.iconView(this@MainActivity, iconRes, 20, R.color.ink).apply {
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                    gravity = Gravity.CENTER
                }
            })
            RoopUi.pressScale(this)
            setOnClickListener { onClick() }
        }

    private fun buildSwitcher(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = getDrawable(R.drawable.bg_switcher)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46), 20, 10, 20, 0)
        }
        val host = FrameLayout(this).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0)
        }
        switcherRow = row
        host.addView(row)
        // Tools is intentionally absent: there is no Tools screen yet, and the
        // specification forbids leaving a decorative tab inert.
        renderSwitcher()
        return host
    }

    private fun renderSwitcher() {
        val row = switcherRow ?: return
        row.removeAllViews()
        listOf("swap" to "Swap", "video" to "Video", "history" to "History", "tools" to "Tools")
            .forEachIndexed { index, (key, label) ->
                val active = key == current
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    background = if (active) getDrawable(R.drawable.bg_gradient_segment) else null
                    setPadding(dp(6), dp(9), dp(6), dp(9))
                    layoutParams = RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (index > 0) marginStart = dp(4) }
                    isClickable = true
                    contentDescription = label
                    addView(RoopUi.iconView(this@MainActivity, iconFor(key), 20,
                        if (active) R.color.surface_solid else R.color.ink_secondary))
                    addView(RoopUi.text(this@MainActivity, label, 14f,
                        if (active) R.color.surface_solid else R.color.ink_secondary, 1).apply {
                        layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0)
                    })
                    setOnClickListener { show(key) }
                })
            }
    }

    private fun buildBottomNav(): View {
        val host = FrameLayout(this)
        navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = getDrawable(R.drawable.bg_nav)
            elevation = dp(6).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                dp(70), 14, 0, 14, 0)
        }
        host.addView(navRow)
        renderNav()
        return host
    }

    private fun renderNav() {
        navRow.removeAllViews()
        listOf(
            // Four places, not seven. Swap and Video left the bar because they
            // are tools, and tools live on Home. Tools merged into Models - two
            // tabs were doing one job: managing what the app can run.
            "home" to ("Home" to R.drawable.ic_sparkle),
            "history" to ("Library" to R.drawable.ic_history),
            "models" to ("Models" to R.drawable.ic_cube),
            "settings" to ("Settings" to R.drawable.ic_settings),
        ).forEach { (key, spec) ->
            val (label, icon) = spec
            val active = key == current
            navRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = if (active) getDrawable(R.drawable.bg_pill_soft) else null
                layoutParams = RoopUi.weight(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                contentDescription = label
                addView(RoopUi.iconView(this@MainActivity, icon, 26,
                    if (active) R.color.blue else R.color.ink_secondary))
                addView(RoopUi.text(this@MainActivity, label, 11.5f,
                    if (active) R.color.blue else R.color.ink_secondary, if (active) 1 else 0)
                    .apply { layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 3, 0, 0) })
                setOnClickListener { show(key) }
            })
        }
    }

    private fun iconFor(key: String): Int = when (key) {
        "video" -> R.drawable.ic_video
        "history" -> R.drawable.ic_history
        "tools" -> R.drawable.ic_tools
        else -> R.drawable.ic_swap
    }

    private fun show(key: String) {
        current = key
        screens.forEach { (name, view) -> view.visibility = if (name == key) View.VISIBLE else View.GONE }
        renderSwitcher()
        renderNav()
        // Settings is not a peer of the work modes, so the switcher steps aside.
        switcherRow?.visibility = if (key == "settings") View.GONE else View.VISIBLE
        when (key) {
            "home" -> Unit
            "history" -> renderHistory()
            "settings" -> renderSettings()
            "swap" -> refreshSwap()
            "video" -> refreshVideo()
            "tools" -> Unit
            "models" -> Unit
        }
    }

    // ------------------------------------------------------ upload cards

    private class Upload(
        val card: LinearLayout,
        val zone: FrameLayout,
        val step: Int,
    )

    private fun uploadCard(
        step: Int,
        title: String,
        subtitle: String,
        stepIcon: Int,
        emptyIcon: Int,
        emptyLine: String,
        emptyHint: String,
        support: String? = null,
        actions: List<Pair<Int, Pair<String, () -> Unit>>>,
        onTap: () -> Unit,
    ): Upload {
        val card = RoopUi.card(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(FrameLayout(this).apply {
            background = getDrawable(R.drawable.bg_gradient_tile)
            layoutParams = RoopUi.lp(dp(34), dp(34))
            addView(RoopUi.iconView(this@MainActivity, stepIcon, 19, R.color.surface_solid).apply {
                layoutParams = FrameLayout.LayoutParams(dp(19), dp(19)).apply {
                    gravity = Gravity.CENTER
                }
            })
        })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0)
            addView(RoopUi.cardTitle(this@MainActivity, title))
            addView(RoopUi.meta(this@MainActivity, subtitle))
        }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { (icon, spec) ->
            val (label, onClick) = spec
            header.addView(iconButton(icon, label, onClick = onClick).apply {
                layoutParams = RoopUi.margin(this@MainActivity, dp(44), dp(44), 6, 0, 0, 0)
            })
        }
        card.addView(header)

        val zone = FrameLayout(this).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0)
        }
        val empty = RoopUi.dropZone(this, emptyIcon, emptyLine, emptyHint).apply {
            setOnClickListener { onTap() }
        }
        zone.addView(empty)
        card.addView(zone)
        if (support != null) {
            card.addView(RoopUi.text(this, support, 10.5f, R.color.ink_muted).apply {
                setPadding(dp(2), dp(8), 0, 0)
            })
        }
        return Upload(card, zone, step)
    }

    /** Swap the drop zone between its empty and selected presentations. */
    private fun renderUpload(
        upload: Upload,
        file: File?,
        emptyIcon: Int,
        emptyLine: String,
        emptyHint: String,
        onTap: () -> Unit,
        onRemove: () -> Unit,
    ) {
        upload.zone.removeAllViews()
        if (file == null) {
            upload.zone.addView(RoopUi.dropZone(this, emptyIcon, emptyLine, emptyHint).apply {
                setOnClickListener { onTap() }
            })
            return
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_card_inner)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        row.addView(ImageView(this).apply {
            layoutParams = RoopUi.lp(dp(62), dp(62))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = getDrawable(R.drawable.bg_thumb)
            clipToOutline = true
            loadThumb(file, this)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 11, 0, 8, 0)
            addView(RoopUi.text(this@MainActivity, file.name, 13.5f, R.color.ink, 1).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            addView(RoopUi.meta(this@MainActivity, describe(file)))
        }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconButton(R.drawable.ic_close, "Remove", onClick = onRemove).apply {
            layoutParams = RoopUi.lp(dp(36), dp(36))
        })
        upload.zone.addView(row)
    }

    // ------------------------------------------------------- swap screen

    private fun buildSwapScreen(): View {
        val col = pageColumn()

        col.addView(RoopUi.screenTitle(this, "Face Swap"))
        col.addView(RoopUi.body(this, "Simple. Private. Powerful.").apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 18)
        })

        swapSource = uploadCard(
            step = 1,
            stepIcon = R.drawable.ic_person,
            title = "Source Face",
            subtitle = "Face to copy FROM",
            emptyIcon = R.drawable.ic_person,
            emptyLine = "Tap to upload source face",
            emptyHint = "JPG \u00B7 PNG \u00B7 WebP",
            actions = listOf(
                R.drawable.ic_camera to ("Take a photo" to { capture(REQ_CAM_SOURCE) }),
                R.drawable.ic_image to ("Choose from gallery" to {
                    pickMedia(REQ_SOURCE, multiple = true, allowVideo = false)
                }),
            ),
            onTap = { pickMedia(REQ_SOURCE, multiple = true, allowVideo = false) },
        )
        col.addView(swapSource.card)

        swapTarget = uploadCard(
            step = 2,
            stepIcon = R.drawable.ic_image,
            title = "Target Image / Video",
            subtitle = "Swap the face ONTO",
            emptyIcon = R.drawable.ic_image,
            emptyLine = "Tap to upload target",
            emptyHint = "JPG \u00B7 PNG \u00B7 WebP \u00B7 GIF \u00B7 BMP \u00B7 TIFF \u00B7 AVIF\n" +
                "MP4 \u00B7 MOV \u00B7 MKV \u00B7 WebM",
            support = "Animated media is processed frame by frame",
            actions = listOf(
                R.drawable.ic_camera to ("Take a photo" to { capture(REQ_CAM_TARGET) }),
                R.drawable.ic_image to ("Choose from gallery" to {
                    pickMedia(REQ_TARGET, multiple = false, allowVideo = true)
                }),
            ),
            onTap = { pickMedia(REQ_TARGET, multiple = false, allowVideo = true) },
        )
        col.addView(swapTarget.card)

        mappingCard = RoopUi.card(this).apply { visibility = View.GONE }
        mappingCard.addView(RoopUi.sectionHeader(
            this, R.drawable.ic_person, "Face Mapping", "Choose which face goes where",
        ))
        mappingBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0)
        }
        mappingCard.addView(mappingBody)
        col.addView(mappingCard)

        val tilesLabel = RoopUi.text(this, "Options", 13f, R.color.ink_muted, 1).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 4, 0, 8)
        }
        col.addView(tilesLabel)
        swapTiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(RoopUi.hScroll(this, swapTiles))

        swapCta = RoopUi.primaryButton(this, "Start Swap", R.drawable.ic_swap)
        swapCta.setOnClickListener { runSwap() }
        col.addView(swapCta)

        swapStatus = RoopUi.text(this, "", 13f, R.color.ink_secondary).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2, 10, 2, 0)
        }
        col.addView(swapStatus)
        swapBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.blue))
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.border_cool))
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6), 2, 10, 2, 0)
        }
        col.addView(swapBar)

        // Only claimed because every stage really is local.
        col.addView(RoopUi.privacyHint(
            this, "All processing happens on your device. Your images never leave your phone.",
        ))

        swapResult = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0)
        }
        col.addView(swapResult)

        return withBlooms(scrollPage(col, transparent = true))
    }

    private fun refreshSwap() {
        renderUpload(swapSource, sourceFiles.firstOrNull(), R.drawable.ic_person,
            "Tap to upload source face", "JPG \u00B7 PNG \u00B7 WebP",
            { pickMedia(REQ_SOURCE, true, false) }) {
            sourceFiles.clear(); faces = null; mappings.clear(); refreshSwap()
        }
        renderUpload(swapTarget, targetFile, R.drawable.ic_image,
            "Tap to upload target", "JPG \u00B7 PNG \u00B7 WebP \u00B7 GIF \u00B7 MP4 \u00B7 MOV",
            { pickMedia(REQ_TARGET, false, true) }) {
            targetFile = null; faces = null; mappings.clear(); refreshSwap()
        }
        swapSource.card.findViewWithTag<View>("facesline")?.let { swapSource.card.removeView(it) }
        val found = faces
        if (found != null && found.source.size > 1) {
            swapSource.card.addView(RoopUi.text(this,
                "${found.source.size} faces found \u00B7 Choose face", 12f, R.color.blue, 1).apply {
                tag = "facesline"
                setPadding(dp(2), dp(8), 0, 0)
                isClickable = true
                setOnClickListener { faceMode = 2; refreshSwap() }
            })
        }
        renderSwapTiles()
        renderMapping()
        RoopUi.setPrimaryEnabled(swapCta, sourceFiles.isNotEmpty() && targetFile != null)
    }

    private fun renderSwapTiles() {
        swapTiles.removeAllViews()
        swapTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_sliders, "Quality", Prefs.QUALITY_ORDER[qualityIndex],
        ).apply {
            setOnClickListener {
                showSheet(
                    "Quality",
                    Prefs.QUALITY_ORDER,
                    qualityIndex,
                    listOf(
                        "Fastest, smallest output.",
                        "Recommended balance of quality and time.",
                        "More detail, more time.",
                        "Best quality. Slowest, and not for video.",
                    ),
                ) { pick -> qualityIndex = pick; refreshSwap() }
            }
        })
        swapTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_scan, "Face Blend", "Not available", enabled = false,
        ))
        swapTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_palette, "Color Match", if (prefs.colorMatch) "On" else "Off",
        ).apply {
            setOnClickListener {
                showSheet("Color Match", listOf("Off", "On"), if (prefs.colorMatch) 1 else 0,
                    listOf("Leave the swapped face's own colour.",
                        "Match it to the target's skin tone and light.")) { pick ->
                    prefs.colorMatch = pick == 1; refreshSwap()
                }
            }
        })
        swapTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_hd, "Restore Details", if (restoreOn) "On" else "Off",
            enabled = models.enhancerInstalled,
        ).apply {
            if (models.enhancerInstalled) setOnClickListener {
                showSheet("Restore Details", listOf("Off", "On"), if (restoreOn) 1 else 0,
                    listOf("Keep the raw swap.", "Recover fine detail with the restore model.")) { pick ->
                    restoreOn = pick == 1; refreshSwap()
                }
            }
        })
        swapTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_person, "Faces",
            when (faceMode) { 0 -> "Largest"; 1 -> "All faces"; else -> "Manual" },
        ).apply {
            setOnClickListener {
                showSheet("Faces to swap", listOf("Largest face", "Every face", "Choose manually"),
                    faceMode,
                    listOf("Swap only the most prominent face.",
                        "Swap every detected face.",
                        "Pick which face goes where yourself.")) { pick ->
                    faceMode = pick
                    if (pick != 2) { mappings.clear() }
                    refreshSwap()
                }
            }
        })
    }

    private fun renderMapping() {
        mappingCard.visibility = if (faceMode == 2) View.VISIBLE else View.GONE
        mappingBody.removeAllViews()
        if (faceMode != 2) return

        val detected = faces
        mappingBody.addView(RoopUi.text(this, when {
            detected == null -> "Detect faces to begin mapping."
            detected.source.isEmpty() || detected.target.isEmpty() ->
                "Found ${detected.source.size} source and ${detected.target.size} target faces."
            else -> "${detected.source.size} source \u00B7 ${detected.target.size} target \u00B7 " +
                "active source #$activeSource \u00B7 pairs: " +
                if (mappings.isEmpty()) "none" else mappings.joinToString(", ") { "${it.first}\u2192${it.second}" }
        }, 13f, R.color.ink_secondary))

        val detect = RoopUi.text(this, "Detect faces", 14f, R.color.blue, 1).apply {
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { detectFaces() }
        }
        mappingBody.addView(detect)

        if (detected == null) return
        mappingBody.addView(RoopUi.meta(this, "Source faces \u2014 tap to select the active one"))
        mappingBody.addView(faceStrip(detected.source, isSource = true))
        mappingBody.addView(RoopUi.meta(this, "Target faces \u2014 tap to pair with the active source"))
        mappingBody.addView(faceStrip(detected.target, isSource = false))
    }

    private fun faceStrip(list: List<NativeBridge.FaceOption>, isSource: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        list.forEach { face ->
            val chosen = isSource && face.index == activeSource
            val paired = !isSource && mappings.any { it.first == activeSource && it.second == face.index }
            row.addView(FrameLayout(this).apply {
                layoutParams = RoopUi.margin(this@MainActivity, dp(62), dp(62), 0, 6, 8, 6)
                background = getDrawable(R.drawable.bg_thumb)
                clipToOutline = true
                alpha = if (chosen) 1f else 0.72f
                contentDescription = if (isSource) "Source face ${face.index}" else "Target face ${face.index}"
                isClickable = true
                setOnClickListener {
                    if (isSource) {
                        activeSource = face.index
                    } else {
                        val pair = activeSource to face.index
                        if (mappings.contains(pair)) mappings.remove(pair) else mappings.add(pair)
                    }
                    renderMapping()
                }
                addView(ImageView(this@MainActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    BitmapFactory.decodeFile(face.cropPath)?.let { setImageBitmap(it) }
                })
                if (chosen || paired) addView(RoopUi.pill(this@MainActivity,
                    if (paired) "PAIR" else "ACTIVE", R.drawable.bg_badge, R.color.surface_solid)
                    .apply { layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = Gravity.BOTTOM or Gravity.START; marginStart = dp(4); bottomMargin = dp(4) } })
            })
        }
        return RoopUi.hScroll(this, row)
    }

    // ------------------------------------------------------ video screen

    private fun buildVideoScreen(): View {
        val col = pageColumn()
        col.addView(RoopUi.screenTitle(this, "Video Face Swap"))
        col.addView(RoopUi.body(this, "Swap faces in videos, on your device.").apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 18)
        })

        vidSource = uploadCard(
            step = 1, stepIcon = R.drawable.ic_person,
            title = "Source Face", subtitle = "Face to copy FROM",
            emptyIcon = R.drawable.ic_person, emptyLine = "Tap to upload source face",
            emptyHint = "JPG \u00B7 PNG \u00B7 WebP",
            actions = listOf(
                R.drawable.ic_camera to ("Take a photo" to { capture(REQ_CAM_VSOURCE) }),
                R.drawable.ic_image to ("Choose from gallery" to {
                    pickMedia(REQ_VSOURCE, multiple = true, allowVideo = false)
                }),
            ),
            onTap = { pickMedia(REQ_VSOURCE, multiple = true, allowVideo = false) },
        )
        col.addView(vidSource.card)

        vidTarget = uploadCard(
            step = 2, stepIcon = R.drawable.ic_video, title = "Video", subtitle = "Swap the face ONTO",
            emptyIcon = R.drawable.ic_video, emptyLine = "Tap to upload video",
            emptyHint = "MP4 \u00B7 MOV \u00B7 MKV \u00B7 WebM",
            actions = listOf(
                R.drawable.ic_folder to ("Browse files" to {
                    pickMedia(REQ_VTARGET, multiple = false, allowVideo = true)
                }),
                R.drawable.ic_image to ("Choose from gallery" to {
                    pickMedia(REQ_VTARGET, multiple = false, allowVideo = true)
                }),
            ),
            onTap = { pickMedia(REQ_VTARGET, multiple = false, allowVideo = true) },
        )
        col.addView(vidTarget.card)

        col.addView(RoopUi.text(this, "Options", 13f, R.color.ink_muted, 1).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 4, 0, 8)
        })
        vidTiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(RoopUi.hScroll(this, vidTiles))

        // Preview before committing. A wrong setting currently costs a whole
        // encode to discover; this makes finding out cost a few seconds.
        val previewCta = android.widget.Button(this).apply {
            text = "Preview 3 frames first"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { openPreview() }
        }
        col.addView(
            previewCta,
            RoopUi.margin(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8),
        )

        vidCta = RoopUi.primaryButton(this, "Generate Video", R.drawable.ic_play)
        vidCta.setOnClickListener { runVideo() }
        col.addView(vidCta)

        vidStatus = RoopUi.text(this, "", 13f, R.color.ink_secondary).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2, 10, 2, 0)
        }
        col.addView(vidStatus)
        vidBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.blue))
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.border_cool))
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6), 2, 10, 2, 0)
        }
        col.addView(vidBar)

        col.addView(RoopUi.privacyHint(
            this,
            "All processing happens on your device. Your videos never leave your phone.",
        ))

        vidResult = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0)
        }
        col.addView(vidResult)
        return scrollPage(col)
    }

    private fun refreshVideo() {
        renderUpload(vidSource, vSources.firstOrNull(), R.drawable.ic_person,
            "Tap to upload source face", "JPG \u00B7 PNG \u00B7 WebP",
            { pickMedia(REQ_VSOURCE, true, false) }) {
            vSources.clear(); refreshVideo()
        }
        renderUpload(vidTarget, vTarget, R.drawable.ic_video,
            "Tap to upload video", "MP4 \u00B7 MOV \u00B7 MKV \u00B7 WebM",
            { pickMedia(REQ_VTARGET, false, true) }) {
            vTarget = null; refreshVideo()
        }
        renderVideoTiles()
        RoopUi.setPrimaryEnabled(vidCta, vSources.isNotEmpty() && vTarget != null)
    }

    private fun renderVideoTiles() {
        vidTiles.removeAllViews()
        vidTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_sliders, "Quality", Prefs.QUALITY_ORDER[qualityIndex],
        ).apply {
            setOnClickListener {
                showSheet("Quality", Prefs.QUALITY_ORDER, qualityIndex,
                    listOf("Fastest.", "Recommended.", "More detail, more time.",
                        "Very slow for video \u2014 long clips may be refused.")) { pick ->
                    qualityIndex = pick; refreshVideo()
                }
            }
        })
        vidTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_scan, "Face Blend", "Not available", enabled = false,
        ))
        vidTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_shield, "Similarity", "Not available", enabled = false,
        ))
        vidTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_palette, "Color Match", if (prefs.colorMatch) "On" else "Off",
        ).apply {
            setOnClickListener {
                showSheet("Color Match", listOf("Off", "On"), if (prefs.colorMatch) 1 else 0,
                    listOf("Leave the swapped face's own colour.",
                        "Match it to the target's skin tone and light.")) { pick ->
                    prefs.colorMatch = pick == 1; refreshVideo()
                }
            }
        })
        vidTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_volume, "Audio", if (vMute) "Mute" else "Keep original",
        ).apply {
            setOnClickListener {
                showSheet("Audio", listOf("Keep original", "Mute"), if (vMute) 1 else 0,
                    listOf("Copy the source audio track through.",
                        "Produce a silent video.")) { pick ->
                    vMute = pick == 1; prefs.muteAudio = vMute; refreshVideo()
                }
            }
        })
        // Clones of the chosen model, working the frames in parallel. Each clone
        // is a full copy of the model in memory, so the count is a real trade.
        vidTiles.addView(RoopUi.optionTile(
            this, R.drawable.ic_cube, "Clones",
            if (prefs.clones > 1) "${prefs.clones}\u00D7" else "Off",
        ).apply {
            setOnClickListener {
                val cores = Runtime.getRuntime().availableProcessors()
                val info = android.app.ActivityManager.MemoryInfo()
                getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
                val ramGb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
                val labels = (1..MAX_MODEL_CLONES).map {
                    if (it == 1) "Off (1 clone)" else "$it clones"
                }
                val notes = (1..MAX_MODEL_CLONES).map { n ->
                    if (n == 1) {
                        "One session, all $cores cores. Least memory."
                    } else {
                        val perClone = (cores / n).coerceAtLeast(1)
                        val ram = n * 0.45
                        "$perClone thread${if (perClone == 1) "" else "s"} per clone \u00B7 " +
                            "about ${String.format(java.util.Locale.US, "%.1f", ram)} GB of models"
                    }
                }
                showSheet(
                    "Model clones \u00B7 ${cores} cores, ${String.format(java.util.Locale.US, "%.0f", ramGb)} GB RAM",
                    labels,
                    prefs.clones - 1,
                    notes,
                ) { pick ->
                    prefs.clones = pick + 1; refreshVideo()
                }
            }
        })
    }

    // ------------------------------------------------------ tools screen

    private fun buildToolsScreen(): View {
        val col = pageColumn()
        col.addView(RoopUi.screenTitle(this, "Tools"))
        col.addView(RoopUi.body(this, "Extras that work on this device.").apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 18)
        })
        col.addView(toolCard(R.drawable.ic_hd, "Edit a photo",
            "Crop, rotate, light, colour — instant, no models") {
            startActivity(Intent(this@MainActivity, EditActivity::class.java))
        })
        col.addView(toolCard(R.drawable.ic_person, "Face Mapping",
            "Choose which face goes where") {
            faceMode = 2
            show("swap")
        })
        col.addView(toolCard(R.drawable.ic_cube, "Model Diagnostics",
            "What is installed, its size and licence") { showModelDiagnostics() })
        col.addView(toolCard(R.drawable.ic_folder, "Storage & Cache",
            "Models, history and temporary files") { show("settings") })
        col.addView(toolCard(R.drawable.ic_info, "Help & Guide",
            "Source vs target, formats, limits") { showHelp() })
        return scrollPage(col)
    }

    /**
     * Home: the tools, not one of them.
     *
     * The app used to open on Face Swap, which made it a face swap app with
     * extras bolted on - the name and the logo changed but the shape never did.
     * It opens on everything it can do now: the finished tools first, and the
     * rest labelled honestly rather than hidden.
     */
    private fun buildHomeScreen(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(RoopUi.screenTitle(this, "What are we making?"))
        col.addView(RoopUi.body(this, "Every tool runs on this phone. Nothing is uploaded.")
            .apply {
                layoutParams = RoopUi.margin(this@MainActivity,
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    0, 4, 0, 4)
            })

        col.addView(homeSection("Ready now"))
        col.addView(homeRow(
            homeTile(R.drawable.ic_sliders, "Edit a photo", "Crop, light, colour, sharpen", true) {
                startActivity(Intent(this@MainActivity, EditActivity::class.java))
            },
            homeTile(R.drawable.ic_swap, "Face Swap", "One face onto another", true) {
                show("swap")
            },
        ))
        col.addView(homeRow(
            homeTile(R.drawable.ic_video, "Swap a video", "Frame by frame, audio kept", true) {
                show("video")
            },
            null,
        ))

        col.addView(homeSection("Fix and sharpen"))
        col.addView(homeRow(
            homeTile(R.drawable.ic_hd, "Upscale", "4x, sharper edges", true) {
                startActivity(Intent(this@MainActivity, UpscaleActivity::class.java))
            },
            homeTile(R.drawable.ic_sparkle, "Remove blur", "Soft and motion-blurred shots", false) {
                notYet("Remove blur")
            },
        ))
        col.addView(homeRow(
            homeTile(R.drawable.ic_filter, "Denoise", "Grain and phone noise", true) {
                startActivity(
                    ToolActivity.intent(
                        this@MainActivity, "denoise", "Denoise", "Remove grain and sensor noise.",
                        ModelManager.SCUNET_FILE, 1,
                    ),
                )
            },
            homeTile(R.drawable.ic_chip, "De-JPEG", "Erase JPEG blocking", true) {
                startActivity(
                    ToolActivity.intent(
                        this@MainActivity, "dejpeg", "De-JPEG", "Erase JPEG blocking and mush.",
                        ModelManager.OMNI_FILE, 1,
                    ),
                )
            },
        ))
        col.addView(homeRow(
            homeTile(R.drawable.ic_star, "Sharpen", "Crisper edges", false) { notYet("Sharpen") },
            homeTile(R.drawable.ic_heart, "Restore", "Old photos, faces and detail", false) {
                notYet("Restore")
            },
        ))

        col.addView(homeSection("Cut and erase"))
        col.addView(homeRow(
            homeTile(R.drawable.ic_person, "Cut out", "Drop the background", true) {
                startActivity(
                    ToolActivity.intent(
                        this@MainActivity, "cutout", "Cut out",
                        "Drop the background and keep the subject.",
                        ModelManager.BIREFNET_FILE, 1,
                    ),
                )
            },
            homeTile(R.drawable.ic_image, "Swap background", "Put the subject elsewhere", false) {
                notYet("Swap background")
            },
        ))
        col.addView(homeRow(
            homeTile(R.drawable.ic_trash, "Erase", "Paint it out, it fills in", false) {
                notYet("Erase")
            },
            homeTile(R.drawable.ic_scan, "Select", "Tap an object to mask it", false) {
                notYet("Select")
            },
        ))

        col.addView(homeSection("Colour"))
        col.addView(homeRow(
            homeTile(R.drawable.ic_palette, "Colourise", "Black and white to colour", false) {
                notYet("Colourise")
            },
            homeTile(R.drawable.ic_chip, "Auto colour", "Fix a cast, lift a dull shot", false) {
                notYet("Auto colour")
            },
        ))
        col.addView(homeRow(
            homeTile(R.drawable.ic_shield, "Colour match", "Blend a face into its frame", true) {
                show("swap")
            },
            null,
        ))

        col.addView(homeSection("Change"))
        col.addView(homeRow(
            homeTile(R.drawable.ic_sort, "Crop and rotate", "Frame it, level it", true) {
                startActivity(Intent(this@MainActivity, EditActivity::class.java))
            },
            homeTile(R.drawable.ic_cube, "Resize", "Exact pixel dimensions", true) {
                startActivity(Intent(this@MainActivity, EditActivity::class.java))
            },
        ))
        col.addView(homeRow(
            homeTile(R.drawable.ic_filter, "Filters", "13 one-tap looks", true) {
                startActivity(Intent(this@MainActivity, EditActivity::class.java))
            },
            homeTile(R.drawable.ic_sort, "Straighten", "Level a tilted horizon", true) {
                startActivity(Intent(this@MainActivity, EditActivity::class.java))
            },
        ))

        col.addView(homeSection("People"))
        col.addView(homeRow(
            homeTile(R.drawable.ic_person, "Skin", "Smooth tone, keep texture", false) {
                notYet("Skin")
            },
            homeTile(R.drawable.ic_scan, "Eyes", "Brighten and sharpen", false) { notYet("Eyes") },
        ))
        col.addView(homeRow(
            homeTile(R.drawable.ic_star, "Teeth", "Whiten, without going blue", false) {
                notYet("Teeth")
            },
            homeTile(R.drawable.ic_sliders, "Reshape", "Jaw, nose, waist, height", false) {
                notYet("Reshape")
            },
        ))

        col.addView(homeSection("Video"))
        col.addView(homeRow(
            homeTile(R.drawable.ic_hd, "Video upscale", "Upscale a whole clip", false) {
                notYet("Video upscale")
            },
            homeTile(R.drawable.ic_video, "Video background", "Cut or swap a clip's backdrop", false) {
                notYet("Video background")
            },
        ))
        col.addView(homeRow(
            homeTile(R.drawable.ic_gif, "GIF maker", "Turn frames into an animation", false) {
                notYet("GIF maker")
            },
            null,
        ))

        return scrollPage(col)
    }

    /**
     * Models: everything the app can run, in one list.
     *
     * Until now the only way to see them was a diagnostics sheet behind the
     * Tools tab. This is the list itself - what is installed, what is not, what
     * each costs in space, and the licence it carries.
     */
    private fun buildModelsScreen(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(RoopUi.screenTitle(this, "Models"))
        col.addView(RoopUi.body(this, "Everything the app can run. Each shows its size and its " +
            "licence before you take it.").apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 4)
        })

        val all = models.models()
        val installed = all.filter { it.installed }
        val available = all.filter { !it.installed }

        col.addView(homeSection("Installed (${installed.size})"))
        installed.forEach { col.addView(modelRow(it)) }

        col.addView(homeSection("Available (${available.size})"))
        if (available.isEmpty()) {
            col.addView(RoopUi.body(this, "Everything on this list is installed."))
        } else {
            available.forEach { col.addView(modelRow(it)) }
        }

        return scrollPage(col)
    }

    /** One model. Tapping a row that is not installed fetches it. */
    private fun modelRow(model: ModelManager.Model): View {
        val card = RoopUi.card(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(RoopUi.softTile(this, R.drawable.ic_cube,
            if (model.installed) R.color.blue else R.color.ink_muted))

        // Not named `text`: inside the TextView's apply block below, a local called
        // text shadows the property and the assignment silently targets the wrong
        // thing.
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 11, 0, 0, 0)
        }
        labels.addView(RoopUi.cardTitle(this, model.title))
        val meta = RoopUi.meta(this, "${model.approxSize} · ${model.licence}")
        labels.addView(meta)
        row.addView(labels, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val action = TextView(this).apply {
            text = if (model.installed) "Installed" else "Get"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(if (model.installed) R.color.success else R.color.blue))
            background = getDrawable(
                if (model.installed) R.drawable.bg_chip_ready else R.drawable.bg_chip_coming,
            )
            setPadding(dp(11), dp(5), dp(11), dp(5))
        }
        row.addView(action)
        card.addView(row)

        if (!model.installed) {
            card.isClickable = true
            card.contentDescription = "Download ${model.title}"
            card.setOnClickListener { fetchModel(model, meta, action) }
            RoopUi.pressScale(card)
        }
        return card
    }

    private fun fetchModel(
        model: ModelManager.Model,
        meta: TextView,
        action: TextView,
    ) {
        action.text = "…"
        Thread {
            runCatching {
                models.download(model) { progress ->
                    runOnUiThread {
                        meta.text = "${progress.label} · ${progress.text}"
                        action.text = when {
                            progress.percent >= 0 -> "${progress.percent}%"
                            progress.resuming -> "Resuming"
                            else -> "…"
                        }
                    }
                }
            }
            runOnUiThread {
                if (model.installed) {
                    meta.text = "${model.approxSize} · ${model.licence}"
                    action.text = "Installed"
                } else {
                    action.text = "Retry"
                }
            }
        }.start()
    }

    private fun homeSection(title: String): View =
        RoopUi.text(this, title.uppercase(), 11.5f, R.color.blue, 2).apply {
            letterSpacing = 0.1f
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 20, 0, 9)
        }

    private fun homeRow(left: View, right: View?): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // WRAP_CONTENT, not MATCH_PARENT. A child asking for match_parent height
        // inside a wrap_content row measures against a height that is not decided
        // yet, and collapses to zero - which is what made every tile on Home
        // vanish while the section headings stayed. The even heights come from
        // minimumHeight on the cards instead, which does not depend on the parent.
        row.addView(left, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(13), 1)
        })
        if (right != null) {
            row.addView(right, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            row.addView(android.view.View(this),
                RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    private fun homeTile(iconRes: Int, title: String, subtitle: String, ready: Boolean,
                         onClick: () -> Unit): View {
        val card = RoopUi.card(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(15))
            // Even heights across a row, so the grid reads as a grid.
            minimumHeight = dp(158)
        }

        // Ready tools get the gradient tile, so what works is obvious at a
        // glance without reading a single label.
        card.addView(
            if (ready) RoopUi.gradientTile(this, iconRes)
            else RoopUi.softTile(this, iconRes, R.color.ink_muted),
        )
        card.addView(RoopUi.cardTitle(this, title).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0)
        })
        card.addView(RoopUi.secondary(this, subtitle).apply {
            minLines = 2
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (ready) "Ready" else "Coming"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(if (ready) R.color.success else R.color.ink_muted))
            background = getDrawable(
                if (ready) R.drawable.bg_chip_ready else R.drawable.bg_chip_coming,
            )
            setPadding(dp(11), dp(4), dp(11), dp(4))
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0)
        })
        card.isClickable = true
        card.contentDescription = title
        card.setOnClickListener { onClick() }
        RoopUi.pressScale(card)
        return card
    }

    /** Say plainly that a tool is designed but not built, rather than pretend. */
    private fun notYet(name: String) {
        showSheet(
            name,
            listOf("Not built yet"),
            -1,
            listOf("The tool is designed and its model is chosen, but there is no screen for it yet. " +
                "The ones marked Ready work today."),
        ) { }
    }

    private fun toolCard(iconRes: Int, title: String, subtitle: String,
                         onClick: () -> Unit): View {
        val card = RoopUi.card(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(RoopUi.softTile(this, iconRes, R.color.blue))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 11, 0, 0, 0)
            addView(RoopUi.cardTitle(this@MainActivity, title))
            addView(RoopUi.meta(this@MainActivity, subtitle))
        }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(RoopUi.iconView(this, R.drawable.ic_chevron, 16, R.color.ink_muted))
        card.addView(row)
        card.isClickable = true
        card.contentDescription = title
        card.setOnClickListener { onClick() }
        RoopUi.pressScale(card)
        return card
    }

    /**
     * The quality report for the job that just finished.
     *
     * Three numbers, and they are the point: every quality claim this app makes
     * can now be checked rather than assumed. Shown as a sheet rather than
     * buried, because a number nobody sees cannot guide anything.
     */
    private fun showQualityReport() {
        val raw = NativeBridge.qualityReport()
        val parsed = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return

        fun num(key: String): Double? =
            if (parsed.isNull(key)) null else parsed.optDouble(key).takeIf { !it.isNaN() }

        val faces = parsed.optInt("facesSwapped", 0)
        if (faces == 0) return

        val lines = mutableListOf<String>()

        num("idSimilarity")?.let { value ->
            // Cosine between the source face and the result. Above about 0.5
            // reads as the same person to ArcFace; below it, it does not.
            val verdict = when {
                value >= 0.6 -> "clearly the same person"
                value >= 0.45 -> "recognisably the same person"
                value >= 0.3 -> "loosely similar"
                else -> "not reading as the same person"
            }
            lines.add("Identity  ${"%.2f".format(value)}  -  $verdict")
        }

        val before = num("sharpnessBefore")
        val after = num("sharpnessAfter")
        if (before != null && after != null && before > 1.0) {
            val change = (after / before - 1.0) * 100.0
            val verdict = when {
                change > 12 -> "crisper than the original"
                change > -12 -> "about as sharp as the original"
                else -> "softer than the original"
            }
            lines.add("Sharpness  ${"%.0f".format(change)}%  -  $verdict")
        }

        num("occlusionCoverage")?.let { value ->
            if (value > 0.005) {
                lines.add(
                    "Occlusion  ${"%.0f".format(value * 100)}% of the face box  -  " +
                        "left alone because something was in front",
                )
            } else {
                lines.add("Occlusion  nothing in front of the face")
            }
        }

        // A clip is judged as a whole, not by whichever frame finished last. A
        // mean on its own hides the failures, so the weakest frame is named.
        val timeline = runCatching { org.json.JSONObject(NativeBridge.qualityTimeline()) }
            .getOrNull()
        val frames = timeline?.optInt("count", 0) ?: 0
        if (frames > 1) {
            val mean = if (timeline!!.isNull("meanId")) null else timeline.optDouble("meanId")
            val weakestFrame = timeline.optInt("weakestFrame", -1)
            val weakestId = if (timeline.isNull("weakestId")) null
                else timeline.optDouble("weakestId")
            if (mean != null) {
                var summary = "Across $frames frames, identity averages " +
                    "${"%.2f".format(mean)}"
                if (weakestFrame >= 0 && weakestId != null) {
                    summary += ", weakest ${"%.2f".format(weakestId)} at frame " +
                        "${weakestFrame + 1}"
                }
                lines.add(summary)
            }
        }

        if (lines.isEmpty()) return
        showSheet("Quality report", lines, -1, null) { }
    }

    /**
     * What is wrong with a source photo, if anything.
     *
     * Two things are worth catching before a job starts, because both are cheap
     * to see and expensive to discover after an encode: a photo too small to
     * hold a face, and a soft one. The thresholds are rough - they are there to
     * catch the obvious cases, not to grade a photograph.
     */
    private fun sourceAdvice(files: List<File>): String? {
        val file = files.firstOrNull() ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null

        val shortest = minOf(bitmap.width, bitmap.height)
        if (shortest < 256) {
            return "This source is ${bitmap.width} x ${bitmap.height}. A face needs roughly " +
                "256 px on the short side before the swapper has anything to work with."
        }

        val sharpness = laplacianVariance(bitmap)
        if (sharpness < 40.0) {
            return "This source looks soft. A sharper photo of the same face swaps better, " +
                "and the quality report will show it."
        }
        return null
    }

    /**
     * Laplacian variance over a downscaled copy - the same measure the quality
     * report uses, so the two numbers mean the same thing.
     */
    private fun laplacianVariance(bitmap: Bitmap): Double {
        val longest = maxOf(bitmap.width, bitmap.height)
        val small = if (longest > 512) {
            val scale = 512.0 / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val w = small.width
        val h = small.height
        if (w < 3 || h < 3) return 0.0

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var acc = 0.0
                for (channel in 0..2) {
                    val shift = channel * 8
                    fun value(index: Int): Double =
                        ((pixels[index] shr shift) and 0xFF).toDouble()
                    acc += value((y - 1) * w + x) + value((y + 1) * w + x) +
                        value(y * w + (x - 1)) + value(y * w + (x + 1)) -
                        4.0 * value(y * w + x)
                }
                val response = acc / 3.0
                sum += response
                sumSquares += response * response
                count++
            }
        }
        if (count < 2) return 0.0
        val mean = sum / count
        return (sumSquares / count) - mean * mean
    }

    /**
     * Free and total RAM, in megabytes.
     *
     * The number that matters for model loading. The Java heap is a separate and
     * much smaller budget, and planning against it was a mistake.
     */
    private fun deviceMemory(): Pair<Long, Long> = runCatching {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        (info.availMem / 1_048_576) to (info.totalMem / 1_048_576)
    }.getOrDefault(0L to 0L)

    /**
     * The debug log: what was asked for, what was chosen, and where it stopped.
     */
    private fun showDebugLog() {
        // Not called `text`: inside the apply blocks below that name would
        // shadow the TextView property and the assignment would target the
        // wrong object - the same trap as the model rows.
        val report = DebugLog.environment(this) + "\n\n" + DebugLog.dump()
        val sheet = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "Debug log"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0E1424"))
        })
        box.addView(TextView(this).apply {
            text = report
            textSize = 11f
            setTextColor(Color.parseColor("#4E5C7C"))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, dp(10))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, onClick: () -> Unit) =
            android.widget.Button(this).apply {
                text = label
                textSize = 12.5f
                isAllCaps = false
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
            }
        actions.addView(action("Copy") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("LabPic debug log", report),
            )
            toast("Copied")
        })
        actions.addView(action("Clear") {
            DebugLog.clear()
            sheet.dismiss()
            toast("Log cleared")
        })
        actions.addView(action("Close") { sheet.dismiss() })
        box.addView(actions)
        sheet.setContentView(
            android.widget.ScrollView(this).apply { addView(box) },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        sheet.show()
    }

    /**
     * Load every model the pipeline will actually use, and say what happened.
     *
     * A swap failing gives one sentence and no way to tell which of six files
     * was responsible. This loads each one and reports a name, a signature or a
     * reason - so a failure stops being a symptom.
     *
     * It runs off the UI thread because loading a 300 MB model is seconds, and
     * it deliberately checks what the pipeline WILL use rather than what is
     * installed: with the small pack preferred, the interesting question is
     * which file that resolution landed on.
     */
    private fun runHealthCheck() {
        toast("Checking models\u2026")
        Thread {
            val checks = mutableListOf<Pair<String, String>>()
            checks.add("Face detector" to models.detectorPath)
            checks.add("Face recogniser" to models.recognizerPath)
            checks.add("Face landmarker" to models.landmarkPath)
            checks.add("Swapper" to models.swapperPath(swapperFile))
            if (models.enhancerInstalled) {
                checks.add("Face restorer" to models.enhancerPath)
            }
            val occluder = occlusionModelPath()
            if (occluder.isNotEmpty()) checks.add("Occlusion mask" to occluder)
            val landmarkModel = landmarkModelPath()
            if (landmarkModel.isNotEmpty()) checks.add("Visibility" to landmarkModel)
            val interpolator = interpolationModelPath()
            if (interpolator.isNotEmpty()) checks.add("Interpolation" to interpolator)

            val lines = mutableListOf<String>()
            var failures = 0
            checks.forEach { (role, path) ->
                val name = File(path).name
                val (ok, detail) = NativeBridge.checkModel(path)
                if (ok) {
                    lines.add("$role \u00b7 $name\n   OK \u00b7 $detail")
                } else {
                    failures++
                    lines.add("$role \u00b7 $name\n   FAILED \u00b7 $detail")
                }
            }

            val header = if (failures == 0) {
                "All ${checks.size} models loaded."
            } else {
                "$failures of ${checks.size} models could not be loaded."
            }
            lines.add(0, header)

            runOnUiThread {
                showSheet("Model health", lines, -1, null) { }
            }
        }.start()
    }

    /** The RIFE interpolation model if installed, otherwise an empty path. */
    private fun interpolationModelPath(): String =
        models.models()
            .firstOrNull { it.fileName == ModelManager.RIFE_FILE }
            ?.takeIf { it.installed }
            ?.file
            ?.absolutePath
            ?: ""

    /** The HRFFA visibility model if installed, otherwise an empty path. */
    private fun landmarkModelPath(): String =
        models.models()
            .firstOrNull { it.fileName == ModelManager.LANDMARK_MODEL_FILE }
            ?.takeIf { it.installed }
            ?.file
            ?.absolutePath
            ?: ""

    /** The XSeg model if it has been installed, otherwise an empty path. */
    private fun occlusionModelPath(): String =
        models.models()
            .firstOrNull { it.fileName == ModelManager.XSEG_FILE }
            ?.takeIf { it.installed }
            ?.file
            ?.absolutePath
            ?: ""

    private fun showModelDiagnostics() {
        val lines = models.models().map { m ->
            val state = if (models.isSwapperInstalled(m.fileName)) "installed" else "not downloaded"
            "${m.title} \u00B7 ${m.approxSize} \u00B7 ${m.licence} \u00B7 $state"
        }
        showSheet("Model Diagnostics", lines, -1,
            lines.map { "Tap for details in Settings \u2192 AI Models." }) { show("settings") }
    }

    // ---------------------------------------------------- history screen

    private fun buildHistoryScreen(): View {
        val col = pageColumn()
        col.addView(RoopUi.screenTitle(this, "Library"))
        col.addView(RoopUi.body(
            this, "All your face swap results, privately saved on this device.",
        ).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 16)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        historySearch = EditText(this).apply {
            hint = "Search by name, date or type\u2026"
            textSize = 14f
            setTextColor(getColor(R.color.ink))
            setHintTextColor(getColor(R.color.ink_muted))
            background = getDrawable(R.drawable.bg_input)
            setPadding(dp(38), dp(12), dp(12), dp(12))
            layoutParams = RoopUi.weight(0, dp(46), 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    hQuery = s?.toString() ?: ""
                    renderHistory()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }
        val searchWrap = FrameLayout(this).apply {
            layoutParams = RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(historySearch)
            addView(RoopUi.iconView(this@MainActivity, R.drawable.ic_search, 17, R.color.ink_muted)
                .apply { layoutParams = FrameLayout.LayoutParams(dp(17), dp(17)).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL; marginStart = dp(13) } })
        }
        controls.addView(searchWrap)
        controls.addView(iconButton(R.drawable.ic_filter, "Filter") { showFilterSheet() })
        controls.addView(iconButton(R.drawable.ic_sort, "Sort") { showSortSheet() })
        col.addView(controls)

        historyChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(RoopUi.hScroll(this, historyChips).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 4)
        })

        historyBulk = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0)
        }
        col.addView(historyBulk)

        historyList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0)
        }
        col.addView(historyList)
        return scrollPage(col)
    }

    private fun renderHistory() {
        historyChips.removeAllViews()
        val chipIcons = mapOf(
            "Images" to R.drawable.ic_image, "Videos" to R.drawable.ic_video,
            "GIFs" to R.drawable.ic_gif, "Favorites" to R.drawable.ic_heart,
        )
        listOf("All", "Images", "Videos", "GIFs", "Favorites").forEach { label ->
            historyChips.addView(RoopUi.chip(this, label, label == hFilter, chipIcons[label]).apply {
                layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0)
                setOnClickListener { hFilter = label; renderHistory() }
            })
        }

        val visible = history.filter(hFilter, favoritesOnly = false, query = hQuery, sort = hSort)
        historyList.removeAllViews()
        historyBulk.removeAllViews()

        if (visible.isEmpty()) {
            historyList.addView(emptyState(
                if (hQuery.isNotBlank() || hFilter != "All") "No results for these filters"
                else "Your creations will appear here",
                if (hQuery.isNotBlank() || hFilter != "All")
                    "Try a different filter or clear the search."
                else "Results you keep in Roop stay available on this device.",
                if (hQuery.isNotBlank() || hFilter != "All") "Clear filters" else "Start a Swap",
            ) {
                if (hQuery.isNotBlank() || hFilter != "All") {
                    hQuery = ""; hFilter = "All"; historySearch.setText(""); renderHistory()
                } else {
                    show("swap")
                }
            })
            return
        }

        for (group in history.grouped(visible)) {
            val card = RoopUi.card(this)
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(RoopUi.groupCheck(this).apply {
                layoutParams = RoopUi.margin(this@MainActivity, dp(22), dp(22), 0, 0, 9, 0)
            })
            header.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(RoopUi.text(this@MainActivity, group.label, 15f, R.color.ink, 1))
                addView(RoopUi.meta(this@MainActivity, history.countSummary(group)))
            }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(iconButton(R.drawable.ic_trash, "Delete ${group.label}") {
                confirmDelete(group.items.map { it.id }.toSet(), "Delete ${group.items.size} items?")
            }.apply { layoutParams = RoopUi.lp(dp(38), dp(38)) })
            card.addView(header)

            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.items.forEach { item -> strip.addView(thumbnail(item)) }
            card.addView(RoopUi.hScroll(this, strip).apply {
                layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0)
            })
            historyList.addView(card)
        }

        val del = RoopUi.primaryButton(this, "Delete Selected (${hSelection.size})",
            R.drawable.ic_trash, trailingArrow = false)
        del.setOnClickListener {
            if (hSelection.isEmpty()) {
                toast("Select items first")
            } else {
                confirmDelete(hSelection.toSet(), "Delete ${hSelection.size} items?")
            }
        }
        RoopUi.setPrimaryEnabled(del, hSelection.isNotEmpty())
        historyBulk.addView(del)
    }

    private fun thumbnail(item: HistoryManager.Item): View =
        FrameLayout(this).apply {
            layoutParams = RoopUi.margin(this@MainActivity, dp(104), dp(104), 0, 0, 10, 0)
            background = getDrawable(R.drawable.bg_thumb)
            clipToOutline = true
            contentDescription = "${item.typeBadge} ${item.fileName}"
            isClickable = true
            setOnClickListener { onThumbTapped(item) }

            if (item.exists) {
                addView(ImageView(this@MainActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val poster = history.posterFor(item)
                    if (poster != null) setImageBitmap(poster) else loadThumb(item.file, this)
                })
            } else {
                addView(RoopUi.iconView(this@MainActivity, R.drawable.ic_close, 22, R.color.ink_muted)
                    .apply { layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply {
                        gravity = Gravity.CENTER } })
            }

            // Bottom-left: the type, or the running time for a video (artboard).
            val isVideoItem = item.kind == HistoryManager.Kind.VIDEO
            val badgeText = when {
                !item.exists -> "MISSING"
                isVideoItem && item.durationLabel.isNotEmpty() -> item.durationLabel
                else -> item.typeBadge
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.bg_badge)
                setPadding(dp(7), dp(3), dp(8), dp(3))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.BOTTOM or Gravity.START; marginStart = dp(6); bottomMargin = dp(6) }
                addView(RoopUi.iconView(this@MainActivity,
                    if (isVideoItem) R.drawable.ic_video else R.drawable.ic_image, 11,
                    R.color.surface_solid))
                addView(RoopUi.text(this@MainActivity, badgeText, 9.5f, R.color.surface_solid, 1)
                    .apply { setPadding(dp(4), 0, 0, 0) })
            })
            addView(FrameLayout(this@MainActivity).apply {
                background = getDrawable(R.drawable.bg_badge)
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = dp(6); bottomMargin = dp(6)
                }
                isClickable = true
                contentDescription = "Actions for ${item.fileName}"
                addView(RoopUi.iconView(this@MainActivity, R.drawable.ic_dots, 12,
                    R.color.surface_solid).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                        gravity = Gravity.CENTER
                    }
                })
                setOnClickListener { showItemSheet(item) }
            })
            if (item.favorite) {
                addView(RoopUi.iconView(this@MainActivity, R.drawable.ic_star, 15, R.color.warning)
                    .apply { layoutParams = FrameLayout.LayoutParams(dp(15), dp(15)).apply {
                        gravity = Gravity.TOP or Gravity.END; marginEnd = dp(6); topMargin = dp(6) } })
            }
            if (hSelection.contains(item.id)) {
                addView(FrameLayout(this@MainActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(getColor(R.color.surface_selected))
                    alpha = 0.55f
                })
                addView(RoopUi.iconView(this@MainActivity, R.drawable.ic_check, 20, R.color.blue)
                    .apply { layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                        gravity = Gravity.CENTER } })
            }
        }

    private fun onThumbTapped(item: HistoryManager.Item) {
        if (!item.exists) {
            confirmDelete(setOf(item.id), "Remove this missing file from history?")
            return
        }
        if (hSelecting || hSelection.isNotEmpty()) {
            if (hSelection.contains(item.id)) hSelection.remove(item.id) else hSelection.add(item.id)
            renderHistory()
            return
        }
        showItemSheet(item)
    }

    private fun showItemSheet(item: HistoryManager.Item) {
        val isClip = isVideo(item.fileName)
        val options = listOf("Preview", "Save to Gallery", "Share") +
            (if (isClip) listOf("Save as GIF") else emptyList()) +
            listOf(
                if (item.favorite) "Remove from Favorites" else "Add to Favorites",
                "Delete",
            )
        showSheet(item.fileName, options, -1, null) { pick ->
            when (options[pick]) {
                "Preview" -> previewItem(item)
                "Save to Gallery" -> {
                    if (saveToGallery(item.file)) toast("Saved to Gallery") else toast("Could not save")
                }
                "Save as GIF" -> exportGif(item.file)
                "Share" -> share(item.file)
                "Add to Favorites", "Remove from Favorites" -> {
                    history.toggleFavorite(item.id); renderHistory()
                }
                "Delete" -> confirmDelete(setOf(item.id), "Delete this item?")
            }
        }
    }

    /**
     * Turn a finished clip into a GIF.
     *
     * MediaCodec cannot write GIF and the whole video path goes through it, so
     * this samples the clip and encodes separately. Sixteen frames at 12 fps is
     * about a second and a half of motion - enough to read as animation, short
     * enough that the file stays a size anyone will send.
     */
    private fun exportGif(clip: File) {
        toast("Building GIF\u2026")
        Thread {
            val frames = sampleForGif(clip)
            if (frames.isEmpty()) {
                runOnUiThread { toast("That clip could not be read") }
                return@Thread
            }
            val outDir = File(getExternalFilesDir(null), "outputs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // Interpolate between the samples when RIFE is present. Sixteen
            // frames become thirty-one, which is the difference between motion
            // and a flipbook - and it is what the model is for.
            val smoothed = smoothForGif(frames)
            val out = File(outDir, "roop_$stamp.gif")
            val ok = GifWriter.write(out, smoothed, if (smoothed.size > frames.size) 40 else 80)
            runOnUiThread {
                if (ok && out.isFile) {
                    registerResult(out, clip, "GIF")
                    toast("GIF saved")
                } else {
                    toast("Could not write the GIF")
                }
            }
        }.start()
    }

    /**
     * Add the frames that were never filmed.
     *
     * A model that cannot be loaded, or a pair it refuses, simply produces no
     * extra frame - the GIF is still made, just at the sampled rate.
     */
    private fun smoothForGif(frames: List<Bitmap>): List<Bitmap> {
        if (frames.size < 2 || !rifeReady) return frames
        val out = ArrayList<Bitmap>(frames.size * 2 - 1)
        for (i in frames.indices) {
            out.add(frames[i])
            if (i == frames.size - 1) break
            val a = frames[i]
            val b = frames[i + 1]
            if (a.width != b.width || a.height != b.height) continue
            val middle = interpolate(a, b) ?: continue
            out.add(middle)
        }
        return out
    }

    private var rifeReady = false

    private fun interpolate(a: Bitmap, b: Bitmap): Bitmap? {
        val w = a.width
        val h = a.height
        val first = ByteArray(w * h * 4)
        val second = ByteArray(w * h * 4)
        a.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(first))
        b.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(second))
        val result = NativeBridge.interpolate(w, h, first, second, 0.5f) ?: return null
        if (result.size < w * h * 4) return null
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(result))
        }
    }

    /** Evenly spaced frames, scaled so a GIF stays a sane size. */
    private fun sampleForGif(clip: File): List<Bitmap> {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(clip.absolutePath)
            val durationMs = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) {
                emptyList()
            } else {
                val count = 16
                (0 until count).mapNotNull { index ->
                    val us = (durationMs * 1000.0 * index / count).toLong()
                    retriever
                        .getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { frame ->
                            val longest = maxOf(frame.width, frame.height)
                            if (longest <= 480) {
                                frame
                            } else {
                                val scale = 480.0 / longest
                                Bitmap.createScaledBitmap(
                                    frame,
                                    (frame.width * scale).toInt().coerceAtLeast(1),
                                    (frame.height * scale).toInt().coerceAtLeast(1),
                                    true,
                                )
                            }
                        }
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** History detail: the media itself, plus the actions that apply to it. */
    private fun previewItem(item: HistoryManager.Item) {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_sheet)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        box.addView(RoopUi.text(this, item.fileName, 16f, R.color.ink, 1).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        box.addView(RoopUi.meta(this, describe(item.file)))

        val holder = FrameLayout(this).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320), 0, 12, 0, 0)
            background = getDrawable(R.drawable.bg_thumb)
            clipToOutline = true
        }
        if (isVideo(item.fileName)) {
            holder.addView(VideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setVideoPath(item.path)
                setOnPreparedListener { player ->
                    player.isLooping = true
                    start()
                }
            })
        } else {
            holder.addView(ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                loadThumb(item.file, this)
            })
        }
        box.addView(holder)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0)
        }
        actions.addView(textButton("Save", R.color.blue) {
            if (saveToGallery(item.file)) toast("Saved to Gallery") else toast("Could not save")
        }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(textButton("Share", R.color.blue) { share(item.file) },
            RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(textButton("Close", R.color.ink_secondary) { dialog.dismiss() },
            RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(actions)
        dialog.setContentView(box)
        presentBottomSheet(dialog)
    }

    private fun confirmDelete(ids: Set<String>, message: String) {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_sheet)
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        box.addView(RoopUi.text(this, message, 17f, R.color.ink, 1))
        box.addView(RoopUi.body(this,
            "This removes Roop's own copy. Anything you exported to the Gallery is untouched."))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0)
        }
        row.addView(textButton("Cancel", R.color.ink_secondary) { dialog.dismiss() },
            RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(textButton("Delete", R.color.danger) {
            history.delete(ids)
            hSelection.clear()
            renderHistory()
            dialog.dismiss()
        }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        dialog.setContentView(box)
        presentBottomSheet(dialog)
    }

    /** Two cells side by side, 9dp apart (spec 8.5). */
    private fun pairRow(left: View, right: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 9)
            addView(left, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(9) })
            addView(right, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    /** `\u00B7 1080\u00D71920` when the dimensions can be read. */
    private fun dimensionsSuffix(file: File): String {
        val dims = try {
            if (isVideo(file.name)) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (w != null && h != null) "$w\u00D7$h" else ""
                } finally {
                    retriever.release()
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                if (options.outWidth > 0) "${options.outWidth}\u00D7${options.outHeight}" else ""
            }
        } catch (_: Throwable) {
            ""
        }
        return if (dims.isEmpty()) "" else " \u00B7 $dims"
    }

    /** Full-screen-ish preview of a finished file. */
    private fun previewFile(file: File) {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_sheet)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        box.addView(RoopUi.text(this, file.name, 16f, R.color.ink, 1).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        box.addView(RoopUi.meta(this, describe(file) + dimensionsSuffix(file)))
        val holder = FrameLayout(this).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                dp(340), 0, 12, 0, 0)
            background = getDrawable(R.drawable.bg_thumb)
            clipToOutline = true
        }
        if (isVideo(file.name)) {
            holder.addView(VideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setVideoPath(file.absolutePath)
                setOnPreparedListener { player ->
                    player.isLooping = true
                    start()
                }
            })
        } else {
            holder.addView(ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                loadThumb(file, this)
            })
        }
        box.addView(holder)
        box.addView(textButton("Close", R.color.ink_secondary) { dialog.dismiss() })
        dialog.setContentView(box)
        presentBottomSheet(dialog)
    }

    private fun textButton(label: String, colorRes: Int, onClick: () -> Unit): TextView =
        RoopUi.text(this, label, 15f, colorRes, 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setOnClickListener { onClick() }
            RoopUi.pressScale(this)
        }

    private fun emptyState(title: String, message: String, cta: String, onClick: () -> Unit): View =
        RoopUi.card(this).apply {
            gravity = Gravity.CENTER
            addView(RoopUi.softTile(this@MainActivity, R.drawable.ic_history, R.color.blue).apply {
                layoutParams = RoopUi.lp(dp(38), dp(38))
            })
            addView(RoopUi.text(this@MainActivity, title, 17f, R.color.ink, 1).apply {
                layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 4)
            })
            addView(RoopUi.body(this@MainActivity, message).apply { gravity = Gravity.CENTER })
            addView(RoopUi.text(this@MainActivity, cta, 14f, R.color.blue, 1).apply {
                setPadding(0, dp(12), 0, dp(4))
                isClickable = true
                setOnClickListener { onClick() }
            })
        }

    // --------------------------------------------------- settings screen

    private fun buildSettingsScreen(): View {
        val col = pageColumn()
        col.addView(RoopUi.screenTitle(this, "Settings"))
        col.addView(RoopUi.body(this, "Customize Roop for the best results on your device.")
            .apply {
                layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 18)
            })

        // --- AI models
        val modelCard = RoopUi.card(this)
        modelCard.addView(RoopUi.sectionHeader(
            this, R.drawable.ic_cube, "AI Models", "Choose the model for face swap",
            gradient = true, trailing = "Learn more",
        ))
        modelCard.addView(RoopUi.text(this,
            "Names and descriptions match the real models. Swappers are research-licence " +
                "unless marked otherwise.",
            12f, R.color.ink_muted).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10)
        })
        modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modelCard.addView(RoopUi.hScroll(this, modelRow))
        col.addView(modelCard)

        downloadCard = RoopUi.card(this).apply { visibility = View.GONE }
        col.addView(downloadCard)

        // The complete inventory: every model the engine can use, not just the
        // swappers. Mirrors the original CHECK / DOWNLOAD MODELS readout.
        val inventoryCard = RoopUi.card(this)
        inventoryCard.addView(RoopUi.sectionHeader(
            this, R.drawable.ic_cube, "All models",
            "Everything the engine can use, and what is on this device",
        ))
        val all = models.models()
        val installedCount = all.count { it.installed }
        inventoryCard.addView(RoopUi.text(this,
            "Engine ${appVersion()}  \u00B7  Present: $installedCount/${all.size}  \u00B7  " +
                ModelManager.humanSize(models.totalBytes()) + " on disk",
            12f, R.color.ink_secondary, 1).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10)
        })
        inventoryCard.addView(RoopUi.text(this,
            "Detector, recogniser and landmarker are required for any swap. Swappers and the " +
                "restorer are optional.",
            11.5f, R.color.ink_muted).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10)
        })
        for (model in all) inventoryCard.addView(inventoryRow(model))
        col.addView(inventoryCard)

        // --- quality
        val qCard = RoopUi.card(this)
        qCard.addView(RoopUi.sectionHeader(
            this, R.drawable.ic_sparkle, "Quality", "Balance quality, speed and resource usage",
        ))
        qualitySegmentHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        qCard.addView(qualitySegmentHost)
        qualityHint = RoopUi.meta(this, "").apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0)
        }
        qCard.addView(qualityHint)
        col.addView(qCard)

        // --- swap options
        val oCard = RoopUi.card(this)
        oCard.addView(RoopUi.sectionHeader(
            this, R.drawable.ic_sliders, "Swap Options", "Fine tune the result",
        ))
        val restoreCell = RoopUi.settingCell(this, R.drawable.ic_shield, "Restore Details",
            if (restoreOn) "On" else "Off").apply {
            isClickable = true
            setOnClickListener {
                showSheet("Restore Details", listOf("Off", "On"), if (restoreOn) 1 else 0,
                    listOf("Keep the raw swap.", "Recover fine detail with the restore model.")) { pick ->
                    restoreOn = pick == 1; prefs.restore = restoreOn; renderSettings()
                }
            }
        }
        val strengthCell = RoopUi.settingCell(this, R.drawable.ic_sliders, "Restore Strength",
            "${(prefs.restoreStrength * 100).toInt()}%").apply {
            isClickable = true
            setOnClickListener { showStrengthSheet() }
        }
        oCard.addView(pairRow(restoreCell, strengthCell))
        oCard.addView(pairRow(
            RoopUi.settingCell(this, R.drawable.ic_sliders, "Face Blend", "Not available", enabled = false),
            RoopUi.settingCell(this, R.drawable.ic_chip, "Color Match",
                if (prefs.colorMatch) "On" else "Off").apply {
                setOnClickListener {
                    showSheet("Color Match", listOf("Off", "On"), if (prefs.colorMatch) 1 else 0,
                        listOf("Leave the swapped face's own colour.",
                            "Match it to the target's skin tone and light.")) { pick ->
                        prefs.colorMatch = pick == 1; renderSettings()
                    }
                }
            },
        ))
        col.addView(oCard)

        // --- device
        val dCard = RoopUi.card(this)
        dCard.addView(RoopUi.sectionHeader(
            this, R.drawable.ic_chip, "Device",
            "Processing happens on your device. Your files stay private.",
        ))
        deviceRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dCard.addView(deviceRow)
        storageRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dCard.addView(storageRow)
        col.addView(dCard)

        // --- privacy
        val pCard = RoopUi.card(this)
        pCard.addView(RoopUi.sectionHeader(
            this, R.drawable.ic_shield, "Privacy & Data", "Your files never leave your device",
        ))
        val (gRow, gToggle) = RoopUi.toggleRow(this, "Save to Gallery",
            "Copy each result into the device gallery", prefs.saveToGallery)
        gToggle.setOnCheckedChangeListener { _, checked -> prefs.saveToGallery = checked }
        pCard.addView(gRow)
        val (hRow, hToggle) = RoopUi.toggleRow(this, "Keep History",
            "Save results in Roop's own history", prefs.keepHistory)
        hToggle.setOnCheckedChangeListener { _, checked -> prefs.keepHistory = checked }
        pCard.addView(hRow)
        pCard.addView(RoopUi.settingRow(this, R.drawable.ic_trash, "Delete all history", "") {
            confirmDelete(history.all().map { it.id }.toSet(), "Delete all history?")
        })
        col.addView(pCard)

        // --- about
        val aCard = RoopUi.card(this)
        aCard.addView(RoopUi.sectionHeader(
            this, R.drawable.ic_info, "About", "App information and resources",
        ))
        aCard.addView(RoopUi.settingRow(this, R.drawable.ic_info, "Version", appVersion()))
        aCard.addView(RoopUi.settingRow(this, R.drawable.ic_star, "View Credits", "") {
            showCredits()
        })
        aCard.addView(RoopUi.settingRow(this, R.drawable.ic_history, "Help & Guide", "") {
            showHelp()
        })
        aCard.addView(RoopUi.settingRow(this, R.drawable.ic_download, "Check for Updates", "") {
            showSheet("Check for Updates", listOf("Updates are handled by your app store"),
                -1, listOf("This build has no update channel of its own.")) { }
        })
        col.addView(aCard)
        return scrollPage(col)
    }

    private fun renderSettings() {
        renderModels()
        qualitySegmentHost.removeAllViews()
        qualitySegmentHost.addView(RoopUi.segment(this, Prefs.QUALITY_ORDER, qualityIndex) { pick ->
            qualityIndex = pick
            prefs.quality = Prefs.QUALITY_ORDER[pick]
            renderSettings()
        })
        qualityHint.text = when (qualityIndex) {
            0 -> "Smallest, fastest. Aligns at the model's native size."
            1 -> "Recommended. One extra pass over the face."
            2 -> "More detail, more time."
            else -> "Best quality, slowest. Not offered for video."
        }

        deviceRow.removeAllViews()
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8)
        }
        strip.addView(RoopUi.deviceCell(this, R.drawable.ic_chip, "Processing", "This device"),
            RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(9) })
        strip.addView(RoopUi.deviceCell(this, R.drawable.ic_chip, "Acceleration", "CPU"),
            RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(9) })
        strip.addView(RoopUi.deviceCell(this, R.drawable.ic_folder, "Models",
            ModelManager.humanSize(models.totalBytes())),
            RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        deviceRow.addView(strip)
        deviceRow.addView(RoopUi.meta(this,
            "No GPU backend is compiled into this build, so every model runs on the CPU. " +
                "That is why a video can take minutes.")
            .apply { layoutParams = RoopUi.margin(this@MainActivity,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8) })

        storageRow.removeAllViews()
        storageRow.addView(RoopUi.settingRow(this, R.drawable.ic_folder, "Models on disk",
            ModelManager.humanSize(models.totalBytes())))
        storageRow.addView(RoopUi.settingRow(this, R.drawable.ic_history, "History on disk",
            ModelManager.humanSize(history.totalBytes())))
        storageRow.addView(RoopUi.settingRow(this, R.drawable.ic_trash, "Clear temporary cache",
            "") { clearCache() })
        storageRow.addView(RoopUi.settingRow(this, R.drawable.ic_cube, "Manage downloaded models",
            "") { show("settings") })
    }

    private fun renderModels() {
        modelRow.removeAllViews()
        for (model in modelOptions()) {
            val (file, label, blurb) = model
            val selected = file == swapperFile
            val installed = models.isSwapperInstalled(file)
            val needsConverter = file == ModelManager.GHOST_2 &&
                !models.isSwapperInstalled(ModelManager.GHOST_CONVERTER)
            val card = FrameLayout(this).apply {
                background = getDrawable(
                    if (selected) R.drawable.bg_tile_selected else R.drawable.bg_tile,
                )
                layoutParams = RoopUi.margin(this@MainActivity, dp(120),
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 10, 0)
                isClickable = true
                contentDescription = "$label, ${if (installed) "installed" else "not downloaded"}"
                RoopUi.pressScale(this)
                setOnClickListener { showModelSheet(file, label) }
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(11), dp(11), dp(11))
                addView(FrameLayout(this@MainActivity).apply {
                    background = getDrawable(R.drawable.bg_thumb)
                    layoutParams = RoopUi.lp(dp(42), dp(42))
                    addView(RoopUi.iconView(this@MainActivity, R.drawable.ic_cube, 19, R.color.blue)
                        .apply { layoutParams = FrameLayout.LayoutParams(dp(19), dp(19)).apply {
                            gravity = Gravity.CENTER } })
                })
                addView(RoopUi.text(this@MainActivity, label, 12f, R.color.ink, 1).apply {
                    layoutParams = RoopUi.margin(this@MainActivity,
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        0, 9, 0, 2)
                })
                addView(RoopUi.text(this@MainActivity, blurb, 10.5f, R.color.ink_muted))
                addView(RoopUi.pill(this@MainActivity,
                    if (!installed) "Download" else if (needsConverter) "Needs converter" else "Installed",
                    if (!installed) R.drawable.bg_pill_lavender else R.drawable.bg_pill_soft,
                    if (!installed) R.color.violet else R.color.ink_muted).apply {
                    layoutParams = RoopUi.margin(this@MainActivity,
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        0, 9, 0, 0)
                })
            })
            if (selected) {
                card.addView(RoopUi.checkBadge(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        marginEnd = dp(9); topMargin = dp(9)
                    }
                })
            }
            modelRow.addView(card)
        }
    }

    /** One line per model: what it is, its size and licence, and its state. */
    private fun inventoryRow(model: ModelManager.Model): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_card_inner)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8)
            isClickable = true
            contentDescription = "${model.title}, " +
                (if (model.installed) "installed" else "not downloaded")
            setOnClickListener { showInventorySheet(model) }
            RoopUi.pressScale(this)
        }
        row.addView(RoopUi.softTile(this, iconForModel(model.title), R.color.blue))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 8, 0)
            addView(RoopUi.text(this@MainActivity, model.title, 13.5f, R.color.ink, 1).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val partial = models.isPartial(model.fileName)
            addView(RoopUi.meta(this@MainActivity, if (partial) {
                "${model.fileName} \u00B7 ${ModelManager.humanSize(models.bytesOnDisk(model.fileName))}" +
                    " of ${model.approxSize} downloaded"
            } else {
                "${model.fileName} \u00B7 ${model.approxSize} \u00B7 ${model.licence}"
            }))
        }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(10) })

        when {
            model.installed ->
                row.addView(RoopUi.pill(this, "Installed", R.drawable.bg_pill_soft, R.color.success))
            models.isPartial(model.fileName) ->
                row.addView(RoopUi.pill(this, "Resume", R.drawable.bg_pill_lavender, R.color.violet))
            model.required ->
                row.addView(RoopUi.pill(this, "Required", R.drawable.bg_pill_lavender, R.color.violet))
            else ->
                row.addView(RoopUi.pill(this, "Download", R.drawable.bg_pill_lavender, R.color.violet))
        }
        return row
    }

    private fun iconForModel(title: String): Int = when {
        title.startsWith("Face detector") -> R.drawable.ic_scan
        title.startsWith("Face recogniser") -> R.drawable.ic_person
        title.startsWith("Face landmarker") -> R.drawable.ic_person
        title.startsWith("Converter") -> R.drawable.ic_sliders
        title.startsWith("Enhancer") -> R.drawable.ic_hd
        title.startsWith("Emap") -> R.drawable.ic_cube
        else -> R.drawable.ic_cube
    }

    private fun showInventorySheet(model: ModelManager.Model) {
        val partial = models.isPartial(model.fileName)
        val actions = mutableListOf<String>()
        if (!model.installed) actions.add(if (partial) "Resume download" else "Download")
        if (partial) actions.add("Discard partial download")
        if (model.installed && !model.required) actions.add("Remove")
        actions.add("Details")

        showSheet(model.title, actions, -1, actions.map { action ->
            when (action) {
                "Download" -> "About ${model.approxSize} \u00B7 ${model.licence}"
                "Resume download" ->
                    "Continues from ${ModelManager.humanSize(models.bytesOnDisk(model.fileName))}" +
                        " of ${model.approxSize}."
                "Discard partial download" -> "Deletes the part-downloaded file."
                "Remove" -> "Deletes the downloaded weights from this device."
                else -> if (model.required) {
                    "Required for every swap."
                } else {
                    "Optional — the engine runs without it."
                }
            }
        }) { pick ->
            when (actions[pick]) {
                "Download", "Resume download" -> downloadModel(model)
                "Discard partial download" -> {
                    models.discardPartial(model.fileName)
                    renderSettings()
                }
                "Remove" -> {
                    runCatching { File(model.file.absolutePath).delete() }
                    renderSettings()
                }
                else -> {
                    val state = if (model.installed) "installed" else "not downloaded"
                    toast("${model.fileName} \u00B7 ${model.approxSize} \u00B7 " +
                        "${model.licence} \u00B7 $state")
                }
            }
        }
    }

    /** The real swapper catalogue — no invented product names. */
    private fun modelOptions(): List<Triple<String, String, String>> = listOf(
        Triple(ModelManager.INSWAPPER_FILE, "inswapper 128",
            "Fastest \u00B7 128px \u00B7 non-commercial licence"),
        Triple(ModelManager.HYPERSWAP_1A, "HyperSwap 1a 256",
            "Stable \u00B7 256px \u00B7 research licence"),
        Triple(ModelManager.HYPERSWAP_1C, "HyperSwap 1c 256",
            "Best quality per pass \u00B7 256px"),
        Triple(ModelManager.GHOST_2, "Ghost 2 256",
            "Permissive licence (Apache-2.0)"),
        Triple(ModelManager.UNIFACE, "UniFace 256",
            "Licence unverified upstream"),
        // Converter-mapped families: each needs its sidecar beside the swapper,
        // which is what the download puts there.
        Triple(ModelManager.SIMSWAP_FILE, "SimSwap 256",
            "Older, well-tested \u00B7 non-commercial"),
        Triple(ModelManager.HIFIFACE_FILE, "HifiFace 256",
            "3DMM-based \u00B7 licence unknown"),
        Triple(ModelManager.FACEDANCER_FILE, "FaceDancer 256",
            "Occlusion-aware by design \u00b7 non-commercial"),
    )

    private fun showModelSheet(file: String, label: String) {
        val installed = models.isSwapperInstalled(file)
        val option = models.models().firstOrNull { it.fileName == file }
        val actions = mutableListOf<String>()
        if (!installed) {
            actions.add(if (models.isPartial(file)) "Resume download" else "Download")
        } else {
            actions.add("Use Model")
        }
        if (installed && file != ModelManager.INSWAPPER_FILE) actions.add("Remove")
        actions.add("Details")

        showSheet(label, actions, -1, actions.map { action ->
            when (action) {
                "Download" -> "About ${option?.approxSize ?: "unknown size"} \u00B7 " +
                    "${option?.licence ?: "licence unknown"}"
                "Use Model" -> "Make this the active swapper for new jobs."
                "Remove" -> "Deletes the downloaded weights from this device."
                else -> "Status, licence and what the model can do."
            }
        }) { pick ->
            when (actions[pick]) {
                "Download", "Resume download" -> {
                    val queue = mutableListOf<ModelManager.Model>()
                    option?.let { queue.add(it) }
                    if (file == ModelManager.GHOST_2) {
                        models.models().firstOrNull { it.fileName == ModelManager.GHOST_CONVERTER }
                            ?.let { queue.add(it) }
                    }
                    downloadModels(queue)
                }
                "Use Model" -> { swapperFile = file; prefs.modelFile = file; renderSettings() }
                "Remove" -> {
                    runCatching { File(models.swapperPath(file)).delete() }
                    if (file == ModelManager.GHOST_2) {
                        runCatching { File(models.swapperPath(ModelManager.GHOST_CONVERTER)).delete() }
                    }
                    renderSettings()
                }
                else -> toast(
                    "${option?.licence ?: "Unknown licence"} \u00B7 " +
                        "${option?.approxSize ?: "size unknown"} \u00B7 " +
                        if (installed) "installed" else "not downloaded",
                )
            }
        }
    }

    private fun showStrengthSheet() {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_sheet)
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        box.addView(RoopUi.text(this, "Restore Strength", 17f, R.color.ink, 1))
        val valueLabel = RoopUi.meta(this, "${(prefs.restoreStrength * 100).toInt()}%")
        box.addView(valueLabel)
        val bar = SeekBar(this).apply {
            max = 100
            progress = (prefs.restoreStrength * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.restoreStrength = value / 100f
                    valueLabel.text = "$value%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        box.addView(bar)
        box.addView(RoopUi.meta(this,
            "0% keeps the raw swap; 100% takes the restoration in full."))
        box.addView(textButton("Done", R.color.blue) { dialog.dismiss() })
        dialog.setContentView(box)
        presentBottomSheet(dialog)
    }

    private fun showPro() {
        showSheet("LabPic Pro", listOf("Not available in this build"), -1,
            listOf("There is no paid tier yet. Everything here already runs on your device, " +
                "with no account and no upload.")) { }
    }

    private fun showCredits() {
        showSheet("Credits", listOf(
            "inswapper \u00B7 InsightFace (non-commercial)",
            "HyperSwap \u00B7 FaceFusion (ResearchRAIL)",
            "Ghost \u00B7 ai-forever (Apache-2.0)",
            "GFPGAN \u00B7 Tencent ARC (Apache-2.0)",
            "SCRFD / ArcFace \u00B7 InsightFace (non-commercial)",
        ), -1, listOf(
            "Face swapper, 128px.",
            "Face swapper, 256px.",
            "Face swapper, 256px.",
            "Face restoration.",
            "Detection, recognition and landmarks.",
        )) { }
    }

    private fun showHelp() {
        showSheet("Help & Guide", listOf(
            "Source vs target",
            "Supported formats",
            "Choosing a model",
            "Quality presets",
            "Where results are saved",
            "Privacy",
        ), -1, listOf(
            "Source is the face you copy FROM; target is what you swap ONTO.",
            "Stills, animated GIF/WebP, and video. Video keeps its audio unless muted.",
            "inswapper is lightest; the 256px models are sharper on large faces.",
            "Low to Ultra trade time for detail. Ultra is not offered for video.",
            "Into Roop's own history; gallery copies only if you enable that.",
            "Everything runs on the device. Model downloads are the only network use.",
        )) { }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun clearCache() {
        val before = cacheDirSize()
        runCatching { cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        toast("Temporary cache cleared (${ModelManager.humanSize(before)})")
        renderSettings()
    }

    private fun cacheDirSize(): Long =
        cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun downloadModel(model: ModelManager.Model) = downloadModels(listOf(model))

    /**
     * Runs a queue of transfers on a background thread, publishing live bytes to
     * the progress card. A failure keeps whatever was downloaded, so tapping
     * again resumes instead of starting over.
     */
    private fun downloadModels(queue: List<ModelManager.Model>) {
        if (queue.isEmpty()) return
        if (activeDownload != null) {
            toast("A download is already running")
            return
        }
        activeDownload = queue.first().title
        lastProgress = ModelManager.Progress(
            queue.first().title,
            models.bytesOnDisk(queue.first().fileName),
            queue.first().expectedBytes,
            resuming = models.isPartial(queue.first().fileName),
        )
        renderDownload()

        Thread {
            var failure: String? = null
            var failedModel: ModelManager.Model? = null
            for (model in queue) {
                io.post {
                    activeDownload = model.title
                    lastProgress = ModelManager.Progress(
                        model.title,
                        models.bytesOnDisk(model.fileName),
                        model.expectedBytes,
                        resuming = models.isPartial(model.fileName),
                    )
                    renderDownload()
                }
                try {
                    models.download(model) { pr -> io.post { lastProgress = pr; renderDownload() } }
                } catch (e: Throwable) {
                    failure = e.message ?: "download failed"
                    failedModel = model
                    break
                }
            }
            val f = failure
            val fm = failedModel
            io.post {
                activeDownload = null
                if (f == null) {
                    lastProgress = null
                    renderDownload()
                    renderSettings()
                    toast("Installed")
                } else {
                    val kept = fm?.let { models.bytesOnDisk(it.fileName) } ?: 0L
                    lastProgress = ModelManager.Progress(
                        fm?.title ?: "Download", kept, fm?.expectedBytes ?: 0L, failed = f,
                    )
                    renderDownload()
                    renderSettings()
                    toast("Stopped at ${ModelManager.humanSize(kept)} \u2014 tap Resume to continue")
                }
            }
        }.start()
    }

    /** The live download card: real bytes, a real bar, and a resume note. */
    private fun renderDownload() {
        downloadCard.removeAllViews()
        val pr = lastProgress
        if (pr == null) {
            downloadCard.visibility = View.GONE
            return
        }
        downloadCard.visibility = View.VISIBLE
        downloadCard.addView(RoopUi.sectionHeader(
            this, R.drawable.ic_download,
            if (pr.finished) "Installed" else if (pr.failed != null) "Download stopped" else "Downloading",
            pr.label,
        ))
        downloadCard.addView(RoopUi.text(
            this,
            pr.text + if (pr.percent >= 0) "   \u00B7   ${pr.percent}%" else "",
            15f, R.color.ink, 1,
        ))
        if (pr.resuming && pr.failed == null && !pr.finished) {
            downloadCard.addView(RoopUi.meta(this, "Resumed from where it stopped."))
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = pr.fraction < 0f
            progress = if (pr.fraction >= 0f) (pr.fraction * 1000f).toInt() else 0
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.blue))
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.border_cool))
            layoutParams = RoopUi.margin(this@MainActivity,
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8), 0, 12, 0, 0)
        }
        downloadCard.addView(bar)
        if (pr.failed != null) {
            downloadCard.addView(RoopUi.meta(this,
                "${pr.failed} \u2014 the part-downloaded file is kept. Tap Resume to carry on."))
        } else if (pr.finished) {
            downloadCard.addView(RoopUi.meta(this, "Ready to use."))
        } else {
            downloadCard.addView(RoopUi.meta(this,
                "Safe to stop: a later attempt continues from here."))
        }
    }

    // -------------------------------------------------------------- flows

    private fun detectFaces() {
        val target = targetFile
        if (sourceFiles.isEmpty() || target == null) {
            toast("Choose a source and a target first")
            return
        }
        val dir = File(cacheDir, "faces").apply { deleteRecursively(); mkdirs() }
        swapStatus.text = "Detecting faces\u2026"
        Thread {
            val result = NativeBridge.detectFaces(
                this, sourceFiles.map { it.absolutePath }, target.absolutePath, dir.absolutePath,
            )
            io.post {
                swapStatus.text = ""
                faces = result
                mappings.clear()
                activeSource = 0
                renderMapping()
                if (result == null) toast("Face detection failed")
            }
        }.start()
    }

    private fun runSwap() {
        val target = targetFile ?: return
        val manual = faceMode == 2
        if (manual && mappings.isEmpty()) {
            toast("Pair at least one source and target face")
            return
        }
        val video = isVideo(target.name)
        if (video && qualityIndex == 3) {
            swapStatus.text = "Ultra quality is not offered for video \u2014 using High."
            qualityIndex = 2
            renderSwapTiles()
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "outputs").apply { mkdirs() }
        val ext = when {
            video -> "mp4"
            isAnimation(target.name) -> "gif"
            else -> encodableExtension(target.name)
        }
        val output = File(outDir, "roop_$stamp.$ext")
        val strength = if (restoreOn) prefs.restoreStrength else 1f
        val sources = sourceFiles.map { it.absolutePath }
        val grid = qualityGrid()
        val label = modelOptions().firstOrNull { it.first == swapperFile }?.second ?: swapperFile

        // Adapt the job to the device rather than refusing it.
        //
        // This used to work out that the models would not fit and stop, which
        // left the app unusable on a phone whose ceiling is 512 MB. Now it works
        // out what DOES fit, runs that, and says what it left out. A swap
        // without the polish pass is worth more than no swap.
        val memory = deviceMemory()
        val plan = models.plan(
            swapperFile = swapperFile,
            wantsRestore = restoreOn,
            wantsOcclusion = occlusionModelPath().isNotEmpty(),
            wantsVisibility = landmarkModelPath().isNotEmpty(),
            deviceFreeMb = memory.first,
            deviceTotalMb = memory.second,
        )
        DebugLog.add(
            "plan",
            "needs ${plan.neededMb} MB \u00b7 device ${memory.first} MB free of " +
                "${memory.second} MB \u00b7 java ceiling " +
                "${Runtime.getRuntime().maxMemory() / 1_048_576} MB" +
                (if (plan.adapted) " \u00b7 dropped ${plan.dropped.joinToString()}"
                 else " \u00b7 everything wanted fits"),
        )
        restoreOn = plan.restore
        if (!plan.occlusion && occlusionModelPath().isNotEmpty()) {
            // An empty path is how the native side is told to leave it off.
            NativeBridge.setOcclusion("")
        }
        if (!plan.visibility && landmarkModelPath().isNotEmpty()) {
            NativeBridge.setLandmarkModel("")
        }

        if (plan.adapted) {
            swapStatus.text = "Running without ${plan.dropped.joinToString()} \u2014 " +
                "they need ${plan.neededMb} MB and this device allows ${plan.ceilingMb} MB."
        }

        DebugLog.add("swap", "request: video=$video target=${target.name} sources=${sources.size}")
        DebugLog.add("swap", "swapper=$swapperFile boost=$grid strength=$strength label=$label")
        DebugLog.add("swap", "detector=${File(models.detectorPath).name} " +
            "recogniser=${File(models.recognizerPath).name}")
        DebugLog.add("swap", "occlusion=${File(occlusionModelPath()).name.ifEmpty { "off" }} " +
            "visibility=${File(landmarkModelPath()).name.ifEmpty { "off" }} " +
            "restore=${if (restoreOn) File(models.enhancerPath).name else "off"}")

        // The work is handed to a foreground service, so it survives the app
        // being backgrounded or the screen switching off.
        SwapBus.pending = if (video) {
            SwapBus.Job.Video(
                inputPath = target.absolutePath,
                outputPath = output.absolutePath,
                sourcePaths = sources,
                swapperFile = swapperFile,
                pixelBoost = grid,
                enhancerStrength = strength,
                keepAudio = true,
                colorMatch = prefs.colorMatch,
                clones = prefs.clones,
                originalPath = target.absolutePath,
                modelLabel = label,
            )
        } else {
            SwapBus.Job.Image(
                sourcePaths = sources,
                targetPath = target.absolutePath,
                outputPath = output.absolutePath,
                swapperFile = swapperFile,
                pixelBoost = grid,
                enhancerStrength = strength,
                mappings = if (manual) mappings.toList() else emptyList(),
                animate = isAnimation(target.name),
                swapEveryFace = faceMode == 1,
                colorMatch = prefs.colorMatch,
                originalPath = target.absolutePath,
                modelLabel = label,
            )
        }
        NativeBridge.resetTimeline()
        setSwapBusy(true, "Preparing\u2026")
        if (!video) startPolling()
        requestNotificationConsent()
        SwapService.start(this)
    }

    /**
     * Sample a few frames and swap them, so the settings can be judged before a
     * whole clip is encoded. Same pipeline on a shorter input - and the
     * per-frame timing it produces is a real estimate for the full job.
     */
    private fun openPreview() {
        val target = vTarget ?: run {
            toast("Choose a video first")
            return
        }
        if (vSources.isEmpty()) {
            toast("Add at least one face first")
            return
        }
        startActivityForResult(
            PreviewActivity.intent(
                this,
                target.absolutePath,
                vSources.map { it.absolutePath },
                swapperFile,
                if (qualityIndex == 3) 4 else qualityGrid(),
                if (restoreOn) prefs.restoreStrength else 1f,
            ),
            REQ_PREVIEW,
        )
    }

    private fun runVideo() {
        val target = vTarget ?: return
        if (qualityIndex == 3) qualityIndex = 2
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "outputs").apply { mkdirs() }
        val output = File(outDir, "roop_$stamp.mp4")
        val sources = vSources.map { it.absolutePath }
        val strength = if (restoreOn) prefs.restoreStrength else 1f
        val label = modelOptions().firstOrNull { it.first == swapperFile }?.second ?: swapperFile

        SwapBus.pending = SwapBus.Job.Video(
            inputPath = target.absolutePath,
            outputPath = output.absolutePath,
            sourcePaths = sources,
            swapperFile = swapperFile,
            pixelBoost = qualityGrid(),
            enhancerStrength = strength,
            keepAudio = !vMute,
            colorMatch = prefs.colorMatch,
            clones = prefs.clones,
            originalPath = target.absolutePath,
            modelLabel = label,
        )
        NativeBridge.resetTimeline()
        DebugLog.add("video", "job handed to the service")
        setVideoBusy(true, "Preparing\u2026")
        requestNotificationConsent()
        SwapService.start(this)
    }

    /** The progress notification needs consent on API 33 and later. */
    private fun requestNotificationConsent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    /**
     * Live progress from the running swap, wherever it is: the engine publishes
     * frames done, a measured rate, and an estimate of what is left.
     */
    private fun onSwapState(state: SwapBus.State) {
        if (state.running && state.framesTotal > 0) {
            val percent = (state.fraction * 100f).toInt().coerceIn(0, 100)
            vidBar.visibility = View.VISIBLE
            vidBar.progress = percent
            vidStatus.text = state.detail
            swapBar.visibility = View.VISIBLE
            swapBar.progress = percent
            swapStatus.text = state.detail
        } else if (state.running && state.message.isNotEmpty()) {
            vidStatus.text = state.message
            swapStatus.text = state.message
        }

        if (state.message.isNotBlank()) {
            DebugLog.add("stage", "${state.stage} \u00b7 ${state.message}")
        }

        when (state.stage) {
            SwapBus.Stage.DONE -> {
                stopPolling()
                setSwapBusy(false, "")
                setVideoBusy(false, "")
                val path = state.resultPath ?: return
                val out = File(path)
                state.originalPath?.let { original ->
                    registerResult(out, File(original), state.modelLabel)
                }
                val host = if (isVideo(out.name)) "video" else "swap"
                showResultCardFor(out, host)
                if (host == "video") vidStatus.text = "Completed" else swapStatus.text = "Completed"
                toast("Done")
                showQualityReport()
            }
            SwapBus.Stage.FAILED -> {
                stopPolling()
                setSwapBusy(false, "")
                setVideoBusy(false, "")
                val message = state.error ?: "The swap could not be completed."
                // The full text, not a summary. This is the line the whole debug
                // log exists to deliver.
                DebugLog.add("FAILED", message)
                swapStatus.text = message
                vidStatus.text = message
                // Straight to the log. A failure nobody can read is a failure
                // nobody can report, and that has cost this project days.
                showDebugLog()
            }
            else -> Unit
        }
    }

    private fun qualityGrid(): Int = when (qualityIndex) {
        0 -> 1
        1 -> 2
        2 -> 3
        else -> 4
    }

    private fun setSwapBusy(busy: Boolean, message: String) {
        swapBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) swapBar.progress = 0
        RoopUi.setPrimaryEnabled(swapCta, !busy && sourceFiles.isNotEmpty() && targetFile != null)
        RoopUi.setPrimaryLabel(swapCta, if (busy) "Processing\u2026" else "Start Swap")
        if (message.isNotEmpty()) swapStatus.text = message
    }

    private fun setVideoBusy(busy: Boolean, message: String) {
        vidBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) vidBar.progress = 0
        RoopUi.setPrimaryEnabled(vidCta, !busy && vSources.isNotEmpty() && vTarget != null)
        RoopUi.setPrimaryLabel(vidCta, if (busy) "Processing\u2026" else "Generate Video")
        if (message.isNotEmpty()) vidStatus.text = message
    }

    private fun startPolling() {
        // The engine's own words for what it is doing. A panic inside ONNX
        // Runtime arrives with no message, so this is the only way to place it -
        // and it is written the moment it changes, not after.
        lastStep = ""

        polling = true
            val now = NativeBridge.step()
            if (now.isNotEmpty() && now != lastStep) {
                lastStep = now
                DebugLog.add("engine", now)
            }
        io.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        io.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            val progress = NativeBridge.progressSnapshot()
            swapStatus.text = stageLabel(progress)
            swapBar.progress = stagePercent(progress)
            io.postDelayed(this, 150L)
        }
    }

    private fun stageLabel(p: NativeBridge.Progress): String = when (p.stage) {
        NativeBridge.Stage.LOADING_MODELS -> "Loading models\u2026"
        NativeBridge.Stage.LOADING_SOURCE -> "Scanning source\u2026"
        NativeBridge.Stage.DETECTING -> "Detecting faces\u2026"
        NativeBridge.Stage.SWAPPING ->
            if (p.facesTotal > 1) "Swapping ${p.facesDone}/${p.facesTotal}" else "Swapping\u2026"
        NativeBridge.Stage.SAVING -> "Saving\u2026"
        NativeBridge.Stage.DONE -> "Completed"
        else -> "Working\u2026"
    }

    private fun stagePercent(p: NativeBridge.Progress): Int = when (p.stage) {
        NativeBridge.Stage.LOADING_MODELS -> 8
        NativeBridge.Stage.LOADING_SOURCE -> 25
        NativeBridge.Stage.DETECTING -> 45
        NativeBridge.Stage.SWAPPING ->
            if (p.facesTotal > 0) 55 + 35 * p.facesDone / p.facesTotal else 60
        NativeBridge.Stage.SAVING -> 95
        NativeBridge.Stage.DONE -> 100
        else -> 5
    }

    private fun registerResult(output: File, original: File, modelLabel: String) {
        if (!prefs.saveToGallery) {
            // Nothing to do; the file stays in app storage either way.
        } else {
            if (!saveToGallery(output)) toast("Saved locally, but the gallery copy failed")
        }
        if (prefs.keepHistory) {
            history.add(
                HistoryManager.Item(
                    id = "${System.currentTimeMillis()}_${output.name}",
                    fileName = output.name,
                    path = output.absolutePath,
                    kind = when {
                        isVideo(output.name) -> HistoryManager.Kind.VIDEO
                        isAnimation(output.name) -> HistoryManager.Kind.GIF
                        else -> HistoryManager.Kind.IMAGE
                    },
                    createdAt = System.currentTimeMillis(),
                    sizeBytes = output.length(),
                    width = 0,
                    height = 0,
                    durationMs = if (isVideo(output.name) || isAnimation(output.name)) {
                        probeDuration(output)
                    } else {
                        0L
                    },
                    modelLabel = modelLabel,
                    sourcePath = original.absolutePath,
                    favorite = false,
                ),
            )
        }
    }

    // ------------------------------------------------------- result cards

    private fun showResultCardFor(file: File, host: String) {
        val hostView = if (host == "video") vidResult else swapResult
        hostView.removeAllViews()
        hostView.addView(resultCard(file, host))
    }

    private fun resultCard(file: File, host: String): View {
        val card = RoopUi.card(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(RoopUi.text(this@MainActivity, "Result", 17f, R.color.ink, 1))
            addView(RoopUi.meta(this@MainActivity, describe(file) + dimensionsSuffix(file)))
        }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(RoopUi.pill(this, "Completed", R.drawable.bg_pill_soft, R.color.success))
        card.addView(header)

        val preview = FrameLayout(this).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                dp(200), 0, 12, 0, 0)
            background = getDrawable(R.drawable.bg_thumb)
            clipToOutline = true
        }
        if (isVideo(file.name)) {
            val player = VideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setVideoPath(file.absolutePath)
            }
            preview.addView(player)
            val seek = SeekBar(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(28),
                ).apply { gravity = Gravity.BOTTOM }
            }
            preview.addView(seek)
            val play = iconButton(R.drawable.ic_play, "Play or pause") {
                if (player.isPlaying) player.pause() else player.start()
            }.apply {
                layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                    gravity = Gravity.CENTER
                }
            }
            preview.addView(play)
            player.setOnPreparedListener { mp ->
                seek.max = player.duration
                mp.isLooping = true
                player.start()
            }
            player.setOnCompletionListener { seek.progress = 0 }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) player.seekTo(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        } else {
            preview.addView(ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                loadThumb(file, this)
            })
        }
        card.addView(preview)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0)
        }
        actions.addView(textButton("Preview", R.color.blue) { previewFile(file) },
            RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(textButton("Save to Gallery", R.color.blue) {
            if (saveToGallery(file)) toast("Saved to Gallery") else toast("Could not save")
        }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(textButton("Share", R.color.blue) { share(file) },
            RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(textButton("More", R.color.ink_secondary) {
            showSheet(file.name, listOf("Save to Gallery", "Share", "Open History"), -1, null) { pick ->
                when (pick) {
                    0 -> if (saveToGallery(file)) toast("Saved to Gallery") else toast("Could not save")
                    1 -> share(file)
                    else -> show("history")
                }
            }
        }, RoopUi.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(actions)
        return card
    }

    // ------------------------------------------------------------- media

    private fun pickMedia(requestCode: Int, multiple: Boolean, allowVideo: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                if (allowVideo) arrayOf("image/*", "video/*") else arrayOf("image/*"),
            )
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            toast("No file picker available")
        }
    }

    private fun capture(requestCode: Int) {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        captureTarget = file
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(file))
        }
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            toast("No camera app found")
        }
    }

    private var captureTarget: File? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PREVIEW) {
            // Approved: run the whole clip with the settings that were previewed.
            if (resultCode == RESULT_OK) runVideo()
            return
        }
        if (requestCode in listOf(REQ_CAM_SOURCE, REQ_CAM_VSOURCE, REQ_CAM_TARGET)) {
            val file = captureTarget
            captureTarget = null
            if (resultCode == RESULT_OK && file != null && file.isFile) {
                when (requestCode) {
                    REQ_CAM_SOURCE -> {
                        sourceFiles.clear(); sourceFiles.add(file)
                        faces = null; mappings.clear()
                    }
                    REQ_CAM_TARGET -> {
                        targetFile = file; faces = null; mappings.clear()
                    }
                    else -> { vSources.clear(); vSources.add(file) }
                }
            }
            refreshSwap(); refreshVideo()
            return
        }
        if (resultCode != RESULT_OK) return

        val picked = mutableListOf<File>()
        data?.data?.let { uri -> copyToCache(uri)?.let { picked.add(it) } }
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                copyToCache(clip.getItemAt(i).uri)?.let { picked.add(it) }
            }
        }
        if (picked.isEmpty()) return

        when (requestCode) {
            REQ_SOURCE -> {
                sourceFiles.clear(); sourceFiles.addAll(picked)
                faces = null; mappings.clear()
                // Say plainly what is wrong with the input, before a job starts.
                // A bad source is the most common cause of a bad swap and the app
                // currently says nothing at all.
                sourceAdvice(picked)?.let { swapStatus.text = it }
            }
            REQ_TARGET -> {
                targetFile = picked.first(); faces = null; mappings.clear()
            }
            REQ_VSOURCE -> {
                vSources.clear(); vSources.addAll(picked)
                sourceAdvice(picked)?.let { vidStatus.text = it }
            }
            REQ_VTARGET -> { vTarget = picked.first() }
        }
        refreshSwap()
        refreshVideo()
    }

    private fun copyToCache(uri: Uri): File? = try {
        val name = queryName(uri) ?: "picked_${System.currentTimeMillis()}"
        val dir = File(cacheDir, "picks").apply { mkdirs() }
        val out = File(dir, name)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        if (out.isFile && out.length() > 0) out else null
    } catch (_: Throwable) {
        null
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Throwable) {
        null
    }

    // ----------------------------------------------------------- gallery

    private fun saveToGallery(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERMISSION)
            return false
        }
        return try {
            val video = isVideo(file.name)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/${file.extension}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) +
                            "/Roop",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(collection, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun share(file: File) {
        try {
            val uri = RoopFiles.uriFor(this, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share result"))
        } catch (_: Throwable) {
            toast("Nothing available to share with")
        }
    }

    // -------------------------------------------------------------- misc

    private fun showOverflow() {
        showSheet(
            "More options",
            listOf("Reset inputs", "Debug log", "Model health check",
                "Tools and diagnostics", "Settings", "Help & Guide"),
            -1, null,
        ) { pick ->
            when (pick) {
                0 -> {
                    sourceFiles.clear(); targetFile = null; faces = null; mappings.clear()
                    vSources.clear(); vTarget = null
                    refreshSwap(); refreshVideo()
                    toast("Selections reset")
                }
                1 -> showDebugLog()
                2 -> runHealthCheck()
                3 -> show("tools")
                4 -> show("settings")
                else -> showHelp()
            }
        }
    }

    private fun showFilterSheet() {
        showSheet("Filter", listOf("All", "Images", "Videos", "GIFs", "Favorites"),
            listOf("All", "Images", "Videos", "GIFs", "Favorites").indexOf(hFilter),
            listOf("Everything.", "Stills only.", "Videos only.", "Animated images only.",
                "Starred items only.")) { pick ->
            hFilter = listOf("All", "Images", "Videos", "GIFs", "Favorites")[pick]
            renderHistory()
        }
    }

    private fun showSortSheet() {
        val options = listOf("Newest first", "Oldest first", "Name A\u2013Z", "Largest first")
        showSheet("Sort", options, options.indexOf(hSort), null) { pick ->
            hSort = options[pick]; renderHistory()
        }
    }

    private fun showSheet(
        title: String,
        options: List<String>,
        selected: Int,
        descriptions: List<String>?,
        onPick: (Int) -> Unit,
    ) {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_sheet)
            setPadding(dp(22), dp(20), dp(22), dp(22))
        }
        box.addView(RoopUi.text(this, title, 17f, R.color.ink, 1).apply {
            layoutParams = RoopUi.margin(this@MainActivity, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 6)
        })
        options.forEachIndexed { index, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                RoopUi.pressScale(this)
                setOnClickListener { onPick(index); dialog.dismiss() }
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(RoopUi.text(this, label, 15f, R.color.ink,
                if (index == selected) 1 else 0), RoopUi.weight(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (index == selected) {
                line.addView(RoopUi.iconView(this, R.drawable.ic_check, 18, R.color.blue))
            }
            row.addView(line)
            descriptions?.getOrNull(index)?.let {
                row.addView(RoopUi.text(this, it, 12.5f, R.color.ink_muted))
            }
            box.addView(row)
        }
        dialog.setContentView(box)
        presentBottomSheet(dialog)
    }

    private fun presentBottomSheet(dialog: Dialog) {
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setGravity(Gravity.BOTTOM)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun pageColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // 20dp gutters (spec 2.2 for >=360dp) and the 20-26dp gap the switcher
        // needs before the screen title.
        setPadding(dp(20), dp(22), dp(20), dp(96))
    }

    private fun scrollPage(inner: View, transparent: Boolean = false): ScrollView =
        ScrollView(this).apply {
            isFillViewport = true
            if (!transparent) setBackgroundColor(getColor(R.color.canvas))
            addView(inner)
        }

    /** Decorative blooms sit behind the content, never over text. */
    private fun withBlooms(page: View): View = FrameLayout(this).apply {
        setBackgroundColor(getColor(R.color.canvas))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.bg_bloom)
            scaleType = ImageView.ScaleType.FIT_START
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420),
            ).apply { gravity = Gravity.TOP }
        })
        addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    /** Decode a thumbnail; animated GIFs animate where the platform supports it. */
    private fun loadThumb(file: File, target: ImageView) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isAnimation(file.name)) {
                val source = android.graphics.ImageDecoder.createSource(file)
                val drawable: Drawable = android.graphics.ImageDecoder.decodeDrawable(source)
                target.setImageDrawable(drawable)
                if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                    drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                }
                return
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = if (file.length() > 1_500_000) 4 else 2
            }
            val bitmap: Bitmap? = BitmapFactory.decodeFile(file.absolutePath, options)
            if (bitmap != null) target.setImageBitmap(bitmap)
        } catch (_: Throwable) {
            // A missing or unreadable preview is not worth failing over.
        }
    }

    private fun describe(file: File): String {
        val size = ModelManager.humanSize(file.length())
        val kind = when {
            isVideo(file.name) -> "Video"
            isAnimation(file.name) -> "Animated"
            else -> "Image"
        }
        val duration = if (isVideo(file.name) || isAnimation(file.name)) {
            val ms = probeDuration(file)
            if (ms > 0) " \u00B7 ${HistoryManager.formatDuration(ms)}" else ""
        } else {
            ""
        }
        return "$kind \u00B7 $size$duration"
    }

    private fun probeDuration(file: File): Long = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    } catch (_: Throwable) {
        0L
    }

    private fun isVideo(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.US) in VIDEO_EXTENSIONS

    private fun isAnimation(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext == "gif" || ext == "webp"
    }

    private fun encodableExtension(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return if (ext in ENCODABLE_OUTPUT) ext else "png"
    }

    private fun dp(value: Int): Int = RoopUi.dp(this, value)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_SOURCE = 11
        const val REQ_TARGET = 12
        const val REQ_VSOURCE = 13
        const val REQ_VTARGET = 14
        const val REQ_CAM_SOURCE = 15
        const val REQ_CAM_VSOURCE = 16
        const val REQ_CAM_TARGET = 18
        const val REQ_PERMISSION = 17
        const val REQ_NOTIFICATIONS = 19
        const val REQ_PREVIEW = 20

        val ENCODABLE_OUTPUT = setOf("jpg", "jpeg", "png", "bmp", "tif", "tiff", "webp", "gif")
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "ts", "flv", "wmv", "mpg", "mpeg",
        )
    }
}
