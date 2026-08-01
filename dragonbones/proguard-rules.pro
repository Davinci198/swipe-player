# Add project specific ProGuard rules for the dragonbones library.
# Keep JNI entry points for DragonBones native bridge.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.dragonbones.** { *; }
