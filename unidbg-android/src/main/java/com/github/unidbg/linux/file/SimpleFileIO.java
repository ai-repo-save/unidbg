package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.NewFileIO;
import com.github.unidbg.file.linux.BaseAndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.memory.mapping.HostMappingException;
import com.github.unidbg.memory.mapping.MappingSource;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.utils.Inspector;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class SimpleFileIO extends BaseAndroidFileIO implements NewFileIO {
    private static final Logger log = LoggerFactory.getLogger(SimpleFileIO.class);
    protected final File file;
    protected final String path;
    private final OpenFileDescription description;
    private boolean closed;
    OutputStream debugStream;

    public SimpleFileIO(int oflags, File file, String path) {
        super(oflags);
        this.file = file;
        this.path = path;
        if (file.isDirectory()) throw new IllegalArgumentException("file is directory: " + file);
        if (!file.exists()) throw new IllegalArgumentException("file not exists: " + file);
        if ((oflags & 3) == 3) throw new LinuxErrnoException(UnixEmulator.EINVAL);
        description = new OpenFileDescription(FileBacking.open(file), oflags);
        op = (oflags & IOConstants.O_CLOEXEC) != 0 ? 1 : 0;
        try {
            onFileOpened(description.backing.openedFile());
        } catch (IOException | RuntimeException | Error failure) {
            try { description.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof HostMappingException) {
                throw new LinuxErrnoException(((HostMappingException) failure).getErrno(), failure);
            }
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException("Initializing file: " + path, failure);
        }
    }

    protected SimpleFileIO(SimpleFileIO original) {
        super(original.oflags);
        if (original.closed) throw new LinuxErrnoException(UnixEmulator.EBADF);
        file = original.file;
        path = original.path;
        description = original.description.retain();
        debugStream = original.debugStream;
        stdio = original.stdio;
    }

    private SimpleFileIO(SimpleFileIO original, int flags) {
        super(flags);
        if (original.closed) throw new LinuxErrnoException(UnixEmulator.EBADF);
        file = original.file;
        path = original.path;
        try { description = original.description.reopen(flags); }
        catch (HostMappingException failure) { throw new LinuxErrnoException(failure.getErrno(), failure); }
        catch (IOException failure) { throw new IllegalStateException("Reopening file: " + path, failure); }
        op = (flags & IOConstants.O_CLOEXEC) != 0 ? 1 : 0;
    }

    void onFileOpened(RandomAccessFile opened) throws IOException {
    }

    protected final void startOutput() throws IOException { description.startOutput(); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (debugStream != null) debugStream.flush();
        } catch (IOException failure) {
            log.warn("event=file_debug_flush_failed path={}", path, failure);
        }
        try { description.close(); }
        catch (IOException failure) { throw new IllegalStateException("Closing file: " + path, failure); }
    }

    @Override
    public boolean isReadable() { return !closed && description.readable(); }
    @Override
    public boolean isWritable() { return !closed && description.writable(); }

    @Override
    public int fcntl(Emulator<?> emulator, int command, long argument) {
        if (closed) return -UnixEmulator.EBADF;
        if (command == 3) return description.flags();
        return super.fcntl(emulator, command, argument);
    }

    @Override
    protected void setFlags(long argument) { description.setFlags(argument); }

    @Override
    public int write(byte[] data) {
        if (closed || !isWritable()) return -UnixEmulator.EBADF;
        try {
            int written = description.write(ByteBuffer.wrap(data));
            if (written > 0 && debugStream != null) {
                debugStream.write(data, 0, written);
                debugStream.flush();
            }
            if (log.isDebugEnabled() && written > 0 && written < 0x3000)
                Inspector.inspect(Arrays.copyOf(data, written), "write backing=" + description.backing.backingId());
            return written;
        } catch (IOException failure) { throw new IllegalStateException("Writing file: " + path, failure); }
    }

    void setDebugStream(OutputStream stream) { debugStream = new BufferedOutputStream(stream); }

    @Override
    public int read(Backend backend, Pointer pointer, int count) {
        if (closed || !isReadable()) return -UnixEmulator.EBADF;
        if (count < 0) return -UnixEmulator.EINVAL;
        ByteBuffer bytes = ByteBuffer.allocate(count);
        try {
            int read = description.read(bytes);
            if (read > 0) pointer.write(0, bytes.array(), 0, read);
            return read;
        } catch (IOException failure) { throw new IllegalStateException("Reading file: " + path, failure); }
    }

    @Override
    public int pread(Backend backend, Pointer pointer, int count, long offset) {
        if (closed || !isReadable()) return -UnixEmulator.EBADF;
        if (count < 0 || offset < 0) return -UnixEmulator.EINVAL;
        ByteBuffer bytes = ByteBuffer.allocate(Math.min(count, 64 * 1024));
        int total = 0;
        try {
            while (total < count) {
                int requested = Math.min(bytes.capacity(), count - total);
                bytes.clear().limit(requested);
                int read = description.backing.readAt(offset + total, bytes);
                if (read <= 0) break;
                pointer.write(total, bytes.array(), 0, read);
                total += read;
                if (read < requested) break;
            }
            return total;
        } catch (IOException failure) { throw new IllegalStateException("Reading file at offset: " + path, failure); }
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata() {
        return closed ? FileQueryResult.failed(UnixEmulator.EBADF) : description.backing.queryMetadata();
    }

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        if (closed) return -UnixEmulator.EBADF;
        stat.st_dev = 1;
        stat.st_mode = stdio ? IO.S_IFCHR | 0x777 : IO.S_IFREG;
        stat.st_uid = 0;
        stat.st_gid = 0;
        try { stat.st_size = description.backing.size(); }
        catch (IOException failure) { throw new IllegalStateException("Reading file size: " + path, failure); }
        stat.st_blksize = emulator.getPageAlign();
        stat.st_ino = 1;
        stat.st_blocks = (stat.st_size + emulator.getPageAlign() - 1) / emulator.getPageAlign();
        stat.setLastModification(file.lastModified());
        stat.pack();
        return 0;
    }

    @Override
    protected byte[] getMmapData(long address, long offset, int length) throws IOException {
        if (closed) throw new LinuxErrnoException(UnixEmulator.EBADF);
        if (offset < 0 || length < 0) throw new IllegalArgumentException("Invalid snapshot range");
        long remaining = Math.max(0, description.backing.size() - offset);
        ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(Math.min(remaining, length)));
        long position = offset;
        while (bytes.hasRemaining()) {
            int read = description.backing.readAt(position, bytes);
            if (read <= 0) break;
            position += read;
        }
        return Arrays.copyOf(bytes.array(), bytes.position());
    }

    @Override
    public MappingSource getMappingSource() {
        if (closed) throw new LinuxErrnoException(UnixEmulator.EBADF);
        return description.backing.mappingSource();
    }

    @Override
    public short poll(short events) {
        if (closed) return 0x20; // POLLNVAL
        return (short)(events & 0x145); // Linux default regular-file mask: IN/OUT/RDNORM/WRNORM.
    }

    @Override
    public String toString() { return path; }

    @Override
    public int ioctl(Emulator<?> emulator, long request, long argument) {
        if (closed) return -UnixEmulator.EBADF;
        if (stdio) return 0;
        return super.ioctl(emulator, request, argument);
    }

    @Override
    public FileIO dup2() { return new SimpleFileIO(this); }

    @Override
    public FileIO reopen(int flags) { return new SimpleFileIO(this, flags); }

    @Override
    public int lseek(int offset, int whence) {
        if (closed) return -UnixEmulator.EBADF;
        try { return (int)description.seek(offset, whence, Integer.MAX_VALUE); }
        catch (LinuxErrnoException failure) { return -failure.getErrno(); }
        catch (IOException failure) { throw new IllegalStateException("Seeking file: " + path, failure); }
    }

    @Override
    public int llseek(long offset, Pointer result, int whence) {
        if (closed) return -UnixEmulator.EBADF;
        try {
            result.setLong(0, description.seek(offset, whence, Long.MAX_VALUE));
            return 0;
        } catch (LinuxErrnoException failure) { return -failure.getErrno(); }
        catch (IOException failure) { throw new IllegalStateException("Seeking file: " + path, failure); }
    }

    @Override
    public int ftruncate(int length) {
        if (closed) return -UnixEmulator.EBADF;
        if (length < 0 || !isWritable()) return -UnixEmulator.EINVAL;
        try { description.backing.truncate(length); return 0; }
        catch (HostMappingException failure) { return -failure.getErrno(); }
        catch (IOException failure) { throw new IllegalStateException("Truncating file: " + path, failure); }
    }

    @Override
    public String getPath() { return path; }
}
