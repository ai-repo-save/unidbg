package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.array.PrimitiveArray;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.memory.MemoryMap;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/** JNI Elements/Release: each acquisition stays valid until its corresponding release. */
public class JniPrimitiveLeaseIntegrationTest {
    @Test
    public void allPrimitiveLeasesPreserveBackingIdentityAndFreeTheirMappings() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                Object[] originals = {new boolean[] {false, true, true, true}, new byte[] {11, 21, 22, 14},
                        new char[] {11, 21, 22, 14}, new short[] {11, 21, 22, 14}, new int[] {11, 21, 22, 14},
                        new long[] {11, 21, 22, 14}, new float[] {11, 21, 22, 14}, new double[] {11, 21, 22, 14}};
                MemoryBlock guard = emulator.getMemory().malloc(9, true);
                try {
                    for (int type = 0; type < originals.length; type++) {
                        DvmObject<?> wrapper = ProxyDvmObject.createObject(vm, originals[type]);
                        PrimitiveArray<?> array = (PrimitiveArray<?>) wrapper;
                        long[] before = mappings(emulator);
                        boolean bool = type == 0;
                        double[] initial = bool ? new double[] {0, 1, 1, 1} : new double[] {11, 21, 22, 14};
                        try (Lease first = acquire(emulator, array, guard.getPointer());
                             Lease second = acquire(emulator, array, guard.getPointer())) {
                            assertBuffer(type, first.pointer, initial);
                            assertBuffer(type, second.pointer, initial);
                            write(type, first.pointer, 0, bool ? 1 : 31);
                            first.release(0);
                            write(type, second.pointer, 1, bool ? 0 : 41);
                            boolean secondCopy = second.copy;
                            second.release(VM.JNI_ABORT);
                            double changed = secondCopy ? 21 : 41;
                            double boolChanged = secondCopy ? 1 : 0;
                            assertBacking(originals[type], bool ? new double[] {1, boolChanged, 1, 1}
                                    : new double[] {31, changed, 22, 14});
                            try (Lease third = acquire(emulator, array, guard.getPointer())) {
                                write(type, third.pointer, 2, bool ? 0 : 51);
                                third.release(VM.JNI_COMMIT);
                                assertBacking(originals[type], bool ? new double[] {1, boolChanged, 0, 1}
                                        : new double[] {31, changed, 51, 14});
                                write(type, third.pointer, 3, bool ? 0 : 61);
                                third.release(0);
                            }
                            assertBacking(originals[type], bool ? new double[] {1, boolChanged, 0, 0}
                                    : new double[] {31, changed, 51, 61});
                            assertSame(originals[type], wrapper.getValue());
                        }
                        assertArrayEquals(before, mappings(emulator));
                    }
                } finally {
                    guard.free();
                }
            }
        }
    }

    private static final class Lease implements AutoCloseable {
        final PrimitiveArray<?> array;
        final boolean copy;
        Pointer pointer;

        Lease(PrimitiveArray<?> array, Pointer pointer, boolean copy) {
            this.array = array;
            this.pointer = pointer;
            this.copy = copy;
        }

        void release(int mode) {
            Pointer buffer = pointer;
            if (mode != VM.JNI_COMMIT) pointer = null;
            array._ReleaseArrayCritical(buffer, mode);
        }

        @Override
        public void close() {
            if (pointer != null) release(VM.JNI_ABORT);
        }
    }

    private static Lease acquire(AndroidEmulator emulator, PrimitiveArray<?> array, Pointer guard) {
        guard.setMemory(0, 9, (byte) 0xa5);
        guard.setByte(4, (byte) 0x7e);
        Pointer pointer = array._GetArrayCritical(emulator, guard.share(4));
        int copy = guard.getByte(4) & 0xff;
        Lease lease = new Lease(array, pointer, copy == 1);
        try {
            assertEquals(true, copy == 0 || copy == 1);
            assertArrayEquals(new byte[] {(byte) 0xa5, (byte) 0xa5, (byte) 0xa5, (byte) 0xa5,
                            (byte) copy, (byte) 0xa5, (byte) 0xa5, (byte) 0xa5, (byte) 0xa5}, guard.getByteArray(0, 9));
            return lease;
        } catch (RuntimeException | Error failure) {
            try { lease.close(); } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void assertBacking(Object array, double[] expected) {
        double[] actual = new double[Array.getLength(array)];
        for (int index = 0; index < actual.length; index++) {
            Object item = Array.get(array, index);
            actual[index] = item instanceof Boolean ? ((Boolean) item ? 1 : 0)
                    : item instanceof Character ? (Character) item : ((Number) item).doubleValue();
        }
        assertArrayEquals(expected, actual, 0);
    }

    private static void assertBuffer(int type, Pointer pointer, double[] expected) {
        double[] actual = new double[expected.length];
        for (int index = 0; index < actual.length; index++) {
            switch (type) {
                case 0: case 1: actual[index] = pointer.getByte(index); break;
                case 2: actual[index] = pointer.getShort(index * 2L) & 0xffff; break;
                case 3: actual[index] = pointer.getShort(index * 2L); break;
                case 4: actual[index] = pointer.getInt(index * 4L); break;
                case 5: actual[index] = pointer.getLong(index * 8L); break;
                case 6: actual[index] = pointer.getFloat(index * 4L); break;
                case 7: actual[index] = pointer.getDouble(index * 8L); break;
                default: throw new AssertionError(type);
            }
        }
        assertArrayEquals(expected, actual, 0);
    }

    private static void write(int type, Pointer pointer, int index, int value) {
        switch (type) {
            case 0: case 1: pointer.setByte(index, (byte) value); break;
            case 2: case 3: pointer.setShort(index * 2L, (short) value); break;
            case 4: pointer.setInt(index * 4L, value); break;
            case 5: pointer.setLong(index * 8L, value); break;
            case 6: pointer.setFloat(index * 4L, value); break;
            case 7: pointer.setDouble(index * 8L, value); break;
            default: throw new AssertionError(type);
        }
    }

    private static long[] mappings(AndroidEmulator emulator) {
        List<MemoryMap> maps = new ArrayList<>(emulator.getMemory().getMemoryMap());
        maps.sort(Comparator.comparingLong(map -> map.base));
        long[] snapshot = new long[maps.size() * 3];
        for (int index = 0; index < maps.size(); index++) {
            snapshot[index * 3] = maps.get(index).base;
            snapshot[index * 3 + 1] = maps.get(index).size;
            snapshot[index * 3 + 2] = maps.get(index).prot;
        }
        return snapshot;
    }
}
