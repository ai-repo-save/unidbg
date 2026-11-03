package com.github.unidbg.linux.struct;

import com.github.unidbg.pointer.UnidbgStructure;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

/** {@code struct sysinfo} of a 64-bit Linux, which {@code sysinfo(2)} fills. */
public class SysInfo64 extends UnidbgStructure {

    public SysInfo64(Pointer p) {
        super(p);
    }

    public long uptime;
    public long[] loads = new long[3];
    public long totalRam;
    public long freeRam;
    public long sharedRam;
    public long bufferRam;
    public long totalSwap;
    public long freeSwap;
    public short procs;
    public short pad;
    public long totalHigh;
    public long freeHigh;
    public int mem_unit;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("uptime", "loads", "totalRam", "freeRam", "sharedRam", "bufferRam", "totalSwap", "freeSwap", "procs", "pad", "totalHigh", "freeHigh", "mem_unit");
    }
}
