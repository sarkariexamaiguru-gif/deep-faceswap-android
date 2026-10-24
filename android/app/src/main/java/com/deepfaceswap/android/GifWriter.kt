package com.deepfaceswap.android

import android.graphics.Bitmap
import java.io.File
import java.io.OutputStream

/**
 * A GIF encoder.
 *
 * The app could read GIFs from the start and could never write one, because
 * MediaCodec does not do GIF and the whole video path goes through MediaCodec.
 * This closes that gap.
 *
 * Two decisions worth naming:
 *
 * The palette is built by MEDIAN CUT over the actual frames rather than taken
 * from a fixed colour cube. A cube is simpler and looks acceptable on most
 * images, and this app is about faces - where a fixed cube bands skin tones
 * badly, which is exactly the case a photo tool cannot afford.
 *
 * Frames are quantised against one GLOBAL palette. Per-frame palettes look
 * marginally better and cost 768 bytes a frame, which on a long clip is most of
 * the file - and a GIF is judged on how it looks per kilobyte.
 */
object GifWriter {

    /** GIF holds 256 colours; one is reserved so the transparent index is free. */
    private const val PALETTE_SIZE = 256

    /**
     * Write `frames` to `file` as a looping GIF.
     *
     * `delayMs` is per frame; GIF stores hundredths of a second, so anything
     * under 20 ms becomes 2, which is the fastest most viewers honour.
     */
    fun write(file: File, frames: List<Bitmap>, delayMs: Int): Boolean = runCatching {
        if (frames.isEmpty()) return false
        val width = frames.first().width
        val height = frames.first().height
        if (frames.any { it.width != width || it.height != height }) return false

        val palette = buildPalette(frames, PALETTE_SIZE)
        val delay = (delayMs / 10).coerceIn(2, 65535)

        file.outputStream().use { out ->
            writeHeader(out, width, height, palette)
            writeLoopExtension(out)
            frames.forEach { frame ->
                writeFrame(out, frame, palette, delay)
            }
            out.write(0x3B) // trailer
        }
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------- palette --

    /**
     * Median cut: start with every sampled colour in one box, repeatedly split
     * the box with the widest channel spread at its median, until there are
     * `count` boxes. Each box then contributes its average colour.
     *
     * Sampled rather than exhaustive - a few thousand pixels describe a frame's
     * palette as well as a million do, and it keeps this linear.
     */
    private fun buildPalette(frames: List<Bitmap>, count: Int): IntArray {
        val samples = ArrayList<Int>(4096)
        val step = (frames.size / 8).coerceAtLeast(1)
        var index = 0
        while (index < frames.size && samples.size < 4096) {
            val frame = frames[index]
            val pixels = IntArray(frame.width * frame.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val stride = (pixels.size / 1024).coerceAtLeast(1)
            var p = 0
            while (p < pixels.size && samples.size < 4096) {
                samples.add(pixels[p])
                p += stride
            }
            index += step
        }
        if (samples.isEmpty()) return IntArray(count) { it or (it shl 8) or (it shl 16) }

        // Annotated explicitly: `listOf(samples)` infers a list of ArrayLists, and
        // the split boxes are plain Lists, which Kotlin will not mix.
        var boxes: List<List<Int>> = listOf(samples)
        while (boxes.size < count) {
            val target = boxes.indexOfFirst { it.size > 1 }
            if (target < 0) break
            val box = boxes[target]
            val split = splitBox(box)
            if (split == null) break
            boxes = boxes.toMutableList().apply {
                removeAt(target)
                add(split.first)
                add(split.second)
            }.toList()
        }

        val palette = IntArray(count)
        boxes.forEachIndexed { i, box ->
            var r = 0L; var g = 0L; var b = 0L
            box.forEach { pixel ->
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
            }
            val n = box.size.coerceAtLeast(1)
            palette[i] = ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
        return palette
    }

    /** Split along the widest channel, at the median. */
    private fun splitBox(box: List<Int>): Pair<List<Int>, List<Int>>? {
        var rMin = 255; var rMax = 0
        var gMin = 255; var gMax = 0
        var bMin = 255; var bMax = 0
        box.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r < rMin) rMin = r; if (r > rMax) rMax = r
            if (g < gMin) gMin = g; if (g > gMax) gMax = g
            if (b < bMin) bMin = b; if (b > bMax) bMax = b
        }
        val rSpan = rMax - rMin
        val gSpan = gMax - gMin
        val bSpan = bMax - bMin
        val shift = when {
            rSpan >= gSpan && rSpan >= bSpan -> 16
            gSpan >= bSpan -> 8
            else -> 0
        }
        val sorted = box.sortedBy { (it shr shift) and 0xFF }
        val middle = sorted.size / 2
        if (middle == 0) return null
        return sorted.subList(0, middle).toList() to sorted.subList(middle, sorted.size).toList()
    }

    /** Nearest palette entry, with a cache because frames repeat colours. */
    private class Mapper(private val palette: IntArray) {
        private val cache = HashMap<Int, Int>(1 shl 14)

        fun indexOf(pixel: Int): Int = cache.getOrPut(pixel) {
            var best = 0
            var bestDistance = Int.MAX_VALUE
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            for (i in palette.indices) {
                val pr = (palette[i] shr 16) and 0xFF
                val pg = (palette[i] shr 8) and 0xFF
                val pb = palette[i] and 0xFF
                val dr = r - pr; val dg = g - pg; val db = b - pb
                val distance = dr * dr + dg * dg + db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
            best
        }
    }

    // ---------------------------------------------------------------- file --

    private fun writeHeader(out: OutputStream, width: Int, height: Int, palette: IntArray) {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, width)
        writeShort(out, height)
        out.write(0xF7) // global table, 8 bits per pixel, 256 entries
        out.write(0)    // background index
        out.write(0)    // pixel aspect ratio
        for (i in 0 until 256) {
            val colour = palette.getOrElse(i) { 0 }
            out.write((colour shr 16) and 0xFF)
            out.write((colour shr 8) and 0xFF)
            out.write(colour and 0xFF)
        }
    }

    private fun writeLoopExtension(out: OutputStream) {
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1)
        writeShort(out, 0) // loop forever
        out.write(0)
    }

