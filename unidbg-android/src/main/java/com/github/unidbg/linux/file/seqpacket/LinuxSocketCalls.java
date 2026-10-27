package com.github.unidbg.linux.file.seqpacket;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LinuxSocketCalls {
    static final int AF_UNIX = 1, SOCK_SEQPACKET = 5, SOCK_CLOEXEC = 0x80000;
    static final int SOL_SOCKET = 1, SO_RCVTIMEO = 20, SO_SNDTIMEO = 21;
    static final int MSG_DONTWAIT = 0x40, MSG_NOSIGNAL = 0x4000;
    static final int F_GETFL = 3, F_SETFL = 4, O_NONBLOCK = 0x800;
    static final int EAGAIN = 11, EINTR = 4, EINVAL = 22;
    private static final Logger log = LoggerFactory.getLogger(LinuxSocketCalls.class);

    public interface LibC extends Library {
        int socketpair(int domain, int type, int protocol, int[] descriptors);
        NativeLong recv(int fd, byte[] buffer, NativeLong size, int flags);
        NativeLong send(int fd, byte[] buffer, NativeLong size, int flags);
        int setsockopt(int fd, int level, int name, byte[] value, int length);
        int getsockopt(int fd, int level, int name, byte[] value, IntByReference length);
        int fcntl(int fd, int command, int argument);
        int close(int fd);
        int shutdown(int fd, int how);
    }

    static final LibC LIBC = load();

    private static LibC load() {
        if (!Platform.isLinux() || !Platform.isIntel() || !Platform.is64Bit() || Native.LONG_SIZE != 8) {
            throw new UnsupportedOperationException("Native SEQPACKET requires a Linux x86_64 host");
        }
        return Native.load(Platform.C_LIBRARY_NAME, LibC.class);
    }

    static int duplicate(int fd) {
        int result = LIBC.fcntl(fd, 1030, 0); // F_DUPFD_CLOEXEC, private host descriptors never survive exec.
        if (result < 0) {
            int errno = Native.getLastError();
            throw new IllegalStateException("fcntl(F_DUPFD_CLOEXEC) fd=" + fd + ", errno=" + errno);
        }
        return result;
    }

    static void close(int fd) {
        if (LIBC.close(fd) < 0) {
            int errno = Native.getLastError();
            log.warn("event=native_socket_close_failed host_fd={} errno={}", fd, errno);
        }
    }

    private LinuxSocketCalls() {
    }
}
