package com.deepfaceswap.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin Kotlin wrapper over the Rust JNI bridge (`libdeep_faceswap_android.so`).
 *
 * All native calls return a JSON string of the form:
 * ```
 * { "ok": true }
 * { "ok": false, "error": "..." }
 * ```
 */
object NativeBridge {

    init {
        System.loadLibrary("deep_faceswap_android")
    }

    private external fun swapImage(
        sourcePaths: String,
        targetPath: String,
        outputPath: String,
        detector: String,
        recognizer: String,
        swapper: String,
        enhancer: String,
        landmark: String,
        useMouthMask: Boolean,
        useMultiFace: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): String

    private external fun coreVersion(): String

    private external fun nativeDetectFaces(
        sourcePaths: String,
        targetPath: String,
        detector: String,
        cropsDir: String,
    ): String

    private external fun swapImageMapped(
        sourcePaths: String,
        targetPath: String,
        outputPath: String,
        detector: String,
        recognizer: String,
        swapper: String,
        enhancer: String,
        landmark: String,
        mappings: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): String

    /** Current swap stage, or -1 when no swap is running. */
    private external fun progress(): Int

    /** Face progress packed as `(done shl 16) or total`. */
    private external fun faceProgress(): Int

    /** One detected face, as returned for the manual mapping UI. */
    data class FaceOption(val index: Int, val score: Float, val cropPath: String)

    /** Faces found in the source image(s) and in the target. */
    data class FaceOptions(val source: List<FaceOption>, val target: List<FaceOption>)

    /** Coarse stages reported by the native engine. */
    enum class Stage {
        IDLE, LOADING_MODELS, LOADING_SOURCE, DETECTING, SWAPPING, SAVING, DONE;

        companion object {
            fun from(code: Int): Stage = when (code) {
                0 -> LOADING_MODELS
                1 -> LOADING_SOURCE
                2 -> DETECTING
                3 -> SWAPPING
                4 -> SAVING
                5 -> DONE
                else -> IDLE
            }
        }
    }

    /** Live progress snapshot: stage plus how many faces are done. */
    data class Progress(val stage: Stage, val facesDone: Int, val facesTotal: Int)

    /**
     * Read the current stage and face counts. Safe to call from the UI thread —
     * it is two atomic loads on the native side.
     */
    fun progressSnapshot(): Progress {
        val stage = Stage.from(runCatching { progress() }.getOrDefault(-1))
        val packed = runCatching { faceProgress() }.getOrDefault(0)
        return Progress(
            stage = stage,
            facesDone = (packed ushr 16) and 0xFFFF,
            facesTotal = packed and 0xFFFF,
        )
    }

    /** Engine version string, e.g. `0.1.0`. */
    fun version(): String = runCatching { coreVersion() }.getOrDefault("unknown")

    /**
     * Result of a face-swap request.
     *
     * @property ok whether the swap succeeded
     * @property error human-readable error message when [ok] is false
     */
    data class SwapResult(val ok: Boolean, val error: String?)

    /**
     * Swap the face(s) in [sourcePaths] onto the image at [targetPath], writing
     * the result to [outputPath]. Model paths come from [ModelManager].
     */
    fun swap(
        context: Context,
        sourcePaths: List<String>,
        targetPath: String,
        outputPath: String,
        swapperFile: String,
        useMouthMask: Boolean,
        useMultiFace: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): SwapResult {
        val models = ModelManager(context)
        val json = swapImage(
            sourcePaths.joinToString(","),
            targetPath,
            outputPath,
            models.detectorPath,
            models.recognizerPath,
            models.swapperPath(swapperFile),
            // The restore model is optional: an empty path disables enhancement.
            if (models.enhancerInstalled) models.enhancerPath else "",
            if (useMouthMask) models.landmarkPath else "",
            useMouthMask,
            useMultiFace,
            pixelBoost,
            enhancerStrength,
        )
        return parse(json)
    }

    private fun parse(json: String): SwapResult = try {
        val obj = JSONObject(json)
        SwapResult(
            ok = obj.optBoolean("ok", false),
            error = if (obj.has("error")) obj.optString("error") else null,
        )
    } catch (e: Exception) {
        SwapResult(ok = false, error = "Malformed native response: $json")
    }

    /**
     * Detect faces in the source(s) and target for the manual mapping UI.
     * Crop images are written under [cropsDir]; returns null if detection failed.
     */
    fun detectFaces(
        context: Context,
        sourcePaths: List<String>,
        targetPath: String,
        cropsDir: String,
    ): FaceOptions? {
        val models = ModelManager(context)
        val json = runCatching {
            nativeDetectFaces(sourcePaths.joinToString(","), targetPath, models.detectorPath, cropsDir)
        }.getOrNull() ?: return null
        return try {
            val obj = JSONObject(json)
            if (!obj.optBoolean("ok", false)) null
            else FaceOptions(
                source = readFaces(obj.optJSONArray("source")),
                target = readFaces(obj.optJSONArray("target")),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readFaces(arr: JSONArray?): List<FaceOption> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            FaceOption(
                index = obj.optInt("index", i),
                score = obj.optDouble("score", 0.0).toFloat(),
                cropPath = obj.optString("crop", ""),
            )
        }
    }

    /**
     * Swap using explicit `(sourceIndex, targetIndex)` pairs, where the indices
     * are positions in detection order — the same indices [detectFaces] returned.
     */
    fun swapMapped(
        context: Context,
        sourcePaths: List<String>,
        targetPath: String,
        outputPath: String,
        mappings: List<Pair<Int, Int>>,
        swapperFile: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): SwapResult {
        val models = ModelManager(context)
        val spec = mappings.joinToString(",") { "${it.first}:${it.second}" }
        val json = swapImageMapped(
            sourcePaths.joinToString(","),
            targetPath,
            outputPath,
            models.detectorPath,
            models.recognizerPath,
            models.swapperPath(swapperFile),
            if (models.enhancerInstalled) models.enhancerPath else "",
            if (useMouthMask) models.landmarkPath else "",
            spec,
            useMouthMask,
            pixelBoost,
            enhancerStrength,
        )
        return parse(json)
    }
}
