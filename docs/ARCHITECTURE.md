# Architecture

## Shape of the app

One Activity, no fragments and no ViewModel. `MainActivity` owns a `WebView`, a
loading spinner and an error view; everything else is a small object graph.

```
MainActivity
  WebView (loads BuildConfig.POS_URL)
    addJavascriptInterface(NativePrinterBridge)  ->  window.__posPrinterNative
    evaluateJavascript(PosPrinterShim.JS)        ->  window.median.posPrinter
  NativePrinterBridge
    CoroutineScope(SupervisorJob + Dispatchers.IO)
    PrinterManager
      NetworkPrinterTransport     raw TCP, default port 9100
      UsbPrinterTransport         UsbManager bulk OUT
      BluetoothPrinterTransport   classic SPP, then BLE GATT via BlePrinterWriter
```

The shell has no idea what a receipt looks like. The web app renders the receipt,
builds the ESC/POS command stream and base64 encodes it; the shell decodes the bytes
and writes them verbatim.

## Why the bridge is asynchronous

`@JavascriptInterface` methods can only return primitives and run on a WebView
thread that must not block, so every command is a request/response pair:

1. The JS shim generates a request id and stores `{resolve, reject, callback}`.
2. It calls `window.__posPrinterNative.printRaw(id, jsonString)`.
3. `NativePrinterBridge` launches a coroutine on `Dispatchers.IO`, runs the command
   and serialises the result to JSON.
4. The result is posted back with `webView.post { evaluateJavascript(...) }`, which
   calls `window.__posPrinterResolve(id, json)` or `__posPrinterReject(id, message)`.

A thrown exception in step 3 becomes a rejection, so the page always settles its
promise: either a resolved `{ success: false, error }` or a rejected `Error`.

The shim is injected on both `onPageStarted` and `onPageFinished` and guards itself
with `window.__posPrinterShimReady`, so a reload or an in-page route change always
ends up with exactly one live bridge.

## Transports

| Transport | How | Failure mode |
| --- | --- | --- |
| `NETWORK` | `java.net.Socket` to `host:port`, 8 s connect timeout, then write and flush | Throws with the message the POS displays |
| `USB` | `UsbManager` device lookup, per-device permission prompt, `claimInterface`, first bulk OUT endpoint, 16 KB chunks | Denied permission or missing endpoint becomes a readable error |
| `BLUETOOTH` | `createRfcommSocketToServiceRecord` (SPP) first; on `IOException` or an LE-only device, BLE GATT to the first writable characteristic in 20 byte chunks | Unpaired device, dropped link or missing characteristic becomes a readable error |

`connect` and `disconnect` deliberately resolve `{ success: true }` without doing
work: every transport opens and closes its own link per job, which keeps printers
that sleep when idle from going stale between jobs.

## Lifecycle and failure handling

- **Bridge teardown.** `NativePrinterBridge.dispose()` cancels the scope from
  `onDestroy`, so nothing is still evaluating JavaScript against a destroyed WebView.
- **Renderer crash.** `onRenderProcessGone` returns `true` (otherwise the process is
  killed) and calls `recreate()`. The restart counters live in the companion object
  on purpose: `recreate()` builds a new Activity, so instance counters would reset
  and the throttle would never trip. After 3 restarts inside 60 seconds the shell
  shows the offline screen instead of looping.
- **Load errors.** `onReceivedError` only reacts to main frame failures and ignores
  `ERROR_UNKNOWN`, which WebView reports for aborted navigations. Subresource
  failures are ignored. Non-http(s) links are handed to the system.
- **System UI.** The status bar is hidden so the POS gets the full height, the
  display cutout is used (`shortEdges`, safe because the POS pads itself with
  `env(safe-area-inset-*)`), and `adjustResize` keeps form fields visible above the
  keyboard. The maintenance menu is a long press on the top strip, measured in
  `dispatchTouchEvent` without consuming the event, so the page keeps its own
  gestures.

## Where to change things

| Goal | File |
| --- | --- |
| Shell behaviour, error screen, gestures | `MainActivity.kt` |
| JS surface, request/response protocol | `bridge/PosPrinterShim.kt`, `bridge/NativePrinterBridge.kt` |
| Add a transport | `printer/PrinterTransport.kt` plus a new transport class, wired in `PrinterManager.kt` |
| Printer byte handling | the transports only move bytes; receipt bytes are the web app's job |
