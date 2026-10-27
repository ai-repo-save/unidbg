package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.linux.android.dvm.host.HostJavaCall;
import com.github.unidbg.linux.android.dvm.host.HostJavaResult;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.thread.ThreadContextSwitchException;
import com.github.unidbg.thread.Waiter;
import java.util.function.Consumer;

public final class GuestJavaWaiter<T> implements Waiter {
    private final HostJavaCall<T> call;
    private final Consumer<HostJavaResult<T>> completion;
    private boolean delivered;

    public GuestJavaWaiter(HostJavaCall<T> call, Consumer<HostJavaResult<T>> completion) {
        this.call = call;
        this.completion = completion;
    }

    public static <T> long suspend(Emulator<?> emulator, HostJavaCall<T> call,
                                  Consumer<HostJavaResult<T>> completion) {
        emulator.getThreadDispatcher().getRunningTask().setWaiter(emulator, new GuestJavaWaiter<>(call, completion));
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
            call.cancel();
        }
    }
}
