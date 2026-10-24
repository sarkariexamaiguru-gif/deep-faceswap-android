package com.deepfaceswap.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Resolves and downloads the ONNX models the engine needs.
 *
 * Layout under `filesDir/models`:
 * ```
 * models/
 *   buffalo_l/
 *     det_10g.onnx            face detector
 *     w600k_r50.onnx          ArcFace recogniser
 *     2d106det.onnx           106-point landmarks (mouth mask)
 *   inswapper_128.onnx        face swapper (default)
 *   inswapper_128_emap.bin    emap sidecar (bundled in the APK, copied on launch)
 *   hyperswap_1a_256.onnx     alternative swapper
 *   hyperswap_1c_256.onnx     alternative swapper
 *   GFPGANv1.4.onnx           optional face enhancer ("master" restore model)
 * ```
 */
class ModelManager(private val context: Context) {

    val modelsDir = File(context.filesDir, "models")

    /** Models that belong to a tool rather than to the swapper. */
    val toolsDir = File(modelsDir, "tools")
    val buffaloDir = File(modelsDir, "buffalo_l")

    /** Where a model comes from. */
    enum class Source {
        /** Entry inside the shared buffalo_l archive. */
        BUFFALO_ZIP,
        BUFFALO_S_ZIP,

        /** Bundled in the APK and copied out on first use. */
        ASSET,

        /** A direct download URL. */
        URL,
    }

    /**
     * One downloadable model.
     *
     * @property title    human-readable name
     * @property fileName file on disk
     * @property dir      directory the file lives in
     * @property required whether the engine needs it to run at all
     * @property source   how to obtain it
     * @property zipEntry entry name inside the buffalo_l archive
     * @property url      direct download URL for [Source.URL]
     * @property approxSize human-readable download size, for display
     * @property licence  the licence the model is released under
     */
    data class Model(
        val title: String,
        val fileName: String,
        val dir: File,
        val required: Boolean,
        val source: Source,
        val zipEntry: String? = null,
        val url: String? = null,
        val approxSize: String = "",
        val licence: String = "",
    ) {
        val file: File get() = File(dir, fileName)
        val installed: Boolean get() = file.isFile && file.length() > 0L
        val bytes: Long get() = if (installed) file.length() else 0L

        /** The advertised download size, parsed to bytes (0 when unknown). */
        val expectedBytes: Long get() = ModelManager.parseSize(approxSize)
    }

