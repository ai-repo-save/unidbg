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

import static org.junit.Assert.assertSame;

public class JniProxyUnpackIntegrationTest {
    public static Object roundTrip(Object value) { return value; }

    @Test
    public void hostIdentityAndArrayWritesSurviveJniRoundTrip() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                vm.setDvmClassFactory(new ProxyClassFactory());
                Pointer env = vm.getJNIEnv(), table = env.getPointer(0);
                int width = wide ? 8 : 4;
                MemoryBlock block = emulator.getMemory().malloc(128, true);
                try {
                    Pointer data = block.getPointer();
                    data.setString(0, "roundTrip");
                    data.setString(32, "(Ljava/lang/Object;)Ljava/lang/Object;");
                    int type = vm.addLocalObject(vm.resolveClass(getClass().getName().replace('.', '/')));
                    int method = invoke(emulator, table, width, 113, env, type, data, data.share(32)).intValue();
                    Object shared = new Object();
                    Object[] values = {new String[] {"first", null}, new String[0],
                            new Object[] {shared, shared, null, String.class},
                            new Class<?>[] {String.class, Object.class}, String.class,
                            new byte[] {1, 2, 3}, null};
                    for (Object expected : values) {
                        int input = vm.addLocalObject(ProxyDvmObject.createObject(vm, expected));
                        int output = invoke(emulator, table, width, 114, env, type, method, input).intValue();
                        assertSame(expected, ProxyDvmObject.unpack(vm.getObject(output)));
                    }

                    Object[] original = {shared, shared};
                    int array = vm.addLocalObject(ProxyDvmObject.createObject(vm, original));
                    int first = invoke(emulator, table, width, 173, env, array, 0).intValue();
                    int second = invoke(emulator, table, width, 173, env, array, 1).intValue();
                    assertSame(shared, ProxyDvmObject.unpack(vm.getObject(first)));
                    assertSame(vm.getObject(first), vm.getObject(second));
                    int classValue = vm.addLocalObject(ProxyDvmObject.createObject(vm, String.class));
                    invoke(emulator, table, width, 174, env, array, 0, classValue);
                    assertSame(String.class, original[0]);
                    assertSame(shared, original[1]);
                    assertSame(original, ProxyDvmObject.unpack(vm.getObject(array)));

                } finally {
                    block.free();
                }
            }
        }
    }

    private static Number invoke(AndroidEmulator emulator, Pointer table, int width, int index, Object... args) {
        return Module.emulateFunction(emulator, UnidbgPointer.nativeValue(table.getPointer((long) index * width)), args);
    }
}
