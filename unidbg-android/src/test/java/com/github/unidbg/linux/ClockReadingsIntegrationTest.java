package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.time.ClockSource;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/**
 * {@code clock_gettime(2)} of both bitnesses answers from the process's installed clock
 * readings, in read order, and keeps the kernel relations between the clock ids.
 */
public class ClockReadingsIntegrationTest {
    private static final int CLOCK_REALTIME = 0;
    private static final int CLOCK_MONOTONIC = 1;
    private static final int CLOCK_THREAD_CPUTIME_ID = 3;
    private static final int CLOCK_MONOTONIC_RAW = 4;
    private static final int CLOCK_BOOTTIME = 7;

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
                    UnidbgPointer tp = scratch.getPointer();
                    assertEquals(1_700_000_000L, calls.clockGettime(CLOCK_REALTIME, tp, wide));
                    assertEquals(0L, secondField(tp, wide));
                    assertEquals(1_700_000_005L, calls.gettimeofday(tp, wide));
                    assertEquals(6_000L, secondField(tp, wide));
                    assertEquals(1_000L, calls.clockGettime(CLOCK_MONOTONIC, tp, wide));
                    assertEquals(0L, secondField(tp, wide));
                    assertEquals(1_003L, calls.clockGettime(CLOCK_BOOTTIME, tp, wide));
                    assertEquals(500_000_000L, secondField(tp, wide));
                    assertEquals(1_007L, calls.clockGettime(CLOCK_MONOTONIC_RAW, tp, wide));
                    assertEquals(500_000_000L, secondField(tp, wide));
                    assertEquals(1_003L, calls.clockGettime(CLOCK_THREAD_CPUTIME_ID, tp, wide));
                    assertEquals(500_000_000L, secondField(tp, wide));
                } finally {
                    scratch.free();
                }
            }
        }
    }

    /** The second field of a {@code timespec} or {@code timeval}, whose offsets agree. */
    private static long secondField(UnidbgPointer value, boolean wide) {
        return wide ? value.getLong(8) : value.getInt(4);
    }

    private static final class RawSyscalls implements AutoCloseable {
        private final AndroidEmulator emulator;
        private final UnidbgPointer code;
        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(8192, 7);
            for (int number : new int[] {78, 113, 169, 263}) {
                long offset = number * 16L;
                code.setInt(offset, emulator.is64Bit() ? 0xd2800008 | (number << 5)
                        : 0xe3007000 | (number & 0xfff) | ((number & 0xf000) << 4));
                code.setInt(offset + 4, emulator.is64Bit() ? 0xd4000001 : 0xef000000);
                code.setInt(offset + 8, emulator.is64Bit() ? 0xd65f03c0 : 0xe12fff1e);
            }
        }
        /** The seconds field of {@code clock_gettime(clk_id, timespec)}. */
        long clockGettime(int clkId, UnidbgPointer timespec, boolean wide) {
            int number = wide ? 113 : 263;
            assertEquals(0, call(number, clkId, timespec.peer));
            return wide ? timespec.getLong(0) : timespec.getInt(0);
        }
        /** The seconds field of {@code gettimeofday(timeval, NULL)}. */
        long gettimeofday(UnidbgPointer timeval, boolean wide) {
            int number = wide ? 169 : 78;
            assertEquals(0, call(number, timeval.peer, 0));
            return wide ? timeval.getLong(0) : timeval.getInt(0);
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
