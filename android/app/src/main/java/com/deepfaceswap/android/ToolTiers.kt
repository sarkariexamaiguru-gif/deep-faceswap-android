package com.deepfaceswap.android

/**
 * Quality tiers, per tool.
 *
 * The tool framework already takes a model file as a parameter, so a tier is
 * just a named choice among the models that fit one job. The labels say which is
 * which rather than leaving anyone to guess from a file size.
 *
 * Fast is the small model that was already there; the others are listed on the
 * Models screen and download on first use, like everything else.
 */
object ToolTiers {

    data class Tier(val label: String, val file: String, val note: String)

    /** The tiers offered for a tool, best-last. Empty means no choice. */
    fun forTool(toolId: String): List<Tier> = when (toolId) {
        "upscale" -> listOf(
            Tier("Fast", ModelManager.UPSCALE_FILE, "5 MB \u00b7 SRVGGNet, quick"),
            Tier("Balanced", ModelManager.ESRGAN_X4_FILE, "67 MB \u00b7 Real-ESRGAN x4plus"),
            Tier("Best", ModelManager.SWIN2SR_FILE, "57 MB \u00b7 Swin2SR, transformer"),
        )
        "denoise" -> listOf(
            Tier("Fast", ModelManager.SCUNET_FILE, "38 MB \u00b7 SCUNet"),
            Tier("Best", ModelManager.REALPLKSR_DENOISE_FILE, "30 MB \u00b7 RealPLKSR"),
        )
        "dejpeg" -> listOf(
            Tier("Fast", ModelManager.OMNI_FILE, "2.4 MB \u00b7 OmniSR"),
            Tier("Balanced", ModelManager.REALPLKSR_DEJPEG_FILE, "30 MB \u00b7 RealPLKSR"),
            Tier("Best", ModelManager.FBCNN_FILE, "137 MB \u00b7 FBCNN"),
        )
        else -> emptyList()
    }
}