    private fun writeFrame(out: OutputStream, frame: Bitmap, palette: IntArray, delay: Int) {
        // Graphic control extension: the frame delay lives here.
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0) // no transparency, no disposal
        writeShort(out, delay)
        out.write(0) // transparent index
        out.write(0)

        out.write(0x2C) // image descriptor
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, frame.width)
        writeShort(out, frame.height)
        out.write(0) // no local table, not interlaced

        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        val mapper = Mapper(palette)
        val indices = ByteArray(pixels.size) { mapper.indexOf(pixels[it]).toByte() }
        writeLzw(out, indices, 8)
    }

    private fun writeShort(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    /**
     * GIF's variable-width LZW.
     *
     * Codes start one bit wider than the pixel depth, grow as the dictionary
     * fills, and reset when they reach 12 bits - at which point a clear code is
     * emitted and the dictionary starts again. Getting the reset wrong is the
     * classic way to produce a GIF that opens in one viewer and not another.
     */
    private fun writeLzw(out: OutputStream, indices: ByteArray, depth: Int) {
        val clearCode = 1 shl depth
        val endCode = clearCode + 1
        var codeSize = depth + 1
        var nextCode = endCode + 1

        val dictionary = HashMap<Long, Int>(1 shl 13)
        val block = java.io.ByteArrayOutputStream()
        var bitBuffer = 0
        var bitCount = 0

        fun emit(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                block.write(bitBuffer and 0xFF)
                bitBuffer = bitBuffer shr 8
                bitCount -= 8
            }
        }

        emit(clearCode)
        var prefix = -1
        for (value in indices) {
            val current = value.toInt() and 0xFF
            if (prefix < 0) {
                prefix = current
                continue
            }
            val key = (prefix.toLong() shl 8) or current.toLong()
            val found = dictionary[key]
            if (found != null) {
                prefix = found
            } else {
                emit(prefix)
                if (nextCode < 4096) {
                    dictionary[key] = nextCode
                    nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    emit(clearCode)
                    dictionary.clear()
                    codeSize = depth + 1
                    nextCode = endCode + 1
                }
                prefix = current
            }
        }
        if (prefix >= 0) emit(prefix)
        emit(endCode)
        if (bitCount > 0) block.write(bitBuffer and 0xFF)

        // Sub-blocks: a length byte, up to 255 bytes, terminated by a zero.
        val bytes = block.toByteArray()
        var offset = 0
        while (offset < bytes.size) {
            val size = (bytes.size - offset).coerceAtMost(255)
            out.write(size)
            out.write(bytes, offset, size)
            offset += size
        }
        out.write(0)
    }
}