    fun models(): List<Model> = listOf(
        Model("Face detector", "det_10g.onnx", buffaloDir, true, Source.BUFFALO_ZIP, "det_10g.onnx",
            approxSize = "17 MB", licence = "Non-commercial"),
        Model("Face recogniser", "w600k_r50.onnx", buffaloDir, true, Source.BUFFALO_ZIP, "w600k_r50.onnx",
            approxSize = "166 MB", licence = "Non-commercial"),
        Model("Face landmarker", "2d106det.onnx", buffaloDir, true, Source.BUFFALO_ZIP, "2d106det.onnx",
            approxSize = "5 MB", licence = "Non-commercial"),
        // The same two jobs as the pair above, at a thirteenth of the size. When
        // these are installed the pipeline prefers them - see recognizerPath and
        // detectorPath. The quality report is what says whether that was right.
        Model("Face recogniser \u00b7 small", "w600k_mbf.onnx", buffaloDir, false,
            Source.BUFFALO_S_ZIP, "w600k_mbf.onnx",
            approxSize = "13 MB", licence = "Non-commercial"),
        Model("Face detector \u00b7 small", "det_500m.onnx", buffaloDir, false,
            Source.BUFFALO_S_ZIP, "det_500m.onnx",
            approxSize = "2 MB", licence = "Non-commercial"),
        Model("Emap sidecar", "inswapper_128_emap.bin", modelsDir, true, Source.ASSET,
            approxSize = "1 MB", licence = "Non-commercial"),
        Model("Swapper · inswapper 128", "inswapper_128.onnx", modelsDir, true, Source.URL,
            url = INSWAPPER_URL, approxSize = "265 MB", licence = "Non-commercial"),
        Model("Swapper · HyperSwap 1a 256", HYPERSWAP_1A, modelsDir, false, Source.URL,
            url = HYPERSWAP_1A_URL, approxSize = "384 MB", licence = "ResearchRAIL"),
        Model("Swapper · HyperSwap 1c 256", HYPERSWAP_1C, modelsDir, false, Source.URL,
            url = HYPERSWAP_1C_URL, approxSize = "384 MB", licence = "ResearchRAIL"),
        Model("Swapper · Ghost 2 256", GHOST_2, modelsDir, false, Source.URL,
            url = GHOST_2_URL, approxSize = "705 MB", licence = "Apache-2.0"),
        Model("Converter · Ghost", GHOST_CONVERTER, modelsDir, false, Source.URL,
            url = GHOST_CONVERTER_URL, approxSize = "20 MB", licence = "Apache-2.0"),
        Model("Swapper · UniFace 256", UNIFACE, modelsDir, false, Source.URL,
            url = UNIFACE_URL, approxSize = "388 MB", licence = "Unknown - check before use"),
        Model("Enhancer · GFPGAN 1.4", ENHANCER_FILE, modelsDir, false, Source.URL,
            url = ENHANCER_URL, approxSize = "340 MB", licence = "Apache-2.0"),        Model(
            "Upscaler x4", UPSCALE_FILE, toolsDir, false, Source.URL,
            url = UPSCALE_URL, approxSize = "5 MB", licence = "BSD-3-Clause",
        ),
        Model(
            "Background cutter", BIREFNET_FILE, toolsDir, false, Source.URL,
            url = BIREFNET_URL, approxSize = "94 MB", licence = "MIT",
        ),
        Model(
            "Object eraser", LAMA_FILE, toolsDir, false, Source.URL,
            url = LAMA_URL, approxSize = "200 MB", licence = "Apache-2.0",
        ),
        Model(
            "De-JPEG", FBCNN_FILE, toolsDir, false, Source.URL,
            url = FBCNN_URL, approxSize = "137 MB", licence = "Apache-2.0",
        ),
        Model(
            "Face restorer · GPEN", GPEN_FILE, modelsDir, false, Source.URL,
            url = GPEN_URL, approxSize = "72 MB", licence = "Non-commercial",
        ),
        Model(
            "Occlusion · xseg_2", XSEG_FILE, toolsDir, false, Source.URL,
            url = XSEG_2_URL, approxSize = "1.5 MB", licence = "GPL-3.0",
        ),
        Model(
            "Occlusion · xseg_1", "xseg_1.onnx", toolsDir, false, Source.URL,
            url = XSEG_1_URL, approxSize = "1.5 MB", licence = "GPL-3.0",
        ),
        Model(
            "Occlusion · xseg_3", "xseg_3.onnx", toolsDir, false, Source.URL,
            url = XSEG_3_URL, approxSize = "1.5 MB", licence = "GPL-3.0",
        ),
        Model(
            "Face parser · r34", BISENET_FILE, toolsDir, false, Source.URL,
            url = BISENET_34_URL, approxSize = "85 MB", licence = "MIT",
        ),
        Model(
            "Face parser · r18", "bisenet_resnet_18.onnx", toolsDir, false, Source.URL,
            url = BISENET_18_URL, approxSize = "50 MB", licence = "MIT",
        ),
        Model(
            "Denoiser", SCUNET_FILE, toolsDir, false, Source.URL,
            url = SCUNET_URL, approxSize = "38 MB", licence = "Apache-2.0",
        ),
        Model(
            "De-JPEG · light", OMNI_FILE, toolsDir, false, Source.URL,
            url = OMNI_URL, approxSize = "2.4 MB", licence = "Apache-2.0",
        ),
        Model(
            "Select · encoder", MOBILESAM_ENCODER_FILE, toolsDir, false, Source.URL,
            url = MOBILESAM_ENCODER_URL, approxSize = "27 MB", licence = "Apache-2.0",
        ),
        Model(
            "Select · decoder", MOBILESAM_DECODER_FILE, toolsDir, false, Source.URL,
            url = MOBILESAM_DECODER_URL, approxSize = "16 MB", licence = "Apache-2.0",
        ),
    )

