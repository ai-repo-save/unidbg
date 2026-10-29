package com.github.unidbg.linux.android;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Explicit dlsym scope integration. Selector: -Dtest=DlsymScopeIntegrationTest -Dmaven.test.skip=false. */
public class DlsymScopeIntegrationTest {
    private static final String SYMBOL = "scope_marker";

    @Test
    public void rtldNextStartsAfterCallerAndDefaultKeepsLoadOrder() throws Exception {
        String ndk = System.getenv("ANDROID_NDK_HOME");
        if (ndk == null || ndk.isEmpty()) {
            throw new IllegalStateException("ANDROID_NDK_HOME is unset");
        }
        Path bin = Paths.get(ndk, "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin");
        Path directory = Files.createTempDirectory(Paths.get("/tmp"), "dlsym-scope-");
        Path source = directory.resolve("scope_marker.c");
        Files.write(source, ("__attribute__((visibility(\"default\"))) int " + SYMBOL + "(void) { return 1; }\n")
                .getBytes(StandardCharsets.US_ASCII));
        try {
            for (boolean wide : new boolean[] {false, true}) {
                String suffix = wide ? "arm64" : "arm32";
                Path firstFile = directory.resolve("libscope_first_" + suffix + ".so");
                Path secondFile = directory.resolve("libscope_second_" + suffix + ".so");
                compile(bin.resolve(wide ? "aarch64-linux-android23-clang" : "armv7a-linux-androideabi23-clang"), source, firstFile);
                compile(bin.resolve(wide ? "aarch64-linux-android23-clang" : "armv7a-linux-androideabi23-clang"), source, secondFile);
                AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
                try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                    emulator.getMemory().setCallInitFunction(false);
                    Module first = emulator.loadLibrary(firstFile.toFile(), false);
                    Module second = emulator.loadLibrary(secondFile.toFile(), false);
                    int lr = wide ? Arm64Const.UC_ARM64_REG_LR : ArmConst.UC_ARM_REG_LR;
                    emulator.getBackend().reg_write(lr, first.base);
                    UnidbgPointer caller = emulator.getContext().getLRPointer();
                    assertEquals(first.base, caller.peer);
                    assertSame(first, emulator.getMemory().findModuleByAddress(caller.peer));

                    long nextHandle = wide ? -1L : 0xfffffffeL;
                    long defaultHandle = wide ? 0L : 0xffffffffL;
                    Symbol next = emulator.getMemory().dlsym(nextHandle, SYMBOL);
                    Symbol fallback = emulator.getMemory().dlsym(defaultHandle, SYMBOL);
                    assertSame(second, emulator.getMemory().findModuleByAddress(next.getAddress()));
                    assertEquals(second.findSymbolByName(SYMBOL, false).getAddress(), next.getAddress());
                    assertSame(first, emulator.getMemory().findModuleByAddress(fallback.getAddress()));
                    assertEquals(first.findSymbolByName(SYMBOL, false).getAddress(), fallback.getAddress());
                    assertSame(first, emulator.getMemory().findModuleByAddress(
                            emulator.getMemory().dlsym(0L, SYMBOL).getAddress()));

                    emulator.getBackend().reg_write(lr, 1L);
                    assertNull(emulator.getMemory().dlsym(nextHandle, SYMBOL));
                }
            }
        } finally {
            Files.deleteIfExists(source);
            for (String suffix : new String[] {"arm32", "arm64"}) {
                Files.deleteIfExists(directory.resolve("libscope_first_" + suffix + ".so"));
                Files.deleteIfExists(directory.resolve("libscope_second_" + suffix + ".so"));
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void compile(Path clang, Path source, Path output) throws Exception {
        Process process = new ProcessBuilder(clang.toString(), "-O2", "-fPIC", "-fno-stack-protector", "-fno-builtin",
                "-nostdlib", "-shared", "-Wl,--no-undefined", "-Wl,-z,max-page-size=4096",
                source.toString(), "-o", output.toString()).redirectErrorStream(true).start();
        String compilerOutput = readAscii(process.getInputStream());
        int status = process.waitFor();
        if (status != 0) {
            throw new IllegalStateException(clang + " exited " + status + ": " + compilerOutput);
        }
    }

    private static String readAscii(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.US_ASCII);
    }
}
