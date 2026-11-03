package com.github.unidbg.linux.struct;

import com.github.unidbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

/**
 * {@code struct rusage} of a 64-bit Linux, which {@code getrusage(2)} fills. A timeval is two
 * longs, so the two CPU times take the first four fields.
 */
public class Rusage64 extends UnidbgStructure {

    public Rusage64(Pointer p) {
        super(p);
    }

    public long ru_utime_sec;
    public long ru_utime_usec;
    public long ru_stime_sec;
    public long ru_stime_usec;
    public long ru_maxrss;
    public long ru_ixrss;
    public long ru_idrss;
    public long ru_isrss;
    public long ru_minflt;
    public long ru_majflt;
    public long ru_nswap;
    public long ru_inblock;
    public long ru_oublock;
    public long ru_msgsnd;
    public long ru_msgrcv;
    public long ru_nsignals;
    public long ru_nvcsw;
    public long ru_nivcsw;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("ru_utime_sec", "ru_utime_usec", "ru_stime_sec", "ru_stime_usec",
                "ru_maxrss", "ru_ixrss", "ru_idrss", "ru_isrss", "ru_minflt", "ru_majflt", "ru_nswap",
                "ru_inblock", "ru_oublock", "ru_msgsnd", "ru_msgrcv", "ru_nsignals", "ru_nvcsw",
                "ru_nivcsw");
    }
}
