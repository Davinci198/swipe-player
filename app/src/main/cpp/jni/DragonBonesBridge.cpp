#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "DragonBonesNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_swipeplayer_DragonBonesBridge_nativeGetVersion(JNIEnv *env, jobject /*thiz*/) {
    LOGI("DragonBones native library loaded");
    return env->NewStringUTF("1.0.0-ndk27");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_swipeplayer_DragonBonesBridge_nativeInit(JNIEnv *env, jobject /*thiz*/) {
    LOGI("DragonBones native init OK");
    return JNI_TRUE;
}