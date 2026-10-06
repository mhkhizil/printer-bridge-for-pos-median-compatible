# Contributing

Thanks for taking a look. This project is deliberately small: one Activity, a JS
bridge and three printer transports. Please help keep it that way.

## Build and run

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:installDebug       # build and install on a connected device
./gradlew :app:assembleRelease    # minified release APK (unsigned without keystore.properties)
```

Requirements: JDK 17+, Android SDK with `platforms;android-34` and
`build-tools;34.0.0`. Point `local.properties` at your SDK (`sdk.dir=...`) and add
`posUrl=https://your-pos` if you want a fixed URL for local builds.

On Unix, make the wrapper executable first: `chmod +x gradlew`.

## What to check before opening a pull request

Printing cannot be covered by unit tests, so a PR that touches a transport should
say which printer model and which transport it was tested on.

1. `./gradlew :app:assembleDebug` builds without warnings.
2. The app loads the POS and the page sees the bridge:
   `typeof window.median.posPrinter.printRaw === "function"` (inspect with
   `chrome://inspect`).
3. One slip printed per transport you changed (Wi-Fi, USB, Bluetooth).
4. Failures come back as `{ success: false, error }`, never as a crash or a hang.

## Code style

- Follow `.editorconfig`: 4 spaces in Kotlin, LF, ASCII only. Please keep new source
  and documentation text ASCII, with no smart quotes and no long dashes.
- Comments should explain why something is the way it is. One line beats a paragraph.
- Keep the JS contract (`window.median.posPrinter`) and the transport interface
  (`PrinterTransport`) stable; other apps and the web side depend on both.

## Commit messages

Short imperative subject, conventional-commit prefixes welcome:
`fix: handle a dropped BLE link`, `docs: clarify device id format`. Keep unrelated
changes in separate commits.

## Reporting a bug

Include the device and Android version, the transport, the printer model and the
exact error string the POS displayed. `adb logcat` output from around the failed
print is very helpful. See the troubleshooting table in the README first.
