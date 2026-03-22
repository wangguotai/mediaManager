// libreact_featureflagsjni.so - 占位实现
// 用于避免 RN 0.82 启动时的 UnsatisfiedLinkError

#include <jni.h>

// 必需的 JNI 函数占位符
JNIEXPORT void JNICALL
Java_com_facebook_react_internal_featureflags_ReactNativeFeatureFlagsCxxAccessor_nativeSetAccessor(JNIEnv *env, jclass clazz) {
    // 空实现
}

JNIEXPORT jboolean JNICALL
Java_com_facebook_react_internal_featureflags_ReactNativeFeatureFlagsCxxAccessor_enableFontScaleChangesUpdatingLayout(JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

// 添加其他可能需要的函数占位符
JNIEXPORT void JNICALL
Java_com_facebook_react_internal_featureflags_ReactNativeFeatureFlagsCxxInterop_nativeSetFeatureFlags(JNIEnv *env, jclass clazz) {
    // 空实现
}
