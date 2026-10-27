#include <jni.h>

JNIEXPORT jint JNICALL
Java_com_github_unidbg_linux_android_dvm_host_JvmMonitorBridge_enter(
        JNIEnv *env, jclass type, jobject target) {
    (void)type;
    return (*env)->MonitorEnter(env, target);
}

JNIEXPORT jint JNICALL
Java_com_github_unidbg_linux_android_dvm_host_JvmMonitorBridge_exit(
        JNIEnv *env, jclass type, jobject target) {
    (void)type;
    return (*env)->MonitorExit(env, target);
}
