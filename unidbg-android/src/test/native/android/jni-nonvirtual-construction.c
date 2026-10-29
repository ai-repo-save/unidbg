#include <jni.h>
#include <stdarg.h>
#include <stdint.h>

uint64_t invocation_results[3][12];
static jobject roots[5];
static jobject returned_objects[3][2];

#define V_HELPER(suffix, type) \
static type call_##suffix##_v(JNIEnv *env, jobject receiver, jclass dispatch, jmethodID method, ...) { \
    va_list arguments; \
    va_start(arguments, method); \
    type value = (*env)->CallNonvirtual##suffix##MethodV(env, receiver, dispatch, method, arguments); \
    va_end(arguments); \
    return value; \
}
V_HELPER(Boolean, jboolean)
V_HELPER(Byte, jbyte)
V_HELPER(Char, jchar)
V_HELPER(Short, jshort)
V_HELPER(Int, jint)
V_HELPER(Long, jlong)
V_HELPER(Float, jfloat)
V_HELPER(Double, jdouble)
V_HELPER(Object, jobject)

static void call_Void_v(JNIEnv *env, jobject receiver, jclass dispatch, jmethodID method, ...) {
    va_list arguments;
    va_start(arguments, method);
    (*env)->CallNonvirtualVoidMethodV(env, receiver, dispatch, method, arguments);
    va_end(arguments);
}

