# Swipe Player - ProGuard rules

# Media3 ExoPlayer: păstrăm clasele folosite de decoder/renderer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keepattributes *Annotation*, InnerClasses, Signature, MethodParameters
-keepclassmembers class * {
    @androidx.media3.common.util.UnstableApi <methods>;
}
