package com.tethershot.app.presets

import android.content.Context
import android.net.Uri
import com.tethershot.app.lut.CubeLut
import java.io.File
import java.io.IOException

data class Preset(val name: String, val file: File?) {
    companion object {
        val NONE = Preset("Original (no preset)", null)
    }
}

/** Stores imported .cube LUT presets in app-private storage. */
class PresetRepository(private val context: Context) {

    private val presetDir: File
        get() = File(context.filesDir, "presets").apply { mkdirs() }

    fun listPresets(): List<Preset> {
        val files = presetDir.listFiles { f -> f.extension.equals("cube", true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        return listOf(Preset.NONE) + files.map { Preset(it.nameWithoutExtension, it) }
    }

    /** Imports a .cube file picked via SAF. Validates by parsing before saving. */
    @Throws(IOException::class)
    fun importPreset(uri: Uri): Preset {
        val displayName = queryDisplayName(uri) ?: "preset_${System.currentTimeMillis()}.cube"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .let { if (it.endsWith(".cube", true)) it else "$it.cube" }

        val tmp = File.createTempFile("import", ".cube", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Cannot read file")
            tmp.inputStream().use { CubeLut.parse(it) } // validate
            val dest = File(presetDir, safeName)
            tmp.copyTo(dest, overwrite = true)
            return Preset(dest.nameWithoutExtension, dest)
        } finally {
            tmp.delete()
        }
    }

    fun deletePreset(preset: Preset) {
        preset.file?.delete()
    }

    @Throws(IOException::class)
    fun loadLut(preset: Preset): CubeLut? {
        val file = preset.file ?: return null
        return file.inputStream().use { CubeLut.parse(it, preset.name) }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }
}
