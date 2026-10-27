#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

/* Linux unix(7), recv(2), socket(7), dup(2), close(2). */
_Atomic int ipc_progress;
int ipc_results[18];
unsigned char ipc_bytes[32];
static int pair[2], closing[2];
static _Atomic int queued;
static const unsigned char first[] = {0x13, 0x57, 0x9b, 0xdf, 1, 2, 3, 4};
static const unsigned char second[] = {5, 6, 7, 8, 9, 10};
static const unsigned char third[] = {11, 12, 13};
static const uint32_t ack = 0x12345678;

static void *receiver(void *unused) {
    ipc_results[0] = recv(pair[0], ipc_bytes, 4, MSG_PEEK);
    ipc_results[1] = recv(pair[0], ipc_bytes + 4, 4, MSG_PEEK);
    ipc_results[2] = recv(pair[0], ipc_bytes + 8, 8, 0);
    ipc_results[3] = recv(pair[0], ipc_bytes + 16, 2, 0);
    ipc_results[4] = recv(pair[0], ipc_bytes + 18, 8, 0);
    errno = 123;
    ipc_results[5] = recv(pair[0], ipc_bytes + 26, 1, 0);
    ipc_results[6] = errno;
    ipc_results[7] = send(pair[0], &ack, sizeof(ack), MSG_NOSIGNAL);
    unsigned char buffer[1024];
    for (int i = 0; i <= queued; ++i) {
        int n = recv(pair[0], buffer, sizeof(buffer), 0);
        if (n != sizeof(buffer)) ipc_results[8]++;
        for (int j = 0; j < n; ++j) if (buffer[j] != 0x5a) ipc_results[8]++;
    }
    send(pair[0], &ack, sizeof(ack), MSG_NOSIGNAL);
    ipc_results[11] = recv(pair[0], buffer, sizeof(buffer), 0);
    ipc_results[12] = send(pair[0], &ack, sizeof(ack), MSG_NOSIGNAL);
    ipc_results[13] = errno;
    close(pair[0]);
    ipc_results[15] = 1;
    atomic_store(&ipc_progress, 1);
    return NULL;
}

static void *sender(void *unused) {
    int a = dup(pair[1]), b = dup(pair[1]);
    if (a < 0 || b < 0 || dup2(a, b) != b) { ipc_results[14] = 1; return NULL; }
    close(a);
    close(pair[1]);
    if (send(b, first, sizeof(first), MSG_EOR | MSG_NOSIGNAL) != sizeof(first) ||
        send(b, second, sizeof(second), MSG_NOSIGNAL) != sizeof(second) ||
        send(b, third, sizeof(third), MSG_NOSIGNAL) != sizeof(third)) ipc_results[14] = 2;
    uint32_t reply = 0;
    if (recv(b, &reply, sizeof(reply), 0) != sizeof(reply) || reply != ack) ipc_results[14] = 3;
    unsigned char buffer[1024];
    memset(buffer, 0x5a, sizeof(buffer));
    for (queued = 0; queued < 1024; ++queued) {
        int n = send(b, buffer, sizeof(buffer), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n == -1 && errno == EAGAIN) break;
        if (n != sizeof(buffer)) { ipc_results[14] = 4; break; }
    }
    if (queued == 1024) ipc_results[14] = 5;
    ipc_results[9] = send(b, buffer, sizeof(buffer), MSG_NOSIGNAL);
    ipc_results[10] = recv(b, &reply, sizeof(reply), 0);
    if (reply != ack) ipc_results[14] = 6;
    close(b);
    ipc_results[16] = 1;
    return NULL;
}

int ipc_start(void) {
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, pair)) return -errno;
    struct timeval timeout = {0, 40000};
    int size = 4096;
    if (setsockopt(pair[0], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(pair[1], SOL_SOCKET, SO_SNDBUF, &size, sizeof(size))) return -errno;
    pthread_t reader, writer;
    int result = pthread_create(&reader, NULL, receiver, NULL);
    if (!result) result = pthread_create(&writer, NULL, sender, NULL);
    errno = EDOM;
    return result;
}

int ipc_main_errno(void) { return errno; }

static void *closing_receiver(void *unused) {
    ipc_results[17] = recv(closing[0], ipc_bytes + 28, 4, 0);
    return NULL;
}

int ipc_wait_start(void) {
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, closing)) return -errno;
    pthread_t thread;
    return pthread_create(&thread, NULL, closing_receiver, NULL);
}

int ipc_close_reader(void) { return close(closing[0]); }

int ipc_release_reader(void) {
    int result = send(closing[1], &ack, sizeof(ack), MSG_NOSIGNAL);
    close(closing[1]);
    return result;
}

static void *main_sender(void *unused) {
    send(closing[1], &ack, sizeof(ack), MSG_NOSIGNAL);
    close(closing[1]);
    return NULL;
}

int ipc_main_wait(void (*ready)(void)) {
    ready();
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, closing)) return -errno;
    pthread_t thread;
    int error = pthread_create(&thread, NULL, main_sender, NULL);
    if (error) return -error;
    uint32_t result = 0;
    int count = recv(closing[0], &result, sizeof(result), 0);
    close(closing[0]);
    return result == ack ? count : -1;
}

int ipc_send_timeout_result[2];

int ipc_send_timeout(void) {
    int sockets[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sockets)) return -errno;
    struct timeval timeout = {0, 20000};
    int size = 4096;
    if (setsockopt(sockets[0], SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(sockets[0], SOL_SOCKET, SO_SNDBUF, &size, sizeof(size))) return -errno;
    unsigned char buffer[1024] = {0};
    for (int i = 0; i < 1024; ++i) {
        int n = send(sockets[0], buffer, sizeof(buffer), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n == -1 && errno == EAGAIN) break;
        if (n != sizeof(buffer) || i == 1023) return -1;
    }
    errno = 123;
    ipc_send_timeout_result[0] = send(sockets[0], buffer, sizeof(buffer), MSG_NOSIGNAL);
    ipc_send_timeout_result[1] = errno;
    close(sockets[0]);
    close(sockets[1]);
    return 0;
}
