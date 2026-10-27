package com.github.unidbg.linux.file.tcp;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.linux.file.socket.LinuxSocketCalls;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.unidbg.linux.file.socket.LinuxSocketCalls.*;

public final class NativeTcpSocketFactory {
    private static final Logger log = LoggerFactory.getLogger(NativeTcpSocketFactory.class);
    private static final AtomicLong SOCKETS = new AtomicLong();
    private final Set<TcpOperation> pending = new HashSet<>();
    private long nextOperation;

    public AndroidFileIO create(Emulator<?> emulator, int domain, int type, int protocol) {
        if ((domain != AF_INET && domain != AF_INET6) || (type & ~(SOCK_CLOEXEC | SOCK_NONBLOCK)) != SOCK_STREAM) {
            throw new LinuxErrnoException(EINVAL);
        }
        int fd = LinuxSocketCalls.LIBC.socket(domain, type | SOCK_CLOEXEC | SOCK_NONBLOCK, protocol);
        int errno = fd < 0 ? Native.getLastError() : 0;
        long id = SOCKETS.incrementAndGet();
        log.debug("event=native_tcp_created emulator={} socket={} host_fd={} domain={} type={} protocol={} errno={}",
                System.identityHashCode(emulator), id, fd, domain, type, protocol, errno);
        if (fd < 0) {
            throw new LinuxErrnoException(errno);
        }
        return new NativeTcpSocket(emulator, this, id, fd, new NativeTcpSocket.Status((type & SOCK_NONBLOCK) != 0), (type & SOCK_CLOEXEC) != 0);
    }

    NativeTcpSocket accepted(NativeTcpSocket listener, int fd, int flags) {
        long id = SOCKETS.incrementAndGet();
        log.debug("event=native_tcp_accepted emulator={} listener={} socket={} host_fd={} flags={}",
                System.identityHashCode(listener.emulator), listener.id, id, fd, flags);
        return new NativeTcpSocket(listener.emulator, this, id, fd,
                new NativeTcpSocket.Status((flags & SOCK_NONBLOCK) != 0), (flags & SOCK_CLOEXEC) != 0);
    }

    long register(TcpOperation operation) {
        pending.add(operation);
        return ++nextOperation;
    }

    void completed(TcpOperation operation) {
        pending.remove(operation);
    }

    public void destroy() {
        for (TcpOperation operation : new ArrayList<>(pending)) {
            operation.onDestroy(null);
        }
    }
}
