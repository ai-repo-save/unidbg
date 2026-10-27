package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory;
import com.github.unidbg.memory.MemoryBlock;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * From the repository root, build jni-thread-attachment.c with NDK armv7a-linux-androideabi23-clang
 * and aarch64-linux-android23-clang using -O2 -shared -fPIC -pthread (-mno-outline-atomics on ARM64)
 * into target/jni-thread-attachment/{arm32,arm64}.so. Run explicitly with
 * -Dtest=JniThreadAttachmentIntegrationTest -Dmaven.test.skip=false -Dsurefire.failIfNoSpecifiedTests=false;
 * jni.thread.attachment.dir selects the fixture directory.
 */
public class JniThreadAttachmentIntegrationTest {
    public static final class Target {
        static Path output;

        public static int record() throws IOException {
            Files.write(output, "host\n".getBytes(StandardCharsets.US_ASCII), StandardOpenOption.APPEND);
            return 0x73;
        }
    }

    @Test
    public void nativeChildDetachPreservesTheParentInvocationOnBothArchitectures() throws Exception {
        File directory = new File(System.getProperty("jni.thread.attachment.dir", "../target/jni-thread-attachment"));
        for (boolean wide : new boolean[] {false, true}) {
            Path workspace = Files.createTempDirectory(new File("target").toPath(), "jni-thread-attachment-");
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.setRootDir(workspace.resolve("root").toFile())
                    .addBackendFactory(new Unicorn2Factory(true)).build()) {
                emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
                emulator.getSyscallHandler().setEnableThreadDispatcher(true);
                BaseVM vm = (BaseVM) emulator.createDalvikVM();
                vm.setDvmClassFactory(new ProxyClassFactory());
                vm.loadLibrary(new File(directory, wide ? "arm64.so" : "arm32.so"), false);
                DvmObject<?> receiver = vm.resolveClass("runtime/evaluation/ThreadAttachment").newObject(new Object());
                int caller = vm.addLocalObject(receiver);
                Pointer env = vm.getJNIEnv();
                Target.output = Files.createFile(workspace.resolve("host.txt"));
                MemoryBlock block = emulator.getMemory().malloc(12 * 4, true);
                try {
                    assertEquals(0x1122334455667788L,
                            receiver.callJniMethodLong(emulator, "run(J)J", block.getPointer().peer));
                    assertArrayEquals(new int[] {0, 0, 0, -2, 0, 0, 0x73, 0, -2, 0, 1, 1},
                            block.getPointer().getIntArray(0, 12));
                    assertSame(env, vm.getJNIEnv());
                    assertSame(receiver, vm.getObject(caller));
                    assertEquals("host\n", new String(Files.readAllBytes(Target.output), StandardCharsets.US_ASCII));
                } finally {
                    block.free();
                }
            }
        }
    }
}
