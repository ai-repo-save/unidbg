package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.jni.*;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.*;

/** Whole JNI plan execution: ordered real side effects, explicit recovery, and backend/target provenance. */
public class JniCallPlanIntegrationTest {
    public static final class Target {
        final Path witness;
        final Object token = new Object();
        final IOException original = new IOException("target failure");
        final IOException checkedHost = new IOException("backend checked failure");
        final AssertionError observerFailure = new AssertionError("failed observer");
        final OutOfMemoryError afterFailure = new OutOfMemoryError("after observer");
        int calls;
        Target(Path witness) { this.witness = witness; }
        public int around() throws IOException { append(0x11); return ++calls; }
        public int recover() throws IOException { append(0x44); throw original; }
        public int rethrow() throws IOException { return recover(); }
        public int hostFailure() { throw new AssertionError("host plan must not be submitted"); }
        public int backend() { throw new AssertionError("modeled provider must own this operation"); }
        public int afterFailure() throws IOException { append(0x99); return 42; }
        public Object object() throws IOException { append(0xbb); return token; }
        public Object backendObject() { throw new AssertionError("modeled object must remain normalized"); }
        public int wrongValue() { throw new AssertionError("invalid normalized value must be rejected before Java execution"); }
        void append(int value) throws IOException {
            Files.write(witness, new byte[] {(byte) value}, StandardOpenOption.APPEND);
        }
    }

    private static final class Plans extends ProxyClassFactory {
        private final Target target;
        private final Thread backend = Thread.currentThread();
        Plans(Target target) { this.target = target; }
        private void owner() { assertSame(backend, Thread.currentThread()); }
        private void record(int value) throws IOException { owner(); target.append(value); }

