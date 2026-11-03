package com.github.unidbg.linux.kernel;

import com.github.unidbg.Emulator;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.UnixEmulator;

/**
 * {@code prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME, addr, len, name)}, which names a range of
 * anonymous memory: the kernel reports the name after the range in {@code /proc/self/maps}.
 *
 * The name belongs to the range, so a process that names part of a mapping gets that part split
 * out of it. The kernel refuses a length that is not a whole number of pages, an option it does
 * not know and a range that is not anonymous memory; a null name clears the name of the range.
 */
public final class AnonymousName {

    /** Bionic's option for naming a range of anonymous memory. */
    public static final int PR_SET_VMA = 0x53564d41;
    private static final int PR_SET_VMA_ANON_NAME = 0;
    /** The kernel keeps at most this many bytes of a range name. */
    private static final int MAX_LENGTH = 80;

    public static int apply(Emulator<?> emulator, long option, long address, long length,
                            long namePointer) {
        if (option != PR_SET_VMA_ANON_NAME) return -UnixEmulator.EINVAL;
        long page = emulator.getPageAlign();
        if (length <= 0 || address % page != 0 || length % page != 0) {
            return -UnixEmulator.EINVAL;
        }
        String name = null;
        if (namePointer != 0) {
            UnidbgPointer pointer = UnidbgPointer.pointer(emulator, namePointer);
            if (pointer == null) return -UnixEmulator.EFAULT;
            name = pointer.getString(0);
            if (name.length() > MAX_LENGTH) return -UnixEmulator.EINVAL;
            if (name.isEmpty()) name = null;
        }
        if (!emulator.getMemory().nameAnonymousRange(address, length, name)) {
            return -UnixEmulator.EBADF;
        }
        return 0;
    }

    private AnonymousName() {
    }
}
