#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdint.h>
#include <fcntl.h>
#include <unistd.h>

typedef long (*host_bridge)(int operation, int argument);
static host_bridge bridge;
static pthread_t threads[2];
static atomic_int a_ready, b_ready;
int monitor_results[12];

static void *thread_a(void *unused) {
    (void)unused;
    monitor_results[0] = (int)bridge(1, 0);
    monitor_results[1] = (int)bridge(1, 0);
    monitor_results[2] = (int)bridge(3, 0);
    atomic_store(&a_ready, 1);
    while (atomic_load(&b_ready) < 1) sched_yield();
    monitor_results[3] = (int)bridge(4, 0);
    monitor_results[4] = (int)bridge(2, 0);
    monitor_results[5] = (int)bridge(5, 0);
    monitor_results[6] = (int)bridge(6, 0);
    monitor_results[7] = (int)bridge(7, 0);
    atomic_store(&a_ready, 2);
    while (atomic_load(&b_ready) < 2) sched_yield();
    monitor_results[8] = (int)bridge(8, 0);
    monitor_results[9] = (int)bridge(2, 0);
    return NULL;
}

static void *thread_b(void *unused) {
    (void)unused;
    while (atomic_load(&a_ready) < 1) sched_yield();
    monitor_results[10] = (int)bridge(3, 1);
    atomic_store(&b_ready, 1);
    while (atomic_load(&a_ready) < 2) sched_yield();
    atomic_store(&b_ready, 2);
    monitor_results[11] = (int)bridge(1, 1);
    return NULL;
}

int monitor_start(host_bridge callback) {
    bridge = callback;
    atomic_init(&a_ready, 0);
    atomic_init(&b_ready, 0);
    int first = pthread_create(&threads[0], NULL, thread_a, NULL);
    if (first != 0) return first;
    return pthread_create(&threads[1], NULL, thread_b, NULL);
}

int monitor_join(void) {
    int first = pthread_join(threads[0], NULL);
    if (first != 0) return first;
    return pthread_join(threads[1], NULL);
}

long monitor_nested(void) {
    const unsigned char witness[] = {0x31, 0x00, 0x7f, 0x62};
    int fd = open("/monitor-native.bin", O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (fd < 0) return -100;
    if (write(fd, witness, sizeof(witness)) != sizeof(witness)) {
        close(fd);
        return -101;
    }
    if (close(fd) != 0) return -102;
    return bridge(9, 0) + 0x30;
}

int monitor_main_hold(void) {
    return (int)bridge(1, 2);
}

static pthread_t close_thread;
int monitor_close_result;

static void *close_boundary(void *unused) {
    (void)unused;
    monitor_close_result = (int)bridge(10, 0);
    return NULL;
}

int monitor_close_start(void) {
    return pthread_create(&close_thread, NULL, close_boundary, NULL);
}

int monitor_close_join(void) {
    return pthread_join(close_thread, NULL);
}
