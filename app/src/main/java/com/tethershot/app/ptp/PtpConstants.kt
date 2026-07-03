package com.tethershot.app.ptp

object PtpConstants {
    // Container types
    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3
    const val TYPE_EVENT = 4

    // Operation codes (PTP standard - vendor independent)
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009

    // Response codes
    const val RSP_OK = 0x2001

    // Events
    const val EVT_OBJECT_ADDED = 0x4002

    // Object formats
    const val FMT_EXIF_JPEG = 0x3801

    const val USB_CLASS_STILL_IMAGE = 6
}
