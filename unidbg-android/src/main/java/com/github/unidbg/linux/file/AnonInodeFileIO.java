package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.linux.BaseAndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.file.linux.StatStructure;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A descriptor the kernel opens on an anonymous inode, which holds no file: {@code epoll_create1(2)}
 * and {@code timerfd_create(2)} hand one out.
 *
 * The inode carries no bytes, so a stat reports an empty read-write inode without a file type, and
 * the descriptor holds the name the kernel shows for it. The runtime models no operation of the
 * object behind the descriptor yet, so a read or a write of it is not modeled.
 */
public class AnonInodeFileIO extends BaseAndroidFileIO {

    private static final AtomicLong INODES = new AtomicLong();

    private final String name;
    private final long inode = INODES.incrementAndGet();

    /**
     * @param name  the inode name the kernel reports, such as {@code anon_inode:[eventpoll]}
     * @param flags the open flags the creator accepted, which this descriptor keeps
     */
    public AnonInodeFileIO(String name, int flags) {
        super(IOConstants.O_RDWR);
        this.name = name;
        this.op = (flags & IOConstants.O_CLOEXEC) != 0 ? 1 : 0;
    }

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        stat.st_ino = inode;
        // alloc_anon_inode(): a read-write inode that carries no file type.
        stat.st_mode = 0600;
        stat.st_nlink = 1;
        stat.st_blksize = emulator.getPageAlign();
        stat.pack();
        return 0;
    }

    @Override
    public void close() {
    }

    @Override
    public String getPath() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
