package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

/** Real JNI calls must retain the target Throwable as a pending exception, not invoke unsupported fallback.
 * https://docs.oracle.com/en/java/javase/11/docs/specs/jni/functions.html#exceptions
 */
public class JniProxyExceptionIntegrationTest {
    public static final IOException ORIGINAL = new IOException("original target failure");
    public static final class Target {
        public Target(int fail) throws IOException { if (fail != 0) throw ORIGINAL; }
        public Object object() throws IOException { throw ORIGINAL; }
        public boolean bool() throws IOException { throw ORIGINAL; }
        public byte byteValue() throws IOException { throw ORIGINAL; }
        public char charValue() throws IOException { throw ORIGINAL; }
        public short shortValue() throws IOException { throw ORIGINAL; }
        public int intValue() throws IOException { throw ORIGINAL; }
        public long longValue() throws IOException { throw ORIGINAL; }
        public float floatValue() throws IOException { throw ORIGINAL; }
        public double doubleValue() throws IOException { throw ORIGINAL; }
        public void voidValue() throws IOException { throw ORIGINAL; }
        public static Object staticObject() throws IOException { throw ORIGINAL; }
        public static boolean staticBoolean() throws IOException { throw ORIGINAL; }
        public static int staticInt() throws IOException { throw ORIGINAL; }
        public static long staticLong() throws IOException { throw ORIGINAL; }
        public static void staticVoid() throws IOException { throw ORIGINAL; }
    }
    @Test public void targetExceptionsReachNativePendingStateOnBothArchitectures() throws Exception {
        for (boolean wide : new boolean[] {false,true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM(); vm.setDvmClassFactory(new ProxyClassFactory());
                Pointer env = vm.getJNIEnv(), table = env.getPointer(0); int width = wide ? 8 : 4;
                DvmObject<?> object = ProxyDvmObject.createObject(vm, new Target(0));
                int receiver = vm.addLocalObject(object), type = vm.addLocalObject(object.getObjectType());
                MemoryBlock block = emulator.getMemory().malloc(256, true);
                try {
                    Pointer data = block.getPointer();
                    int constructor = method(emulator, table, width, env, type, data, 33, "<init>", "(I)V");
                    data.setInt(128, 1);
                    invoke(emulator, table, width, 30, env, type, constructor, data.share(128));
                    pending(emulator, vm, table, width, env);
                    String[] names = {"object","bool","byteValue","charValue","shortValue","intValue","longValue","floatValue","doubleValue","voidValue"};
                    String[] returns = {"Ljava/lang/Object;","Z","B","C","S","I","J","F","D","V"};
                    int[] instanceCalls = {35,38,41,44,47,50,53,56,58,62};
                    for (int index = 0; index < names.length; index++) {
                        int method = method(emulator, table, width, env, type, data, 33, names[index], "()" + returns[index]);
                        // ARM64 has no Char JNI table implementation; exercise the same live-VM binding directly.
                        if (wide && returns[index].equals("C")) {
                            DvmMethod binding = object.getObjectType().getMethod(method);
                            binding.callCharMethodV(object, new VaList((BaseVM) vm, binding) { });
                        } else {
                            // Double is direct, other existing entries V; no arguments are consumed.
                            invoke(emulator, table, width, instanceCalls[index], env, receiver, method, data.share(160));
                        }
                        pending(emulator, vm, table, width, env);
                    }
                    String[] statics = {"staticObject","staticBoolean","staticInt","staticLong","staticVoid"};
                    String[] types = {"Ljava/lang/Object;","Z","I","J","V"}; int[] calls = {115,118,130,133,142};
                    for (int index = 0; index < statics.length; index++) {
                        int method = method(emulator, table, width, env, type, data, 113, statics[index], "()" + types[index]);
                        invoke(emulator, table, width, calls[index], env, type, method, data.share(160));
                        pending(emulator, vm, table, width, env);
                    }
                } finally { block.free(); }
            }
        }
    }
    private static int method(AndroidEmulator emulator, Pointer table, int width, Pointer env, int type, Pointer data,
                              int call, String name, String signature) {
        data.setString(0, name); data.setString(64, signature);
        return invoke(emulator, table, width, call, env, type, data, data.share(64)).intValue();
    }
    private static void pending(AndroidEmulator emulator, VM vm, Pointer table, int width, Pointer env) {
        int handle = invoke(emulator, table, width, 15, env).intValue();
        assertNotEquals(0, handle); assertSame(ORIGINAL, vm.getObject(handle).getValue());
        assertEquals("java/io/IOException", vm.getObject(handle).getObjectType().getClassName());
        invoke(emulator, table, width, 17, env);
        assertEquals(0, invoke(emulator, table, width, 15, env).intValue());
    }
    private static Number invoke(AndroidEmulator emulator, Pointer table, int width, int index, Object... args) {
        return Module.emulateFunction(emulator, UnidbgPointer.nativeValue(table.getPointer((long) index * width)), args);
    }
}
