package com.github.unidbg.linux.android.dvm.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

final class GuestJavaThread implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GuestJavaThread.class);
    final int tid;
    final Thread thread;
    final JvmMonitorOwnership monitors = new JvmMonitorOwnership();
    final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final Object state = new Object();
    private final Deque<HostJavaCall<?>> mailbox = new ArrayDeque<>();
    private final Deque<HostJavaCall<?>> active = new ArrayDeque<>();
    private boolean closing;
    private volatile Throwable closeFailure;

    GuestJavaThread(int tid, int emulatorId) {
        this.tid = tid;
        this.thread = new Thread(this, "unidbg-java-" + emulatorId + "-" + tid);
        thread.start();
    }

    void submit(HostJavaCall<?> call) {
        synchronized (state) {
            if (closing) {
                throw new IllegalStateException("Guest Java thread is closing: " + tid);
            }
            mailbox.addLast(call);
            state.notifyAll();
        }
    }

    HostJavaCall<?> currentCall() {
        synchronized (state) {
            if (Thread.currentThread() != thread || active.isEmpty()) {
                throw new IllegalStateException("Native reentry requires an active guest Java callback");
            }
            return active.getLast();
        }
    }

    boolean hasActiveCalls() {
        synchronized (state) {
            return !active.isEmpty();
        }
    }

    private HostJavaCall<?> claim(Long parent, CompletableFuture<?> reply) throws InterruptedException {
        synchronized (state) {
            while (true) {
                if (closing || reply != null && reply.isDone()) {
                    return null;
                }
                for (Iterator<HostJavaCall<?>> iterator = mailbox.iterator(); iterator.hasNext();) {
                    HostJavaCall<?> call = iterator.next();
                    if (parent == null || call.parent == parent) {
                        iterator.remove();
                        active.addLast(call);
                        return call;
                    }
                }
                state.wait();
            }
        }
    }

    private <T> void execute(HostJavaCall<T> call) {
        log.debug("event=guest_java_call_started tid={} call={} parent={}", tid, call.id, call.parent);
        HostJavaResult<T> outcome = call.execute();
        boolean cancelledAtFinish;
        synchronized (state) {
            cancelledAtFinish = call.reserveFinish();
            if (active.removeLast() != call) {
                throw new IllegalStateException("Guest Java callback stack is out of order");
            }
        }
        outcome = call.finish(outcome, cancelledAtFinish);
        call.publish(outcome);
        Throwable failure = outcome.failure();
        if (failure == null) {
            log.debug("event=guest_java_call_completed tid={} call={}", tid, call.id);
        } else {
            log.debug("event=guest_java_call_failed tid={} call={}", tid, call.id, failure);
        }
    }

    <T> T awaitNative(CompletableFuture<HostJavaResult<T>> reply) throws Exception {
        HostJavaCall<?> parent = currentCall();
        reply.whenComplete((result, failure) -> {
            synchronized (state) {
                state.notifyAll();
            }
        });
        while (!reply.isDone()) {
            HostJavaCall<?> nested = claim(parent.id, reply);
            if (nested == null) {
                if (!reply.isDone()) {
                    throw new CancellationException("Guest Java thread is closing: " + tid);
                }
                break;
            }
            execute(nested);
        }
        return reply.join().get();
    }

    void beginClose() {
        List<HostJavaCall<?>> cancelled;
        synchronized (state) {
            if (closing) {
                return;
            }
            closing = true;
            for (HostJavaCall<?> call : active) {
                call.cancel();
            }
            cancelled = new ArrayList<>(mailbox);
            mailbox.clear();
            for (HostJavaCall<?> call : cancelled) {
                call.cancel();
            }
            state.notifyAll();
        }
        for (HostJavaCall<?> call : cancelled) {
            cancelQueued(call);
        }
    }

    private static <T> void cancelQueued(HostJavaCall<T> call) {
        boolean cancelledAtFinish = call.reserveFinish();
        call.publish(call.finish(HostJavaResult.failure(new CancellationException("Host call cancelled: " + call.id)), cancelledAtFinish));
    }

    Throwable closeFailure() {
        return closeFailure;
    }

    @Override
    public void run() {
        log.debug("event=guest_java_thread_started tid={} host_thread={}", tid, thread.getId());
        try {
            HostJavaCall<?> call;
            while ((call = claim(null, null)) != null) {
                execute(call);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            closeFailure = failure;
            log.error("event=guest_java_thread_interrupted tid={}", tid, failure);
        } finally {
            beginClose();
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
