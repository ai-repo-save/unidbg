package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.HostMemoryBackend;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.file.linux.IOConstants;
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
import static org.junit.Assert.assertThrows;

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
        Path syscallRetained = directory.resolve("syscall-retained.bin").toAbsolutePath();
        byte[] syscallExpected;
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
                try (LinuxMappingSource secondOpen = LinuxMappingSource.open(retained, true);
                     LinuxMappingSource replacementSource = LinuxMappingSource.open(opened, true)) {
                    assertThrows(UnsupportedOperationException.class, () -> secondOpen.truncate(PAGE));
                    assertArrayEquals(expected, Files.readAllBytes(retained));
                    secondOpen.truncate(2L * PAGE + 7);
                    expected = Arrays.copyOf(expected, 2 * PAGE + 7);
                    assertArrayEquals(new byte[12], backend.mem_read(0x44000000L + 7, 12));
                    assertArrayEquals(expected, Files.readAllBytes(retained));
                    secondOpen.truncate(2L * PAGE + 19);
                    expected = Arrays.copyOf(expected, 2 * PAGE + 19);
                    assertArrayEquals(expected, Files.readAllBytes(retained));
                    assertEquals(27, file.getFilePointer());
                    replacementSource.truncate(0);
                    replacement = new byte[0];
                    assertArrayEquals(replacement, Files.readAllBytes(opened));
                }
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
                syscallExpected = syscallMappings(emulator, directory);
            }
            assertEquals(originalMappings, mappings(retained));
            assertEquals(Arrays.asList(), mappings(syscallRetained));
            assertEquals(0, descriptors(syscallRetained));
            Path sparse = directory.resolve("large-offset.bin").toAbsolutePath();
            assertEquals(Arrays.asList(), mappings(sparse));
            assertEquals(0, descriptors(sparse));
            assertArrayEquals(syscallExpected, Files.readAllBytes(syscallRetained));
            assertArrayEquals(expected, Files.readAllBytes(retained));
        }
        assertEquals(0, descriptors(retained));
        try (LinuxMappingSource released = LinuxMappingSource.open(retained, true)) {
            released.truncate(0);
        }
        assertArrayEquals(new byte[0], Files.readAllBytes(retained));
    }

    private byte[] syscallMappings(AndroidEmulator emulator, Path directory) throws Exception {
        Path original = directory.resolve("syscall.bin").toAbsolutePath();
        Path retained = directory.resolve("syscall-retained.bin").toAbsolutePath();
        byte[] expected = new byte[2 * PAGE + 19];
        Arrays.fill(expected, (byte) 0x31);
        Files.write(original, expected);
        int fd = emulator.getSyscallHandler().addFileIO(new SimpleFileIO(IOConstants.O_RDWR,
                original.toFile(), "/mapping/syscall.bin"));
        assertEquals(0, fd);
        Files.move(original, retained);
        byte[] replacement = new byte[PAGE];
        Arrays.fill(replacement, (byte) 0x22);
        Files.write(original, replacement);
        int readonly = emulator.getSyscallHandler().addFileIO(new SimpleFileIO(IOConstants.O_RDONLY,
                retained.toFile(), "/mapping/readonly.bin"));
        Backend backend = emulator.getBackend();
        backend.mem_write(0x43000100L, is64Bit ? new byte[] {1, 0, 0, (byte) 0xd4} :
                new byte[] {0, 0, 0, (byte) 0xef});
        assertEquals(27, syscall(backend, 19, 62, fd, 27, 0));
        long first = 0x45000000L, alias = 0x46000000L, isolated = 0x47000000L;
        long readonlyAlias = 0x48000000L;
        assertEquals(first, map(backend, first, 3 * PAGE, RW, 0x11, fd, 0));
        assertEquals(alias, map(backend, alias, 2 * PAGE, RW, 0x11, fd, PAGE));
        assertEquals(isolated, map(backend, isolated, PAGE, RW, 0x12, readonly, PAGE));
        assertEquals(readonlyAlias, map(backend, readonlyAlias, PAGE, UnicornConst.UC_PROT_READ,
                0x11, readonly, PAGE));
        assertEquals(-13, map(backend, 0x49000000L, PAGE, RW, 0x11, readonly, PAGE));
        assertEquals(27, syscall(backend, 19, 62, fd, 0, 1));
        store(backend, isolated + 7, 0xc7);
        assertEquals(0, syscall(backend, 144, 227, isolated, PAGE, MappedFileRegion.MS_SYNC));
        assertArrayEquals(new byte[] {(byte) 0xc7}, backend.mem_read(isolated + 7, 1));
        assertArrayEquals(expected, Files.readAllBytes(retained));
        assertEquals(0, syscall(backend, 220, 233, isolated, PAGE, 4));
        assertArrayEquals(new byte[] {0x31}, backend.mem_read(isolated + 7, 1));
        assertArrayEquals(expected, Files.readAllBytes(retained));
        store(backend, first + PAGE + 9, 0x5e);
        expected[PAGE + 9] = 0x5e;
        assertArrayEquals(new byte[] {0x5e}, backend.mem_read(alias + 9, 1));
        assertArrayEquals(new byte[] {0x5e}, backend.mem_read(readonlyAlias + 9, 1));
        assertArrayEquals(expected, Files.readAllBytes(retained));
        assertEquals(PAGE + 11, syscall(backend, 19, 62, fd, PAGE + 11, 0));
        backend.mem_write(0x43000300L, new byte[] {0x6f});
        assertEquals(1, syscall(backend, 4, 64, fd, 0x43000300L, 1));
        expected[PAGE + 11] = 0x6f;
        assertArrayEquals(new byte[] {0x6f}, backend.mem_read(alias + 11, 1));
        assertArrayEquals(expected, Files.readAllBytes(retained));
        assertEquals(0, syscall(backend, 125, 226, first + PAGE, PAGE, UnicornConst.UC_PROT_READ));
        assertEquals(0, syscall(backend, 220, 233, first + PAGE, PAGE, 4));
        assertArrayEquals(new byte[] {0x5e}, backend.mem_read(first + PAGE + 9, 1));
        assertArrayEquals(expected, Files.readAllBytes(retained));
        assertEquals(0, syscall(backend, 144, 227, first + PAGE, PAGE, MappedFileRegion.MS_SYNC));
        assertEquals(-22, syscall(backend, 144, 227, first, PAGE,
                MappedFileRegion.MS_SYNC | MappedFileRegion.MS_ASYNC));
        assertThrows(UnsupportedOperationException.class, () -> syscall(backend, 93, 46, fd, PAGE));
        assertArrayEquals(expected, Files.readAllBytes(retained));
        assertEquals(0, syscall(backend, 93, 46, fd, 2L * PAGE + 7));
        expected = Arrays.copyOf(expected, 2 * PAGE + 7);
        assertArrayEquals(new byte[12], backend.mem_read(first + 2 * PAGE + 7, 12));
        assertArrayEquals(expected, Files.readAllBytes(retained));
        assertEquals(0, syscall(backend, 6, 57, fd));
        assertEquals(0, syscall(backend, 6, 57, readonly));
        store(backend, alias + 13, 0x7b);
        expected[PAGE + 13] = 0x7b;
        assertArrayEquals(new byte[] {0x7b}, backend.mem_read(first + PAGE + 13, 1));
        assertEquals(0, syscall(backend, 91, 215, first + PAGE, PAGE));
        store(backend, alias + PAGE + 3, 0x44);
        expected[2 * PAGE + 3] = 0x44;
        assertEquals(0, syscall(backend, 144, 227, first + 2 * PAGE, PAGE, MappedFileRegion.MS_SYNC));
        assertEquals(-12, syscall(backend, 144, 227, first, 3 * PAGE, MappedFileRegion.MS_SYNC));
        assertEquals(first + 2 * PAGE, map(backend, first + 2 * PAGE, PAGE, RW, 0x32, -1, 0));
        assertArrayEquals(new byte[PAGE], backend.mem_read(first + 2 * PAGE, PAGE));
        assertArrayEquals(expected, Files.readAllBytes(retained));
        assertArrayEquals(replacement, Files.readAllBytes(original));
        largeOffsetMapping(emulator, directory);
        return expected;
    }

    private void largeOffsetMapping(AndroidEmulator emulator, Path directory) throws Exception {
        Path file = directory.resolve("large-offset.bin").toAbsolutePath();
        long offset = (1L << 32) + PAGE;
        try (RandomAccessFile sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength(offset + PAGE);
            sparse.seek(offset + 17);
            sparse.write(0x6a);
        }
        int fd = emulator.getSyscallHandler().addFileIO(new SimpleFileIO(IOConstants.O_RDWR,
                file.toFile(), "/mapping/large-offset.bin"));
        Backend backend = emulator.getBackend();
        long address = 0x4a000000L;
        assertEquals(address, map(backend, address, PAGE, RW, 0x11, fd, offset));
        assertArrayEquals(new byte[] {0x6a}, backend.mem_read(address + 17, 1));
        store(backend, address + 18, 0x5b);
        assertEquals(0, syscall(backend, 144, 227, address, PAGE, MappedFileRegion.MS_SYNC));
        assertEquals(0, syscall(backend, 6, 57, fd));
        try (RandomAccessFile witness = new RandomAccessFile(file.toFile(), "r")) {
            witness.seek(offset + 17);
            byte[] actual = new byte[2];
            witness.readFully(actual);
            assertArrayEquals(new byte[] {0x6a, 0x5b}, actual);
        }
    }

    private long map(Backend backend, long address, int length, int prot, int flags, int fd, long offset) {
        return syscall(backend, 192, 222, address, length, prot, flags, fd, is64Bit ? offset : offset >>> 12);
    }

    private long syscall(Backend backend, int arm32, int arm64, long... args) {
        int[] registers = is64Bit ? new int[] {Arm64Const.UC_ARM64_REG_X0, Arm64Const.UC_ARM64_REG_X1,
                Arm64Const.UC_ARM64_REG_X2, Arm64Const.UC_ARM64_REG_X3, Arm64Const.UC_ARM64_REG_X4,
                Arm64Const.UC_ARM64_REG_X5} : new int[] {ArmConst.UC_ARM_REG_R0, ArmConst.UC_ARM_REG_R1,
                ArmConst.UC_ARM_REG_R2, ArmConst.UC_ARM_REG_R3, ArmConst.UC_ARM_REG_R4, ArmConst.UC_ARM_REG_R5};
        for (int i = 0; i < args.length; i++) {
            backend.reg_write(registers[i], args[i]);
        }
        backend.reg_write(is64Bit ? Arm64Const.UC_ARM64_REG_X8 : ArmConst.UC_ARM_REG_R7, is64Bit ? arm64 : arm32);
        backend.emu_start(0x43000100L, 0x43000104L, 0, 1);
        Number result = backend.reg_read(registers[0]);
        return is64Bit ? result.longValue() : result.intValue();
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
