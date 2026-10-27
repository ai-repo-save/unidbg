#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

/* Linux tcp(7), socket(7), connect(2), accept(2), recv(2), poll(2), dup(2). */
static int listener = -1, held = -1;
static struct sockaddr_storage endpoint;
static socklen_t endpoint_size;
int tcp_results[16];
int tcp_error_results[8];
int tcp_readiness_results[8];
unsigned char tcp_bytes[16];
static const unsigned char request[] = {0x13, 0x57, 0x9b, 0xdf};
static const unsigned char reply[] = {0x24, 0x68, 0xac, 0xe0};

static int address(int family, unsigned short port) {
    memset(&endpoint, 0, sizeof(endpoint));
    if (family == AF_INET) {
        struct sockaddr_in *a = (struct sockaddr_in *)&endpoint;
        a->sin_family = AF_INET;
        a->sin_port = htons(port);
        a->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        endpoint_size = sizeof(*a);
    } else {
        struct sockaddr_in6 *a = (struct sockaddr_in6 *)&endpoint;
        a->sin6_family = AF_INET6;
        a->sin6_port = htons(port);
        a->sin6_addr = in6addr_loopback;
        endpoint_size = sizeof(*a);
    }
    return 0;
}

static int timeout(int fd, int option) {
    struct timeval value = {0, 40000}, readback = {0};
    socklen_t size = sizeof(readback);
    if (setsockopt(fd, SOL_SOCKET, option, &value, sizeof(value)) ||
        getsockopt(fd, SOL_SOCKET, option, &readback, &size)) return -errno;
    return size == sizeof(readback) && readback.tv_sec == 0 && readback.tv_usec == 40000 ? 0 : -1000;
}

int tcp_open_server(int family) {
    address(family, 0);
    listener = socket(family, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
    if (listener < 0) return -errno;
    if (bind(listener, (struct sockaddr *)&endpoint, endpoint_size) || listen(listener, 8) ||
        getsockname(listener, (struct sockaddr *)&endpoint, &endpoint_size)) return -errno;
    if (family == AF_INET) return ntohs(((struct sockaddr_in *)&endpoint)->sin_port);
    return ntohs(((struct sockaddr_in6 *)&endpoint)->sin6_port);
}

int tcp_accept_exchange(void) {
    struct sockaddr_storage peer;
    socklen_t size = sizeof(peer);
    int fd = accept4(listener, (struct sockaddr *)&peer, &size, SOCK_CLOEXEC);
    if (fd < 0) return -errno;
    int expected_size = peer.ss_family == AF_INET ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6);
    if (size != (socklen_t)expected_size || fcntl(fd, F_GETFD) != FD_CLOEXEC ||
        (fcntl(fd, F_GETFL) & O_NONBLOCK)) return -1001;
    struct sockaddr_storage source;
    socklen_t source_size = sizeof(source);
    tcp_results[0] = recvfrom(fd, tcp_bytes, 2, MSG_PEEK, (struct sockaddr *)&source, &source_size);
    if (source_size != 0) return -1013;
    tcp_results[1] = recv(fd, tcp_bytes + 2, 2, MSG_PEEK);
    unsigned char ready = 0x7e;
    if (send(fd, &ready, 1, MSG_NOSIGNAL) != 1) return -errno;
    tcp_results[2] = recv(fd, tcp_bytes + 4, 4, MSG_WAITALL);
    tcp_results[3] = send(fd, reply, sizeof(reply), MSG_NOSIGNAL);
    struct sockaddr_storage truncated;
    memset(&truncated, 0xa5, sizeof(truncated));
    size = 2;
    if (getpeername(fd, (struct sockaddr *)&truncated, &size) || size != (socklen_t)expected_size ||
        truncated.ss_family != peer.ss_family || ((unsigned char *)&truncated)[2] != 0xa5) return -1002;
    int copy = dup(fd);
    if (copy < 0 || dup2(fd, copy) != copy || close(fd)) return -errno;
    if (fcntl(copy, F_SETFD, FD_CLOEXEC) || fcntl(copy, F_GETFD) != FD_CLOEXEC) return -1003;
    if (timeout(copy, SO_RCVTIMEO)) return -1004;
    errno = 0;
    tcp_results[4] = recv(copy, tcp_bytes + 8, 1, 0);
    tcp_results[5] = errno;
    if (shutdown(copy, SHUT_WR)) return -errno;
    tcp_results[6] = recv(copy, tcp_bytes + 8, 1, 0);
    errno = 0;
    tcp_results[7] = send(copy, reply, sizeof(reply), MSG_NOSIGNAL);
    tcp_results[8] = errno;
    close(copy);
    close(listener);
    listener = -1;
    return 0;
}

