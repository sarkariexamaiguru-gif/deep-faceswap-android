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
 *   inswapper_128.onnx        face swapper
 *   inswapper_128_emap.bin    emap sidecar (bundled in the APK, copied on launch)
 * ```
 */
class ModelManager(private val context: Context) {

    val modelsDir = File(context.filesDir, "models")
    val buffaloDir = File(modelsDir, "buffalo_l")

    /** Where a model comes from. */
    enum class Source { BUFFALO_ZIP, INSWAPPER, ASSET }

    /**
     * One downloadable model.
     *
     * @property title    human-readable name
     * @property fileName file on disk
     * @property dir      directory the file lives in
     * @property required whether the engine needs it to run
     * @property source   how to obtain it
     * @property zipEntry entry name inside the buffalo_l archive
     */
    data class Model(
        val title: String,
        val fileName: String,
        val dir: File,
        val required: Boolean,
        val source: Source,
        val zipEntry: String? = null,
    ) {
        val file: File get() = File(dir, fileName)
        val installed: Boolean get() = file.isFile && file.length() > 0L
        val bytes: Long get() = if (installed) file.length() else 0L
    }

    fun models(): List<Model> = listOf(
        Model("Face detector", "det_10g.onnx", buffaloDir, true, Source.BUFFALO_ZIP, "det_10g.onnx"),
        Model("Face recogniser", "w600k_r50.onnx", buffaloDir, true, Source.BUFFALO_ZIP, "w600k_r50.onnx"),
        Model("Face landmarker", "2d106det.onnx", buffaloDir, true, Source.BUFFALO_ZIP, "2d106det.onnx"),
        Model("Face swapper", "inswapper_128.onnx", modelsDir, true, Source.INSWAPPER),
        Model("Emap sidecar", "inswapper_128_emap.bin", modelsDir, true, Source.ASSET),
    )

    // Paths handed to the JNI bridge.
    val detectorPath get() = File(buffaloDir, "det_10g.onnx").absolutePath
    val recognizerPath get() = File(buffaloDir, "w600k_r50.onnx").absolutePath
    val landmarkPath get() = File(buffaloDir, "2d106det.onnx").absolutePath
    val swapperPath get() = File(modelsDir, "inswapper_128.onnx").absolutePath
    val emapPath get() = File(modelsDir, "inswapper_128_emap.bin").absolutePath

    fun ensureDirs() {
        modelsDir.mkdirs()
        buffaloDir.mkdirs()
    }

    fun missing(): List<String> = models().filter { it.required && !it.installed }.map { it.title }

    fun isReady(): Boolean = missing().isEmpty()

    fun installedCount(): Int = models().count { it.installed }

    fun totalBytes(): Long = models().sumOf { it.bytes }

    /** `"3 of 5 present, 412.6 MB on disk"` */
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
            Source.INSWAPPER -> {
                progress("Downloading inswapper_128.onnx …")
                downloadFile(INSWAPPER_URL, File(modelsDir, model.fileName), progress)
            }
            Source.ASSET -> {
                progress("Installing ${model.fileName} from the APK …")
                if (!copyEmapFromAssetsIfPresent()) {
                    throw IllegalStateException("emap asset missing from the APK")
                }
            }
        }
    }

    /** Fetch everything that can be fetched automatically. Returns a log summary. */
    fun downloadAll(progress: (String) -> Unit): String {
        ensureDirs()
        val log = StringBuilder()

        val buffaloMissing = models()
            .filter { it.source == Source.BUFFALO_ZIP && !it.installed }
        if (buffaloMissing.isNotEmpty()) {
            progress("Downloading buffalo_l.zip …")
            try {
                downloadZip(BUFFALO_ZIP_URL, buffaloDir, BUFFALO_ENTRIES, progress)
                log.append("buffalo_l models installed.\n")
            } catch (e: Exception) {
                log.append("buffalo_l download failed: ${e.message}\n")
            }
        }

        val swapper = models().first { it.source == Source.INSWAPPER }
        if (!swapper.installed) {
            progress("Downloading inswapper_128.onnx …")
            try {
                downloadFile(INSWAPPER_URL, swapper.file, progress)
                log.append("inswapper_128.onnx installed.\n")
            } catch (e: Exception) {
                log.append("inswapper download failed: ${e.message}\n")
            }
        }

        if (copyEmapFromAssetsIfPresent()) {
            log.append("emap installed.\n")
        } else {
            log.append("emap asset missing from the APK.\n")
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
            FileOutputStream(target).use { output ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    total += n
                    if (total - last > (4L shl 20)) {
                        progress("  … ${humanSize(total)}")
                        last = total
                    }
                }
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
            readTimeout = 60_000
            instanceFollowRedirects = true
        }

    companion object {
        private const val BUFFALO_ZIP_URL =
            "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip"
        private const val INSWAPPER_URL =
            "https://huggingface.co/hacksider/deep-live-cam/resolve/main/inswapper_128_fp16.onnx"

        private val BUFFALO_ENTRIES = setOf("det_10g.onnx", "w600k_r50.onnx", "2d106det.onnx")

        fun humanSize(bytes: Long): String {
            if (bytes <= 0L) return "0.0 MB"
            val mb = bytes / 1_048_576.0
            return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
        }
    }
}