    // Paths handed to the JNI bridge.
    // Prefer the small pack when it is installed. Same job, a thirteenth of the
    // size, and the identity number in the quality report is what proves whether
    // the trade was worth it - which is exactly why that report came first.
    val detectorPath get() = preferred("det_500m.onnx", "det_10g.onnx")
    val recognizerPath get() = preferred("w600k_mbf.onnx", "w600k_r50.onnx")

    /** The small file if it exists, otherwise the large one. */
    private fun preferred(small: String, large: String): String {
        val smallFile = File(buffaloDir, small)
        return if (smallFile.isFile) smallFile.absolutePath else File(buffaloDir, large).absolutePath
    }
    val landmarkPath get() = File(buffaloDir, "2d106det.onnx").absolutePath
    val emapPath get() = File(modelsDir, "inswapper_128_emap.bin").absolutePath
    val enhancerPath get() = File(modelsDir, ENHANCER_FILE).absolutePath

    /** Path to a swapper by file name, e.g. [INSWAPPER_FILE]. */
    fun swapperPath(fileName: String): String = File(modelsDir, fileName).absolutePath

    /** Whether a swapper has been downloaded. */
    /** Bytes already on disk, counting an unfinished download. */
    fun bytesOnDisk(fileName: String): Long {
        val full = File(modelsDir, fileName)
        if (full.isFile && full.length() > 0L) return full.length()
        val part = File(modelsDir, "$fileName.part")
        return if (part.isFile) part.length() else 0L
    }

    /** True when a download was started but never finished. */
    fun isPartial(fileName: String): Boolean {
        val full = File(modelsDir, fileName)
        if (full.isFile && full.length() > 0L) return false
        val part = File(modelsDir, "$fileName.part")
        return part.isFile && part.length() > 0L
    }

    /** Throw away an unfinished download so the next attempt starts clean. */
    fun discardPartial(fileName: String) {
        runCatching { File(modelsDir, "$fileName.part").delete() }
    }

    fun isSwapperInstalled(fileName: String): Boolean =
        File(modelsDir, fileName).let { it.isFile && it.length() > 0L }

    /** True when the optional restore model has been downloaded. */
    val enhancerInstalled: Boolean get() = isSwapperInstalled(ENHANCER_FILE)

    fun ensureDirs() {
        modelsDir.mkdirs()
        buffaloDir.mkdirs()
    }

    fun missing(): List<String> = models().filter { it.required && !it.installed }.map { it.title }

    fun isReady(): Boolean = missing().isEmpty()

    fun installedCount(): Int = models().count { it.installed }

    fun totalBytes(): Long = models().sumOf { it.bytes }

    /** `"4 of 8 present, 412.6 MB on disk"` */
    fun summaryLine(): String {
        val all = models()
        return "${all.count { it.installed }} of ${all.size} present, ${humanSize(totalBytes())} on disk"
    }

    fun statusText(): String {
        ensureDirs()
        return buildString {
            append("Models dir: ${modelsDir.absolutePath}\n")
            append(summaryLine())
            val missing = missing()
            if (missing.isNotEmpty()) {
                append("\nMissing: " + missing.joinToString(", "))
            }
        }
    }

    // ------------------------------------------------------------ downloads --

    /** Download a single model. Buffalo members pull the shared archive once. */
    /**
     * Live state of a model transfer.
     *
     * [bytesTotal] is -1 when the server does not advertise a length, in which
     * case the UI shows a plain byte count rather than inventing a percentage.
     */
    data class Progress(
        val label: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        val resuming: Boolean = false,
        val finished: Boolean = false,
        val failed: String? = null,
    ) {
        val fraction: Float
            get() = if (bytesTotal > 0L) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else -1f

        val percent: Int get() = if (fraction < 0f) -1 else (fraction * 100f).toInt()

        /** `142.0 MB / 705.0 MB`, or just the count when the total is unknown. */
        val text: String
            get() = if (bytesTotal > 0L) {
                "${ModelManager.humanSize(bytesDone)} / ${ModelManager.humanSize(bytesTotal)}"
            } else {
                ModelManager.humanSize(bytesDone)
            }
    }

