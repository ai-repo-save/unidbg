package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.array.ArrayObject;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** JNI/proxy integration regression: Java Arrays.sort mutates its argument and
 * Arrays.asList retains its backing array; JNI Get/SetObjectArrayElement observe that same array.
 * https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/Arrays.html
 * https://docs.oracle.com/en/java/javase/11/docs/specs/jni/functions.html
 */
public class JniProxyObjectArrayIntegrationTest {
    @Test
    public void sharedObjectArrayOnBothArchitectures() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                vm.setDvmClassFactory(new ProxyClassFactory());
                Pointer env = vm.getJNIEnv(), table = env.getPointer(0);
                int width = wide ? 8 : 4;
                String a = new String("a"), z = new String("z");
                String[] backing = {z, a, null};
                ArrayObject array = (ArrayObject) ProxyDvmObject.createObject(vm, backing);
                int handle = vm.addLocalObject(array);
                DvmObject<?> originalA = array.getValue()[1];
                MemoryBlock block = emulator.getMemory().malloc(256, true);
                try {
                    Pointer memory = block.getPointer();
                    int arrays = vm.addLocalObject(vm.resolveClass("java/util/Arrays"));
                    memory.setString(0, "asList");
                    memory.setString(32, "([Ljava/lang/Object;)Ljava/util/List;");
                    int method = invoke(emulator, table, width, 113, env, arrays, memory, memory.share(32)).intValue();
                    memory.setLong(128, handle & 0xffffffffL);
                    int listHandle = invoke(emulator, table, width, 116, env, arrays, method, memory.share(128)).intValue();
                    List<?> list = (List<?>) vm.getObject(listHandle).getValue();
                    int replacement = vm.addLocalObject(ProxyDvmObject.createObject(vm, "b"));
                    invoke(emulator, table, width, 174, env, handle, 2, replacement);
                    assertArrayEquals(new String[] {z, a, "b"}, backing);
                    assertEquals("b", list.get(2));
                    memory.setString(0, "sort");
                    memory.setString(32, "([Ljava/lang/Object;)V");
                    method = invoke(emulator, table, width, 113, env, arrays, memory, memory.share(32)).intValue();
                    invoke(emulator, table, width, 143, env, arrays, method, memory.share(128));
                    assertArrayEquals(new String[] {a, "b", z}, backing);
                    int first = invoke(emulator, table, width, 173, env, handle, 0).intValue();
                    assertSame(originalA, vm.getObject(first));
                    assertEquals(a, list.get(0));
                    backing[1] = null;
                    assertEquals(0, invoke(emulator, table, width, 173, env, handle, 1).intValue());
                    backing[1] = "host";
                    int second = invoke(emulator, table, width, 173, env, handle, 1).intValue();
                    assertEquals("host", vm.getObject(second).getValue());
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
