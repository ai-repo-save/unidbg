package com.github.unidbg.memory.mapping;

import com.sun.jna.Pointer;
import com.sun.jna.platform.unix.LibCAPI.size_t;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

final class LinuxMappedFileRegion implements MappedFileRegion {

    private static final Logger log = LoggerFactory.getLogger(LinuxMappedFileRegion.class);
    private static final AtomicLong REGIONS = new AtomicLong();
    private final long id = REGIONS.incrementAndGet();
    private final Pointer address;
    private final long length;
    private boolean closed;

    LinuxMappedFileRegion(Pointer address, long length, long source, long offset, boolean shared) {
        this.address = address;
        this.length = length;
        log.debug("event=file_region_mapped source={} region={} offset={} length={} shared={} host_address={}",
                source, id, offset, length, shared, address);
    }

    @Override
    public synchronized ByteBuffer buffer() {
        requireOpen();
        return address.getByteBuffer(0, length);
    }

    @Override
    public synchronized void sync(long offset, long count, int flags) throws IOException {
        requireOpen();
        if (offset < 0 || offset % LinuxMappingCalls.PAGE_SIZE != 0 || count <= 0 ||
                offset > length || count > length - offset ||
                (flags & ~(MS_ASYNC | MS_SYNC | MS_INVALIDATE)) != 0 ||
                (flags & (MS_ASYNC | MS_SYNC)) == (MS_ASYNC | MS_SYNC)) {
            throw new IllegalArgumentException("Invalid mapping sync offset=" + offset +
                    ", length=" + count + ", flags=" + flags);
        }
        if (LinuxMappingCalls.LIBC.msync(address.share(offset), new size_t(count), flags) < 0) {
            HostMappingException failure = LinuxMappingCalls.failure("msync", "region=" + id);
            log.debug("event=file_region_sync_failed region={} offset={} length={} flags={} errno={}",
                    id, offset, count, flags, failure.getErrno());
            throw failure;
        }
        log.debug("event=file_region_synced region={} offset={} length={} flags={}", id, offset, count, flags);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (LinuxMappingCalls.LIBC.munmap(address, new size_t(length)) < 0) {
            HostMappingException failure = LinuxMappingCalls.failure("munmap", "region=" + id);
            log.debug("event=file_region_unmap_failed region={} errno={}", id, failure.getErrno());
            throw failure;
        }
        closed = true;
        log.debug("event=file_region_unmapped region={}", id);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Mapped file region is closed");
        }
    }
}
