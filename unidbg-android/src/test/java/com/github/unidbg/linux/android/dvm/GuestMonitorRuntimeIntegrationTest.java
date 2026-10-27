package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.CodeHook;
import com.github.unidbg.arm.backend.UnHook;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.host.GuestJavaRuntime;
import com.github.unidbg.linux.android.dvm.host.GuestNativeGateway;
import com.github.unidbg.linux.android.dvm.host.HostJavaCall;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.thread.Function64;
import com.github.unidbg.thread.MainTask;
import com.github.unidbg.thread.Task;
import org.junit.Test;
import unicorn.Arm64Const;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class GuestMonitorRuntimeIntegrationTest {
    @Test
    public void nativeJavaMonitorLifecycle() throws Exception {
        String fixture = System.getProperty("unidbg.guestMonitorLibrary");
        if (fixture == null) {
            throw new IllegalArgumentException("Required: -Dunidbg.guestMonitorLibrary=<ARM64 fixture>");
        }
        Path workspace = Files.createTempDirectory(new File("target").toPath(), "guest-monitor-");
        Object target = new Object();
        GateHost host = new GateHost(target, workspace);
        Set<Long> backendThreads = new HashSet<>();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        long backendThread = Thread.currentThread().getId();
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit().setRootDir(workspace.resolve("root").toFile())
                .addBackendFactory(new Unicorn2Factory(true)).build()) {
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
            emulator.getSyscallHandler().setEnableThreadDispatcher(true);
            GuestJavaRuntime runtime = new GuestJavaRuntime((com.github.unidbg.AbstractEmulator<?>) emulator);
            host.gateway = runtime.gateway();
            Module module = emulator.loadLibrary(new File(fixture), false);
            long nestedAddress = module.findSymbolByName("monitor_nested").getAddress();
            Method ordinary = GateHost.class.getMethod("ordinary", int.class, int.class);
            emulator.getBackend().hook_add_new(new CodeHook() {
                private UnHook handle;
                @Override public void hook(Backend backend, long address, int size, Object user) {
                    backendThreads.add(Thread.currentThread().getId());
                }
                @Override public void onAttach(UnHook hook) { handle = hook; }
                @Override public void detach() { handle.unhook(); }
            }, module.base, module.base + module.size, null);
            UnidbgPointer bridge = emulator.getSvcMemory().registerSvc(new Arm64Svc() {
                @Override public long handle(Emulator<?> current) {
                    int operation = current.getContext().getIntArg(0);
                    int argument = current.getContext().getIntArg(1);
                    int tid = ((Task) current.get(Task.TASK_KEY)).getId();
                    HostJavaCall<? extends Number> call;
                    if (operation == 1) {
                        call = runtime.monitorEnter(target);
                        if (argument == 1) host.blocking.set(call);
                    } else if (operation == 2) {
                        call = runtime.monitorExit(target);
                    } else {
                        if (operation == 5) {
                            host.nativeRequest = new GuestNativeGateway.Request<Long>() {
                                @Override public MainTask prepare(com.github.unidbg.AbstractEmulator<?> backend) {
                                    return new Function64(tid, nestedAddress, backend.getReturnAddress(), false);
                                }
                                @Override public Long complete(Number result) { return result.longValue(); }
                            };
                        }
                        call = runtime.submit(() -> {
                            try {
                                return (Number) ordinary.invoke(host, operation, argument);
                            } catch (InvocationTargetException failure) {
                                Throwable cause = failure.getTargetException();
                                if (cause instanceof Error) throw (Error) cause;
                                throw (Exception) cause;
                            }
                        });
                    }
                    return suspendNumber(current, call, observedFailure);
                }
            });
            assertEquals(0, module.callFunction(emulator, "monitor_start", bridge).intValue());
            emulator.getThreadDispatcher().runThreads(10, TimeUnit.SECONDS);
            assertArrayEquals(new int[] {0, 0, 1, 1, 0, 0x42, -7, 1, 1, 0, 0, -2},
                    module.findSymbolByName("monitor_results").createPointer(emulator).getIntArray(0, 12));
            assertSame(host.original, observedFailure.get());
            assertEquals(0, module.callFunction(emulator, "monitor_join").intValue());
            host.observer.join(3000);
            assertEquals(Thread.State.TERMINATED, host.observer.getState());
            assertEquals("observer acquired\n", new String(Files.readAllBytes(workspace.resolve("observer.txt")), StandardCharsets.US_ASCII));
            assertArrayEquals(new byte[] {0x31, 0, 0x7f, 0x62},
                    Files.readAllBytes(emulator.getFileSystem().getRootDir().toPath().resolve("monitor-native.bin")));
            assertSame(host.a, host.nested);
            assertEquals(false, host.a == host.b);
            host.a.join(3000);
            host.b.join(3000);
            assertEquals(Thread.State.TERMINATED, host.a.getState());
            assertEquals(Thread.State.TERMINATED, host.b.getState());

            assertEquals(0, module.callFunction(emulator, "monitor_main_hold").intValue());
            // Capture the real owner using JVM lock information, outside the emulated call.
            host.closeObserver = host.observer("close-observer.txt");
            ThreadInfo blocked = awaitBlocked(host.closeObserver);
            host.main = findThread(blocked.getLockOwnerId());
            assertEquals(new HashSet<>(Arrays.asList(backendThread)), backendThreads);
        }
        host.closeObserver.join(3000);
        host.main.join(3000);
        assertEquals(Thread.State.TERMINATED, host.closeObserver.getState());
        assertEquals(Thread.State.TERMINATED, host.main.getState());
        assertEquals("observer acquired\n", new String(Files.readAllBytes(workspace.resolve("close-observer.txt")), StandardCharsets.US_ASCII));
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        assertNull(threads.getThreadInfo(host.a.getId()));
        assertNull(threads.getThreadInfo(host.b.getId()));
        assertNull(threads.getThreadInfo(host.main.getId()));
        assertNull(host.observerFailure.get());
    }

    private static <T extends Number> long suspendNumber(Emulator<?> emulator, HostJavaCall<T> call,
                                                        AtomicReference<Throwable> failure) {
        return GuestJavaWaiter.suspend(emulator, call, result -> numberResult(emulator, result, failure));
    }

    private static void numberResult(Emulator<?> emulator,
                                     com.github.unidbg.linux.android.dvm.host.HostJavaResult<? extends Number> result,
                                     AtomicReference<Throwable> failure) {
        long value;
        if (result.failure() instanceof CancellationException) {
            value = -2;
        } else if (result.failure() != null) {
            failure.set(result.failure());
            value = -7;
        } else {
            try {
                value = result.get().longValue();
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        }
        emulator.getBackend().reg_write(Arm64Const.UC_ARM64_REG_X0, value);
    }

    private static Thread findThread(long id) {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getId() == id)
                .findFirst().orElseThrow(() -> new AssertionError("Lock owner has no live Java thread: " + id));
    }

    private static ThreadInfo awaitBlocked(Thread thread) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            ThreadInfo info = threads.getThreadInfo(thread.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED) return info;
            Thread.yield();
        }
        throw new AssertionError("Expected BLOCKED Java monitor contender: " + Arrays.toString(threads.dumpAllThreads(true, true)));
    }

    public static final class GateHost {
        final Object target;
        final Path workspace;
        final RuntimeException original = new RuntimeException("original host target exception");
        final AtomicReference<HostJavaCall<? extends Number>> blocking = new AtomicReference<>();
        final AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        GuestNativeGateway gateway;
        GuestNativeGateway.Request<Long> nativeRequest;
        volatile Thread a, b, nested, main, observer, closeObserver;

        GateHost(Object target, Path workspace) {
            this.target = target;
            this.workspace = workspace;
        }

        public long ordinary(int operation, int argument) throws Exception {
            switch (operation) {
                case 3:
                    if (argument == 0) a = Thread.currentThread();
                    else b = Thread.currentThread();
                    return Thread.holdsLock(target) ? 1 : 0;
                case 4:
                    observer = observer("observer.txt");
                    assertEquals(a.getId(), awaitBlocked(observer).getLockOwnerId());
                    return 1;
                case 5:
                    return gateway.invoke(nativeRequest);
                case 6:
                    throw original;
                case 7:
                    assertEquals(a.getId(), awaitBlocked(observer).getLockOwnerId());
                    assertEquals(true, Thread.holdsLock(target));
                    return 1;
                case 8:
                    assertEquals(a.getId(), awaitBlocked(b).getLockOwnerId());
                    assertEquals(true, blocking.get().cancel());
                    return 1;
                case 9:
                    nested = Thread.currentThread();
                    assertEquals(true, Thread.holdsLock(target));
                    return 0x12;
                default:
                    throw new IllegalArgumentException("Unknown fixture operation: " + operation);
            }
        }

        Thread observer(String file) {
            Thread thread = new Thread(() -> {
                try {
                    synchronized (target) {
                        Files.write(workspace.resolve(file), "observer acquired\n".getBytes(StandardCharsets.US_ASCII));
                    }
                } catch (Throwable failure) {
                    observerFailure.set(failure);
                }
            }, "independent-monitor-observer");
            thread.start();
            return thread;
        }
    }
}
