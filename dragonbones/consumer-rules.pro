# Consumer ProGuard rules for dragonbones library consumers.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.dragonbones.** { *; }
