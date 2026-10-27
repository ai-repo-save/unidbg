package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.NestedRun;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.memory.MemoryBlock;
import org.junit.Test;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Build jni-native-tailcall.c using armv7a-linux-androideabi23-clang and aarch64-linux-android23-clang with
 * -O2 -fPIC -fno-stack-protector -nostdlib -shared -Wl,--no-undefined
 * into target/jni-native-tailcall/{arm32,arm64}.so. Run explicitly with
 * -Dtest=JniNativeTailCallIntegrationTest -Dmaven.test.skip=false
 * -Dsurefire.failIfNoSpecifiedTests=false; jni.native.tailcall.dir selects the fixture directory.
 */
public class JniNativeTailCallIntegrationTest {
    public static final class Target {
        static Path output;

        public static int host(int value) throws IOException {
            Files.write(output, (value + "\n").getBytes(StandardCharsets.US_ASCII), StandardOpenOption.APPEND);
            return value + 5;
        }
    }

    @Test
    public void nativeTailCallsAndHostWaitsPreserveTheCallerOnBothArchitectures() throws Exception {
        File directory = new File(System.getProperty("jni.native.tailcall.dir", "../target/jni-native-tailcall"));
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                BaseVM vm = (BaseVM) emulator.createDalvikVM();
                Module fixture = emulator.loadLibrary(new File(directory, wide ? "arm64.so" : "arm32.so"), false);
                long tail = fixture.findSymbolByName("tail_target", false).getAddress();
                ProxyClassFactory factory = new ProxyClassFactory();
                factory.setFallbackJni(new AbstractJni() {
                    @Override
                    public void callStaticVoidMethod(BaseVM target, DvmClass type, String signature, VarArg args) {
                        emulator.getBackend().reg_write(wide ? Arm64Const.UC_ARM64_REG_X2 : ArmConst.UC_ARM_REG_R2,
                                args.getIntArg(0));
                        throw NestedRun.runToFunction(tail);
                    }

                    @Override
                    public void callStaticVoidMethodV(BaseVM target, DvmClass type, String signature, VaList args) {
                        callStaticVoidMethod(target, type, signature, args);
                    }

                    @Override
                    public int callStaticIntMethodV(BaseVM target, DvmClass type, String signature, VaList args) {
                        return args.getIntArg(0) - 3;
                    }
                });
                vm.setDvmClassFactory(factory);
                int type = vm.addLocalObject(vm.resolveClass("runtime/evaluation/NativeTail"));
                Target.output = Files.createTempFile(new File("target").toPath(), "jni-native-tailcall-", ".txt");
                MemoryBlock block = emulator.getMemory().malloc(10 * 4, true);
                try {
                    for (int mode = 0; mode < 3; mode++) {
                        int value = 101 + mode;
                        block.getPointer().setMemory(0, 10 * 4, (byte) 0);
                        assertEquals(0x54321000 + mode, fixture.callFunction(emulator, "exercise",
                                vm.getJNIEnv(), type, mode, value, block.getPointer()).intValue());
                        assertArrayEquals(new int[] {value, 1, 1, 0, value - 3, 0, value + 5, 0, 0, 0x12345678},
                                block.getPointer().getIntArray(0, 10));
                    }
                    assertEquals("101\n102\n103\n", new String(Files.readAllBytes(Target.output), StandardCharsets.US_ASCII));
                } finally {
                    block.free();
                }
            }
        }
    }
}
