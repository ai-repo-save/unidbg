package com.github.unidbg.linux.file.tcp;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.linux.file.socket.LinuxSocketCalls;
import com.github.unidbg.linux.file.socket.NativeSocketCloseService;
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
    private final Set<NativeTcpSocket> views = new HashSet<>();
    private NativeSocketCloseService closes;
    private Emulator<?> emulator;
    private boolean closing;
    private long nextOperation;

    public AndroidFileIO create(Emulator<?> emulator, int domain, int type, int protocol) {
        if ((domain != AF_INET && domain != AF_INET6) || (type & ~(SOCK_CLOEXEC | SOCK_NONBLOCK)) != SOCK_STREAM) {
            throw new LinuxErrnoException(EINVAL);
        }
        if (closing) {
            throw new IllegalStateException("TCP socket factory is closing");
        }
        if (closes == null) {
            this.emulator = emulator;
            closes = new NativeSocketCloseService(emulator, this::releaseOwned);
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

    void opened(NativeTcpSocket socket) {
        views.add(socket);
    }

    void closed(NativeTcpSocket socket) {
        views.remove(socket);
    }

    NativeSocketCloseService.Request release(long socket, int fd) {
        return closes.submit(socket, fd);
    }

    private void releaseOwned() {
        closing = true;
        for (TcpOperation operation : new ArrayList<>(pending)) {
            operation.onDestroy(emulator);
        }
        for (NativeTcpSocket socket : new ArrayList<>(views)) {
            socket.close();
        }
    }

    public void destroy() {
        if (closes != null) {
            closes.beginClose();
        }
    }
}
