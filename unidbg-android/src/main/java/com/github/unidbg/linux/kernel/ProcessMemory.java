package com.github.unidbg.linux.kernel;

import com.github.unidbg.Emulator;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryMap;

/**
 * The memory the runtime maps for the emulated process: the ranges of its address space and the
 * stack the runtime allocated for it.
 *
 * {@code /proc/self/status} and {@code /proc/self/stat} report the mapped bytes as the process
 * size, and {@code getrusage(2)} reports them as the maximum resident set.
 */
public final class ProcessMemory {

    /** The bytes of every range the runtime mapped for the process, its stack included. */
    public static long mappedBytes(Emulator<?> emulator) {
        long total = 0;
        for (MemoryMap map : emulator.getMemory().getMemoryMap()) total += map.size;
        return total;
    }

    /** The stack the runtime allocated for the main thread. */
    public static long stackBytes(Emulator<?> emulator) {
        return (long) Memory.STACK_SIZE_OF_PAGE * emulator.getPageAlign();
    }

    private ProcessMemory() {
    }
}
