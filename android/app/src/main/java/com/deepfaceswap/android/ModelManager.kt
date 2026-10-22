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
    val buffaloDir = File(modelsDir, "buffalo_l")

    /** Where a model comes from. */
    enum class Source {
        /** Entry inside the shared buffalo_l archive. */
        BUFFALO_ZIP,

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
    }

    fun models(): List<Model> = listOf(
        Model("Face detector", "det_10g.onnx", buffaloDir, true, Source.BUFFALO_ZIP, "det_10g.onnx",
            approxSize = "17 MB", licence = "Non-commercial"),
        Model("Face recogniser", "w600k_r50.onnx", buffaloDir, true, Source.BUFFALO_ZIP, "w600k_r50.onnx",
            approxSize = "166 MB", licence = "Non-commercial"),
        Model("Face landmarker", "2d106det.onnx", buffaloDir, true, Source.BUFFALO_ZIP, "2d106det.onnx",
            approxSize = "5 MB", licence = "Non-commercial"),
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
            url = ENHANCER_URL, approxSize = "340 MB", licence = "Apache-2.0"),
    )

    // Paths handed to the JNI bridge.
    val detectorPath get() = File(buffaloDir, "det_10g.onnx").absolutePath
    val recognizerPath get() = File(buffaloDir, "w600k_r50.onnx").absolutePath
    val landmarkPath get() = File(buffaloDir, "2d106det.onnx").absolutePath
    val emapPath get() = File(modelsDir, "inswapper_128_emap.bin").absolutePath
    val enhancerPath get() = File(modelsDir, ENHANCER_FILE).absolutePath

    /** Path to a swapper by file name, e.g. [INSWAPPER_FILE]. */
    fun swapperPath(fileName: String): String = File(modelsDir, fileName).absolutePath

    /** Whether a swapper has been downloaded. */
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
    fun download(model: Model, progress: (String) -> Unit) {
        ensureDirs()
        when (model.source) {
            Source.BUFFALO_ZIP -> {
                progress("Downloading buffalo_l.zip …")
                downloadZip(BUFFALO_ZIP_URL, buffaloDir, BUFFALO_ENTRIES, progress)
            }
            Source.ASSET -> {
                progress("Installing ${model.fileName} from the APK …")
                if (!copyEmapFromAssetsIfPresent()) {
                    throw IllegalStateException("emap asset missing from the APK")
                }
            }
            Source.URL -> {
                val url = model.url
                    ?: throw IllegalStateException("no download URL for ${model.fileName}")
                val size = if (model.approxSize.isNotEmpty()) " (${model.approxSize})" else ""
                progress("Downloading ${model.fileName}$size …")
                downloadFile(url, model.file, progress)
            }
        }
    }

    /** Fetch everything that can be fetched automatically. Returns a log summary. */
    fun downloadAll(progress: (String) -> Unit): String {
        ensureDirs()
        val log = StringBuilder()

        val buffaloMissing = models().filter { it.source == Source.BUFFALO_ZIP && !it.installed }
        if (buffaloMissing.isNotEmpty()) {
            progress("Downloading buffalo_l.zip …")
            try {
                downloadZip(BUFFALO_ZIP_URL, buffaloDir, BUFFALO_ENTRIES, progress)
                log.append("buffalo_l models installed.\n")
            } catch (e: Exception) {
                log.append("buffalo_l download failed: ${e.message}\n")
            }
        }

        models()
            .filter { it.source == Source.URL && it.required && !it.installed }
            .forEach { model ->
                progress("Downloading ${model.fileName} (${model.approxSize}) …")
                try {
                    downloadFile(model.url!!, model.file, progress)
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

    private fun downloadFile(url: String, target: File, progress: (String) -> Unit) {
        val conn = open(url)
        conn.inputStream.use { input ->
            target.parentFile?.mkdirs()
            // Write to a temp file first so an interrupted download never leaves
            // a truncated model that looks installed.
            val temp = File(target.parentFile, "${target.name}.part")
            FileOutputStream(temp).use { output ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    total += n
                    if (total - last > (8L shl 20)) {
                        progress("  … ${humanSize(total)}")
                        last = total
                    }
                }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                throw IllegalStateException("downloaded file is empty")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        conn.disconnect()
    }

    private fun downloadZip(url: String, destDir: File, wanted: Set<String>, progress: (String) -> Unit) {
        val conn = open(url)
        destDir.mkdirs()
        var total = 0L
        var last = 0L
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
                            total += n
                            if (total - last > (8L shl 20)) {
                                progress("  … ${humanSize(total)}")
                                last = total
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        conn.disconnect()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }

    companion object {
        const val INSWAPPER_FILE = "inswapper_128.onnx"
        const val HYPERSWAP_1A = "hyperswap_1a_256.onnx"
        const val HYPERSWAP_1C = "hyperswap_1c_256.onnx"
        const val GHOST_2 = "ghost_2_256.onnx"
        const val GHOST_CONVERTER = "arcface_converter_ghost.onnx"
        const val UNIFACE = "uniface_256.onnx"
        const val ENHANCER_FILE = "GFPGANv1.4.onnx"

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

        fun humanSize(bytes: Long): String {
            if (bytes <= 0L) return "0.0 MB"
            val mb = bytes / 1_048_576.0
            return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
        }
    }
}
