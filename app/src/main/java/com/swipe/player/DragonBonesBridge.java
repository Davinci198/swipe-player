package com.swipe.player;

public class DragonBonesBridge {
    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("dragonbones_native");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.w("DragonBonesBridge", "Native lib not available: " + e.getMessage());
        }
    }

    public static native String nativeGetVersion();
    public static native boolean nativeInit();

    public static String getVersion() {
        try {
            return loaded ? nativeGetVersion() : "N/A";
        } catch (UnsatisfiedLinkError e) {
            return "N/A";
        }
    }

    public static boolean init() {
        try {
            return loaded && nativeInit();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }
}
