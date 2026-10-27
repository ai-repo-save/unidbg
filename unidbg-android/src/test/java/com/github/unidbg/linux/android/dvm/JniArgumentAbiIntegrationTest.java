package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.Svc;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;

/**
 * NDK callback argument decoding, not a substitute for the full JNI Java witness scenario.
 * On dev-builder, create target/jni-argument-abi and use the NDK bin directory:
 * armv7a-linux-androideabi23-clang and aarch64-linux-android23-clang, each with
 * -O2 -fPIC -fno-stack-protector -nostdlib -shared -Wl,--no-undefined
 * unidbg-android/src/test/native/android/jni-argument-abi.c -o target/jni-argument-abi/{arm32,arm64}.so.
 * Explicit Maven selector: -Dmaven.test.skip=false -Dtest=JniArgumentAbiIntegrationTest
 * -Dsurefire.failIfNoSpecifiedTests=false; optional -Djni.argument.abi.dir changes the fixture directory.
 */
public class JniArgumentAbiIntegrationTest {
    @Test
    public void compiledOrdinaryVaListAndJvalueCallsPreserveAllArguments() throws Exception {
        File directory = new File(System.getProperty("jni.argument.abi.dir", "../target/jni-argument-abi"));
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                BaseVM vm = (BaseVM) emulator.createDalvikVM();
                DvmClass type = vm.resolveClass("runtime/evaluation/AbiArguments");
                DvmMethod method = new DvmMethod(type, "observe",
                        "(ZBCSIJFDLjava/lang/Object;IIIIIIIIIDDDDDDDDD)V", false);
                DvmObject<?> anchor = type.newObject(new Object());
                int handle = vm.addLocalObject(anchor);
                Module fixture = emulator.loadLibrary(new File(directory, wide ? "arm64.so" : "arm32.so"), false);
                List<Object> expected = new ArrayList<>(Arrays.asList(1, -101, 65505, -12345, -123456789,
                        0x1020304050607080L, 1.5f, -2.25d, anchor));
                for (int index = 0; index < 9; index++) expected.add(21 + index);
                for (int index = 0; index < 9; index++) expected.add(31.25 + index);
                String[] entries = {"invoke_plain", "invoke_v", "invoke_a"};
                for (int mode = 0; mode < entries.length; mode++) {
                    final int invocation = mode;
                    List<Object> observed = new ArrayList<>();
                    LongSupplier decode = () -> {
                        VarArg args;
                        if (invocation == 0) args = ArmVarArg.create(emulator, vm, method);
                        else if (invocation == 1) args = wide
                                ? new VaList64(emulator, vm, emulator.getContext().getPointerArg(3), method)
                                : new VaList32(emulator, vm, emulator.getContext().getPointerArg(3), method);
                        else args = new JValueList(vm, emulator.getContext().getPointerArg(3), method);
                        observed.addAll(args.args);
                        observed.set(8, args.getObjectArg(8));
                        return 0;
                    };
                    Svc callback = wide ? new Arm64Svc() {
                        @Override
                        public long handle(com.github.unidbg.Emulator<?> current) { return decode.getAsLong(); }
                    } : new ArmSvc() {
                        @Override
                        public long handle(com.github.unidbg.Emulator<?> current) { return decode.getAsLong(); }
                    };
                    UnidbgPointer function = emulator.getSvcMemory().registerSvc(callback);
                    fixture.findSymbolByName(entries[mode], false).call(emulator, function, handle);
                    assertEquals(entries[mode] + " arm" + (wide ? 64 : 32), expected, observed);
                }
            }
        }
    }
}
