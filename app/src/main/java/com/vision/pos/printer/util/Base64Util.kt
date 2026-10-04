package com.vision.pos.printer.util

import android.util.Base64

/** Decodes the base64 ESC/POS stream the web app sends in `printRaw`. */
object Base64Util {
    fun decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)
}
