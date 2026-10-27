package com.github.unidbg.linux.android.dvm.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class GuestJavaThread implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GuestJavaThread.class);
    final int tid;
    final Thread thread;
    final JvmMonitorOwnership monitors = new JvmMonitorOwnership();
    final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final LinkedBlockingQueue<HostJavaCall<?>> mailbox = new LinkedBlockingQueue<>();
    private final Deque<HostJavaCall<?>> active = new ConcurrentLinkedDeque<>();
    private volatile boolean closing;
    private volatile Throwable closeFailure;

    GuestJavaThread(int tid, int emulatorId) {
        this.tid = tid;
        this.thread = new Thread(this, "unidbg-java-" + emulatorId + "-" + tid);
        thread.start();
    }

    void submit(HostJavaCall<?> call) {
        if (closing) {
            throw new IllegalStateException("Guest Java thread is closing: " + tid);
        }
        mailbox.add(call);
    }

    HostJavaCall<?> currentCall() {
        if (Thread.currentThread() != thread || active.isEmpty()) {
            throw new IllegalStateException("Native reentry requires an active guest Java callback");
        }
        return active.getLast();
    }

    boolean hasActiveCalls() {
        return !active.isEmpty();
    }

    private <T> void execute(HostJavaCall<T> call) {
        active.addLast(call);
        log.debug("event=guest_java_call_started tid={} call={} parent={}", tid, call.id, call.parent);
        HostJavaResult<T> outcome;
        try {
            outcome = call.execute();
        } finally {
            active.removeLast();
        }
        call.complete(outcome);
        Throwable failure = call.result().failure();
        if (failure == null) {
            log.debug("event=guest_java_call_completed tid={} call={}", tid, call.id);
        } else {
            log.debug("event=guest_java_call_failed tid={} call={}", tid, call.id, failure);
        }
    }

    <T> T awaitNative(CompletableFuture<HostJavaResult<T>> reply) throws Exception {
        HostJavaCall<?> parent = currentCall();
        while (!reply.isDone()) {
            if (closing) {
                throw new CancellationException("Guest Java thread is closing: " + tid);
            }
            HostJavaCall<?> nested = mailbox.poll(10, TimeUnit.MILLISECONDS);
            if (nested != null) {
                if (nested.stop) {
                    mailbox.add(nested);
                    throw new CancellationException("Guest Java thread is closing: " + tid);
                }
                if (nested.parent != parent.id) {
                    throw new IllegalStateException("Host callback belongs to another native call chain");
                }
                execute(nested);
            }
        }
        return reply.join().get();
    }

    void beginClose() {
        if (closing) {
            return;
        }
        closing = true;
        for (HostJavaCall<?> call : active) {
            call.cancel();
        }
        for (HostJavaCall<?> call : mailbox) {
            call.cancel();
        }
        mailbox.add(new HostJavaCall<>(0, 0, null, () -> null, null, true));
    }

    Throwable closeFailure() {
        return closeFailure;
    }

    @Override
    public void run() {
        log.debug("event=guest_java_thread_started tid={} host_thread={}", tid, thread.getId());
        try {
            while (true) {
                HostJavaCall<?> call = mailbox.take();
                if (call.stop) {
                    break;
                }
                execute(call);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            closeFailure = failure;
            log.error("event=guest_java_thread_interrupted tid={}", tid, failure);
        } finally {
            try {
                monitors.releaseAll();
            } catch (Throwable failure) {
                closeFailure = failure;
                log.error("event=guest_java_monitor_cleanup_failed tid={}", tid, failure);
            }
            log.debug("event=guest_java_thread_stopped tid={} host_thread={}", tid, thread.getId());
        }
    }
}
