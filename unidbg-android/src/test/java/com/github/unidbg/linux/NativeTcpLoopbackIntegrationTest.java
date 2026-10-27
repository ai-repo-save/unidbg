package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.memory.MemoryBlock;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NativeTcpLoopbackIntegrationTest {
    private static final byte[] REQUEST = {0x13, 0x57, (byte) 0x9b, (byte) 0xdf};
    private static final byte[] REPLY = {0x24, 0x68, (byte) 0xac, (byte) 0xe0};

    @Test
    public void nativeLoopbackLifecycle() throws Exception {
        // JDK 21 initializes a process-lifetime socket descriptor on the first host socket.
        server(InetAddress.getByName("127.0.0.1")).close();
        for (boolean arm64 : new boolean[] {true, false}) {
            String property = "unidbg.nativeTcpLibrary" + (arm64 ? "64" : "32");
            String library = System.getProperty(property);
            if (library == null) {
                throw new IllegalArgumentException("Required property: -D" + property + "=<native fixture>");
            }
            for (int family : new int[] {2, 10}) {
                lifecycle(arm64, family, new File(library));
            }
        }
    }

    private static void lifecycle(boolean arm64, int family, File library) throws Exception {
        Set<String> baseline = sockets();
        Set<String> originalWorkers = socketWorkers();
        InetAddress loopback = InetAddress.getByName(family == 2 ? "127.0.0.1" : "::1");
        ExecutorService host = Executors.newSingleThreadExecutor();
        try (AndroidEmulator emulator = (arm64 ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                .addBackendFactory(new Unicorn2Factory(true)).build()) {
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
            emulator.getSyscallHandler().setEnableThreadDispatcher(true);
            Module module = emulator.loadLibrary(library, false);
            Pointer results = module.findSymbolByName("tcp_results").createPointer(emulator);
            Pointer bytes = module.findSymbolByName("tcp_bytes").createPointer(emulator);
            int port = module.callFunction(emulator, "tcp_open_server", family).intValue();
            assertTrue("kernel allocated a TCP port", port > 0 && port <= 65535);
            Future<byte[]> peer = host.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(loopback, port), 2000);
                    socket.setSoTimeout(2000);
                    socket.getOutputStream().write(REQUEST, 0, 2);
                    assertArrayEquals(new byte[] {0x7e}, read(socket, 1));
                    socket.getOutputStream().write(REQUEST, 2, 2);
                    byte[] reply = read(socket, 4);
                    assertEquals(-1, socket.getInputStream().read());
                    socket.shutdownOutput();
                    return reply;
                }
            });
            long started = System.nanoTime();
            assertEquals(0, module.callFunction(emulator, "tcp_accept_exchange").intValue());
            assertTrue("SO_RCVTIMEO waited its 40ms budget", System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(40));
            assertArrayEquals(REPLY, peer.get(3, TimeUnit.SECONDS));
            assertArrayEquals(new int[] {2, 2, 4, 4, -1, 11, 0, -1, 32}, results.getIntArray(0, 9));
            assertArrayEquals(new byte[] {0x13, 0x57, 0x13, 0x57, 0x13, 0x57, (byte) 0x9b, (byte) 0xdf}, bytes.getByteArray(0, 8));
            assertEquals(baseline, sockets());

            try (ServerSocket server = server(loopback)) {
                Future<byte[]> client = host.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        byte[] request = read(socket, 4);
                        socket.getOutputStream().write(REPLY);
                        return request;
                    }
                });
                started = System.nanoTime();
                assertEquals(0, module.callFunction(emulator, "tcp_connect_exchange", family, server.getLocalPort()).intValue());
                assertTrue("poll waited its 20ms budget", System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(20));
                assertArrayEquals(new int[] {2, 0, 1, 1, 0, 0, 0, 0},
                        module.findSymbolByName("tcp_readiness_results").createPointer(emulator).getIntArray(0, 8));
                assertArrayEquals(REQUEST, client.get(3, TimeUnit.SECONDS));
                assertArrayEquals(new int[] {4, 4}, results.getIntArray(9 * 4L, 2));
                assertArrayEquals(REPLY, bytes.getByteArray(12, 4));
            }
            assertEquals(baseline, sockets());

            try (Socket reservation = new Socket()) {
                reservation.bind(new InetSocketAddress(loopback, 0));
                assertEquals(0, module.callFunction(emulator, "tcp_errors", family, reservation.getLocalPort()).intValue());
                assertArrayEquals(new int[] {-1, 111, -1, 98}, results.getIntArray(11 * 4L, 4));
                assertArrayEquals(new int[] {-1, 11, -1, 11, -1, 22, -1, 92},
                        module.findSymbolByName("tcp_error_results").createPointer(emulator).getIntArray(0, 8));
            }
            assertEquals(baseline, sockets());
            assertEquals(0, module.callFunction(emulator, "tcp_guest_exchange", family).intValue());
            emulator.getThreadDispatcher().runThreads(1, TimeUnit.SECONDS);
            assertEquals(0, results.getInt(15 * 4L));
            assertEquals(0, emulator.getThreadDispatcher().getTaskCount());
            assertEquals(baseline, sockets());

            byte[] payload = new byte[1024 * 1024];
            new java.util.Random(144).nextBytes(payload);
            MemoryBlock source = emulator.getMemory().malloc(payload.length, true);
            try (ServerSocket server = server(loopback)) {
                source.getPointer().write(payload);
                Future<byte[]> transferred = host.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        byte[] received = read(socket, payload.length);
                        assertEquals(-1, socket.getInputStream().read());
                        return received;
                    }
                });
                assertEquals(payload.length, module.callFunction(emulator, "tcp_send_blocking", family,
                        server.getLocalPort(), source.getPointer(), payload.length).intValue());
                assertArrayEquals(payload, transferred.get(3, TimeUnit.SECONDS));
            } finally {
                source.free();
            }
            assertEquals(baseline, sockets());

            try (ServerSocket server = server(loopback)) {
                assertEquals(0, module.callFunction(emulator, "tcp_send_timeout", family, server.getLocalPort()).intValue());
                int transferred = module.findSymbolByName("tcp_transferred").createPointer(emulator).getInt(0);
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    ByteArrayOutputStream received = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = socket.getInputStream().read(buffer)) != -1) {
                        received.write(buffer, 0, count);
                    }
                    byte[] expected = new byte[transferred];
                    java.util.Arrays.fill(expected, (byte) 0x5a);
                    assertArrayEquals(expected, received.toByteArray());
                }
            }
            assertEquals(baseline, sockets());

            for (int mode : new int[] {0, 1}) {
                try (ServerSocket server = lingerServer(loopback)) {
                    int transferred = module.callFunction(emulator, "tcp_linger_prepare", family, server.getLocalPort()).intValue();
                    assertTrue("queued TCP payload", transferred > 0);
                    CountDownLatch drain = new CountDownLatch(1);
                    Future<byte[]> peerBytes = lingerPeer(host, server, transferred, drain);
                    Pointer notify = callback(emulator, drain::countDown);
                    assertEquals(0, module.callFunction(emulator, "tcp_linger_finish", mode, notify).intValue());
                    assertArrayEquals(new int[] {0, 1, 0}, module.findSymbolByName("tcp_linger_results")
                            .createPointer(emulator).getIntArray(0, 3));
                    assertArrayEquals(lingerPayload(transferred), peerBytes.get(3, TimeUnit.SECONDS));
                }
                emulator.getThreadDispatcher().runThreads(1, TimeUnit.SECONDS);
                assertEquals(baseline, sockets());
            }

            try (ServerSocket server = server(loopback)) {
                assertEquals(0, module.callFunction(emulator, "tcp_pending_start", family, server.getLocalPort()).intValue());
                try (Socket socket = server.accept()) {
                    emulator.getThreadDispatcher().runThreads(1, TimeUnit.MILLISECONDS);
                    assertEquals(0, module.callFunction(emulator, "tcp_pending_close").intValue());
                    socket.getOutputStream().write(REQUEST);
                    emulator.getThreadDispatcher().runThreads(1, TimeUnit.SECONDS);
                    assertEquals(4, results.getInt(15 * 4L));
                    assertArrayEquals(REQUEST, bytes.getByteArray(8, 4));
                    assertEquals(0, emulator.getThreadDispatcher().getTaskCount());
                }
            }
            assertEquals(baseline, sockets());
            assertEquals(0, module.callFunction(emulator, "tcp_pending_accept", family).intValue());
            try (ServerSocket server = server(loopback)) {
                assertEquals(0, module.callFunction(emulator, "tcp_pending_start", family, server.getLocalPort()).intValue());
                try (Socket peerSocket = server.accept()) {
                    emulator.getThreadDispatcher().runThreads(1, TimeUnit.MILLISECONDS);
                    assertEquals(2, emulator.getThreadDispatcher().getTaskCount());
                }
            }
            try (ServerSocket closing = lingerServer(loopback); ServerSocket abandoned = lingerServer(loopback)) {
                int first = module.callFunction(emulator, "tcp_linger_prepare", family, closing.getLocalPort()).intValue();
                assertTrue("queued TCP payload", first > 0);
                CountDownLatch drain = new CountDownLatch(1), armed = new CountDownLatch(1);
                Future<byte[]> cancelled = lingerPeer(host, closing, first, drain);
                Pointer ready = callback(emulator, () -> {
                    emulator.getThreadDispatcher().getRunningTask().setDestroyListener(ignored -> drain.countDown());
                    armed.countDown();
                });
                assertEquals(0, module.callFunction(emulator, "tcp_linger_pending_start", ready).intValue());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (armed.getCount() != 0 && System.nanoTime() < deadline) {
                    emulator.getThreadDispatcher().runThreads(1, TimeUnit.MILLISECONDS);
                }
                assertEquals(0, armed.getCount());
                int second = module.callFunction(emulator, "tcp_linger_prepare", family, abandoned.getLocalPort()).intValue();
                assertTrue("queued TCP payload", second > 0);
                Future<byte[]> orphaned = lingerPeer(host, abandoned, second, drain);
                emulator.getThreadDispatcher().shutdown(5, TimeUnit.SECONDS);
                assertArrayEquals(lingerPayload(first), cancelled.get(3, TimeUnit.SECONDS));
                assertArrayEquals(lingerPayload(second), orphaned.get(3, TimeUnit.SECONDS));
                assertEquals(0, emulator.getThreadDispatcher().getTaskCount());
                assertEquals(originalWorkers, socketWorkers());
            }
        } finally {
            host.shutdownNow();
            assertTrue(host.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(baseline, sockets());
        assertEquals(originalWorkers, socketWorkers());
    }

    private static Pointer callback(AndroidEmulator emulator, Runnable action) {
        return emulator.getSvcMemory().registerSvc(emulator.is64Bit() ? new Arm64Svc() {
            @Override
            public long handle(Emulator<?> current) {
                action.run();
                return 0;
            }
        } : new ArmSvc() {
            @Override
            public long handle(Emulator<?> current) {
                action.run();
                return 0;
            }
        });
    }

    private static ServerSocket lingerServer(InetAddress address) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReceiveBufferSize(4096);
        server.bind(new InetSocketAddress(address, 0));
        server.setSoTimeout(2000);
        return server;
    }

    private static Future<byte[]> lingerPeer(ExecutorService host, ServerSocket server, int count, CountDownLatch drain) {
        return host.submit(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2000);
                assertTrue(drain.await(3, TimeUnit.SECONDS));
                byte[] bytes = read(socket, count);
                assertEquals(-1, socket.getInputStream().read());
                return bytes;
            }
        });
    }

    private static byte[] lingerPayload(int count) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, (byte) 0x6d);
        return bytes;
    }

    private static Set<String> socketWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive).map(Thread::getName)
                .filter(name -> name.startsWith("unidbg-socket-close-"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static ServerSocket server(InetAddress address) throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(address, 0));
        server.setSoTimeout(2000);
        return server;
    }

    private static byte[] read(Socket socket, int count) throws IOException {
        byte[] bytes = new byte[count];
        new java.io.DataInputStream(socket.getInputStream()).readFully(bytes);
        return bytes;
    }

    private static Set<String> sockets() throws IOException {
        try (Stream<Path> entries = Files.list(Paths.get("/proc/self/fd"))) {
            Set<String> result = new TreeSet<>();
            for (Path entry : entries.collect(Collectors.toList())) {
                String target = Files.readSymbolicLink(entry).toString();
                if (target.startsWith("socket:[")) {
                    result.add(entry.getFileName() + "=" + target);
                }
            }
            return result;
        }
    }
}
