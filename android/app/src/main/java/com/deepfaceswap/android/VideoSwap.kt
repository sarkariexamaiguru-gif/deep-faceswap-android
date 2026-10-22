package com.deepfaceswap.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Swaps faces across a video using the platform's own media stack.
 *
 * Design notes:
 * - Decode and encode use the device's codecs in ByteBuffer (YUV) mode, which
 *   avoids any external dependency — no FFmpeg, no APK growth, no extra licence.
 * - The heavy models are loaded **once** per clip through a native swap session,
 *   not once per frame.
 * - Each frame goes to native as a lossless PNG and comes back as a PNG, so the
 *   swap never compounds JPEG artefacts across frames.
 * - The audio track is copied through untouched.
 * - Frames without a detectable face are passed through unchanged.
 *
 * This is CPU-bound by nature: the face swap itself dominates, so expect a short
 * clip to take minutes rather than seconds.
 */
class VideoSwap(private val context: Context) {

    /** Outcome of a video swap. */
    data class Result(val ok: Boolean, val frames: Int, val error: String?)

    /**
     * Swap faces across [input], writing to [output].
     *
     * @param onFrame called as `(framesDone, estimatedTotal)` so the UI can show
     *   real progress. [estimatedTotal] is an estimate from the clip duration.
     */
    fun swap(
        input: File,
        output: File,
        sourcePaths: List<String>,
        mappings: List<Pair<Int, Int>>,
        swapperFile: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
        keepAudio: Boolean,
        onMessage: (String) -> Unit,
        onFrame: (Int, Int) -> Unit,
    ): Result {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val frameDir = File(context.cacheDir, "video_frames")
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)

