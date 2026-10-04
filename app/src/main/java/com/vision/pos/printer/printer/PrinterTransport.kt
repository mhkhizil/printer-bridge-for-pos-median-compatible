package com.vision.pos.printer.printer

/** Mirrors the web app's `PrinterTransport` union. */
enum class PrinterTransport(val wireName: String) {
    NETWORK("NETWORK"),
    USB("USB"),
    BLUETOOTH("BLUETOOTH");

    companion object {
        fun fromWire(value: String?): PrinterTransport? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
    }
}
