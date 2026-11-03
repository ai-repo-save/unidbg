package com.github.unidbg.linux.kernel;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.linux.file.AnonInodeFileIO;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.linux.struct.RLimit32;
import com.github.unidbg.linux.struct.RLimit64;
import com.github.unidbg.linux.struct.Rusage32;
import com.github.unidbg.linux.struct.Rusage64;
import com.github.unidbg.linux.struct.SysInfo32;
import com.github.unidbg.linux.struct.SysInfo64;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.UnixSyscallHandler;
import com.github.unidbg.unix.time.ClockSource;
import com.github.unidbg.unix.time.CpuTimeSource;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * The syscalls a Linux process answers from its own facts and from the machine it runs on:
 * {@code sysinfo(2)}, {@code getrusage(2)}, {@code prlimit64(2)}, {@code epoll_create1(2)},
 * {@code timerfd_create(2)}, {@code memfd_create(2)}, {@code getcpu(2)}, {@code membarrier(2)},
 * {@code capget(2)}, {@code perf_event_open(2)} and {@code ptrace(2)}.
 *
 * The numbers are the runtime's own: the monotonic timeline of the process, the CPU time it spent,
 * the memory and processors the host gives it, and the descriptors and threads its tables hold. The
 * runtime holds one process, so a call naming another process finds none, and a call that would need
 * a second process, a debugger or performance counters is refused as the kernel refuses it.
 *
 * Both Android ABIs share this implementation: the numbers and the answers are the same, and the
 * width of the ABI's {@code long} decides the layout of the structures a call fills.
 */
public final class KernelSyscalls {

    private static final Logger log = LoggerFactory.getLogger(KernelSyscalls.class);

    /** The width of the ABI's {@code long}, which the structures the calls fill follow. */
    public enum Abi {
        /** Every {@code long} is four bytes wide. */
        ILP32,
        /** Every {@code long} is eight bytes wide. */
        LP64,
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MICROSECOND = 1_000L;
    private static final long BYTES_PER_KILOBYTE = 1024L;

    /** The runtime holds one process, which is every process the emulated system has. */
    private static final short PROCESS_COUNT = 1;
    /** The emulated machine has one memory node. */
    private static final int NUMA_NODE = 0;

    private static final int RUSAGE_SELF = 0;
    private static final int RUSAGE_CHILDREN = -1;
    private static final int RUSAGE_THREAD = 1;

    private static final int MEMBARRIER_CMD_QUERY = 0;
    private static final int MEMBARRIER_CMD_GLOBAL = 1;
    /**
     * The barrier commands the emulation answers. A guest thread runs on the host thread that
     * dispatches it, so the commands that order stores between processors are the only ones a
     * caller can name, and {@code MEMBARRIER_CMD_GLOBAL} is the one a caller needs.
     */
    private static final int SUPPORTED_MEMBARRIER_COMMANDS = MEMBARRIER_CMD_GLOBAL;

    private static final int CAPABILITY_VERSION_3 = 0x20080522;
    /** {@code CAP_SYS_RESOURCE}: a process holding it may raise the hard limit of a resource. */
    private static final int CAP_SYS_RESOURCE = 24;

    private static final int EPOLL_CLOEXEC = IOConstants.O_CLOEXEC;
    private static final int TFD_CLOEXEC = IOConstants.O_CLOEXEC;
    private static final int TFD_NONBLOCK = IOConstants.O_NONBLOCK;
    private static final int MFD_CLOEXEC = 0x0001;
    private static final int MFD_ALLOW_SEALING = 0x0002;

    private static final String EPOLL_INODE = "anon_inode:[eventpoll]";
    private static final String TIMERFD_INODE = "anon_inode:[timerfd]";

    /** {@code COMPAT_RLIM_INFINITY}: the top of the 32-bit ABI's limit fields. */
    private static final long COMPAT_RLIM_INFINITY = 0xffffffffL;

    private final UnixSyscallHandler<AndroidFileIO> descriptors;
    private final Abi abi;

    public KernelSyscalls(UnixSyscallHandler<AndroidFileIO> descriptors, Abi abi) {
        this.descriptors = descriptors;
        this.abi = abi;
    }

