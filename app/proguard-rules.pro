# NetCam ProGuard/R8 rules.
# The app currently ships with minification disabled; these rules are kept
# for future release builds.

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-dontwarn com.google.mlkit.**
