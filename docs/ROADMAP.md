# Roadmap and known limitations

This page is honest about the current state of the project. Nothing here is
scheduled; it is a list of things worth fixing and things worth knowing before you
deploy it.

## Known limitations

**Verification coverage.** Debug and release builds compile clean with JDK 21, AGP
8.5.2 and `platforms;android-34`, and the debug build has been smoke tested on a
Samsung A05 (Android 13, landscape 1600x720). The print paths are only as tested as
the printers they were tried on. If a printer misbehaves, please open an issue with
the model number.

**BLE is a generic fallback.** `BlePrinterWriter` writes to the first writable
characteristic it finds. Classic SPP is the primary path for ESC/POS hardware. A BLE
printer with an unusual layout can be supported properly by pinning its
service/characteristic UUIDs.

**Bluetooth failures are slow.** `BluetoothSocket.connect()` has no timeout API, so a
printer that is powered off or out of range can hold a job for around 12 seconds
before the BLE fallback runs. Wi-Fi and USB fail fast (8 second connect timeout).

**Only 5xx counts as an HTTP failure.** If the server answers the main frame with a
5xx (a dead backend or a maintenance page), the offline screen is shown through
`onReceivedHttpError`. A 4xx, or a 200 that serves a maintenance page, still
renders the server's own HTML: everything else is driven by WebView load errors
(`onReceivedError`), not HTTP status codes.

**Ignoring `ERROR_UNKNOWN` is deliberate.** WebView reports `-1` for aborted
navigations, such as redirects and download links, which is not an outage. A genuine
failure reported as `-1` is therefore left alone, and a document that renders
nothing is caught by the empty-document check (`verifyRendered`); the hidden
maintenance menu (long press the top strip) remains the escape hatch.

**Not enabled on purpose.** `FLAG_SECURE` (blocks screenshots) and kiosk/lock-task
mode. Add them only if a deployment needs them; kiosk mode in particular changes
what the Back button does.

**`targetSdk 34`.** Google Play requires new submissions to target API 35 or newer,
which means bumping `compileSdk` and AGP and retesting. Direct APK distribution is
unaffected.

**Smaller items.** `databaseEnabled` is still on (legacy Web SQL, unused),
`mixedContentMode` is `MIXED_CONTENT_COMPATIBILITY_MODE`, and `settings.textZoom` is
not pinned, so a very large system font scale can distort the POS layout.

## Ideas worth doing

- A self test in the maintenance menu that prints a short sample and cuts.
- Predictive back support (`android:enableOnBackInvokedCallback`) and polish for it.
- An iOS sibling exposing the same JS contract (NetworkExtension for TCP,
  ExternalAccessory for USB, CoreBluetooth for Bluetooth).
