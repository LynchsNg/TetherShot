package com.tethershot.app.edit

data class Filter(val name: String, val settings: EditSettings)

/** Built-in color looks applied on top of the user's manual adjustments. */
object FilterLibrary {
    val NONE = Filter("Không filter", EditSettings.NEUTRAL)

    val ALL: List<Filter> = listOf(
        NONE,
        Filter("Ấm áp (Warm)", EditSettings(temperature = 35, vibrance = 15, contrast = 5)),
        Filter("Lạnh (Cool)", EditSettings(temperature = -35, contrast = 8)),
        Filter("Rực rỡ (Vivid)", EditSettings(saturation = 30, contrast = 15, sharpen = 15)),
        Filter("Nhẹ nhàng (Soft)", EditSettings(contrast = -15, exposure = 8, saturation = -10)),
        Filter("Phim (Film)", EditSettings(contrast = 12, saturation = -15, temperature = 12, grain = 25)),
        Filter("Đen trắng (B&W)", EditSettings(saturation = -100, contrast = 18)),
        Filter("Chân dung (Portrait)", EditSettings(temperature = 12, exposure = 5, skinSmooth = 35, vibrance = 10)),
        Filter("Hoài cổ (Retro)", EditSettings(temperature = 25, tint = 12, contrast = -8, saturation = -20, grain = 35))
    )
}
