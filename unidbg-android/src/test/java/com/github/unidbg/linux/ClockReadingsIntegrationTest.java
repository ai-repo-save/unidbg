package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.time.ClockSource;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code clock_gettime(2)} and {@code clock_getres(2)} of both bitnesses answer from the
 * process's installed clock readings, in read order, and keep the kernel relations between the
 * clock ids. The CPU clocks count the time the guest spent running, so the window it spends in
 * {@code nanosleep(2)} does not advance them.
 */
public class ClockReadingsIntegrationTest {
    private static final int CLOCK_REALTIME = 0;
    private static final int CLOCK_MONOTONIC = 1;
    private static final int CLOCK_PROCESS_CPUTIME_ID = 2;
    private static final int CLOCK_THREAD_CPUTIME_ID = 3;
    private static final int CLOCK_MONOTONIC_RAW = 4;
    private static final int CLOCK_REALTIME_COARSE = 5;
    private static final int CLOCK_BOOTTIME = 7;
    private static final int CLOCK_REALTIME_ALARM = 8;

    private static final long SLEEP_NANOS = 50_000_000L;
    /** CPU time the sleeping guest may still spend, a quarter of the sleep window. */
    private static final long SLEEP_CPU_NANOS = SLEEP_NANOS / 4;

    @Test
    public void installedReadingsAnswerBothBitnesses() throws Exception {
        Path parent = Paths.get("target/clocks");
        Files.createDirectories(parent);
        for (boolean wide : new boolean[] {false, true}) {
            Path root = Files.createTempDirectory(parent, wide ? "arm64-" : "arm32-").toAbsolutePath();
            try (AndroidEmulator emulator = (wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                    .setRootDir(root.toFile()).addBackendFactory(new Unicorn2Factory(true)).build();
                 RawSyscalls calls = new RawSyscalls(emulator)) {
                ClockSource.install(emulator, ClockSource.of(
                        new long[] {1_700_000_000_000_000_000L, 1_700_000_005_006_000_000L},
                        new long[] {1_000_000_000_000L, 1_000_500_000_000L},
                        3_000_000_000L, 7_000_000_000L));
                MemoryBlock scratch = emulator.getMemory().malloc(4096, true);
                try {
                    UnidbgPointer value = scratch.getPointer();
                    assertEquals(1_700_000_000_000_000_000L, calls.clockGettime(CLOCK_REALTIME, value));
                    assertEquals(1_700_000_005_006_000L, calls.gettimeofday(value));
                    assertEquals(1_000_000_000_000L, calls.clockGettime(CLOCK_MONOTONIC, value));
                    assertEquals(1_003_500_000_000L, calls.clockGettime(CLOCK_BOOTTIME, value));
                    assertEquals(1_007_500_000_000L, calls.clockGettime(CLOCK_MONOTONIC_RAW, value));

                    long thread = calls.clockGettime(CLOCK_THREAD_CPUTIME_ID, value);
                    long process = calls.clockGettime(CLOCK_PROCESS_CPUTIME_ID, value);
                    assertTrue(process >= thread);
                    calls.setTimespec(value, SLEEP_NANOS);
                    calls.nanosleep(value);
                    long sleptThread = calls.clockGettime(CLOCK_THREAD_CPUTIME_ID, value);
                    long sleptProcess = calls.clockGettime(CLOCK_PROCESS_CPUTIME_ID, value);
                    assertTrue(sleptProcess >= sleptThread);
                    assertTrue(sleptThread >= thread);
                    assertTrue(sleptThread - thread <= SLEEP_CPU_NANOS);
                    assertTrue(sleptProcess - process <= SLEEP_CPU_NANOS);
                    // The boot clock counts from boot, the CPU clocks from the process start.
                    assertTrue(sleptProcess < calls.clockGettime(CLOCK_BOOTTIME, value));

                    assertEquals(1L, calls.clockGetres(CLOCK_THREAD_CPUTIME_ID, value));
                    assertEquals(1L, calls.clockGetres(CLOCK_PROCESS_CPUTIME_ID, value));

                    assertEquals(-UnixEmulator.EINVAL, calls.clockGettimeReturn(CLOCK_REALTIME_COARSE, value));
                    assertEquals(-UnixEmulator.EINVAL, calls.clockGettimeReturn(CLOCK_REALTIME_ALARM, value));
                    assertEquals(-UnixEmulator.EINVAL, calls.clockGetresReturn(CLOCK_REALTIME_ALARM, value));
                    // The clocks of the process still answer after the rejected clock ids.
                    assertEquals(1_000_500_000_000L, calls.clockGettime(CLOCK_MONOTONIC, value));
                } finally {
                    scratch.free();
                }
            }
        }
    }

    private static final class RawSyscalls implements AutoCloseable {
        private static final int CLOCK_GETTIMEOFDAY_32 = 78;
        private static final int NANOSLEEP_64 = 101;
        private static final int CLOCK_GETTIME_64 = 113;
        private static final int CLOCK_GETRES_64 = 114;
        private static final int CLOCK_GETTIMEOFDAY_64 = 169;
        private static final int NANOSLEEP_32 = 162;
        private static final int CLOCK_GETTIME_32 = 263;
        private static final int CLOCK_GETRES_32 = 264;

        private final AndroidEmulator emulator;
        private final UnidbgPointer code;
        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(8192, 7);
            for (int number : new int[] {CLOCK_GETTIMEOFDAY_32, NANOSLEEP_64, CLOCK_GETTIME_64,
                    CLOCK_GETRES_64, CLOCK_GETTIMEOFDAY_64, NANOSLEEP_32, CLOCK_GETTIME_32,
                    CLOCK_GETRES_32}) {
                long offset = number * 16L;
                code.setInt(offset, emulator.is64Bit() ? 0xd2800008 | (number << 5)
                        : 0xe3007000 | (number & 0xfff) | ((number & 0xf000) << 4));
                code.setInt(offset + 4, emulator.is64Bit() ? 0xd4000001 : 0xef000000);
                code.setInt(offset + 8, emulator.is64Bit() ? 0xd65f03c0 : 0xe12fff1e);
            }
        }
        /** The return value of {@code clock_gettime(clk_id, timespec)}. */
        int clockGettimeReturn(int clkId, UnidbgPointer timespec) {
            return call(emulator.is64Bit() ? CLOCK_GETTIME_64 : CLOCK_GETTIME_32, clkId, timespec.peer);
        }
        /** The nanoseconds {@code clock_gettime(clk_id, timespec)} reports. */
        long clockGettime(int clkId, UnidbgPointer timespec) {
            assertEquals(0, clockGettimeReturn(clkId, timespec));
            return emulator.is64Bit()
                    ? timespec.getLong(0) * 1_000_000_000L + timespec.getLong(8)
                    : (long) timespec.getInt(0) * 1_000_000_000L + timespec.getInt(4);
        }
        /** The return value of {@code clock_getres(clk_id, res)}. */
        int clockGetresReturn(int clkId, UnidbgPointer res) {
            return call(emulator.is64Bit() ? CLOCK_GETRES_64 : CLOCK_GETRES_32, clkId, res.peer);
        }
        /** The nanoseconds {@code clock_getres(clk_id, res)} reports. */
        long clockGetres(int clkId, UnidbgPointer res) {
            assertEquals(0, clockGetresReturn(clkId, res));
            return emulator.is64Bit()
                    ? res.getLong(0) * 1_000_000_000L + res.getLong(8)
                    : (long) res.getInt(0) * 1_000_000_000L + res.getInt(4);
        }
        /** The microseconds {@code gettimeofday(timeval, NULL)} reports. */
        long gettimeofday(UnidbgPointer timeval) {
            assertEquals(0, call(emulator.is64Bit() ? CLOCK_GETTIMEOFDAY_64 : CLOCK_GETTIMEOFDAY_32,
                    timeval.peer, 0));
            return emulator.is64Bit()
                    ? timeval.getLong(0) * 1_000_000L + timeval.getLong(8)
                    : (long) timeval.getInt(0) * 1_000_000L + timeval.getInt(4);
        }
        void setTimespec(UnidbgPointer timespec, long nanos) {
            if (emulator.is64Bit()) {
                timespec.setLong(0, nanos / 1_000_000_000L);
                timespec.setLong(8, nanos % 1_000_000_000L);
            } else {
                timespec.setInt(0, (int) (nanos / 1_000_000_000L));
                timespec.setInt(4, (int) (nanos % 1_000_000_000L));
            }
        }
        void nanosleep(UnidbgPointer req) {
            assertEquals(0, call(emulator.is64Bit() ? NANOSLEEP_64 : NANOSLEEP_32, req.peer, 0));
        }
        int call(int number, Number... arguments) {
            if (emulator.is32Bit()) {
                arguments = Arrays.stream(arguments).map(Number::intValue).toArray(Number[]::new);
            }
            return emulator.eFunc(code.peer + number * 16L, arguments).intValue();
        }
        @Override public void close() { emulator.getMemory().munmap(code.peer, 8192); }
    }
}
