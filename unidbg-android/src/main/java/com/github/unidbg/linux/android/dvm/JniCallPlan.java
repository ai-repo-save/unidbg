package com.github.unidbg.linux.android.dvm;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * An explicit continuation tree for one JNI binder's original operation, overload and decoded arguments.
 * Backend providers invoke that same operation; this is not a cross-descriptor invocation API.
 * Host operations contain only host values. Normalization, continuations and observers run on backend.
 */
public final class JniCallPlan {
    @FunctionalInterface
    public interface BackendContinuation {
        JniCallPlan resume(Object normalizedJniValue) throws Throwable;
    }

    @FunctionalInterface
    public interface BackendFailureContinuation {
        JniCallPlan resume(Throwable original, boolean targetThrown) throws Throwable;
    }

    public interface Observer {
        void completed(Object normalizedJniValue) throws Throwable;
        void failed(Throwable original, boolean targetThrown) throws Throwable;
    }

    enum Kind { HOST, BACKEND, VALUE, FAILURE, THEN, OBSERVE }
    final Kind kind;
    final Callable<?> operation;
    final Jni provider;
    final Object value;
    final Throwable failure;
    final boolean targetThrown;
    final JniCallPlan source;
    final BackendContinuation success;
    final BackendFailureContinuation onFailure;
    final Observer observer;

    private JniCallPlan(Kind kind, Callable<?> operation, Jni provider, Object value, Throwable failure,
                        boolean targetThrown, JniCallPlan source, BackendContinuation success,
                        BackendFailureContinuation onFailure, Observer observer) {
        this.kind = kind; this.operation = operation; this.provider = provider; this.value = value;
        this.failure = failure; this.targetThrown = targetThrown; this.source = source;
        this.success = success; this.onFailure = onFailure; this.observer = observer;
    }

    public static JniCallPlan host(Callable<?> pureHostOperation) {
        return new JniCallPlan(Kind.HOST, Objects.requireNonNull(pureHostOperation), null, null, null,
                false, null, null, null, null);
    }

    /** Reuses only the original operation/overload/arguments bound to this invocation. */
    public static JniCallPlan backend(Jni modeledProvider) {
        return new JniCallPlan(Kind.BACKEND, null, Objects.requireNonNull(modeledProvider), null, null,
                false, null, null, null, null);
    }

    /** Object results are DvmObject values, not native handles or raw host objects. */
    public static JniCallPlan value(Object normalizedJniValue) {
        return new JniCallPlan(Kind.VALUE, null, null, normalizedJniValue, null, false, null, null, null, null);
    }

    /** An explicit unhandled failure; classification is preserved, never inferred from identity. */
    public static JniCallPlan failure(Throwable original, boolean targetThrown) {
        return new JniCallPlan(Kind.FAILURE, null, null, null, Objects.requireNonNull(original), targetThrown,
                null, null, null, null);
    }

    public JniCallPlan then(BackendContinuation success) {
        return new JniCallPlan(Kind.THEN, null, null, null, null, false, this,
                Objects.requireNonNull(success), null, null);
    }

    /** Recovery requires returning an explicit value or subsequent plan; thrown errors are backend failures. */
    public JniCallPlan then(BackendContinuation success, BackendFailureContinuation failure) {
        return new JniCallPlan(Kind.THEN, null, null, null, null, false, this,
                Objects.requireNonNull(success), Objects.requireNonNull(failure), null);
    }

    /** Successful observer errors propagate; failed observer errors are suppressed on the original failure. */
    public JniCallPlan observe(Observer observer) {
        return new JniCallPlan(Kind.OBSERVE, null, null, null, null, false, this,
                null, null, Objects.requireNonNull(observer));
    }
}
