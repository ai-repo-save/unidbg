package com.github.unidbg.memory.mapping;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.linux.LibC;
import com.sun.jna.platform.unix.LibCAPI;
import com.sun.jna.platform.unix.LibCUtil;

final class LinuxMappingCalls {

    static final long PAGE_SIZE = 4096;
    static final int PROT_READ_WRITE = 3;
    static final int MAP_SHARED = 1;
    static final int MAP_PRIVATE = 2;
    static final int MADV_DONTNEED = 4;
    static final int O_CLOEXEC = 0x80000;
    static final int O_RDWR = 2;
    static final int S_IFMT = 0170000;
    static final int S_IFREG = 0100000;

    interface FileCalls extends Library {
        int open(String path, int flags);
        int fstat(int fd, FileStat stat);
        int stat(String path, FileStat stat);
        int lstat(String path, FileStat stat);
        int getpagesize();
        int madvise(Pointer address, long length, int advice);
    }

    // Linux x86_64 struct stat, including its ABI padding and reserved tail.
    @Structure.FieldOrder({"device", "inode", "links", "mode", "uid", "gid", "padding",
            "rdev", "size", "blockSize", "blocks", "times", "reserved"})
    public static final class FileStat extends Structure {
        public long device, inode, links;
        public int mode, uid, gid, padding;
        public long rdev, size, blockSize, blocks;
        public long[] times = new long[6];
        public long[] reserved = new long[3];
    }

    static final FileCalls FILES = load();
    static final LibCAPI LIBC = LibC.INSTANCE;

    private static FileCalls load() {
        if (!Platform.isLinux() || !Platform.isIntel() || !Platform.is64Bit() || Native.LONG_SIZE != 8) {
            throw new UnsupportedOperationException("File mapping requires a Linux x86_64 host");
        }
        FileCalls calls = Native.load(Platform.C_LIBRARY_NAME, FileCalls.class);
        if (calls.getpagesize() != PAGE_SIZE) {
            throw new UnsupportedOperationException("File mapping requires 4096-byte host pages");
        }
        return calls;
    }

    static Pointer map(int fd, long offset, long length, boolean shared) throws HostMappingException {
        try {
            Pointer pointer = LibCUtil.mmap(null, length, PROT_READ_WRITE,
                    shared ? MAP_SHARED : MAP_PRIVATE, fd, offset);
            if (Pointer.nativeValue(pointer) == -1L) {
                throw failure("mmap", "fd=" + fd + ", offset=" + offset + ", length=" + length);
            }
            return pointer;
        } catch (com.sun.jna.LastErrorException error) {
            throw new HostMappingException("mmap", error.getErrorCode(),
                    "fd=" + fd + ", offset=" + offset + ", length=" + length);
        }
    }

    static void truncate(int fd, long length) throws HostMappingException {
        try {
            if (LibCUtil.ftruncate(fd, length) < 0) {
                throw failure("ftruncate", "fd=" + fd + ", length=" + length);
            }
        } catch (com.sun.jna.LastErrorException error) {
            throw new HostMappingException("ftruncate", error.getErrorCode(), "fd=" + fd + ", length=" + length);
        }
    }

    static HostMappingException failure(String operation, String context) {
        return new HostMappingException(operation, Native.getLastError(), context);
    }

    private LinuxMappingCalls() {
    }
}
