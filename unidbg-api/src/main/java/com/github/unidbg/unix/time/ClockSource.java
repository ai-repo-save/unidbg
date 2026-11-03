package com.github.unidbg.unix.time;

import com.github.unidbg.Emulator;

/**
 * The clocks of one emulated process, all read from one monotonic timeline and one wall-clock
 * timeline.
 *
 * A device clock advances between two reads, so a process that reports several readings of the
 * same clock reports different values; each timeline therefore replays the readings an observer
 * recorded, in the order the guest reads them, and repeats the last one once they run out. The
 * clocks keep their kernel relations: {@code CLOCK_BOOTTIME} is the monotonic timeline plus the
 * time the device spent suspended, {@code CLOCK_MONOTONIC_RAW} is the monotonic timeline without
 * the NTP slew, {@code gettimeofday} reads the wall clock, and Android's
 * {@code SystemClock.uptimeMillis} is the millisecond reading of the monotonic timeline.
 *
 * {@code CLOCK_PROCESS_CPUTIME_ID} and {@code CLOCK_THREAD_CPUTIME_ID} count CPU time instead of
 * elapsed time and come from {@link CpuTimeSource}: a process that sleeps burns neither.
 */
public final class ClockSource {

    public static final int CLOCK_REALTIME = 0;
    public static final int CLOCK_MONOTONIC = 1;
    public static final int CLOCK_PROCESS_CPUTIME_ID = 2;
    public static final int CLOCK_THREAD_CPUTIME_ID = 3;
    public static final int CLOCK_MONOTONIC_RAW = 4;
    public static final int CLOCK_MONOTONIC_COARSE = 6;
    public static final int CLOCK_BOOTTIME = 7;

    /** The resolution {@code clock_getres} reports for every clock of the process. */
    public static final long CLOCK_RESOLUTION_NANOS = 1;

    private static final String KEY = "unidbg.clockSource";

    /** Recorded readings in read order; {@code null} reads the host clock instead. */
    private final long[] realtimeReadings;
    private final long[] monotonicReadings;
    private final long suspendNanos;
    private final long rawSkewNanos;
    private final long hostMonotonicOrigin = System.nanoTime();
    private int realtimeReads;
    private int monotonicReads;

    private ClockSource(long[] realtimeReadings, long[] monotonicReadings, long suspendNanos,
                        long rawSkewNanos) {
        this.realtimeReadings = realtimeReadings;
        this.monotonicReadings = monotonicReadings;
        this.suspendNanos = suspendNanos;
        this.rawSkewNanos = rawSkewNanos;
    }

    /**
     * @param realtimeReadings  wall-clock readings in read order, nanoseconds; not empty
     * @param monotonicReadings monotonic readings in read order, nanoseconds; not empty
     * @param suspendNanos      time the process spent suspended, which only CLOCK_BOOTTIME counts
     * @param rawSkewNanos      distance of CLOCK_MONOTONIC_RAW from the monotonic timeline
     */
    public static ClockSource of(long[] realtimeReadings, long[] monotonicReadings,
                                 long suspendNanos, long rawSkewNanos) {
        return new ClockSource(realtimeReadings, monotonicReadings, suspendNanos, rawSkewNanos);
    }

    /** The clocks of the emulated process, shared by every reader in it. */
    public static ClockSource of(Emulator<?> emulator) {
        ClockSource source = emulator.get(KEY);
        if (source == null) {
            source = new ClockSource(null, null, 0, 0);
            emulator.set(KEY, source);
        }
        return source;
    }

    /** Replaces the clocks of the emulated process, which its readers must use from then on. */
    public static void install(Emulator<?> emulator, ClockSource source) {
        emulator.set(KEY, source);
    }

    /**
     * Whether the emulated process has this clock. The kernel answers a {@code clock_gettime} or
     * {@code clock_getres} of a clock id it does not know with {@code EINVAL}, and so does the
     * process for the clocks it does not model.
     */
    public static boolean isModeled(int clockId) {
        switch (clockId) {
            case CLOCK_REALTIME:
            case CLOCK_MONOTONIC:
            case CLOCK_PROCESS_CPUTIME_ID:
            case CLOCK_THREAD_CPUTIME_ID:
            case CLOCK_MONOTONIC_RAW:
            case CLOCK_MONOTONIC_COARSE:
            case CLOCK_BOOTTIME:
                return true;
            default:
                return false;
        }
    }

    /** One reading of the kernel's {@code clock_gettime}, in nanoseconds. */
    public static long clockNanos(Emulator<?> emulator, int clockId) {
        switch (clockId) {
            case CLOCK_REALTIME:
                return of(emulator).realtimeNanos();
            case CLOCK_MONOTONIC:
            case CLOCK_MONOTONIC_COARSE:
                return of(emulator).monotonicNanos();
            case CLOCK_MONOTONIC_RAW:
                return of(emulator).monotonicRawNanos();
            case CLOCK_BOOTTIME:
                return of(emulator).boottimeNanos();
            case CLOCK_PROCESS_CPUTIME_ID:
                return CpuTimeSource.of(emulator).processNanos();
            case CLOCK_THREAD_CPUTIME_ID:
                return CpuTimeSource.of(emulator).threadNanos();
            default:
                throw new IllegalArgumentException("clk_id=" + clockId);
        }
    }

    /** Wall clock; {@code gettimeofday} and {@code System.currentTimeMillis} read it. */
    public long realtimeNanos() {
        if (realtimeReadings == null) {
            return System.currentTimeMillis() * 1_000_000L;
        }
        return realtimeReadings[Math.min(realtimeReads++, realtimeReadings.length - 1)];
    }

    public long realtimeMillis() {
        return realtimeNanos() / 1_000_000L;
    }

    /** Monotonic timeline without suspend time; Android's uptime counts the same clock. */
    public long monotonicNanos() {
        if (monotonicReadings == null) {
            return System.nanoTime() - hostMonotonicOrigin;
        }
        return monotonicReadings[Math.min(monotonicReads++, monotonicReadings.length - 1)];
    }

    public long uptimeMillis() {
        return monotonicNanos() / 1_000_000L;
    }

    /** Monotonic timeline plus suspend time; Android's elapsedRealtime counts the same clock. */
    public long boottimeNanos() {
        return monotonicNanos() + suspendNanos;
    }

    public long elapsedRealtimeMillis() {
        return boottimeNanos() / 1_000_000L;
    }

    public long monotonicRawNanos() {
        return monotonicNanos() + rawSkewNanos;
    }
}
