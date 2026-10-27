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

    int getTaskCount();

    boolean sendSignal(int tid, int sig, SignalTask signalTask);

    RunnableTask getRunningTask();

    void submitNativeCall(Task owner, MainTask entry, NativeCallCompletion completion);

    void runInTaskContext(Task owner, RunnableTask frame, Runnable cleanup);

    void addEventSource(DispatcherEventSource source);

    void addThreadLifecycleListener(ThreadLifecycleListener listener);

    void shutdown(long timeout, TimeUnit unit);

}
