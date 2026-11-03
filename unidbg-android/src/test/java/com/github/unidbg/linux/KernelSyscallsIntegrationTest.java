package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.time.ClockSource;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.OptionalInt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The syscalls that answer from the runtime's own facts: {@code sysinfo(2)}, {@code getrusage(2)},
 * {@code prlimit64(2)}, {@code epoll_create1(2)}, {@code timerfd_create(2)}, {@code memfd_create(2)},
 * {@code getcpu(2)}, {@code membarrier(2)}, {@code capget(2)}, {@code perf_event_open(2)} and
 * {@code ptrace(2)}.
 *
 * The machine facts are the host's: the memory ceiling of the process the guest runs in, the
 * processors the host lets it use and the descriptors it may hold. The process facts are the
 * runtime's: the monotonic reading an observer installed, the address space it mapped and the
 * credentials the process runs under.
 */
public class KernelSyscallsIntegrationTest {
    /** The monotonic reading an observer installed, which {@code sysinfo(2)} counts as uptime. */
    private static final long UPTIME_NANOS = 1_234_000_000_000L;
    private static final long WALL_NANOS = 1_700_000_000_000_000_000L;

    private static final int RUSAGE_SELF = 0;
    private static final int RUSAGE_CHILDREN = -1;
    private static final int RUSAGE_THREAD = 1;

    private static final int RLIMIT_STACK = 3;
    private static final int RLIMIT_CORE = 4;
    private static final int RLIMIT_NPROC = 6;
    private static final int RLIMIT_NOFILE = 7;
    private static final int RLIMIT_MEMLOCK = 8;
    /** One past the last resource a 64-bit Linux numbers. */
    private static final int RESOURCE_COUNT = 16;

    private static final int CLOCK_MONOTONIC = 1;
    private static final int EPOLL_CLOEXEC = 0x80000;
    private static final int TFD_CLOEXEC = 0x80000;
    private static final int MFD_CLOEXEC = 0x0001;

    private static final int MEMBARRIER_CMD_QUERY = 0;
    private static final int MEMBARRIER_CMD_GLOBAL = 1;
    private static final int MEMBARRIER_CMD_PRIVATE_EXPEDITED = 1 << 3;

    private static final int CAPABILITY_VERSION_3 = 0x20080522;
    private static final int CAPABILITY_VERSION_1 = 0x19980330;
    /**
     * The full capability set of the recorded device, whose kernel numbers capabilities up to
     * {@code CAP_AUDIT_READ}, which is what a root process keeps.
     */
    private static final long DEVICE_ROOT_CAPABILITIES = 0x3fffffffffL;

    private static final int PTRACE_TRACEME = 0;
    private static final int PTRACE_ATTACH = 16;

    /** {@code personality(2)} answers the domain the process holds when it is queried. */
    private static final long PERSONALITY_QUERY = 0xffffffffL;
    private static final int PER_LINUX = 0x0000;
    private static final int PER_LINUX32 = 0x0008;

    private static final int SEEK_SET = 0;
    private static final int F_GETFD = 1;
    private static final int FD_CLOEXEC = 1;

