package com.swipe.player;
public class DragonBonesBridge {
    static {
        System.loadLibrary("dragonbones_native");
    }
    public static native String nativeGetVersion();
    public static native boolean nativeInit();

    public static String getVersion() {
        return nativeGetVersion();
    }
    public static boolean init() {
        return nativeInit();
    }
}
