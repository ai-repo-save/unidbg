package com.github.unidbg.linux.android.dvm.host;

import org.scijava.nativelib.NativeLoader;

import java.io.IOException;

final class JvmNativeLibrary {
    static {
        try {
            NativeLoader.loadLibrary("unidbg_jvm_monitor");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static void initialize() {
    }

    private JvmNativeLibrary() {
    }
}
