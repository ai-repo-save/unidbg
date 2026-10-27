package com.github.unidbg.linux.android.dvm.host;

public final class HostJavaResult<T> {
    private final T value;
    private final Throwable failure;

    private HostJavaResult(T value, Throwable failure) {
        this.value = value;
        this.failure = failure;
    }

    static <T> HostJavaResult<T> success(T value) {
        return new HostJavaResult<>(value, null);
    }

    static <T> HostJavaResult<T> failure(Throwable failure) {
        return new HostJavaResult<>(null, failure);
    }

    public Throwable failure() {
        return failure;
    }

    public T get() throws Exception {
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure != null) {
            throw (Exception) failure;
        }
        return value;
    }
}
