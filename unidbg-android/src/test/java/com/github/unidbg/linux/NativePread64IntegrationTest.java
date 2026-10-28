package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class NativePread64IntegrationTest {
    private static final byte[] HEAD = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final long LOW_SIGN_BIT_OFFSET = 0x80000003L;
    private static final long HIGH_WORD_OFFSET = 0x100000005L;

    @Test
    public void bionicPositionedReadsAndUnknownSyscall() throws Exception {
        for (boolean arm64 : new boolean[] {true, false}) {
            String property = "unidbg.nativePread64Library" + (arm64 ? "64" : "32");
            String library = System.getProperty(property);
            if (library == null) {
                throw new IllegalArgumentException("Required property: -D" + property + "=<native fixture>");
            }
            Path directory = Paths.get("target/native-pread64");
            Files.createDirectories(directory);
            Path root = Files.createTempDirectory(directory, arm64 ? "arm64-" : "arm32-").toAbsolutePath();
            Path file = root.resolve("pread.bin");
            try (RandomAccessFile host = new RandomAccessFile(file.toFile(), "rw")) {
                host.write(HEAD);
                host.seek(LOW_SIGN_BIT_OFFSET);
                host.write(new byte[] {0x55, 0x66});
                host.seek(HIGH_WORD_OFFSET);
                host.write(new byte[] {0x77, (byte) 0x88});
            }
            try (AndroidEmulator emulator = (arm64 ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                    .setRootDir(root.toFile()).addBackendFactory(new Unicorn2Factory(true)).build()) {
                emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
                Module probe = emulator.loadLibrary(new File(library), false);
                assertEquals(0, probe.callFunction(emulator, "pread64_probe", "/pread.bin").intValue());
                // Linux pread(2) leaves the open file description's current offset unchanged.
                assertArrayEquals(new int[] {2, 3, 2, 2, 2, 2, 4, 0, 0, -1, 22, -1, 9, -1, 9, -1, 38, 4, 0, 0},
                        probe.findSymbolByName("pread64_results").createPointer(emulator).getIntArray(0, 20));
                assertArrayEquals(new byte[] {1, 2, 5, 6, 7, 3, 4, 0x55, 0x66, 0x77, (byte) 0x88},
                        probe.findSymbolByName("pread64_bytes").createPointer(emulator).getByteArray(0, 11));
            }
            try (RandomAccessFile host = new RandomAccessFile(file.toFile(), "r")) {
                assertEquals(HIGH_WORD_OFFSET + 2, host.length());
                byte[] head = new byte[HEAD.length];
                host.readFully(head);
                assertArrayEquals(HEAD, head);
                byte[] bytes = new byte[2];
                host.seek(LOW_SIGN_BIT_OFFSET);
                host.readFully(bytes);
                assertArrayEquals(new byte[] {0x55, 0x66}, bytes);
                host.seek(HIGH_WORD_OFFSET);
                host.readFully(bytes);
                assertArrayEquals(new byte[] {0x77, (byte) 0x88}, bytes);
            }
        }
    }
}
