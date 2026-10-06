# --- JavaScript bridge -------------------------------------------------------
# The bridge is resolved BY NAME from the WebView, so its members must survive R8,
# and every @JavascriptInterface method must keep its name + signature.
-keep class com.vision.pos.printer.bridge.NativePrinterBridge { *; }
-keepclassmembers class com.vision.pos.printer.bridge.NativePrinterBridge {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
-keepattributes JavascriptInterface

# --- WebView / AndroidX ------------------------------------------------------
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebView {
    public *;
}
