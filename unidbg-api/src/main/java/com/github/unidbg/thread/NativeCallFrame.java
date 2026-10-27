package com.github.unidbg.thread;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;

final class NativeCallFrame {
    final MainTask entry;
    final NativeCallCompletion completion;
    final long parentStackPoint;
    private long initialContext;

    NativeCallFrame(Emulator<?> emulator, MainTask entry, NativeCallCompletion completion, long parentStackPoint) {
        this.entry = entry;
        this.completion = completion;
        this.parentStackPoint = parentStackPoint;
        Backend backend = emulator.getBackend();
        initialContext = backend.context_alloc();
        backend.context_save(initialContext);
    }

    void restoreInitial(Emulator<?> emulator) {
        Backend backend = emulator.getBackend();
        backend.context_restore(initialContext);
        backend.context_free(initialContext);
        initialContext = 0;
    }

    void destroy(Emulator<?> emulator) {
        if (initialContext != 0) {
            emulator.getBackend().context_free(initialContext);
            initialContext = 0;
        }
        entry.destroy(emulator);
    }
}
