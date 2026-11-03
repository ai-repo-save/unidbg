package com.github.unidbg.linux.kernel;

import com.github.unidbg.Emulator;
import com.github.unidbg.memory.Memory;

import java.lang.management.ManagementFactory;

/**
 * The resource limits of the emulated process, which {@code getrlimit(2)} and
 * {@code prlimit64(2)} report and set.
 *
 * A limit is either a fact of the runtime or unbounded: the stack limit is the stack the runtime
 * mapped, the process limit is the number of threads the runtime's thread table holds, the
 * descriptor limit is the number of descriptors the host gives the runtime, and the core limit is
 * zero because the runtime writes no core file. Every other resource is unbounded, which is what a
 * runtime that never refuses an operation for exceeding it enforces.
 *
 * The guest may set a limit; later queries report the value it set. The runtime enforces only the
 * limits its own structures enforce, so a set value is a statement about the process, not a bound
 * the emulation applies.
 */
public final class ProcessLimits {

    public static final int RLIMIT_CPU = 0;
    public static final int RLIMIT_FSIZE = 1;
    public static final int RLIMIT_DATA = 2;
    public static final int RLIMIT_STACK = 3;
    public static final int RLIMIT_CORE = 4;
    public static final int RLIMIT_RSS = 5;
    public static final int RLIMIT_NPROC = 6;
    public static final int RLIMIT_NOFILE = 7;
    public static final int RLIMIT_MEMLOCK = 8;
    public static final int RLIMIT_AS = 9;
    public static final int RLIMIT_LOCKS = 10;
    public static final int RLIMIT_SIGPENDING = 11;
    public static final int RLIMIT_MSGQUEUE = 12;
    public static final int RLIMIT_NICE = 13;
    public static final int RLIMIT_RTPRIO = 14;
    public static final int RLIMIT_RTTIME = 15;

    /** {@code RLIM_NLIMITS}: the resources a 64-bit Linux numbers. */
    public static final int RESOURCE_COUNT = 16;

    /** {@code RLIM_INFINITY}: the kernel reports an unbounded limit as every bit set. */
    public static final long UNLIMITED = -1L;

    private static final String KEY = "unidbg.processLimits";

    private final long[] soft = new long[RESOURCE_COUNT];
    private final long[] hard = new long[RESOURCE_COUNT];

    private ProcessLimits(Emulator<?> emulator) {
        for (int resource = 0; resource < RESOURCE_COUNT; resource++) {
            soft[resource] = UNLIMITED;
            hard[resource] = UNLIMITED;
        }
        long stack = ProcessMemory.stackBytes(emulator);
        soft[RLIMIT_STACK] = stack;
        hard[RLIMIT_STACK] = stack;
        soft[RLIMIT_CORE] = 0;
        hard[RLIMIT_CORE] = 0;
        soft[RLIMIT_NPROC] = Memory.MAX_THREADS;
        hard[RLIMIT_NPROC] = Memory.MAX_THREADS;
        soft[RLIMIT_NOFILE] = hostDescriptors();
        hard[RLIMIT_NOFILE] = soft[RLIMIT_NOFILE];
    }

    /**
     * The descriptor limit of the host process the guest runs in, which bounds the guest's own
     * descriptors because the files it opens hold host descriptors.
     */
    private static long hostDescriptors() {
        return ((com.sun.management.UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getMaxFileDescriptorCount();
    }

    /** The limits of the emulated process, shared by every reader in it. */
    public static ProcessLimits of(Emulator<?> emulator) {
        ProcessLimits limits = emulator.get(KEY);
        if (limits == null) {
            limits = new ProcessLimits(emulator);
            emulator.set(KEY, limits);
        }
        return limits;
    }

    /** Whether a 64-bit Linux numbers this resource. */
    public static boolean isResource(int resource) {
        return resource >= 0 && resource < RESOURCE_COUNT;
    }

    public long soft(int resource) {
        return soft[resource];
    }

    public long hard(int resource) {
        return hard[resource];
    }

    /** Sets both limits of one resource, which replaces what every later query reports. */
    public void update(int resource, long soft, long hard) {
        this.soft[resource] = soft;
        this.hard[resource] = hard;
    }
}
