package com.deepfaceswap.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * Preview: swap a few frames, then decide.
 *
 * Before committing to a whole clip, this runs the full pipeline on a handful of
 * sampled frames and shows them with their quality numbers. The point is that
 * finding out a setting is wrong should cost fifteen seconds, not ten minutes of
 * encoding.
 *
 * The trap it has to avoid is a preview that lies. Three frames of the same
 * static close-up make a clip look perfect that falls apart the moment a face
 * turns - so the samples are spread across the timeline rather than taken from
 * the front, and each one reports its own numbers so a weak sample is visible
 * rather than hidden behind an average.
 */
class PreviewActivity : Activity() {

    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_SOURCES = "sources"
        private const val EXTRA_SWAPPER = "swapper"
        private const val EXTRA_BOOST = "boost"
        private const val EXTRA_STRENGTH = "strength"

        /** How many frames to sample. Enough to see a turn, few enough to be fast. */
        private const val SAMPLES = 3

        fun intent(
            context: Context,
            video: String,
            sources: List<String>,
            swapper: String,
            boost: Int,
            strength: Float,
        ): Intent = Intent(context, PreviewActivity::class.java).apply {
            putExtra(EXTRA_VIDEO, video)
            putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources))
            putExtra(EXTRA_SWAPPER, swapper)
            putExtra(EXTRA_BOOST, boost)
            putExtra(EXTRA_STRENGTH, strength)
        }
    }

    private lateinit var column: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var runButton: Button

    private var videoPath = ""
    private var sources: List<String> = emptyList()
    private var swapperFile = ""
    private var boost = 1
    private var strength = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoPath = intent.getStringExtra(EXTRA_VIDEO) ?: ""
        @Suppress("DEPRECATION")
        sources = intent.getStringArrayListExtra(EXTRA_SOURCES) ?: emptyList()
        swapperFile = intent.getStringExtra(EXTRA_SWAPPER) ?: ""
        boost = intent.getIntExtra(EXTRA_BOOST, 1)
        strength = intent.getFloatExtra(EXTRA_STRENGTH, 1f)

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
                text = "Preview"
                textSize = 20f
                setTextColor(Color.parseColor("#0E1424"))
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(button("Close") { finish() })
        root.addView(header)

        statusView = TextView(this).apply {
            text = "Sampling $SAMPLES frames across the clip \u2026"
            textSize = 12f
            setTextColor(Color.parseColor("#7C88A6"))
        }
        root.addView(statusView)

        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(8); bottomMargin = dp(8) },
        )

        runButton = button("Run the whole clip") {
            setResult(RESULT_OK)
            finish()
        }.apply {
            textSize = 14.5f
            setBackgroundColor(Color.parseColor("#1677FF"))
            setTextColor(Color.WHITE)
            isEnabled = false
        }
        root.addView(
            runButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)),
        )

        setContentView(root)

        // Extraction and three full swaps: never on the UI thread.
        Thread { build() }.start()
    }

    // ------------------------------------------------------------- sampling --

    /**
     * Frames spread across the clip, avoiding the first and last tenth where a
     * fade or a title card usually sits.
     */
    private fun sampleFrames(path: String, count: Int): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) {
                emptyList()
            } else {
                (0 until count).mapNotNull { index ->
                    val fraction = (index + 1).toDouble() / (count + 1)
                    val us = (durationMs * 1000.0 * fraction).toLong()
                    retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun build() {
        val frames = sampleFrames(videoPath, SAMPLES)
        if (frames.isEmpty()) {
            ui {
                statusView.text = "This clip could not be read for sampling."
            }
            return
        }

        val work = File(cacheDir, "preview").apply { mkdirs() }
        var shown = 0

        frames.forEachIndexed { index, frame ->
            ui { statusView.text = "Swapping sample ${index + 1} of ${frames.size} \u2026" }

            val input = File(work, "frame_$index.png")
            val output = File(work, "frame_${index}_swapped.png")
            val written = runCatching {
                FileOutputStream(input).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
            if (!written) return@forEachIndexed

            val result = NativeBridge.swap(
                this, sources, input.absolutePath, output.absolutePath,
                swapperFile, false, false, boost, strength,
            )
            if (!result.ok) {
                val why = result.error ?: "the engine refused"
                ui { addCard(index, frame, null, "Could not swap this frame \u2014 $why") }
                return@forEachIndexed
            }

            val swapped = runCatching {
                BitmapFactory.decodeFile(output.absolutePath)
            }.getOrNull()
            val numbers = describeQuality()
            ui {
                addCard(index, frame, swapped, numbers)
                shown++
            }
        }

        ui {
            statusView.text = if (shown == 0) {
                "None of the samples could be swapped."
            } else {
                "$shown of ${frames.size} sampled and swapped. Tap an image to compare with " +
                    "the original."
            }
            runButton.isEnabled = shown > 0
        }
    }

    /** The quality report, phrased for someone deciding rather than reading a log. */
    private fun describeQuality(): String {
        val raw = NativeBridge.qualityReport()
        val parsed = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return ""
        val parts = mutableListOf<String>()

        if (!parsed.isNull("idSimilarity")) {
            val value = parsed.optDouble("idSimilarity")
            val verdict = when {
                value >= 0.6 -> "clearly the same person"
                value >= 0.45 -> "recognisably the same person"
                value >= 0.3 -> "loosely similar"
                else -> "not reading as the same person"
            }
            parts.add("Identity ${"%.2f".format(value)} \u2014 $verdict")
        }

        val before = if (parsed.isNull("sharpnessBefore")) null else parsed.optDouble("sharpnessBefore")
        val after = if (parsed.isNull("sharpnessAfter")) null else parsed.optDouble("sharpnessAfter")
        if (before != null && after != null && before > 1.0) {
            val change = (after / before - 1.0) * 100.0
            val verdict = when {
                change > 12 -> "crisper"
                change > -12 -> "about as sharp"
                else -> "softer"
            }
            parts.add("Sharpness ${"%.0f".format(change)}% \u2014 $verdict")
        }

        if (!parsed.isNull("occlusionCoverage")) {
            val value = parsed.optDouble("occlusionCoverage")
            parts.add(
                if (value > 0.005) {
                    "Occlusion ${"%.0f".format(value * 100)}% left alone"
                } else {
                    "Occlusion \u2014 nothing in front"
                },
            )
        }

        return parts.joinToString("   \u00B7   ")
    }

    // --------------------------------------------------------------- display --

    private fun addCard(index: Int, original: Bitmap, swapped: Bitmap?, numbers: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val seconds = ((index + 1).toDouble() / (SAMPLES + 1)) * 100.0
        card.addView(TextView(this).apply {
            text = "Sample ${index + 1}  \u00B7  about ${"%.0f".format(seconds)}% into the clip"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#7C88A6"))
        })

        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(swapped ?: original)
            if (swapped != null) {
                // Tap to compare. The honest way to judge a swap, and it costs
                // nothing to offer on a still.
                var showingSwapped = true
                setOnClickListener {
                    showingSwapped = !showingSwapped
                    setImageBitmap(if (showingSwapped) swapped else original)
                }
            }
        }
        card.addView(
            image,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        if (numbers.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = numbers
                textSize = 11.5f
                setTextColor(Color.parseColor("#4E5C7C"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(7) }
            })
        } else {
            card.addView(TextView(this).apply {
                text = "No numbers for this frame."
                textSize = 11.5f
                setTextColor(Color.parseColor("#7C88A6"))
            })
        }

        column.addView(card)
    }

    // --------------------------------------------------------------- widgets --

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            textSize = 12.5f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
