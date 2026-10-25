package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.linux.android.dvm.jni.ProxyReflectedMethod;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class JniProxyReflectionAccessIntegrationTest {

    private static final IOException TARGET_FAILURE = new IOException("private target failure");

    private static final class PrivateTarget {
        private int value;

        private PrivateTarget() {
            value = 7;
        }

        private int advance() {
            return ++value;
        }

        private int fail() throws IOException {
            throw TARGET_FAILURE;
        }
    }

    @Test
    public void publicDeclarationsAndPrivateMembersThroughJni() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                vm.setDvmClassFactory(new ProxyClassFactory());
                Pointer env = vm.getJNIEnv();
                Pointer table = env.getPointer(0);
                int width = wide ? 8 : 4;
                MemoryBlock block = emulator.getMemory().malloc(256, true);
                try {
                    Pointer data = block.getPointer();
                    for (boolean vaList : new boolean[] {false, true}) {
                        Enumeration<String> enumeration = Collections.enumeration(Collections.singleton("entry"));
                        DvmObject<?> wrapped = ProxyDvmObject.createObject(vm, enumeration);
                        int receiver = vm.addLocalObject(wrapped);
                        int type = vm.addLocalObject(wrapped.getObjectType());
                        int hasMore = member(emulator, table, width, env, type, data, 33, "hasMoreElements", "()Z");
                        int next = member(emulator, table, width, env, type, data, 33, "nextElement", "()Ljava/lang/Object;");
                        int booleanCall = vaList ? 38 : 37;
                        int objectCall = vaList ? 35 : 34;
                        assertEquals(VM.JNI_TRUE, invoke(emulator, table, width, booleanCall, env, receiver, hasMore, data.share(160)).intValue());
                        int entry = invoke(emulator, table, width, objectCall, env, receiver, next, data.share(160)).intValue();
                        assertEquals("entry", vm.getObject(entry).getValue());
                        assertEquals(VM.JNI_FALSE, invoke(emulator, table, width, booleanCall, env, receiver, hasMore, data.share(160)).intValue());
                        assertOriginalDeclaration(emulator, vm, table, width, env, type, hasMore, enumeration.getClass());

                        Map<String, String> map = new HashMap<>();
                        map.put("key", "value");
                        Object keys = map.keySet();
                        wrapped = ProxyDvmObject.createObject(vm, keys);
                        receiver = vm.addLocalObject(wrapped);
                        type = vm.addLocalObject(wrapped.getObjectType());
                        int toArray = member(emulator, table, width, env, type, data, 33, "toArray", "()[Ljava/lang/Object;");
                        int array = invoke(emulator, table, width, objectCall, env, receiver, toArray, data.share(160)).intValue();
                        assertEquals(1, invoke(emulator, table, width, 171, env, array).intValue());
                        int key = invoke(emulator, table, width, 173, env, array, 0).intValue();
                        assertEquals("key", vm.getObject(key).getValue());
                        assertOriginalDeclaration(emulator, vm, table, width, env, type, toArray, keys.getClass());

                        type = vm.addLocalObject(vm.resolveClass(PrivateTarget.class.getName().replace('.', '/')));
                        int constructor = member(emulator, table, width, env, type, data, 33, "<init>", "()V");
                        receiver = invoke(emulator, table, width, vaList ? 29 : 28, env, type, constructor, data.share(160)).intValue();
                        PrivateTarget target = (PrivateTarget) vm.getObject(receiver).getValue();
                        assertEquals(7, target.value);
                        int field = member(emulator, table, width, env, type, data, 94, "value", "I");
                        invoke(emulator, table, width, 109, env, receiver, field, 41);
                        assertEquals(41, target.value);
                        int advance = member(emulator, table, width, env, type, data, 33, "advance", "()I");
                        assertEquals(42, invoke(emulator, table, width, vaList ? 50 : 49, env, receiver, advance, data.share(160)).intValue());
                        assertEquals(42, target.value);
                        assertEquals(42, invoke(emulator, table, width, 100, env, receiver, field).intValue());
                        int fail = member(emulator, table, width, env, type, data, 33, "fail", "()I");
                        assertEquals(0, invoke(emulator, table, width, vaList ? 50 : 49, env, receiver, fail, data.share(160)).intValue());
                        int exception = invoke(emulator, table, width, 15, env).intValue();
                        assertSame(TARGET_FAILURE, vm.getObject(exception).getValue());
                        invoke(emulator, table, width, 17, env);
                        assertEquals(0, invoke(emulator, table, width, 15, env).intValue());
                    }
                } finally {
                    block.free();
                }
            }
        }
    }

    private static int member(AndroidEmulator emulator, Pointer table, int width, Pointer env, int type,
                              Pointer data, int call, String name, String signature) {
        data.setString(0, name);
        data.setString(64, signature);
        return invoke(emulator, table, width, call, env, type, data, data.share(64)).intValue();
    }

    private static void assertOriginalDeclaration(AndroidEmulator emulator, VM vm, Pointer table, int width,
                                                  Pointer env, int type, int method, Class<?> declaringClass) {
        int reflected = invoke(emulator, table, width, 9, env, type, method, VM.JNI_FALSE).intValue();
        ProxyReflectedMethod original = (ProxyReflectedMethod) vm.getObject(reflected).getValue();
        assertSame(declaringClass, original.getDeclaringClass());
    }

    private static Number invoke(AndroidEmulator emulator, Pointer table, int width, int index, Object... args) {
        return Module.emulateFunction(emulator, UnidbgPointer.nativeValue(table.getPointer((long) index * width)), args);
    }
}
