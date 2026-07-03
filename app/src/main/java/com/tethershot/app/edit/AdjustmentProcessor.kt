package com.tethershot.app.edit

import android.graphics.Bitmap
import kotlin.math.pow
import kotlin.random.Random

/**
 * Applies [EditSettings] to a bitmap in a single pixel pass plus optional
 * convolution passes (skin smoothing, sharpening). Non-destructive: always
 * returns a new bitmap and never mutates the input.
 */
object AdjustmentProcessor {

    fun apply(src: Bitmap, s: EditSettings): Bitmap {
        if (s.isNeutral) return src
        val w = src.width
        val h = src.height
        var pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        if (s.skinSmooth > 0) pixels = skinSmooth(pixels, w, h, s.skinSmooth / 100f)

        applyColorPass(pixels, s)

        if (s.sharpen > 0) pixels = unsharpMask(pixels, w, h, s.sharpen / 100f)
        if (s.grain > 0) applyGrain(pixels, s.grain / 100f)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun applyColorPass(pixels: IntArray, s: EditSettings) {
        // Precompute per-channel curves: exposure gain, white-balance gains, contrast S-curve.
        val evGain = 2f.pow(s.exposure / 50f)          // ±2 EV
        val temp = s.temperature / 100f
        val tint = s.tint / 100f
        val rGain = evGain * (1f + 0.25f * temp) * (1f + 0.10f * tint)
        val gGain = evGain * (1f - 0.15f * tint)
        val bGain = evGain * (1f - 0.25f * temp) * (1f + 0.10f * tint)
        val contrast = 1f + s.contrast / 100f

        val lutR = IntArray(256)
        val lutG = IntArray(256)
        val lutB = IntArray(256)
        for (v in 0..255) {
            lutR[v] = curve(v, rGain, contrast)
            lutG[v] = curve(v, gGain, contrast)
            lutB[v] = curve(v, bGain, contrast)
        }

        val sat = s.saturation / 100f
        val vib = s.vibrance / 100f
        val needsSat = sat != 0f || vib != 0f

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c and -0x1000000
            var r = lutR[c shr 16 and 0xFF]
            var g = lutG[c shr 8 and 0xFF]
            var b = lutB[c and 0xFF]

            if (needsSat) {
                val luma = (0.299f * r + 0.587f * g + 0.114f * b)
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val currentSat = if (maxC > 0) (maxC - minC) / maxC.toFloat() else 0f
                // Vibrance boosts low-saturation pixels more than already-vivid ones.
                val amount = sat + vib * (1f - currentSat)
                if (amount != 0f) {
                    val f = 1f + amount
                    r = clamp255((luma + (r - luma) * f).toInt())
                    g = clamp255((luma + (g - luma) * f).toInt())
                    b = clamp255((luma + (b - luma) * f).toInt())
                }
            }
            pixels[i] = a or (r shl 16) or (g shl 8) or b
        }
    }

    private fun curve(v: Int, gain: Float, contrast: Float): Int {
        var f = v / 255f * gain
        f = (f - 0.5f) * contrast + 0.5f
        return clamp255((f * 255f + 0.5f).toInt())
    }

    /**
     * Surface-blur style smoothing restricted to skin-tone pixels: blends each
     * skin pixel toward a box-blurred version, preserving strong edges.
     */
    private fun skinSmooth(pixels: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val radius = (2 + strength * 6).toInt()
        val blurred = boxBlur(pixels, w, h, radius)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            val weight = if (isSkinTone(r, g, b)) {
                val bc = blurred[i]
                val diff = maxOf(
                    kotlin.math.abs(r - (bc shr 16 and 0xFF)),
                    kotlin.math.abs(g - (bc shr 8 and 0xFF)),
                    kotlin.math.abs(b - (bc and 0xFF))
                )
                // Preserve edges: strong differences (eyes, hair borders) keep original.
                if (diff > 40) 0f else strength * (1f - diff / 40f)
            } else 0f
            out[i] = if (weight <= 0f) c else blend(c, blurred[i], weight)
        }
        return out
    }

    private fun isSkinTone(r: Int, g: Int, b: Int): Boolean =
        r > 60 && g > 35 && b > 20 &&
            r > b && r > g - 15 &&
            (maxOf(r, g, b) - minOf(r, g, b)) > 10 &&
            kotlin.math.abs(r - g) > 10

    private fun blend(c1: Int, c2: Int, t: Float): Int {
        val r = ((c1 shr 16 and 0xFF) * (1 - t) + (c2 shr 16 and 0xFF) * t).toInt()
        val g = ((c1 shr 8 and 0xFF) * (1 - t) + (c2 shr 8 and 0xFF) * t).toInt()
        val b = ((c1 and 0xFF) * (1 - t) + (c2 and 0xFF) * t).toInt()
        return (c1 and -0x1000000) or (r shl 16) or (g shl 8) or b
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0; var count = 0
            for (x in -radius..radius) {
                val xi = x.coerceIn(0, w - 1)
                val c = src[row + xi]
                sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF; count++
            }
            for (x in 0 until w) {
                tmp[row + x] = (src[row + x] and -0x1000000) or
                    (sr / count shl 16) or (sg / count shl 8) or (sb / count)
                val outX = (x - radius).coerceIn(0, w - 1)
                val inX = (x + radius + 1).coerceIn(0, w - 1)
                val co = src[row + outX]; val ci = src[row + inX]
                sr += (ci shr 16 and 0xFF) - (co shr 16 and 0xFF)
                sg += (ci shr 8 and 0xFF) - (co shr 8 and 0xFF)
                sb += (ci and 0xFF) - (co and 0xFF)
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0; var count = 0
            for (y in -radius..radius) {
                val yi = y.coerceIn(0, h - 1)
                val c = tmp[yi * w + x]
                sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF; count++
            }
            for (y in 0 until h) {
                out[y * w + x] = (tmp[y * w + x] and -0x1000000) or
                    (sr / count shl 16) or (sg / count shl 8) or (sb / count)
                val outY = (y - radius).coerceIn(0, h - 1)
                val inY = (y + radius + 1).coerceIn(0, h - 1)
                val co = tmp[outY * w + x]; val ci = tmp[inY * w + x]
                sr += (ci shr 16 and 0xFF) - (co shr 16 and 0xFF)
                sg += (ci shr 8 and 0xFF) - (co shr 8 and 0xFF)
                sb += (ci and 0xFF) - (co and 0xFF)
            }
        }
        return out
    }

    private fun unsharpMask(pixels: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val blurred = boxBlur(pixels, w, h, 1)
        val out = IntArray(pixels.size)
        val k = amount * 1.5f
        for (i in pixels.indices) {
            val c = pixels[i]
            val bc = blurred[i]
            val r = clamp255(((c shr 16 and 0xFF) + k * ((c shr 16 and 0xFF) - (bc shr 16 and 0xFF))).toInt())
            val g = clamp255(((c shr 8 and 0xFF) + k * ((c shr 8 and 0xFF) - (bc shr 8 and 0xFF))).toInt())
            val b = clamp255(((c and 0xFF) + k * ((c and 0xFF) - (bc and 0xFF))).toInt())
            out[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun applyGrain(pixels: IntArray, amount: Float) {
        val rnd = Random(42)
        val range = (amount * 24).toInt()
        if (range == 0) return
        for (i in pixels.indices) {
            val n = rnd.nextInt(-range, range + 1)
            val c = pixels[i]
            val r = clamp255((c shr 16 and 0xFF) + n)
            val g = clamp255((c shr 8 and 0xFF) + n)
            val b = clamp255((c and 0xFF) + n)
            pixels[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
}
