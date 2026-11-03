package com.github.unidbg.linux.struct;

import com.github.unidbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

/**
 * {@code struct rusage} of a 32-bit Linux, which {@code getrusage(2)} fills. A timeval is two
 * 32-bit longs, so the two CPU times take the first four fields.
 */
public class Rusage32 extends UnidbgStructure {

    public Rusage32(Pointer p) {
        super(p);
    }

    public int ru_utime_sec;
    public int ru_utime_usec;
    public int ru_stime_sec;
    public int ru_stime_usec;
    public int ru_maxrss;
    public int ru_ixrss;
    public int ru_idrss;
    public int ru_isrss;
    public int ru_minflt;
    public int ru_majflt;
    public int ru_nswap;
    public int ru_inblock;
    public int ru_oublock;
    public int ru_msgsnd;
    public int ru_msgrcv;
    public int ru_nsignals;
    public int ru_nvcsw;
    public int ru_nivcsw;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("ru_utime_sec", "ru_utime_usec", "ru_stime_sec", "ru_stime_usec",
                "ru_maxrss", "ru_ixrss", "ru_idrss", "ru_isrss", "ru_minflt", "ru_majflt", "ru_nswap",
                "ru_inblock", "ru_oublock", "ru_msgsnd", "ru_msgrcv", "ru_nsignals", "ru_nvcsw",
                "ru_nivcsw");
    }
}
