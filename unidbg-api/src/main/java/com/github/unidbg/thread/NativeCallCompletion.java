package com.github.unidbg.thread;

public interface NativeCallCompletion {
    void complete(Number result, Throwable failure);
}
