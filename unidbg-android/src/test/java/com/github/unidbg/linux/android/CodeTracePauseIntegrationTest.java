package com.github.unidbg.linux.android;

import capstone.api.Instruction;
import com.alibaba.fastjson.JSON;
import com.github.unidbg.TraceHook;
import com.github.unidbg.arm.InstructionVisitor;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.CodeHook;
import com.github.unidbg.arm.backend.UnHook;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import org.apache.commons.codec.binary.Hex;
import org.junit.Test;
import unicorn.Arm64Const;
import unicorn.UnicornConst;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** Explicit integration selector: -Dtest=CodeTracePauseIntegrationTest -Dmaven.test.skip=false. */
public class CodeTracePauseIntegrationTest {
    private static final long BASE = 0x100000, DATA = 0x200000, OPEN = BASE + 4, END = BASE + 28, STOP = BASE + 256;
    // mov x2,#0; add #1; add #2; add #4; b +4; add #8; str [x1]; add #16; str [x1,#8]; ret.
    private static final String CODE = "020080d24204009142080091421000910100001442200091220000f942400091220400f9c0035fd6";
    private static final List<Long> WINDOW = Arrays.asList(4L, 8L, 12L, 16L, 20L, 24L);

    private static final class CountingEmulator extends AndroidARM64Emulator {
        long disassemblies;
        CountingEmulator() { super("code-trace-pause", null, Collections.singletonList(new Unicorn2Factory(false))); }
        @Override public Instruction[] printAssemble(PrintStream out, long address, int size,
                int maxLengthLibraryName, InstructionVisitor visitor) {
            disassemblies++;
            return super.printAssemble(out, address, size, maxLengthLibraryName, visitor);
        }
    }

    private abstract static class Hook implements CodeHook {
        private UnHook hook;
        @Override public void onAttach(UnHook hook) { this.hook = hook; }
        @Override public void detach() { hook.unhook(); }
    }

    private static PrintStream discard() {
        return new PrintStream(new OutputStream() { @Override public void write(int value) { } });
    }

    private static void execute(Backend backend) {
        backend.reg_write(Arm64Const.UC_ARM64_REG_X1, DATA);
        backend.reg_write(Arm64Const.UC_ARM64_REG_X30, STOP);
        backend.emu_start(BASE, STOP, 0, 0);
    }

    private static void assertEffects(Backend backend) {
        ByteBuffer data = ByteBuffer.wrap(backend.mem_read(DATA, 16)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(15L, data.getLong(0));
        assertEquals(31L, data.getLong(8));
        assertEquals(31L, backend.reg_read(Arm64Const.UC_ARM64_REG_X2).longValue());
        assertEquals(STOP, backend.reg_read(Arm64Const.UC_ARM64_REG_PC).longValue());
    }

    @Test public void pausedPreparedTracePreservesRecordsAndGuestEffects() throws Exception {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("machineCodeHex", CODE);
        try (CountingEmulator emulator = new CountingEmulator()) {
            Backend backend = emulator.getBackend();
            byte[] code = Hex.decodeHex(CODE.toCharArray());
            backend.mem_map(BASE, 4096, UnicornConst.UC_PROT_ALL);
            backend.mem_map(DATA, 4096, UnicornConst.UC_PROT_ALL);
            backend.mem_write(BASE, code);
            execute(backend);
            assertEffects(backend);
            boolean[] armed = {false};
            int[] budget = {0};
            List<Long> observed = new ArrayList<>();
            TraceHook[] trace = {null};
            backend.hook_add_new(new Hook() {
                @Override public void hook(Backend ignored, long address, int size, Object user) {
                    if (armed[0]) trace[0].resume();
                }
            }, OPEN, OPEN, null);
            backend.hook_add_new(new Hook() {
                @Override public void hook(Backend ignored, long address, int size, Object user) {
                    if (armed[0]) trace[0].pause();
                }
            }, END, END, null);
            trace[0] = emulator.traceCode(BASE, BASE + code.length - 1, (emu, address, instruction) -> {
                observed.add(address - BASE);
                if (observed.size() == budget[0]) trace[0].pause();
            });
            trace[0].setRedirect(discard());
            trace[0].pause();
            // The machine code is already warm; registration changes instrumentation, not guest code bytes.
            backend.removeJitCodeCache(BASE, BASE + code.length);
            long before = emulator.disassemblies;
            execute(backend);
            assertEquals(Collections.emptyList(), observed);
            assertEffects(backend);
            evidence.put("preparedPausedDisassemblies", emulator.disassemblies - before);
            List<List<Long>> windows = new ArrayList<>();
            armed[0] = true;
            try {
                for (int limit : new int[] {100000, 2, 100000, 100000}) {
                    budget[0] = limit;
                    observed.clear();
                    execute(backend);
                    assertEffects(backend);
                    List<Long> expected = limit == 2 ? WINDOW.subList(0, 2) : WINDOW;
                    assertEquals(expected, observed);
                    windows.add(new ArrayList<>(observed));
                }
            } finally {
                armed[0] = false;
                trace[0].stopTrace();
            }
            evidence.put("windowRecords", windows);
            int iterations = Integer.parseInt(System.getProperty("code.trace.pause.iterations", "1000"));
            List<Map<String, Object>> measurements = new ArrayList<>();
            int[] callbacks = {0};
            TraceHook performance = null;
            try {
                for (String mode : Arrays.asList("no-trace", "paused", "active")) {
                    if (mode.equals("paused")) {
                        performance = emulator.traceCode(BASE, BASE + code.length - 1,
                                (emu, address, instruction) -> callbacks[0]++);
                        performance.setRedirect(discard());
                        performance.pause();
                        backend.removeJitCodeCache(BASE, BASE + code.length);
                    } else if (mode.equals("active")) {
                        performance.resume();
                    }
                    callbacks[0] = 0;
                    long disassemblies = emulator.disassemblies;
                    long start = System.nanoTime();
                    for (int i = 0; i < iterations; i++) execute(backend);
                    long elapsed = System.nanoTime() - start;
                    assertEffects(backend);
                    Map<String, Object> measurement = new LinkedHashMap<>();
                    measurement.put("mode", mode);
                    measurement.put("iterations", iterations);
                    measurement.put("guestInstructions", 10L * iterations);
                    measurement.put("elapsedNanos", elapsed);
                    measurement.put("traceRecords", callbacks[0]);
                    measurement.put("disassemblies", emulator.disassemblies - disassemblies);
                    measurements.add(measurement);
                }
            } finally { if (performance != null) performance.stopTrace(); }
            evidence.put("performanceDiagnostics", measurements);
            evidence.put("memoryReadBack", Arrays.asList(15L, 31L));
        }
        String json = JSON.toJSONString(evidence);
        System.out.println(json);
        String destination = System.getProperty("code.trace.pause.diagnostics");
        if (destination != null) {
            Path file = Paths.get(destination).toAbsolutePath();
            Files.createDirectories(file.getParent());
            Files.write(file, (json + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
