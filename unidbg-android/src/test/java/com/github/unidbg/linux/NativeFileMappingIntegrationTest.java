package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.HostMemoryBackend;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.mapping.LinuxMappingSource;
import com.github.unidbg.memory.mapping.MappedFileRegion;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import unicorn.Arm64Const;
import unicorn.ArmConst;
import unicorn.UnicornConst;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class NativeFileMappingIntegrationTest {

    private static final int PAGE = 4096;
    private static final int RW = UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_WRITE;
    private final boolean is64Bit;

    @Parameterized.Parameters(name = "ARM{0}")
    public static Collection<Object[]> architectures() {
        return Arrays.asList(new Object[][] {{32}, {64}});
    }

    public NativeFileMappingIntegrationTest(int bits) {
        is64Bit = bits == 64;
    }

    // Linux mmap(2), msync(2), close(2): actual guest stores and independent file reads.
    @Test
    public void hostBackedMappings() throws Exception {
        Path output = Paths.get("target/native-file-mapping");
        Files.createDirectories(output);
        Path directory = Files.createTempDirectory(output, is64Bit ? "arm64-" : "arm32-");
        Path opened = directory.resolve("opened.bin").toAbsolutePath();
        Path retained = directory.resolve("retained.bin").toAbsolutePath();
        byte[] expected = new byte[2 * PAGE + 19];
        Arrays.fill(expected, 0, PAGE, (byte) 0x31);
        Arrays.fill(expected, PAGE, 2 * PAGE, (byte) 0x72);
        Arrays.fill(expected, 2 * PAGE, expected.length, (byte) 0x19);
        Files.write(opened, expected);
        try (LinuxMappingSource source = LinuxMappingSource.open(opened, true);
             RandomAccessFile file = new RandomAccessFile(opened.toFile(), "rw")) {
            file.seek(27);
            Files.move(opened, retained);
            byte[] replacement = new byte[PAGE];
            Arrays.fill(replacement, (byte) 0x22);
            Files.write(opened, replacement);
            List<String> originalMappings = mappings(retained);
            try (LinuxMappingSource readonly = LinuxMappingSource.open(retained, false);
                 MappedFileRegion shared = source.map(0, 2L * PAGE, true);
                 MappedFileRegion alias = source.map(PAGE, PAGE, true);
                 MappedFileRegion privateRegion = readonly.map(PAGE, PAGE, false);
                 MappedFileRegion tail = source.map(2L * PAGE, PAGE, true);
                 AndroidEmulator emulator = (is64Bit ? AndroidEmulatorBuilder.for64Bit() :
                         AndroidEmulatorBuilder.for32Bit()).addBackendFactory(new Unicorn2Factory(false)).build()) {
                assertEquals(27, file.getFilePointer());
                source.close();
                readonly.close();
                Backend backend = emulator.getBackend();
                HostMemoryBackend host = (HostMemoryBackend) backend;
                long first = 0x40000000L;
                long second = 0x41000000L;
                long isolated = 0x42000000L;
                ByteBuffer sharedBuffer = shared.buffer();
                sharedBuffer.position(13);
                sharedBuffer.limit(PAGE);
                host.mem_map_host(first, sharedBuffer, RW);
                host.mem_map_host(second, alias.buffer(), RW);
                host.mem_map_host(isolated, privateRegion.buffer(), RW);
                host.mem_map_host(0x44000000L, tail.buffer(), RW);
                backend.mem_map(0x43000000L, PAGE, UnicornConst.UC_PROT_ALL);
                // STRB W1,[X0] / STRB R1,[R0], with no dependence on a guest libc.
                backend.mem_write(0x43000000L, is64Bit ? new byte[] {1, 0, 0, 0x39} :
                        new byte[] {0, 0x10, (byte) 0xc0, (byte) 0xe5});
                byte[] tailPage = new byte[PAGE];
                Arrays.fill(tailPage, 0, 19, (byte) 0x19);
                assertArrayEquals(tailPage, backend.mem_read(0x44000000L, PAGE));
                store(backend, 0x44000000L + 100, 0x55);
                tail.sync(0, PAGE, MappedFileRegion.MS_SYNC);
                assertArrayEquals(expected, Files.readAllBytes(retained));
                store(backend, isolated + 7, 0xc7);
                privateRegion.sync(0, PAGE, MappedFileRegion.MS_SYNC);
                assertArrayEquals(expected, Files.readAllBytes(retained));
                assertArrayEquals(new byte[] {(byte) 0xc7}, backend.mem_read(isolated + 7, 1));

                store(backend, first + PAGE + 9, 0x5e);
                expected[PAGE + 9] = 0x5e;
                assertArrayEquals(new byte[] {0x5e}, backend.mem_read(second + 9, 1));
                assertArrayEquals(expected, Files.readAllBytes(retained));
                file.seek(PAGE + 11);
                file.write(0x6f);
                expected[PAGE + 11] = 0x6f;
                assertArrayEquals(new byte[] {0x6f}, backend.mem_read(first + PAGE + 11, 1));
                assertArrayEquals(new byte[] {0x6f}, backend.mem_read(second + 11, 1));
                shared.sync(PAGE, PAGE, MappedFileRegion.MS_SYNC);
                assertEquals(PAGE + 12, file.getFilePointer());
                assertArrayEquals(expected, Files.readAllBytes(retained));
                assertArrayEquals(replacement, Files.readAllBytes(opened));

                backend.mem_unmap(first, PAGE);
                backend.mem_protect(first + PAGE, PAGE, UnicornConst.UC_PROT_READ);
                store(backend, second + 13, 0x7b);
                expected[PAGE + 13] = 0x7b;
                assertArrayEquals(new byte[] {0x7b}, backend.mem_read(first + PAGE + 13, 1));
            }
            assertEquals(originalMappings, mappings(retained));
            assertArrayEquals(expected, Files.readAllBytes(retained));
        }
        assertEquals(0, descriptors(retained));
    }

    private void store(Backend backend, long address, int value) {
        backend.reg_write(is64Bit ? Arm64Const.UC_ARM64_REG_X0 : ArmConst.UC_ARM_REG_R0, address);
        backend.reg_write(is64Bit ? Arm64Const.UC_ARM64_REG_X1 : ArmConst.UC_ARM_REG_R1, value);
        backend.emu_start(0x43000000L, 0x43000004L, 0, 1);
    }

    private static List<String> mappings(Path file) throws Exception {
        return Files.readAllLines(Paths.get("/proc/self/maps")).stream()
                .filter(line -> line.endsWith(" " + file)).collect(Collectors.toList());
    }

    private static long descriptors(Path file) throws Exception {
        try (Stream<Path> entries = Files.list(Paths.get("/proc/self/fd"))) {
            long count = 0;
            for (Path fd : entries.collect(Collectors.toList())) {
                if (Files.readSymbolicLink(fd).toString().equals(file.toString())) {
                    count++;
                }
            }
            return count;
        }
    }
}