    /** {@code sysinfo(2)}: the memory, the process count and the uptime of the emulated machine. */
    public long sysinfo(Emulator<AndroidFileIO> emulator, Pointer info) {
        long uptime = ClockSource.of(emulator).monotonicNanos() / NANOS_PER_SECOND;
        long totalRam = MachineFacts.totalMemoryBytes();
        long freeRam = MachineFacts.freeMemoryBytes();
        long memUnit = 1;
        if (abi == Abi.ILP32 && (totalRam >>> 32) != 0) {
            // A memory count the four-byte fields cannot hold is reported in units of mem_unit,
            // which the 32-bit ABI raises to the page size before it divides the counts.
            while (memUnit < emulator.getPageAlign()) {
                memUnit <<= 1;
                totalRam >>= 1;
                freeRam >>= 1;
            }
        }
        if (abi == Abi.ILP32) {
            SysInfo32 values = new SysInfo32(info);
            values.uptime = (int) uptime;
            values.totalRam = (int) totalRam;
            values.freeRam = (int) freeRam;
            values.procs = PROCESS_COUNT;
            values.mem_unit = (int) memUnit;
            values.pack();
        } else {
            SysInfo64 values = new SysInfo64(info);
            values.uptime = uptime;
            values.totalRam = totalRam;
            values.freeRam = freeRam;
            values.procs = PROCESS_COUNT;
            values.mem_unit = (int) memUnit;
            values.pack();
        }
        log.debug("event=kernel_sysinfo_answered uptime={} totalram={} freeram={} procs={} mem_unit={}",
                uptime, totalRam, freeRam, PROCESS_COUNT, memUnit);
        return 0;
    }

    /**
     * {@code getrusage(2)}: the CPU time and the maximum resident set of the process, its threads
     * or its children.
     *
     * {@code ru_maxrss} is the largest resident set the measured entity held, which Linux reports
     * in kilobytes. The runtime does not observe which mapped pages are resident, so it answers the
     * bytes it mapped for the process, which a thread that runs in that address space shares. The
     * runtime holds no child of the process, so {@code RUSAGE_CHILDREN} answers no time and no
     * resident set.
     */
    public long getrusage(Emulator<AndroidFileIO> emulator, int who, Pointer usage) {
        long nanos;
        switch (who) {
            case RUSAGE_SELF:
                nanos = CpuTimeSource.of(emulator).processNanos();
                break;
            case RUSAGE_CHILDREN:
                // The runtime holds no child of the process, so no child spent CPU time.
                nanos = 0;
                break;
            case RUSAGE_THREAD:
                nanos = CpuTimeSource.of(emulator).threadNanos();
                break;
            default:
                log.debug("event=rusage_rejected who={} errno={}", who, UnixEmulator.EINVAL);
                return -UnixEmulator.EINVAL;
        }
        // The host runs guest instructions and guest syscalls in user mode alike, so the CPU time
        // the runtime measures is user time and none of it is system time.
        long maxrss = who == RUSAGE_CHILDREN
                ? 0 : ProcessMemory.mappedBytes(emulator) / BYTES_PER_KILOBYTE;
        if (abi == Abi.ILP32) {
            Rusage32 values = new Rusage32(usage);
            values.ru_utime_sec = (int) (nanos / NANOS_PER_SECOND);
            values.ru_utime_usec = (int) (nanos % NANOS_PER_SECOND / NANOS_PER_MICROSECOND);
            values.ru_maxrss = (int) maxrss;
            values.pack();
        } else {
            Rusage64 values = new Rusage64(usage);
            values.ru_utime_sec = nanos / NANOS_PER_SECOND;
            values.ru_utime_usec = nanos % NANOS_PER_SECOND / NANOS_PER_MICROSECOND;
            values.ru_maxrss = maxrss;
            values.pack();
        }
        log.debug("event=rusage_answered who={} cpu_nanos={} maxrss={}", who, nanos, maxrss);
        return 0;
    }

