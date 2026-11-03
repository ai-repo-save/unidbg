package com.github.unidbg.linux.struct;

import com.github.unidbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

/** {@code struct rlimit} of a 32-bit Linux, which {@code getrlimit(2)} fills. */
public class RLimit32 extends UnidbgStructure {

    public int rlim_cur;
    public int rlim_max;

    public RLimit32(Pointer p) {
        super(p);
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("rlim_cur", "rlim_max");
    }

}
