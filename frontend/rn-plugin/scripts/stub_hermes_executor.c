// libhermes_executor.so - 占位实现
// HermesExecutor 需要加载这个库

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "HermesExecutorStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// HermesExecutor 构造函数
JNIEXPORT jlong JNICALL
Java_com_facebook_hermes_reactexecutor_HermesExecutor_initHybridDefaultConfig(
    JNIEnv *env,
    jclass clazz,
    jboolean enableDebugger,
    jstring debugRuntimePath) {
    LOGI("initHybridDefaultConfig stub called");
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_facebook_hermes_reactexecutor_HermesExecutor_initHybrid(
    JNIEnv *env,
    jclass clazz,
    jboolean enableDebugger,
    jstring debugRuntimePath,
    jlong heapSizeMB) {
    LOGI("initHybrid stub called");
    return 0;
}

// 其他可能需要的 JNI 函数
JNIEXPORT void JNICALL
Java_com_facebook_hermes_reactexecutor_HermesExecutor_nativeInstall(JNIEnv *env, jclass clazz) {
    LOGI("nativeInstall stub called");
}
