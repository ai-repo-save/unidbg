package com.github.unidbg.linux.android.dvm.host;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.thread.MainTask;

public final class GuestNativeGateway {
    public interface Request<T> {
        MainTask prepare(AbstractEmulator<?> emulator) throws Exception;
        T complete(Number result) throws Exception;
        default void close() {
        }
    }

    private final GuestJavaRuntime runtime;

    GuestNativeGateway(GuestJavaRuntime runtime) {
        this.runtime = runtime;
    }

    public <T> T invoke(Request<T> request) throws Exception {
        return runtime.invokeNative(request);
    }
}
