package com.github.unidbg.memory.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public final class LinuxMappingSource implements MappingSource {

    private static final Logger log = LoggerFactory.getLogger(LinuxMappingSource.class);
    private static final AtomicLong SOURCES = new AtomicLong();
    private final long id;
    private final boolean writable;
    private int fd;

    private LinuxMappingSource(int fd, boolean writable, long id) {
        this.fd = fd;
        this.writable = writable;
        this.id = id;
    }

    /** Opens the mapping resource during the FileIO open operation, never during map. */
    public static LinuxMappingSource open(Path path, boolean writable) throws IOException {
        int fd = LinuxMappingCalls.FILES.open(path.toString(), LinuxMappingCalls.O_CLOEXEC |
                (writable ? LinuxMappingCalls.O_RDWR : 0));
        if (fd < 0) {
            HostMappingException failure = LinuxMappingCalls.failure("open", "path=" + path);
            log.debug("event=file_mapping_source_open_failed path={} writable={} errno={}",
                    path, writable, failure.getErrno());
            throw failure;
        }
        long id = SOURCES.incrementAndGet();
        log.debug("event=file_mapping_source_opened source={} host_fd={} path={} writable={}",
                id, fd, path, writable);
        return new LinuxMappingSource(fd, writable, id);
    }

    @Override
    public synchronized MappedFileRegion map(long offset, long length, boolean shared) throws IOException {
        if (fd < 0) {
            throw new IllegalStateException("Mapping source is closed");
        }
        if (offset < 0 || offset % LinuxMappingCalls.PAGE_SIZE != 0 || length <= 0 ||
                length % LinuxMappingCalls.PAGE_SIZE != 0 || offset > Long.MAX_VALUE - length) {
            throw new IllegalArgumentException("Invalid file mapping offset=" + offset + ", length=" + length);
        }
        if (length > Integer.MAX_VALUE) {
            throw new UnsupportedOperationException("File mapping exceeds the direct ByteBuffer capacity");
        }
        if (shared && !writable) {
            throw new UnsupportedOperationException("Shared mapping requires host read/write file access");
        }
        LinuxMappingCalls.FileStat stat = new LinuxMappingCalls.FileStat();
        if (LinuxMappingCalls.FILES.fstat(fd, stat) < 0) {
            throw LinuxMappingCalls.failure("fstat", "source=" + id + ", fd=" + fd);
        }
        if ((stat.mode & LinuxMappingCalls.S_IFMT) != LinuxMappingCalls.S_IFREG) {
            throw new UnsupportedOperationException("File mapping requires a regular file");
        }
        if (offset + length > stat.size) {
            throw new UnsupportedOperationException("File mapping beyond EOF requires guest fault handling");
        }
        return new LinuxMappedFileRegion(LinuxMappingCalls.map(fd, offset, length, shared),
                length, id, offset, shared);
    }

    @Override
    public synchronized void close() throws IOException {
        if (fd < 0) {
            return;
        }
        int closing = fd;
        fd = -1;
        if (LinuxMappingCalls.LIBC.close(closing) < 0) {
            HostMappingException failure = LinuxMappingCalls.failure("close", "source=" + id + ", fd=" + closing);
            log.debug("event=file_mapping_source_close_failed source={} errno={}", id, failure.getErrno());
            throw failure;
        }
        log.debug("event=file_mapping_source_closed source={} host_fd={}", id, closing);
    }
}
