package com.github.unidbg.linux.file;

import com.github.unidbg.file.FileIO;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.memory.mapping.HostMappingException;
import com.github.unidbg.memory.mapping.LinuxMappingSource;
import com.github.unidbg.memory.mapping.MappingSource;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/** Holds the ordinary I/O and mapping resources acquired by one file open. */
public final class FileBacking implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(FileBacking.class);
    private static final AtomicLong IDS = new AtomicLong();
    private final long id = IDS.incrementAndGet();
    private final RandomAccessFile file;
    private final MappingSource source;
    private final int appendFd;
    private int references = 1;

    public interface AppendCalls extends Library {
        int open(String path, int flags);
        NativeLong write(int fd, byte[] data, NativeLong count);
        NativeLong lseek(int fd, NativeLong offset, int whence);
        int close(int fd);
    }

    private static final AppendCalls APPEND = loadAppendCalls();

    private static AppendCalls loadAppendCalls() {
        if (!Platform.isLinux() || !Platform.is64Bit() || Native.LONG_SIZE != 8) {
            throw new UnsupportedOperationException("File backing requires a 64-bit Linux host");
        }
        return Native.load(Platform.C_LIBRARY_NAME, AppendCalls.class);
    }

    static FileBacking open(File path) {
        MappingSource source = null;
        RandomAccessFile file = null;
        int appendFd = -1;
        try {
            source = LinuxMappingSource.open(path.toPath(), true);
            file = new RandomAccessFile(path, "rws");
            // Linux open(2): each write on O_APPEND atomically selects EOF and writes.
            appendFd = APPEND.open(path.toString(), 1 | 0x400 | 0x80000);
            if (appendFd < 0) throw new LinuxErrnoException(Native.getLastError());
            return new FileBacking(file, source, appendFd);
        } catch (IOException | RuntimeException | Error failure) {
            if (appendFd >= 0 && APPEND.close(appendFd) < 0) {
                failure.addSuppressed(new LinuxErrnoException(Native.getLastError()));
            }
            try { if (file != null) file.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { if (source != null) source.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof HostMappingException) {
                throw new LinuxErrnoException(((HostMappingException) failure).getErrno(), failure);
            }
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException("Opening file backing: " + path, failure);
        }
    }

    private FileBacking(RandomAccessFile file, MappingSource source, int appendFd) {
        this.file = file;
        this.source = source;
        this.appendFd = appendFd;
        log.debug("event=file_backing_opened backing={}", id);
    }

    public long backingId() { return id; }

    synchronized FileBacking retain() {
        if (references == 0) throw new IllegalStateException("File backing is closed");
        ++references;
        log.debug("event=file_backing_retained backing={} references={}", id, references);
        return this;
    }

    RandomAccessFile openedFile() { return file; }
    public MappingSource mappingSource() { return source; }
    public long size() throws IOException { return file.length(); }
    public int readAt(long offset, ByteBuffer destination) throws IOException { return file.getChannel().read(destination, offset); }
    public synchronized int writeAt(long offset, ByteBuffer data) throws IOException { return file.getChannel().write(data, offset); }
    synchronized long append(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.duplicate().get(bytes);
        long written = APPEND.write(appendFd, bytes, new NativeLong(bytes.length)).longValue();
        if (written < 0) throw new LinuxErrnoException(Native.getLastError());
        data.position(data.position() + Math.toIntExact(written));
        long offset = APPEND.lseek(appendFd, new NativeLong(0), FileIO.SEEK_CUR).longValue();
        if (offset < 0) throw new LinuxErrnoException(Native.getLastError());
        return offset;
    }

    public synchronized void truncate(long size) throws IOException { source.truncate(size); }

    @Override
    public synchronized void close() throws IOException {
        if (references == 0) return;
        --references;
        log.debug("event=file_backing_released backing={} references={}", id, references);
        if (references != 0) return;
        IOException failure = null;
        if (APPEND.close(appendFd) < 0) {
            failure = new IOException("Closing append fd for backing " + id,
                    new LinuxErrnoException(Native.getLastError()));
        }
        try { file.close(); } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        try { source.close(); } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
        log.debug("event=file_backing_closed backing={}", id);
    }
}
