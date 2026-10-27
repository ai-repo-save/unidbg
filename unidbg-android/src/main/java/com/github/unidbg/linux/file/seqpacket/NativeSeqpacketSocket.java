package com.github.unidbg.linux.file.seqpacket;

import com.github.unidbg.linux.file.socket.LinuxSocketCalls;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.linux.BaseAndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.thread.RunnableTask;
import com.github.unidbg.thread.ThreadContextSwitchException;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class NativeSeqpacketSocket extends BaseAndroidFileIO {
    private static final Logger log = LoggerFactory.getLogger(NativeSeqpacketSocket.class);
    private final Emulator<?> emulator;
    private final NativeSeqpacketSocketFactory owner;
    private final long pair;
    private int fd;

    NativeSeqpacketSocket(Emulator<?> emulator, NativeSeqpacketSocketFactory owner, long pair, int fd) {
        super(IOConstants.O_RDWR);
        this.emulator = emulator;
        this.owner = owner;
        this.pair = pair;
        this.fd = fd;
    }

    @Override
    public void close() {
        if (fd >= 0) {
            log.debug("event=native_socket_closed pair={} host_fd={}", pair, fd);
            LinuxSocketCalls.close(fd);
            fd = -1;
        }
    }

    @Override
    public FileIO dup2() {
        return duplicateTo(emulator, owner);
    }

    NativeSeqpacketSocket duplicateTo(Emulator<?> target, NativeSeqpacketSocketFactory targetOwner) {
        int duplicate = LinuxSocketCalls.duplicate(fd);
        log.debug("event=native_socket_duplicated pair={} host_fd={} duplicate_fd={} source_emulator={} target_emulator={}",
                pair, fd, duplicate, System.identityHashCode(emulator), System.identityHashCode(target));
        return new NativeSeqpacketSocket(target, targetOwner, pair, duplicate);
    }

    private int error(int errno) {
        return -errno;
    }

    private int transfer(byte[] source, Pointer destination, int size, int flags) {
        if (size < 0) {
            return error(LinuxSocketCalls.EINVAL);
        }
        SocketOperation operation = new SocketOperation(owner, pair, fd, flags, source, destination, size);
        try {
            if (operation.canDispatch()) {
                return operation.result();
            }
            RunnableTask task = emulator.getThreadDispatcher().getRunningTask();
            if (task == null) {
                throw new IllegalStateException("Blocking SEQPACKET operation requires a dispatcher task");
            }
            task.setWaiter(emulator, operation);
        } catch (RuntimeException | Error failure) {
            operation.onDestroy(emulator);
            throw failure;
        }
        throw new ThreadContextSwitchException();
    }

    @Override
    public int read(Backend backend, Pointer buffer, int count) {
        return transfer(null, buffer, count, 0);
    }

    @Override
    public int write(byte[] data) {
        return transfer(data, null, data.length, 0);
    }

    @Override
    public int recvfrom(Backend backend, Pointer buffer, int length, int flags, Pointer address, Pointer addressLength) {
        if (address != null || addressLength != null) {
            throw new UnsupportedOperationException("Unnamed SEQPACKET recvfrom requires null address arguments");
        }
        return transfer(null, buffer, length, flags);
    }

    @Override
    public int sendto(byte[] data, int flags, Pointer address, int addressLength) {
        if (address != null || addressLength != 0) {
            throw new UnsupportedOperationException("Unnamed SEQPACKET sendto requires null address arguments");
        }
        return transfer(data, null, data.length, flags);
    }

    @Override
    public int setsockopt(int level, int name, Pointer value, int length) {
        if (length < 0) {
            return error(LinuxSocketCalls.EINVAL);
        }
        byte[] bytes = value.getByteArray(0, length);
        if (isTimeout(level, name) && length == 16) {
            bytes = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
                    .putLong(value.getLong(0)).putLong(value.getLong(8)).array();
        }
        int result = LinuxSocketCalls.LIBC.setsockopt(fd, level, name, bytes, length);
        int errno = result < 0 ? Native.getLastError() : 0;
        if (log.isDebugEnabled()) {
            log.debug("event=native_socket_option_set pair={} host_fd={} level={} option={} hex={} result={} errno={}",
                    pair, fd, level, name, Hex.encodeHexString(bytes), result, errno);
        }
        return result < 0 ? error(errno) : result;
    }

    @Override
    public int getsockopt(int level, int name, Pointer value, Pointer length) {
        int capacity = length.getInt(0);
        if (capacity < 0) {
            return error(LinuxSocketCalls.EINVAL);
        }
        byte[] bytes = new byte[Math.max(1, capacity)];
        IntByReference size = new IntByReference(capacity);
        int result = LinuxSocketCalls.LIBC.getsockopt(fd, level, name, bytes, size);
        if (result < 0) {
            return error(Native.getLastError());
        }
        if (isTimeout(level, name) && size.getValue() == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
            value.setLong(0, buffer.getLong());
            value.setLong(8, buffer.getLong());
        } else {
            value.write(0, bytes, 0, size.getValue());
        }
        length.setInt(0, size.getValue());
        return 0;
    }

    private boolean isTimeout(int level, int name) {
        return level == LinuxSocketCalls.SOL_SOCKET &&
                (name == LinuxSocketCalls.SO_RCVTIMEO || name == LinuxSocketCalls.SO_SNDTIMEO);
    }

    @Override
    public int fcntl(Emulator<?> emulator, int command, long argument) {
        if (command == LinuxSocketCalls.F_GETFL || command == LinuxSocketCalls.F_SETFL) {
            int result = LinuxSocketCalls.LIBC.fcntl(fd, command, (int) argument);
            int errno = result < 0 ? Native.getLastError() : 0;
            if (command == LinuxSocketCalls.F_SETFL) {
                log.debug("event=native_socket_flags_set pair={} host_fd={} flags={} result={} errno={}",
                        pair, fd, argument, result, errno);
            }
            return result < 0 ? error(errno) : result;
        }
        if (command == 1) { // F_GETFD describes the guest descriptor, not the private host fd.
            return op;
        }
        if (command == 2) {
            op = (int) argument & 1;
            return 0;
        }
        return super.fcntl(emulator, command, argument);
    }

    @Override
    public int shutdown(int how) {
        int result = LinuxSocketCalls.LIBC.shutdown(fd, how);
        int errno = result < 0 ? Native.getLastError() : 0;
        log.debug("event=native_socket_shutdown pair={} host_fd={} direction={} result={} errno={}",
                pair, fd, how, result, errno);
        return result < 0 ? error(errno) : result;
    }

    @Override
    public short poll(short events) {
        return LinuxSocketCalls.poll(fd, events);
    }

    @Override
    public boolean canRead() {
        long result = LinuxSocketCalls.LIBC.recv(fd, new byte[1], new NativeLong(1),
                2 | LinuxSocketCalls.MSG_DONTWAIT).longValue();
        return result >= 0 || Native.getLastError() != LinuxSocketCalls.EAGAIN;
    }

    @Override
    public String getPath() {
        return "socket:seqpacket:" + pair;
    }
}
