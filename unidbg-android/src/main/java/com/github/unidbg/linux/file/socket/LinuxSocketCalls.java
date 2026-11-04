package com.github.unidbg.linux.file.socket;

import com.github.unidbg.linux.LinuxErrnoException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class LinuxSocketCalls {
    public static final int AF_UNIX = 1, AF_INET = 2, AF_INET6 = 10;
    public static final int SOCK_STREAM = 1, SOCK_SEQPACKET = 5, SOCK_NONBLOCK = 0x800, SOCK_CLOEXEC = 0x80000;
    public static final int SOL_SOCKET = 1, SO_ERROR = 4, SO_RCVLOWAT = 18, SO_RCVTIMEO = 20, SO_SNDTIMEO = 21;
    public static final int MSG_OOB = 1, MSG_PEEK = 2, MSG_TRUNC = 0x20, MSG_DONTWAIT = 0x40, MSG_WAITALL = 0x100, MSG_NOSIGNAL = 0x4000;
    public static final int F_GETFD = 1, F_SETFD = 2, F_GETFL = 3, F_SETFL = 4, F_DUPFD_CLOEXEC = 1030, O_NONBLOCK = 0x800;
    public static final int EBADF = 9, EAGAIN = 11, EINTR = 4, EFAULT = 14, EINVAL = 22, EINPROGRESS = 115;
    public static final short POLLIN = 1, POLLOUT = 4, POLLHUP = 0x10, POLLRDHUP = 0x2000;
    private static final Logger log = LoggerFactory.getLogger(LinuxSocketCalls.class);

    public interface LibC extends Library {
        int socket(int domain, int type, int protocol);
        int connect(int fd, byte[] address, int length);
        int bind(int fd, byte[] address, int length);
        int listen(int fd, int backlog);
        int accept4(int fd, byte[] address, IntByReference length, int flags);
        int getsockname(int fd, byte[] address, IntByReference length);
        int getpeername(int fd, byte[] address, IntByReference length);
        NativeLong recvfrom(int fd, byte[] buffer, NativeLong size, int flags, byte[] address, IntByReference length);
        NativeLong sendto(int fd, byte[] buffer, NativeLong size, int flags, byte[] address, int length);
        int poll(byte[] descriptors, NativeLong count, int timeout);
        int socketpair(int domain, int type, int protocol, int[] descriptors);
        NativeLong recv(int fd, byte[] buffer, NativeLong size, int flags);
        NativeLong send(int fd, byte[] buffer, NativeLong size, int flags);
        int setsockopt(int fd, int level, int name, byte[] value, int length);
        int getsockopt(int fd, int level, int name, byte[] value, IntByReference length);
        int fcntl(int fd, int command, int argument);
        int close(int fd);
        int shutdown(int fd, int how);
    }

    public static final LibC LIBC = load();

    private static LibC load() {
        if (!Platform.isLinux() || !Platform.isIntel() || !Platform.is64Bit() || Native.LONG_SIZE != 8) {
            throw new UnsupportedOperationException("Native sockets require a Linux x86_64 host");
        }
        return Native.load(Platform.C_LIBRARY_NAME, LibC.class);
    }

    public static int duplicate(int fd) {
        int result = LIBC.fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (result < 0) {
            int errno = Native.getLastError();
            log.debug("event=native_socket_duplicate_failed host_fd={} errno={}", fd, errno);
            throw new LinuxErrnoException(errno);
        }
        return result;
    }

    /**
     * Waits until the descriptor reports {@code events} or the millisecond budget elapses, which parks
     * the calling host thread in the kernel the way a process waits on its own socket. A negative
     * budget waits without a deadline. An interrupted wait leaves the descriptor state to the caller's
     * next readiness check.
     */
    public static void await(int fd, short events, int timeoutMillis) {
        byte[] descriptor = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                .putInt(fd).putShort(events).putShort((short) 0).array();
        if (LIBC.poll(descriptor, new NativeLong(1), timeoutMillis) < 0) {
            log.debug("event=native_socket_await_end host_fd={} events={} errno={}", fd, events, Native.getLastError());
        }
    }

    public static short poll(int fd, short events) {
        byte[] descriptor = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                .putInt(fd).putShort(events).putShort((short) 0).array();
        int result = LIBC.poll(descriptor, new NativeLong(1), 0);
        if (result < 0) {
            int errno = Native.getLastError();
            log.debug("event=native_socket_poll_failed host_fd={} events={} errno={}", fd, events, errno);
            throw new LinuxErrnoException(errno);
        }
        return ByteBuffer.wrap(descriptor).order(ByteOrder.nativeOrder()).getShort(6);
    }

    public static void close(int fd) {
        if (LIBC.close(fd) < 0) {
            int errno = Native.getLastError();
            log.warn("event=native_socket_close_failed host_fd={} errno={}", fd, errno);
        }
    }

    private LinuxSocketCalls() {
    }
}
