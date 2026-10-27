package com.github.unidbg.thread;

public interface ThreadLifecycleListener {
    void onThreadTerminated(Task task);
}
