package com.github.unidbg.linux.file.tcp;

import com.github.unidbg.Emulator;
import com.github.unidbg.linux.file.socket.LinuxSocketCalls;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import static com.github.unidbg.linux.file.socket.LinuxSocketCalls.*;

final class SocketOptions {
    private static boolean timeout(int level, int name) {
        return level == SOL_SOCKET && (name == SO_RCVTIMEO || name == SO_SNDTIMEO);
    }

    static int set(Emulator<?> emulator, int fd, int level, int name, Pointer value, int length) {
        if (length < 0) {
            return -EINVAL;
        }
        if (value == null && length != 0) {
            return -EFAULT;
        }
        byte[] bytes = value == null ? null : value.getByteArray(0, length);
        if (timeout(level, name)) {
            int required = emulator.is64Bit() ? 16 : 8;
            if (length < required) {
                return -EINVAL;
            }
            long seconds = emulator.is64Bit() ? value.getLong(0) : value.getInt(0);
            long micros = emulator.is64Bit() ? value.getLong(8) : value.getInt(4);
            bytes = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putLong(seconds).putLong(micros).array();
            length = 16;
        }
        int result = LinuxSocketCalls.LIBC.setsockopt(fd, level, name, bytes, length);
        return result < 0 ? -Native.getLastError() : result;
    }

    static int get(Emulator<?> emulator, int fd, int level, int name, Pointer value, Pointer length) {
        if (length == null) {
            return -EFAULT;
        }
        int capacity = length.getInt(0);
        if (capacity < 0) {
            return -EINVAL;
        }
        if (value == null && capacity != 0) {
            return -EFAULT;
        }
        boolean timeval = timeout(level, name);
        byte[] bytes = new byte[Math.max(1, timeval ? 16 : capacity)];
        IntByReference size = new IntByReference(timeval ? 16 : capacity);
        int result = LinuxSocketCalls.LIBC.getsockopt(fd, level, name, bytes, size);
        if (result < 0) {
            return -Native.getLastError();
        }
        if (timeval) {
            ByteBuffer host = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
            long seconds = host.getLong(), micros = host.getLong();
            ByteBuffer guest = ByteBuffer.allocate(emulator.is64Bit() ? 16 : 8).order(ByteOrder.LITTLE_ENDIAN);
            if (emulator.is64Bit()) {
                guest.putLong(seconds).putLong(micros);
            } else {
                guest.putInt((int) seconds).putInt((int) micros);
            }
            bytes = guest.array();
            size.setValue(bytes.length);
        }
        int count = Math.min(capacity, size.getValue());
        if (count != 0) {
            value.write(0, bytes, 0, count);
        }
        length.setInt(0, count);
        return 0;
    }

    static int receiveLowWater(int fd) {
        byte[] bytes = new byte[4];
        if (LinuxSocketCalls.LIBC.getsockopt(fd, SOL_SOCKET, SO_RCVLOWAT, bytes, new IntByReference(4)) < 0) {
            throw new IllegalStateException("getsockopt(SO_RCVLOWAT) fd=" + fd + ", errno=" + Native.getLastError());
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).getInt();
    }

    static long timeoutNanos(int fd, boolean sending) {
        byte[] bytes = new byte[16];
        int result = LinuxSocketCalls.LIBC.getsockopt(fd, SOL_SOCKET, sending ? SO_SNDTIMEO : SO_RCVTIMEO, bytes, new IntByReference(16));
        if (result < 0) {
            throw new IllegalStateException("getsockopt(timeout) fd=" + fd + ", errno=" + Native.getLastError());
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        long seconds = TimeUnit.SECONDS.toNanos(buffer.getLong());
        long micros = TimeUnit.MICROSECONDS.toNanos(buffer.getLong());
        return Long.MAX_VALUE - seconds < micros ? Long.MAX_VALUE : seconds + micros;
    }

    private SocketOptions() {
    }
}
