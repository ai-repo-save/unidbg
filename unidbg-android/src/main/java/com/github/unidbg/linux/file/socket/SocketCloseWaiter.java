package com.github.unidbg.linux.file.socket;

import com.github.unidbg.Emulator;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.thread.RunnableTask;
import com.github.unidbg.thread.ThreadContextSwitchException;
import com.github.unidbg.thread.Waiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.util.function.IntUnaryOperator;

public final class SocketCloseWaiter implements Waiter {
    private static final Logger log = LoggerFactory.getLogger(SocketCloseWaiter.class);
    private final NativeSocketCloseService.Request request;
    private final IntUnaryOperator completion;
    private boolean applied, detached;
    private int result;

    private SocketCloseWaiter(NativeSocketCloseService.Request request, IntUnaryOperator completion) {
        this.request = request;
        this.completion = completion;
    }

    public static int await(Emulator<?> emulator, NativeSocketCloseService.Request request, IntUnaryOperator completion) {
        SocketCloseWaiter waiter = new SocketCloseWaiter(request, completion);
        if (waiter.canDispatch()) {
            return waiter.result();
        }
        RunnableTask task = emulator.getThreadDispatcher().getRunningTask();
        if (task == null) {
            throw new IllegalStateException("A suspending socket close requires a dispatcher task");
        }
        task.setWaiter(emulator, waiter);
        throw new ThreadContextSwitchException();
    }

    @Override
    public boolean canDispatch() {
        return request.isDone();
    }

    private int result() {
        if (!applied) {
            int raw = request.result();
            applied = true;
            try {
                result = completion.applyAsInt(raw);
            } catch (ThreadContextSwitchException failure) {
                throw new IllegalStateException("Socket close completion must not yield", failure);
            }
        }
        return result;
    }

    @Override
    public void onContinueRun(Emulator<?> emulator) {
        emulator.getBackend().reg_write(emulator.is64Bit() ? Arm64Const.UC_ARM64_REG_X0 : ArmConst.UC_ARM_REG_R0, result());
    }

    @Override
    public void onSignal(SignalTask task) {
        log.debug("event=native_socket_close_signal_deferred request={}", request.id());
    }

    @Override
    public void onDestroy(Emulator<?> emulator) {
        if (!detached && !applied) {
            detached = true;
            log.debug("event=native_socket_close_waiter_detached request={}", request.id());
        }
    }
}
