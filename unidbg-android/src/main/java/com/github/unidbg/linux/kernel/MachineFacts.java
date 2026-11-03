package com.github.unidbg.linux.kernel;

/**
 * The machine the emulated process runs on: the memory and the processors the runtime gives it.
 *
 * The guest runs inside the host process, so the memory the runtime holds out for it is that
 * process's heap ceiling and the memory still free below it, and the processors the guest may run
 * on are the ones the host lets the runtime use.
 */
public final class MachineFacts {

    /** The memory the runtime may give the emulated process. */
    public static long totalMemoryBytes() {
        return Runtime.getRuntime().maxMemory();
    }

    /** The memory of {@link #totalMemoryBytes()} the emulated process has not taken yet. */
    public static long freeMemoryBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }

    /** The processors the host lets the runtime use. */
    public static int processors() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * The processor a guest thread runs on. The runtime dispatches guest threads on host threads,
     * pins neither and does not report where a thread last ran, so a guest thread keeps one
     * processor of the emulated machine: the index of its thread id in the processor set.
     */
    public static int processorOf(int threadId) {
        return Math.floorMod(threadId, processors());
    }

    private MachineFacts() {
    }
}
