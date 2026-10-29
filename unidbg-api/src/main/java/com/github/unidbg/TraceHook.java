package com.github.unidbg;

import java.io.PrintStream;

public interface TraceHook {

    void setRedirect(PrintStream redirect);

    /** Pauses a code trace before disassembly while retaining its backend registration and redirect. */
    default void pause() {
        throw new UnsupportedOperationException("pause is supported by code traces only");
    }

    /** Resumes a paused code trace without registering a new backend hook. */
    default void resume() {
        throw new UnsupportedOperationException("resume is supported by code traces only");
    }

    void stopTrace();

}
