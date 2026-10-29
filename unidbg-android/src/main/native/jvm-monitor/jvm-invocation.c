#include <jni.h>

static jobject result(JNIEnv *env, jobject value, jthrowable target) {
    jclass type = (*env)->FindClass(env,
            "com/github/unidbg/linux/android/dvm/host/JvmInvocationBridge$Result");
    if (type == NULL) return NULL;
    jmethodID constructor = (*env)->GetMethodID(env, type, "<init>",
            "(Ljava/lang/Object;Ljava/lang/Throwable;)V");
    if (constructor == NULL) return NULL;
    jvalue args[2] = {{0}};
    args[0].l = value;
    args[1].l = target;
    return (*env)->NewObjectA(env, type, constructor, args);
}

static jobject target_failure(JNIEnv *env) {
    jthrowable target = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    jobject outcome = result(env, NULL, target);
    (*env)->DeleteLocalRef(env, target);
    return outcome;
}

static void invalid_kind(JNIEnv *env) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type != NULL) (*env)->ThrowNew(env, type, "Invalid private JNI bridge type code");
}

static jboolean arguments(JNIEnv *env, jobjectArray input, const jbyte *kinds,
                          jsize count, jvalue *values) {
    for (jsize i = 0; i < count; i++) {
        jobject argument = (*env)->GetObjectArrayElement(env, input, i);
        if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
        if (kinds[i] == 'L') {
            values[i].l = argument;
            continue;
        }
        jclass type = (*env)->GetObjectClass(env, argument);
        if (type == NULL) {
            (*env)->DeleteLocalRef(env, argument);
            return JNI_FALSE;
        }
        jmethodID accessor;
#define UNBOX(code, member, name, signature, call) \
        case code: \
            accessor = (*env)->GetMethodID(env, type, name, signature); \
            if (accessor != NULL) values[i].member = (*env)->call(env, argument, accessor, NULL); \
            break
        switch (kinds[i]) {
            UNBOX('Z', z, "booleanValue", "()Z", CallBooleanMethodA);
            UNBOX('B', b, "byteValue", "()B", CallByteMethodA);
            UNBOX('C', c, "charValue", "()C", CallCharMethodA);
            UNBOX('S', s, "shortValue", "()S", CallShortMethodA);
            UNBOX('I', i, "intValue", "()I", CallIntMethodA);
            UNBOX('J', j, "longValue", "()J", CallLongMethodA);
            UNBOX('F', f, "floatValue", "()F", CallFloatMethodA);
            UNBOX('D', d, "doubleValue", "()D", CallDoubleMethodA);
            default: invalid_kind(env); break;
        }
#undef UNBOX
        (*env)->DeleteLocalRef(env, type);
        (*env)->DeleteLocalRef(env, argument);
        if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    }
    return JNI_TRUE;
}

static void release_arguments(JNIEnv *env, const jbyte *kinds, jsize count, jvalue *values) {
    for (jsize i = 0; i < count; i++) {
        if (kinds[i] == 'L' && values[i].l != NULL) (*env)->DeleteLocalRef(env, values[i].l);
    }
}

static jobject box(JNIEnv *env, jbyte kind, jvalue value) {
    const char *name;
    const char *signature;
    jvalue argument = {0};
    switch (kind) {
        case 'L': return value.l;
        case 'V': return NULL;
#define BOX(code, member, wrapper, descriptor) \
        case code: name = "java/lang/" wrapper; signature = descriptor; argument.member = value.member; break
        BOX('Z', z, "Boolean", "(Z)Ljava/lang/Boolean;");
        BOX('B', b, "Byte", "(B)Ljava/lang/Byte;");
        BOX('C', c, "Character", "(C)Ljava/lang/Character;");
        BOX('S', s, "Short", "(S)Ljava/lang/Short;");
        BOX('I', i, "Integer", "(I)Ljava/lang/Integer;");
        BOX('J', j, "Long", "(J)Ljava/lang/Long;");
        BOX('F', f, "Float", "(F)Ljava/lang/Float;");
        BOX('D', d, "Double", "(D)Ljava/lang/Double;");
        default: invalid_kind(env); return NULL;
    }
#undef BOX
    jclass type = (*env)->FindClass(env, name);
    if (type == NULL) return NULL;
    jmethodID factory = (*env)->GetStaticMethodID(env, type, "valueOf", signature);
    if (factory == NULL) return NULL;
    return (*env)->CallStaticObjectMethodA(env, type, factory, &argument);
}

