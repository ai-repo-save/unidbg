package com.github.unidbg.linux.file.seqpacket;

import com.github.unidbg.linux.file.socket.LinuxSocketCalls;
import com.github.unidbg.Emulator;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.LinuxErrnoException;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class NativeSeqpacketSocketFactory {
    private static final Logger log = LoggerFactory.getLogger(NativeSeqpacketSocketFactory.class);
    private static final AtomicLong PAIRS = new AtomicLong();
    private final Set<SocketOperation> pending = new HashSet<>();
    private long nextOperation;

    public AndroidFileIO[] createPair(Emulator<?> emulator) {
        if (!emulator.is64Bit()) {
            throw new UnsupportedOperationException("Native SEQPACKET requires the ARM64 guest ABI");
        }
        int[] descriptors = new int[2];
        if (LinuxSocketCalls.LIBC.socketpair(LinuxSocketCalls.AF_UNIX,
                LinuxSocketCalls.SOCK_SEQPACKET | LinuxSocketCalls.SOCK_CLOEXEC, 0, descriptors) < 0) {
            int errno = Native.getLastError();
            log.debug("event=native_socketpair_failed emulator={} errno={}", System.identityHashCode(emulator), errno);
            throw new LinuxErrnoException(errno);
        }
        long pair = PAIRS.incrementAndGet();
        log.debug("event=native_socketpair_created emulator={} pair={} host_fds={},{}",
                System.identityHashCode(emulator), pair, descriptors[0], descriptors[1]);
        return new AndroidFileIO[] {
                new NativeSeqpacketSocket(emulator, this, pair, descriptors[0]),
                new NativeSeqpacketSocket(emulator, this, pair, descriptors[1])
        };
    }

    /** Duplicates the kernel endpoint while assigning waits to this factory and the target emulator. */
    public AndroidFileIO duplicateFrom(AndroidFileIO source, Emulator<?> target) {
        if (!target.is64Bit()) {
            throw new UnsupportedOperationException("Native SEQPACKET requires the ARM64 guest ABI");
        }
        if (!(source instanceof NativeSeqpacketSocket)) {
            throw new IllegalArgumentException("Source is not a native SEQPACKET socket");
        }
        return ((NativeSeqpacketSocket) source).duplicateTo(target, this);
    }

    long register(SocketOperation operation) {
        pending.add(operation);
        return ++nextOperation;
    }

    void completed(SocketOperation operation) {
        pending.remove(operation);
    }

    public void destroy() {
        for (SocketOperation operation : new ArrayList<>(pending)) {
            operation.onDestroy(null);
        }
    }
}
