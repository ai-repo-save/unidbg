package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.signal.SigSet;
import com.github.unidbg.signal.SignalOps;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.signal.UnixSigSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * 抢占式调度
 */
public class UniThreadDispatcher implements ThreadDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UniThreadDispatcher.class);

    private final List<Task> taskList = new ArrayList<>();
    private final AbstractEmulator<?> emulator;
    private final Map<Task, Deque<NativeCallFrame>> nativeFrames = new IdentityHashMap<>();
    private final Map<RunnableTask, Long> stackPoints = new IdentityHashMap<>();
    private final List<DispatcherEventSource> eventSources = new ArrayList<>();
    private final List<ThreadLifecycleListener> lifecycleListeners = new ArrayList<>();
    private boolean closed;

    public UniThreadDispatcher(AbstractEmulator<?> emulator) {
        this.emulator = emulator;
    }

    private final List<ThreadTask> threadTaskList = new ArrayList<>();

    @Override
    public void addThread(ThreadTask task) {
        if (closed) {
            throw new IllegalStateException("Thread dispatcher is closed");
        }
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
        if (closed) {
            throw new IllegalStateException("Thread dispatcher is closed");
        }
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
        abortNativeFrames(task, new CancellationException("Guest task terminated: " + task.getId()));
        task.destroy(emulator);
        stackPoints.remove(task);
        for (SignalTask signalTask : task.getSignalTaskList()) {
            signalTask.destroy(emulator);
            task.removeSignalTask(signalTask);
        }
        if (!task.isMainThread()) {
            for (ThreadLifecycleListener listener : lifecycleListeners) {
                listener.onThreadTerminated(task);
            }
        }
    }

    @Override
    public void addEventSource(DispatcherEventSource source) {
        eventSources.add(source);
    }

    @Override
    public void addThreadLifecycleListener(ThreadLifecycleListener listener) {
        lifecycleListeners.add(listener);
    }

    @Override
    public void submitNativeCall(Task owner, MainTask entry, NativeCallCompletion completion) {
        if (closed || emulator.isRunning() || !taskList.contains(owner) || !owner.isContextSaved()) {
            throw new IllegalStateException("Native reentry requires a suspended live guest task");
        }
        RunnableTask parent = activeTask(owner);
        parent.restoreContext(emulator);
        long parentStack = stackPoints.getOrDefault(parent, emulator.getMemory().getStackPoint());
        nativeFrames.computeIfAbsent(owner, ignored -> new ArrayDeque<>())
                .addLast(new NativeCallFrame(emulator, entry, completion, parentStack));
        emulator.getMemory().setStackPoint(com.sun.jna.Pointer.nativeValue(emulator.getStackPointer()));
        stackPoints.put(entry, emulator.getMemory().getStackPoint());
        log.debug("event=native_reentry_submitted tid={} entry={}", owner.getId(), entry);
    }

    private RunnableTask activeTask(Task owner) {
        Deque<NativeCallFrame> frames = nativeFrames.get(owner);
        return frames == null || frames.isEmpty() ? owner : frames.getLast().entry;
    }

    private void abortNativeFrames(Task owner, Throwable failure) {
        Deque<NativeCallFrame> frames = nativeFrames.remove(owner);
        if (frames != null) {
            while (!frames.isEmpty()) {
                NativeCallFrame frame = frames.removeLast();
                frame.destroy(emulator);
                stackPoints.remove(frame.entry);
                frame.completion.complete(null, failure);
            }
        }
    }

    private void finishNativeFrame(Task owner, Number result, Throwable failure) {
        Deque<NativeCallFrame> frames = nativeFrames.get(owner);
        NativeCallFrame frame = frames.removeLast();
        frame.destroy(emulator);
        stackPoints.remove(frame.entry);
        activeTask(owner).restoreContext(emulator);
        if (frames.isEmpty()) {
            nativeFrames.remove(owner);
        }
        emulator.getMemory().setStackPoint(frame.parentStackPoint);
        log.debug("event=native_reentry_completed tid={} result={}", owner.getId(), result, failure);
        frame.completion.complete(result, failure);
    }

    private void dispatchEvents() {
        for (DispatcherEventSource source : eventSources) {
            source.dispatchPending();
        }
    }

    @Override
    public void shutdown(long timeout, TimeUnit unit) {
        if (closed) {
            return;
        }
        if (timeout <= 0 || unit == null || emulator.isRunning()) {
            throw new IllegalArgumentException("Shutdown requires a positive budget and an idle backend");
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (DispatcherEventSource source : eventSources) {
            source.beginClose();
        }
        promotePendingThreads();
        for (Task task : new ArrayList<>(taskList)) {
            destroyTask(task);
            taskList.remove(task);
        }
        while (true) {
            dispatchEvents();
            boolean complete = true;
            for (DispatcherEventSource source : eventSources) {
                complete &= source.isClosed();
            }
            if (complete) {
                for (DispatcherEventSource source : eventSources) {
                    if (source.closeFailure() != null) {
                        throw new IllegalStateException("Dispatcher resource cleanup failed", source.closeFailure());
                    }
                }
                closed = true;
                log.debug("event=thread_dispatcher_closed emulator={}", System.identityHashCode(emulator));
                return;
            }
            if (System.nanoTime() >= deadline) {
                IllegalStateException failure = new IllegalStateException("Dispatcher resource cleanup budget exhausted");
                log.error("event=thread_dispatcher_close_failed emulator={} threads={}",
                        System.identityHashCode(emulator), ManagementFactory.getThreadMXBean().dumpAllThreads(true, true), failure);
                throw failure;
            }
            Thread.yield();
        }
    }

    @Override
    public void runThreads(long timeout, TimeUnit unit) {
        if (closed) {
            throw new IllegalStateException("Thread dispatcher is closed");
        }
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
                dispatchEvents();
                if (taskList.isEmpty()) {
                    promotePendingThreads();
                    if (taskList.isEmpty()) {
                        log.debug("event=thread_dispatch_completed emulator={} reason=empty", System.identityHashCode(emulator));
                        return null;
                    }
                }
                for (Task task : new ArrayList<>(taskList)) {
                    if (task.isFinish()) {
                        taskList.remove(task);
                        destroyTask(task);
                        continue;
                    }
                    RunnableTask active = activeTask(task);
                    if (active.canDispatch()) {
                        log.debug("Start dispatch task={}", task);
                        emulator.set(Task.TASK_KEY, task);

                        if (active.isContextSaved()) {
                            active.restoreContext(emulator);
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
                            this.runningTask = active;
                            if (active != task && !active.isContextSaved()) {
                                nativeFrames.get(task).getLast().restoreInitial(emulator);
                            }
                            Long stack = stackPoints.get(active);
                            if (stack != null) {
                                emulator.getMemory().setStackPoint(stack);
                            }
                            Number ret = ((Task) active).dispatch(emulator);
                            log.debug("End dispatch task={}, ret={}", active, ret);
                            if (ret != null) {
                                if (active != task) {
                                    finishNativeFrame(task, ret, null);
                                } else {
                                    task.setResult(emulator, ret);
                                    taskList.remove(task);
                                    destroyTask(task);
                                    if (task.isMainThread()) {
                                        return ret;
                                    }
                                }
                            } else {
                                active.saveContext(emulator);
                                stackPoints.put(active, emulator.getMemory().getStackPoint());
                            }
                        } catch (PopContextException e) {
                            this.runningTask.popContext(emulator);
                        } catch (RuntimeException | Error failure) {
                            if (active != task) {
                                finishNativeFrame(task, null, failure);
                            } else {
                                taskList.remove(task);
                                destroyTask(task);
                                throw failure;
                            }
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