int tcp_connect_exchange(int family, int port) {
    address(family, (unsigned short)port);
    int fd = socket(family, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (fd < 0) return -errno;
    int duplicate = dup(fd);
    if (duplicate < 0) return -errno;
    if (fcntl(duplicate, F_GETFD) != 0 || !(fcntl(duplicate, F_GETFL) & O_NONBLOCK)) return -1005;
    errno = 0;
    int result = connect(fd, (struct sockaddr *)&endpoint, endpoint_size);
    if (result && errno != EINPROGRESS) return -errno;
    struct pollfd event = {fd, POLLIN | POLLOUT, 0};
    if (poll(&event, 1, 1000) != 1 || event.revents != POLLOUT) return -1006;
    unsigned char unavailable;
    errno = 0;
    tcp_error_results[0] = recv(fd, &unavailable, 1, 0);
    tcp_error_results[1] = errno;
    if (dup2(fd, 65) != 65) return -errno;
    fd_set reads, writes;
    FD_ZERO(&reads);
    FD_ZERO(&writes);
    FD_SET(fd, &reads);
    FD_SET(fd, &writes);
    FD_SET(65, &writes);
    struct timeval immediate = {0, 0};
    tcp_readiness_results[0] = select(66, &reads, &writes, NULL, &immediate);
    tcp_readiness_results[1] = FD_ISSET(fd, &reads);
    tcp_readiness_results[2] = FD_ISSET(fd, &writes);
    tcp_readiness_results[3] = FD_ISSET(65, &writes);
    FD_ZERO(&reads);
    FD_SET(65, &reads);
    struct timespec instant = {0, 0};
    tcp_readiness_results[4] = pselect(66, &reads, NULL, NULL, &instant, NULL);
    tcp_readiness_results[5] = FD_ISSET(65, &reads);
    close(65);
    struct pollfd waiting = {fd, POLLIN, 0};
    tcp_readiness_results[6] = poll(&waiting, 1, 20);
    tcp_readiness_results[7] = waiting.revents;
    int error = -1;
    socklen_t size = sizeof(error);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) || error) return error ? -error : -errno;
    if (fcntl(duplicate, F_SETFL, fcntl(duplicate, F_GETFL) & ~O_NONBLOCK) || (fcntl(fd, F_GETFL) & O_NONBLOCK)) return -1007;
    int enabled = 1, actual = 0;
    size = sizeof(actual);
    if (setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled)) ||
        getsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &actual, &size) || actual != 1) return -1008;
    close(duplicate);
    tcp_results[9] = write(fd, request, sizeof(request));
    int received = 0;
    while (received < 4) {
        int count = read(fd, tcp_bytes + 12 + received, 4 - received);
        if (count <= 0) return count < 0 ? -errno : -1014;
        received += count;
    }
    tcp_results[10] = received;
    close(fd);
    return 0;
}

