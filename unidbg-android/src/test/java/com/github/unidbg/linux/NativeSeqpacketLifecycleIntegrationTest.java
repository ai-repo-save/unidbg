package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NativeSeqpacketLifecycleIntegrationTest {
    // Linux unix(7), recv(2), socket(7), dup(2), close(2); actual SDK23 Bionic pthread ABI.
    @Test
    public void nativeLifecycle() throws Exception {
        String libraryPath = System.getProperty("unidbg.nativeIpcLibrary");
        if (libraryPath == null || libraryPath.isEmpty()) {
            throw new IllegalArgumentException("Required property: -Dunidbg.nativeIpcLibrary=<ARM64 native fixture>");
        }
        File library = new File(libraryPath);
        Set<String> originalSockets = sockets();
        Path marker = Paths.get("target/native-ipc/destroy-listener.txt");
        Files.createDirectories(marker.getParent());
        Files.write(marker, new byte[0]);
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(true)).build()) {
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
            emulator.getSyscallHandler().setEnableThreadDispatcher(true);
            Module module = emulator.loadLibrary(library, false);
            Pointer progress = module.findSymbolByName("ipc_progress").createPointer(emulator);
            Pointer results = module.findSymbolByName("ipc_results").createPointer(emulator);
            Pointer bytes = module.findSymbolByName("ipc_bytes").createPointer(emulator);
            assertEquals(0, module.callFunction(emulator, "ipc_start").intValue());
            assertEquals(0, progress.getInt(0));
            long start = System.nanoTime();
            emulator.getThreadDispatcher().runThreads(1, TimeUnit.SECONDS);
            assertTrue("SO_RCVTIMEO must wait for the guest's 40ms", System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(40));
            assertEquals(1, progress.getInt(0));
            assertArrayEquals(new int[] {4, 4, 8, 2, 3, -1, 11, 4, 0, 1024, 4, 0, -1, 32, 0, 1, 1, 0}, results.getIntArray(0, 18));
            assertArrayEquals(new byte[] {
                    0x13, 0x57, (byte) 0x9b, (byte) 0xdf, 0x13, 0x57, (byte) 0x9b, (byte) 0xdf,
                    0x13, 0x57, (byte) 0x9b, (byte) 0xdf, 1, 2, 3, 4, 5, 6, 11, 12, 13,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
            }, bytes.getByteArray(0, 32));
            assertEquals(33, module.callFunction(emulator, "ipc_main_errno").intValue());
            assertEquals(0, emulator.getThreadDispatcher().getTaskCount());
            assertEquals(originalSockets, sockets());

            assertEquals(0, module.callFunction(emulator, "ipc_wait_start").intValue());
            emulator.getThreadDispatcher().runThreads(1, TimeUnit.MILLISECONDS);
            assertEquals(0, module.callFunction(emulator, "ipc_close_reader").intValue());
            assertEquals(4, module.callFunction(emulator, "ipc_release_reader").intValue());
            emulator.getThreadDispatcher().runThreads(1, TimeUnit.SECONDS);
            assertEquals(4, results.getInt(17 * 4L));
            assertArrayEquals(new byte[] {0x78, 0x56, 0x34, 0x12}, bytes.getByteArray(28, 4));
            assertEquals(originalSockets, sockets());

            UnidbgPointer ready = emulator.getSvcMemory().registerSvc(new Arm64Svc() {
                @Override
                public long handle(Emulator<?> current) {
                    current.getThreadDispatcher().getRunningTask().setDestroyListener(ignored -> {
                        try {
                            Files.write(marker, "destroyed\n".getBytes(StandardCharsets.US_ASCII), StandardOpenOption.APPEND);
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    });
                    return 0;
                }
            });
            assertEquals(4, module.callFunction(emulator, "ipc_main_wait", ready).intValue());
            emulator.getThreadDispatcher().runThreads(1, TimeUnit.SECONDS);
            assertEquals("destroyed\n", new String(Files.readAllBytes(marker), StandardCharsets.US_ASCII));
            assertEquals(originalSockets, sockets());

            assertEquals(0, module.callFunction(emulator, "ipc_send_timeout").intValue());
            assertArrayEquals(new int[] {-1, 11}, module.findSymbolByName("ipc_send_timeout_result")
                    .createPointer(emulator).getIntArray(0, 2));
            assertEquals(11, module.callFunction(emulator, "ipc_main_errno").intValue());
            assertEquals(originalSockets, sockets());

            assertEquals(0, module.callFunction(emulator, "ipc_wait_start").intValue());
            emulator.getThreadDispatcher().runThreads(1, TimeUnit.MILLISECONDS);
        }
        assertEquals(originalSockets, sockets());
    }

    private static Set<String> sockets() throws IOException {
        Set<String> sockets = new TreeSet<>();
        try (Stream<Path> entries = Files.list(Paths.get("/proc/self/fd"))) {
            for (Path entry : entries.collect(Collectors.toList())) {
                String target = Files.readSymbolicLink(entry).toString();
                if (target.startsWith("socket:[")) {
                    sockets.add(entry.getFileName() + "=" + target);
                }
            }
        }
        return sockets;
    }
}
