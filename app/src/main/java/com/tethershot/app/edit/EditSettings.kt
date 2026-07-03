package com.tethershot.app.edit

/**
 * Non-destructive adjustment parameters. All values default to neutral (no-op).
 * Ranges are UI-friendly integers, converted internally by [AdjustmentProcessor].
 */
data class EditSettings(
    val exposure: Int = 0,      // -100..100  (~ -2EV..+2EV)
    val contrast: Int = 0,      // -100..100
    val saturation: Int = 0,    // -100..100
    val temperature: Int = 0,   // -100 (cool) .. 100 (warm)
    val tint: Int = 0,          // -100 (green) .. 100 (magenta)
    val vibrance: Int = 0,      // -100..100
    val sharpen: Int = 0,       // 0..100
    val grain: Int = 0,         // 0..100
    val skinSmooth: Int = 0     // 0..100
) {
    val isNeutral: Boolean
        get() = this == NEUTRAL

    /** Combines two adjustment sets (e.g. a filter on top of manual edits). */
    operator fun plus(other: EditSettings) = EditSettings(
        exposure = (exposure + other.exposure).coerceIn(-100, 100),
        contrast = (contrast + other.contrast).coerceIn(-100, 100),
        saturation = (saturation + other.saturation).coerceIn(-100, 100),
        temperature = (temperature + other.temperature).coerceIn(-100, 100),
        tint = (tint + other.tint).coerceIn(-100, 100),
        vibrance = (vibrance + other.vibrance).coerceIn(-100, 100),
        sharpen = (sharpen + other.sharpen).coerceIn(0, 100),
        grain = (grain + other.grain).coerceIn(0, 100),
        skinSmooth = (skinSmooth + other.skinSmooth).coerceIn(0, 100)
    )

    companion object {
        val NEUTRAL = EditSettings()
    }
}
