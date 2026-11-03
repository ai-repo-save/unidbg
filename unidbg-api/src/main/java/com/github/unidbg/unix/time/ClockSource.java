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
 * The coarse clocks read the same timelines truncated to the tick their {@code clock_getres}
 * reports, the alarm clocks read the timeline of their base clock because the alarm ids only
 * affect which timers may wake the device, and {@code CLOCK_TAI} is the wall clock plus the
 * offset the device's timekeeping holds against it.
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
    public static final int CLOCK_REALTIME_COARSE = 5;
    public static final int CLOCK_MONOTONIC_COARSE = 6;
    public static final int CLOCK_BOOTTIME = 7;
    public static final int CLOCK_REALTIME_ALARM = 8;
    public static final int CLOCK_BOOTTIME_ALARM = 9;
    public static final int CLOCK_TAI = 11;

    /** The resolution {@code clock_getres} reports for the clocks that read the timekeeping clock. */
    public static final long CLOCK_RESOLUTION_NANOS = 1;
    /**
     * The tick the coarse clocks truncate to, as the reference device's {@code clock_getres}
     * reports it: the kernel updates their cached reading once per tick of its timer.
     */
    public static final long COARSE_RESOLUTION_NANOS = 10_000_000L;
    /**
     * Distance of {@code CLOCK_TAI} from {@code CLOCK_REALTIME}, the leap second count the
     * kernel keeps; the reference device's time daemon leaves it at zero, so its TAI clock
     * reads the wall clock.
     */
    public static final long TAI_OFFSET_NANOS = 0;

    private static final String KEY = "unidbg.clockSource";

    /** Recorded readings in read order; {@code null} reads the host clock instead. */
    private final long[] realtimeReadings;
    private final long[] monotonicReadings;
    private final long suspendNanos;
    private final long rawSkewNanos;
    /** Where a timeline without recorded readings counts from. */
    private final long hostMonotonicOrigin;
    private int realtimeReads;
    private int monotonicReads;

    private ClockSource(long[] realtimeReadings, long[] monotonicReadings, long suspendNanos,
                        long rawSkewNanos, long hostMonotonicOrigin) {
        this.realtimeReadings = realtimeReadings;
        this.monotonicReadings = monotonicReadings;
        this.suspendNanos = suspendNanos;
        this.rawSkewNanos = rawSkewNanos;
        this.hostMonotonicOrigin = hostMonotonicOrigin;
    }

    /**
     * @param realtimeReadings  wall-clock readings in read order, nanoseconds; not empty
     * @param monotonicReadings monotonic readings in read order, nanoseconds; not empty
     * @param suspendNanos      time the process spent suspended, which only CLOCK_BOOTTIME counts
     * @param rawSkewNanos      distance of CLOCK_MONOTONIC_RAW from the monotonic timeline
     */
    public static ClockSource of(long[] realtimeReadings, long[] monotonicReadings,
                                 long suspendNanos, long rawSkewNanos) {
        return new ClockSource(realtimeReadings, monotonicReadings, suspendNanos, rawSkewNanos,
                System.nanoTime());
    }

    /**
     * The clocks of the emulated process, shared by every reader in it. A process whose timeline
     * an observer did not record reads the host monotonic clock from the instant the process
     * started running, which is where its CPU clocks count from as well.
     */
    public static ClockSource of(Emulator<?> emulator) {
        ClockSource source = emulator.get(KEY);
        if (source == null) {
            source = new ClockSource(null, null, 0, 0, CpuTimeSource.of(emulator).startNanos());
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
     * process for the clocks it does not model. {@code CLOCK_SGI_CYCLE} is hardware specific:
     * a kernel without a cycle counter refuses it, and so does this process.
     */
    public static boolean isModeled(int clockId) {
        switch (clockId) {
            case CLOCK_REALTIME:
            case CLOCK_MONOTONIC:
            case CLOCK_PROCESS_CPUTIME_ID:
            case CLOCK_THREAD_CPUTIME_ID:
            case CLOCK_MONOTONIC_RAW:
            case CLOCK_REALTIME_COARSE:
            case CLOCK_MONOTONIC_COARSE:
            case CLOCK_BOOTTIME:
            case CLOCK_REALTIME_ALARM:
            case CLOCK_BOOTTIME_ALARM:
            case CLOCK_TAI:
                return true;
            default:
                return false;
        }
    }

    /** The resolution {@code clock_getres} reports for one clock of the process. */
    public static long resolutionNanos(int clockId) {
        switch (clockId) {
            case CLOCK_REALTIME_COARSE:
            case CLOCK_MONOTONIC_COARSE:
                return COARSE_RESOLUTION_NANOS;
            default:
                return CLOCK_RESOLUTION_NANOS;
        }
    }

    /** One reading of the kernel's {@code clock_gettime}, in nanoseconds. */
    public static long clockNanos(Emulator<?> emulator, int clockId) {
        ClockSource source = of(emulator);
        switch (clockId) {
            case CLOCK_REALTIME:
            case CLOCK_REALTIME_ALARM:
                return source.realtimeNanos();
            case CLOCK_REALTIME_COARSE:
                return truncate(source.realtimeNanos(), COARSE_RESOLUTION_NANOS);
            case CLOCK_TAI:
                return source.realtimeNanos() + TAI_OFFSET_NANOS;
            case CLOCK_MONOTONIC:
                return source.monotonicNanos();
            case CLOCK_MONOTONIC_COARSE:
                return truncate(source.monotonicNanos(), COARSE_RESOLUTION_NANOS);
            case CLOCK_MONOTONIC_RAW:
                return source.monotonicRawNanos();
            case CLOCK_BOOTTIME:
            case CLOCK_BOOTTIME_ALARM:
                return source.boottimeNanos();
            case CLOCK_PROCESS_CPUTIME_ID:
                return CpuTimeSource.of(emulator).processNanos();
            case CLOCK_THREAD_CPUTIME_ID:
                return CpuTimeSource.of(emulator).threadNanos();
            default:
                throw new IllegalArgumentException("clk_id=" + clockId);
        }
    }

    /** A coarse clock reports the base reading of the tick it falls into. */
    private static long truncate(long nanos, long resolution) {
        return nanos - Math.floorMod(nanos, resolution);
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
