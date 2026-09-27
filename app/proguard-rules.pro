# KidPlayerActivity's PlayerBridge is called from the page's JavaScript by name,
# which R8 cannot see.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
