# Median.co plugin spec: `median.posPrinter`

**Purpose.** Ship the POS web app as a Median.co (GoNative) app with silent raw
ESC/POS printing over Wi-Fi (`host:9100`), USB (OTG) and Bluetooth, with no Android
print dialog.

**What Median needs to build.** A private (custom) native plugin that exposes exactly
this JavaScript surface on the page:

```js
window.median.posPrinter = {
  connect(options),      // -> Promise, and options.callback(result)
  disconnect(options),
  discover(options),     // -> { success: true, devices: [{ id, name }] }
  printRaw(options)      // -> { success: true, bytes } | { success: false, error }
};
```

The web app already ships the client for this. It also accepts the legacy alias
`window.gonative.posPrinter`. No web changes are needed if the plugin matches this
contract.

---

## 1. Calling convention

- Every method receives one options object and may also carry a `callback`:
  `{ ...options, callback: (result) => void }`.
- The method returns a Promise and may also invoke `options.callback(result)`.
  Resolving on whichever happens first is fine.
- The POS treats a resolved `{ success: false, error }` as a failure and shows
  `error` to the operator verbatim.
- A thrown exception is also treated as a failure, and its message is shown.
- The client polls for up to 5 seconds after page load for `window.median.posPrinter`
  to appear, so the object has to exist early.

---

## 2. Methods

| Method | Options | Resolves with |
| --- | --- | --- |
| `connect` | none | `{ success: true }` |
| `disconnect` | none | `{ success: true }` |
| `discover` | `{ transport?: "NETWORK" \| "USB" \| "BLUETOOTH" }` | `{ success: true, devices: [{ id, name }] }` |
| `printRaw` | `{ transport, host?, port?, deviceId?, dataBase64, encoding: "base64" }` | `{ success: true, bytes: <n> }` |

Notes:

- `connect` and `disconnect` may be no-ops that resolve `{ success: true }`. The
  reference implementation opens a connection per print job.
- `discover` with `transport: "NETWORK"` returns an empty list, because the operator
  types the IP and port. Omit `transport` to return USB and Bluetooth devices.
- `printRaw.encoding` is always `"base64"`, and the bytes are the raw ESC/POS byte
  stream the web app produced.

---

## 3. `printRaw` payload

```ts
{
  transport: "NETWORK" | "USB" | "BLUETOOTH",  // required
  host?: string,        // NETWORK only, for example "192.168.1.50"
  port?: number,        // NETWORK only, default 9100
  deviceId?: string,    // USB or BLUETOOTH, from discover(), see section 4
  dataBase64: string,   // required, raw ESC/POS bytes, base64
  encoding: "base64"
}
```

The plugin decodes `dataBase64` and writes the bytes verbatim.

---

## 4. Device ids

The id has to be deterministic across discoveries, because the POS stores it and sends
it back as `printRaw.deviceId`.

| Transport | `discover().devices[].id` | `name` |
| --- | --- | --- |
| USB | `usb:<vendorId>:<productId>` lowercase hex, 4 digits each, for example `usb:04b8:0e15` | product or manufacturer name, else the id |
| Bluetooth | the MAC address, for example `66:12:AB:34:CD:EF` | device name, else the MAC |
| NETWORK | none, the list is empty | none |

Being tolerant of the label being sent back instead of the id is a nice-to-have.

---

## 5. Transport behaviour

**NETWORK (raw TCP on port 9100)**

- A missing host resolves as an error: `Network printer IP address is required`.
- The port defaults to 9100 and `tcpNoDelay` is on.
- Connect timeout 8 seconds, read/write timeout 15 seconds, then write, flush, close.

**USB (OTG) through `UsbManager`**

- Find the device by id, otherwise error `USB printer "<id>" is not connected`.
- Request the per-device runtime permission and wait for the operator, up to 30
  seconds. If it is denied: `USB permission was denied for <label>. Accept the system
  prompt and try again.`
- Open the device, claim interface 0, use the first bulk OUT endpoint (otherwise
  `No USB bulk OUT endpoint on <label>`), write in 16 KB chunks, then release and close.

**BLUETOOTH, classic SPP first with a BLE GATT fallback**

- Request `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` on demand, the first time Bluetooth
  is used, rather than at launch. If they are not granted:
  `Bluetooth permission is required. Grant "Nearby devices" access and try again.`
- Resolve the device by MAC or name. If it is not paired:
  `Bluetooth printer "<id>" is not paired`.
- Classic: `createRfcommSocketToServiceRecord(00001101-0000-1000-8000-00805F9B34FB)`,
  call `cancelDiscovery()` before `connect()`, then write and flush.
- If SPP fails with an `IOException`, or the device is LE only: connect with BLE,
  discover services, pick the first writable characteristic (`WRITE`, else
  `WRITE_NO_RESPONSE`; common services are FFF0/FFE1 and 18F0/2AF1) and write in
  20 byte chunks. Connect timeout 15 seconds, per-write timeout 10 seconds. With no
  writable characteristic: `No writable BLE characteristic on <address>`.

