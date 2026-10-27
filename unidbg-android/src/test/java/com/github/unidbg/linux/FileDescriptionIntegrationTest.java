package com.github.unidbg.linux;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.linux.file.Stdout;
import com.github.unidbg.linux.file.LogCatFileIO;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.memory.mapping.MappedFileRegion;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

/** Linux dup(2), fcntl(2), read(2): compiled Bionic calls plus independent file reads. */
public class FileDescriptionIntegrationTest {
    @Test
    public void nativeAliasesAndBackingLifetime() throws Exception {
        String library = System.getProperty("unidbg.nativeEvaluationLibrary");
        if (library == null) throw new IllegalArgumentException("Required property: -Dunidbg.nativeEvaluationLibrary=<ARM64 probe>");
        Path directory = Paths.get("target/file-description");
        Files.createDirectories(directory);
        Path workspace = Files.createTempDirectory(directory, "native-").toAbsolutePath();
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setRootDir(workspace.toFile()).addBackendFactory(new Unicorn2Factory(false)).build()) {
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
            emulator.getSyscallHandler().setEnableThreadDispatcher(true);
            Module probe = emulator.loadLibrary(new File(library), false);
            MemoryBlock output = emulator.getMemory().malloc(65536, true);
            try {
                int length = probe.findSymbolByName("runtime_eval_native").call(emulator,
                        "native-fd", "/", output.getPointer(), 65536).intValue();
                assertTrue(length > 0 && length < 65536);
                JSONObject observations = JSON.parseObject(new String(output.getPointer().getByteArray(0, length),
                        StandardCharsets.UTF_8)).getJSONObject("observations");
                assertEquals(1, observations.getJSONObject("original_descriptor").getIntValue("rc"));
                assertEquals(0, observations.getJSONObject("duplicate_descriptor").getIntValue("rc"));
                assertEquals(0x800, observations.getJSONObject("original_status").getIntValue("rc") & 0x800);
                assertEquals(5, observations.getJSONObject("offset").getIntValue("rc"));
                assertEquals(3, observations.getJSONObject("surviving_write").getIntValue("rc"));
                assertEquals(0, observations.getJSONObject("duplicate_close").getIntValue("rc"));
            } finally { output.free(); }
            assertArrayEquals(new byte[] {0x10, 0x20, 0x30, 0x40, 0x50, (byte)0xa1, (byte)0xb2, (byte)0xc3},
                    Files.readAllBytes(workspace.resolve("native-fd.bin")));
            Path file = workspace.resolve("held.bin"), retained = workspace.resolve("retained.bin");
            Files.write(file, new byte[4096]);
            SimpleFileIO original = new SimpleFileIO(IOConstants.O_RDWR | IOConstants.O_CLOEXEC, file.toFile(), "/held.bin");
            SimpleFileIO alias = (SimpleFileIO)original.dup2();
            try {
                assertEquals(1, original.fcntl(emulator, 1, 0));
                assertEquals(0, alias.fcntl(emulator, 1, 0));
                assertEquals(0, original.fcntl(emulator, 2, 0));
                assertEquals(0, original.fcntl(emulator, 1, 0));
                assertEquals(0, alias.fcntl(emulator, 4, IOConstants.O_NONBLOCK));
                assertEquals(IOConstants.O_NONBLOCK, original.fcntl(emulator, 3, 0) & IOConstants.O_NONBLOCK);
                assertEquals(0, original.fcntl(emulator, 4, 0));
                assertEquals(IOConstants.O_RDWR, alias.fcntl(emulator, 3, 0));
                Files.move(file, retained);
                Files.write(file, new byte[] {0x55});
                assertEquals(4, original.write(new byte[] {1, 2, 3, 4}));
                assertEquals(4, alias.lseek(0, FileIO.SEEK_CUR));
                MemoryBlock buffer = emulator.getMemory().malloc(4, true);
                try {
                    assertEquals(4, alias.pread(emulator.getBackend(), buffer.getPointer(), 4, 0));
                    assertArrayEquals(new byte[] {1, 2, 3, 4}, buffer.getPointer().getByteArray(0, 4));
                    assertEquals(4, original.lseek(0, FileIO.SEEK_CUR));
                } finally { buffer.free(); }
                MappedFileRegion mapping = alias.getMappingSource().map(0, 4096, true);
                try {
                    original.close();
                    assertEquals(2, alias.write(new byte[] {5, 6}));
                    alias.close();
                    mapping.buffer().put(6, (byte)7);
                    mapping.sync(0, 4096, MappedFileRegion.MS_SYNC);
                } finally { mapping.close(); }
            } finally { original.close(); alias.close(); }
            byte[] expected = new byte[4096];
            for (int i = 0; i < 7; ++i) expected[i] = (byte)(i + 1);
            assertArrayEquals(expected, Files.readAllBytes(retained));
            assertArrayEquals(new byte[] {0x55}, Files.readAllBytes(file));
            assertEquals(0, openHandles(retained));
            verifyProcReopen(emulator, retained);
            verifyAppend(workspace);
            verifyCaptureAliases(emulator, workspace);
        }
    }

    private static void verifyProcReopen(AndroidEmulator emulator, Path retained) throws Exception {
        Module libc = emulator.getMemory().findModule("libc.so");
        SimpleFileIO held = new SimpleFileIO(IOConstants.O_RDWR, retained.toFile(), "/held.bin");
        int heldFd = emulator.getSyscallHandler().addFileIO(held);
        try {
            assertEquals(3, held.lseek(3, FileIO.SEEK_SET));
            byte[] before = Files.readAllBytes(retained);
            Set<Path> entriesBefore = workspaceEntries(retained.getParent());
            int[][] rejected = {
                    {IOConstants.O_NOFOLLOW, 40},
                    {IOConstants.O_DIRECTORY, 20},
                    {IOConstants.O_NOFOLLOW | IOConstants.O_DIRECTORY, 20},
                    {IOConstants.O_CREAT | IOConstants.O_EXCL | IOConstants.O_NOFOLLOW, 17}
            };
            for (int[] sample : rejected) {
                assertEquals("proc-fd open flags=" + Integer.toHexString(sample[0]), -1,
                        libc.findSymbolByName("open").call(emulator, "/proc/self/fd/" + heldFd,
                                IOConstants.O_RDWR | 0x200 | sample[0], 0).intValue());
                UnidbgPointer errno = UnidbgPointer.pointer(emulator,
                        libc.findSymbolByName("__errno").call(emulator).longValue());
                assertEquals(sample[1], errno.getInt(0));
                assertEquals(entriesBefore, workspaceEntries(retained.getParent()));
                assertArrayEquals(before, Files.readAllBytes(retained));
                assertEquals(3, held.lseek(0, FileIO.SEEK_CUR));
                assertEquals(0, held.fcntl(emulator, 1, 0));
            }
            assertThrows(UnsupportedOperationException.class, () -> held.reopen(0x200000 | 0x200));
            assertArrayEquals(before, Files.readAllBytes(retained));
            assertEquals(3, held.lseek(0, FileIO.SEEK_CUR));
            assertEquals(0, held.fcntl(emulator, 1, 0));
            int reopenedFd = libc.findSymbolByName("open").call(emulator,
                    "/proc/self/fd/" + heldFd, IOConstants.O_RDWR | IOConstants.O_CLOEXEC, 0).intValue();
            assertTrue(reopenedFd >= 0);
            try {
                FileIO reopened = emulator.getSyscallHandler().getFileIO(reopenedFd);
                assertEquals(1, reopened.fcntl(emulator, 1, 0));
                assertEquals(0, reopened.lseek(0, FileIO.SEEK_CUR));
                assertEquals(0, reopened.fcntl(emulator, 4, IOConstants.O_NONBLOCK));
                assertEquals(IOConstants.O_RDWR, held.fcntl(emulator, 3, 0));
                MemoryBlock buffer = emulator.getMemory().malloc(2, true);
                try {
                    assertEquals(2, reopened.read(emulator.getBackend(), buffer.getPointer(), 2));
                    assertArrayEquals(new byte[] {1, 2}, buffer.getPointer().getByteArray(0, 2));
                } finally { buffer.free(); }
                assertEquals(3, held.lseek(0, FileIO.SEEK_CUR));
            } finally { assertEquals(0, libc.findSymbolByName("close").call(emulator, reopenedFd).intValue()); }
            int truncatedFd = libc.findSymbolByName("open").call(emulator,
                    "/proc/self/fd/" + heldFd, IOConstants.O_RDWR | 0x200, 0).intValue();
            assertTrue(truncatedFd >= 0);
            try {
                assertArrayEquals(new byte[0], Files.readAllBytes(retained));
                assertEquals(3, held.lseek(0, FileIO.SEEK_CUR));
            } finally { assertEquals(0, libc.findSymbolByName("close").call(emulator, truncatedFd).intValue()); }
        } finally { emulator.getSyscallHandler().closeFileIO(heldFd); }
        assertEquals(0, openHandles(retained));
    }

    private static void verifyAppend(Path workspace) throws Exception {
        Path path = workspace.resolve("append.bin"), retained = workspace.resolve("append-retained.bin");
        Files.write(path, new byte[0]);
        SimpleFileIO first = new SimpleFileIO(IOConstants.O_RDWR | IOConstants.O_APPEND, path.toFile(), "/append.bin");
        SimpleFileIO second = new SimpleFileIO(IOConstants.O_RDWR | IOConstants.O_APPEND, path.toFile(), "/append.bin");
        byte[] a = new byte[16], b = new byte[16];
        Arrays.fill(a, (byte)0x31);
        Arrays.fill(b, (byte)0x72);
        ExecutorService writers = Executors.newFixedThreadPool(2);
        try {
            Files.move(path, retained);
            Files.write(path, new byte[] {0x55});
            Future<?> left = writers.submit(() -> { for (int i = 0; i < 200; ++i) assertEquals(16, first.write(a)); });
            Future<?> right = writers.submit(() -> { for (int i = 0; i < 200; ++i) assertEquals(16, second.write(b)); });
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
            byte[] appended = Files.readAllBytes(retained);
            assertEquals(6400, appended.length);
            int aRecords = 0, bRecords = 0;
            for (int i = 0; i < appended.length; i += 16) {
                byte[] record = Arrays.copyOfRange(appended, i, i + 16);
                if (record[0] == a[0]) { assertArrayEquals(a, record); ++aRecords; }
                else { assertArrayEquals(b, record); ++bRecords; }
            }
            assertEquals(200, aRecords);
            assertEquals(200, bRecords);
            assertEquals(16, first.write(a));
            assertEquals(6416, first.lseek(0, FileIO.SEEK_CUR));
            assertEquals(0, first.fcntl(null, 4, 0));
            assertEquals(0, first.lseek(0, FileIO.SEEK_SET));
            assertEquals(16, first.write(a));
            assertEquals(16, first.lseek(0, FileIO.SEEK_CUR));
            System.arraycopy(a, 0, appended, 0, 16);
            byte[] expected = Arrays.copyOf(appended, 6416);
            System.arraycopy(a, 0, expected, 6400, 16);
            assertArrayEquals(expected, Files.readAllBytes(retained));
        } finally {
            writers.shutdownNow();
            try { assertTrue(writers.awaitTermination(10, TimeUnit.SECONDS)); }
            finally { first.close(); second.close(); }
        }
        assertArrayEquals(new byte[] {0x55}, Files.readAllBytes(path));
        assertEquals(0, openHandles(retained));
    }

    private static void verifyCaptureAliases(AndroidEmulator emulator, Path workspace) throws Exception {
        Path capture = workspace.resolve("stdout-capture.bin");
        Files.write(capture, new byte[] {0x55});
        Stdout output = new Stdout(IOConstants.O_WRONLY, capture.toFile(), "stdout", false, (data, err) -> false);
        FileIO alias = output.dup2();
        try {
            assertEquals(2, output.write(new byte[] {1, 2}));
            assertEquals(2, alias.write(new byte[] {3, 4}));
            output.close();
            assertEquals(2, alias.write(new byte[] {5, 6}));
        } finally { output.close(); alias.close(); }
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, Files.readAllBytes(capture));
        assertEquals(0, openHandles(capture));

        Path log = workspace.resolve("log-capture.bin");
        Files.write(log, new byte[0]);
        LogCatFileIO logger = new LogCatFileIO(emulator, IOConstants.O_WRONLY, log.toFile(), "/dev/log/main");
        try {
            Files.write(log, new byte[] {0x10, 0x20});
            FileIO duplicate = logger.dup2();
            try { assertArrayEquals(new byte[] {0x10, 0x20}, Files.readAllBytes(log)); }
            finally { duplicate.close(); }
        } finally { logger.close(); }
        assertEquals(0, openHandles(log));
    }

    private static Set<Path> workspaceEntries(Path workspace) throws Exception {
        try (Stream<Path> entries = Files.walk(workspace)) {
            return entries.collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static long openHandles(Path file) throws Exception {
        try (Stream<Path> descriptors = Files.list(Paths.get("/proc/self/fd"))) {
            long count = 0;
            for (Path descriptor : (Iterable<Path>) descriptors::iterator) {
                if (Files.readSymbolicLink(descriptor).equals(file)) ++count;
            }
            return count;
        }
    }
}
