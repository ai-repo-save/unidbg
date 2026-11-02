package com.github.unidbg.linux.android;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.virtualmodule.android.SystemProperties;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Explicit guest dlsym integration, without a target library or device. */
public class HookSvcLifecycleIntegrationTest {

    @Test
    public void entriesRemainCallableForTheirDefinitionsOnBothAbis() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            try (Fixture first = new Fixture(wide, "first"); Fixture second = new Fixture(wide, "second")) {
                first.exercise();
                second.exercise();
                first.exercise();
            }
        }
    }

    private static final class Provider implements SystemPropertyProvider {
        String value;
        Pointer replacement;

        @Override
        public String getProperty(String key) {
            return value;
        }

        @Override
        public Pointer __system_property_find(String key) {
            return replacement;
        }
    }

    private static final class Fixture implements Closeable {
        final AndroidEmulator emulator;
        final boolean wide;
        final String label;
        final Map<String, UnidbgPointer> originals = new LinkedHashMap<>();
        final Map<String, UnidbgPointer>[] targets;
        final UnidbgPointer[] records;
        final UnidbgPointer output;
        final UnidbgPointer marker;
        final UnidbgPointer callback;
        final SystemPropertyHook primary;
        final SystemPropertyHook secondary;
        final Provider provider = new Provider();
        final Provider otherProvider = new Provider();
        final long dlsym;
        SystemPropertyHook active;

        @SuppressWarnings("unchecked")
        Fixture(boolean wide, String label) {
            this.wide = wide;
            this.label = label;
            emulator = (wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                    .addBackendFactory(new Unicorn2Factory(true)).build();
            output = emulator.getSvcMemory().allocate(128, "result");
            marker = emulator.getSvcMemory().allocate(4, "original-result");
            Map<String, UnidbgPointer> libdl = new LinkedHashMap<>();
            for (String name : new String[] {"dlsym", "dladdr", "dlopen", "dlclose", "dlerror",
                    "dl_iterate_phdr", "dl_unwind_find_exidx"}) {
                libdl.put(name, service(target -> 0));
            }
            emulator.getMemory().loadVirtualModule("libdl.so", libdl);
            primary = new SystemPropertyHook(emulator);
            secondary = new SystemPropertyHook(emulator);
            primary.setPropertyProvider(provider);
            secondary.setPropertyProvider(otherProvider);
            active = primary;
            emulator.getMemory().addHookListener((memory, library, symbol, old) ->
                    active.hook(memory, library, symbol, old));
            targets = new Map[2];
            records = new UnidbgPointer[2];
            for (int index = 0; index < targets.length; index++) {
                final int id = index;
                final byte[] bytes = nativeValue(index).getBytes(StandardCharsets.UTF_8);
                records[index] = record(nativeValue(index));
                Map<String, UnidbgPointer> symbols = new LinkedHashMap<>();
                symbols.put("__system_property_get", service(target -> {
                    target.getContext().getPointerArg(1).write(0, Arrays.copyOf(bytes, bytes.length + 1), 0, bytes.length + 1);
                    return bytes.length;
                }));
                symbols.put("__system_property_read", service(target -> {
                    target.getContext().getPointerArg(2).write(0, Arrays.copyOf(bytes, bytes.length + 1), 0, bytes.length + 1);
                    return bytes.length;
                }));
                symbols.put("__system_property_find", service(target -> {
                    marker.setInt(0, id + 1);
                    return records[id].peer;
                }));
                targets[index] = symbols;
            }
            originals.putAll(targets[0]);
            emulator.getMemory().loadVirtualModule("libc.so", originals);
            new SystemProperties(emulator, null).register(emulator.getMemory());
            callback = service(target -> {
                RegisterContext context = target.getContext();
                Pointer cookie = context.getPointerArg(0);
                byte[] name = context.getPointerArg(1).getString(0).getBytes(StandardCharsets.UTF_8);
                byte[] value = context.getPointerArg(2).getString(0).getBytes(StandardCharsets.UTF_8);
                cookie.write(0, Arrays.copyOf(name, name.length + 1), 0, name.length + 1);
                cookie.write(name.length + 1, Arrays.copyOf(value, value.length + 1), 0, value.length + 1);
                cookie.setInt(name.length + value.length + 2, context.getIntArg(3));
                return 0;
            });
            dlsym = emulator.getMemory().dlsym(0, "dlsym").getAddress();
        }

        void exercise() {
            active = primary;
            for (String name : new String[] {"dlsym", "dladdr", "dlopen", "dlclose", "dlerror",
                    "dl_iterate_phdr", "dl_unwind_find_exidx"}) {
                long entry = stable(name);
                verifyDladdr(entry, "libdl.so", name);
                if ("dlsym".equals(name)) {
                    assertEquals(dlsym, entry);
                } else if ("dladdr".equals(name)) {
                    assertEquals(0, call(entry, 0, output).intValue());
                }
            }
            for (int index = 0; index < targets.length; index++) {
                originals.putAll(targets[index]);
                long get = stable("__system_property_get");
                long read = stable("__system_property_read");
                long find = stable("__system_property_find");
                verifyDladdr(get, "libc.so", "__system_property_get");
                verifyDladdr(read, "libc.so", "__system_property_read");
                verifyDladdr(find, "libc.so", "__system_property_find");
                provider.value = label + "-provider";
                verifyValue(get, read, index, provider.value);
                provider.value = label + "-updated";
                verifyValue(get, read, index, provider.value);
                otherProvider.value = label + "-other-instance";
                primary.setPropertyProvider(otherProvider);
                verifyValue(get, read, index, otherProvider.value);
                primary.setPropertyProvider(provider);
                provider.value = null;
                verifyValue(get, read, index, nativeValue(index));
                provider.replacement = record(label + "-replacement");
                verifyFind(find, index, wide ? provider.replacement : records[index],
                        wide ? label + "-replacement" : nativeValue(index));
                provider.replacement = null;
                verifyFind(find, index, records[index], nativeValue(index));
                assertEquals(get, stable("__system_property_get"));
                assertEquals(read, stable("__system_property_read"));
                assertEquals(find, stable("__system_property_find"));
                active = secondary;
                verifyValue(stable("__system_property_get"), stable("__system_property_read"), index, otherProvider.value);
                active = primary;
                assertEquals(get, stable("__system_property_get"));
                verifyValue(get, read, index, nativeValue(index));
            }
        }

        private void verifyDladdr(long address, String library, String symbol) {
            int pointerSize = wide ? 8 : 4;
            long entry = stable("dladdr");
            assertEquals(1, call(entry, UnidbgPointer.pointer(emulator, address), output).intValue());
            assertEquals(library, output.getPointer(0).getString(0));
            assertEquals(emulator.getMemory().findModule(library).base,
                    UnidbgPointer.nativeValue(output.getPointer(pointerSize)));
            assertEquals(symbol, output.getPointer(2L * pointerSize).getString(0));
            assertEquals(address, UnidbgPointer.nativeValue(output.getPointer(3L * pointerSize)));
        }

        private void verifyValue(long get, long read, int index, String expected) {
            verifyBuffer(get, new Object[] {"fixture.key", output}, expected);
            verifyBuffer(read, new Object[] {records[index], 0, output}, expected);
        }

        private void verifyBuffer(long entry, Object[] arguments, String expected) {
            byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
            output.setMemory(0, 128, (byte) 0xa5);
            assertEquals(bytes.length, call(entry, arguments).intValue());
            byte[] buffer = new byte[128];
            Arrays.fill(buffer, (byte) 0xa5);
            System.arraycopy(bytes, 0, buffer, 0, bytes.length);
            buffer[bytes.length] = 0;
            assertArrayEquals(buffer, output.getByteArray(0, buffer.length));
        }

        private void verifyFind(long entry, int index, Pointer expected, String expectedValue) {
            marker.setInt(0, 0);
            long result = address(call(entry, "fixture.key"));
            assertEquals(UnidbgPointer.nativeValue(expected), result);
            assertEquals(index + 1, marker.getInt(0));
            byte[] expectedBytes = expectedValue.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(Arrays.copyOf(expectedBytes, expectedBytes.length + 1),
                    UnidbgPointer.pointer(emulator, result).getByteArray(4, expectedBytes.length + 1));
            output.setMemory(0, 128, (byte) 0xa5);
            call(stable("__system_property_read_callback"), UnidbgPointer.pointer(emulator, result), callback, output);
            byte[] name = "fixture.key".getBytes(StandardCharsets.UTF_8);
            byte[] buffer = new byte[128];
            Arrays.fill(buffer, (byte) 0xa5);
            System.arraycopy(name, 0, buffer, 0, name.length);
            buffer[name.length] = 0;
            System.arraycopy(expectedBytes, 0, buffer, name.length + 1, expectedBytes.length);
            buffer[name.length + expectedBytes.length + 1] = 0;
            int serialOffset = name.length + expectedBytes.length + 2;
            Arrays.fill(buffer, serialOffset, serialOffset + 4, (byte) 0);
            buffer[serialOffset + 3] = (byte) expectedBytes.length;
            assertArrayEquals(buffer, output.getByteArray(0, buffer.length));
        }

        private long stable(String symbol) {
            long entry = address(call(dlsym, 0, symbol));
            assertEquals(entry, address(call(dlsym, 0, symbol)));
            assertEquals(entry, address(call(dlsym, 0, symbol)));
            return entry;
        }

        private Number call(long entry, Object... arguments) {
            return Module.emulateFunction(emulator, entry, arguments);
        }

        private long address(Number number) {
            return wide ? number.longValue() : Integer.toUnsignedLong(number.intValue());
        }

        private String nativeValue(int index) {
            return label + "-native-" + index;
        }

        private UnidbgPointer record(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            UnidbgPointer record = emulator.getSvcMemory().allocate(128, "prop_info");
            record.setMemory(0, 128, (byte) 0);
            record.setInt(0, bytes.length << 24);
            record.setString(4, value);
            record.setString(4 + SystemPropertyHook.PROP_VALUE_MAX, "fixture.key");
            return record;
        }

        private UnidbgPointer service(ToLongFunction<Emulator<?>> handler) {
            return emulator.getSvcMemory().registerSvc(wide ? new Arm64Svc() {
                @Override
                public long handle(Emulator<?> target) {
                    return handler.applyAsLong(target);
                }
            } : new ArmSvc() {
                @Override
                public long handle(Emulator<?> target) {
                    return handler.applyAsLong(target);
                }
            });
        }

        @Override
        public void close() throws IOException {
            emulator.close();
        }
    }
}
