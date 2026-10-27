package com.github.unidbg.linux.file.tcp;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.file.socket.LinuxSocketCalls;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.thread.RunnableTask;
import com.github.unidbg.thread.ThreadContextSwitchException;
import com.github.unidbg.thread.Waiter;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.ToIntFunction;

import static com.github.unidbg.linux.file.socket.LinuxSocketCalls.*;

abstract class TcpOperation implements Waiter {
    private static final Logger log = LoggerFactory.getLogger(TcpOperation.class);
    final NativeTcpSocket socket;
    final int fd;
    final boolean nonblocking;
    final long id;
    private final long timeout, started = System.nanoTime();
    private final int timeoutErrno;
    private boolean finished, released, destroyed, interrupted;
    int result;

    TcpOperation(NativeTcpSocket socket, boolean sending, int flags, int timeoutErrno) {
        this.socket = socket;
        fd = LinuxSocketCalls.duplicate(socket.fd());
        nonblocking = socket.nonblocking() || (flags & MSG_DONTWAIT) != 0;
        try {
            timeout = SocketOptions.timeoutNanos(fd, sending);
        } catch (RuntimeException | Error failure) {
            LinuxSocketCalls.close(fd);
            throw failure;
        }
        this.timeoutErrno = timeoutErrno;
        id = socket.owner.register(this);
        log.debug("event=native_tcp_operation_started socket={} operation={} host_fd={} kind={} nonblocking={} timeout_ns={}",
                socket.id, id, fd, getClass().getSimpleName(), nonblocking, timeout);
    }

    abstract void attempt();

    void publish() {
    }

    int partial() {
        return 0;
    }

    final void complete(int value) {
        result = value;
        finished = true;
        log.debug("event=native_tcp_operation_completed socket={} operation={} result={}", socket.id, id, result);
        release();
    }

    final void wouldBlock() {
        if (nonblocking) {
            complete(-timeoutErrno);
        }
    }

    @Override
    public final boolean canDispatch() {
        if (!finished) {
            if (interrupted) {
                complete(partial() > 0 ? partial() : -EINTR);
            } else {
                attempt();
                if (!finished && timeout != 0 && System.nanoTime() - started >= timeout) {
                    complete(partial() > 0 ? partial() : -timeoutErrno);
                }
            }
        }
        return finished;
    }

    final int execute() {
        try {
            if (canDispatch()) {
                return result();
            }
            RunnableTask task = socket.emulator.getThreadDispatcher().getRunningTask();
            if (task == null) {
                throw new IllegalStateException("Blocking TCP operation requires a dispatcher task");
            }
            task.setWaiter(socket.emulator, this);
        } catch (RuntimeException | Error failure) {
            onDestroy(socket.emulator);
            throw failure;
        }
        throw new ThreadContextSwitchException();
    }

    private int result() {
        try {
            publish();
            return result;
        } finally {
            onDestroy(socket.emulator);
        }
    }

    @Override
    public final void onContinueRun(Emulator<?> emulator) {
        int value = result();
        emulator.getBackend().reg_write(emulator.is64Bit() ? Arm64Const.UC_ARM64_REG_X0 : ArmConst.UC_ARM_REG_R0, value);
    }

    @Override
    public final void onSignal(SignalTask task) {
        interrupted = true;
    }

    @Override
    public void onDestroy(Emulator<?> emulator) {
        if (!destroyed) {
            destroyed = true;
            if (!finished) {
                log.debug("event=native_tcp_operation_cancelled socket={} operation={}", socket.id, id);
            }
            release();
            socket.owner.completed(this);
        }
    }

    private void release() {
        if (!released) {
            released = true;
            LinuxSocketCalls.close(fd);
        }
    }

    static final class Transfer extends TcpOperation {
        private final byte[] bytes, address;
        private final Pointer destination;
        private final SocketAddress peer;
        private final boolean sending;
        private final int size, flags, addressLength, minimum;
        private int count, peeked;
        private byte[] chunk;
        private boolean blocked;

        Transfer(NativeTcpSocket socket, byte[] source, Pointer destination, int size, int flags,
                 byte[] address, int addressLength, SocketAddress peer) {
            super(socket, source != null, flags, EAGAIN);
            this.sending = source != null;
            this.bytes = sending ? source : new byte[Math.max(1, size)];
            this.destination = destination;
            this.size = size;
            this.flags = flags;
            this.minimum = sending || (flags & MSG_WAITALL) != 0 ? size : Math.min(size, SocketOptions.receiveLowWater(fd));
            this.address = address;
            this.addressLength = addressLength;
            this.peer = peer;
            if (sending && log.isDebugEnabled()) {
                log.debug("event=native_tcp_send_data socket={} operation={} hex={} flags={} address={}", socket.id, id,
                        Hex.encodeHexString(bytes), flags, address == null ? null : Hex.encodeHexString(address));
            }
        }