int tcp_errors(int family, int port) {
    address(family, (unsigned short)port);
    int fd = socket(family, SOCK_STREAM, 0);
    if (fd < 0) return -errno;
    errno = 0;
    tcp_results[11] = connect(fd, (struct sockaddr *)&endpoint, endpoint_size);
    tcp_results[12] = errno;
    close(fd);
    int first = socket(family, SOCK_STREAM | SOCK_NONBLOCK, 0), second = socket(family, SOCK_STREAM, 0);
    if (first < 0 || second < 0) return -errno;
    address(family, 0);
    if (bind(first, (struct sockaddr *)&endpoint, endpoint_size) || getsockname(first, (struct sockaddr *)&endpoint, &endpoint_size)) return -errno;
    errno = 0;
    tcp_results[13] = bind(second, (struct sockaddr *)&endpoint, endpoint_size);
    tcp_results[14] = errno;
    if (listen(first, 1)) return -errno;
    errno = 0;
    tcp_error_results[2] = accept4(first, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
    tcp_error_results[3] = errno;
    errno = 0;
    tcp_error_results[4] = accept4(first, NULL, NULL, 1);
    tcp_error_results[5] = errno;
    int option;
    socklen_t option_size = sizeof(option);
    errno = 0;
    tcp_error_results[6] = getsockopt(first, SOL_SOCKET, 0x7fffffff, &option, &option_size);
    tcp_error_results[7] = errno;
    close(first);
    close(second);
    return 0;
}

static void *guest_receiver(void *unused) {
    int fd = accept(listener, NULL, NULL);
    if (fd < 0) { tcp_results[15] = -errno; return NULL; }
    unsigned char bytes[4];
    if (recv(fd, bytes, sizeof(bytes), MSG_WAITALL) != sizeof(bytes) || memcmp(bytes, request, sizeof(bytes)) ||
        send(fd, reply, sizeof(reply), MSG_NOSIGNAL) != sizeof(reply)) tcp_results[15] = -1009;
    close(fd);
    close(listener);
    listener = -1;
    return NULL;
}

int tcp_guest_exchange(int family) {
    int port = tcp_open_server(family);
    if (port < 0) return port;
    pthread_t thread;
    int error = pthread_create(&thread, NULL, guest_receiver, NULL);
    if (error) return -error;
    int fd = socket(family, SOCK_STREAM, 0);
    if (connect(fd, (struct sockaddr *)&endpoint, endpoint_size) ||
        send(fd, request, sizeof(request), MSG_NOSIGNAL) != sizeof(request)) return -errno;
    unsigned char bytes[4];
    if (recv(fd, bytes, sizeof(bytes), MSG_WAITALL) != sizeof(bytes) || memcmp(bytes, reply, sizeof(bytes))) return -1010;
    close(fd);
    return 0;
}

static void *pending_receiver(void *unused) {
    tcp_results[15] = recv(held, tcp_bytes + 8, 4, MSG_WAITALL);
    return NULL;
}

int tcp_pending_start(int family, int port) {
    address(family, (unsigned short)port);
    held = socket(family, SOCK_STREAM, 0);
    if (held < 0 || connect(held, (struct sockaddr *)&endpoint, endpoint_size)) return -errno;
    pthread_t thread;
    return pthread_create(&thread, NULL, pending_receiver, NULL);
}

int tcp_pending_close(void) {
    int result = close(held);
    held = -1;
    return result;
}

int tcp_pending_accept(int family) {
    int port = tcp_open_server(family);
    if (port < 0) return port;
    pthread_t thread;
    return pthread_create(&thread, NULL, guest_receiver, NULL);
}

int tcp_transferred;

int tcp_send_timeout(int family, int port) {
    address(family, (unsigned short)port);
    int fd = socket(family, SOCK_STREAM, 0), size = 4096;
    if (fd < 0 || setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &size, sizeof(size)) ||
        connect(fd, (struct sockaddr *)&endpoint, endpoint_size)) return -errno;
    int error = timeout(fd, SO_SNDTIMEO);
    if (error) return error;
    unsigned char buffer[8192];
    memset(buffer, 0x5a, sizeof(buffer));
    tcp_transferred = 0;
    for (int i = 0; i < 1024; ++i) {
        int n = send(fd, buffer, sizeof(buffer), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n < 0 && errno == EAGAIN) break;
        if (n < 0) return -errno;
        tcp_transferred += n;
        if (i == 1023) return -1011;
    }
    for (int i = 0; i < 1024; ++i) {
        errno = 0;
        int n = send(fd, buffer, sizeof(buffer), MSG_NOSIGNAL);
        if (n < 0 && errno == EAGAIN) {
            close(fd);
            return 0;
        }
        if (n < 0) return -errno;
        tcp_transferred += n;
    }
    close(fd);
    return -1012;
}

