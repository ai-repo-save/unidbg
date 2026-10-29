package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.file.ByteArrayFileIO;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.IO;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Guest access(2) and open(2) continue through a symlink target supplied as a file fact. */
public class SymlinkTargetIntegrationTest {
    private static final byte[] TARGET = {7, 7, 7, 7};
    private static final byte[] DECOY = {9, 9};

    @Test
    public void symlinkComponentUsesLinkTargetFact() throws Exception {
        Path parent = Paths.get("target/symlink-target");
        Files.createDirectories(parent);
        for (boolean wide : new boolean[] {false, true}) {
            Path root = Files.createTempDirectory(parent, wide ? "arm64-" : "arm32-").toAbsolutePath();
            try (AndroidEmulator emulator = (wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                    .setRootDir(root.toFile()).addBackendFactory(new Unicorn2Factory(true)).build();
                 RawSyscalls calls = new RawSyscalls(emulator)) {
                AndroidSyscallHandler handler = (AndroidSyscallHandler) emulator.getSyscallHandler();
                handler.addIOResolver(new TargetFacts());
                handler.setGuestCredentials(new GuestCredentials(OptionalInt.of(1000), OptionalInt.of(1000),
                        OptionalInt.empty(), OptionalInt.empty(), Optional.empty()));
                MemoryBlock scratch = emulator.getMemory().malloc(256, true);
                try {
                    UnidbgPointer buffer = scratch.getPointer();
                    buffer.setString(0, "/link/../secret");
                    assertEquals(-13, calls.call(wide ? 48 : 334, IO.AT_FDCWD, buffer.peer, 4));
                    int fd = calls.call(wide ? 56 : 322, IO.AT_FDCWD, buffer.peer, 0, 0);
                    assertTrue(fd >= 0);
                    assertEquals(TARGET.length, calls.call(wide ? 63 : 3, fd, buffer.peer + 64, TARGET.length));
                    assertArrayEquals(TARGET, buffer.getByteArray(64, TARGET.length));
                    assertEquals(0, calls.call(wide ? 57 : 6, fd));
                    assertThrows(UnsupportedOperationException.class,
                            () -> handler.checkAccessAt(emulator, IO.AT_FDCWD, "/notarget/secret", 0));
                    assertThrows(UnsupportedOperationException.class,
                            () -> handler.open(emulator, "/notarget/secret", 0));
                } finally {
                    scratch.free();
                }
            }
        }
    }

    private static final class TargetFacts implements IOResolver<AndroidFileIO> {
        @Override
        public FileResult<AndroidFileIO> resolve(Emulator<AndroidFileIO> emulator, String path, int oflags) {
            if ("/vault/secret".equals(path)) return FileResult.success(new ByteArrayFileIO(oflags, path, TARGET));
            if ("/secret".equals(path) || "/link/../secret".equals(path) || "/link".equals(path)) {
                return FileResult.success(new ByteArrayFileIO(oflags, path, DECOY));
            }
            return null;
        }

        @Override
        public FileQueryResult<FileMetadata> queryMetadata(Emulator<AndroidFileIO> emulator, String path, boolean followLinks) {
            if ("/".equals(path) || "/vault".equals(path) || "/vault/dir".equals(path)) {
                return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFDIR | 0755).build());
            }
            if ("/link".equals(path)) {
                return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFLNK | 0777).linkTarget("vault/dir").build());
            }
            if ("/vault/secret".equals(path)) {
                return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFREG | 0400).uid(1).gid(1).build());
            }
            if ("/notarget".equals(path)) {
                return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFLNK | 0777).build());
            }
            if ("/secret".equals(path) || "/link/../secret".equals(path) || "/notarget/secret".equals(path)) {
                return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFREG | 0777).build());
            }
            return null;
        }

        @Override
        public FileQueryResult<java.util.List<com.github.unidbg.file.DirectoryEntry>> queryDirectory(
                Emulator<AndroidFileIO> emulator, String path) {
            return null;
        }
    }

    private static final class RawSyscalls implements AutoCloseable {
        private final AndroidEmulator emulator;
        private final UnidbgPointer code;

        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(8192, 7);
            for (int number : new int[] {3, 6, 48, 56, 57, 63, 322, 334}) {
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
