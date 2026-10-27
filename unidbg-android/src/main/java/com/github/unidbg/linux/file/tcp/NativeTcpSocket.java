package com.github.unidbg.linux.file.tcp;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.BaseAndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.linux.file.socket.LinuxSocketCalls;
import com.github.unidbg.linux.file.socket.NativeSocketCloseService;
import com.github.unidbg.linux.file.socket.SocketCloseWaiter;
import com.github.unidbg.unix.IO;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import static com.github.unidbg.linux.file.socket.LinuxSocketCalls.*;

public final class NativeTcpSocket extends BaseAndroidFileIO {
    private static final Logger log = LoggerFactory.getLogger(NativeTcpSocket.class);
    final Emulator<?> emulator;
    final NativeTcpSocketFactory owner;
    final long id;
    private final Status status;
    private int fd;
    private NativeSocketCloseService.Request closeRequest;

    static final class Status {
        boolean nonblocking;

        Status(boolean nonblocking) {
            this.nonblocking = nonblocking;
        }
    }

    NativeTcpSocket(Emulator<?> emulator, NativeTcpSocketFactory owner, long id, int fd, Status status, boolean cloexec) {
        super(IOConstants.O_RDWR);
        this.emulator = emulator;
        this.owner = owner;
        this.id = id;
        this.fd = fd;
        this.status = status;
        op = cloexec ? 1 : 0;
        owner.opened(this);
    }

    int fd() {
        return fd;
    }

    boolean nonblocking() {
        return status.nonblocking;
    }

    private NativeSocketCloseService.Request release() {
        if (fd >= 0) {
            int descriptor = fd;
            fd = -1;
            owner.closed(this);
            closeRequest = owner.release(id, descriptor);
            log.debug("event=native_tcp_close_transferred socket={} host_fd={} request={}", id, descriptor, closeRequest.id());
        }
        return closeRequest;
    }

    @Override
    public void close() {
        release();
    }

    @Override
    public int close(Emulator<?> emulator, IntUnaryOperator completion) {
        NativeSocketCloseService.Request request = release();
        return request == null ? completion.applyAsInt(0) : SocketCloseWaiter.await(emulator, request, completion);
    }

    @Override
    public FileIO dup2() {
        int duplicate = LinuxSocketCalls.duplicate(fd);
        log.debug("event=native_tcp_duplicated socket={} host_fd={} duplicate_fd={}", id, fd, duplicate);
        return new NativeTcpSocket(emulator, owner, id, duplicate, status, false);
    }

    @Override
    public int connect(Pointer address, int length) {
        if (length < 0 || length > 128) {
            return -EINVAL;
        }
        return execute(() -> new TcpOperation.Connect(this, SocketAddress.input(address, length), length));
    }

    @Override
    public int bind(Pointer address, int length) {
        if (length < 0 || length > 128) {
            return -EINVAL;
        }
        byte[] bytes = SocketAddress.input(address, length);
        int result = LinuxSocketCalls.LIBC.bind(fd, bytes, length);
        int errno = result < 0 ? Native.getLastError() : 0;
        log.debug("event=native_tcp_bound socket={} address={} result={} errno={}", id,
                bytes == null ? null : Hex.encodeHexString(bytes), result, errno);
        return result < 0 ? -errno : result;
    }

    @Override
    public int listen(int backlog) {
        int result = LinuxSocketCalls.LIBC.listen(fd, backlog);
        int errno = result < 0 ? Native.getLastError() : 0;
        log.debug("event=native_tcp_listening socket={} backlog={} result={} errno={}", id, backlog, result, errno);
        return result < 0 ? -errno : result;
    }

    @Override
    public int accept(Pointer address, Pointer length, int flags, ToIntFunction<AndroidFileIO> installer) {
        if ((flags & ~(SOCK_CLOEXEC | SOCK_NONBLOCK)) != 0) {
            return -EINVAL;
        }
        if (address != null && length == null) {
            return -EFAULT;
        }
        return execute(() -> new TcpOperation.Accept(this, new SocketAddress(address, length), flags, installer));
    }

    @Override
    public int read(Backend backend, Pointer destination, int count) {
        return recvfrom(backend, destination, count, 0, null, null);
    }

    @Override
    public int write(byte[] bytes) {
        return sendto(bytes, 0, null, 0);
    }

