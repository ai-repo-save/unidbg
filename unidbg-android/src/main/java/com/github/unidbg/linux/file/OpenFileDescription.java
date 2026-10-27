package com.github.unidbg.linux.file;

import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.unix.UnixEmulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

final class OpenFileDescription implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(OpenFileDescription.class);
    private static final AtomicLong IDS = new AtomicLong();
    private static final int MUTABLE_FLAGS = IOConstants.O_APPEND | IOConstants.O_NONBLOCK;
    private static final int CREATION_FLAGS = IOConstants.O_CREAT | IOConstants.O_EXCL | IOConstants.O_CLOEXEC | 0x200;
    private final long id = IDS.incrementAndGet();
    final FileBacking backing;
    private final int access;
    private int status;
    private long position;
    private int references = 1;
    private boolean outputStarted;

    OpenFileDescription(FileBacking backing, int flags) {
        this.backing = backing;
        this.access = flags & 3;
        this.status = flags & ~CREATION_FLAGS;
        log.debug("event=file_description_opened description={} backing={} flags={}", id, backing.backingId(), status);
    }

    synchronized OpenFileDescription retain() {
        if (references == 0) throw new LinuxErrnoException(UnixEmulator.EBADF);
        ++references;
        log.debug("event=file_description_retained description={} references={}", id, references);
        return this;
    }

    synchronized OpenFileDescription reopen(int flags) throws IOException {
        if (references == 0) throw new LinuxErrnoException(UnixEmulator.EBADF);
        if ((flags & 3) == 3) throw new LinuxErrnoException(UnixEmulator.EINVAL);
        if ((flags & (IOConstants.O_CREAT | IOConstants.O_EXCL)) == (IOConstants.O_CREAT | IOConstants.O_EXCL)) {
            throw new LinuxErrnoException(UnixEmulator.EEXIST);
        }
        if ((flags & 0x200) != 0) backing.truncate(0);
        return new OpenFileDescription(backing.retain(), flags);
    }

    synchronized boolean readable() { return access == IOConstants.O_RDONLY || access == IOConstants.O_RDWR; }
    synchronized boolean writable() { return access == IOConstants.O_WRONLY || access == IOConstants.O_RDWR; }
    synchronized int flags() { return status; }

    synchronized void setFlags(long argument) {
        long supported = Integer.toUnsignedLong(status | MUTABLE_FLAGS | CREATION_FLAGS | 3);
        if ((argument & ~supported) != 0) {
            throw new UnsupportedOperationException("Unsupported file status flags: " + Long.toHexString(argument));
        }
        int previous = status;
        status = (status & ~MUTABLE_FLAGS) | ((int)argument & MUTABLE_FLAGS);
        log.debug("event=file_status_changed description={} previous={} flags={}", id, previous, status);
    }

    synchronized int read(ByteBuffer destination) throws IOException {
        if (!readable()) return -UnixEmulator.EBADF;
        if (!destination.hasRemaining()) return 0;
        int read = backing.readAt(position, destination);
        if (read < 0) read = 0;
        position += read;
        return read;
    }

    synchronized int write(ByteBuffer source) throws IOException {
        if (!writable()) return -UnixEmulator.EBADF;
        if (!source.hasRemaining()) return 0;
        if ((status & IOConstants.O_APPEND) != 0) {
            int before = source.position();
            position = backing.append(source);
            return source.position() - before;
        }
        int written = backing.writeAt(position, source);
        position += written;
        return written;
    }

    synchronized long seek(long offset, int whence, long maximum) throws IOException {
        long base;
        switch (whence) {
            case FileIO.SEEK_SET: base = 0; break;
            case FileIO.SEEK_CUR: base = position; break;
            case FileIO.SEEK_END: base = backing.size(); break;
            default: throw new LinuxErrnoException(UnixEmulator.EINVAL);
        }
        long target;
        try { target = Math.addExact(base, offset); }
        catch (ArithmeticException failure) { throw new LinuxErrnoException(UnixEmulator.EINVAL, failure); }
        if (target < 0) throw new LinuxErrnoException(UnixEmulator.EINVAL);
        if (target > maximum) throw new UnsupportedOperationException("Seek exceeds this entry point's offset capacity");
        position = target;
        return target;
    }

    synchronized void startOutput() throws IOException {
        if (!outputStarted) {
            backing.truncate(0);
            outputStarted = true;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (references == 0) return;
        --references;
        log.debug("event=file_description_released description={} references={}", id, references);
        if (references == 0) backing.close();
    }
}
