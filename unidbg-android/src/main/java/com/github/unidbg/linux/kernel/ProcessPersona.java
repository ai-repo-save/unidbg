package com.github.unidbg.linux.kernel;

import com.github.unidbg.Emulator;

/**
 * The execution domain of the emulated process, which {@code personality(2)} reports and sets.
 *
 * A process starts in {@code PER_LINUX} and keeps the domain it was given: a call that names a
 * domain answers the domain it replaced, and a query ({@code 0xffffffff}) reports the domain the
 * process holds.
 */
public final class ProcessPersona {

    /** {@code PER_LINUX}: the domain a process starts in. */
    public static final int PER_LINUX = 0x0000;

    /** The value {@code personality(2)} takes to query the domain instead of setting one. */
    public static final long QUERY = 0xffffffffL;

    private static final String KEY = "unidbg.processPersona";

    private int persona = PER_LINUX;

    private ProcessPersona() {
    }

    /** The domain of the emulated process, shared by every reader in it. */
    public static ProcessPersona of(Emulator<?> emulator) {
        ProcessPersona persona = emulator.get(KEY);
        if (persona == null) {
            persona = new ProcessPersona();
            emulator.set(KEY, persona);
        }
        return persona;
    }

    /** The domain the process holds. */
    public int current() {
        return persona;
    }

    /** Sets the domain and answers the one it replaced. */
    public int replace(int persona) {
        int previous = this.persona;
        this.persona = persona;
        return previous;
    }
}
