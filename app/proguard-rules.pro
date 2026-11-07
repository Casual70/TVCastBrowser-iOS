# Keep CastOptionsProvider for reflection by Google Play Services Cast SDK
-keep class com.tvcast.browser.cast.CastOptionsProvider { *; }

# Keep JavascriptInterface methods for WebView DOM video sniffer
# Keep JavascriptInterface methods for WebView DOM/XHR/Fetch video sniffer
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.tvcast.browser.sniffer.MediaSniffer$JsMediaBridge { *; }
