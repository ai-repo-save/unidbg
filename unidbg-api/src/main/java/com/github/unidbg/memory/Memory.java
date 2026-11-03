package com.github.unidbg.memory;

import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.spi.Loader;
import com.github.unidbg.thread.BaseTask;
import com.github.unidbg.unix.IO;

import java.util.Collection;

@SuppressWarnings("unused")
public interface Memory extends IO, Loader, StackMemory {

    long STACK_BASE = 0xe5000000L;

    int MAX_THREADS = 16;
    int STACK_SIZE_OF_THREAD_PAGE = MAX_THREADS * BaseTask.THREAD_STACK_PAGE; // for thread stack
    int STACK_SIZE_OF_MAIN_PAGE = 256; // for main stack
    int STACK_SIZE_OF_PAGE = STACK_SIZE_OF_THREAD_PAGE + STACK_SIZE_OF_MAIN_PAGE;

    long MMAP_BASE = 0x12000000L;//0x1fffe180e , limited by MMIO_TRAP_ADDRESS

    int allocateThreadIndex();
    void freeThreadIndex(int index);
    UnidbgPointer allocateThreadStack(int index);
    UnidbgPointer allocateStack(int size);
    UnidbgPointer pointer(long address);
    void setStackPoint(long sp);
    long getStackPoint();
    long getStackBase();
    int getStackSize();

    long mmap2(long start, int length, int prot, int flags, int fd, long offset);
    int msync(long address, int length, int flags);
    int discardFileMapping(long address, int length);
    void close();
    int mprotect(long address, int length, int prot);
    int brk(long address);

    /**
     * 分配内存
     * @param length 大小
     * @param runtime <code>true</code>表示使用mmap按页大小分配，相应的调用MemoryBlock.free方法则使用munmap释放，<code>false</code>表示使用libc.malloc分配，相应的调用MemoryBlock.free方法则使用libc.free释放
     */
    MemoryBlock malloc(int length, boolean runtime);
    UnidbgPointer mmap(int length, int prot);
    int munmap(long start, int length);

    /**
     * set errno
     */
    void setErrno(int errno);

    int getLastErrno();

    Collection<MemoryMap> getMemoryMap();

    /**
     * Names an anonymous range of the address space, which {@code /proc/self/maps} reports after
     * the range. A named range answers with its own line and its own extent, cut out of the
     * mapping it lies in. The kernel walks the mappings the range covers and names each of them,
     * so a range that spans mappings of different protection or flags names every part. A range
     * the mappings do not cover, or a range of file-backed memory, is refused.
     *
     * @return the errno of the refusal, or 0 when the range is named
     */
    int nameAnonymousRange(long address, long length, String name);

    void setMMapListener(MMapListener listener);

    MMapListener getMMapListener();

}
