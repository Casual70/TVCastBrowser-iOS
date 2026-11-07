# Keep JavascriptInterface methods for WebView DOM/XHR/Fetch video sniffer
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.tvcast.browser.sniffer.MediaSniffer$JsMediaBridge { *; }