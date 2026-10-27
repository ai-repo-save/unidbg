package com.github.unidbg.linux.android.dvm.host;

import com.github.unidbg.thread.Task;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class HostJavaCall<T> {
    final long id;
    final long parent;
    final Task owner;
    final Callable<T> operation;
    final Consumer<T> compensate;
    private volatile boolean cancelled;
    private boolean finishing;
    private final CompletableFuture<HostJavaResult<T>> result = new CompletableFuture<>();

    HostJavaCall(long id, long parent, Task owner, Callable<T> operation, Consumer<T> compensate) {
        this.id = id;
        this.parent = parent;
        this.owner = owner;
        this.operation = operation;
        this.compensate = compensate;
    }

    public long id() {
        return id;
    }

    boolean isCancelled() {
        return cancelled;
    }

    public boolean isDone() {
        return result.isDone();
    }

    public synchronized boolean cancel() {
        if (finishing || cancelled) {
            return false;
        }
        cancelled = true;
        return true;
    }

    public HostJavaResult<T> result() {
        if (!result.isDone()) {
            throw new IllegalStateException("Host call is still running: " + id);
        }
        return result.join();
    }

    HostJavaResult<T> execute() {
        if (cancelled) {
            return HostJavaResult.failure(new CancellationException("Host call cancelled: " + id));
        }
        try {
            return HostJavaResult.success(operation.call());
        } catch (Throwable failure) {
            return HostJavaResult.failure(failure);
        }
    }

    synchronized boolean reserveFinish() {
        if (finishing) {
            throw new IllegalStateException("Host call has already finished: " + id);
        }
        finishing = true;
        return cancelled;
    }

    HostJavaResult<T> finish(HostJavaResult<T> outcome, boolean cancelledAtFinish) {
        if (cancelledAtFinish && outcome.failure() == null) {
            try {
                if (compensate != null) {
                    compensate.accept(outcome.get());
                }
                outcome = HostJavaResult.failure(new CancellationException("Host call cancelled: " + id));
            } catch (Throwable failure) {
                outcome = HostJavaResult.failure(failure);
            }
        }
        return outcome;
    }

    void publish(HostJavaResult<T> outcome) {
        result.complete(outcome);
    }
}
