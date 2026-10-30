package com.vision.pos.printer.util

import android.util.Base64

/** Decodes the base64 ESC/POS stream the web app sends in `printRaw`. */
object Base64Util {
    fun decode(value: String): ByteArray {
        // Only switch to the URL-safe alphabet when the payload actually uses it, so
        // every input that decoded before keeps decoding byte for byte as it did.
        val urlSafe = value.indexOf('-') >= 0 || value.indexOf('_') >= 0
        return try {
            Base64.decode(value, if (urlSafe) Base64.URL_SAFE else Base64.DEFAULT)
        } catch (caught: IllegalArgumentException) {
            throw IllegalArgumentException("dataBase64 is not valid base64", caught)
        }
    }
}
