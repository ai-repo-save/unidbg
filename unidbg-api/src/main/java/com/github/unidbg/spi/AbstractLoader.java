package com.github.unidbg.spi;

import com.github.unidbg.Alignment;
import com.github.unidbg.Emulator;
import com.github.unidbg.LibraryResolver;
import com.github.unidbg.Module;
import com.github.unidbg.ModuleListener;
import com.github.unidbg.arm.ARM;
import com.github.unidbg.arm.ARMEmulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.file.NewFileIO;
import com.github.unidbg.hook.HookListener;
import com.github.unidbg.memory.MMapListener;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.memory.mapping.FileMappingManager;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.thread.BaseTask;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.UnixSyscallHandler;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;
import unicorn.UnicornConst;

import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public abstract class AbstractLoader<T extends NewFileIO> implements Memory, Loader {

    private static final Logger log = LoggerFactory.getLogger(AbstractLoader.class);

    protected final Backend backend;
    protected final Emulator<T> emulator;
    protected final UnixSyscallHandler<T> syscallHandler;

    protected long sp;
    protected long mmapBaseAddress;
    protected final Map<Long, MemoryMap> memoryMap = new TreeMap<>();
    protected final FileMappingManager fileMappings;

    protected Boolean[] threadStackMap = new Boolean[Memory.MAX_THREADS];

    protected MMapListener mMapListener;


    @Override
    public int allocateThreadIndex(){
        for(int i = 0; i<threadStackMap.length ;  i++){
            if(threadStackMap[i]==null || !threadStackMap[i]){
                threadStackMap[i] = true;
                return i;
            }
        }
        throw new UnsupportedOperationException("Threads is too much, max is = " + threadStackMap.length);
    }

    @Override
    public void freeThreadIndex(int index){
        if(index>=0) {
            threadStackMap[index] = false;
        }
    }

    @Override
    public UnidbgPointer allocateThreadStack(int index){
        if(!threadStackMap[index]) {
            throw new UnsupportedOperationException("Your ThreadStackIndex doesn't exist, it must come from allocateThreadIndex(), index = " + index);
        }
        long threadStackBase = Memory.STACK_BASE - (long) Memory.STACK_SIZE_OF_MAIN_PAGE * emulator.getPageAlign();
        long address = threadStackBase - (long) BaseTask.THREAD_STACK_PAGE * index * emulator.getPageAlign();
        if (log.isDebugEnabled()) {
            log.debug("allocateThreadStackAddress=0x{}", Long.toHexString(address));
        }
        return UnidbgPointer.pointer(emulator, address);
    }

    @Override
    public void setMMapListener(MMapListener listener) {
        this.mMapListener = listener;
    }

    @Override
    public MMapListener getMMapListener() {
        return mMapListener;
    }

    protected void setMMapBaseAddress(long address) {
        this.mmapBaseAddress = address;

        if (log.isDebugEnabled()) {
            log.debug("setMMapBaseAddress=0x{}", Long.toHexString(address));
        }
    }

    public AbstractLoader(Emulator<T> emulator, UnixSyscallHandler<T> syscallHandler) {
        this.backend = emulator.getBackend();
        this.emulator = emulator;
        this.syscallHandler = syscallHandler;
        this.fileMappings = new FileMappingManager(backend, memoryMap, emulator.getPageAlign());

        setMMapBaseAddress(MMAP_BASE);
    }

    @Override
    public void close() {
        fileMappings.close();
    }

    @Override
    public int msync(long address, int length, int flags) {
        throw new UnsupportedOperationException("Memory synchronization requires a platform implementation");
    }

    @Override
    public int discardFileMapping(long address, int length) {
        throw new UnsupportedOperationException("File mapping advice requires a platform implementation");
    }

    protected int memoryError(int errno) {
        setErrno(errno);
        return -1;
    }

    @Override
    public Collection<MemoryMap> getMemoryMap() {
        return memoryMap.values();
    }

    @Override
    public final UnidbgPointer mmap(int length, int prot) {
        int aligned = (int) ARM.alignSize(length, emulator.getPageAlign());
        long addr = mmap2(0, aligned, prot, 0, -1, 0);
        UnidbgPointer pointer = UnidbgPointer.pointer(emulator, addr);
        assert pointer != null;
        return pointer.setSize(aligned);
    }

//    private static final int MAP_SHARED =	0x01;		/* Share changes */
//    private static final int MAP_PRIVATE =	0x02;		/* Changes are private */
//    private static final int MAP_TYPE =	0x0f;		/* Mask for type of mapping */
//    private static final int MAP_FIXED =	0x10;		/* Interpret addr exactly */
//    private static final int MAP_ANONYMOUS =	0x20;		/* don't use a file */

    protected final long allocateMapAddress(long mask, long length) {
        Map.Entry<Long, MemoryMap> lastEntry = null;
        for (Map.Entry<Long, MemoryMap> entry : memoryMap.entrySet()) {
            if (lastEntry == null) {
                lastEntry = entry;
            } else {
                MemoryMap map = lastEntry.getValue();
                long mmapAddress = map.base + map.size;
                if (mmapAddress + length < entry.getKey() && (mmapAddress & mask) == 0) {
                    return mmapAddress;
                } else {
                    lastEntry = entry;
                }
            }
        }
        if (lastEntry != null) {
            MemoryMap map = lastEntry.getValue();
            long mmapAddress = map.base + map.size;
            if (mmapAddress < mmapBaseAddress) {
                log.debug("allocateMapAddress mmapBaseAddress=0x{}, mmapAddress=0x{}", Long.toHexString(mmapBaseAddress), Long.toHexString(mmapAddress));
                setMMapBaseAddress(mmapAddress);
            }
        }

        long addr = mmapBaseAddress;
        while ((addr & mask) != 0) {
            addr += emulator.getPageAlign();
        }
        setMMapBaseAddress(addr + length);
        return addr;
    }

    @Override
    public final int munmap(long start, int length) {
        try {
            return unmapInternal(start, length);
        } finally {
            fileMappings.releaseUnused();
        }
    }

    @Override
    public int nameAnonymousRange(long address, long length, String name) {
        long start = address;
        long end = address + length;
        List<MemoryMap> affected = new ArrayList<>();
        MemoryMap first = null;
        long covered = start;
        for (MemoryMap map : memoryMap.values()) {
            if (covered >= end) break;
            long mapEnd = map.base + map.size;
            if (mapEnd <= covered) continue;
            if (map.base > covered) return refuseRangeName(start, length, "unmapped", UnixEmulator.ENOMEM);
            if (map.backing != null) return refuseRangeName(start, length, "backed", UnixEmulator.EBADF);
            if (first != null && (map.prot != first.prot || map.flags != first.flags)) {
                return refuseRangeName(start, length, "kind", UnixEmulator.EINVAL);
            }
            first = map;
            affected.add(map);
            covered = Math.min(mapEnd, end);
        }
        if (covered < end) return refuseRangeName(start, length, "unmapped", UnixEmulator.ENOMEM);
        for (MemoryMap map : affected) {
            long mapEnd = map.base + map.size;
            memoryMap.remove(map.base);
            if (map.base < start) {
                memoryMap.put(map.base, map.slice(map.base, start - map.base, map.prot));
            }
            if (mapEnd > end) {
                memoryMap.put(end, map.slice(end, mapEnd - end, map.prot));
            }
        }
        MemoryMap named = first.slice(start, end - start, first.prot);
        named.name = name;
        memoryMap.put(start, named);
        log.debug("event=memory_range_named address=0x{} length={} name={}", Long.toHexString(start),
                length, name);
        return 0;
    }

    private int refuseRangeName(long start, long length, String reason, int errno) {
        log.debug("event=memory_range_name_refused address=0x{} length={} reason={} errno={}",
                Long.toHexString(start), length, reason, errno);
        return memoryError(errno);
    }

    private int unmapInternal(long start, int length) {
        int aligned = (int) ARM.alignSize(length, emulator.getPageAlign());
        backend.mem_unmap(start, aligned);
        if (mMapListener != null) {
            mMapListener.onUnmap(start, aligned);
        }
        long end = start + aligned;
        int prot = 0;
        List<MemoryMap> affected = new ArrayList<>();
        for (MemoryMap map : memoryMap.values()) {
            if (start < map.base + map.size && end > map.base) {
                affected.add(map);
            }
        }
        if (!affected.isEmpty()) {
            prot = affected.get(0).prot;
        }
        for (MemoryMap map : affected) {
            long mapEnd = map.base + map.size;
            memoryMap.remove(map.base);
            if (map.base < start) {
                memoryMap.put(map.base, map.slice(map.base, start - map.base, map.prot));
            }
            if (mapEnd > end) {
                memoryMap.put(end, map.slice(end, mapEnd - end, map.prot));
            }
        }
        log.debug("event=memory_unmapped address=0x{} length={}", Long.toHexString(start), aligned);
        if (memoryMap.isEmpty()) {
            setMMapBaseAddress(MMAP_BASE);
        }
        return prot;
    }

    @Override
    public final int mprotect(long address, int length, int prot) {
        if (address % ARMEmulator.PAGE_ALIGN != 0) {
            return memoryError(UnixEmulator.EINVAL);
        }

        int aligned = (int) ARM.alignSize(length, emulator.getPageAlign());
        if (mMapListener != null) {
            prot = mMapListener.onProtect(address, aligned, prot);
        }
        long protEnd = address + aligned;
        List<MemoryMap> affected = new ArrayList<>();
        for (MemoryMap m : memoryMap.values()) {
            if (address < m.base + m.size && protEnd > m.base) {
                affected.add(m);
            }
        }
        if ((prot & UnicornConst.UC_PROT_WRITE) != 0) {
            for (MemoryMap map : affected) {
                if (!map.mayWrite) {
                    log.debug("event=memory_protect_denied address=0x{} length={} prot={} mapping=0x{}",
                            Long.toHexString(address), aligned, prot, Long.toHexString(map.base));
                    return memoryError(UnixEmulator.EACCES);
                }
            }
        }
        fileMappings.checkProtection(address, aligned, prot);
        backend.mem_protect(address, aligned, prot);
        for (MemoryMap map : affected) {
            long mapEnd = map.base + map.size;
            if (address <= map.base && protEnd >= mapEnd) {
                map.prot = prot;
            } else if (address <= map.base) {
                memoryMap.remove(map.base);
                memoryMap.put(map.base, map.slice(map.base, protEnd - map.base, prot));
                memoryMap.put(protEnd, map.slice(protEnd, mapEnd - protEnd, map.prot));
            } else if (protEnd >= mapEnd) {
                int oldProt = map.prot;
                memoryMap.remove(map.base);
                memoryMap.put(map.base, map.slice(map.base, address - map.base, oldProt));
                memoryMap.put(address, map.slice(address, mapEnd - address, prot));
            } else {
                int oldProt = map.prot;
                memoryMap.remove(map.base);
                memoryMap.put(map.base, map.slice(map.base, address - map.base, oldProt));
                memoryMap.put(address, map.slice(address, aligned, prot));
                memoryMap.put(protEnd, map.slice(protEnd, mapEnd - protEnd, oldProt));
            }
        }
        return 0;
    }

    @Override
    public final Module load(File elfFile) {
        return load(elfFile,false);
    }

    @Override
    public final Module load(LibraryFile libraryFile) {
        return load(libraryFile, false);
    }

    @Override
    public final Module load(File elfFile, boolean forceCallInit) {
        return loadInternal(createLibraryFile(elfFile), forceCallInit);
    }

    protected abstract LibraryFile createLibraryFile(File file);

    @Override
    public final Module load(LibraryFile libraryFile, boolean forceCallInit) {
        return loadInternal(libraryFile, forceCallInit);
    }

    protected abstract Module loadInternal(LibraryFile libraryFile, boolean forceCallInit);

    protected boolean callInitFunction = true;

    @Override
    public final void disableCallInitFunction() {
        this.callInitFunction = false;
    }

    @Override
    public void setCallInitFunction(boolean callInit) {
        this.callInitFunction = callInit;
    }

    protected final List<HookListener> hookListeners = new ArrayList<>();

    @Override
    public final void addHookListener(HookListener listener) {
        hookListeners.add(listener);
    }

    protected LibraryResolver libraryResolver;

    @Override
    public void setLibraryResolver(LibraryResolver libraryResolver) {
        libraryResolver.onSetToLoader(emulator);

        this.libraryResolver = libraryResolver;
    }

    @Override
    public final UnidbgPointer allocateStack(int size) {
        long newAddr = sp - size;
        long threadStackBase = Memory.STACK_BASE - (long) Memory.STACK_SIZE_OF_MAIN_PAGE * emulator.getPageAlign();
        if(newAddr <= threadStackBase){
            throw new IllegalStateException("Error! main thread stack point too large. sp=0x" + Long.toHexString(sp) + ", threadStackBase=0x" + Long.toHexString(threadStackBase));
        }
        setStackPoint(newAddr);
        UnidbgPointer pointer = UnidbgPointer.pointer(emulator, sp);
        assert pointer != null;
        return pointer.setSize(size);
    }

    @Override
    public final UnidbgPointer writeStackString(String str) {
        byte[] data = str.getBytes(StandardCharsets.UTF_8);
        return writeStackBytes(Arrays.copyOf(data, data.length + 1));
    }

    @Override
    public final UnidbgPointer writeStackBytes(byte[] data) {
        int size = ARM.alignSize(data.length);
        UnidbgPointer pointer = allocateStack(size);
        assert pointer != null;
        pointer.write(0, data, 0, data.length);
        return pointer;
    }

    @Override
    public final UnidbgPointer pointer(long address) {
        return UnidbgPointer.pointer(emulator, address);
    }

    private long stackBase;
    protected int stackSize;

    @Override
    public long getStackBase() {
        return stackBase;
    }

    @Override
    public int getStackSize() {
        return stackSize;
    }

    @Override
    public final void setStackPoint(long sp) {
        if (this.sp == 0) {
            this.stackBase = sp;
        }
        this.sp = sp;
        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, sp);
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, sp);
        }
    }

    @Override
    public long getStackPoint() {
        return sp;
    }

    protected final List<ModuleListener> moduleListeners = new ArrayList<>();

    @Override
    public final void addModuleListener(ModuleListener listener) {
        moduleListeners.add(listener);
    }

    protected final void notifyModuleLoaded(Module module) {
        for (ModuleListener listener : moduleListeners) {
            listener.onLoaded(emulator, module);
        }
    }

    protected final void dump(Pointer pointer, long size, File outFile) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(outFile.toPath())) {
            int dump = 0;
            while (dump < size) {
                long read = size - dump;
                if (read > ARMEmulator.PAGE_ALIGN) {
                    read = ARMEmulator.PAGE_ALIGN;
                }
                byte[] data = pointer.getByteArray(dump, (int) read);
                outputStream.write(data);
                dump += (int) read;
            }
        }
    }

    protected final Alignment mem_map(long address, long size, int prot, String libraryName, long align) {
        Alignment alignment = ARM.align(address, size, align);

        if (log.isDebugEnabled()) {
            log.debug("[{}]0x{} - 0x{}, size=0x{}, prot={}", libraryName, Long.toHexString(alignment.address), Long.toHexString(alignment.address + alignment.size), Long.toHexString(alignment.size), prot);
        }

        backend.mem_map(alignment.address, alignment.size, prot);
        if (mMapListener != null) {
            mMapListener.onMap(alignment.address, alignment.size, prot);
        }
        if (memoryMap.put(alignment.address, new MemoryMap(alignment.address, alignment.size, prot)) != null) {
            log.warn("mem_map replace exists memory map address={}", Long.toHexString(alignment.address));
        }
        return alignment;
    }

    @Override
    public final Module findModuleByAddress(long address) {
        for (Module module : getLoadedModules()) {
            long base = getModuleBase(module);
            if (address >= base && address < base + module.size) {
                return module;
            }
        }
        return null;
    }

    protected long getModuleBase(Module module) {
        return module.base;
    }

    @Override
    public final Module findModule(String name) {
        for (Module module : getLoadedModules()) {
            if (module.name.equals(name)) {
                return module;
            }
        }
        return null;
    }

    @Override
    public Module loadVirtualModule(String name, Map<String, UnidbgPointer> symbols) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void serialize(DataOutput out) throws IOException {
        out.writeLong(sp);
        out.writeLong(mmapBaseAddress);
        out.writeLong(stackBase);
        out.writeLong(stackSize);
        out.writeInt(memoryMap.size());
        for (Map.Entry<Long, MemoryMap> entry : memoryMap.entrySet()) {
            MemoryMap map = entry.getValue();
            out.writeLong(entry.getKey());
            map.serialize(out);
            UnidbgPointer pointer = UnidbgPointer.pointer(emulator, map.base);
            assert pointer != null;
            byte[] data = pointer.getByteArray(0, (int) map.size);
            out.write(data);
        }
    }

}
