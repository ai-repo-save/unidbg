package com.github.unidbg.linux.android.dvm.host;

final class JvmMonitorBridge {
    static {
        JvmNativeLibrary.initialize();
    }

    static void initialize() {
    }

    static native int enter(Object target);
    static native int exit(Object target);

    private JvmMonitorBridge() {
    }
}