    fun download(model: Model, progress: (Progress) -> Unit) {
        ensureDirs()
        when (model.source) {
            Source.BUFFALO_ZIP -> {
                val expected = models()
                    .filter { it.source == Source.BUFFALO_ZIP }
                    .sumOf { it.expectedBytes }
                downloadZip(BUFFALO_ZIP_URL, buffaloDir, BUFFALO_ENTRIES, "buffalo_l",
                    expected, progress)
            }
            Source.BUFFALO_S_ZIP -> {
                val expected = models()
                    .filter { it.source == Source.BUFFALO_S_ZIP }
                    .sumOf { it.expectedBytes }
                downloadZip(BUFFALO_S_ZIP_URL, buffaloDir, BUFFALO_S_ENTRIES, "buffalo_s",
                    expected, progress)
            }
            Source.ASSET -> {
                progress(Progress(model.title, 0L, 0L))
                if (!copyEmapFromAssetsIfPresent()) {
                    throw IllegalStateException("emap asset missing from the APK")
                }
                progress(Progress(model.title, 0L, 0L, finished = true))
            }
            Source.URL -> {
                val url = model.url
                    ?: throw IllegalStateException("no download URL for ${model.fileName}")
                downloadFile(url, model.file, model.title, progress)
            }
        }
    }

    /** Fetch everything that can be fetched automatically. Returns a log summary. */
    fun downloadAll(progress: (Progress) -> Unit): String {
        ensureDirs()
        val log = StringBuilder()

        val buffaloMissing = models().filter { it.source == Source.BUFFALO_ZIP && !it.installed }
        if (buffaloMissing.isNotEmpty()) {
            try {
                downloadZip(BUFFALO_ZIP_URL, buffaloDir, BUFFALO_ENTRIES, "buffalo_l",
                    models().filter { it.source == Source.BUFFALO_ZIP }.sumOf { it.expectedBytes },
                    progress)
                log.append("buffalo_l models installed.\n")
            } catch (e: Exception) {
                log.append("buffalo_l download failed: ${e.message}\n")
            }
        }

        models()
            .filter { it.source == Source.URL && it.required && !it.installed }
            .forEach { model ->
                try {
                    downloadFile(model.url!!, model.file, model.title, progress)
                    log.append("${model.fileName} installed.\n")
                } catch (e: Exception) {
                    log.append("${model.fileName} download failed: ${e.message}\n")
                }
            }

        if (copyEmapFromAssetsIfPresent()) {
            log.append("emap installed.\n")
        } else {
            log.append("emap asset missing from the APK.\n")
        }

        val optionalMissing = models().filter { !it.required && !it.installed }
        if (optionalMissing.isNotEmpty()) {
            log.append("Optional (download individually): ")
            log.append(optionalMissing.joinToString(", ") { it.title })
            log.append("\n")
        }

        if (log.isEmpty()) log.append("Everything already present.\n")
        return log.toString()
    }

