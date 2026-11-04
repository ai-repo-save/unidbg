package com.github.unidbg.thread;

import com.github.unidbg.signal.SignalOps;
import com.github.unidbg.signal.SignalTask;

import java.util.List;
import java.util.concurrent.TimeUnit;

public interface ThreadDispatcher extends SignalOps {

    void addThread(ThreadTask task);

    List<Task> getTaskList();

    Number runMainForResult(MainTask main);

    /** Runs pending and active threads until completion or budget exhaustion; an empty dispatcher is complete. */
    void runThreads(long timeout, TimeUnit unit);

    /**
     * Dispatches every guest thread that can run once, including threads queued meanwhile.
     * <p>
     * A caller that hands this emulator's host thread to another emulator needs a completion
     * condition this emulator's own progress defines, because a budget would let the host's speed
     * decide how much ran. A pass that dispatches nothing is that condition: every guest thread
     * waits.
     *
     * @return the number of guest threads this pass ran
     */
    int dispatchPass();

    int getTaskCount();

    boolean sendSignal(int tid, int sig, SignalTask signalTask);

    RunnableTask getRunningTask();

    void submitNativeCall(Task owner, MainTask entry, NativeCallCompletion completion);

    void runInTaskContext(Task owner, RunnableTask frame, Runnable cleanup);

    void addEventSource(DispatcherEventSource source);

    void addThreadLifecycleListener(ThreadLifecycleListener listener);

    void shutdown(long timeout, TimeUnit unit);

}
