package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.IO;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** openat(2) relative to a directory descriptor follows that directory after rename(2). */
public class DirfdIdentityIntegrationTest {
    @Test
    public void renamedDirectoryFollowsDirfd() throws Exception {
        Path parent = Paths.get("target/dirfd-identity");
        Files.createDirectories(parent);
        byte[] original = {11, 22, 33, 44, 55};
        byte[] replacement = {9, 8, 7, 6};
        for (boolean wide : new boolean[] {false, true}) {
            Path root = Files.createTempDirectory(parent, wide ? "arm64-" : "arm32-").toAbsolutePath();
            Files.createDirectory(root.resolve("old"));
            Files.write(root.resolve("old/child"), original);
            try (AndroidEmulator emulator = (wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                    .setRootDir(root.toFile()).addBackendFactory(new Unicorn2Factory(true)).build();
                 RawSyscalls calls = new RawSyscalls(emulator)) {
                MemoryBlock scratch = emulator.getMemory().malloc(256, true);
                try {
                    UnidbgPointer buffer = scratch.getPointer();
                    buffer.setString(0, "/old");
                    buffer.setString(64, "/moved");
                    buffer.setString(128, "child");
                    int dirfd = calls.call(wide ? 56 : 322, IO.AT_FDCWD, buffer.peer, 0x10000, 0);
                    assertTrue(dirfd >= 0);
                    assertEquals(0, calls.call(wide ? 38 : 329, IO.AT_FDCWD, buffer.peer, IO.AT_FDCWD, buffer.peer + 64));
                    assertArrayEquals(original, Files.readAllBytes(root.resolve("moved/child")));
                    Files.createDirectory(root.resolve("old"));
                    Files.write(root.resolve("old/child"), replacement);
                    int child = calls.call(wide ? 56 : 322, dirfd, buffer.peer + 128, 0, 0);
                    assertTrue(child >= 0);
                    assertEquals(original.length, calls.call(wide ? 63 : 3, child, buffer.peer + 192, original.length));
                    assertArrayEquals(original, buffer.getByteArray(192, original.length));
                    assertArrayEquals(original, Files.readAllBytes(root.resolve("moved/child")));
                    assertArrayEquals(replacement, Files.readAllBytes(root.resolve("old/child")));
                    assertEquals(0, calls.call(wide ? 57 : 6, child));
                    assertEquals(0, calls.call(wide ? 57 : 6, dirfd));
                } finally {
                    scratch.free();
                }
            }
        }
    }

    private static final class RawSyscalls implements AutoCloseable {
        private final AndroidEmulator emulator;
        private final UnidbgPointer code;

        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(8192, 7);
            for (int number : new int[] {3, 6, 38, 56, 57, 63, 322, 329}) {
                long offset = number * 16L;
                code.setInt(offset, emulator.is64Bit() ? 0xd2800008 | (number << 5)
                        : 0xe3007000 | (number & 0xfff) | ((number & 0xf000) << 4));
                code.setInt(offset + 4, emulator.is64Bit() ? 0xd4000001 : 0xef000000);
                code.setInt(offset + 8, emulator.is64Bit() ? 0xd65f03c0 : 0xe12fff1e);
            }
        }

        int call(int number, Number... arguments) {
            if (emulator.is32Bit()) {
                arguments = Arrays.stream(arguments).map(Number::intValue).toArray(Number[]::new);
            }
            return emulator.eFunc(code.peer + number * 16L, arguments).intValue();
        }

        @Override
        public void close() {
            emulator.getMemory().munmap(code.peer, 8192);
        }
    }
}
