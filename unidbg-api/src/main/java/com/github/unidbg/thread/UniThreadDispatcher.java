package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.signal.SigSet;
import com.github.unidbg.signal.SignalOps;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.signal.UnixSigSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 抢占式调度
 */
public class UniThreadDispatcher implements ThreadDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UniThreadDispatcher.class);

    private final List<Task> taskList = new ArrayList<>();
    private final AbstractEmulator<?> emulator;

    public UniThreadDispatcher(AbstractEmulator<?> emulator) {
        this.emulator = emulator;
    }

    private final List<ThreadTask> threadTaskList = new ArrayList<>();

    @Override
    public void addThread(ThreadTask task) {
        threadTaskList.add(task);
    }

    @Override
    public List<Task> getTaskList() {
        return taskList;
    }

    @Override
    public boolean sendSignal(int tid, int sig, SignalTask signalTask) {
        List<Task> list = new ArrayList<>();
        list.addAll(taskList);
        list.addAll(threadTaskList);
        boolean ret = false;
        for (Task task : list) {
            SignalOps signalOps = null;
            if (tid == 0 && task.isMainThread()) {
                signalOps = this;
            }
            if (tid == task.getId()) {
                signalOps = task;
            }
            if (signalOps == null) {
                continue;
            }
            SigSet sigSet = signalOps.getSigMaskSet();
            SigSet sigPendingSet = signalOps.getSigPendingSet();
            if (sigPendingSet == null) {
                sigPendingSet = new UnixSigSet(0);
                signalOps.setSigPendingSet(sigPendingSet);
            }
            if (sigSet != null && sigSet.containsSigNumber(sig)) {
                sigPendingSet.addSigNumber(sig);
                return false;
            }
            if (signalTask != null) {
                task.addSignalTask(signalTask);
                if (log.isTraceEnabled()) {
                    emulator.attach().debug("Signal dispatched: sig=" + sig);
                }
            } else {
                sigPendingSet.addSigNumber(sig);
            }
            ret = true;
            break;
        }
        return ret;
    }

    private RunnableTask runningTask;

    @Override
    public RunnableTask getRunningTask() {
        return runningTask;
    }

    @Override
    public Number runMainForResult(MainTask main) {
        taskList.add(0, main);

        log.debug("runMainForResult main={}", main);

        Number ret;
        try {
            ret = run(0, null);
        } catch (RuntimeException | Error failure) {
            if (taskList.remove(main)) {
                destroyTask(main);
            }
            throw failure;
        }
        for (Iterator<Task> iterator = taskList.iterator(); iterator.hasNext(); ) {
            Task task = iterator.next();
            if (task.isFinish()) {
                log.debug("Finish task={}", task);
                iterator.remove();
                destroyTask(task);
            }
        }
        return ret;
    }

    private void destroyTask(Task task) {
        task.destroy(emulator);
        for (SignalTask signalTask : task.getSignalTaskList()) {
            signalTask.destroy(emulator);
            task.removeSignalTask(signalTask);
        }
    }

    @Override
    public void runThreads(long timeout, TimeUnit unit) {
        if (timeout <= 0 || unit == null) {
            throw new IllegalArgumentException("Invalid timeout.");
        }
        Backend backend = emulator.getBackend();
        long context = backend.context_alloc();
        backend.context_save(context);
        long stack = emulator.getMemory().getStackPoint();
        try {
            run(timeout, unit);
        } finally {
            emulator.getMemory().setStackPoint(stack);
            backend.context_restore(context);
            backend.context_free(context);
        }
    }

    private Number run(long timeout, TimeUnit unit) {
        try {
            long start = System.currentTimeMillis();
            while (true) {
                if (taskList.isEmpty()) {
                    promotePendingThreads();
                    if (taskList.isEmpty()) {
                        log.debug("event=thread_dispatch_completed emulator={} reason=empty", System.identityHashCode(emulator));
                        return null;
                    }
                }
                for (Iterator<Task> iterator = taskList.iterator(); iterator.hasNext(); ) {
                    Task task = iterator.next();
                    if (task.isFinish()) {
                        iterator.remove();
                        destroyTask(task);
                        continue;
                    }
                    if (task.canDispatch()) {
                        log.debug("Start dispatch task={}", task);
                        emulator.set(Task.TASK_KEY, task);

                        if(task.isContextSaved()) {
                            task.restoreContext(emulator);
                            for (SignalTask signalTask : task.getSignalTaskList()) {
                                if (signalTask.canDispatch()) {
                                    log.debug("Start run signalTask={}", signalTask);
                                    SignalOps ops = task.isMainThread() ? this : task;
                                    try {
                                        this.runningTask = signalTask;
                                        Number ret = signalTask.callHandler(ops, emulator);
                                        log.debug("End run signalTask={}, ret={}", signalTask, ret);
                                        if (ret != null) {
                                            signalTask.setResult(emulator, ret);
                                            signalTask.destroy(emulator);
                                            task.removeSignalTask(signalTask);
                                        } else {
                                            signalTask.saveContext(emulator);
                                        }
                                    } catch (PopContextException e) {
                                        this.runningTask.popContext(emulator);
                                    } catch (RuntimeException | Error failure) {
                                        signalTask.destroy(emulator);
                                        task.removeSignalTask(signalTask);
                                        throw failure;
                                    }
                                } else {
                                    log.debug("Skip call handler signalTask={}", signalTask);
                                }
                            }
                        }

                        try {
                            this.runningTask = task;
                            Number ret = task.dispatch(emulator);
                            log.debug("End dispatch task={}, ret={}", task, ret);
                            if (ret != null) {
                                task.setResult(emulator, ret);
                                destroyTask(task);
                                iterator.remove();
                                if(task.isMainThread()) {
                                    return ret;
                                }
                            } else {
                                task.saveContext(emulator);
                            }
                        } catch(PopContextException e) {
                            this.runningTask.popContext(emulator);
                        } catch (RuntimeException | Error failure) {
                            iterator.remove();
                            destroyTask(task);
                            throw failure;
                        }
                    }
                }

                promotePendingThreads();

                if (timeout > 0 && unit != null &&
                        System.currentTimeMillis() - start >= unit.toMillis(timeout)) {
                    log.debug("event=thread_dispatch_budget_exhausted emulator={} active={}", System.identityHashCode(emulator), taskList.size());
                    return null;
                }
                if (taskList.isEmpty()) {
                    log.debug("event=thread_dispatch_completed emulator={} reason=empty", System.identityHashCode(emulator));
                    return null;
                }

                Thread.yield();
            }
        } finally {
            this.runningTask = null;
            emulator.set(Task.TASK_KEY, null);
        }
    }

    private void promotePendingThreads() {
        if (!threadTaskList.isEmpty()) {
            log.debug("event=thread_dispatch_pending_promoted emulator={} count={}", System.identityHashCode(emulator), threadTaskList.size());
        }
        Collections.reverse(threadTaskList);
        for (ThreadTask thread : threadTaskList) {
            taskList.add(0, thread);
        }
        threadTaskList.clear();
    }

    @Override
    public int getTaskCount() {
        return taskList.size() + threadTaskList.size();
    }

    private SigSet mainThreadSigMaskSet;
    private SigSet mainThreadSigPendingSet;

    @Override
    public SigSet getSigMaskSet() {
        return mainThreadSigMaskSet;
    }

    @Override
    public void setSigMaskSet(SigSet sigMaskSet) {
        this.mainThreadSigMaskSet = sigMaskSet;
    }

    @Override
    public SigSet getSigPendingSet() {
        return mainThreadSigPendingSet;
    }

    @Override
    public void setSigPendingSet(SigSet sigPendingSet) {
        this.mainThreadSigPendingSet = sigPendingSet;
    }
}