            val videoTrack = findTrack(extractor, "video/")
                ?: return Result(false, 0, "That file has no video track")
            val audioTrack = findTrack(extractor, "audio/")
            val videoFormat = extractor.getTrackFormat(videoTrack)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME)
                ?: return Result(false, 0, "Unsupported video track")
            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val frameRate = optInt(videoFormat, MediaFormat.KEY_FRAME_RATE, 30).coerceIn(1, 120)

            val durationMs = probeDurationMs(input)
            val estimated = if (durationMs > 0) ((durationMs / 1000.0) * frameRate).toInt() else 0
            if (estimated > MAX_FRAMES) {
                return Result(
                    false, 0,
                    "About $estimated frames — the limit is $MAX_FRAMES. Try a shorter clip.",
                )
            }

            // ---- native session: models and source faces loaded once
            onMessage("Loading models …")
            val session = NativeBridge.openSession(context, sourcePaths, swapperFile, useMouthMask)
            if (!session.ok) {
                return Result(false, 0, session.error ?: "Could not open the swap session")
            }
            onMessage("${session.sourceFaces} source face(s) loaded")

            // ---- decoder
            val decoderName = pickCodec(mime, encoder = false, needFlexible = true)
            decoder = createCodec(decoderName, mime, encoder = false)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            // ---- encoder
            val encoderName = pickCodec(H264, encoder = true, needFlexible = true)
            if (encoderName == null && !hasFlexibleEncoder()) {
                return Result(false, 0, "This device has no H.264 encoder that accepts YUV buffers")
            }
            encoder = createCodec(encoderName, H264, encoder = true)
            encoder.configure(buildEncoderFormat(width, height, frameRate), null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackRef = intArrayOf(-1)
            val startedRef = booleanArrayOf(false)

            frameDir.deleteRecursively()
            frameDir.mkdirs()
            val inFile = File(frameDir, "in.png")
            val outFile = File(frameDir, "out.png")

            val bufferInfo = MediaCodec.BufferInfo()
            val mappingSpec = mappings.joinToString(",") { "${it.first}:${it.second}" }

            var frames = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val endOfStream =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (bufferInfo.size > 0) {
                        val image = decoder.getOutputImage(outIndex)
                        if (image != null) {
                            val frame = yuvToBitmap(image)
                            val swapped = swapOneFrame(
                                frame, inFile, outFile, mappingSpec,
                                useMouthMask, pixelBoost, enhancerStrength,
                            )
                            feedEncoder(encoder, swapped, bufferInfo.presentationTimeUs, width, height)
                            frames++
                            onFrame(frames, if (estimated > 0) estimated else frames)
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (endOfStream) outputDone = true
                }

                drainEncoder(encoder, muxer, bufferInfo, trackRef, startedRef, false)
            }

            // ---- flush the encoder
            onMessage("Finishing video …")
            while (!drainEncoder(encoder, muxer, bufferInfo, trackRef, startedRef, true)) {
                // keep draining until the end-of-stream buffer comes out
            }

            if (!startedRef[0]) {
                return Result(false, 0, "The encoder produced no frames")
            }

            // ---- audio passthrough
            // Audio is copied through untouched, unless the user asked for silence.
            if (audioTrack >= 0 && keepAudio) {
                copyAudio(extractor, audioTrack, muxer)
            }

            return Result(true, frames, null)
        } catch (e: Throwable) {
            // Never let a media failure take the app down: report it instead.
            return Result(false, 0, "${e.javaClass.simpleName}: ${e.message ?: "video swap failed"}")
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            runCatching { NativeBridge.closeSession() }
            runCatching { frameDir.deleteRecursively() }
        }
    }

    // ------------------------------------------------------------ per frame --

    /** Send one frame through native, falling back to the original on failure. */
    private fun swapOneFrame(
        frame: Bitmap,
        inFile: File,
        outFile: File,
        mappingSpec: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): Bitmap {
        return try {
            FileOutputStream(inFile).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val result = NativeBridge.swapSessionFrame(
                inFile.absolutePath, outFile.absolutePath, mappingSpec,
                useMouthMask, pixelBoost, enhancerStrength,
            )
            if (result.ok) {
                BitmapFactory.decodeFile(outFile.absolutePath) ?: frame
            } else {
                frame
            }
        } catch (e: Throwable) {
            frame
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }

    private fun feedEncoder(
        encoder: MediaCodec,
        bitmap: Bitmap,
        ptsUs: Long,
        width: Int,
        height: Int,
    ) {
        while (true) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val image = encoder.getInputImage(index)
                if (image != null) {
                    bitmapToYuv(bitmap, image)
                }
                encoder.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
                return
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        trackRef: IntArray,
        startedRef: BooleanArray,
        endOfStream: Boolean,
    ): Boolean {
        if (endOfStream) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                encoder.queueInputBuffer(
                    index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (endOfStream) continue else return false
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!startedRef[0]) {
                    trackRef[0] = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    startedRef[0] = true
                }
                continue
            }
            if (outIndex < 0) continue

            val buffer = encoder.getOutputBuffer(outIndex)
            if (buffer != null) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    info.size = 0
                }
                if (info.size > 0 && startedRef[0]) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    muxer.writeSampleData(trackRef[0], buffer, info)
                }
            }
            encoder.releaseOutputBuffer(outIndex, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                return true
            }
        }
    }

    /** Copy the audio track through without re-encoding it. */
    private fun copyAudio(extractor: MediaExtractor, audioTrack: Int, muxer: MediaMuxer) {
        val format = extractor.getTrackFormat(audioTrack)
        val index = muxer.addTrack(format)
        if (index < 0) return
        extractor.selectTrack(audioTrack)

        val info = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.allocate(AUDIO_BUFFER)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(index, buffer, info)
            extractor.advance()
        }
    }

    // ----------------------------------------------------------- yuv <-> rgb --

    /** Convert a decoded YUV_420_888 image into an ARGB bitmap, honouring strides. */
    private fun yuvToBitmap(image: android.media.Image): Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val out = IntArray(width * height)
        var index = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = (row / 2) * uvRowStride
            for (col in 0 until width) {
                val y = (yBuffer.get(yRow + col).toInt() and 0xFF)
                val uvCol = (col / 2) * uvPixelStride
                val u = (uBuffer.get(uvRow + uvCol).toInt() and 0xFF) - 128
                val v = (vBuffer.get(uvRow + uvCol).toInt() and 0xFF) - 128

                val yf = (y - 16).coerceAtLeast(0) * 1.164f
                val r = (yf + 1.596f * v).toInt().coerceIn(0, 255)
                val g = (yf - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b = (yf + 2.017f * u).toInt().coerceIn(0, 255)
                out[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Write an ARGB bitmap into a codec input image's YUV planes.
     *
     * The chroma planes are subsampled 2x2, so each chroma sample averages its
     * four source pixels.
     */
    private fun bitmapToYuv(bitmap: Bitmap, image: android.media.Image) {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var index = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            for (col in 0 until width) {
                val colour = pixels[index++]
                val r = (colour shr 16) and 0xFF
                val g = (colour shr 8) and 0xFF
                val b = colour and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(yRow + col, y.coerceIn(0, 255).toByte())
            }
        }

        var row = 0
        while (row < height) {
            var col = 0
            while (col < width) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val x = col + dx
                        val y = row + dy
                        if (x < width && y < height) {
                            val colour = pixels[y * width + x]
                            rSum += (colour shr 16) and 0xFF
                            gSum += (colour shr 8) and 0xFF
                            bSum += colour and 0xFF
                            count++
                        }
                    }
                }
                if (count > 0) {
                    val r = rSum / count
                    val g = gSum / count
                    val b = bSum / count
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val offset = (row / 2) * uvRowStride + (col / 2) * uvPixelStride
                    uBuffer.put(offset, u.coerceIn(0, 255).toByte())
                    vBuffer.put(offset, v.coerceIn(0, 255).toByte())
                }
                col += 2
            }
            row += 2
        }
    }

    // -------------------------------------------------------------- plumbing --

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun probeDurationMs(input: File): Long = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(input.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    } catch (e: Throwable) {
        0L
    }

    private fun optInt(format: MediaFormat, key: String, fallback: Int): Int =
        if (format.containsKey(key)) {
            runCatching { format.getInteger(key) }.getOrDefault(fallback)
        } else {
            fallback
        }

    private fun buildEncoderFormat(width: Int, height: Int, frameRate: Int): MediaFormat {
        val bitrate = (width * height * 4).coerceIn(2_000_000, 12_000_000)
        return MediaFormat.createVideoFormat(H264, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
    }

    /**
     * Pick a codec for [mime]. Software codecs are preferred because they
     * reliably accept YUV buffers, which hardware codecs often do not.
     */
    private fun pickCodec(mime: String, encoder: Boolean, needFlexible: Boolean): String? {
        var fallback: String? = null
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder != encoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            if (needFlexible) {
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                if (!caps.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    )
                ) {
                    continue
                }
            }
            val software = info.name.startsWith("OMX.google") || info.name.startsWith("c2.android")
            if (software) return info.name
            if (fallback == null) fallback = info.name
        }
        return fallback
    }

    private fun hasFlexibleEncoder(): Boolean = pickCodec(H264, encoder = true, needFlexible = true) != null

    private fun createCodec(name: String?, mime: String, encoder: Boolean): MediaCodec =
        if (name != null) {
            MediaCodec.createByCodecName(name)
        } else if (encoder) {
            MediaCodec.createEncoderByType(mime)
        } else {
            MediaCodec.createDecoderByType(mime)
        }

    private companion object {
        const val H264 = "video/avc"
        const val TIMEOUT_US = 10_000L
        const val AUDIO_BUFFER = 256 * 1024

        /** Roughly a minute at 20fps; longer clips would take far too long on a phone. */
        const val MAX_FRAMES = 1200
    }
}
