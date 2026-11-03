package com.github.unidbg.linux.kernel;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.AndroidSyscallHandler;
import com.github.unidbg.linux.GuestCredentials;

/**
 * The capability sets of the emulated process, which {@code capget(2)} reports and
 * {@code /proc/self/status} renders.
 *
 * The sets are what the kernel gives a process that executed with the credentials the runtime
 * runs it under: the effective user id decides. A process that runs as root keeps every
 * capability of the bounding set in its permitted and effective sets; a process that runs as
 * another user keeps none. The bounding set holds every capability the modeled kernel numbers,
 * and the inheritable and ambient sets are empty.
 */
public final class ProcessCapabilities {

    /**
     * {@code CAP_AUDIT_READ}, the last capability the modeled kernel numbers. The recorded device
     * reports it as the highest bit of the full set it gives a root process.
     */
    public static final int CAP_LAST_CAP = 37;

    /** Every capability of the modeled kernel. */
    public static final long BOUNDING_SET = (1L << (CAP_LAST_CAP + 1)) - 1;

    public final long inheritable;
    public final long permitted;
    public final long effective;
    public final long bounding;
    public final long ambient;

    private ProcessCapabilities(long inheritable, long permitted, long effective, long bounding,
                                long ambient) {
        this.inheritable = inheritable;
        this.permitted = permitted;
        this.effective = effective;
        this.bounding = bounding;
        this.ambient = ambient;
    }

    /** The capabilities of the process that runs with {@code credentials}. */
    public static ProcessCapabilities of(GuestCredentials credentials) {
        int uid = credentials.effectiveUid.orElse(credentials.realUid.orElse(0));
        long privileged = uid == 0 ? BOUNDING_SET : 0;
        return new ProcessCapabilities(0, privileged, privileged, BOUNDING_SET, 0);
    }

    /** The capabilities of the emulated process. */
    public static ProcessCapabilities of(Emulator<AndroidFileIO> emulator) {
        AndroidSyscallHandler handler = (AndroidSyscallHandler) emulator.getSyscallHandler();
        return of(handler.getGuestCredentials());
    }
}
