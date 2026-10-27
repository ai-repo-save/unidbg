package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.file.seqpacket.NativeSeqpacketSocketFactory;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class NativeSeqpacketTransferIntegrationTest {
    // Linux unix(7), dup(2), recv(2), close(2): duplicates share the endpoint, not its descriptor lifetime.
    @Test
    public void crossEmulatorLifecycle() throws Exception {
        Set<String> originalSockets = sockets();
        ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
        NativeSeqpacketSocketFactory sourceSockets = new NativeSeqpacketSocketFactory();
        try (AndroidEmulator source = emulator()) {
            AndroidFileIO[] pair = sourceSockets.createPair(source);
            source.getSyscallHandler().addFileIO(pair[0]);
            source.getSyscallHandler().addFileIO(pair[1]);
            byte[] request = {0x13, 0x57, (byte) 0x9b, (byte) 0xdf};
            byte[] response = {0x24, 0x68, (byte) 0xac, (byte) 0xe0};
            NativeSeqpacketSocketFactory targetSockets = new NativeSeqpacketSocketFactory();
            try (AndroidEmulator target = emulator()) {
                AndroidFileIO transferred = targetSockets.duplicateFrom(pair[0], target);
                target.getSyscallHandler().addFileIO(transferred);
                UnidbgPointer timeout = target.getSvcMemory().allocate(16, "receive-timeout");
                timeout.setLong(0, 1);
                timeout.setLong(8, 0);
                assertEquals(0, transferred.setsockopt(1, 20, timeout, 16));
                UnidbgPointer buffer = target.getSvcMemory().allocate(request.length, "received-packet");
                AtomicReference<ScheduledFuture<Integer>> sent = new AtomicReference<>();
                UnidbgPointer receive = target.getSvcMemory().registerSvc(new Arm64Svc() {
                    @Override public long handle(Emulator<?> current) {
                        sent.set(sender.schedule(() -> pair[1].write(request), 20, TimeUnit.MILLISECONDS));
                        return transferred.read(current.getBackend(), buffer, request.length);
                    }
                });
                assertEquals(request.length, Module.emulateFunction(target, receive.peer).intValue());
                assertEquals(Integer.valueOf(request.length), sent.get().get(1, TimeUnit.SECONDS));
                assertArrayEquals(request, buffer.getByteArray(0, request.length));
                assertEquals(response.length, transferred.write(response));
                UnidbgPointer received = source.getSvcMemory().allocate(response.length, "received-response");
                assertEquals(response.length, pair[1].read(source.getBackend(), received, response.length));
                assertArrayEquals(response, received.getByteArray(0, response.length));
            } finally {
                targetSockets.destroy();
            }
            assertEquals(request.length, pair[1].write(request));
            UnidbgPointer received = source.getSvcMemory().allocate(request.length, "surviving-endpoint");
            assertEquals(request.length, pair[0].read(source.getBackend(), received, request.length));
            assertArrayEquals(request, received.getByteArray(0, request.length));
        } finally {
            sourceSockets.destroy();
            sender.shutdownNow();
            if (!sender.awaitTermination(1, TimeUnit.SECONDS)) throw new IllegalStateException("Socket sender did not stop");
        }
        assertEquals(originalSockets, sockets());
    }

    private static AndroidEmulator emulator() {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(false)).build();
        emulator.getSyscallHandler().setEnableThreadDispatcher(true);
        return emulator;
    }

    private static Set<String> sockets() throws IOException {
        Set<String> sockets = new TreeSet<>();
        try (Stream<Path> entries = Files.list(Paths.get("/proc/self/fd"))) {
            for (Path entry : entries.collect(Collectors.toList())) {
                String target = Files.readSymbolicLink(entry).toString();
                if (target.startsWith("socket:[")) sockets.add(entry.getFileName() + "=" + target);
            }
        }
        return sockets;
    }
}