    @Override
    public int recvfrom(Backend backend, Pointer destination, int size, int flags, Pointer address, Pointer length) {
        if (size < 0) {
            return -EINVAL;
        }
        if ((destination == null && size != 0) || (address != null && length == null)) {
            return -EFAULT;
        }
        return execute(() -> new TcpOperation.Transfer(this, null, destination, size, flags, null, 0,
                new SocketAddress(address, length)));
    }

    @Override
    public int sendto(byte[] bytes, int flags, Pointer address, int length) {
        if (length < 0 || length > 128) {
            return -EINVAL;
        }
        return execute(() -> new TcpOperation.Transfer(this, bytes, null, bytes.length, flags,
                SocketAddress.input(address, length), length, null));
    }

    private int execute(Supplier<TcpOperation> operation) {
        try {
            return operation.get().execute();
        } catch (LinuxErrnoException failure) {
            return -failure.getErrno();
        }
    }

    @Override
    public int getsockname(Pointer address, Pointer length) {
        return name(address, length, false);
    }

    @Override
    public int getpeername(Pointer address, Pointer length) {
        return name(address, length, true);
    }

    private int name(Pointer address, Pointer length, boolean peer) {
        if (length == null) {
            return -EFAULT;
        }
        SocketAddress output = new SocketAddress(address, length);
        int result = peer ? LinuxSocketCalls.LIBC.getpeername(fd, output.bytes, output.length)
                : LinuxSocketCalls.LIBC.getsockname(fd, output.bytes, output.length);
        if (result < 0) {
            return -Native.getLastError();
        }
        output.publish();
        if (address == null) {
            length.setInt(0, output.length.getValue());
        }
        return result;
    }

    @Override
    public int setsockopt(int level, int name, Pointer value, int length) {
        int result = SocketOptions.set(emulator, fd, level, name, value, length);
        log.debug("event=native_tcp_option_set socket={} level={} option={} value={} result={}", id, level, name,
                value == null || length < 0 ? null : Hex.encodeHexString(value.getByteArray(0, length)), result);
        return result;
    }

    @Override
    public int getsockopt(int level, int name, Pointer value, Pointer length) {
        int result = SocketOptions.get(emulator, fd, level, name, value, length);
        log.debug("event=native_tcp_option_read socket={} level={} option={} value={} result={}", id, level, name,
                result < 0 ? null : Hex.encodeHexString(value == null ? new byte[0] : value.getByteArray(0, length.getInt(0))), result);
        return result;
    }

    @Override
    public int shutdown(int direction) {
        int result = LinuxSocketCalls.LIBC.shutdown(fd, direction);
        int errno = result < 0 ? Native.getLastError() : 0;
        log.debug("event=native_tcp_shutdown socket={} direction={} result={} errno={}", id, direction, result, errno);
        return result < 0 ? -errno : result;
    }

    @Override
    public int fcntl(Emulator<?> emulator, int command, long argument) {
        if (command == F_GETFD) {
            return op;
        }
        if (command == F_SETFD) {
            op = (int) argument & 1;
            return 0;
        }
        if (command == F_GETFL || command == F_SETFL) {
            int result = LinuxSocketCalls.LIBC.fcntl(fd, command, (int) argument | O_NONBLOCK);
            if (result < 0) {
                return -Native.getLastError();
            }
            if (command == F_SETFL) {
                status.nonblocking = (argument & O_NONBLOCK) != 0;
                log.debug("event=native_tcp_flags_set socket={} flags={}", id, argument);
            } else {
                result = (result & ~O_NONBLOCK) | (status.nonblocking ? O_NONBLOCK : 0);
            }
            return result;
        }
        return super.fcntl(emulator, command, argument);
    }

    @Override
    public short poll(short events) {
        return poll(fd, events);
    }

    static short poll(int fd, short events) {
        return LinuxSocketCalls.poll(fd, events);
    }

    @Override
    public boolean canRead() {
        return poll(POLLIN) != 0;
    }

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        stat.st_dev = 0;
        stat.st_mode = IO.S_IFSOCK;
        stat.st_uid = 0;
        stat.st_gid = 0;
        stat.st_size = 0;
        stat.st_blksize = 0;
        stat.st_ino = 0;
        stat.pack();
        return 0;
    }

    @Override
    public String getPath() {
        return "socket:tcp:" + id;
    }
}
