package com.tethershot.app.lut

import android.graphics.Bitmap

/** Applies a 3D LUT to a bitmap using trilinear interpolation. */
object LutProcessor {

    fun apply(src: Bitmap, lut: CubeLut): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val n = lut.size
        val maxIdx = n - 1
        val data = lut.data
        val scale = maxIdx / 255f

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            val r = (c shr 16 and 0xFF) * scale
            val g = (c shr 8 and 0xFF) * scale
            val b = (c and 0xFF) * scale

            val r0 = r.toInt().coerceAtMost(maxIdx - 1).coerceAtLeast(0)
            val g0 = g.toInt().coerceAtMost(maxIdx - 1).coerceAtLeast(0)
            val b0 = b.toInt().coerceAtMost(maxIdx - 1).coerceAtLeast(0)
            val fr = r - r0
            val fg = g - g0
            val fb = b - b0

            var outR = 0f
            var outG = 0f
            var outB = 0f
            for (corner in 0 until 8) {
                val dr = corner and 1
                val dg = corner shr 1 and 1
                val db = corner shr 2 and 1
                val wgt = (if (dr == 1) fr else 1 - fr) *
                    (if (dg == 1) fg else 1 - fg) *
                    (if (db == 1) fb else 1 - fb)
                if (wgt == 0f) continue
                val idx = (((b0 + db) * n + (g0 + dg)) * n + (r0 + dr)) * 3
                outR += wgt * data[idx]
                outG += wgt * data[idx + 1]
                outB += wgt * data[idx + 2]
            }

            val ri = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gi = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bi = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