        private JniCallPlan decorate(String name, JniCallPlan original) {
            switch (name) {
                case "around": return original.then(first -> {
                    record(0x22);
                    return original.then(second -> JniCallPlan.value((Integer) first + (Integer) second));
                }).observe(observer(0x33, 0xaa, false));
                case "recover": return original.observe(observer(-1, 0x45, true)).then(JniCallPlan::value, (failure, targetThrown) -> {
                    owner(); assertSame(target.original, failure); assertEquals(true, targetThrown);
                    record(0x55); return JniCallPlan.value(73);
                }).observe(observer(0x66, 0xaa, false));
                case "rethrow": return original.then(JniCallPlan::value, (failure, targetThrown) -> {
                    owner(); assertSame(target.original, failure); assertEquals(true, targetThrown);
                    return JniCallPlan.failure(failure, targetThrown);
                }).observe(new JniCallPlan.Observer() {
                    @Override public void completed(Object value) { throw new AssertionError("target did not return"); }
                    @Override public void failed(Throwable failure, boolean targetThrown) throws Throwable {
                        owner(); assertSame(target.original, failure); assertEquals(true, targetThrown);
                        record(0x46); throw target.observerFailure;
                    }
                });
                case "hostFailure": return JniCallPlan.failure(target.checkedHost, false).then(JniCallPlan::value, (failure, targetThrown) -> {
                    owner(); assertSame(target.checkedHost, failure); assertEquals(false, targetThrown);
                    return JniCallPlan.failure(failure, targetThrown);
                }).observe(observer(-1, 0x77, false));
                case "backend": return JniCallPlan.backend(new AbstractJni() {
                    @Override public int callIntMethod(BaseVM vm, DvmObject<?> object, DvmMethod method, VarArg args) {
                        try { record(0x88); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                        return 9;
                    }
                }).then(value -> { owner(); return JniCallPlan.value((Integer) value + 1); });
                case "afterFailure": return original.observe(new JniCallPlan.Observer() {
                    @Override public void completed(Object value) { owner(); throw target.afterFailure; }
                    @Override public void failed(Throwable failure, boolean targetThrown) throws Throwable { record(0xaa); }
                });
                case "object": return original.then(value -> {
                    owner(); assertSame(target.token, ((DvmObject<?>) value).getValue());
                    return JniCallPlan.value(value);
                });
                case "backendObject": return JniCallPlan.backend(new AbstractJni() {
                    @Override public DvmObject<?> callObjectMethod(BaseVM vm, DvmObject<?> object, DvmMethod method, VarArg args) {
                        try { record(0xcc); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                        return ProxyDvmObject.createObject(vm, target.token);
                    }
                }).then(value -> { owner(); assertSame(target.token, ((DvmObject<?>) value).getValue()); return JniCallPlan.value(value); });
                case "wrongValue": return JniCallPlan.value(Long.valueOf(1));
                default: return original;
            }
        }

        private JniCallPlan.Observer observer(int success, int failure, boolean targetExpected) {
            return new JniCallPlan.Observer() {
                @Override public void completed(Object value) throws Throwable { owner(); if (success >= 0) record(success); }
                @Override public void failed(Throwable original, boolean targetThrown) throws Throwable {
                    owner(); assertEquals(targetExpected, targetThrown); if (failure >= 0) record(failure);
                }
            };
        }

        @Override
        public DvmClass createClass(BaseVM vm, String name, DvmClass parent, DvmClass[] interfaces) {
            return new ProxyDvmClass(vm, name, parent, interfaces, classLoader, visitor, null) {
                @Override protected Jni createJni(ProxyClassLoader loader, ProxyDvmObjectVisitor visits, Jni fallback) {
                    Jni delegate = super.createJni(loader, visits, fallback);
                    return (Jni) Proxy.newProxyInstance(Jni.class.getClassLoader(), new Class<?>[] {HostJni.class}, (proxy, method, args) -> {
                        Object result;
                        try { result = method.invoke(delegate, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                        return method.getName().equals("planMethod")
                                ? decorate(((DvmMethod) args[2]).getMethodName(), (JniCallPlan) result) : result;
                    });
                }
            };
        }
    }

    @Test
    public void explicitChainsPreserveOwnerOrderAndFailureClassification() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            Path witness = Files.createTempFile(Paths.get("target"), "jni-plan-", ".bin");
            Target target = new Target(witness);
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                vm.setDvmClassFactory(new Plans(target));
                DvmObject<?> object = ProxyDvmObject.createObject(vm, target);
                int receiver = vm.addLocalObject(object), type = vm.addLocalObject(object.getObjectType());
                Pointer env = vm.getJNIEnv(), table = env.getPointer(0);
                int width = wide ? 8 : 4;
                MemoryBlock strings = emulator.getMemory().malloc(256, true);
                try {
                    Pointer data = strings.getPointer();
                    int around = member(emulator, table, width, env, type, data, "around", "()I");
                    assertEquals(3, invoke(emulator, table, width, 49, env, receiver, around).intValue());
                    int recover = member(emulator, table, width, env, type, data, "recover", "()I");
                    assertEquals(73, invoke(emulator, table, width, 49, env, receiver, recover).intValue());
                    assertEquals(0, invoke(emulator, table, width, 228, env).intValue());
                    int rethrow = member(emulator, table, width, env, type, data, "rethrow", "()I");
                    invoke(emulator, table, width, 49, env, receiver, rethrow);
                    int pending = invoke(emulator, table, width, 15, env).intValue();
                    assertSame(target.original, vm.getObject(pending).getValue());
                    assertArrayEquals(new Throwable[] {target.observerFailure}, target.original.getSuppressed());
                    invoke(emulator, table, width, 17, env);
                    int hostFailure = member(emulator, table, width, env, type, data, "hostFailure", "()I");
                    assertSame(target.checkedHost, assertThrows(IllegalStateException.class,
                            () -> invoke(emulator, table, width, 49, env, receiver, hostFailure)).getCause());
                    assertEquals(0, invoke(emulator, table, width, 228, env).intValue());
                    int backend = member(emulator, table, width, env, type, data, "backend", "()I");
                    assertEquals(10, invoke(emulator, table, width, 49, env, receiver, backend).intValue());
                    int after = member(emulator, table, width, env, type, data, "afterFailure", "()I");
                    assertSame(target.afterFailure, assertThrows(OutOfMemoryError.class,
                            () -> invoke(emulator, table, width, 49, env, receiver, after)));
                    assertEquals(0, invoke(emulator, table, width, 228, env).intValue());
                    for (String name : new String[] {"object", "backendObject"}) {
                        int method = member(emulator, table, width, env, type, data, name, "()Ljava/lang/Object;");
                        int result = invoke(emulator, table, width, 34, env, receiver, method).intValue();
                        assertSame(target.token, vm.getObject(result).getValue());
                    }
                    int wrong = member(emulator, table, width, env, type, data, "wrongValue", "()I");
                    assertThrows(ClassCastException.class, () -> invoke(emulator, table, width, 49, env, receiver, wrong));
                    assertEquals(0, invoke(emulator, table, width, 228, env).intValue());
                    assertEquals(2, target.calls);
                } finally { strings.free(); }
            }
            assertArrayEquals(new byte[] {0x11, 0x22, 0x11, 0x33, 0x44, 0x45, 0x55, 0x66, 0x44, 0x46,
                    0x77, (byte) 0x88, (byte) 0x99, (byte) 0xbb, (byte) 0xcc}, Files.readAllBytes(witness));
        }
    }

    private static int member(AndroidEmulator emulator, Pointer table, int width, Pointer env, int type, Pointer data,
                              String name, String descriptor) {
        data.setString(0, name); data.setString(64, descriptor);
        return invoke(emulator, table, width, 33, env, type, data, data.share(64)).intValue();
    }

    private static Number invoke(AndroidEmulator emulator, Pointer table, int width, int index, Object... args) {
        return Module.emulateFunction(emulator, UnidbgPointer.nativeValue(table.getPointer((long) index * width)), args);
    }
}
