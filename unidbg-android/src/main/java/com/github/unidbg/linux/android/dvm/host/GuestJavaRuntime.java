package com.github.unidbg.linux.android.dvm.host;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.thread.DispatcherEventSource;
import com.github.unidbg.thread.MainTask;
import com.github.unidbg.thread.Task;
import com.github.unidbg.thread.ThreadLifecycleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class GuestJavaRuntime implements DispatcherEventSource, ThreadLifecycleListener {
    private static final Logger log = LoggerFactory.getLogger(GuestJavaRuntime.class);
    private final AbstractEmulator<?> emulator;
    private final Thread backendThread;
    private final Map<Integer, GuestJavaThread> threads = new ConcurrentHashMap<>();
    private final Map<Task, Deque<Long>> nativeParents = new IdentityHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> backendRequests = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final GuestNativeGateway gateway;
    private boolean closing;
    private Throwable cleanupFailure;

    public GuestJavaRuntime(AbstractEmulator<?> emulator) {
        JvmMonitorBridge.initialize();
        this.emulator = emulator;
        this.backendThread = Thread.currentThread();
        this.gateway = new GuestNativeGateway(this);
        emulator.getThreadDispatcher().addEventSource(this);
        emulator.getThreadDispatcher().addThreadLifecycleListener(this);
        log.debug("event=guest_java_runtime_started emulator={}", System.identityHashCode(emulator));
    }

    public GuestNativeGateway gateway() {
        return gateway;
    }

    private void requireBackendThread() {
        if (Thread.currentThread() != backendThread) {
            throw new IllegalStateException("VM operations require the backend owner thread");
        }
    }

    private GuestJavaThread thread(int tid) {
        if (closing) {
            throw new IllegalStateException("Guest Java runtime is closing");
        }
        return threads.computeIfAbsent(tid, id -> new GuestJavaThread(id, System.identityHashCode(emulator)));
    }

    public <T> HostJavaCall<T> submit(Callable<T> operation) {
        return submit(operation, null);
    }

    private <T> HostJavaCall<T> submit(Callable<T> operation, Consumer<T> compensate) {
        requireBackendThread();
        if (closing) {
            throw new IllegalStateException("Guest Java runtime is closing");
        }
        Task owner = emulator.get(Task.TASK_KEY);
        if (owner == null) {
            throw new IllegalStateException("Host calls require a current guest task");
        }
        Deque<Long> parents = nativeParents.get(owner);
        long parent = parents == null || parents.isEmpty() ? 0 : parents.getLast();
        HostJavaCall<T> call = new HostJavaCall<>(sequence.incrementAndGet(), parent, owner, operation, compensate);
        thread(owner.getId()).submit(call);
        log.debug("event=guest_java_call_submitted emulator={} tid={} call={} parent={}",
                System.identityHashCode(emulator), owner.getId(), call.id, parent);
        return call;
    }

    public HostJavaCall<Integer> monitorEnter(Object target) {
        requireBackendThread();
        Task owner = emulator.get(Task.TASK_KEY);
        if (owner == null) {
            throw new IllegalStateException("MonitorEnter requires a current guest task");
        }
        GuestJavaThread worker = thread(owner.getId());
        return submit(() -> worker.monitors.enter(target), result -> {
            if (result == 0 && worker.monitors.exit(target) != 0) {
                throw new IllegalStateException("Cancelled MonitorEnter could not release its monitor");
            }
        });
    }

    public HostJavaCall<Integer> monitorExit(Object target) {
        requireBackendThread();
        Task owner = emulator.get(Task.TASK_KEY);
        if (owner == null) {
            throw new IllegalStateException("MonitorExit requires a current guest task");
        }
        GuestJavaThread worker = thread(owner.getId());
        return submit(() -> worker.monitors.exit(target));
    }

    public HostJavaCall<Void> releaseMonitors() {
        requireBackendThread();
        Task owner = emulator.get(Task.TASK_KEY);
        if (owner == null) {
            throw new IllegalStateException("Monitor cleanup requires a current guest task");
        }
        GuestJavaThread worker = thread(owner.getId());
        return submit(() -> {
            worker.monitors.releaseAll();
            return null;
        });
    }

    <T> T invokeNative(GuestNativeGateway.Request<T> request) throws Exception {
        GuestJavaThread worker = threads.values().stream()
                .filter(candidate -> candidate.thread == Thread.currentThread()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Native reentry requires a guest Java thread"));
        HostJavaCall<?> parent = worker.currentCall();
        CompletableFuture<HostJavaResult<T>> reply = new CompletableFuture<>();
        backendRequests.add(() -> startNative(parent, request, reply));
        return worker.awaitNative(reply);
    }

    private <T> void startNative(HostJavaCall<?> parent, GuestNativeGateway.Request<T> request,
                                 CompletableFuture<HostJavaResult<T>> reply) {
        if (closing || parent.isCancelled()) {
            try {
                request.close();
            } finally {
                reply.complete(HostJavaResult.failure(new CancellationException("Guest Java runtime is closing")));
            }
            return;
        }
        Deque<Long> parents = nativeParents.computeIfAbsent(parent.owner, ignored -> new ArrayDeque<>());
        parents.addLast(parent.id);
        try {
            MainTask entry = request.prepare(emulator);
            emulator.getThreadDispatcher().submitNativeCall(parent.owner, entry, (result, failure) -> {
                HostJavaResult<T> outcome;
                try {
                    outcome = failure == null ? HostJavaResult.success(request.complete(result)) : HostJavaResult.failure(failure);
                } catch (Throwable completionFailure) {
                    outcome = HostJavaResult.failure(completionFailure);
                }
                try {
                    request.close();
                } catch (Throwable cleanupFailure) {
                    if (outcome.failure() != null) {
                        outcome.failure().addSuppressed(cleanupFailure);
                    } else {
                        outcome = HostJavaResult.failure(cleanupFailure);
                    }
                } finally {
                    parents.removeLast();
                    if (parents.isEmpty()) nativeParents.remove(parent.owner);
                }
                reply.complete(outcome);
            });
        } catch (Throwable failure) {
            try {
                request.close();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } finally {
                parents.removeLast();
                if (parents.isEmpty()) nativeParents.remove(parent.owner);
            }
            reply.complete(HostJavaResult.failure(failure));
        }
    }

    public boolean hasActiveJavaCall(int tid) {
        requireBackendThread();
        GuestJavaThread worker = threads.get(tid);
        return worker != null && worker.hasActiveCalls();
    }

    public CompletableFuture<Void> terminateThread(int tid) {
        requireBackendThread();
        GuestJavaThread worker = threads.get(tid);
        if (worker == null) {
            return CompletableFuture.completedFuture(null);
        }
        worker.beginClose();
        return worker.terminated;
    }

    @Override
    public void onThreadTerminated(Task task) {
        terminateThread(task.getId());
    }

    @Override
    public void dispatchPending() {
        requireBackendThread();
        Runnable request;
        while ((request = backendRequests.poll()) != null) {
            request.run();
        }
        for (GuestJavaThread worker : threads.values()) {
            if (!worker.thread.isAlive()) {
                Throwable failure = worker.closeFailure();
                if (failure == null) {
                    worker.terminated.complete(null);
                } else {
                    worker.terminated.completeExceptionally(failure);
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else if (cleanupFailure != failure) cleanupFailure.addSuppressed(failure);
                }
                threads.remove(worker.tid, worker);
            }
        }
    }

    @Override
    public void beginClose() {
        requireBackendThread();
        closing = true;
        for (GuestJavaThread worker : threads.values()) {
            worker.beginClose();
        }
        log.debug("event=guest_java_runtime_closing emulator={} threads={}", System.identityHashCode(emulator), threads.size());
    }

    @Override
    public boolean isClosed() {
        return closing && backendRequests.isEmpty() && threads.isEmpty();
    }

    @Override
    public Throwable closeFailure() {
        return cleanupFailure;
    }
}
