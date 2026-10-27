package com.github.unidbg.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.memory.mapping.MappingSource;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.util.function.IntUnaryOperator;

public interface FileIO {

    int SEEK_SET = 0;
    int SEEK_CUR = 1;
    int SEEK_END = 2;

    void close();

    /** Completion runs once with the raw close result on the backend owner and must not yield. */
    default int close(Emulator<?> emulator, IntUnaryOperator completion) {
        close();
        return completion.applyAsInt(0);
    }

    int write(byte[] data);

    int read(Backend backend, Pointer buffer, int count);

    int pread(Backend backend, Pointer buffer, int count, long offset);

    int fcntl(Emulator<?> emulator, int cmd, long arg);

    int ioctl(Emulator<?> emulator, long request, long argp);

    FileIO dup2();

    /** Opens this descriptor's proc-fd symbolic link as a new description of the held resource. */
    FileIO reopen(int flags);

    int connect(Pointer addr, int addrlen);

    int bind(Pointer addr, int addrlen);

    int listen(int backlog);

    int setsockopt(int level, int optname, Pointer optval, int optlen);

    int sendto(byte[] data, int flags, Pointer dest_addr, int addrlen);

    int lseek(int offset, int whence);

    int ftruncate(int length);

    int getpeername(Pointer addr, Pointer addrlen);

    int shutdown(int how);

    int getsockopt(int level, int optname, Pointer optval, Pointer optlen);

    int getsockname(Pointer addr, Pointer addrlen);

    long mmap2(Emulator<?> emulator, long addr, int aligned, int prot, long offset, int length) throws IOException;

    /** Returns readiness revents without blocking the emulator owner thread. */
    short poll(short events);

    boolean isReadable();

    boolean isWritable();

    MappingSource getMappingSource();

    int llseek(long offset, Pointer result, int whence);

    int recvfrom(Backend backend, Pointer buf, int len, int flags, Pointer src_addr, Pointer addrlen);

    String getPath();

    boolean isStdIO();
}
