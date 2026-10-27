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
    private final LinuxFileMappingRegistry.Key identity;
    private int fd;

    private LinuxMappingSource(int fd, boolean writable, long id, LinuxFileMappingRegistry.Key identity) {
        this.fd = fd;
        this.writable = writable;
        this.id = id;
        this.identity = identity;
    }

    /** Opens the mapping resource during the FileIO open operation, never during map. */
    public static LinuxMappingSource open(Path path, boolean writable) throws IOException {
        int fd = LinuxMappingCalls.FILES.open(path.toString(), LinuxMappingCalls.O_CLOEXEC |
                (writable ? LinuxMappingCalls.O_RDWR : 0));
        if (fd < 0) {
            HostMappingException failure = LinuxMappingCalls.failure("open", "path=" + path);
            log.debug("event=file_mapping_source_open_failed path={} writable={} errno={}",
                    path, writable, failure.getErrno(), failure);
            throw failure;
        }
        long id = SOURCES.incrementAndGet();
        try {
            LinuxMappingCalls.FileStat stat = stat(fd, id);
            if ((stat.mode & LinuxMappingCalls.S_IFMT) != LinuxMappingCalls.S_IFREG) {
                throw new UnsupportedOperationException("File mapping requires a regular file");
            }
            LinuxFileMappingRegistry.Key identity = new LinuxFileMappingRegistry.Key(stat.device, stat.inode);
            log.debug("event=file_mapping_source_opened source={} host_fd={} path={} writable={} device={} inode={}",
                    id, fd, path, writable, stat.device, stat.inode);
            return new LinuxMappingSource(fd, writable, id, identity);
        } catch (IOException | RuntimeException | Error failure) {
            if (LinuxMappingCalls.LIBC.close(fd) < 0) {
                failure.addSuppressed(LinuxMappingCalls.failure("close", "source=" + id + ", fd=" + fd));
            }
            throw failure;
        }
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
        synchronized (LinuxFileMappingRegistry.LOCK) {
            LinuxMappingCalls.FileStat stat = stat(fd, id);
            if (offset >= stat.size || (offset + length - 1) / LinuxMappingCalls.PAGE_SIZE >
                    (stat.size - 1) / LinuxMappingCalls.PAGE_SIZE) {
                throw new UnsupportedOperationException("File mapping of pages beyond EOF requires guest fault handling");
            }
            try {
                return new LinuxMappedFileRegion(LinuxMappingCalls.map(fd, offset, length, shared),
                        length, id, offset, shared, identity);
            } catch (HostMappingException failure) {
                log.debug("event=file_region_map_failed source={} offset={} length={} shared={} errno={}",
                        id, offset, length, shared, failure.getErrno(), failure);
                throw failure;
            }
        }
    }

    @Override
    public synchronized void truncate(long length) throws IOException {
        if (fd < 0) {
            throw new IllegalStateException("Mapping source is closed");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Negative file length: " + length);
        }
        synchronized (LinuxFileMappingRegistry.LOCK) {
            try {
                LinuxFileMappingRegistry.checkTruncate(identity, length);
            } catch (UnsupportedOperationException failure) {
                log.debug("event=file_mapping_truncate_unsupported source={} length={}", id, length, failure);
                throw failure;
            }
            try {
                LinuxMappingCalls.truncate(fd, length);
            } catch (HostMappingException failure) {
                log.debug("event=file_mapping_truncate_failed source={} length={} errno={}",
                        id, length, failure.getErrno(), failure);
                throw failure;
            }
            log.debug("event=file_mapping_truncated source={} length={}", id, length);
        }
    }

    private static LinuxMappingCalls.FileStat stat(int fd, long source) throws HostMappingException {
        LinuxMappingCalls.FileStat stat = new LinuxMappingCalls.FileStat();
        if (LinuxMappingCalls.FILES.fstat(fd, stat) < 0) {
            HostMappingException failure = LinuxMappingCalls.failure("fstat", "source=" + source + ", fd=" + fd);
            log.debug("event=file_mapping_stat_failed source={} errno={}", source, failure.getErrno(), failure);
            throw failure;
        }
        return stat;
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
            log.debug("event=file_mapping_source_close_failed source={} errno={}", id, failure.getErrno(), failure);
            throw failure;
        }
        log.debug("event=file_mapping_source_closed source={} host_fd={}", id, closing);
    }
}
