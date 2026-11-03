package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.struct.RLimit32;
import com.github.unidbg.linux.struct.RLimit64;
import com.github.unidbg.linux.struct.Rusage32;
import com.github.unidbg.linux.struct.Rusage64;
import com.github.unidbg.linux.struct.Stat32;
import com.github.unidbg.linux.struct.Stat64;
import com.github.unidbg.linux.struct.SysInfo32;
import com.github.unidbg.linux.struct.SysInfo64;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.pointer.UnidbgStructure;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.time.ClockSource;
import org.junit.Test;

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
 * {@code getcpu(2)}, {@code membarrier(2)}, {@code capget(2)}, {@code perf_event_open(2)},
 * {@code mount(2)}, {@code personality(2)} and {@code ptrace(2)}.
 *
 * The machine facts are the host's: the memory ceiling of the process the guest runs in, the
 * processors the host lets it use and the descriptors it may hold. The process facts are the
 * runtime's: the monotonic reading an observer installed, the address space it mapped and the
 * credentials the process runs under.
 *
 * Every assertion runs on both Android ABIs: the calls and their answers are the same, and only the
 * structure one ABI fills differs from the structure the other fills.
 *
 * Each structure is also read at the offsets its ABI defines, which the kernel headers give:
 * {@code struct sysinfo} of {@code include/uapi/linux/sysinfo.h} and {@code struct compat_sysinfo}
 * of {@code kernel/sys.c}, {@code struct rusage} and {@code struct rlimit} of
 * {@code include/uapi/linux/resource.h} with {@code struct compat_rusage} and
 * {@code struct compat_rlimit} of {@code include/linux/compat.h}, and {@code struct stat} of
 * {@code include/uapi/asm-generic/stat.h} with the arm {@code struct stat64} of
 * {@code arch/arm/include/uapi/asm/stat.h}.
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
    /** One past the last resource a Linux numbers. */
    private static final int RESOURCE_COUNT = 16;

    private static final int CLOCK_MONOTONIC = 1;
    private static final int CLOCK_MONOTONIC_RAW = 4;
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
        for (boolean wide : new boolean[] {false, true}) {
            try {
                verifyKernelAnswers(parent, wide);
            } catch (AssertionError failure) {
                throw new AssertionError((wide ? "arm64: " : "arm32: ") + failure.getMessage(), failure);
            }
        }
    }

    private static void verifyKernelAnswers(Path parent, boolean wide) throws Exception {
        Path root = Files.createTempDirectory(parent, wide ? "arm64-" : "arm32-").toAbsolutePath();
        try (AndroidEmulator emulator = (wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                .setRootDir(root.toFile()).addBackendFactory(new Unicorn2Factory(true)).build();
             KernelSyscallsGuest calls = new KernelSyscallsGuest(emulator)) {
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

    private static void verifySysinfo(KernelSyscallsGuest calls, UnidbgPointer info) {
        assertEquals(0, calls.sysinfo(info.peer));
        UnidbgStructure values = sysinfo(calls, info);
        assertEquals(UPTIME_NANOS / 1_000_000_000L, field(values, "uptime"));
        long memUnit = field(values, "mem_unit");
        long total = field(values, "totalRam") * memUnit;
        long free = field(values, "freeRam") * memUnit;
        // A field the ABI cannot hold reports its count in units of mem_unit.
        assertEquals(Runtime.getRuntime().maxMemory(), total);
        assertTrue("freeram=" + free + ", totalram=" + total, free > 0 && free <= total);
        assertEquals(1, field(values, "procs"));
        assertEquals(0, field(values, "totalSwap"));
        assertEquals(0, field(values, "freeSwap"));
        // The eight-byte fields hold every count of the machine as it is.
        if (calls.is64Bit()) assertEquals(1, memUnit);

        // The same facts at the offsets the ABI defines, so a field order or width the packing
        // classes got wrong cannot pass through the unpacking classes that share it.
        if (calls.is64Bit()) {
            assertEquals(UPTIME_NANOS / 1_000_000_000L, info.getLong(0));      // uptime
            assertEquals(Runtime.getRuntime().maxMemory(), info.getLong(32));   // totalram
            assertEquals(1, info.getInt(104));                                  // mem_unit
        } else {
            assertEquals(UPTIME_NANOS / 1_000_000_000L, word(info, 0));         // uptime
            assertEquals(Runtime.getRuntime().maxMemory(), word(info, 16) * word(info, 52));
            // mem_unit doubles while the count does not fit its four-byte field.
            assertTrue("mem_unit=" + word(info, 52), Long.bitCount(word(info, 52)) == 1);
        }
    }

    private static void verifyRusage(AndroidEmulator emulator, KernelSyscallsGuest calls, UnidbgPointer usage) {
        long mapped = 0;
        for (MemoryMap map : emulator.getMemory().getMemoryMap()) mapped += map.size;

        assertEquals(0, calls.getrusage(RUSAGE_SELF, usage.peer));
        UnidbgStructure self = rusage(calls, usage);
        long processNanos = clock(self);
        assertTrue("utime=" + processNanos, processNanos >= 0 && processNanos < UPTIME_NANOS);
        // The guest runs in user mode, so it spends no system time.
        assertEquals(0, field(self, "ru_stime_sec"));
        assertEquals(0, field(self, "ru_stime_usec"));
        assertEquals(mapped / 1024, field(self, "ru_maxrss"));
        // ru_maxrss is the fifth field of the 64-bit struct rusage and of the four-byte
        // struct compat_rusage, at the offset each ABI gives it.
        assertEquals(mapped / 1024, calls.is64Bit() ? usage.getLong(32) : word(usage, 16));

        // The runtime holds no child of the process, so no child spent CPU time and none held a
        // resident set.
        assertEquals(0, calls.getrusage(RUSAGE_CHILDREN, usage.peer));
        UnidbgStructure children = rusage(calls, usage);
        assertEquals(0, clock(children));
        assertEquals(0, field(children, "ru_maxrss"));

        // The thread clock counts only the dispatch it is read in, so the process clock read
        // afterwards covers it. A thread runs in the address space of the process, so its resident
        // set is the mapped bytes of that space.
        assertEquals(0, calls.getrusage(RUSAGE_THREAD, usage.peer));
        UnidbgStructure thread = rusage(calls, usage);
        long threadNanos = clock(thread);
        assertEquals(mapped / 1024, field(thread, "ru_maxrss"));
        assertEquals(0, calls.getrusage(RUSAGE_SELF, usage.peer));
        long laterNanos = clock(rusage(calls, usage));
        assertTrue("thread=" + threadNanos + ", process=" + laterNanos, threadNanos <= laterNanos);

        assertEquals(-UnixEmulator.EINVAL, calls.getrusage(2, usage.peer));
    }

    private static void verifyLimits(AndroidEmulator emulator, KernelSyscallsGuest calls, UnidbgPointer limit,
                                     UnidbgPointer requested) {
        long stack = (long) Memory.STACK_SIZE_OF_PAGE * emulator.getPageAlign();
        assertEquals(0, calls.prlimit64(0, RLIMIT_STACK, 0, limit.peer));
        assertEquals(stack, limit64(limit, "rlim_cur"));
        assertEquals(stack, limit64(limit, "rlim_max"));

        assertEquals(0, calls.prlimit64(0, RLIMIT_NPROC, 0, limit.peer));
        assertEquals(Memory.MAX_THREADS, limit64(limit, "rlim_cur"));
        assertEquals(Memory.MAX_THREADS, limit64(limit, "rlim_max"));

        assertEquals(0, calls.prlimit64(0, RLIMIT_CORE, 0, limit.peer));
        assertEquals(0, limit64(limit, "rlim_cur"));
        assertEquals(0, limit64(limit, "rlim_max"));

        // The runtime locks any page it is asked to lock, so the amount is unbounded.
        assertEquals(0, calls.prlimit64(0, RLIMIT_MEMLOCK, 0, limit.peer));
        assertEquals(-1L, limit64(limit, "rlim_cur"));  // RLIM_INFINITY in an eight-byte field
        assertEquals(-1L, limit64(limit, "rlim_max"));
        // The limit the ABI's own rlimit fields hold reports the top of those fields.
        assertEquals(0, calls.getrlimit(RLIMIT_MEMLOCK, limit.peer));
        assertEquals(calls.unlimitedLimit(), limit(calls, limit, "rlim_cur"));
        assertEquals(calls.unlimitedLimit(), limit(calls, limit, "rlim_max"));

        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, 0, limit.peer));
        long descriptors = limit64(limit, "rlim_cur");
        assertEquals(descriptors, limit64(limit, "rlim_max"));
        assertTrue("nofile=" + descriptors, descriptors >= Memory.MAX_THREADS);
        // getrlimit(2) asks the same question, so it answers from the same limits.
        assertEquals(0, calls.getrlimit(RLIMIT_NOFILE, limit.peer));
        assertEquals(descriptors, limit(calls, limit, "rlim_cur"));

        requested.setLong(0, 1024);
        requested.setLong(8, 2048);
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, requested.peer, limit.peer));
        assertEquals(descriptors, limit64(limit, "rlim_cur"));  // the call reports the limits it replaced
        assertEquals(descriptors, limit64(limit, "rlim_max"));
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, 0, limit.peer));
        assertEquals(1024, limit64(limit, "rlim_cur"));
        assertEquals(2048, limit64(limit, "rlim_max"));
        assertEquals(0, calls.getrlimit(RLIMIT_NOFILE, limit.peer));
        assertEquals(1024, limit(calls, limit, "rlim_cur"));
        assertEquals(2048, limit(calls, limit, "rlim_max"));
        // The same limits at the offsets the ABI's struct rlimit gives them: two kernel longs, four
        // bytes each in the struct compat_rlimit the 32-bit call fills.
        if (calls.is64Bit()) {
            assertEquals(1024, limit.getLong(0));
            assertEquals(2048, limit.getLong(8));
        } else {
            assertEquals(1024, word(limit, 0));
            assertEquals(2048, word(limit, 4));
        }

        requested.setLong(0, 4096);
        requested.setLong(8, 1024);
        assertEquals(-UnixEmulator.EINVAL, calls.prlimit64(0, RLIMIT_NOFILE, requested.peer, 0));

        // A process running as root holds CAP_SYS_RESOURCE, so it may raise a hard limit.
        requested.setLong(0, 1024);
        requested.setLong(8, 4096);
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, requested.peer, 0));
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, 0, limit.peer));
        assertEquals(4096, limit64(limit, "rlim_max"));

        assertEquals(-UnixEmulator.EINVAL, calls.prlimit64(0, RESOURCE_COUNT, 0, limit.peer));
        assertEquals(-UnixEmulator.ESRCH, calls.prlimit64(emulator.getPid() + 1, RLIMIT_NOFILE, 0, limit.peer));
        assertEquals(0, calls.prlimit64(emulator.getPid(), RLIMIT_NOFILE, 0, limit.peer));
    }

    private static void verifyDescriptors(KernelSyscallsGuest calls, UnidbgPointer first, UnidbgPointer second) {
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
        StatStructure eventPollStat = stat(calls, first);
        assertEquals(0600, eventPollStat.st_mode);
        assertEquals(0, calls.fstat(timer, second.peer));
        StatStructure timerStat = stat(calls, second);
        assertNotEquals(eventPollStat.st_ino, timerStat.st_ino);
        assertEquals(0600, timerStat.st_mode);
        // The same entries at the offsets the ABI's struct stat gives st_mode and st_ino: a live
        // inode number per descriptor, distinct like the fields are.
        assertEquals(0600, word(first, 16));
        assertEquals(0600, word(second, 16));
        long eventPollInode = ino(calls, first);
        long timerInode = ino(calls, second);
        assertNotEquals(0, eventPollInode);
        assertNotEquals(0, timerInode);
        assertNotEquals(eventPollInode, timerInode);
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
        assertEquals(8, stat(calls, first).st_size);
        // st_size sits at the same offset in the 64-bit struct stat and in the arm stat64 the arm
        // table's fstat(2) fills.
        assertEquals(8, first.getLong(48));

        assertEquals(0, calls.close(eventPoll));
        assertEquals(0, calls.close(timer));
        assertEquals(0, calls.close(memory));
        assertEquals(-UnixEmulator.EBADF, calls.fstat(eventPoll, first.peer));

        assertEquals(-UnixEmulator.EINVAL, calls.epollCreate1(1));
        assertEquals(-UnixEmulator.EINVAL, calls.timerfdCreate(CLOCK_MONOTONIC, 0x100));
        // A clock the runtime does not model as a timer source is refused.
        assertEquals(-UnixEmulator.EINVAL, calls.timerfdCreate(CLOCK_MONOTONIC_RAW, TFD_CLOEXEC));
        // The name is copied before the file is created, so a call that names no file has no name
        // to copy.
        assertEquals(-UnixEmulator.EFAULT, calls.memFdCreate(null, MFD_CLOEXEC));
    }

    private static void verifyProcessor(KernelSyscallsGuest calls, UnidbgPointer first) {
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

    private static void verifyBarriers(KernelSyscallsGuest calls) {
        assertEquals(MEMBARRIER_CMD_GLOBAL, calls.membarrier(MEMBARRIER_CMD_QUERY, 0));
        assertEquals(0, calls.membarrier(MEMBARRIER_CMD_GLOBAL, 0));
        assertEquals(-UnixEmulator.EINVAL, calls.membarrier(MEMBARRIER_CMD_PRIVATE_EXPEDITED, 0));
        assertEquals(-UnixEmulator.EINVAL, calls.membarrier(MEMBARRIER_CMD_QUERY, 1));
    }

    private static void verifyRootCapabilities(AndroidEmulator emulator, KernelSyscallsGuest calls,
                                               UnidbgPointer header, UnidbgPointer data) {
        header.setInt(0, CAPABILITY_VERSION_3);
        header.setInt(4, 0);
        assertEquals(0, calls.capget(header.peer, data.peer));
        assertEquals(DEVICE_ROOT_CAPABILITIES, set(data, 0));
        assertEquals(DEVICE_ROOT_CAPABILITIES, set(data, 4));
        assertEquals(0, set(data, 8));

        // The status of the process reports the sets the syscall answers.
        String status = calls.text("/proc/self/status", data.peer);
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

    private static void verifyRefusals(AndroidEmulator emulator, KernelSyscallsGuest calls,
                                       UnidbgPointer attributes) {
        attributes.setInt(0, 1);  // PERF_TYPE_SOFTWARE
        attributes.setInt(4, 128);  // sizeof(struct perf_event_attr) of the recorded device
        attributes.setLong(8, 0);  // PERF_COUNT_SW_CPU_CLOCK
        assertEquals(-UnixEmulator.EACCES, calls.perfEventOpen(attributes.peer, 0, -1, -1));
        // The runtime authorizes no performance access, whatever the request names.
        assertEquals(-UnixEmulator.EACCES, calls.perfEventOpen(0, 0, -1, -1));

        assertEquals(-UnixEmulator.EPERM, calls.ptrace(PTRACE_ATTACH, emulator.getPid()));
        assertEquals(-UnixEmulator.EPERM, calls.ptrace(PTRACE_TRACEME, 0));

        // A number the table leaves unassigned keeps the answer of an unknown syscall.
        assertEquals(-UnixEmulator.ENOSYS, calls.unmodeled());
    }

    private static void verifyPolicy(KernelSyscallsGuest calls, UnidbgPointer first) {
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

    private static void verifyUnprivilegedProcess(AndroidEmulator emulator, KernelSyscallsGuest calls,
                                                  UnidbgPointer header, UnidbgPointer data) {
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
        String status = calls.text("/proc/self/status", data.peer);
        assertTrue(status, status.contains("CapEff:\t0000000000000000\n"));

        // A process without CAP_SYS_RESOURCE may not raise a hard limit, but lowering one stands.
        header.setLong(0, 1024);
        header.setLong(8, 8192);
        assertEquals(-UnixEmulator.EPERM, calls.prlimit64(0, RLIMIT_NOFILE, header.peer, 0));
        header.setLong(8, 2048);
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, header.peer, 0));
        assertEquals(0, calls.prlimit64(0, RLIMIT_NOFILE, 0, data.peer));
        assertEquals(1024, limit64(data, "rlim_cur"));
        assertEquals(2048, limit64(data, "rlim_max"));
        assertEquals(0, calls.getrlimit(RLIMIT_NOFILE, header.peer));
        assertEquals(1024, limit(calls, header, "rlim_cur"));
        assertEquals(2048, limit(calls, header, "rlim_max"));
    }

    /** The fields {@code sysinfo(2)} writes, in the structure the ABI lays out. */
    private static UnidbgStructure sysinfo(KernelSyscallsGuest calls, UnidbgPointer info) {
        return unpack(calls.is64Bit() ? new SysInfo64(info) : new SysInfo32(info));
    }

    /** The fields {@code getrusage(2)} writes, in the structure the ABI lays out. */
    private static UnidbgStructure rusage(KernelSyscallsGuest calls, UnidbgPointer usage) {
        return unpack(calls.is64Bit() ? new Rusage64(usage) : new Rusage32(usage));
    }

    /** The {@code struct stat} {@code fstat(2)} writes, in the structure the ABI lays out. */
    private static StatStructure stat(KernelSyscallsGuest calls, UnidbgPointer stat) {
        StatStructure values = calls.is64Bit() ? new Stat64(stat) : new Stat32(stat);
        values.unpack();
        return values;
    }

    /** One field of the two eight-byte {@code prlimit64(2)} limits, which both ABIs share. */
    private static long limit64(UnidbgPointer limits, String name) {
        return field(unpack(new RLimit64(limits)), name);
    }

    /** One field of the {@code struct rlimit} {@code getrlimit(2)} writes in this ABI. */
    private static long limit(KernelSyscallsGuest calls, UnidbgPointer limits, String name) {
        return field(unpack(calls.is64Bit() ? new RLimit64(limits) : new RLimit32(limits)), name);
    }

    /**
     * The {@code st_ino} of the {@code struct stat} {@code fstat(2)} wrote, at the offset the ABI
     * gives it: below {@code st_dev} of the 64-bit structure and past the times of the arm
     * {@code stat64} the arm table's {@code fstat(2)} fills.
     */
    private static long ino(KernelSyscallsGuest calls, UnidbgPointer stat) {
        return stat.getLong(calls.is64Bit() ? 8 : 96);
    }

    /** A four-byte field at an offset of a structure, which the ABIs hold unsigned. */
    private static long word(UnidbgPointer memory, int offset) {
        return memory.getInt(offset) & 0xffffffffL;
    }

    /** The CPU time one {@code struct rusage} reports: the user time of the measured entity. */
    private static long clock(UnidbgStructure usage) {
        return field(usage, "ru_utime_sec") * 1_000_000_000L + field(usage, "ru_utime_usec") * 1_000L;
    }

    /** One capability set of the two data records: the low word at its offset, the high word above. */
    private static long set(UnidbgPointer data, int offset) {
        return (data.getInt(offset) & 0xffffffffL) | ((data.getInt(offset + 12) & 0xffffffffL) << 32);
    }

    private static UnidbgStructure unpack(UnidbgStructure values) {
        values.unpack();
        return values;
    }

    /** One field of a structure, whose four-byte fields the ABI holds unsigned. */
    private static long field(UnidbgStructure values, String name) {
        Object value = values.readField(name);
        long number = ((Number) value).longValue();
        return value instanceof Integer ? number & 0xffffffffL : number;
    }
}
