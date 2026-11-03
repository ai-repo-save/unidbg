package com.github.unidbg.memory;

import com.github.unidbg.serialize.Serializable;
import com.github.unidbg.memory.mapping.MappedFileRegion;

import java.io.DataOutput;
import java.io.IOException;

public class MemoryMap implements Serializable {

    public final long base;
    public final long size;
    public int prot;
    public final int flags;
    public final long offset;
    public final MappedFileRegion backing;
    public final boolean mayWrite;
    /** The name a process gave the range, which {@code /proc/self/maps} reports as {@code [anon:<name>]}. */
    public String name;

    public MemoryMap(long base, long size, int prot) {
        this(base, size, prot, 0, 0, null, true);
    }

    public MemoryMap(long base, long size, int prot, int flags, long offset, MappedFileRegion backing,
                     boolean mayWrite) {
        this.base = base;
        this.size = size;
        this.prot = prot;
        this.flags = flags;
        this.offset = offset;
        this.backing = backing;
        this.mayWrite = mayWrite;
    }

    public MemoryMap slice(long address, long length, int protection) {
        MemoryMap slice = new MemoryMap(address, length, protection, flags, offset + address - base, backing, mayWrite);
        slice.name = name;
        return slice;
    }

    @Override
    public void serialize(DataOutput out) throws IOException {
        out.writeLong(base);
        out.writeLong(size);
        out.writeInt(prot);
    }

    @Override
    public String toString() {
        return "MemoryMap{" +
                "base=0x" + Long.toHexString(base) +
                ", size=0x" + Long.toHexString(size) +
                ", prot=" + prot +
                '}';
    }
}
