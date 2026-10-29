package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.CodeHook;
import com.github.unidbg.arm.backend.UnHook;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class JniNonvirtualConstructionIntegrationTest {
    private static final RuntimeException ORIGINAL = new RuntimeException("constructor and method witness");

    public interface Defaults {
        default int defaultValue(int value) {
            ((Base) this).defaultCalls++;
            Witness.touch();
            return value;
        }
    }

    public static class Base {
        int state;
        int baseCalls;
        int derivedCalls;
        int defaultCalls;
        Object[] lastArguments;

        public Base(int state) {
            this.state = state;
            Witness.constructed.add(this);
            Witness.touch();
            if (state < 0) throw ORIGINAL;
        }

        public boolean z(boolean value) { baseCalls++; Witness.touch(); return value; }
        public byte b(byte value) { baseCalls++; Witness.touch(); return value; }
        public char c(char value) { baseCalls++; Witness.touch(); return value; }
        public short s(short value) { baseCalls++; Witness.touch(); return value; }
        public int i(int value) { baseCalls++; Witness.touch(); return value; }
        public long j(long value) { baseCalls++; Witness.touch(); return value; }
        public float f(float value) { baseCalls++; Witness.touch(); return value; }
        public double d(double value) { baseCalls++; Witness.touch(); return value; }
        public Object object(Object value) { baseCalls++; Witness.touch(); return value; }
        public int[] array(int[] value) { baseCalls++; Witness.touch(); return value; }
        private int hidden(int value) { baseCalls++; Witness.touch(); return value; }

        public void arguments(boolean z, byte b, char c, short s, int i, long j, float f, double d, Object object) {
            baseCalls++;
            Witness.touch();
            lastArguments = new Object[] {z, b, c, s, i, j, f, d, object};
        }

        public void fail() {
            Witness.touch();
            throw ORIGINAL;
        }
    }

    public static class Derived extends Base implements Defaults {
        public Derived(int state) { super(state); }
        @Override public boolean z(boolean value) { derivedCalls++; Witness.touch(); return value; }
        @Override public byte b(byte value) { derivedCalls++; Witness.touch(); return value; }
        @Override public char c(char value) { derivedCalls++; Witness.touch(); return value; }
        @Override public short s(short value) { derivedCalls++; Witness.touch(); return value; }
        @Override public int i(int value) { derivedCalls++; Witness.touch(); return value; }
        @Override public long j(long value) { derivedCalls++; Witness.touch(); return value; }
        @Override public float f(float value) { derivedCalls++; Witness.touch(); return value; }
        @Override public double d(double value) { derivedCalls++; Witness.touch(); return value; }
        @Override public Object object(Object value) { derivedCalls++; Witness.touch(); return value; }
        @Override public int[] array(int[] value) { derivedCalls++; Witness.touch(); return value; }
        @Override public void arguments(boolean z, byte b, char c, short s, int i, long j, float f, double d, Object object) {
            derivedCalls++;
            Witness.touch();
        }
        @Override public int defaultValue(int value) { derivedCalls++; Witness.touch(); return value; }

        Object[] javaSuperOracle(Object anchor, int[] array) {
            super.arguments(true, (byte) -101, (char) 65505, (short) -12345, -123456789,
                    0x1020304050607080L, 1.5f, -2.25d, anchor);
            Object[] values = {super.z(true), super.b((byte) -101), super.c((char) 65505),
                    super.s((short) -12345), super.i(-123456789), super.j(0x1020304050607080L),
                    super.f(1.5f), super.d(-2.25d), super.object(anchor), super.array(array),
                    Defaults.super.defaultValue(73), ((Base) this).hidden(77)};
            i(19);
            return values;
        }
    }

    public static class InitProbe {
        static { Witness.initializerThread = Thread.currentThread(); }
        public InitProbe(int unused) { }
    }

    public static class InitProbeWide {
        static { Witness.initializerThread = Thread.currentThread(); }
        public InitProbeWide(int unused) { }
    }

    public static final class Witness {
        static final List<Object> before = new ArrayList<>();
        static final List<Object> constructed = new ArrayList<>();
        static final List<Object> after = new ArrayList<>();
        static final List<Integer> initialStates = new ArrayList<>();
        static final List<Boolean> locked = new ArrayList<>();
        static final List<Object> exceptions = new ArrayList<>();
        static final List<Thread> threads = new ArrayList<>();
        static Thread initializerThread;

        static void reset() {
            before.clear(); constructed.clear(); after.clear(); initialStates.clear();
            locked.clear(); exceptions.clear(); threads.clear(); initializerThread = null;
        }

        static void touch() { threads.add(Thread.currentThread()); }

        public static void before(Object value) {
            touch();
            before.add(value);
            initialStates.add(((Base) value).state);
            locked.add(Thread.holdsLock(value));
        }

        public static void after(Object value) {
            touch();
            after.add(value);
            locked.add(Thread.holdsLock(value));
        }

        public static void exception(Object value) { touch(); exceptions.add(value); }
        public static void monitor(Object value) { touch(); locked.add(Thread.holdsLock(value)); }
    }

    @Test
    public void productionJniPreservesDispatchConstructionIdentityAndThrowable() throws Exception {
        String directory = System.getProperty("unidbg.jniInvocationDirectory");
        if (directory == null) throw new IllegalArgumentException("Required: -Dunidbg.jniInvocationDirectory=<fixture directory>");
        Thread backendOwner = Thread.currentThread();
        for (boolean wide : new boolean[] {false, true}) {
            Object anchor = new Object();
            int[] array = {17, -29};
            Derived oracle = new Derived(41);
            Object[] expected = oracle.javaSuperOracle(anchor, array);
            Object[] expectedArguments = oracle.lastArguments;
            Witness.reset();
            Set<Thread> backendThreads = new HashSet<>();
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                emulator.getSyscallHandler().setEnableThreadDispatcher(true);
                BaseVM vm = (BaseVM) emulator.createDalvikVM();
                vm.setDvmClassFactory(new ProxyClassFactory().configObjectVisitor((member, object, arguments) -> Witness.touch()));
                Module fixture = emulator.loadLibrary(new File(directory, wide ? "arm64.so" : "arm32.so"), false);
                emulator.getBackend().hook_add_new(new CodeHook() {
                    private UnHook handle;
                    @Override public void hook(Backend backend, long address, int size, Object user) {
                        backendThreads.add(Thread.currentThread());
                    }
                    @Override public void onAttach(UnHook hook) { handle = hook; }
                    @Override public void detach() { handle.unhook(); }
                }, fixture.base, fixture.base + fixture.size, null);
                try {
                    assertEquals("fixture arm" + (wide ? 64 : 32), 0,
                            fixture.callFunction(emulator, "invocation_run", vm.getJNIEnv(),
                                    handle(vm, Base.class), handle(vm, Derived.class), handle(vm, Defaults.class),
                                    handle(vm, Witness.class), handle(vm, wide ? InitProbeWide.class : InitProbe.class),
                                    handle(vm, anchor), handle(vm, array)).intValue());
                    Pointer results = fixture.findSymbolByName("invocation_results", false).createPointer(emulator);
                    for (int mode = 0; mode < 3; mode++) {
                        Pointer row = results.share(mode * 12L * 8);
                        Object[] actual = {row.getLong(0) == 1, (byte) row.getLong(8), (char) row.getLong(16),
                                (short) row.getLong(24), (int) row.getLong(32), row.getLong(40),
                                Float.intBitsToFloat((int) row.getLong(48)), Double.longBitsToDouble(row.getLong(56)),
                                ProxyDvmObject.unpack(vm.getObject((int) row.getLong(64))),
                                ProxyDvmObject.unpack(vm.getObject((int) row.getLong(72))),
                                (int) row.getLong(80), (int) row.getLong(88)};
                        assertArrayEquals(expected, actual);
                        assertSame(anchor, actual[8]);
                        assertSame(array, actual[9]);
                        Base object = (Base) Witness.after.get(mode);
                        assertArrayEquals(expectedArguments, object.lastArguments);
                        assertEquals(oracle.baseCalls, object.baseCalls);
                        assertEquals(oracle.derivedCalls, object.derivedCalls);
                        assertEquals(oracle.defaultCalls, object.defaultCalls);
                    }
                    assertEquals(Arrays.asList(0, 0, 0, 0, 0), Witness.initialStates);
                    assertEquals(5, Witness.constructed.size());
                    assertEquals(5, Witness.after.size());
                    int[] states = {41, 42, 43, 81, -7};
                    for (int i = 0; i < states.length; i++) {
                        assertSame(Witness.before.get(i), Witness.constructed.get(i));
                        assertSame(Witness.before.get(i), Witness.after.get(i));
                        assertEquals(states[i], ((Base) Witness.after.get(i)).state);
                    }
                    assertEquals(Arrays.asList(true, true, true, true, true, true, true, true, true, true, true), Witness.locked);
                    assertEquals(Arrays.asList(ORIGINAL, ORIGINAL), Witness.exceptions);
                    assertSame(ORIGINAL, Witness.exceptions.get(0));
                    assertSame(ORIGINAL, Witness.exceptions.get(1));
                    Thread worker = Witness.threads.get(0);
                    assertSame(worker, Witness.initializerThread);
                    for (Thread observed : Witness.threads) assertSame(worker, observed);
                    assertEquals(new HashSet<>(Arrays.asList(backendOwner)), backendThreads);
                    assertEquals(2, new HashSet<>(Arrays.asList(backendOwner, worker)).size());
                } finally {
                    assertEquals(0, fixture.callFunction(emulator, "invocation_cleanup", vm.getJNIEnv()).intValue());
                }
            }
        }
    }

    private static int handle(BaseVM vm, Object host) {
        return vm.addLocalObject(ProxyDvmObject.createObject(vm, host));
    }
}