    @Test
    public void theKernelAnswersFromTheRuntimeFacts() throws Exception {
        Path parent = Paths.get("target/kernel-syscalls");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "arm64-").toAbsolutePath();
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setRootDir(root.toFile()).addBackendFactory(new Unicorn2Factory(true)).build();
             RawSyscalls calls = new RawSyscalls(emulator)) {
            ClockSource.install(emulator, ClockSource.of(new long[] {WALL_NANOS}, new long[] {UPTIME_NANOS}, 0, 0));
            MemoryBlock scratch = emulator.getMemory().malloc(16384, true);
            try {
                UnidbgPointer first = scratch.getPointer();
                UnidbgPointer second = first.share(4096, 0);
                verifySysinfo(calls, first);
                verifyRusage(emulator, calls, first);
                verifyLimits(emulator, calls, first, second);
                verifyDescriptors(calls, first, second);
                verifyProcessor(calls, first);
                verifyBarriers(calls);
                verifyRootCapabilities(emulator, calls, first, second);
                verifyRefusals(emulator, calls, first);
                verifyPolicy(calls, first);
                verifyUnprivilegedProcess(emulator, calls, first, second);
            } finally {
                scratch.free();
            }
        }
    }

    private static void verifySysinfo(RawSyscalls calls, UnidbgPointer info) {
        assertEquals(0, calls.sysinfo(info.peer));
        assertEquals(UPTIME_NANOS / 1_000_000_000L, info.getLong(0));
        long total = info.getLong(32);
        long free = info.getLong(40);
        assertEquals(Runtime.getRuntime().maxMemory(), total);
        assertTrue("freeram=" + free + ", totalram=" + total, free > 0 && free <= total);
        assertEquals(1, info.getShort(80));  // procs
        assertEquals(0, info.getLong(64));  // totalswap
        assertEquals(0, info.getLong(72));  // freeswap
        assertEquals(1, info.getInt(104));  // mem_unit
    }

    private static void verifyRusage(AndroidEmulator emulator, RawSyscalls calls, UnidbgPointer usage) {
        long mapped = 0;
        for (MemoryMap map : emulator.getMemory().getMemoryMap()) mapped += map.size;

        assertEquals(0, calls.getrusage(RUSAGE_SELF, usage.peer));
        long processNanos = usage.getLong(0) * 1_000_000_000L + usage.getLong(8) * 1_000L;
        assertTrue("utime=" + processNanos, processNanos >= 0 && processNanos < UPTIME_NANOS);
        assertEquals(0, usage.getLong(16));  // the guest runs in user mode, so it spends no system time
        assertEquals(0, usage.getLong(24));
        assertEquals(mapped / 1024, usage.getLong(32));

        // The runtime holds no child of the process, so no child spent CPU time and none held a
        // resident set.
        assertEquals(0, calls.getrusage(RUSAGE_CHILDREN, usage.peer));
        assertTrue("children cpu", usage.getLong(0) == 0 && usage.getLong(8) == 0);
        assertEquals(0, usage.getLong(32));

        // The thread clock counts only the dispatch it is read in, so the process clock read
        // afterwards covers it. A thread runs in the address space of the process, so its resident
        // set is the mapped bytes of that space.
        assertEquals(0, calls.getrusage(RUSAGE_THREAD, usage.peer));
        long threadNanos = usage.getLong(0) * 1_000_000_000L + usage.getLong(8) * 1_000L;
        assertEquals(mapped / 1024, usage.getLong(32));
        assertEquals(0, calls.getrusage(RUSAGE_SELF, usage.peer));
        long laterNanos = usage.getLong(0) * 1_000_000_000L + usage.getLong(8) * 1_000L;
        assertTrue("thread=" + threadNanos + ", process=" + laterNanos, threadNanos <= laterNanos);

        assertEquals(-UnixEmulator.EINVAL, calls.getrusage(2, usage.peer));
    }

    private static void verifyLimits(AndroidEmulator emulator, RawSyscalls calls, UnidbgPointer limit,
                                     UnidbgPointer requested) {
        long stack = (long) Memory.STACK_SIZE_OF_PAGE * emulator.getPageAlign();
        assertEquals(0, calls.prlimit64(0, RLIMIT_STACK, 0, limit.peer));
        assertEquals(stack, limit.getLong(0));
        assertEquals(stack, limit.getLong(8));

        assertEquals(0, calls.prlimit64(0, RLIMIT_NPROC, 0, limit.peer));
        assertEquals(Memory.MAX_THREADS, limit.getLong(0));
        assertEquals(Memory.MAX_THREADS, limit.getLong(8));

        assertEquals(0, calls.prlimit64(0, RLIMIT_CORE, 0, limit.peer));
        assertEquals(0, limit.getLong(0));
        assertEquals(0, limit.getLong(8));

        // The runtime locks any page it is asked to lock, so the amount is unbounded.
        assertEquals(0, calls.prlimit64(0, RLIMIT_MEMLOCK, 0, limit.peer));
        assertEquals(-1L, limit.getLong(0));
        assertEquals(-1L, limit.getLong(8));

        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, 0, limit.peer));
        long descriptors = limit.getLong(0);
        assertEquals(descriptors, limit.getLong(8));
        assertTrue("nofile=" + descriptors, descriptors >= Memory.MAX_THREADS);
        // getrlimit(2) asks the same question, so it answers from the same limits.
        assertEquals(0, calls.getrlimit(RLIMIT_NOFILE, limit.peer));
        assertEquals(descriptors, limit.getLong(0));

        requested.setLong(0, 1024);
        requested.setLong(8, 2048);
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, requested.peer, limit.peer));
        assertEquals(descriptors, limit.getLong(0));  // the call reports the limits it replaced
        assertEquals(descriptors, limit.getLong(8));
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, 0, limit.peer));
        assertEquals(1024, limit.getLong(0));
        assertEquals(2048, limit.getLong(8));
        assertEquals(0, calls.getrlimit(RLIMIT_NOFILE, limit.peer));
        assertEquals(1024, limit.getLong(0));
        assertEquals(2048, limit.getLong(8));

        requested.setLong(0, 4096);
        requested.setLong(8, 1024);
        assertEquals(-UnixEmulator.EINVAL, calls.prlimit64(0, RLIMIT_NOFILE, requested.peer, 0));

        // A process running as root holds CAP_SYS_RESOURCE, so it may raise a hard limit.
        requested.setLong(0, 1024);
        requested.setLong(8, 4096);
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, requested.peer, 0));
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, 0, limit.peer));
        assertEquals(4096, limit.getLong(8));

        assertEquals(-UnixEmulator.EINVAL, calls.prlimit64(0, RESOURCE_COUNT, 0, limit.peer));
        assertEquals(-UnixEmulator.ESRCH, calls.prlimit64(emulator.getPid() + 1, RLIMIT_NOFILE, 0, limit.peer));
        assertEquals(0, calls.prlimit64(emulator.getPid(), RLIMIT_NOFILE, 0, limit.peer));
    }

    private static void verifyDescriptors(RawSyscalls calls, UnidbgPointer first, UnidbgPointer second) {
        int eventPoll = calls.epollCreate1(EPOLL_CLOEXEC);
        assertTrue("epoll fd=" + eventPoll, eventPoll >= 0);
        int timer = calls.timerfdCreate(CLOCK_MONOTONIC, TFD_CLOEXEC);
        assertTrue("timerfd fd=" + timer, timer >= 0);
        int memory = calls.memFdCreate("runtime-evaluation", MFD_CLOEXEC);
        assertTrue("memfd fd=" + memory, memory >= 0);
        assertNotEquals(eventPoll, timer);
        assertNotEquals(timer, memory);

        // Every created descriptor is in the table the file syscalls read.
        assertEquals(0, calls.fstat(eventPoll, first.peer));
        long eventPollInode = first.getLong(8);
        assertEquals(0600, first.getInt(16));
        assertEquals(0, calls.fstat(timer, second.peer));
        assertNotEquals(eventPollInode, second.getLong(8));
        assertEquals(0600, second.getInt(16));
        assertEquals(FD_CLOEXEC, calls.fcntl(eventPoll, F_GETFD, 0));
        assertEquals(FD_CLOEXEC, calls.fcntl(timer, F_GETFD, 0));
        assertEquals(FD_CLOEXEC, calls.fcntl(memory, F_GETFD, 0));

        // A memfd keeps the bytes the guest writes.
        byte[] payload = {0x10, 0x20, 0x30};
        first.write(0, payload, 0, payload.length);
        assertEquals(payload.length, calls.write(memory, first.peer, payload.length));
        assertEquals(0, calls.ftruncate(memory, 8));
        assertEquals(0, calls.lseek(memory, 0, SEEK_SET));
        assertEquals(payload.length, calls.read(memory, second.peer, payload.length));
        assertArrayEquals(payload, second.getByteArray(0, payload.length));
        assertEquals(0, calls.fstat(memory, first.peer));
        assertEquals(8, first.getLong(48));

        assertEquals(0, calls.close(eventPoll));
        assertEquals(0, calls.close(timer));
        assertEquals(0, calls.close(memory));
        assertEquals(-UnixEmulator.EBADF, calls.fstat(eventPoll, first.peer));

        assertEquals(-UnixEmulator.EINVAL, calls.epollCreate1(1));
        assertEquals(-UnixEmulator.EINVAL, calls.timerfdCreate(CLOCK_MONOTONIC, 0x100));
        // The name is copied before the file is created, so a call that names no file has no name
        // to copy.
        assertEquals(-UnixEmulator.EFAULT, calls.memFdCreate(null, MFD_CLOEXEC));
    }

    private static void verifyProcessor(RawSyscalls calls, UnidbgPointer first) {
        int processors = Runtime.getRuntime().availableProcessors();
        assertEquals(0, calls.getcpu(first.peer, first.share(8, 0).peer, 0));
        int cpu = first.getInt(0);
        int node = first.getInt(8);
        assertEquals("node=" + node, 0, node);
        assertTrue("cpu=" + cpu + ", processors=" + processors, cpu >= 0 && cpu < processors);

        // A guest thread keeps the processor it answers, so a second call reports the same one.
        assertEquals(0, calls.getcpu(first.peer, 0, 0));
        assertEquals(cpu, first.getInt(0));
    }

    private static void verifyBarriers(RawSyscalls calls) {
        assertEquals(MEMBARRIER_CMD_GLOBAL, calls.membarrier(MEMBARRIER_CMD_QUERY, 0));
        assertEquals(0, calls.membarrier(MEMBARRIER_CMD_GLOBAL, 0));
        assertEquals(-UnixEmulator.EINVAL, calls.membarrier(MEMBARRIER_CMD_PRIVATE_EXPEDITED, 0));
        assertEquals(-UnixEmulator.EINVAL, calls.membarrier(MEMBARRIER_CMD_QUERY, 1));
    }

    private static void verifyRootCapabilities(AndroidEmulator emulator, RawSyscalls calls, UnidbgPointer header,
                                               UnidbgPointer data) {
        header.setInt(0, CAPABILITY_VERSION_3);
        header.setInt(4, 0);
        assertEquals(0, calls.capget(header.peer, data.peer));
        assertEquals(DEVICE_ROOT_CAPABILITIES, set(data, 0));
        assertEquals(DEVICE_ROOT_CAPABILITIES, set(data, 4));
        assertEquals(0, set(data, 8));

        // The status of the process reports the sets the syscall answers.
        String status = calls.text(emulator, "/proc/self/status", data.peer);
        assertTrue(status, status.contains("CapEff:\t0000003fffffffff\n"));
        assertTrue(status, status.contains("CapPrm:\t0000003fffffffff\n"));
        assertTrue(status, status.contains("CapBnd:\t0000003fffffffff\n"));
        assertTrue(status, status.contains("CapInh:\t0000000000000000\n"));

        header.setInt(0, CAPABILITY_VERSION_1);
        assertEquals(-UnixEmulator.EINVAL, calls.capget(header.peer, data.peer));
        assertEquals(CAPABILITY_VERSION_3, header.getInt(0));

        header.setInt(0, CAPABILITY_VERSION_3);
        header.setInt(4, emulator.getPid() + 1);
        assertEquals(-UnixEmulator.ESRCH, calls.capget(header.peer, data.peer));
    }

    /** One capability set of the two data records: the low word at its offset, the high word above. */
    private static long set(UnidbgPointer data, int offset) {
        return (data.getInt(offset) & 0xffffffffL) | ((data.getInt(offset + 12) & 0xffffffffL) << 32);
    }

    private static void verifyRefusals(AndroidEmulator emulator, RawSyscalls calls, UnidbgPointer attributes) {
        attributes.setInt(0, 1);  // PERF_TYPE_SOFTWARE
        attributes.setInt(4, 128);  // sizeof(struct perf_event_attr) of the recorded device
        attributes.setLong(8, 0);  // PERF_COUNT_SW_CPU_CLOCK
        assertEquals(-UnixEmulator.EACCES, calls.perfEventOpen(attributes.peer, 0, -1, -1, 0));
        // The runtime authorizes no performance access, whatever the request names.
        assertEquals(-UnixEmulator.EACCES, calls.perfEventOpen(0, 0, -1, -1, 0));

        assertEquals(-UnixEmulator.EPERM, calls.ptrace(PTRACE_ATTACH, emulator.getPid()));
        assertEquals(-UnixEmulator.EPERM, calls.ptrace(PTRACE_TRACEME, 0));
    }

    private static void verifyPolicy(RawSyscalls calls, UnidbgPointer first) {
        // The runtime serves the mount table it holds, so a process may not change it.
        first.setString(0, "none");
        first.setString(16, "/");
        first.setString(32, "none");
        assertEquals(-UnixEmulator.EACCES, calls.mount(first.peer, first.share(16, 0).peer,
                first.share(32, 0).peer, 0));
        // The platform denies the operation before it looks at the names: a call that names neither
        // source, target nor type is refused like any other.
        assertEquals(-UnixEmulator.EACCES, calls.mount(0, 0, 0, 0));

        // A process starts in PER_LINUX and keeps the domain it was given.
        assertEquals(PER_LINUX, calls.personality(PERSONALITY_QUERY));
        assertEquals(PER_LINUX, calls.personality(PER_LINUX32));
        assertEquals(PER_LINUX32, calls.personality(PERSONALITY_QUERY));
        assertEquals(PER_LINUX32, calls.personality(PER_LINUX));
        assertEquals(PER_LINUX, calls.personality(PERSONALITY_QUERY));
    }

    private static void verifyUnprivilegedProcess(AndroidEmulator emulator, RawSyscalls calls, UnidbgPointer header,
                                                  UnidbgPointer data) {
        int uid = 10123;
        ((AndroidSyscallHandler) emulator.getSyscallHandler()).setGuestCredentials(new GuestCredentials(
                OptionalInt.of(uid), OptionalInt.of(uid), OptionalInt.of(uid), OptionalInt.of(uid),
                java.util.Optional.of(Collections.emptySet())));

        // A process that runs as another user keeps no capability.
        header.setInt(0, CAPABILITY_VERSION_3);
        header.setInt(4, 0);
        assertEquals(0, calls.capget(header.peer, data.peer));
        assertEquals(0, set(data, 0));
        assertEquals(0, set(data, 4));
        String status = calls.text(emulator, "/proc/self/status", data.peer);
        assertTrue(status, status.contains("CapEff:\t0000000000000000\n"));

        // A process without CAP_SYS_RESOURCE may not raise a hard limit, but lowering one stands.
        header.setLong(0, 1024);
        header.setLong(8, 8192);
        assertEquals(-UnixEmulator.EPERM, calls.prlimit64(0, RLIMIT_NOFILE, header.peer, 0));
        header.setLong(8, 2048);
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, header.peer, 0));
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, 0, data.peer));
        assertEquals(1024, data.getLong(0));
        assertEquals(2048, data.getLong(8));
    }

    /**
     * arm64 syscalls issued from emulated code, so the dispatcher, the descriptor table and the
     * guest credentials answer them.
     */
    private static final class RawSyscalls implements AutoCloseable {
        private static final int EPOLL_CREATE1 = 20;
        private static final int MOUNT = 40;
        private static final int PERSONALITY = 92;
        private static final int FCNTL = 25;
        private static final int FTRUNCATE = 46;
        private static final int OPENAT = 56;
        private static final int CLOSE = 57;
        private static final int LSEEK = 62;
        private static final int READ = 63;
        private static final int WRITE = 64;
        private static final int FSTAT = 80;
        private static final int TIMERFD_CREATE = 85;
        private static final int CAPGET = 90;
        private static final int PTRACE = 117;
        private static final int GETRLIMIT = 163;
        private static final int GETRUSAGE = 165;
        private static final int GETCPU = 168;
        private static final int SYSINFO = 179;
        private static final int PERF_EVENT_OPEN = 241;
        private static final int PRLIMIT64 = 261;
        private static final int MEMFD_CREATE = 279;
        private static final int MEMBARRIER = 283;

        private final AndroidEmulator emulator;
        private final UnidbgPointer code;
        private final UnidbgPointer name;

        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(8192, 7);
            name = emulator.getSvcMemory().allocate(256, "kernel-syscalls-test-path");
            for (int number : new int[] {EPOLL_CREATE1, MOUNT, FCNTL, FTRUNCATE, OPENAT, CLOSE, LSEEK, READ,
                    WRITE, FSTAT, TIMERFD_CREATE, CAPGET, PERSONALITY, PTRACE, GETRLIMIT, GETRUSAGE, GETCPU,
                    SYSINFO, PERF_EVENT_OPEN, PRLIMIT64, MEMFD_CREATE, MEMBARRIER}) {
                long offset = number * 16L;
                code.setInt(offset, 0xd2800008 | (number << 5));
                code.setInt(offset + 4, 0xd4000001);
                code.setInt(offset + 8, 0xd65f03c0);
            }
        }

        int sysinfo(long info) {
            return call(SYSINFO, info);
        }

        int getrusage(int who, long usage) {
            return call(GETRUSAGE, who, usage);
        }

        int getrlimit(int resource, long limit) {
            return call(GETRLIMIT, resource, limit);
        }

        int prlimit64(int pid, int resource, long requested, long limit) {
            return call(PRLIMIT64, pid, resource, requested, limit);
        }

        int mount(long source, long target, long type, long flags) {
            return call(MOUNT, source, target, type, flags, 0);
        }

        int personality(long persona) {
            return call(PERSONALITY, persona);
        }

        int epollCreate1(int flags) {
            return call(EPOLL_CREATE1, flags);
        }

        int timerfdCreate(int clockId, int flags) {
            return call(TIMERFD_CREATE, clockId, flags);
        }

        int memFdCreate(String value, int flags) {
            long label = 0;
            if (value != null) {
                name.setString(0, value);
                label = name.peer;
            }
            return call(MEMFD_CREATE, label, flags);
        }

        int getcpu(long cpu, long node, long cache) {
            return call(GETCPU, cpu, node, cache);
        }

        int membarrier(int command, int flags) {
            return call(MEMBARRIER, command, flags);
        }

        int capget(long header, long data) {
            return call(CAPGET, header, data);
        }

        int perfEventOpen(long attributes, int pid, int cpu, int groupFd, long flags) {
            return call(PERF_EVENT_OPEN, attributes, pid, cpu, groupFd, flags);
        }

        int ptrace(int request, int pid) {
            return call(PTRACE, request, pid, 0, 0);
        }

        int write(int fd, long buffer, int count) {
            return call(WRITE, fd, buffer, count);
        }

        int read(int fd, long buffer, int count) {
            return call(READ, fd, buffer, count);
        }

        int ftruncate(int fd, long length) {
            return call(FTRUNCATE, fd, length);
        }

        int lseek(int fd, int offset, int whence) {
            return call(LSEEK, fd, offset, whence);
        }

        int fstat(int fd, long stat) {
            return call(FSTAT, fd, stat);
        }

        int fcntl(int fd, int command, long argument) {
            return call(FCNTL, fd, command, argument);
        }

        int close(int fd) {
            return call(CLOSE, fd);
        }

        /** The text of one guest file, read through the syscalls of the process into {@code buffer}. */
        String text(AndroidEmulator emulator, String path, long buffer) {
            name.setString(0, path);
            int fd = call(OPENAT, -100, name.peer, 0, 0);
            assertTrue(path, fd >= 0);
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                while (true) {
                    int count = call(READ, fd, buffer, 1024);
                    assertTrue(path + ": " + count, count >= 0);
                    if (count == 0) return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                    bytes.write(emulator.getMemory().pointer(buffer).getByteArray(0, count), 0, count);
                    buffer += count;
                }
            } finally {
                assertEquals(0, call(CLOSE, fd));
            }
        }

        int call(int number, Number... arguments) {
            return emulator.eFunc(code.peer + number * 16L, arguments).intValue();
        }

        @Override
        public void close() {
            emulator.getMemory().munmap(code.peer, 8192);
        }
    }
}
