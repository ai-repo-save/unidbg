package com.github.unidbg.unix.time;

import com.github.unidbg.Emulator;
import com.github.unidbg.thread.Task;
import com.github.unidbg.thread.UniThreadDispatcher;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;

/**
 * The CPU time the emulated process and each of its threads spent running, which answers
 * {@code CLOCK_PROCESS_CPUTIME_ID} and {@code CLOCK_THREAD_CPUTIME_ID}.
 *
 * The emulator dispatches every guest thread on one host thread, so CPU time is attributed at
 * dispatch boundaries: {@link UniThreadDispatcher} opens a slice around each
 * {@link Task#dispatch} and the slice's host CPU time goes to the dispatching thread and to the
 * process. Time the guest spends blocked, such as in {@code nanosleep}, elapses between slices
 * and therefore counts for neither clock. A dispatch nested inside another one, such as a signal
 * handler run from a blocked task, belongs to the enclosing slice.
 */
public final class CpuTimeSource {

    private static final String KEY = "unidbg.cpuTimeSource";

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    /** CPU time of the slices that ended, which is the process's total so far. */
    private long processNanos;
    /** CPU time of the slices that ended, per guest thread id. */
    private final Map<Integer, Long> threadNanos = new HashMap<>();
    private int depth;
    private Task sliceTask;
    private long sliceStartNanos;

    private CpuTimeSource() {
    }

    /** The CPU clocks of the emulated process, shared by every reader in it. */
    public static CpuTimeSource of(Emulator<?> emulator) {
        CpuTimeSource source = emulator.get(KEY);
        if (source == null) {
            source = new CpuTimeSource();
            emulator.set(KEY, source);
        }
        return source;
    }

    /** Starts measuring the time {@code task} spends running. */
    public void beginSlice(Task task) {
        if (depth++ == 0) {
            sliceTask = task;
            sliceStartNanos = threads.getCurrentThreadCpuTime();
        }
    }

    /** Charges the slice's CPU time to the task that opened it and to the process. */
    public void endSlice() {
        if (--depth == 0) {
            long spent = threads.getCurrentThreadCpuTime() - sliceStartNanos;
            processNanos += spent;
            Long previous = threadNanos.get(sliceTask.getId());
            threadNanos.put(sliceTask.getId(), previous == null ? spent : previous + spent);
            sliceTask = null;
        }
    }

    /**
     * Drops the CPU time of a guest thread that exited; the process keeps it. The tasks of the
     * main thread are the host calls into the guest, so its id outlives each of them.
     */
    public void removeThread(Task task) {
        if (!task.isMainThread()) {
            threadNanos.remove(task.getId());
        }
    }

    /** CPU time the whole process spent running, including the slice in progress. */
    public long processNanos() {
        return processNanos + openSliceNanos();
    }

    /** CPU time the thread of the slice in progress spent running, none outside a slice. */
    public long threadNanos() {
        if (sliceTask == null) {
            return 0;
        }
        Long spent = threadNanos.get(sliceTask.getId());
        return (spent == null ? 0 : spent) + openSliceNanos();
    }

    private long openSliceNanos() {
        return depth == 0 ? 0 : threads.getCurrentThreadCpuTime() - sliceStartNanos;
    }
}