#define CALL(suffix, dispatch, ...) \
    (mode == 0 ? (*env)->CallNonvirtual##suffix##Method(env, receiver, dispatch, method, __VA_ARGS__) : \
     mode == 1 ? call_##suffix##_v(env, receiver, dispatch, method, __VA_ARGS__) : \
     (*env)->CallNonvirtual##suffix##MethodA(env, receiver, dispatch, method, arguments))
#define LOOKUP(dispatch, name, signature, failure) \
    method = (*env)->GetMethodID(env, dispatch, name, signature); \
    if (method == NULL) return failure
#define CHECK(failure) if ((*env)->ExceptionCheck(env)) return failure

static void witness(JNIEnv *env, jclass type, jmethodID method, jobject value) {
    jvalue argument;
    argument.l = value;
    (*env)->CallStaticVoidMethodA(env, type, method, &argument);
}

int invocation_run(JNIEnv *env, jclass base, jclass derived, jclass defaults,
                   jclass observer, jclass init_probe, jobject anchor, jobject array) {
    jmethodID before = (*env)->GetStaticMethodID(env, observer, "before", "(Ljava/lang/Object;)V");
    if (before == NULL) return 1;
    jmethodID after = (*env)->GetStaticMethodID(env, observer, "after", "(Ljava/lang/Object;)V");
    if (after == NULL) return 2;
    jmethodID exception = (*env)->GetStaticMethodID(env, observer, "exception", "(Ljava/lang/Object;)V");
    if (exception == NULL) return 3;
    jmethodID monitor = (*env)->GetStaticMethodID(env, observer, "monitor", "(Ljava/lang/Object;)V");
    if (monitor == NULL) return 4;
    if ((*env)->MonitorEnter(env, anchor) != JNI_OK) return 5;
    witness(env, observer, monitor, anchor);
    CHECK(6);
    if ((*env)->MonitorExit(env, anchor) != JNI_OK) return 7;
    jobject probe = (*env)->AllocObject(env, init_probe);
    CHECK(8);
    (*env)->DeleteLocalRef(env, probe);

    jmethodID constructor = (*env)->GetMethodID(env, derived, "<init>", "(I)V");
    if (constructor == NULL) return 9;
    jmethodID method;
    jvalue arguments[9];
    for (int mode = 0; mode < 3; mode++) {
        jobject receiver = (*env)->AllocObject(env, derived);
        CHECK(10);
        roots[mode] = (*env)->NewGlobalRef(env, receiver);
        CHECK(11);
        if ((*env)->MonitorEnter(env, receiver) != JNI_OK) return 12;
        witness(env, observer, before, receiver);
        CHECK(13);
        method = constructor;
        arguments[0].i = 41 + mode;
        CALL(Void, derived, arguments[0].i);
        CHECK(14);
        if ((*env)->IsSameObject(env, receiver, roots[mode]) != JNI_TRUE) return 15;

        LOOKUP(base, "z", "(Z)Z", 20);
        arguments[0].z = JNI_TRUE;
        invocation_results[mode][0] = CALL(Boolean, base, JNI_TRUE);
        CHECK(21);
        LOOKUP(base, "b", "(B)B", 22);
        arguments[0].b = -101;
        invocation_results[mode][1] = (uint64_t)(int64_t)CALL(Byte, base, -101);
        CHECK(23);
        LOOKUP(base, "c", "(C)C", 24);
        arguments[0].c = 65505;
        invocation_results[mode][2] = CALL(Char, base, 65505);
        CHECK(25);
        LOOKUP(base, "s", "(S)S", 26);
        arguments[0].s = -12345;
        invocation_results[mode][3] = (uint64_t)(int64_t)CALL(Short, base, -12345);
        CHECK(27);
        LOOKUP(base, "i", "(I)I", 28);
        arguments[0].i = -123456789;
        invocation_results[mode][4] = (uint64_t)(int64_t)CALL(Int, base, -123456789);
        CHECK(29);
        LOOKUP(base, "j", "(J)J", 30);
        arguments[0].j = INT64_C(0x1020304050607080);
        invocation_results[mode][5] = (uint64_t)CALL(Long, base, arguments[0].j);
        CHECK(31);
        LOOKUP(base, "f", "(F)F", 32);
        arguments[0].f = 1.5f;
        union { jfloat value; uint32_t bits; } single;
        single.value = CALL(Float, base, 1.5);
        invocation_results[mode][6] = single.bits;
        CHECK(33);
        LOOKUP(base, "d", "(D)D", 34);
        arguments[0].d = -2.25;
        union { jdouble value; uint64_t bits; } twice;
        twice.value = CALL(Double, base, -2.25);
        invocation_results[mode][7] = twice.bits;
        CHECK(35);
        LOOKUP(base, "object", "(Ljava/lang/Object;)Ljava/lang/Object;", 36);
        arguments[0].l = anchor;
        jobject object_result = CALL(Object, base, anchor);
        CHECK(37);
        returned_objects[mode][0] = (*env)->NewGlobalRef(env, object_result);
        invocation_results[mode][8] = (uintptr_t)returned_objects[mode][0];
        LOOKUP(base, "array", "([I)[I", 38);
        arguments[0].l = array;
        jobject array_result = CALL(Object, base, array);
        CHECK(39);
        returned_objects[mode][1] = (*env)->NewGlobalRef(env, array_result);
        invocation_results[mode][9] = (uintptr_t)returned_objects[mode][1];
        LOOKUP(defaults, "defaultValue", "(I)I", 40);
        arguments[0].i = 73;
        invocation_results[mode][10] = (uint64_t)(int64_t)CALL(Int, defaults, 73);
        CHECK(41);
        LOOKUP(base, "hidden", "(I)I", 42);
        arguments[0].i = 77;
        invocation_results[mode][11] = (uint64_t)(int64_t)CALL(Int, base, 77);
        CHECK(43);
        LOOKUP(base, "arguments", "(ZBCSIJFDLjava/lang/Object;)V", 44);
        arguments[0].z = JNI_TRUE;
        arguments[1].b = -101;
        arguments[2].c = 65505;
        arguments[3].s = -12345;
        arguments[4].i = -123456789;
        arguments[5].j = INT64_C(0x1020304050607080);
        arguments[6].f = 1.5f;
        arguments[7].d = -2.25;
        arguments[8].l = anchor;
        CALL(Void, base, JNI_TRUE, -101, 65505, -12345, -123456789,
                arguments[5].j, 1.5, -2.25, anchor);
        CHECK(45);
        LOOKUP(base, "i", "(I)I", 46);
        arguments[0].i = 19;
        if ((*env)->CallIntMethodA(env, receiver, method, arguments) != 19) return 47;
        CHECK(48);
        witness(env, observer, after, receiver);
        CHECK(49);
        if ((*env)->MonitorExit(env, receiver) != JNI_OK) return 50;
        (*env)->DeleteLocalRef(env, receiver);
    }
    jobject receiver = (*env)->AllocObject(env, derived);
    CHECK(51);
    roots[3] = (*env)->NewGlobalRef(env, receiver);
    CHECK(52);
    if ((*env)->MonitorEnter(env, receiver) != JNI_OK) return 53;
    witness(env, observer, before, receiver);
    CHECK(54);
    arguments[0].i = 81;
    (*env)->CallVoidMethodA(env, receiver, constructor, arguments);
    CHECK(55);
    witness(env, observer, after, receiver);
    CHECK(56);
    if ((*env)->MonitorExit(env, receiver) != JNI_OK) return 57;
    (*env)->DeleteLocalRef(env, receiver);

    receiver = roots[0];
    LOOKUP(base, "fail", "()V", 58);
    (*env)->CallNonvirtualVoidMethodA(env, receiver, base, method, NULL);
    if (!(*env)->ExceptionCheck(env)) return 59;
    jobject failure = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    witness(env, observer, exception, failure);
    CHECK(60);
    (*env)->DeleteLocalRef(env, failure);

    receiver = (*env)->AllocObject(env, derived);
    CHECK(61);
    roots[4] = (*env)->NewGlobalRef(env, receiver);
    CHECK(62);
    if ((*env)->MonitorEnter(env, receiver) != JNI_OK) return 63;
    witness(env, observer, before, receiver);
    CHECK(64);
    arguments[0].i = -7;
    (*env)->CallNonvirtualVoidMethodA(env, receiver, derived, constructor, arguments);
    if (!(*env)->ExceptionCheck(env)) return 65;
    failure = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    witness(env, observer, exception, failure);
    CHECK(66);
    (*env)->DeleteLocalRef(env, failure);
    witness(env, observer, after, receiver);
    CHECK(67);
    if ((*env)->MonitorExit(env, receiver) != JNI_OK) return 68;
    (*env)->DeleteLocalRef(env, receiver);
    return 0;
}

int invocation_cleanup(JNIEnv *env) {
    for (int i = 0; i < 5; i++) {
        if (roots[i] != NULL) {
            (*env)->DeleteGlobalRef(env, roots[i]);
            roots[i] = NULL;
        }
    }
    for (int mode = 0; mode < 3; mode++) {
        for (int i = 0; i < 2; i++) {
            if (returned_objects[mode][i] != NULL) {
                (*env)->DeleteGlobalRef(env, returned_objects[mode][i]);
                returned_objects[mode][i] = NULL;
            }
        }
    }
    return 0;
}
