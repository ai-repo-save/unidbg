package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassLoader;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmClass;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObjectVisitor;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.junit.Test;
import unicorn.Arm64Const;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class JniPrimitiveIntegrationTest {
    private static final IOException TARGET_FAILURE = new IOException("Java target failure");
    private static final UnsupportedOperationException HOST_FAILURE = new UnsupportedOperationException("JNI implementation failure");
    private static final AssertionError HOST_ERROR = new AssertionError("JNI implementation error");

    public static final class Values {
        public boolean z = true;
        public byte b = -101;
        public char c = '\ufedc';
        public short s = -30201;
        public int i = -19088743;
        public long j = -0x123456789abcdefL;
        public float f = -2.5f;
        public double d = -Math.PI;
        public static boolean sz = false;
        public static byte sb = -117;
        public static char sc = '\uff80';
        public static short ss = -31234;
        public static int si = -123456789;
        public static long sj = 0x1234567887654321L;
        public static float sf = 19.75f;
        public static double sd = Math.sqrt(2);
        private Object last;
        private static Object staticLast;

        public Object[] values() { return new Object[] {z, b, c, s, i, j, f, d}; }
        public static Object[] staticValues() { return new Object[] {sz, sb, sc, ss, si, sj, sf, sd}; }
        public Object last() { return last; }
        public static Object staticLast() { return staticLast; }
        public boolean echo(boolean v) { last = v; return v; }
        public byte echo(byte v) { last = v; return v; }
        public char echo(char v) { last = v; return v; }
        public short echo(short v) { last = v; return v; }
        public int echo(int v) { last = v; return v; }
        public long echo(long v) { last = v; return v; }
        public float echo(float v) { last = v; return v; }
        public double echo(double v) { last = v; return v; }
        public static boolean stat(boolean v) { staticLast = v; return v; }
        public static byte stat(byte v) { staticLast = v; return v; }
        public static char stat(char v) { staticLast = v; return v; }
        public static short stat(short v) { staticLast = v; return v; }
        public static int stat(int v) { staticLast = v; return v; }
        public static long stat(long v) { staticLast = v; return v; }
        public static float stat(float v) { staticLast = v; return v; }
        public static double stat(double v) { staticLast = v; return v; }
        public double fail() throws IOException { last = "instance-target"; throw TARGET_FAILURE; }
        public static double staticFail() throws IOException { staticLast = "static-target"; throw TARGET_FAILURE; }
        public int hostFailure() { return 0; }
        public int hostError() { return 0; }
    }

    private static final class FailureFactory extends ProxyClassFactory {
        @Override
        public DvmClass createClass(BaseVM vm, String name, DvmClass parent, DvmClass[] interfaces) {
            return new ProxyDvmClass(vm, name, parent, interfaces, classLoader, visitor, null) {
                @Override
                protected Jni createJni(ProxyClassLoader loader, ProxyDvmObjectVisitor visits, Jni fallback) {
                    Jni delegate = super.createJni(loader, visits, fallback);
                    return (Jni) Proxy.newProxyInstance(Jni.class.getClassLoader(), new Class<?>[] {Jni.class}, (proxy, method, args) -> {
                        if (method.getName().equals("callIntMethodV")) {
                            String target = ((DvmMethod) args[2]).getMethodName();
                            if (target.equals("hostFailure")) throw HOST_FAILURE;
                            if (target.equals("hostError")) throw HOST_ERROR;
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
                }
            };
        }
    }

    // JNI table indices and jvalue layout: https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html
    @Test
    public void primitiveFieldsMethodsAndFailureRecoveryOnBothArchitectures() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                vm.setDvmClassFactory(new FailureFactory());
                Values values = new Values();
                DvmObject<?> object = ProxyDvmObject.createObject(vm, values);
                int receiver = vm.addLocalObject(object), clazz = vm.addLocalObject(object.getObjectType());
                Pointer env = vm.getJNIEnv(), table = env.getPointer(0);
                int width = wide ? 8 : 4;
                MemoryBlock block = emulator.getMemory().malloc(256, true);
                try {
                    Pointer data = block.getPointer();
                    String types = "ZBCSIJFD";
                    String[] names = {"z", "b", "c", "s", "i", "j", "f", "d"};
                    for (boolean stat : new boolean[] {false, true}) {
                        Object[] expected = stat ? Values.staticValues() : values.values();
                        for (int index = 0; index < types.length(); index++) {
                            char type = types.charAt(index);
                            int field = member(emulator, table, width, env, clazz, data, stat ? 144 : 94,
                                    (stat ? "s" : "") + names[index], String.valueOf(type));
                            Number raw = invoke(emulator, table, width, (stat ? 146 : 96) + index,
                                    env, stat ? clazz : receiver, field);
                            assertEquals(expected[index], value(emulator, type, raw));
                            int method = member(emulator, table, width, env, clazz, data, stat ? 113 : 33,
                                    stat ? "stat" : "echo", "(" + type + ")" + type);
                            argument(data.share(128), type, expected[index]);
                            raw = invoke(emulator, table, width, (stat ? 119 : 39) + index * 3,
                                    env, stat ? clazz : receiver, method, data.share(128));
                            assertEquals(expected[index], value(emulator, type, raw));
                            assertEquals(expected[index], stat ? Values.staticLast() : values.last());
                        }
                        int fail = member(emulator, table, width, env, clazz, data, stat ? 113 : 33,
                                stat ? "staticFail" : "fail", "()D");
                        invoke(emulator, table, width, stat ? 140 : 60, env, stat ? clazz : receiver, fail, data.share(128));
                        int pending = invoke(emulator, table, width, 15, env).intValue();
                        assertSame(TARGET_FAILURE, vm.getObject(pending).getValue());
                        assertEquals(stat ? "static-target" : "instance-target", stat ? Values.staticLast() : values.last());
                        invoke(emulator, table, width, 17, env);
                        assertEquals(0, invoke(emulator, table, width, 15, env).intValue());
                    }
                    int math = vm.addLocalObject(vm.resolveClass("java/lang/Math"));
                    int abs = member(emulator, table, width, env, math, data, 113, "abs", "(F)F");
                    argument(data.share(128), 'F', -2.5f);
                    Number mathRaw = invoke(emulator, table, width, 137, env, math, abs, data.share(128));
                    assertEquals(2.5f, value(emulator, 'F', mathRaw));
                    int sqrt = member(emulator, table, width, env, math, data, 113, "sqrt", "(D)D");
                    argument(data.share(128), 'D', 9d);
                    mathRaw = invoke(emulator, table, width, 140, env, math, sqrt, data.share(128));
                    assertEquals(3d, value(emulator, 'D', mathRaw));

                    for (String name : new String[] {"hostFailure", "hostError"}) {
                        int fail = member(emulator, table, width, env, clazz, data, 33, name, "()I");
                        if (name.equals("hostFailure")) {
                            assertSame(HOST_FAILURE, assertThrows(UnsupportedOperationException.class,
                                    () -> invoke(emulator, table, width, 51, env, receiver, fail, data.share(128))));
                        } else {
                            assertSame(HOST_ERROR, assertThrows(AssertionError.class,
                                    () -> invoke(emulator, table, width, 51, env, receiver, fail, data.share(128))));
                        }
                        assertEquals(0, emulator.getThreadDispatcher().getTaskCount());
                        int good = member(emulator, table, width, env, clazz, data, 33, "echo", "(D)D");
                        argument(data.share(128), 'D', 123.125d);
                        Number raw = invoke(emulator, table, width, 60, env, receiver, good, data.share(128));
                        assertEquals(123.125d, value(emulator, 'D', raw));
                        assertEquals(123.125d, values.last());
                    }
                } finally {
                    block.free();
                }
            }
        }
    }

    private static void argument(Pointer data, char type, Object value) {
        data.setLong(0, 0x1122334455667788L);
        switch (type) {
            case 'Z': data.setByte(0, (byte) ((Boolean) value ? 1 : 0)); break;
            case 'B': data.setByte(0, (Byte) value); break;
            case 'C': data.setShort(0, (short) ((Character) value).charValue()); break;
            case 'S': data.setShort(0, (Short) value); break;
            case 'I': data.setInt(0, (Integer) value); break;
            case 'J': data.setLong(0, (Long) value); break;
            case 'F': data.setFloat(0, (Float) value); break;
            case 'D': data.setDouble(0, (Double) value); break;
            default: throw new AssertionError(type);
        }
    }

    private static Object value(AndroidEmulator emulator, char type, Number raw) {
        if (emulator.is64Bit() && (type == 'F' || type == 'D')) {
            ByteBuffer vector = ByteBuffer.wrap(emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0)).order(ByteOrder.LITTLE_ENDIAN);
            if (type == 'F') return vector.getFloat();
            return vector.getDouble();
        }
        switch (type) {
            case 'Z': return raw.intValue() == 1;
            case 'B': return raw.byteValue();
            case 'C': return (char) raw.intValue();
            case 'S': return raw.shortValue();
            case 'I': return raw.intValue();
            case 'J': return raw.longValue();
            case 'F': return Float.intBitsToFloat(raw.intValue());
            case 'D': return Double.longBitsToDouble(raw.longValue());
            default: throw new AssertionError(type);
        }
    }

    private static int member(AndroidEmulator emulator, Pointer table, int width, Pointer env, int clazz,
                              Pointer memory, int slot, String name, String signature) {
        memory.setString(0, name);
        memory.setString(64, signature);
        return invoke(emulator, table, width, slot, env, clazz, memory, memory.share(64)).intValue();
    }

    private static Number invoke(AndroidEmulator emulator, Pointer table, int width, int slot, Object... args) {
        return Module.emulateFunction(emulator, UnidbgPointer.nativeValue(table.getPointer((long) slot * width)), args);
    }
}
