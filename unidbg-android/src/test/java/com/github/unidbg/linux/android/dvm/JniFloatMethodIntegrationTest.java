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
import unicorn.Arm64Const;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

/** Integration regression for native CallFloatMethodA, including ABI return registers.
 * JNI table index 57 and jvalue union (float is not promoted to double):
 * https://docs.oracle.com/en/java/javase/11/docs/specs/jni/functions.html#calltypemethod-routines
 * https://docs.oracle.com/en/java/javase/11/docs/specs/jni/types.html#the-value-type
 */
public class JniFloatMethodIntegrationTest {
    private static final IOException ORIGINAL = new IOException("float target failure");

    public static final class Target {
        public float value() { return -3.25f; }
        public float identity(float value, int marker) {
            assertEquals(0x12345678, marker);
            return value;
        }
        public float fail() throws IOException { throw ORIGINAL; }
    }

    @Test
    public void floatArrayCallsOnBothArchitectures() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide
                    ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                vm.setDvmClassFactory(new ProxyClassFactory());
                Pointer env = vm.getJNIEnv(), table = env.getPointer(0);
                int width = wide ? 8 : 4;
                DvmObject<?> object = ProxyDvmObject.createObject(vm, new Target());
                int receiver = vm.addLocalObject(object), type = vm.addLocalObject(object.getObjectType());
                MemoryBlock block = emulator.getMemory().malloc(160, true);
                try {
                    Pointer data = block.getPointer();
                    int method = method(emulator, table, width, env, type, data, "value", "()F");
                    assertEquals(0xc0500000, callFloat(emulator, table, width, env, receiver, method, null, wide));
                    assertEquals(0, invoke(emulator, table, width, 15, env).intValue());

                    method = method(emulator, table, width, env, type, data, "identity", "(FI)F");
                    // Each union occupies eight bytes; high bytes are deliberately nonzero.
                    data.setLong(128, 0x1122334400000000L);
                    data.setInt(136, 0x12345678);
                    int[] values = {0x3fa00000, 0xc0500000, 0x80000000, 0x7f800000, 0x7fc12345};
                    for (int bits : values) {
                        data.setInt(128, bits);
                        assertEquals(bits, callFloat(emulator, table, width, env, receiver, method, data.share(128), wide));
                        assertEquals(0, invoke(emulator, table, width, 15, env).intValue());
                    }

                    method = method(emulator, table, width, env, type, data, "fail", "()F");
                    assertEquals(0, callFloat(emulator, table, width, env, receiver, method, null, wide));
                    int pending = invoke(emulator, table, width, 15, env).intValue();
                    assertNotEquals(0, pending);
                    assertSame(ORIGINAL, vm.getObject(pending).getValue());
                    invoke(emulator, table, width, 17, env);
                    assertEquals(0, invoke(emulator, table, width, 15, env).intValue());
                } finally {
                    block.free();
                }
            }
        }
    }

    private static int callFloat(AndroidEmulator emulator, Pointer table, int width, Pointer env,
                                 int receiver, int method, Pointer args, boolean wide) {
        Number result = invoke(emulator, table, width, 57, env, receiver, method, args);
        if (!wide) {
            return result.intValue();
        }
        byte[] vector = emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0);
        return ByteBuffer.wrap(vector).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int method(AndroidEmulator emulator, Pointer table, int width, Pointer env,
                              int type, Pointer data, String name, String signature) {
        data.setString(0, name);
        data.setString(64, signature);
        return invoke(emulator, table, width, 33, env, type, data, data.share(64)).intValue();
    }

    private static Number invoke(AndroidEmulator emulator, Pointer table, int width, int index, Object... args) {
        return Module.emulateFunction(emulator, UnidbgPointer.nativeValue(table.getPointer((long) index * width)), args);
    }
}