        @Override
        void attempt() {
            if (count > 0 && chunk == null) {
                chunk = new byte[Math.min(size - count, 8192)];
                if (sending) {
                    System.arraycopy(bytes, count, chunk, 0, chunk.length);
                }
            }
            byte[] buffer = count == 0 ? bytes : chunk;
            long value = sending
                    ? LinuxSocketCalls.LIBC.sendto(fd, buffer, new NativeLong(Math.min(size - count, buffer.length)),
                            flags | MSG_DONTWAIT | MSG_NOSIGNAL, address, addressLength).longValue()
                    : LinuxSocketCalls.LIBC.recvfrom(fd, buffer, new NativeLong(Math.min(size - count, buffer.length)), flags | MSG_DONTWAIT,
                            peer.bytes, peer.length).longValue();
            int errno = value < 0 ? Native.getLastError() : 0;
            if (value < 0) {
                if (errno != EAGAIN || !blocked) {
                    log.debug("event=native_tcp_transfer_error socket={} operation={} errno={} transferred={}",
                            socket.id, id, errno, partial());
                }
                blocked = errno == EAGAIN;
                if (errno == EAGAIN) {
                    if (nonblocking) {
                        complete(partial() > 0 ? partial() : -errno);
                    }
                } else {
                    complete(partial() > 0 ? partial() : -errno);
                }
                return;
            }
            blocked = false;
            if (sending) {
                log.debug("event=native_tcp_send_chunk socket={} operation={} offset={} result={}", socket.id, id, count, value);
            }
            if (!sending && log.isDebugEnabled()) {
                log.debug("event=native_tcp_receive_chunk socket={} operation={} result={} flags={} hex={}", socket.id, id,
                        value, flags, (flags & MSG_TRUNC) == 0 ? Hex.encodeHexString(Arrays.copyOf(buffer, (int) value)) : null);
            }
            if (!sending && count > 0) {
                System.arraycopy(buffer, 0, bytes, count, (int) value);
            }
            peeked = (flags & MSG_PEEK) != 0 ? (int) value : 0;
            if (value == 0 || nonblocking || count + value >= minimum ||
                    (!sending && (flags & MSG_OOB) != 0) ||
                    (peeked > 0 && (NativeTcpSocket.poll(fd, POLLRDHUP) & (POLLRDHUP | POLLHUP)) != 0)) {
                complete(count + (int) value);
            } else if (sending || (flags & MSG_PEEK) == 0) {
                count += (int) value;
                if (sending) {
                    chunk = null;
                }
            }
        }

        @Override
        int partial() {
            return Math.max(count, peeked);
        }

        @Override
        void publish() {
            if (!sending && result >= 0) {
                if (result > 0 && (flags & MSG_TRUNC) == 0) {
                    destination.write(0, bytes, 0, Math.min(size, result));
                }
                peer.publish();
            }
        }
    }

    static final class Connect extends TcpOperation {
        private final byte[] address;
        private final int length;
        private boolean connecting;

        Connect(NativeTcpSocket socket, byte[] address, int length) {
            super(socket, true, 0, EINPROGRESS);
            this.address = address;
            this.length = length;
            log.debug("event=native_tcp_connect_address socket={} operation={} address={}", socket.id, id,
                    address == null ? null : Hex.encodeHexString(address));
        }

        @Override
        void attempt() {
            if (!connecting) {
                int value = LinuxSocketCalls.LIBC.connect(fd, address, length);
                int errno = value < 0 ? Native.getLastError() : 0;
                if (value == 0 || errno != EINPROGRESS) {
                    complete(value < 0 ? -errno : value);
                    return;
                }
                connecting = true;
                wouldBlock();
                return;
            }
            short ready = NativeTcpSocket.poll(fd, POLLOUT);
            if (ready != 0) {
                byte[] error = new byte[4];
                int value = LinuxSocketCalls.LIBC.getsockopt(fd, SOL_SOCKET, SO_ERROR, error, new IntByReference(4));
                int errno = value < 0 ? Native.getLastError() : ByteBuffer.wrap(error).order(ByteOrder.nativeOrder()).getInt();
                complete(-errno);
            }
        }
    }

    static final class Accept extends TcpOperation {
        private final SocketAddress address;
        private final int flags;
        private final ToIntFunction<AndroidFileIO> installer;
        private int accepted = -1;

        Accept(NativeTcpSocket socket, SocketAddress address, int flags, ToIntFunction<AndroidFileIO> installer) {
            super(socket, false, 0, EAGAIN);
            this.address = address;
            this.flags = flags;
            this.installer = installer;
        }

        @Override
        void attempt() {
            accepted = LinuxSocketCalls.LIBC.accept4(fd, address.bytes, address.length, SOCK_CLOEXEC | SOCK_NONBLOCK);
            int errno = accepted < 0 ? Native.getLastError() : 0;
            if (accepted >= 0) {
                complete(0);
            } else if (errno == EAGAIN) {
                wouldBlock();
            } else {
                complete(-errno);
            }
        }

        @Override
        void publish() {
            if (result == 0) {
                NativeTcpSocket child = socket.owner.accepted(socket, accepted, flags);
                accepted = -1;
                boolean transferred = false;
                try {
                    address.publish();
                    result = installer.applyAsInt(child);
                    transferred = result >= 0;
                } finally {
                    if (!transferred) {
                        child.close();
                    }
                }
            }
        }

        @Override
        public void onDestroy(Emulator<?> emulator) {
            if (accepted >= 0) {
                LinuxSocketCalls.close(accepted);
                accepted = -1;
            }
            super.onDestroy(emulator);
        }
    }
}
