package com.github.unidbg.linux.file.seqpacket;

import com.github.unidbg.linux.file.socket.LinuxSocketCalls;
import com.github.unidbg.Emulator;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.thread.GuestActivity;
import com.github.unidbg.thread.Waiter;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class SocketOperation implements Waiter {
    private static final Logger log = LoggerFactory.getLogger(SocketOperation.class);
    private final NativeSeqpacketSocketFactory owner;
    private final long pair, id;
    private final int fd, flags, size;
    private final boolean sending, nonblocking;
    private final byte[] bytes;
    private final Pointer destination;
    /** Longest single kernel wait, which keeps the emulator's other guest threads on their turn. */
    private static final int WAIT_SLICE_MILLIS = 1;
    private final long timeout;
    /** Time the peer stayed silent, which excludes the time another host thread ran guest code. */
    private long quietNanos;
    private long lastSample = System.nanoTime();
    private boolean finished, closed, interrupted;
    private int result, errno;

    SocketOperation(NativeSeqpacketSocketFactory owner, long pair, int socket, int flags,
                    byte[] source, Pointer destination, int size) {
        this.owner = owner;
        this.pair = pair;
        this.fd = LinuxSocketCalls.duplicate(socket);
        this.flags = flags;
        this.sending = source != null;
        this.bytes = source == null || source.length == 0 ? new byte[Math.max(1, size)] : source;
        this.destination = destination;
        this.size = size;
        try {
            int status = LinuxSocketCalls.LIBC.fcntl(fd, LinuxSocketCalls.F_GETFL, 0);
            if (status < 0) {
                int error = Native.getLastError();
                throw new IllegalStateException("fcntl(F_GETFL) host_fd=" + fd + ", errno=" + error);
            }
            nonblocking = (flags & LinuxSocketCalls.MSG_DONTWAIT) != 0 || (status & LinuxSocketCalls.O_NONBLOCK) != 0;
            timeout = timeoutNanos();
        } catch (RuntimeException | Error failure) {
            LinuxSocketCalls.close(fd);
            throw failure;
        }
        this.id = owner.register(this);
        log.debug("event=native_socket_operation_started pair={} operation={} host_fd={} sending={} size={} flags={} timeout_ns={}",
                pair, id, fd, sending, size, flags, timeout);
        if (sending && log.isDebugEnabled()) {
            log.debug("event=native_socket_send_data pair={} operation={} hex={}", pair, id, Hex.encodeHexString(source));
        }
    }

    /** The timeout the guest set on this socket, in nanoseconds; zero waits until the peer acts. */
    private long timeoutNanos() {
        byte[] value = new byte[16];
        if (LinuxSocketCalls.LIBC.getsockopt(fd, LinuxSocketCalls.SOL_SOCKET,
                sending ? LinuxSocketCalls.SO_SNDTIMEO : LinuxSocketCalls.SO_RCVTIMEO,
                value, new IntByReference(value.length)) < 0) {
            int error = Native.getLastError();
            throw new IllegalStateException("getsockopt(timeout) host_fd=" + fd + ", errno=" + error);
        }
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.nativeOrder());
        long seconds = TimeUnit.SECONDS.toNanos(buffer.getLong());
        long micros = TimeUnit.MICROSECONDS.toNanos(buffer.getLong());
        return Long.MAX_VALUE - seconds < micros ? Long.MAX_VALUE : seconds + micros;
    }

    @Override
    public boolean canDispatch() {
        if (finished) {
            return true;
        }
        if (interrupted) {
            complete(-1, LinuxSocketCalls.EINTR);
            return true;
        }
        int hostFlags = flags | LinuxSocketCalls.MSG_DONTWAIT;
        long count = sending
                ? LinuxSocketCalls.LIBC.send(fd, bytes, new NativeLong(size), hostFlags | LinuxSocketCalls.MSG_NOSIGNAL).longValue()
                : LinuxSocketCalls.LIBC.recv(fd, bytes, new NativeLong(size), hostFlags).longValue();
        int error = count < 0 ? Native.getLastError() : 0;
        if (count < 0 && error == LinuxSocketCalls.EAGAIN && !nonblocking) {
            if (timeout != 0) {
                long sampled = System.nanoTime();
                if (!GuestActivity.elsewhere()) {
                    quietNanos += sampled - lastSample;
                }
                lastSample = sampled;
                if (quietNanos >= timeout) {
                    complete(-1, LinuxSocketCalls.EAGAIN);
                    return true;
                }
            }
            awaitPeer();
            return false;
        }
        complete((int) count, error);
        return true;
    }

    /**
     * Parks this host thread in the kernel until the peer is ready, so a blocking transfer costs no CPU
     * and ends on the peer's progress. One slice at most, because the emulator's other guest threads
     * share this host thread.
     *
     * The wait lasts one slice, which is the granularity at which this emulator's other guest threads get
     * their turn; a caller that must keep waiting returns and is called again.
     */
    private void awaitPeer() {
        LinuxSocketCalls.await(fd, sending ? LinuxSocketCalls.POLLOUT : LinuxSocketCalls.POLLIN, WAIT_SLICE_MILLIS);
    }

    private void complete(int result, int errno) {
        this.result = result;
        this.errno = errno;
        this.finished = true;
        log.debug("event=native_socket_operation_completed pair={} operation={} host_fd={} result={} errno={}",
                pair, id, fd, result, errno);
        if (!sending && result > 0 && log.isDebugEnabled()) {
            log.debug("event=native_socket_receive_data pair={} operation={} hex={}", pair, id,
                    Hex.encodeHexString(Arrays.copyOf(bytes, Math.min(result, size))));
        }
        release();
    }

    int result() {
        if (!finished) {
            throw new IllegalStateException("Socket operation is not complete: " + id);
        }
        if (!sending && result > 0) {
            destination.write(0, bytes, 0, Math.min(result, size));
        }
        // SDK23 Bionic's SVC wrappers translate -errno into the calling native thread's TLS.
        return result < 0 ? -errno : result;
    }

    @Override
    public void onContinueRun(Emulator<?> emulator) {
        emulator.getBackend().reg_write(Arm64Const.UC_ARM64_REG_X0, result());
    }

    @Override
    public void onSignal(SignalTask task) {
        interrupted = true;
    }

    @Override
    public void onDestroy(Emulator<?> emulator) {
        if (!closed) {
            log.debug("event=native_socket_operation_cancelled pair={} operation={} host_fd={}", pair, id, fd);
            release();
        }
    }

    private void release() {
        if (!closed) {
            closed = true;
            LinuxSocketCalls.close(fd);
            owner.completed(this);
        }
    }
}
