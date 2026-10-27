package com.github.unidbg.thread;

public interface DispatcherEventSource {
    void dispatchPending();
    void beginClose();
    boolean isClosed();
    Throwable closeFailure();
}
