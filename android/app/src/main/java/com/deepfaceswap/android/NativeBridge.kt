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
