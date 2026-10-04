package com.vision.pos.printer.printer

import org.json.JSONObject

/**
 * A discovered printer. `id` is the stable handle the web app stores and sends
 * back in `printRaw.deviceId`, so it must stay the same across discoveries.
 */
data class PrinterDevice(val id: String, val name: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
}
