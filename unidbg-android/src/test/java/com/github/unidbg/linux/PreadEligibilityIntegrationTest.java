package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.UnixEmulator;
import com.sun.jna.Library;
import com.sun.jna.Native;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Linux pread(2) returns ESPIPE for a pipe or socket, and EINVAL before that when the offset is negative. */
public class PreadEligibilityIntegrationTest {
    private static final byte[] UNCHANGED = {(byte) 0xa5, (byte) 0xa5, (byte) 0xa5, (byte) 0xa5};

    @Test
    public void nonSeekablePreadReturnsLinuxErrno() throws Exception {
        LinuxLibC libc = LinuxLibC.INSTANCE;
        int[] hostPipe = new int[2];
        assertEquals(0, libc.pipe(hostPipe));
        int hostSocket = libc.socket(2, 1, 0);
        try {
            assertEquals(-1, libc.pread(hostPipe[0], UNCHANGED.clone(), UNCHANGED.length, 0));
            assertEquals(UnixEmulator.ESPIPE, Native.getLastError());
            assertEquals(-1, libc.pread(hostPipe[1], UNCHANGED.clone(), UNCHANGED.length, 0));
            assertEquals(UnixEmulator.ESPIPE, Native.getLastError());
            assertEquals(-1, libc.pread(hostSocket, UNCHANGED.clone(), UNCHANGED.length, 0));
            assertEquals(UnixEmulator.ESPIPE, Native.getLastError());
            assertEquals(-1, libc.pread(hostPipe[0], UNCHANGED.clone(), UNCHANGED.length, -1));
            assertEquals(UnixEmulator.EINVAL, Native.getLastError());
            for (boolean wide : new boolean[] {true, false}) {
                try (AndroidEmulator emulator = (wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                        .addBackendFactory(new Unicorn2Factory(true)).build()) {
                    MemoryBlock scratch = emulator.getMemory().malloc(256, true);
                    try (RawSyscalls calls = new RawSyscalls(emulator)) {
                        UnidbgPointer buffer = scratch.getPointer();
                        assertEquals(0, calls.call(wide ? 59 : 359, buffer.peer, 0));
                        int readFd = buffer.getInt(0);
                        int writeFd = buffer.getInt(4);
                        int socket = calls.call(wide ? 198 : 281, 2, 1, 0);
                        assertEquals(Math.max(readFd, writeFd) + 1, socket);
                        for (int fd : new int[] {readFd, writeFd, socket}) {
                            buffer.write(64, UNCHANGED, 0, UNCHANGED.length);
                            assertEquals(-UnixEmulator.ESPIPE, calls.pread(fd, buffer.peer + 64, UNCHANGED.length, 0));
                            assertArrayEquals(UNCHANGED, buffer.getByteArray(64, UNCHANGED.length));
                        }
                        buffer.write(64, UNCHANGED, 0, UNCHANGED.length);
                        assertEquals(-UnixEmulator.EINVAL, calls.pread(readFd, buffer.peer + 64, UNCHANGED.length, -1));
                        assertArrayEquals(UNCHANGED, buffer.getByteArray(64, UNCHANGED.length));
                        assertEquals(0, calls.call(wide ? 57 : 6, readFd));
                        assertEquals(0, calls.call(wide ? 57 : 6, writeFd));
                        assertEquals(0, calls.call(wide ? 57 : 6, socket));
                    } finally {
                        scratch.free();
                    }
                }
            }
        } finally {
            assertEquals(0, libc.close(hostPipe[0]));
            assertEquals(0, libc.close(hostPipe[1]));
            if (hostSocket >= 0) {
                assertEquals(0, libc.close(hostSocket));
            }
        }
    }

    private interface LinuxLibC extends Library {
        LinuxLibC INSTANCE = Native.load("c", LinuxLibC.class);
        int pipe(int[] fds);
        int socket(int domain, int type, int protocol);
        int close(int fd);
        long pread(int fd, byte[] buffer, long count, long offset);
    }

    private static final class RawSyscalls implements AutoCloseable {
        private static final int ARM32_PREAD_ZERO = 0;
        private static final int ARM32_PREAD_NEGATIVE = 32;
        private final AndroidEmulator emulator;
        private final UnidbgPointer code;

        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(8192, 7);
            for (int number : new int[] {6, 57, 59, 67, 198, 281, 359}) {
                long offset = 256 + number * 16L;
                code.setInt(offset, emulator.is64Bit() ? 0xd2800008 | (number << 5)
                        : 0xe3007000 | (number & 0xfff) | ((number & 0xf000) << 4));
                code.setInt(offset + 4, emulator.is64Bit() ? 0xd4000001 : 0xef000000);
                code.setInt(offset + 8, emulator.is64Bit() ? 0xd65f03c0 : 0xe12fff1e);
            }
            if (emulator.is32Bit()) {
                code.setInt(ARM32_PREAD_ZERO, 0xe3a04000);
                code.setInt(ARM32_PREAD_ZERO + 4, 0xe3a05000);
                code.setInt(ARM32_PREAD_ZERO + 8, 0xe30070b4);
                code.setInt(ARM32_PREAD_ZERO + 12, 0xef000000);
                code.setInt(ARM32_PREAD_ZERO + 16, 0xe12fff1e);
                code.setInt(ARM32_PREAD_NEGATIVE, 0xe3e04000);
                code.setInt(ARM32_PREAD_NEGATIVE + 4, 0xe3e05000);
                code.setInt(ARM32_PREAD_NEGATIVE + 8, 0xe30070b4);
                code.setInt(ARM32_PREAD_NEGATIVE + 12, 0xef000000);
                code.setInt(ARM32_PREAD_NEGATIVE + 16, 0xe12fff1e);
            }
        }

        int call(int number, Number... arguments) {
            if (emulator.is32Bit()) {
                arguments = Arrays.stream(arguments).map(Number::intValue).toArray(Number[]::new);
            }
            return emulator.eFunc(code.peer + 256 + number * 16L, arguments).intValue();
        }

        int pread(int fd, long buffer, int count, long offset) {
            if (emulator.is64Bit()) {
                return call(67, fd, buffer, count, offset);
            }
            long entry = code.peer + (offset < 0 ? ARM32_PREAD_NEGATIVE : ARM32_PREAD_ZERO);
            return emulator.eFunc(entry, fd, (int) buffer, count).intValue();
        }

        @Override
        public void close() {
            emulator.getMemory().munmap(code.peer, 8192);
        }
    }
}
