package com.github.unidbg.file;

import java.util.Objects;

/** Null means unhandled, as in IOResolver.resolve; ENOENT is an authoritative absence. */
public final class FileQueryResult<V> {
    public enum Status { PRESENT, UNKNOWN, ERROR, FALLBACK }

    public final V value;
    public final int errno;
    private final Status status;

    private FileQueryResult(Status status, V value, int errno) {
        this.status = status;
        this.value = value;
        this.errno = errno;
    }

    public static <V> FileQueryResult<V> success(V value) {
        return new FileQueryResult<>(Status.PRESENT, Objects.requireNonNull(value), 0);
    }

    public static <V> FileQueryResult<V> fallback(V value) {
        return new FileQueryResult<>(Status.FALLBACK, Objects.requireNonNull(value), 0);
    }

    public static <V> FileQueryResult<V> unknown() {
        return new FileQueryResult<>(Status.UNKNOWN, null, 0);
    }

    public static <V> FileQueryResult<V> failed(int errno) {
        if (errno <= 0) throw new IllegalArgumentException("errno=" + errno);
        return new FileQueryResult<>(Status.ERROR, null, errno);
    }

    public Status status() { return status; }
    public boolean isSuccess() { return status == Status.PRESENT; }
    public boolean isFallback() { return status == Status.FALLBACK; }
}