    /** Copy the bundled emap sidecar out of the APK assets. */
    fun copyEmapFromAssetsIfPresent(): Boolean {
        val target = File(modelsDir, "inswapper_128_emap.bin")
        if (target.isFile && target.length() > 0L) return true
        return try {
            modelsDir.mkdirs()
            context.assets.open("inswapper_128_emap.bin").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------- plumbing --

    private fun downloadFile(
        url: String,
        target: File,
        label: String,
        progress: (Progress) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        // Keep the partial file between attempts so a dropped connection can be
        // resumed instead of restarted.
        val temp = File(target.parentFile, "${target.name}.part")
        var done = if (temp.isFile) temp.length() else 0L

        val conn = open(url, if (done > 0L) done else null)
        try {
            val code = conn.responseCode
            // 206 means the server honoured the Range; anything else means start over.
            val resuming = done > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (done > 0L && !resuming) {
                temp.delete()
                done = 0L
            }
            val length = conn.contentLengthLong
            val total = if (resuming && length > 0L) done + length else length
            progress(Progress(label, done, total, resuming = resuming))

            conn.inputStream.use { input ->
                FileOutputStream(temp, resuming).use { output ->
                    val buf = ByteArray(1 shl 16)
                    var sinceReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        sinceReport += n
                        if (sinceReport >= (1L shl 20)) {
                            progress(Progress(label, done, total, resuming = resuming))
                            sinceReport = 0L
                        }
                    }
                }
            }

            if (temp.length() <= 0L) {
                temp.delete()
                throw IllegalStateException("downloaded file is empty")
            }
            // A short read means the socket dropped mid-file. Leave the .part in
            // place so the next tap continues from here.
            if (total > 0L && temp.length() < total) {
                throw IllegalStateException(
                    "stopped at ${humanSize(temp.length())} of ${humanSize(total)}",
                )
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            progress(Progress(label, done, total, finished = true))
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadZip(
        url: String,
        destDir: File,
        wanted: Set<String>,
        label: String,
        expectedTotal: Long,
        progress: (Progress) -> Unit,
    ) {
        val conn = open(url)
        try {
            destDir.mkdirs()
            var done = 0L
            var sinceReport = 0L
            progress(Progress(label, 0L, expectedTotal))
            ZipInputStream(conn.inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = File(entry.name).name
                    if (name in wanted) {
                        FileOutputStream(File(destDir, name)).use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                sinceReport += n
                                if (sinceReport >= (1L shl 20)) {
                                    progress(Progress(label, done, expectedTotal))
                                    sinceReport = 0L
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            progress(Progress(label, done, expectedTotal, finished = true))
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, from: Long? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            // Generous: these files run to hundreds of megabytes.
            readTimeout = 180_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Roop-Android")
            if (from != null && from > 0L) setRequestProperty("Range", "bytes=$from-")
        }

    companion object {
        const val INSWAPPER_FILE = "inswapper_128.onnx"
        const val HYPERSWAP_1A = "hyperswap_1a_256.onnx"
        const val HYPERSWAP_1C = "hyperswap_1c_256.onnx"
        const val GHOST_2 = "ghost_2_256.onnx"
        const val GHOST_CONVERTER = "arcface_converter_ghost.onnx"
        const val UNIFACE = "uniface_256.onnx"
        const val ENHANCER_FILE = "GFPGANv1.4.onnx"

        /** The upscaler behind the Upscale tool. */
        const val UPSCALE_FILE = "realesr-general-x4v3.onnx"

        /** Background cutter. */
        const val BIREFNET_FILE = "birefnet_lite_512.onnx"

        /** Object eraser. */
        const val LAMA_FILE = "lama_fp32.onnx"

        /** JPEG artifact remover. */
        const val FBCNN_FILE = "fbcnn_color_fp16.onnx"

        /** A smaller face restorer: a quarter of GFPGAN's size for the same job. */
        const val GPEN_FILE = "GPEN-BFR-256.onnx"

        /** Occlusion. XSeg says what is in front of the face so the swap can
         *  leave it alone - the microphone, the hand, the sunglasses. */
        const val XSEG_FILE = "xseg_2.onnx"

        /** Semantic face parsing, for region masks. */
        const val BISENET_FILE = "bisenet_resnet_34.onnx"

        /** General colour denoiser. */
        const val SCUNET_FILE = "scunet_color_real_gan_fp16.onnx"

        /** A very small de-JPEG, for when the 137 MB one is overkill. */
        const val OMNI_FILE = "1xDeJPG_OmniSR-fp16.onnx"

        /** Interactive selection, split into the two halves the model ships as. */
        const val MOBILESAM_ENCODER_FILE = "mobile_sam_image_encoder.onnx"
        const val MOBILESAM_DECODER_FILE = "sam_mask_decoder_single.onnx"

        // Sources below are the upstream or a documented re-export. Each carries
        // the licence of its weights, not of the repository that hosts it.
        private const val BIREFNET_URL =
            "https://huggingface.co/studioludens/birefnet-lite-512/resolve/main/onnx/" +
                "model_fp16.onnx"

        private const val LAMA_URL =
            "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"

        private const val FBCNN_URL =
            "https://huggingface.co/colpona/dejpeg-models/resolve/main/fbcnn/" +
                "fbcnn_color_fp16.onnx"

        // The facefusion-assets release train, which is where the whole
        // FaceFusion model set lives. Version numbers differ per model and are
        // not interchangeable.
        // The release tagged `Models` on that repository. The tag matters: the
        // same file is also published under a second tag, and only this one is
        // the one the upstream download list points at.
        private const val GPEN_URL =
            "https://github.com/harisreedhar/Face-Upscalers-ONNX/releases/download/Models/" +
                "GPEN-BFR-256.onnx"

        private const val XSEG_1_URL =
            "https://github.com/facefusion/facefusion-assets/releases/download/models-3.1.0/xseg_1.onnx"
        private const val XSEG_2_URL =
            "https://github.com/facefusion/facefusion-assets/releases/download/models-3.1.0/xseg_2.onnx"
        private const val XSEG_3_URL =
            "https://github.com/facefusion/facefusion-assets/releases/download/models-3.2.0/xseg_3.onnx"
        private const val BISENET_18_URL =
            "https://github.com/facefusion/facefusion-assets/releases/download/models-3.1.0/bisenet_resnet_18.onnx"
        private const val BISENET_34_URL =
            "https://github.com/facefusion/facefusion-assets/releases/download/models-3.0.0/bisenet_resnet_34.onnx"

        private const val SCUNET_URL =
            "https://huggingface.co/colpona/dejpeg-models/resolve/main/scunet/" +
                "scunet_color_real_gan_fp16.onnx"

        private const val OMNI_URL =
            "https://huggingface.co/colpona/dejpeg-models/resolve/main/1xDeJPG_OmniSR-fp16.onnx"

        private const val MOBILESAM_ENCODER_URL =
            "https://huggingface.co/Acly/MobileSAM/resolve/main/mobile_sam_image_encoder.onnx"

        private const val MOBILESAM_DECODER_URL =
            "https://huggingface.co/Acly/MobileSAM/resolve/main/sam_mask_decoder_single.onnx"

        private const val UPSCALE_URL =
            "https://huggingface.co/CoderViking/realesr-general-x4v3-onnx/resolve/main/" +
                "realesr-general-x4v3.onnx"

        private const val BUFFALO_ZIP_URL =
            "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip"
        private const val INSWAPPER_URL =
            "https://huggingface.co/hacksider/deep-live-cam/resolve/main/inswapper_128_fp16.onnx"
        private const val ENHANCER_URL =
            "https://huggingface.co/hacksider/deep-live-cam/resolve/main/GFPGANv1.4.onnx"
        private const val HYPERSWAP_1A_URL =
            "https://huggingface.co/facefusion/models-3.3.0/resolve/main/hyperswap_1a_256.onnx"
        private const val HYPERSWAP_1C_URL =
            "https://huggingface.co/facefusion/models-3.3.0/resolve/main/hyperswap_1c_256.onnx"
        private const val GHOST_2_URL =
            "https://huggingface.co/facefusion/models-3.0.0/resolve/main/ghost_2_256.onnx"
        private const val GHOST_CONVERTER_URL =
            "https://huggingface.co/facefusion/models-3.0.0/resolve/main/arcface_converter_ghost.onnx"
        private const val UNIFACE_URL =
            "https://huggingface.co/facefusion/models-3.0.0/resolve/main/uniface_256.onnx"

        private val BUFFALO_ENTRIES = setOf("det_10g.onnx", "w600k_r50.onnx", "2d106det.onnx")

        // The small pack. Two of its files replace the two large ones above at a
        // thirteenth of the size; 2d106det is shared and already present.
        private const val BUFFALO_S_ZIP_URL =
            "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip"

        private val BUFFALO_S_ENTRIES = setOf("det_500m.onnx", "w600k_mbf.onnx")

        /** Parse `705 MB` / `1.2 GB` back into bytes; 0 when it cannot. */
        fun parseSize(text: String): Long {
            val t = text.trim().uppercase()
            if (t.isEmpty()) return 0L
            val number = t.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
            return when {
                t.contains("GB") -> (number * 1_073_741_824.0).toLong()
                t.contains("MB") -> (number * 1_048_576.0).toLong()
                t.contains("KB") -> (number * 1024.0).toLong()
                else -> 0L
            }
        }

        fun humanSize(bytes: Long): String {
            if (bytes <= 0L) return "0.0 MB"
            val mb = bytes / 1_048_576.0
            return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
        }
    }
}
