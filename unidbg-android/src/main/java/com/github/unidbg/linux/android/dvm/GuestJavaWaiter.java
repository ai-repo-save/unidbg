package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.linux.android.dvm.host.HostJavaCall;
import com.github.unidbg.linux.android.dvm.host.HostJavaResult;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.thread.RunnableTask;
import com.github.unidbg.thread.Task;
import com.github.unidbg.thread.ThreadContextSwitchException;
import com.github.unidbg.thread.Waiter;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

public final class GuestJavaWaiter<T> implements Waiter {
    private final HostJavaCall<T> call;
    private final Consumer<HostJavaResult<T>> completion;
    private final Consumer<Throwable> abandoned;
    private final Task owner;
    private final RunnableTask frame;
    private boolean delivered;

    public GuestJavaWaiter(HostJavaCall<T> call, Consumer<HostJavaResult<T>> completion) {
        this(call, completion, null, null, null);
    }

    private GuestJavaWaiter(HostJavaCall<T> call, Consumer<HostJavaResult<T>> completion,
                            Consumer<Throwable> abandoned, Task owner, RunnableTask frame) {
        this.call = call;
        this.completion = completion;
        this.abandoned = abandoned;
        this.owner = owner;
        this.frame = frame;
    }

    public static <T> void rearm(Emulator<?> emulator, HostJavaCall<T> call,
                                 Consumer<HostJavaResult<T>> completion, Consumer<Throwable> abandoned) {
        Task owner = emulator.get(Task.TASK_KEY);
        RunnableTask frame = emulator.getThreadDispatcher().getRunningTask();
        if (owner == null || frame == null) {
            throw new IllegalStateException("Host waiter installation requires a current guest frame");
        }
        frame.setWaiter(emulator, new GuestJavaWaiter<>(call, completion, abandoned, owner, frame));
    }

    public static <T> long suspend(Emulator<?> emulator, HostJavaCall<T> call,
                                  Consumer<HostJavaResult<T>> completion) {
        return suspend(emulator, call, completion, null);
    }

    public static <T> long suspend(Emulator<?> emulator, HostJavaCall<T> call,
                                  Consumer<HostJavaResult<T>> completion, Consumer<Throwable> abandoned) {
        rearm(emulator, call, completion, abandoned);
        throw new ThreadContextSwitchException();
    }

    @Override
    public boolean canDispatch() {
        return call.isDone();
    }

    @Override
    public void onContinueRun(Emulator<?> emulator) {
        delivered = true;
        completion.accept(call.result());
    }

    @Override
    public void onSignal(SignalTask task) {
    }

    @Override
    public void onDestroy(Emulator<?> emulator) {
        if (!delivered) {
            delivered = true;
            call.cancel();
            if (abandoned != null) {
                CancellationException cancellation = new CancellationException("Guest host call abandoned: " + call.id());
                emulator.getThreadDispatcher().runInTaskContext(owner, frame, () -> abandoned.accept(cancellation));
            }
        }
    }
}
