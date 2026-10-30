package com.vision.pos.printer.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.vision.pos.printer.printer.PrinterManager
import com.vision.pos.printer.printer.PrinterTransport
import com.vision.pos.printer.util.Base64Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The native object exposed to the WebView as `window.__posPrinterNative`.
 *
 * `@JavascriptInterface` can only return primitives, so every command is
 * asynchronous: the shim passes a request id, and this class replies by
 * evaluating `window.__posPrinterResolve/__posPrinterReject`.
 */
class NativePrinterBridge(
    private val webView: WebView,
    private val manager: PrinterManager,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    /** Set by [dispose] so no reply is evaluated against a destroyed WebView. */
    @Volatile
    private var disposed = false

    /**
     * Cancels in-flight work. Call from the host's `onDestroy` so nothing keeps
     * running (or evaluates JS) against a dead WebView.
     */
    fun dispose() {
        disposed = true
        job.cancel()
    }

    /**
     * connect/disconnect have nothing to configure, because each transport opens its
     * own link per job. The parameter is ignored but kept so the signature still
     * matches the `median.posPrinter` contract.
     */
    @JavascriptInterface
    fun connect(requestId: String, @Suppress("UNUSED_PARAMETER") optionsJson: String) {
        run(requestId) { success() }
    }

    @JavascriptInterface
    fun disconnect(requestId: String, @Suppress("UNUSED_PARAMETER") optionsJson: String) {
        run(requestId) { success() }
    }

    @JavascriptInterface
    fun discover(requestId: String, optionsJson: String) {
        run(requestId) {
            val transport = PrinterTransport.fromWire(options(optionsJson).optString("transport"))
            val devices = JSONArray()
            manager.discover(transport).forEach { devices.put(it.toJson()) }
            JSONObject().put("success", true).put("devices", devices)
        }
    }

    @JavascriptInterface
    fun printRaw(requestId: String, optionsJson: String) {
        run(requestId) {
            val payload = options(optionsJson)
            val transport = PrinterTransport.fromWire(payload.optString("transport"))
                ?: error("printRaw requires a transport (NETWORK, USB or BLUETOOTH)")
            val dataBase64 = requireNotNull(payload.stringOrNull("dataBase64")) {
                "printRaw requires dataBase64"
            }

            val bytes = Base64Util.decode(dataBase64)
            manager.printRaw(
                transport = transport,
                host = payload.stringOrNull("host"),
                port = if (payload.has("port")) payload.optInt("port") else null,
                deviceId = payload.stringOrNull("deviceId"),
                bytes = bytes,
            )
            JSONObject().put("success", true).put("bytes", bytes.size)
        }
    }

    private fun success(): JSONObject = JSONObject().put("success", true)

    private fun options(optionsJson: String): JSONObject =
        if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)

    /**
     * `optString` renders a JSON `null` as the text "null", which would then be used
     * as a host name, a device id or base64 data. Treat absent, null and blank alike.
     */
    private fun JSONObject.stringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun run(requestId: String, action: suspend () -> JSONObject) {
        scope.launch {
            try {
                resolve(requestId, action())
            } catch (cancelled: CancellationException) {
                // dispose() cancelled us: not a command failure, and there is no
                // document left to show it to.
                throw cancelled
            } catch (caught: Throwable) {
                Log.w(TAG, "posPrinter command failed", caught)
                reject(requestId, caught.message ?: caught.javaClass.simpleName)
            }
        }
    }

    private fun resolve(requestId: String, result: JSONObject) = evaluate(
        "window.__posPrinterResolve && window.__posPrinterResolve(" +
            "${JSONObject.quote(requestId)}, ${JSONObject.quote(result.toString())});"
    )

    private fun reject(requestId: String, message: String) = evaluate(
        "window.__posPrinterReject && window.__posPrinterReject(" +
            "${JSONObject.quote(requestId)}, ${JSONObject.quote(message)});"
    )

    private fun evaluate(script: String) {
        if (disposed) return
        webView.post { if (!disposed) webView.evaluateJavascript(script, null) }
    }

    companion object {
        const val BRIDGE_NAME = "__posPrinterNative"
        private const val TAG = "NativePrinterBridge"
    }
}
