package com.deepfaceswap.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Resolves and (optionally) downloads the ONNX models the engine needs.
 *
 * Models live under `filesDir/models`:
 * ```
 * models/
 *   buffalo_l/
 *     det_10g.onnx      (face detector)
 *     w600k_r50.onnx    (ArcFace recogniser)
 *     2d106det.onnx     (106-point landmarks, mouth mask)
 *   inswapper_128.onnx       (face swapper)
 *   inswapper_128_emap.bin   (512x512 f32 embedding map, required by the swapper)
 * ```
 *
 * The emap sidecar is not distributed with the upstream project; generate it
 * from `inswapper_128.onnx` with `scripts/extract_emap.py`, then push it to the
 * device (see the repository README). If you drop it in
 * `app/src/main/assets/inswapper_128_emap.bin` it is copied out automatically.
 */
class ModelManager(private val context: Context) {

    val modelsDir = File(context.filesDir, "models")
    val buffaloDir = File(modelsDir, "buffalo_l")

    val detectorPath = File(buffaloDir, "det_10g.onnx").absolutePath
    val recognizerPath = File(buffaloDir, "w600k_r50.onnx").absolutePath
    val landmarkPath = File(buffaloDir, "2d106det.onnx").absolutePath
    val swapperPath = File(modelsDir, "inswapper_128.onnx").absolutePath
    val emapPath = File(modelsDir, "inswapper_128_emap.bin").absolutePath

    /** A required model file. */
    data class Spec(val label: String, val file: File)

    fun specs(): List<Spec> = listOf(
        Spec("det_10g.onnx (detector)", File(buffaloDir, "det_10g.onnx")),
        Spec("w600k_r50.onnx (recogniser)", File(buffaloDir, "w600k_r50.onnx")),
        Spec("2d106det.onnx (landmarks)", File(buffaloDir, "2d106det.onnx")),
        Spec("inswapper_128.onnx (swapper)", File(modelsDir, "inswapper_128.onnx")),
        Spec("inswapper_128_emap.bin (emap)", File(modelsDir, "inswapper_128_emap.bin")),
    )

    fun ensureDirs() {
        buffaloDir.mkdirs()
    }

    fun missing(): List<String> = specs().filterNot { it.file.isFile }.map { it.label }

    fun isReady(): Boolean = missing().isEmpty()

    fun statusText(): String {
        ensureDirs()
        val present = specs().filter { it.file.isFile }
        val missing = missing()
        return buildString {
            append("Models dir: ${modelsDir.absolutePath}\n")
            append("Present: ${present.size}/${specs().size}\n")
            if (missing.isNotEmpty()) {
                append("Missing:\n")
                missing.forEach { append("  - $it\n") }
            } else {
                append("All models present.")
            }
        }
    }

    /** Copy a bundled emap asset out to the models dir, if one is packaged. */
    fun copyEmapFromAssetsIfPresent(): Boolean {
        val target = File(modelsDir, "inswapper_128_emap.bin")
        if (target.isFile) return true
        return try {
            context.assets.open("inswapper_128_emap.bin").use { input ->
                ensureDirs()
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Download the models that can be fetched automatically (the three buffalo_l
     * models and inswapper_128.onnx). The emap sidecar must be supplied separately.
     *
     * @return a summary string describing the outcome
     */
    fun downloadAll(progress: (String) -> Unit): String {
        ensureDirs()
        val log = StringBuilder()

        // 1) buffalo_l.zip -> det_10g.onnx, w600k_r50.onnx, 2d106det.onnx
        if (!File(buffaloDir, "det_10g.onnx").isFile ||
            !File(buffaloDir, "w600k_r50.onnx").isFile ||
            !File(buffaloDir, "2d106det.onnx").isFile
        ) {
            progress("Downloading buffalo_l.zip ...")
            try {
                downloadZip(
                    "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip",
                    buffaloDir,
                    setOf("det_10g.onnx", "w600k_r50.onnx", "2d106det.onnx"),
                )
                log.append("buffalo_l models downloaded.\n")
            } catch (e: Exception) {
                log.append("buffalo_l download failed: ${e.message}\n")
            }
        } else {
            log.append("buffalo_l models already present.\n")
        }

        // 2) inswapper_128.onnx
        val swapper = File(modelsDir, "inswapper_128.onnx")
        if (!swapper.isFile) {
            progress("Downloading inswapper_128.onnx ...")
            try {
                downloadFile(
                    "https://huggingface.co/hacksider/deep-live-cam/resolve/main/inswapper_128_fp16.onnx",
                    swapper,
                    progress,
                )
                log.append("inswapper_128.onnx downloaded.\n")
            } catch (e: Exception) {
                log.append("inswapper download failed: ${e.message}\n")
            }
        } else {
            log.append("inswapper_128.onnx already present.\n")
        }

        // 3) emap — from assets if packaged, otherwise the user must provide it.
        if (copyEmapFromAssetsIfPresent()) {
            log.append("emap ready.\n")
        } else {
            log.append(
                "emap missing: run scripts/extract_emap.py and push " +
                    "inswapper_128_emap.bin to ${modelsDir.absolutePath}\n",
            )
        }

        return log.toString()
    }

    private fun downloadFile(url: String, target: File, progress: (String) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { output ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    total += n
                    if (total - lastReport > (4L shl 20)) {
                        progress("  ... ${total / (1 shl 20)} MiB")
                        lastReport = total
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun downloadZip(url: String, destDir: File, wanted: Set<String>) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        destDir.mkdirs()
        ZipInputStream(conn.inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = File(entry.name).name
                if (entry.name in wanted || name in wanted) {
                    FileOutputStream(File(destDir, name)).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        conn.disconnect()
    }
}
