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
import com.github.unidbg.thread.RunnableTask;
import com.github.unidbg.thread.ThreadTask;
import com.github.unidbg.linux.android.dvm.host.HostJavaResult;
import org.junit.Test;
import unicorn.Arm64Const;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
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
        PlanProbe plans = new PlanProbe(workspace);
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
                    if (operation == 11 || operation == 12) {
                        return plans.begin(current, runtime, operation == 12);
                    }
                    if (operation == 13) {
                        return plans.cancel(current, runtime);
                    }
                    HostJavaCall<? extends Number> call;
                    if (operation == 10) {
                        call = runtime.submit(host::closeBoundary);
                        await(host.closeEntered);
                        for (int index = 0; index < 8; index++) {
                            host.closeQueued.add(runtime.submit(host::queuedAfterClose));
                        }
                        runtime.terminateThread(tid);
                        host.closeReleased.countDown();
                    } else if (operation == 1) {
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
                        if (operation == 5) {
                            host.queued.set(runtime.submit(host::queued));
                            host.queuedReady.countDown();
                        }
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
            assertSame(host.a, host.queuedThread);
            assertEquals(Integer.valueOf(0x73), host.queued.get().result().get());
            assertEquals("outer-start\nnested\nouter-end\nqueued\n",
                    new String(Files.readAllBytes(workspace.resolve("queued-chain.txt")), StandardCharsets.US_ASCII));
            assertEquals(false, host.a == host.b);
            host.a.join(3000);
            host.b.join(3000);
            assertEquals(Thread.State.TERMINATED, host.a.getState());
            assertEquals(Thread.State.TERMINATED, host.b.getState());

            assertEquals(0, module.callFunction(emulator, "monitor_close_start").intValue());
            emulator.getThreadDispatcher().runThreads(10, TimeUnit.SECONDS);
            assertEquals(-2, module.findSymbolByName("monitor_close_result").createPointer(emulator).getInt(0));
            assertEquals(0, module.callFunction(emulator, "monitor_close_join").intValue());
            host.closingThread.join(3000);
            assertEquals(Thread.State.TERMINATED, host.closingThread.getState());
            for (HostJavaCall<Integer> queued : host.closeQueued) {
                assertEquals(CancellationException.class, queued.result().failure().getClass());
            }
            assertEquals("entered\nreturned\n",
                    new String(Files.readAllBytes(workspace.resolve("close-boundary.txt")), StandardCharsets.US_ASCII));

            assertEquals(0x4455, module.callFunction(emulator, "monitor_two_step").intValue());
            assertEquals("svc\nhost-one\nbackend-one\nhost-two\nbackend-two\n",
                    new String(Files.readAllBytes(workspace.resolve("plan-two-step.txt")), StandardCharsets.US_ASCII));
            assertEquals(0, module.callFunction(emulator, "monitor_plan_cancel_start").intValue());
            assertEquals(1, module.callFunction(emulator, "monitor_plan_cancel_control").intValue());
            emulator.getThreadDispatcher().runThreads(3, TimeUnit.SECONDS);
            assertEquals(0, module.callFunction(emulator, "monitor_plan_cancel_join").intValue());
            plans.cancelHostThread.join(3000);
            assertEquals(Thread.State.TERMINATED, plans.cancelHostThread.getState());
            assertEquals("svc\nhost-one\nbackend-one\nhost-two\nabandoned\nhost-two-returned\n",
                    new String(Files.readAllBytes(workspace.resolve("plan-cancel.txt")), StandardCharsets.US_ASCII));
            assertArrayEquals(new byte[] {0x13, 0x57},
                    Files.readAllBytes(emulator.getFileSystem().getRootDir().toPath().resolve("monitor-plan-cancel.bin")));

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
        assertNull(threads.getThreadInfo(host.closingThread.getId()));
        assertNull(threads.getThreadInfo(plans.cancelHostThread.getId()));
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

    private static void await(CountDownLatch latch) {
        try {
            assertEquals(true, latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
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

    private static final class PlanProbe {
        final Path workspace;
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch secondReleased = new CountDownLatch(1);
        final RuntimeException abandonedFailure = new RuntimeException("abandoned observer failure");
        ThreadTask cancelledTask;
        RunnableTask cancelledFrame;
        volatile Thread cancelHostThread;

        PlanProbe(Path workspace) { this.workspace = workspace; }

        long begin(Emulator<?> emulator, GuestJavaRuntime runtime, boolean cancel) {
            Task owner = emulator.get(Task.TASK_KEY);
            RunnableTask frame = emulator.getThreadDispatcher().getRunningTask();
            long[] origin = registers(emulator);
            String file = cancel ? "plan-cancel.txt" : "plan-two-step.txt";
            trace(file, "svc\n", true);
            if (cancel) {
                cancelledTask = (ThreadTask) owner;
                cancelledFrame = frame;
            }
            HostJavaCall<Integer> first = runtime.submit(() -> {
                trace(file, "host-one\n", false);
                return 0x444e;
            });
            return GuestJavaWaiter.suspend(emulator, first, result -> {
                context(emulator, origin);
                int value = value(result);
                trace(file, "backend-one\n", false);
                HostJavaCall<Integer> second = runtime.submit(() -> {
                    trace(file, "host-two\n", false);
                    if (cancel) {
                        cancelHostThread = Thread.currentThread();
                        secondEntered.countDown();
                        await(secondReleased);
                        trace(file, "host-two-returned\n", false);
                    }
                    return value + 7;
                });
                GuestJavaWaiter.rearm(emulator, second, terminal -> {
                    context(emulator, origin);
                    trace(file, "backend-two\n", false);
                    frame.setWaiter(emulator, null);
                    emulator.getBackend().reg_write(Arm64Const.UC_ARM64_REG_X0, value(terminal));
                }, failure -> {
                    assertEquals(CancellationException.class, failure.getClass());
                    context(emulator, origin);
                    trace(file, "abandoned\n", false);
                    secondReleased.countDown();
                    if (cancel) throw abandonedFailure;
                });
            }, failure -> {
                context(emulator, origin);
                trace(file, "first-abandoned\n", false);
                secondReleased.countDown();
            });
        }

        long cancel(Emulator<?> emulator, GuestJavaRuntime runtime) {
            HostJavaCall<Integer> ready = runtime.submit(() -> {
                await(secondEntered);
                return 1;
            });
            return GuestJavaWaiter.suspend(emulator, ready, result -> {
                long[] before = registers(emulator);
                try {
                    cancelledFrame.setWaiter(emulator, null);
                    fail("Abandoned observer must report its original failure");
                } catch (RuntimeException failure) {
                    assertSame(abandonedFailure, failure);
                } finally {
                    cancelledTask.setExitStatus(0);
                }
                context(emulator, before);
                emulator.getBackend().reg_write(Arm64Const.UC_ARM64_REG_X0, value(result));
            });
        }

        private static int value(HostJavaResult<Integer> result) {
            try { return result.get(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }

        private static long[] registers(Emulator<?> emulator) {
            Backend backend = emulator.getBackend();
            return new long[] {backend.reg_read(Arm64Const.UC_ARM64_REG_X0).longValue(),
                    backend.reg_read(Arm64Const.UC_ARM64_REG_X1).longValue(),
                    backend.reg_read(Arm64Const.UC_ARM64_REG_X19).longValue(),
                    backend.reg_read(Arm64Const.UC_ARM64_REG_SP).longValue(),
                    backend.reg_read(Arm64Const.UC_ARM64_REG_TPIDR_EL0).longValue(),
                    backend.reg_read(Arm64Const.UC_ARM64_REG_PC).longValue(),
                    backend.reg_read(Arm64Const.UC_ARM64_REG_LR).longValue(),
                    emulator.getMemory().getStackPoint()};
        }

        private static void context(Emulator<?> emulator, long[] expected) {
            assertArrayEquals(expected, registers(emulator));
        }

        private void trace(String file, String value, boolean create) {
            try {
                if (create) Files.write(workspace.resolve(file), value.getBytes(StandardCharsets.US_ASCII));
                else Files.write(workspace.resolve(file), value.getBytes(StandardCharsets.US_ASCII), StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    public static final class GateHost {
        final Object target;
        final Path workspace;
        final RuntimeException original = new RuntimeException("original host target exception");
        final AtomicReference<HostJavaCall<? extends Number>> blocking = new AtomicReference<>();
        final AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        final AtomicReference<HostJavaCall<Integer>> queued = new AtomicReference<>();
        final CountDownLatch queuedReady = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch closeReleased = new CountDownLatch(1);
        final List<HostJavaCall<Integer>> closeQueued = new ArrayList<>();
        GuestNativeGateway gateway;
        GuestNativeGateway.Request<Long> nativeRequest;
        volatile Thread a, b, nested, main, observer, closeObserver, queuedThread, closingThread;

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
                    Files.write(workspace.resolve("queued-chain.txt"), "outer-start\n".getBytes(StandardCharsets.US_ASCII));
                    await(queuedReady);
                    long value = gateway.invoke(nativeRequest);
                    append("queued-chain.txt", "outer-end\n");
                    return value;
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
                    append("queued-chain.txt", "nested\n");
                    assertEquals(true, Thread.holdsLock(target));
                    return 0x12;
                default:
                    throw new IllegalArgumentException("Unknown fixture operation: " + operation);
            }
        }

        public int queued() throws Exception {
            queuedThread = Thread.currentThread();
            append("queued-chain.txt", "queued\n");
            return 0x73;
        }

        public int closeBoundary() throws Exception {
            closingThread = Thread.currentThread();
            Files.write(workspace.resolve("close-boundary.txt"), "entered\n".getBytes(StandardCharsets.US_ASCII));
            closeEntered.countDown();
            await(closeReleased);
            append("close-boundary.txt", "returned\n");
            return 0x51;
        }

        public int queuedAfterClose() throws Exception {
            append("close-boundary.txt", "queued\n");
            return 0x52;
        }

        private void append(String file, String value) throws Exception {
            Files.write(workspace.resolve(file), value.getBytes(StandardCharsets.US_ASCII), StandardOpenOption.APPEND);
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
