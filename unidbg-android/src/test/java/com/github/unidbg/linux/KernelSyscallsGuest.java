package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.pointer.UnidbgPointer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The kernel syscalls a guest issues from emulated code, so the dispatcher, the descriptor table and
 * the guest credentials answer them. One instance serves one Android ABI: the numbers are the ones
 * that ABI table assigns, and the arguments are passed the way that ABI passes them.
 */
final class KernelSyscallsGuest implements AutoCloseable {

    /** The number of each call in the two ABI tables: the arm64 number and the arm one. */
    private enum Call {
        EPOLL_CREATE1(20, 357),
        MOUNT(40, 21),
        PERSONALITY(92, 136),
        FCNTL(25, 221),
        FTRUNCATE(46, 93),
        OPENAT(56, 322),
        CLOSE(57, 6),
        LSEEK(62, 19),
        READ(63, 3),
        WRITE(64, 4),
        FSTAT(80, 197),
        TIMERFD_CREATE(85, 350),
        CAPGET(90, 184),
        PTRACE(117, 26),
        /** {@code getrlimit(2)}, which the arm table numbers as {@code ugetrlimit(2)}. */
        GETRLIMIT(163, 191),
        GETRUSAGE(165, 77),
        GETCPU(168, 345),
        SYSINFO(179, 116),
        PERF_EVENT_OPEN(241, 364),
        PRLIMIT64(261, 369),
        MEMFD_CREATE(279, 385),
        MEMBARRIER(283, 389),
        /** A number neither ABI table assigns. */
        UNMODELED(500, 500);

        private final int wide;
        private final int narrow;

        Call(int wide, int narrow) {
            this.wide = wide;
            this.narrow = narrow;
        }
    }

    private final AndroidEmulator emulator;
    private final boolean wide;
    private final UnidbgPointer code;
    private final UnidbgPointer name;

    KernelSyscallsGuest(AndroidEmulator emulator) {
        this.emulator = emulator;
        this.wide = emulator.is64Bit();
        this.code = emulator.getMemory().mmap(8192, 7);
        this.name = emulator.getSvcMemory().allocate(256, "kernel-syscalls-test-path");
        for (Call call : Call.values()) {
            int number = number(call);
            long offset = number * 16L;
            if (wide) {
                code.setInt(offset, 0xd2800008 | (number << 5));  // movz x8, #number
                code.setInt(offset + 4, 0xd4000001);  // svc #0
                code.setInt(offset + 8, 0xd65f03c0);  // ret
            } else {
                // movw r7, #number; svc #0; bx lr
                code.setInt(offset, 0xe3007000 | (number & 0xfff) | ((number & 0xf000) << 4));
                code.setInt(offset + 4, 0xef000000);
                code.setInt(offset + 8, 0xe12fff1e);
            }
        }
    }

    /** Whether this guest runs the 64-bit ABI. */
    boolean is64Bit() {
        return wide;
    }

    /** The limit the ABI's {@code struct rlimit} reports as unbounded. */
    long unlimitedLimit() {
        return wide ? -1L : 0xffffffffL;
    }

    int sysinfo(long info) {
        return call(Call.SYSINFO, info);
    }

    int getrusage(int who, long usage) {
        return call(Call.GETRUSAGE, who, usage);
    }

    int getrlimit(int resource, long limit) {
        return call(Call.GETRLIMIT, resource, limit);
    }

    int prlimit64(int pid, int resource, long requested, long limit) {
        return call(Call.PRLIMIT64, pid, resource, requested, limit);
    }

    int mount(long source, long target, long type, long flags) {
        return call(Call.MOUNT, source, target, type, flags);
    }

    int personality(long persona) {
        return call(Call.PERSONALITY, persona);
    }

    int epollCreate1(int flags) {
        return call(Call.EPOLL_CREATE1, flags);
    }

    int timerfdCreate(int clockId, int flags) {
        return call(Call.TIMERFD_CREATE, clockId, flags);
    }

    int memFdCreate(String value, int flags) {
        long label = 0;
        if (value != null) {
            name.setString(0, value);
            label = name.peer;
        }
        return call(Call.MEMFD_CREATE, label, flags);
    }

    int getcpu(long cpu, long node, long cache) {
        return call(Call.GETCPU, cpu, node, cache);
    }

    int membarrier(int command, int flags) {
        return call(Call.MEMBARRIER, command, flags);
    }

    int capget(long header, long data) {
        return call(Call.CAPGET, header, data);
    }

    int perfEventOpen(long attributes, int pid, int cpu, int groupFd) {
        return call(Call.PERF_EVENT_OPEN, attributes, pid, cpu, groupFd);
    }

    int ptrace(int request, int pid) {
        return call(Call.PTRACE, request, pid, 0, 0);
    }

    int write(int fd, long buffer, int count) {
        return call(Call.WRITE, fd, buffer, count);
    }

    int read(int fd, long buffer, int count) {
        return call(Call.READ, fd, buffer, count);
    }

    int ftruncate(int fd, long length) {
        return call(Call.FTRUNCATE, fd, length);
    }

    int lseek(int fd, int offset, int whence) {
        return call(Call.LSEEK, fd, offset, whence);
    }

    int fstat(int fd, long stat) {
        return call(Call.FSTAT, fd, stat);
    }

    int fcntl(int fd, int command, long argument) {
        return call(Call.FCNTL, fd, command, argument);
    }

    int close(int fd) {
        return call(Call.CLOSE, fd);
    }

    /** A number the ABI table leaves unassigned. */
    int unmodeled() {
        return call(Call.UNMODELED);
    }

    /** The text of one guest file, read through the syscalls of the process into {@code buffer}. */
    String text(String path, long buffer) {
        name.setString(0, path);
        int fd = call(Call.OPENAT, -100, name.peer, 0, 0);
        assertTrue(path, fd >= 0);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                int count = call(Call.READ, fd, buffer, 1024);
                assertTrue(path + ": " + count, count >= 0);
                if (count == 0) return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                bytes.write(emulator.getMemory().pointer(buffer).getByteArray(0, count), 0, count);
                buffer += count;
            }
        } finally {
            assertEquals(0, call(Call.CLOSE, fd));
        }
    }

    int call(Call call, Number... arguments) {
        long address = code.peer + number(call) * 16L;
        if (wide) {
            return emulator.eFunc(address, arguments).intValue();
        }
        // The ABI hands one register to each argument, so a value crosses as one word.
        return emulator.eFunc(address, Arrays.stream(arguments).map(Number::intValue).toArray(Number[]::new))
                .intValue();
    }

    private int number(Call call) {
        return wide ? call.wide : call.narrow;
    }

    @Override
    public void close() {
        emulator.getMemory().munmap(code.peer, 8192);
    }
}
