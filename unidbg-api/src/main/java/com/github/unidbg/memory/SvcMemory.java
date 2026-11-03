package com.github.unidbg.memory;

import com.github.unidbg.Svc;
import com.github.unidbg.pointer.UnidbgPointer;

import java.util.Collection;

public interface SvcMemory extends StackMemory {

    UnidbgPointer allocate(int size, String label);

    UnidbgPointer allocateSymbolName(String name);

    UnidbgPointer registerSvc(Svc svc);

    Svc getSvc(int svcNumber);

    MemRegion findRegion(long addr);

    /**
     * Maps a page the runtime needs for its svc code, which the guest address space reports as
     * that page's own region.
     */
    void mapPage(long address, int size, int perms, String label);

    /**
     * The pages {@link #mapPage} mapped: the svc page the stubs live in and the traps a svc
     * return address points into. They hold one mapping each, whatever {@link #allocate} placed
     * inside them.
     */
    Collection<MemRegion> getRegions();

    long getBase();
    int getSize();

}