    /**
     * {@code prlimit64(2)}. A call with no new limit queries the resource; a call with one sets both
     * limits, which later queries report. Raising a hard limit takes {@code CAP_SYS_RESOURCE}, as the
     * kernel requires.
     */
    public long prlimit64(Emulator<AndroidFileIO> emulator, int pid, int resource, Pointer newLimit,
                          Pointer oldLimit) {
        if (!ProcessLimits.isResource(resource)) {
            log.debug("event=prlimit_rejected pid={} resource={} errno={}", pid, resource, UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        if (pid != 0 && pid != emulator.getPid()) {
            log.debug("event=prlimit_rejected pid={} resource={} errno={}", pid, resource, UnixEmulator.ESRCH);
            return -UnixEmulator.ESRCH;
        }
        ProcessLimits limits = ProcessLimits.of(emulator);
        long soft = limits.soft(resource);
        long hard = limits.hard(resource);
        long requestedSoft = 0;
        long requestedHard = 0;
        if (newLimit != null) {
            RLimit64 requested = new RLimit64(newLimit);
            requested.unpack();
            requestedSoft = requested.rlim_cur;
            requestedHard = requested.rlim_max;
            if (Long.compareUnsigned(requestedSoft, requestedHard) > 0) {
                log.debug("event=prlimit_rejected pid={} resource={} soft={} hard={} errno={}",
                        pid, resource, requestedSoft, requestedHard, UnixEmulator.EINVAL);
                return -UnixEmulator.EINVAL;
            }
            boolean privileged = (ProcessCapabilities.of(emulator).effective & 1L << CAP_SYS_RESOURCE) != 0;
            if (!privileged && Long.compareUnsigned(requestedHard, hard) > 0) {
                log.debug("event=prlimit_rejected pid={} resource={} hard={} errno={}",
                        pid, resource, requestedHard, UnixEmulator.EPERM);
                return -UnixEmulator.EPERM;
            }
        }
        if (oldLimit != null) {
            RLimit64 previous = new RLimit64(oldLimit);
            previous.rlim_cur = soft;
            previous.rlim_max = hard;
            previous.pack();
        }
        if (newLimit != null) {
            limits.update(resource, requestedSoft, requestedHard);
            log.debug("event=prlimit_updated pid={} resource={} soft={} hard={}", pid, resource,
                    requestedSoft, requestedHard);
        } else {
            log.debug("event=prlimit_queried pid={} resource={} soft={} hard={}", pid, resource, soft, hard);
        }
        return 0;
    }

    /**
     * {@code getrlimit(2)} of the 32-bit ABI, which {@code ugetrlimit} names: the two limits of one
     * resource in a {@code struct rlimit} of four-byte fields. The ABI reports every limit above its
     * fields as {@code COMPAT_RLIM_INFINITY}.
     */
    public long getrlimit32(Emulator<AndroidFileIO> emulator, int resource, Pointer limit) {
        if (!ProcessLimits.isResource(resource)) {
            log.debug("event=prlimit_rejected pid=0 resource={} errno={}", resource, UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        ProcessLimits limits = ProcessLimits.of(emulator);
        RLimit32 values = new RLimit32(limit);
        values.rlim_cur = capped(limits.soft(resource));
        values.rlim_max = capped(limits.hard(resource));
        values.pack();
        log.debug("event=prlimit_queried pid=0 resource={} soft={} hard={}", resource, limits.soft(resource),
                limits.hard(resource));
        return 0;
    }

    /** One limit in a four-byte field: a limit above the top of the field reports the top. */
    private static int capped(long limit) {
        return Long.compareUnsigned(limit, COMPAT_RLIM_INFINITY) > 0
                ? (int) COMPAT_RLIM_INFINITY : (int) limit;
    }

    /** {@code epoll_create1(2)}: a descriptor on an anonymous inode. */
    public long epollCreate1(int flags) {
        if ((flags & ~EPOLL_CLOEXEC) != 0) {
            log.debug("event=epoll_create_rejected flags=0x{} errno={}", Integer.toHexString(flags),
                    UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        int fd = descriptors.addFileIO(new AnonInodeFileIO(EPOLL_INODE, flags));
        log.debug("event=kernel_descriptor_created kind=epoll fd={} name={}", fd, EPOLL_INODE);
        return fd;
    }

    /** {@code timerfd_create(2)}: a descriptor on an anonymous inode that counts a modeled clock. */
    public long timerfdCreate(int clockId, int flags) {
        if (!isTimerClock(clockId)) {
            log.debug("event=timerfd_create_rejected clock_id={} errno={}", clockId, UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        if ((flags & ~(TFD_CLOEXEC | TFD_NONBLOCK)) != 0) {
            log.debug("event=timerfd_create_rejected flags=0x{} errno={}", Integer.toHexString(flags),
                    UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        int fd = descriptors.addFileIO(new AnonInodeFileIO(TIMERFD_INODE, flags));
        log.debug("event=kernel_descriptor_created kind=timerfd fd={} clock_id={} name={}", fd, clockId,
                TIMERFD_INODE);
        return fd;
    }

    /**
     * The clocks a timer may count. The runtime models the boot, the monotonic and the wall clock,
     * and the alarm variants of the last two as unsupported.
     */
    private static boolean isTimerClock(int clockId) {
        switch (clockId) {
            case ClockSource.CLOCK_REALTIME:
            case ClockSource.CLOCK_MONOTONIC:
            case ClockSource.CLOCK_BOOTTIME:
                return true;
            default:
                return false;
        }
    }

    /**
     * {@code memfd_create(2)}: a file the process reaches only through descriptors. The pages of the
     * file come from the host, which holds them for the runtime the way the kernel holds a memfd.
     * The name labels the file and is copied before the file is created, so a call that names no
     * file has no name to copy and answers {@code EFAULT}.
     */
    public long memFdCreate(Pointer name, int flags) {
        if ((flags & ~(MFD_CLOEXEC | MFD_ALLOW_SEALING)) != 0) {
            log.debug("event=memfd_create_rejected name=0x{} flags=0x{} errno={}",
                    Long.toHexString(Pointer.nativeValue(name)), Integer.toHexString(flags),
                    UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        if (name == null) {
            log.debug("event=memfd_create_rejected name=0x0 flags=0x{} errno={}",
                    Integer.toHexString(flags), UnixEmulator.EFAULT);
            return -UnixEmulator.EFAULT;
        }
        String label = name.getString(0);
        File pages;
        try {
            pages = File.createTempFile("unidbg-memfd-", null);
        } catch (IOException failure) {
            log.warn("event=memfd_storage_failed name={} errno={}", label, UnixEmulator.ENOMEM, failure);
            return -UnixEmulator.ENOMEM;
        }
        int oflags = IOConstants.O_RDWR | ((flags & MFD_CLOEXEC) != 0 ? IOConstants.O_CLOEXEC : 0);
        AndroidFileIO file = new SimpleFileIO(oflags, pages, label);
        // The file stays reachable through the descriptor alone, so it takes no name in the host's
        // file system from here on; the kernel unlinks a memfd the same way.
        if (!pages.delete()) {
            throw new IllegalStateException("Releasing memfd storage: " + pages);
        }
        int fd = descriptors.addFileIO(file);
        log.debug("event=kernel_descriptor_created kind=memfd fd={} name={}", fd, label);
        return fd;
    }

    /** {@code getcpu(2)}: the processor and the memory node of the calling guest thread. */
    public long getcpu(int threadId, Pointer cpu, Pointer node) {
        int processor = MachineFacts.processorOf(threadId);
        if (cpu != null) cpu.setInt(0, processor);
        if (node != null) node.setInt(0, NUMA_NODE);
        log.debug("event=kernel_getcpu_answered thread={} cpu={} node={}", threadId, processor, NUMA_NODE);
        return 0;
    }

    /** {@code membarrier(2)}. */
    public long membarrier(int command, int flags) {
        if (flags != 0) {
            log.debug("event=membarrier_rejected command=0x{} flags=0x{} errno={}", Integer.toHexString(command),
                    Integer.toHexString(flags), UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        switch (command) {
            case MEMBARRIER_CMD_QUERY:
                log.debug("event=membarrier_commands_answered commands=0x{}",
                        Integer.toHexString(SUPPORTED_MEMBARRIER_COMMANDS));
                return SUPPORTED_MEMBARRIER_COMMANDS;
            case MEMBARRIER_CMD_GLOBAL:
                // Every guest thread runs on the host thread that dispatches it, so the stores of
                // one guest thread are ordered before the loads of another without a barrier.
                log.debug("event=membarrier_completed command=0x{}", Integer.toHexString(command));
                return 0;
            default:
                log.debug("event=membarrier_rejected command=0x{} errno={}", Integer.toHexString(command),
                        UnixEmulator.EINVAL);
                return -UnixEmulator.EINVAL;
        }
    }

    /** {@code capget(2)}: the capability sets of the process that {@code /proc/self/status} renders. */
    public long capget(Emulator<AndroidFileIO> emulator, Pointer header, Pointer data) {
        int version = header.getInt(0);
        int pid = header.getInt(4);
        if (version != CAPABILITY_VERSION_3) {
            // A 64-bit kernel answers only the third version and names it to the caller.
            header.setInt(0, CAPABILITY_VERSION_3);
            log.debug("event=capget_version_rejected version=0x{} preferred=0x{} errno={}",
                    Integer.toHexString(version), Integer.toHexString(CAPABILITY_VERSION_3), UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        if (pid != 0 && pid != emulator.getPid()) {
            log.debug("event=capget_rejected pid={} errno={}", pid, UnixEmulator.ESRCH);
            return -UnixEmulator.ESRCH;
        }
        ProcessCapabilities capabilities = ProcessCapabilities.of(emulator);
        data.setInt(0, (int) capabilities.effective);
        data.setInt(4, (int) capabilities.permitted);
        data.setInt(8, (int) capabilities.inheritable);
        data.setInt(12, (int) (capabilities.effective >>> 32));
        data.setInt(16, (int) (capabilities.permitted >>> 32));
        data.setInt(20, (int) (capabilities.inheritable >>> 32));
        log.debug("event=capget_answered pid={} effective=0x{} permitted=0x{} inheritable=0x{}", pid,
                Long.toHexString(capabilities.effective), Long.toHexString(capabilities.permitted),
                Long.toHexString(capabilities.inheritable));
        return 0;
    }

    /**
     * {@code perf_event_open(2)}. The runtime holds no performance counter, so it refuses the request
     * rather than answer with a descriptor that measures nothing: a kernel refuses performance access
     * to a process it does not authorize for it with {@code EACCES}, and the runtime authorizes none.
     * The refusal holds for every request, so the attributes the caller names are not read.
     */
    public long perfEventOpen(Pointer attributes, int pid, int cpu, int groupFd) {
        log.debug("event=perf_event_refused attributes=0x{} pid={} cpu={} group_fd={} errno={}",
                Long.toHexString(Pointer.nativeValue(attributes)), pid, cpu, groupFd, UnixEmulator.EACCES);
        return -UnixEmulator.EACCES;
    }

    /**
     * {@code mount(2)}. The mount table the runtime serves is the one an observer recorded or the
     * three entries the view renders, and no process may change it: the platform denies the operation
     * before it looks at the arguments, so no name the caller passes is read.
     */
    public long mount(Pointer source, Pointer target, Pointer type, long flags) {
        log.debug("event=mount_refused source=0x{} target=0x{} type=0x{} flags=0x{} errno={}",
                Long.toHexString(Pointer.nativeValue(source)), Long.toHexString(Pointer.nativeValue(target)),
                Long.toHexString(Pointer.nativeValue(type)), Long.toHexString(flags), UnixEmulator.EACCES);
        return -UnixEmulator.EACCES;
    }

    /** {@code personality(2)}: the execution domain of the process. */
    public long personality(Emulator<?> emulator, long requested) {
        ProcessPersona persona = ProcessPersona.of(emulator);
        if (requested == ProcessPersona.QUERY) {
            int current = persona.current();
            log.debug("event=personality_answered persona=0x{}", Integer.toHexString(current));
            return current;
        }
        int previous = persona.replace((int) requested);
        log.debug("event=personality_replaced persona=0x{} previous=0x{}", Integer.toHexString((int) requested),
                Integer.toHexString(previous));
        return previous;
    }

    /**
     * {@code ptrace(2)}. The runtime attaches no debugger, and the kernel refuses to attach the
     * calling process; the process reports a seccomp filter, so it traces nobody else either.
     */
    public long ptrace(Emulator<?> emulator, int request, int pid) {
        log.debug("event=ptrace_refused request=0x{} pid={} self={} errno={}", Integer.toHexString(request), pid,
                pid == emulator.getPid(), UnixEmulator.EPERM);
        return -UnixEmulator.EPERM;
    }
}
