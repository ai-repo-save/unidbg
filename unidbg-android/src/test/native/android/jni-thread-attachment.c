#include <jni.h>
#include <pthread.h>
#include <stdint.h>

struct attachment {
    JavaVM *vm;
    JNIEnv *parent_env;
    jint *observed;
};

static void *child(void *argument) {
    struct attachment *attachment = argument;
    JavaVM *vm = attachment->vm;
    jint *observed = attachment->observed;
    JNIEnv *env = NULL;
    observed[3] = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    observed[4] = (*vm)->AttachCurrentThread(vm, &env, NULL);
    observed[11] = env != attachment->parent_env;
    observed[5] = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    jclass type = (*env)->FindClass(env,
            "com/github/unidbg/linux/android/dvm/JniThreadAttachmentIntegrationTest$Target");
    jmethodID method = (*env)->GetStaticMethodID(env, type, "record", "()I");
    observed[6] = (*env)->CallStaticIntMethod(env, type, method);
    (*env)->DeleteLocalRef(env, type);
    observed[7] = (*vm)->DetachCurrentThread(vm);
    observed[8] = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    return NULL;
}

JNIEXPORT jlong JNICALL Java_runtime_evaluation_ThreadAttachment_run(JNIEnv *env, jobject self, jlong address) {
    (void)self;
    jint *observed = (jint *)(intptr_t)address;
    struct attachment attachment = {.parent_env = env, .observed = observed};
    observed[0] = (*env)->GetJavaVM(env, &attachment.vm);
    pthread_t thread;
    observed[1] = pthread_create(&thread, NULL, child, &attachment);
    observed[2] = pthread_join(thread, NULL);
    JNIEnv *current = NULL;
    observed[9] = (*attachment.vm)->GetEnv(attachment.vm, (void **)&current, JNI_VERSION_1_6);
    observed[10] = current == env;
    return 0x1122334455667788LL;
}
