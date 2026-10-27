#include <pthread.h>
#include <stdatomic.h>

_Atomic int ipc_progress;

static void *child(void *arg) {
    atomic_store(&ipc_progress, 1);
    return NULL;
}

int ipc_start(void) {
    pthread_t thread;
    return pthread_create(&thread, NULL, child, NULL);
}
