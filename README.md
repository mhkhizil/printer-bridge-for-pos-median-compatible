# Vision POS — Android print bridge

> **Handoff notes:** see [docs/NEXT_STEPS.md](docs/NEXT_STEPS.md) for build/run steps and known limitations.

A tiny Android **WebView shell** that gives the Vision POS web app the same silent
raw-ESC/POS printing it already has on desktop via QZ Tray — **on Android**, over
Wi‑Fi (`host:9100`), USB (OTG) and Bluetooth.

The web app already ships the client for this. This repo only supplies the native
side: an object exposed to the page as `window.median.posPrinter`, exactly matching
the contract the web code calls.

```
Web app (src/lib/printing/webPrinterTransports.ts)
  window.median.posPrinter.printRaw({ transport, host, port, deviceId, dataBase64, encoding })
        │  (JS shim: Promise + options.callback)
        ▼
window.__posPrinterNative  ──►  NativePrinterBridge (Kotlin, @JavascriptInterface)
                                     │
                                     ▼
                            PrinterManager
                     ┌───────────────┼────────────────┐
                 Network (TCP 9100)  USB (bulk)   Bluetooth (SPP / BLE)
```

## Contract implemented

`window.median.posPrinter` with four methods. **Every method takes ONE options
object** and returns a **Promise** (and also invokes `options.callback(result)`):

| Method | Options | Resolves with |
| --- | --- | --- |
| `connect` | — | `{ success: true }` |
| `disconnect` | — | `{ success: true }` |
| `discover` | `{ transport?: "NETWORK"\|"USB"\|"BLUETOOTH" }` | `{ success: true, devices: [{ id, name }] }` |
| `printRaw` | `{ transport, host?, port?, deviceId?, dataBase64, encoding: "base64" }` | `{ success: true, bytes }` |

- `dataBase64` is the **raw ESC/POS byte stream** produced by the web app; it is
  decoded and written to the printer verbatim.
- Failures resolve as `{ success: false, error: "…" }` → the shim rejects with an
  `Error`, which the POS UI shows verbatim.
- `discover` returns a **stable `id`**; `printRaw.deviceId` is matched against it.
  - USB → `usb:04b8:0e15` (VID:PID hex).
  - Bluetooth → the **MAC address** (e.g. `66:12:AB:34:CD:EF`).

## Project layout

```
app/src/main/
  AndroidManifest.xml                       permissions + USB attach filter
  java/com/vision/pos/printer/
    MainActivity.kt                         WebView shell, injects the bridge
    bridge/
      NativePrinterBridge.kt                @JavascriptInterface → PrinterManager
      PosPrinterShim.kt                     JS shim → window.median.posPrinter
    printer/
      PrinterManager.kt                     routes by transport
      PrinterTransport.kt                   NETWORK | USB | BLUETOOTH
      PrinterDevice.kt                      { id, name }
      NetworkPrinterTransport.kt            raw TCP (default port 9100)
      UsbPrinterTransport.kt                UsbManager bulk OUT, 16 KB chunks
      BluetoothPrinterTransport.kt          classic RFCOMM/SPP
      BlePrinterWriter.kt                   BLE GATT fallback
    util/Base64Util.kt                      base64 → ByteArray
  res/…                                     theme, icon, layout, USB filter
```

## Configure the POS URL

`app/build.gradle.kts`:

```kotlin
buildConfigField("String", "POS_URL", "\"https://vision.example.com\"")
```

Set this to the deployed POS web app (the same URL your desktop POS uses). The
WebView loads it on start.

## Build & run

**Easiest:** open this folder in **Android Studio** (Ladybug/2024.2 or newer) and
press ▶ Run. Studio generates the Gradle wrapper jar for you.

**Command line** (requires a Gradle install or a generated wrapper):

```bash
# one-time, if gradle is on your PATH
gradle wrapper --gradle-version 8.7

./gradlew :app:assembleDebug          # debug APK
./gradlew :app:installDebug           # install on a connected device
./gradlew :app:assembleRelease        # unsigned/minified-off release APK
```

`compileSdk 34`, `minSdk 24`, `targetSdk 34`, Kotlin 1.9.24, AGP 8.5.2.

> The binary `gradle/wrapper/gradle-wrapper.jar` is intentionally not committed —
> Android Studio (or `gradle wrapper`) creates it on first open.

## Android permissions

Declared in `AndroidManifest.xml`:

- `INTERNET` — load the POS web app.
- `BLUETOOTH` / `BLUETOOTH_ADMIN` (≤ API 30) and `BLUETOOTH_CONNECT` /
  `BLUETOOTH_SCAN` (API 31+) — requested at runtime on first launch.
- `<uses-feature android:name="android.hardware.usb.host" />` plus a
  `USB_DEVICE_ATTACHED` intent-filter (`res/xml/usb_device_filter.xml`) so the
  app is offered when a receipt printer is plugged in.

USB device permission is a **separate, per-device** system prompt handled at print
time (`UsbPrinterTransport.ensurePermission`) — accept it once per printer.

## Testing checklist (on a physical device)

1. Install and open the app — the POS web app loads.
2. In POS **Settings → Printer**, create/edit a printer and tap **Test print**:
   - A badge reading **“Android direct printing ready”** confirms the bridge is live.
3. **Wi‑Fi printer:** choose Network, enter the printer IP + port (9100) → Test
   print. Expect a silent print, **no Android print dialog**.
4. **USB printer:** connect via OTG → **Discover** → pick the device → accept the
   USB permission prompt → Test print.
5. **Bluetooth printer:** pair it in Android Settings first → **Discover** → pick
   it → Test print.
6. Confirm the receipt/kitchen bytes match the desktop (QZ Tray) output.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Badge shows “Browser fallback” | The shim did not run. Reload the page; confirm `window.median.posPrinter` exists in Chrome DevTools (`chrome://inspect`). |
| `Android direct printing needs the POS printer native plugin…` | The web app is open outside this shell, or the bridge failed to inject. |
| Wi‑Fi print fails instantly | Wrong IP/port, printer off, or the tablet is on a different subnet/VLAN than the printer. |
| USB: “permission was denied” | Re-run and accept the prompt; some ROMs need *USB debugging*/OTG enabled. |
| USB: “No USB bulk OUT endpoint” | The device is not a printer (or exposes only a vendor interface). Try a different model/port. |
| Bluetooth: “permission is required” | Grant **Nearby devices** in app settings, then retry. |
| Bluetooth LE printer does nothing | Classic SPP was rejected and the BLE fallback found no writable characteristic; share the printer model. |

## Using Median instead of this wrapper (optional)

If you ship the app through **Median.co** rather than this shell, ask them for a
**private native plugin** that exposes the same `median.posPrinter` contract, and
enable the plugin + JavaScript Bridge in App Studio. The web app needs no changes —
it detects `window.median.posPrinter` either way.

## Caveats

- The **BLE GATT** path is a best-effort generic writer (first writable
  characteristic). Classic SPP is the primary, well-tested path for ESC/POS.
- `cancelDiscovery` is called before every Bluetooth connect to avoid flaky SPP
  handshakes.
- Release builds keep minification **off** by default; if you enable R8, keep the
  bridge class (see `app/proguard-rules.pro`).

