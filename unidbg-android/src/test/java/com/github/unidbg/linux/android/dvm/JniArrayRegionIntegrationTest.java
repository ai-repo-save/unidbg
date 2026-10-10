package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/** Integration correctness and regression tests through the emulated JNI function table.
 * JNI table indices, widths and exception contract:
 * https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#getprimitivetypearrayregion-routines
 * Bounds (including empty end regions):
 * https://android.googlesource.com/platform/art/+/android-8.0.0_r36/runtime/jni_internal.cc
 */
public class JniArrayRegionIntegrationTest {
    @Test
    public void primitiveRegionsOnBothArchitectures() throws Exception {
        Object[] values = {
                new boolean[] {false, true, false, true},
                new byte[] {0, (byte) 0x80, 0x7f, 0},
                new char[] {0, 0x8001, 0xffff, 0},
                new short[] {0, (short) 0x8001, 0x7fff, 0},
                new int[] {0, 0x80000001, 0x7fffffff, 0},
                new long[] {0, 0x8000000000000001L, 0x7fffffffffffffffL, 0},
                new float[] {0, Float.intBitsToFloat(0x80000000), Float.intBitsToFloat(0x7fc12345), 0},
                new double[] {0, Double.longBitsToDouble(0x8000000000000000L),
                        Double.longBitsToDouble(0x7ff8123456789abcL), 0}
        };
        String[] descriptors = {"[Z", "[B", "[C", "[S", "[I", "[J", "[F", "[D"};
        // Explicit little-endian JNI element representations, independent of implementation.
        byte[][] regions = {
                {1, 0}, {(byte) 0x80, 0x7f}, {1, (byte) 0x80, -1, -1}, {1, (byte) 0x80, -1, 0x7f},
                {1, 0, 0, (byte) 0x80, -1, -1, -1, 0x7f},
                {1, 0, 0, 0, 0, 0, 0, (byte) 0x80, -1, -1, -1, -1, -1, -1, -1, 0x7f},
                {0, 0, 0, (byte) 0x80, 0x45, 0x23, (byte) 0xc1, 0x7f},
                {0, 0, 0, 0, 0, 0, 0, (byte) 0x80, (byte) 0xbc, (byte) 0x9a, 0x78, 0x56,
                        0x34, 0x12, (byte) 0xf8, 0x7f}
        };
        for (boolean is64Bit : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = is64Bit
                    ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                Pointer env = vm.getJNIEnv();
                Pointer table = env.getPointer(0);
                int pointerSize = is64Bit ? 8 : 4;
                MemoryBlock block = emulator.getMemory().malloc(64, true);
                try {
                    UnidbgPointer buffer = block.getPointer();
                    byte[] guard = new byte[64];
                    Arrays.fill(guard, (byte) 0x5a);
                    for (int type = 0; type < values.length; type++) {
                        int handle = vm.addLocalObject(vm.resolveClass(descriptors[type]).newObject(values[type]));
                        Pointer entry = table.getPointer((199L + type) * pointerSize);
                        buffer.write(0, guard, 0, guard.length);
                        invoke(emulator, entry, env, handle, 1, 2, buffer.share(8));
                        byte[] expected = guard.clone();
                        System.arraycopy(regions[type], 0, expected, 8, regions[type].length);
                        assertArrayEquals("type=" + type + ", is64=" + is64Bit, expected, buffer.getByteArray(0, 64));
                        assertEquals(0, exception(emulator, table, env, pointerSize));

                        // The public pointer typed-write length is a count, not an end index.
                        if (type >= 3) {
                            buffer.write(0, guard, 0, guard.length);
                            switch (type) {
                                case 3: buffer.write(8, (short[]) values[type], 1, 2); break;
                                case 4: buffer.write(8, (int[]) values[type], 1, 2); break;
                                case 5: buffer.write(8, (long[]) values[type], 1, 2); break;
                                case 6: buffer.write(8, (float[]) values[type], 1, 2); break;
                                case 7: buffer.write(8, (double[]) values[type], 1, 2); break;
                            }
                            assertArrayEquals(expected, buffer.getByteArray(0, 64));
                        }

                        // Full extent reads must not overrun the destination.
                        buffer.write(0, guard, 0, guard.length);
                        invoke(emulator, entry, env, handle, 0, 4, buffer.share(8));
                        int width = regions[type].length / 2;
                        byte[] full = new byte[width * 4];
                        System.arraycopy(regions[type], 0, full, width, regions[type].length);
                        if (type == 0) full[3] = 1;
                        expected = guard.clone();
                        System.arraycopy(full, 0, expected, 8, full.length);
                        assertArrayEquals(expected, buffer.getByteArray(0, 64));
                        assertEquals(0, exception(emulator, table, env, pointerSize));

                        buffer.write(0, guard, 0, guard.length);
                        invoke(emulator, entry, env, handle, 4, 0, buffer.share(8));
                        assertArrayEquals(guard, buffer.getByteArray(0, 64));
                        assertEquals(0, exception(emulator, table, env, pointerSize));
                        int[][] invalid = {{-1, 1}, {0, -1}, {3, 2}, {5, 0}, {1, Integer.MAX_VALUE}};
                        for (int[] bounds : invalid) {
                            invoke(emulator, entry, env, handle, bounds[0], bounds[1], buffer.share(8));
                            assertArrayEquals(guard, buffer.getByteArray(0, 64));
                            int pending = exception(emulator, table, env, pointerSize);
                            assertNotEquals(0, pending);
                            assertEquals("java/lang/ArrayIndexOutOfBoundsException",
                                    vm.getObject(pending).getObjectType().getClassName());
                            invoke(emulator, table.getPointer(17L * pointerSize), env);
                            assertEquals(0, exception(emulator, table, env, pointerSize));
                        }
                    }
                } finally {
                    block.free();
                }
            }
        }
    }

    private static int exception(AndroidEmulator emulator, Pointer table, Pointer env, int pointerSize) {
        return invoke(emulator, table.getPointer(15L * pointerSize), env).intValue();
    }

    private static Number invoke(AndroidEmulator emulator, Pointer entry, Object... args) {
        return Module.emulateFunction(emulator, UnidbgPointer.nativeValue(entry), args);
    }
}
