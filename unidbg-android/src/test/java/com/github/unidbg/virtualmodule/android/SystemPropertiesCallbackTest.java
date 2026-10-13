package com.github.unidbg.virtualmodule.android;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Actual ARM32/64 callback entry versus AOSP prop_info and ReadCallback contracts. */
public class SystemPropertiesCallbackTest {
    @Test
    public void callbackReceivesCookieNameValueAndSerialForShortAndLongProperties() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
                Module module = new SystemProperties(emulator, null).register(emulator.getMemory());
                UnidbgPointer area = emulator.getMemory().mmap(4096, 3);
                com.sun.jna.Pointer cookie = area.share(3500);
                try {
                    for (int length : new int[] {0, 1, 91, 92, 510, 511, 512, 513}) {
                        boolean longer = length >= 92;
                        String name = longer ? "ro.callback.test" : "callback.test";
                        byte[] nameBytes = (name + "\0").getBytes(StandardCharsets.UTF_8);
                        byte[] value = new byte[length + 1];
                        for (int i = 0; i < length; i++) value[i] = (byte) (0x80 + i % 64);
                        area.write(0, new byte[4096], 0, 4096);
                        int serial = longer ? 0x34010000 : length << 24;
                        area.setInt(0, serial);
                        area.write(96, nameBytes, 0, nameBytes.length);
                        if (longer) {
                            byte[] legacy = "Must use read callback\0".getBytes(StandardCharsets.UTF_8);
                            area.write(4, legacy, 0, legacy.length);
                            area.setInt(60, 256);
                            area.write(256, value, 0, value.length);
                        } else {
                            area.write(4, value, 0, value.length);
                        }
                        final long[] received = new long[4];
                        final byte[][] bytes = new byte[1][];
                        UnidbgPointer callback;
                        if (wide) {
                            callback = emulator.getSvcMemory().registerSvc(new Arm64Svc() {
                                @Override public long handle(com.github.unidbg.Emulator<?> current) {
                                    capture(current.getContext(), received, bytes, value.length);
                                    return 0;
                                }
                            });
                        } else {
                            callback = emulator.getSvcMemory().registerSvc(new ArmSvc() {
                                @Override public long handle(com.github.unidbg.Emulator<?> current) {
                                    capture(current.getContext(), received, bytes, value.length);
                                    return 0;
                                }
                            });
                        }
                        module.findSymbolByName("__system_property_read_callback").call(emulator, area, callback, cookie);
                        assertEquals(UnidbgPointer.nativeValue(cookie), received[0]);
                        assertEquals(UnidbgPointer.nativeValue(area.share(96)), received[1]);
                        assertEquals(UnidbgPointer.nativeValue(area.share(longer ? 256 : 4)), received[2]);
                        assertEquals(Integer.toUnsignedLong(serial), received[3] & 0xffffffffL);
                        assertArrayEquals(value, bytes[0]);
                    }
                } finally {
                    emulator.getMemory().munmap(UnidbgPointer.nativeValue(area), 4096);
                }
            }
        }
    }
    private static void capture(RegisterContext context, long[] args, byte[][] bytes, int length) {
        for (int i = 0; i < args.length; i++) args[i] = UnidbgPointer.nativeValue(context.getPointerArg(i));
        bytes[0] = context.getPointerArg(2).getByteArray(0, length);
    }
}
