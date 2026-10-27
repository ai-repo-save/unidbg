package com.github.unidbg.linux.android.dvm.jni;

/** Carries only exceptions raised by the reflected target, not visitor or bridge failures. */
public final class ProxyTargetException extends RuntimeException {
    ProxyTargetException(Throwable target) {
        super("Reflected Java target raised an exception", target);
    }

    public Throwable target() { return getCause(); }
}
