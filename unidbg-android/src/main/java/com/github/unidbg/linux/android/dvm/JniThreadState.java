package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.pointer.UnidbgPointer;

final class JniThreadState {
    private final int tid;
    private boolean attached;
    private UnidbgPointer env;
    private boolean daemon;
    private DvmObject<?> pendingException;
    private JniLocalFrames locals;

    JniThreadState(int tid) {
        this.tid = tid;
    }

    int tid() { return tid; }
    boolean attached() { return attached; }
    UnidbgPointer env() { return env; }
    boolean daemon() { return daemon; }
    DvmObject<?> pendingException() { return pendingException; }
    void pendingException(DvmObject<?> value) { pendingException = value; }
    JniLocalFrames locals() { return locals; }

    void attach(UnidbgPointer env, boolean daemon, JniThreadResources resources) {
        this.env = env;
        this.daemon = daemon;
        this.locals = resources.createLocals(this);
        this.attached = true;
    }

    void detached() {
        env.setPointer(0, null);
        env = null;
        locals = null;
        attached = false;
        daemon = false;
    }
}
