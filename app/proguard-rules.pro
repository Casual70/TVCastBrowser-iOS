# Keep CastOptionsProvider for reflection by Google Play Services Cast SDK
-keep class com.tvcast.browser.cast.CastOptionsProvider { *; }

# Keep JavascriptInterface methods for WebView DOM video sniffer
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