JNIEXPORT jobject JNICALL
Java_com_github_unidbg_linux_android_dvm_host_JvmInvocationBridge_allocate0(
        JNIEnv *env, jclass bridge, jclass target) {
    (void)bridge;
    jobject value = (*env)->AllocObject(env, target);
    if ((*env)->ExceptionCheck(env)) return target_failure(env);
    return result(env, value, NULL);
}

JNIEXPORT jobject JNICALL
Java_com_github_unidbg_linux_android_dvm_host_JvmInvocationBridge_invoke0(
        JNIEnv *env, jclass bridge, jobject receiver, jclass dispatch,
        jstring name, jstring descriptor, jbyteArray argument_kinds,
        jobjectArray input, jbyte return_kind) {
    (void)bridge;
    /* JVM method descriptors permit at most 255 parameter slots. */
    jvalue values[255] = {{0}};
    jbyte kinds[255] = {0};
    jsize count = (*env)->GetArrayLength(env, argument_kinds);
    if ((*env)->EnsureLocalCapacity(env, count + 16) != JNI_OK) return NULL;
    (*env)->GetByteArrayRegion(env, argument_kinds, 0, count, kinds);
    if ((*env)->ExceptionCheck(env)) return NULL;
    if (!arguments(env, input, kinds, count, values)) {
        release_arguments(env, kinds, count, values);
        return NULL;
    }
    const char *method_name = (*env)->GetStringUTFChars(env, name, NULL);
    if (method_name == NULL) {
        release_arguments(env, kinds, count, values);
        return NULL;
    }
    const char *method_descriptor = (*env)->GetStringUTFChars(env, descriptor, NULL);
    if (method_descriptor == NULL) {
        (*env)->ReleaseStringUTFChars(env, name, method_name);
        release_arguments(env, kinds, count, values);
        return NULL;
    }
    jmethodID method = (*env)->GetMethodID(env, dispatch, method_name, method_descriptor);
    (*env)->ReleaseStringUTFChars(env, descriptor, method_descriptor);
    (*env)->ReleaseStringUTFChars(env, name, method_name);
    if ((*env)->ExceptionCheck(env)) {
        release_arguments(env, kinds, count, values);
        return target_failure(env);
    }
    jvalue returned = {0};
#define INVOKE(code, member, call) \
    case code: returned.member = (*env)->call(env, receiver, dispatch, method, values); break
    switch (return_kind) {
        INVOKE('Z', z, CallNonvirtualBooleanMethodA);
        INVOKE('B', b, CallNonvirtualByteMethodA);
        INVOKE('C', c, CallNonvirtualCharMethodA);
        INVOKE('S', s, CallNonvirtualShortMethodA);
        INVOKE('I', i, CallNonvirtualIntMethodA);
        INVOKE('J', j, CallNonvirtualLongMethodA);
        INVOKE('F', f, CallNonvirtualFloatMethodA);
        INVOKE('D', d, CallNonvirtualDoubleMethodA);
        INVOKE('L', l, CallNonvirtualObjectMethodA);
        case 'V': (*env)->CallNonvirtualVoidMethodA(env, receiver, dispatch, method, values); break;
        default:
            release_arguments(env, kinds, count, values);
            invalid_kind(env);
            return NULL;
    }
#undef INVOKE
    release_arguments(env, kinds, count, values);
    if ((*env)->ExceptionCheck(env)) return target_failure(env);
    jobject boxed = box(env, return_kind, returned);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return result(env, boxed, NULL);
}
