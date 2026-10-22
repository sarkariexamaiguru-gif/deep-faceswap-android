package com.deepfaceswap.android

import android.content.Context
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
    ): String

    private external fun coreVersion(): String

    /** Current swap stage, or -1 when no swap is running. */
    private external fun progress(): Int

    /** Face progress packed as `(done shl 16) or total`. */
    private external fun faceProgress(): Int

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
        useMouthMask: Boolean,
        useMultiFace: Boolean,
    ): SwapResult {
        val models = ModelManager(context)
        val json = swapImage(
            sourcePaths.joinToString(","),
            targetPath,
            outputPath,
            models.detectorPath,
            models.recognizerPath,
            models.swapperPath,
            // GFPGAN enhancement is optional and not bundled by default.
            "",
            if (useMouthMask) models.landmarkPath else "",
            useMouthMask,
            useMultiFace,
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
}
