package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.file.ByteArrayFileIO;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class SmallFileDataIntegrationTest {
    @Test
    public void bionicDirectoryAndMemoryFileReadsMatchHostFiles() throws Exception {
        for (boolean arm64 : new boolean[] {true, false}) {
            String property = "unidbg.smallFileDataLibrary" + (arm64 ? "64" : "32");
            String library = System.getProperty(property);
            if (library == null) {
                throw new IllegalArgumentException("Required property: -D" + property + "=<native fixture>");
            }
            Path output = Paths.get("target/small-file-data");
            Files.createDirectories(output);
            Path root = Files.createTempDirectory(output, arm64 ? "arm64-" : "arm32-").toAbsolutePath();
            Path directory = Files.createDirectory(root.resolve("entries"));
            Files.createFile(directory.resolve("abcd"));
            Files.createFile(directory.resolve("abcde"));
            Files.createFile(directory.resolve("éé"));
            Files.createDirectory(directory.resolve("汉字a"));
            List<String> expectedNames;
            try (Stream<Path> children = Files.list(directory)) {
                expectedNames = children.map(path -> path.getFileName().toString()).collect(Collectors.toList());
            }
            expectedNames.add(".");
            expectedNames.add("..");
            Collections.sort(expectedNames);
            Path file = root.resolve("source.bin");
            Files.write(file, "actual host file contents\n".getBytes(StandardCharsets.UTF_8));
            byte[] source = Files.readAllBytes(file);
            try (AndroidEmulator emulator = (arm64 ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                    .setRootDir(root.toFile()).addBackendFactory(new Unicorn2Factory(true)).build()) {
                emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
                emulator.getSyscallHandler().addIOResolver((emu, path, flags) ->
                        "/memory.bin".equals(path) ? FileResult.success(new ByteArrayFileIO(flags, path, source)) : null);
                Module probe = emulator.loadLibrary(new File(library), false);
                assertEquals(0, probe.callFunction(emulator, "directory_probe", "/entries").intValue());
                int[] directoryResults = probe.findSymbolByName("directory_results").createPointer(emulator).getIntArray(0, 6);
                assertEquals(-1, directoryResults[0]);
                assertEquals(22, directoryResults[1]);
                assertEquals(24, directoryResults[2]);
                assertEquals(0, directoryResults[3]);
                assertEquals(0, directoryResults[5]);
                Pointer records = probe.findSymbolByName("directory_bytes").createPointer(emulator);
                byte[] data = records.getByteArray(0, directoryResults[4]);
                assertEquals(Collections.singletonList("."), decodeDirectory(Arrays.copyOf(data, directoryResults[2])));
                List<String> actualNames = decodeDirectory(data);
                Collections.sort(actualNames);
                assertEquals(expectedNames, actualNames);

                // The same Bionic probe reads the host-backed file and its in-memory snapshot.
                for (String path : new String[] {"/source.bin", "/memory.bin"}) {
                    assertEquals(0, probe.callFunction(emulator, "file_probe", path, source.length).intValue());
                    assertArrayEquals(new int[] {2, 3, 0, -1, 22, 0, 2, 1, 4, 0},
                            probe.findSymbolByName("file_results").createPointer(emulator).getIntArray(0, 10));
                    byte[] host = Files.readAllBytes(file);
                    assertArrayEquals(new byte[] {host[0], host[1], host[4], host[5], host[6], host[2], host[3], host[host.length - 1]},
                            probe.findSymbolByName("file_bytes").createPointer(emulator).getByteArray(0, 8));
                    assertArrayEquals(source, host);
                }
            }
        }
    }

    private static List<String> decodeDirectory(byte[] bytes) {
        List<String> names = new ArrayList<>();
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 0;
        while (offset < bytes.length) {
            // Linux getdents(2): linux_dirent64.d_name starts at byte 19.
            int recordLength = Short.toUnsignedInt(data.getShort(offset + 16));
            int end = offset + 19;
            while (bytes[end] != 0) {
                end++;
            }
            int nameLength = end - offset - 19;
            assertEquals((19 + nameLength + 1 + 7) & ~7, recordLength);
            names.add(new String(bytes, offset + 19, nameLength, StandardCharsets.UTF_8));
            offset += recordLength;
        }
        assertEquals(bytes.length, offset);
        return names;
    }
}
