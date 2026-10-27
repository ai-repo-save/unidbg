package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.linux.android.dvm.host.HostJavaCall;
import com.github.unidbg.linux.android.dvm.host.HostJavaResult;
import com.github.unidbg.linux.android.dvm.jni.ProxyTargetException;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Backend interpreter; only the HOST leaf's operation crosses into the host Java thread. */
final class JniPlanExecution {
    private final Emulator<?> emulator;
    private final BaseVM vm;
    private final Function<Jni, Object> backend;
    private final Function<Object, Object> normalizeHost;
    private final Function<Object, Object> validateNormalized;
    private final ToLongFunction<Object> terminal;
    private final ToLongFunction<Throwable> targetFailure;
    private final Deque<JniCallPlan> frames = new ArrayDeque<>();
    private boolean suspended;
    private boolean completed;
    private boolean abandoned;
    private long encoded;

    private JniPlanExecution(Emulator<?> emulator, BaseVM vm, Function<Jni, Object> backend,
                             Function<Object, Object> normalizeHost, Function<Object, Object> validateNormalized,
                             ToLongFunction<Object> terminal, ToLongFunction<Throwable> targetFailure) {
        this.emulator = emulator; this.vm = vm; this.backend = backend; this.normalizeHost = normalizeHost;
        this.validateNormalized = validateNormalized;
        this.terminal = terminal; this.targetFailure = targetFailure;
    }

    static long run(Emulator<?> emulator, BaseVM vm, JniCallPlan plan, Function<Jni, Object> backend,
                    Function<Object, Object> normalizeHost, Function<Object, Object> validateNormalized,
                    ToLongFunction<Object> terminal, ToLongFunction<Throwable> targetFailure) {
        JniPlanExecution execution = new JniPlanExecution(emulator, vm, backend, normalizeHost, validateNormalized, terminal, targetFailure);
        execution.advance(Objects.requireNonNull(plan, "plan"));
        if (!execution.completed) throw new IllegalStateException("Initial JNI plan did not suspend or complete");
        return execution.encoded;
    }

    private void advance(JniCallPlan next) {
        while (next != null && !abandoned) {
            switch (next.kind) {
                case THEN: case OBSERVE:
                    frames.push(next);
                    next = next.source;
                    break;
                case HOST:
                    await(next.operation);
                    return;
                case BACKEND:
                    Object value;
                    try { value = backend.apply(next.provider); }
                    catch (Throwable failure) { next = failed(failure, false); break; }
                    next = succeeded(value);
                    break;
                case VALUE:
                    next = succeeded(next.value);
                    break;
                case FAILURE:
                    next = failed(next.failure, next.targetThrown);
                    break;
                default: throw new IllegalStateException("Unknown JNI plan node");
            }
        }
    }

    private <T> void await(Callable<T> operation) {
        HostJavaCall<T> call;
        try { call = vm.threads().hostRuntime().submit(operation); }
        catch (Throwable failure) { advance(failed(failure, false)); return; }
        if (!suspended) {
            suspended = true;
            GuestJavaWaiter.suspend(emulator, call, this::hostCompleted, this::abandon);
            throw new IllegalStateException("JNI host wait did not suspend");
        }
        GuestJavaWaiter.rearm(emulator, call, this::hostCompleted, this::abandon);
    }

    private void hostCompleted(HostJavaResult<?> result) {
        if (abandoned || completed) throw new IllegalStateException("JNI plan was already retired");
        Throwable failure = result.failure();
        if (failure instanceof ProxyTargetException) {
            advance(failed(((ProxyTargetException) failure).target(), true));
        } else if (failure != null) {
            advance(failed(failure, false));
        } else {
            Object normalized;
            try { normalized = normalizeHost.apply(JniInvocationSupport.successValue(result)); }
            catch (Throwable normalizeFailure) { advance(failed(normalizeFailure, false)); return; }
            advance(succeeded(normalized));
        }
    }

    private JniCallPlan succeeded(Object value) {
        try { value = validateNormalized.apply(value); }
        catch (Throwable failure) { return failed(failure, false); }
        while (!frames.isEmpty()) {
            JniCallPlan frame = frames.pop();
            if (frame.kind == JniCallPlan.Kind.OBSERVE) {
                try { frame.observer.completed(value); }
                catch (Throwable failure) { return failed(failure, false); }
            } else {
                try { return Objects.requireNonNull(frame.success.resume(value), "JNI success continuation plan"); }
                catch (Throwable failure) { return failed(failure, false); }
            }
        }
        finish(terminal.applyAsLong(value));
        return null;
    }

    private JniCallPlan failed(Throwable original, boolean targetThrown) {
        while (!frames.isEmpty()) {
            JniCallPlan frame = frames.pop();
            if (frame.kind == JniCallPlan.Kind.OBSERVE) {
                notifyFailed(frame.observer, original, targetThrown);
            } else if (frame.onFailure != null) {
                try { return Objects.requireNonNull(frame.onFailure.resume(original, targetThrown), "JNI failure continuation plan"); }
                catch (Throwable failure) { original = failure; targetThrown = false; }
            }
        }
        if (!targetThrown) {
            completed = true;
            throw JniInvocationSupport.hostFailure(original);
        }
        try { finish(targetFailure.applyAsLong(original)); }
        catch (RuntimeException | Error bridgeFailure) {
            if (bridgeFailure != original) bridgeFailure.addSuppressed(original);
            throw bridgeFailure;
        }
        return null;
    }

    private void finish(long result) {
        if (completed || abandoned) throw new IllegalStateException("JNI plan completion repeated");
        completed = true;
        encoded = result;
        if (suspended) emulator.getBackend().reg_write(
                emulator.is64Bit() ? Arm64Const.UC_ARM64_REG_X0 : ArmConst.UC_ARM_REG_R0,
                emulator.is64Bit() ? result : (int) result);
    }

    private void abandon(Throwable cancellation) {
        if (completed || abandoned) return;
        abandoned = true;
        while (!frames.isEmpty()) {
            JniCallPlan frame = frames.pop();
            if (frame.kind == JniCallPlan.Kind.OBSERVE) notifyFailed(frame.observer, cancellation, false);
        }
    }

    private static void notifyFailed(JniCallPlan.Observer observer, Throwable original, boolean targetThrown) {
        try { observer.failed(original, targetThrown); }
        catch (Throwable failure) { if (failure != original) original.addSuppressed(failure); }
    }
}
