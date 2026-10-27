#include <jni.h>
#include <stdarg.h>

/* JNI calling signatures, with plain callbacks rather than an artificial va_list layout. */
typedef void (*plain_sink)(JNIEnv *, jobject, jmethodID, ...);
typedef void (*v_sink)(JNIEnv *, jobject, jmethodID, va_list);
typedef void (*a_sink)(JNIEnv *, jobject, jmethodID, const jvalue *);

#define ARGUMENTS \
    JNI_TRUE, (jbyte)-101, (jchar)0xffe1, (jshort)-12345, (jint)-123456789, \
    (jlong)0x1020304050607080LL, (jfloat)1.5f, (jdouble)-2.25, anchor, \
    21, 22, 23, 24, 25, 26, 27, 28, 29, \
    31.25, 32.25, 33.25, 34.25, 35.25, 36.25, 37.25, 38.25, 39.25

JNIEXPORT void invoke_plain(plain_sink sink, jobject anchor) {
    sink(NULL, anchor, NULL, ARGUMENTS);
}

static void forward_v(v_sink sink, jobject anchor, ...) {
    va_list args;
    va_start(args, anchor);
    sink(NULL, anchor, NULL, args);
    va_end(args);
}

JNIEXPORT void invoke_v(v_sink sink, jobject anchor) {
    forward_v(sink, anchor, ARGUMENTS);
}

JNIEXPORT void invoke_a(a_sink sink, jobject anchor) {
    jvalue args[27];
    args[0].z = JNI_TRUE; args[1].b = -101; args[2].c = 0xffe1; args[3].s = -12345;
    args[4].i = -123456789; args[5].j = (jlong)0x1020304050607080LL;
    args[6].f = 1.5f; args[7].d = -2.25; args[8].l = anchor;
    for (int index = 0; index < 9; index++) {
        args[9 + index].i = 21 + index;
        args[18 + index].d = 31.25 + index;
    }
    sink(NULL, anchor, NULL, args);
}

/* Fixed-signature field calls additionally exercise the non-variadic FP/wide ABI. */
JNIEXPORT void invoke_fields(JNIEnv *env, jobject target, jvalue *observed) {
    jclass type = (*env)->GetObjectClass(env, target);
#define FIELD(Type, member, signature, input, slot) do { \
    jfieldID instance = (*env)->GetFieldID(env, type, #member, signature); \
    jfieldID stat = (*env)->GetStaticFieldID(env, type, "s" #member, signature); \
    (*env)->Set##Type##Field(env, target, instance, input); \
    (*env)->SetStatic##Type##Field(env, type, stat, input); \
    observed[slot].member = (*env)->Get##Type##Field(env, target, instance); \
    observed[9 + slot].member = (*env)->GetStatic##Type##Field(env, type, stat); \
} while (0)
    FIELD(Boolean, z, "Z", JNI_TRUE, 0);
    FIELD(Byte, b, "B", (jbyte)-101, 1);
    FIELD(Char, c, "C", (jchar)0xffe1, 2);
    FIELD(Short, s, "S", (jshort)-12345, 3);
    FIELD(Int, i, "I", (jint)-123456789, 4);
    FIELD(Long, j, "J", (jlong)0x1020304050607080LL, 5);
    FIELD(Float, f, "F", (jfloat)1.5f, 6);
    FIELD(Double, d, "D", (jdouble)-2.25, 7);
#undef FIELD
    jfieldID instance = (*env)->GetFieldID(env, type, "object", "Ljava/lang/Object;");
    jfieldID stat = (*env)->GetStaticFieldID(env, type, "sobject", "Ljava/lang/Object;");
    (*env)->SetObjectField(env, target, instance, target);
    (*env)->SetStaticObjectField(env, type, stat, target);
    observed[8].l = (*env)->GetObjectField(env, target, instance);
    observed[17].l = (*env)->GetStaticObjectField(env, type, stat);
    (*env)->DeleteLocalRef(env, type);
}