int tcp_send_blocking(int family, int port, const unsigned char *bytes, int size) {
    address(family, (unsigned short)port);
    int fd = socket(family, SOCK_STREAM, 0), capacity = 4096;
    if (fd < 0 || setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &capacity, sizeof(capacity)) ||
        connect(fd, (struct sockaddr *)&endpoint, endpoint_size)) return -errno;
    struct timeval deadline = {2, 0};
    if (setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &deadline, sizeof(deadline))) return -errno;
    int result = send(fd, bytes, size, MSG_NOSIGNAL);
    int error = result < 0 ? errno : 0;
    close(fd);
    return result < 0 ? -error : result;
}

static int linger_fd, linger_mode, linger_notified;
static void (*linger_callback)(void);
int tcp_linger_results[3];

int tcp_linger_prepare(int family, int port) {
    address(family, (unsigned short)port);
    linger_fd = socket(family, SOCK_STREAM, 0);
    int capacity = 4096;
    struct linger linger = {1, 2};
    if (linger_fd < 0 || setsockopt(linger_fd, SOL_SOCKET, SO_SNDBUF, &capacity, sizeof(capacity)) ||
        setsockopt(linger_fd, SOL_SOCKET, SO_LINGER, &linger, sizeof(linger)) ||
        connect(linger_fd, (struct sockaddr *)&endpoint, endpoint_size)) return -errno;
    unsigned char bytes[8192];
    memset(bytes, 0x6d, sizeof(bytes));
    int total = 0;
    for (int i = 0; i < 1024; ++i) {
        int count = send(linger_fd, bytes, sizeof(bytes), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (count < 0 && errno == EAGAIN) return total;
        if (count < 0) return -errno;
        total += count;
    }
    return -1015;
}

static void *linger_notify(void *unused) {
    if (linger_mode) {
        struct linger current = {-1, -1};
        socklen_t size = sizeof(current);
        tcp_linger_results[2] = getsockopt(linger_fd, SOL_SOCKET, SO_LINGER, &current, &size) ? -errno : current.l_onoff;
    }
    linger_callback();
    linger_notified = 1;
    return NULL;
}

int tcp_linger_finish(int mode, void (*notify)(void)) {
    linger_mode = mode;
    linger_notified = 0;
    linger_callback = notify;
    memset(tcp_linger_results, 0, sizeof(tcp_linger_results));
    int replacement = mode ? socket(endpoint.ss_family, SOCK_STREAM, 0) : -1;
    if (mode && replacement < 0) return -errno;
    pthread_t thread;
    int error = pthread_create(&thread, NULL, linger_notify, NULL);
    if (error) return -error;
    int result = mode ? dup2(replacement, linger_fd) : close(linger_fd);
    tcp_linger_results[0] = mode && result == linger_fd ? 0 : result;
    tcp_linger_results[1] = linger_notified;
    if (mode) {
        close(replacement);
        close(linger_fd);
    }
    return 0;
}

static void *linger_pending(void *unused) {
    linger_callback();
    close(linger_fd);
    return NULL;
}

int tcp_linger_pending_start(void (*ready)(void)) {
    linger_callback = ready;
    pthread_t thread;
    return pthread_create(&thread, NULL, linger_pending, NULL);
}
