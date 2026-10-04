# Next steps & known limitations

Handoff notes for this repo. Everything below is the state of the scaffold after
it was created; nothing here is implemented automatically.

## Your next steps

1. **Point it at your POS.** Edit `app/build.gradle.kts` and set the deployed POS
   web app URL:

   ```kotlin
   buildConfigField("String", "POS_URL", "\"https://<your-deployed-pos>\"")
   ```

2. **Build.** Open the folder in Android Studio (it generates the Gradle wrapper
   jar, which is not committed as a binary) and press ▶ Run, or from a terminal:

   ```bash
   gradle wrapper --gradle-version 8.7
   ./gradlew :app:assembleDebug     # or :app:installDebug
   ```

3. **Test on device** using the README checklist (Wi‑Fi → USB → Bluetooth; expect
   a silent print with **no** Android dialog; the Printer settings badge should
   read **“Android direct printing ready”**).

4. **Median.co alternative.** If you'd rather ship via Median.co instead of this
   shell, ask them for a **private plugin** exposing this same `median.posPrinter`
   contract — same code path, no web changes.

## Honest limitations

- **Not compiled in the authoring environment.** There is no JDK, Gradle, or
  Android SDK installed there (`java` is not on PATH). The code is written and
  statically checked (imports, scope, precedence, and no stray `$` in the raw JS
  shim), but the first real `assembleDebug` should be run on your machine or CI.
  If any compiler nit appears, paste it and it can be fixed fast.
- **BLE GATT is a generic fallback.** `printer/BlePrinterWriter.kt` writes to the
  first writable characteristic it finds; classic SPP is the primary, well-tested
  path. If a BLE printer misbehaves, share the model and its exact
  service/characteristic UUIDs can be pinned.
- **Release signing / Play upload is yours.** A keystore + AAB and the on-device
  acceptance testing are not part of this repo.

## Quick reference

| Item | Value |
| --- | --- |
| Bridge object (native) | `window.__posPrinterNative` |
| Contract exposed to the page | `window.median.posPrinter.{connect,disconnect,discover,printRaw}` |
| POS URL build field | `BuildConfig.POS_URL` (`app/build.gradle.kts`) |
| Default network print port | `9100` |
| USB device id shape | `usb:<vendorId>:<productId>` (e.g. `usb:04b8:0e15`) |
| Bluetooth device id shape | MAC address (e.g. `66:12:AB:34:CD:EF`) |
| Bluetooth SPP UUID | `00001101-0000-1000-8000-00805F9B34FB` |
| Min / target / compile SDK | 24 / 34 / 34 |
| Kotlin / AGP | 1.9.24 / 8.5.2 |
