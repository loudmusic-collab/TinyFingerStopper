# The WebView in KidPlayerActivity loads the YouTube IFrame API, which calls back
# into the page only - there is no JavascriptInterface to keep.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
