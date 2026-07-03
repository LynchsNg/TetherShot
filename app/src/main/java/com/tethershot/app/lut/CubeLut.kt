package com.tethershot.app.lut

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * A parsed 3D LUT from a .cube file.
 * Data layout: red fastest, then green, then blue (standard .cube ordering).
 */
class CubeLut(
    val title: String,
    val size: Int,
    val data: FloatArray, // size^3 * 3 floats (r,g,b)
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f)
) {
    companion object {
        @Throws(IOException::class)
        fun parse(input: InputStream, fallbackTitle: String = "LUT"): CubeLut {
            var title = fallbackTitle
            var size = 0
            var domainMin = floatArrayOf(0f, 0f, 0f)
            var domainMax = floatArrayOf(1f, 1f, 1f)
            var data: FloatArray? = null
            var idx = 0

            BufferedReader(InputStreamReader(input)).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val parts = line.split(Regex("\\s+"))
                    when {
                        parts[0].equals("TITLE", true) -> {
                            title = line.substringAfter(parts[0]).trim().trim('"')
                        }
                        parts[0].equals("LUT_3D_SIZE", true) -> {
                            size = parts[1].toInt()
                            data = FloatArray(size * size * size * 3)
                        }
                        parts[0].equals("LUT_1D_SIZE", true) ->
                            throw IOException("1D LUTs are not supported")
                        parts[0].equals("DOMAIN_MIN", true) ->
                            domainMin = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                        parts[0].equals("DOMAIN_MAX", true) ->
                            domainMax = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                        parts.size >= 3 && parts[0].toFloatOrNull() != null -> {
                            val d = data ?: throw IOException("LUT data before LUT_3D_SIZE")
                            if (idx + 2 < d.size) {
                                d[idx++] = parts[0].toFloat()
                                d[idx++] = parts[1].toFloat()
                                d[idx++] = parts[2].toFloat()
                            }
                        }
                    }
                }
            }
            val d = data ?: throw IOException("Missing LUT_3D_SIZE")
            if (idx != d.size) throw IOException("Incomplete LUT data: $idx/${d.size}")
            return CubeLut(title, size, d, domainMin, domainMax)
        }
    }
}
