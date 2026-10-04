# The JavaScript bridge is resolved by name from the WebView, so keep it intact
# if you ever enable minification for the release build.
-keepclassmembers class com.vision.pos.printer.bridge.NativePrinterBridge {
    public *;
}
-keep class com.vision.pos.printer.bridge.NativePrinterBridge { *; }
