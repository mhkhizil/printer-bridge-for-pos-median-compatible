# POS Android print bridge

[![Android CI](https://github.com/mhkhizil/printer-bridge-for-pos-median-compatible/actions/workflows/android.yml/badge.svg)](https://github.com/mhkhizil/printer-bridge-for-pos-median-compatible/actions/workflows/android.yml)

An Android WebView shell that loads a POS web app and gives it silent, raw ESC/POS
printing over Wi-Fi, USB (OTG) and Bluetooth. No Android print dialog and no "share
to printer" detour: the page hands over a base64 ESC/POS byte stream and those bytes
land on the printer.

This is a thin native layer, not a POS. The web app keeps its own UI, sign-in and
receipt layout; this project only implements the printing bridge the page calls. It
was extracted from a POS deployment running on counter tablets in landscape.

## Why it exists

A browser cannot open a raw socket to a printer, cannot talk to a USB device, and
cannot hold a Bluetooth serial link, so a web POS quietly loses its printing the
moment it runs on a tablet instead of a desktop. This shell fixes that by exposing
one JavaScript object to the page and implementing it natively.

A web app needs no changes if it already calls that contract, which follows the
convention used by the Median.co GoNative JavaScript bridge.

## What you get

- `window.median.posPrinter.{connect,disconnect,discover,printRaw}`, a Promise based
  bridge that also invokes `options.callback(result)` for callers that prefer it.
- Three transports: raw TCP (port 9100), USB bulk transfer, and Bluetooth using
  classic SPP first with a BLE GATT fallback.
- A hardened WebView: no file or content provider access, no geolocation, cleartext
  HTTP disabled, non-http schemes handed to the system, `allowBackup="false"`.
- Recovery for the failures that actually happen on cheap tablets: an offline screen
  with Retry, a hidden reload menu, and WebView renderer crash recovery.
- One Activity and one dependency set (AndroidX core, appcompat, activity,
  kotlinx-coroutines). No analytics, no network calls of its own.

## Requirements

| | |
| --- | --- |
| JDK | 17 or newer, on `JAVA_HOME` |
| Android SDK | `platforms;android-34`, `build-tools;34.0.0` |
| Device | Android 7.0 (API 24) or newer |
| Gradle | not required, the wrapper is committed (Gradle 8.7) |

## Quick start

```bash
git clone https://github.com/mhkhizil/printer-bridge-for-pos-median-compatible.git
cd printer-bridge-for-pos-median-compatible
```

Create `local.properties` in the project root (it is git-ignored):

```properties
sdk.dir=/path/to/Android/Sdk
posUrl=https://your-pos.example.com
```

Then build and install:

```bash
./gradlew :app:installDebug      # or :app:assembleDebug for the APK only
```

`posUrl` is the address the WebView loads on start. It can also be overridden per
build without touching any file:

```bash
./gradlew :app:assembleDebug -PposUrl=https://your-pos.example.com
POS_URL=https://your-pos.example.com ./gradlew :app:assembleDebug
```

Resolution order, highest first: `-PposUrl`, the `POS_URL` environment variable,
`posUrl` in `local.properties`, then the placeholder default in
`app/build.gradle.kts`. The value ends up in `BuildConfig.POS_URL`.

## How the bridge works

The page talks to one native object. `@JavascriptInterface` methods can only return
primitives, so every call is asynchronous: the JS shim sends a request id and the
native side answers through `window.__posPrinterResolve/Reject`.

| Method | Options | Resolves with |
| --- | --- | --- |
| `connect` | none | `{ success: true }` |
| `disconnect` | none | `{ success: true }` |
| `discover` | `{ transport?: "NETWORK" \| "USB" \| "BLUETOOTH" }` | `{ success: true, devices: [{ id, name }] }` |
| `printRaw` | `{ transport, host?, port?, deviceId?, dataBase64, encoding: "base64" }` | `{ success: true, bytes }` |

- `dataBase64` is the raw ESC/POS byte stream. It is decoded and written verbatim.
- Failures resolve as `{ success: false, error: "..." }`, and the shim rejects the
  promise as well so both `await` and `.catch()` work.
- `connect` and `disconnect` are no-ops that resolve successfully: each transport
  opens and closes its own link per job, which keeps idle printers from going stale.
- `discover` returns a stable id that has to survive a round trip through
  `printRaw.deviceId`.

### Device ids

| Transport | `discover().devices[].id` |
| --- | --- |
| USB | `usb:<vendorId>:<productId>` in lowercase hex, e.g. `usb:04b8:0e15` |
| Bluetooth | the MAC address, e.g. `66:12:AB:34:CD:EF` |
| NETWORK | none, the operator types the IP and port |

`discover` without a `transport` returns USB and paired Bluetooth devices together.
`discover({ transport: "NETWORK" })` returns an empty list by design.

## Permissions and privacy

| Permission | Why it is needed |
| --- | --- |
| `INTERNET` | Loads the POS over HTTPS and opens printer sockets |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (API 30 and below only) | Legacy Bluetooth stack |
| `BLUETOOTH_CONNECT` | List paired printers and open a Bluetooth link (API 31+) |
| `BLUETOOTH_SCAN` | `cancelDiscovery()` before a classic connect (API 31+); declared with `neverForLocation` because no location is derived |

- Bluetooth permissions are requested lazily, on the first Bluetooth print or
  discovery, never at launch, so a Wi-Fi or USB only venue never sees the prompt.
- USB permission is a per-device system prompt, requested at print time.
- The app collects nothing. No analytics, no crash reporter, and no remote call
  other than the POS URL you configure. `allowBackup="false"` keeps the session and
  local storage out of ADB and cloud backups.
- Cleartext HTTP is disabled in `res/xml/network_security_config.xml`. Printer
  sockets are plain TCP and are unaffected by that policy.

## Project layout

```
app/src/main/
  AndroidManifest.xml                    permissions, USB attach filter
  java/com/vision/pos/printer/
    MainActivity.kt                      WebView host, error screen, maintenance menu
    bridge/
      NativePrinterBridge.kt             @JavascriptInterface, one coroutine per command
      PosPrinterShim.kt                  JS shim, publishes window.median.posPrinter
    printer/
      PrinterManager.kt                  routes a call to a transport
      PrinterTransport.kt                NETWORK | USB | BLUETOOTH
      PrinterDevice.kt                   { id, name }
      NetworkPrinterTransport.kt         raw TCP, default port 9100
      UsbPrinterTransport.kt             UsbManager bulk transfers, permission prompt
      BluetoothPrinterTransport.kt       classic RFCOMM/SPP
      BlePrinterWriter.kt                BLE GATT fallback
    util/Base64Util.kt                   base64 to bytes
  res/                                   theme, adaptive icon, layout, USB filter
docs/
  ARCHITECTURE.md                        how the pieces fit together
  ROADMAP.md                             known limitations and ideas
  median-plugin-spec.md                  the same bridge written as a spec for a
                                         hosted wrapper (Median.co)
```

## Building

Android Studio: open the project folder and press Run.

Command line:

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:installDebug       # build and install on a connected device
./gradlew :app:assembleRelease    # minified release APK
```

`compileSdk 34`, `minSdk 24`, `targetSdk 34`, Kotlin 1.9.24, AGP 8.5.2. The release
build runs R8 with resource shrinking; `app/proguard-rules.pro` keeps the bridge
class and every `@JavascriptInterface` method.

## Signing a release

`keystore.properties` in the project root (git-ignored) drives the signing config:

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file `:app:assembleRelease` still builds, but the APK is unsigned and
cannot be installed or uploaded. Create the keystore once and keep it forever: every
update has to be signed with the same key, or devices refuse to install over the
existing app.

```bash
keytool -genkeypair -v -keystore release.jks -storetype PKCS12 \
  -keyalg RSA -keysize 2048 -validity 10000 -alias <your-alias>
```

Bump `versionCode` (and `versionName`) in `app/build.gradle.kts` for every upload. The
launcher icon is an adaptive icon (`mipmap-anydpi-v26/`) with PNG fallbacks for API 24
and 25; `store-assets/` holds the 512x512 versions used for store listings.

## Installing on several tablets

Without a store listing, the simplest way to roll out a build is your own laptop
over Wi-Fi:

```powershell
# the laptop address (DHCP changes it, which is the usual reason a QR stops working)
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "169.254.*" }

# serve the folder holding the APK, and allow the firewall prompt for private networks
cd dist
python -m http.server 8080

# regenerate the QR whenever that address changes
npx --yes qrcode -t png -w 512 -o install-qr.png "http://<laptop-ip>:8080/app-release.apk"
```

Scan the QR on each tablet and allow installing from unknown sources. Uninstall any
older build first: a debug build cannot be upgraded into a release build, because the
signing keys differ.

## Shell behaviour

- **Status bar.** Hidden, so the POS gets the full screen height. Swipe from the top
  edge to show it briefly; the navigation bar stays visible.
- **Display cutout.** `windowLayoutInDisplayCutoutMode=shortEdges`
  (`res/values-v27/themes.xml`) lets notch and punch-hole devices use the full width
  in landscape. This is only safe because the POS pads itself with
  `env(safe-area-inset-*)`.
- **Keyboard.** `windowSoftInputMode="adjustResize"`, so form fields stay above the
  keyboard instead of being covered by it.
- **Reload.** There is no toolbar. Long press the top strip of the screen for about
  1.5 seconds to open a small menu with *Reload POS*, *Clear cache and reload* and
  *Cancel*. The touch is not consumed, so the page keeps its own gestures.
- **Orientation.** `sensorLandscape`: landscape either way up, not locked to one side.
- **Offline.** A failed load shows a "Can't reach the POS" screen with a Retry button
  instead of the raw WebView error page.

## Troubleshooting

| Symptom | Cause or fix |
| --- | --- |
| The POS shows a browser fallback | The shim did not run. Reload the page and check `window.median.posPrinter` through `chrome://inspect`. |
| "Android direct printing needs the POS printer native plugin" | The web app is running outside this shell, or the bridge failed to inject. |
| Wi-Fi print fails immediately | Wrong IP or port, printer off, or the tablet is on a different subnet than the printer. |
| USB: "permission was denied" | Run it again and accept the prompt; a few ROMs also need OTG enabled. |
| USB: "No USB bulk OUT endpoint" | The device is not a printer, or it only exposes a vendor interface. Try another model or port. |
| Bluetooth: "permission is required" | The Nearby devices prompt appears on the first Bluetooth action. Accept it, or grant it in Android settings and retry. |
| Bluetooth LE printer does nothing | Classic SPP was refused and the BLE fallback found no writable characteristic. Open an issue with the model and its service UUIDs. |
| Blank page after a crash | Long press the top strip and pick Reload POS. |

## Status and limitations

See [docs/ROADMAP.md](docs/ROADMAP.md) for the full list, including the BLE fallback,
Bluetooth connect latency and the `targetSdk` situation.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Bug reports that include the printer model
and `adb logcat` output from around the failed print are the most useful kind. The
same bridge is specified for hosted wrappers in
[docs/median-plugin-spec.md](docs/median-plugin-spec.md).

## License

MIT, see [LICENSE](LICENSE).