---

## 6. Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />

<!-- Classic Bluetooth, API 30 and below -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

<!-- Bluetooth, API 31 and above, requested at runtime -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />

<uses-feature android:name="android.hardware.usb.host" android:required="false" />
<uses-feature android:name="android.hardware.bluetooth" android:required="false" />
```

- Request the Bluetooth permissions on demand rather than at launch, so Wi-Fi or USB
  only venues are never prompted.
- USB permission is a separate per-device prompt, requested at print time.
- `neverForLocation` satisfies the Play policy for `BLUETOOTH_SCAN`, because no
  location is derived.
- The POS is served over HTTPS, so no cleartext exception is needed.

---

## 7. Reference implementation

| File | Role |
| --- | --- |
| `app/src/main/java/com/vision/pos/printer/bridge/NativePrinterBridge.kt` | `@JavascriptInterface` entry points and promise resolution |
| `app/.../bridge/PosPrinterShim.kt` | JS shim that publishes `window.median.posPrinter` |
| `app/.../printer/PrinterManager.kt` | routes a call by transport |
| `app/.../printer/NetworkPrinterTransport.kt` | TCP 9100 |
| `app/.../printer/UsbPrinterTransport.kt` | USB bulk OUT and permission prompt |
| `app/.../printer/BluetoothPrinterTransport.kt` | classic SPP |
| `app/.../printer/BlePrinterWriter.kt` | BLE GATT fallback |
| `app/.../util/Base64Util.kt` | base64 to bytes |
| `app/src/main/AndroidManifest.xml` | permissions and USB attach filter |
| `README.md`, `docs/ARCHITECTURE.md` | project context |

The Android app can be copied as is. It carries no third-party dependencies beyond
AndroidX and kotlinx-coroutines.

**iOS.** This repository is Android only. iOS needs its own implementation
(NetworkExtension or a raw TCP socket, ExternalAccessory for USB, CoreBluetooth for
Bluetooth) exposing the same JS contract.

---

## 8. Acceptance tests

1. The page loads and `typeof window.median.posPrinter.printRaw === "function"`.
2. `discover({})` resolves `{ success: true, devices: [...] }` with USB and paired
   Bluetooth devices.
3. `discover({ transport: "NETWORK" })` resolves `{ success: true, devices: [] }`.
4. Wi-Fi: `printRaw({ transport: "NETWORK", host, port: 9100, dataBase64, encoding: "base64" })`
   prints silently and resolves `{ success: true, bytes: n }`, with no OS dialog.
5. USB: after the system prompt is accepted the slip prints. Denying it resolves
   `{ success: false, error: "USB permission was denied for ..." }`.
6. Bluetooth: a paired SPP printer prints, and so does a BLE-only printer.
7. Failure shape: an unplugged printer resolves `{ success: false, error: "..." }`,
   never a crash and never a silent hang.
8. In the POS UI, Settings then Printer shows the badge "Android direct printing
   ready", and Test print succeeds.

---

## 9. Drop-in integration

If you own the WebView build, the bridge is three edits. It is plain Kotlin plus one
JS string.

**(a) Copy these classes** into your app's source tree, keeping the package layout:
`bridge/NativePrinterBridge.kt`, `bridge/PosPrinterShim.kt`,
`printer/{PrinterManager,PrinterTransport,PrinterDevice,NetworkPrinterTransport,UsbPrinterTransport,BluetoothPrinterTransport,BlePrinterWriter}.kt`
and `util/Base64Util.kt`.

**(b) Register and inject in your WebView host**, mirroring `MainActivity.kt`:

```kotlin
webView.settings.javaScriptEnabled = true
webView.addJavascriptInterface(
    NativePrinterBridge(
        webView,
        PrinterManager(applicationContext) { /* suspend: request Bluetooth permissions */ true },
    ),
    NativePrinterBridge.BRIDGE_NAME,          // "__posPrinterNative"
)
webView.webViewClient = object : WebViewClient() {
    override fun onPageStarted(v: WebView, u: String?, f: Bitmap?) { v.evaluateJavascript(PosPrinterShim.JS, null) }
    override fun onPageFinished(v: WebView, u: String?)            { v.evaluateJavascript(PosPrinterShim.JS, null) }
}
webView.loadUrl(BuildConfig.POS_URL)
```

**(c) Manifest and runtime.** Add the permissions from section 6, then request
`BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` when a Bluetooth print or discovery is first
requested, the way `MainActivity.requestBluetoothPermission()` does.

If you enable R8, keep the bridge class:

```proguard
-keep class com.vision.pos.printer.bridge.NativePrinterBridge { *; }
-keepclassmembers class com.vision.pos.printer.bridge.NativePrinterBridge { public *; }
```