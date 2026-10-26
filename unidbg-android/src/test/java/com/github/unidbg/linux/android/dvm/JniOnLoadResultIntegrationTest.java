package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.Svc;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class JniOnLoadResultIntegrationTest {

    // https://docs.oracle.com/en/java/javase/21/docs/specs/jni/invocation.html#jni_onload
    @Test
    public void nativeVersionsAndReferenceLifetimeOnBothArchitectures() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                VM vm = emulator.createDalvikVM();
                DvmObject<?> local = new StringObject(vm, "local");
                DvmObject<?> global = new StringObject(vm, "global");
                int globalHandle = vm.addGlobalObject(global);
                int[] version = {VM.JNI_VERSION_1_6};
                int[] createdHandle = {0};
                java.util.function.ToLongFunction<Emulator<?>> onLoad = current -> {
                    assertEquals(UnidbgPointer.nativeValue(vm.getJavaVM()),
                            UnidbgPointer.nativeValue(current.getContext().getPointerArg(0)));
                    assertNull(current.getContext().getPointerArg(1));
                    createdHandle[0] = vm.addLocalObject(new StringObject(vm, "created during JNI_OnLoad"));
                    return version[0];
                };
                Svc service = wide ? new Arm64Svc() {
                    @Override
                    public long handle(Emulator<?> current) { return onLoad.applyAsLong(current); }
                } : new ArmSvc() {
                    @Override
                    public long handle(Emulator<?> current) { return onLoad.applyAsLong(current); }
                };
                UnidbgPointer entry = emulator.getSvcMemory().registerSvc(service);
                Map<String, UnidbgPointer> symbols = new HashMap<>();
                symbols.put("fixture", entry);
                Module module = emulator.getMemory().loadVirtualModule("libonload-result.so", symbols);
                DalvikModule dalvik = new DalvikModule((BaseVM) vm, module);

                int retainedHandle = vm.addLocalObject(local);
                dalvik.callJNI_OnLoad(emulator);
                assertSame(local, vm.getObject(retainedHandle));
                assertThrows(IllegalStateException.class, () -> dalvik.callJNI_OnLoadWithResult(emulator));
                assertSame(local, vm.getObject(retainedHandle));

                symbols.put("JNI_OnLoad", entry);
                for (int expected : new int[] {VM.JNI_VERSION_1_1, VM.JNI_VERSION_1_2, VM.JNI_VERSION_1_4,
                        VM.JNI_VERSION_1_6, VM.JNI_VERSION_1_8}) {
                    version[0] = expected;
                    int handle = vm.addLocalObject(local);
                    assertEquals(expected, dalvik.callJNI_OnLoadWithResult(emulator));
                    assertNull(vm.getObject(handle));
                    assertNull(vm.getObject(createdHandle[0]));
                    assertSame(global, vm.getObject(globalHandle));
                }
                int handle = vm.addLocalObject(local);
                dalvik.callJNI_OnLoad(emulator);
                assertNull(vm.getObject(handle));
                assertNull(vm.getObject(createdHandle[0]));
                assertSame(global, vm.getObject(globalHandle));

                for (int invalid : new int[] {0, -1}) {
                    version[0] = invalid;
                    for (boolean withResult : new boolean[] {false, true}) {
                        int invalidHandle = vm.addLocalObject(local);
                        assertThrows(IllegalStateException.class, () -> {
                            if (withResult) dalvik.callJNI_OnLoadWithResult(emulator);
                            else dalvik.callJNI_OnLoad(emulator);
                        });
                        assertNull(vm.getObject(invalidHandle));
                        assertNull(vm.getObject(createdHandle[0]));
                        assertSame(global, vm.getObject(globalHandle));
                    }
                }
            }
        }
    }
}
