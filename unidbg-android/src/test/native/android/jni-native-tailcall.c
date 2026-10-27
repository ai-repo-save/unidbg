#include <jni.h>
#include <stdarg.h>
#include <stdint.h>

static jint *witness;
static JNIEnv *original_env;
static jclass original_type;

JNIEXPORT void tail_target(JNIEnv *env, jclass type, jint value) {
    witness[0] = value;
    witness[1] = env == original_env;
    witness[2] = (*env)->IsSameObject(env, type, original_type);
}

static void call_v(JNIEnv *env, jclass type, jmethodID method, ...) {
    va_list args;
    va_start(args, method);
    (*env)->CallStaticVoidMethodV(env, type, method, args);
    va_end(args);
}

JNIEXPORT jint exercise(JNIEnv *env, jclass type, jint mode, jint value, jint *observed) {
    witness = observed;
    original_env = env;
    original_type = type;
    jmethodID tail = (*env)->GetStaticMethodID(env, type, "tail", "(I)V");
    jvalue args[1];
    args[0].i = value;
    uintptr_t before, after;
    __asm__ volatile("mov %0, sp" : "=r"(before));
    if (mode == 0) {
        (*env)->CallStaticVoidMethod(env, type, tail, value);
    } else if (mode == 1) {
        call_v(env, type, tail, value);
    } else {
        (*env)->CallStaticVoidMethodA(env, type, tail, args);
    }
    __asm__ volatile("mov %0, sp" : "=r"(after));
    observed[3] = (jint)(after - before);

    jmethodID normal = (*env)->GetStaticMethodID(env, type, "normal", "(I)I");
    __asm__ volatile("mov %0, sp" : "=r"(before));
    observed[4] = (*env)->CallStaticIntMethodA(env, type, normal, args);
    __asm__ volatile("mov %0, sp" : "=r"(after));
    observed[5] = (jint)(after - before);

    jclass host = (*env)->FindClass(env,
            "com/github/unidbg/linux/android/dvm/JniNativeTailCallIntegrationTest$Target");
    jmethodID method = (*env)->GetStaticMethodID(env, host, "host", "(I)I");
    __asm__ volatile("mov %0, sp" : "=r"(before));
    observed[6] = (*env)->CallStaticIntMethodA(env, host, method, args);
    __asm__ volatile("mov %0, sp" : "=r"(after));
    observed[7] = (jint)(after - before);
    observed[8] = (*env)->ExceptionCheck(env);
    observed[9] = 0x12345678;
    (*env)->DeleteLocalRef(env, host);
    return 0x54321000 + mode;
}
