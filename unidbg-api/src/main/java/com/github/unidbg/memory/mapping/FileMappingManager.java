package com.github.unidbg.memory.mapping;

import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.HostMemoryBackend;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.unix.UnixEmulator;
import unicorn.UnicornConst;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FileMappingManager {

    private static final int MAP_SHARED = 1;
    private final Backend backend;
    private final Map<Long, MemoryMap> maps;
    private final int pageSize;
    private final Map<MappedFileRegion, Long> allocations = new IdentityHashMap<>();

    public FileMappingManager(Backend backend, Map<Long, MemoryMap> maps, int pageSize) {
        this.backend = backend;
        this.maps = maps;
        this.pageSize = pageSize;
    }

    public PendingMapping prepare(MappingSource source, int length, int prot, int flags,
                                  long offset, boolean mayWrite) throws IOException {
        if (!(backend instanceof HostMemoryBackend)) {
            throw new UnsupportedOperationException("Backend does not support host-backed file mappings");
        }
        rejectExecutable(prot);
        return new PendingMapping(source.map(offset, length, (flags & MAP_SHARED) != 0),
                length, prot, flags, offset, mayWrite);
    }

    public final class PendingMapping implements AutoCloseable {
        private final MappedFileRegion region;
        private final int length, prot, flags;
        private final long offset;
        private final boolean mayWrite;
        private boolean committed;

        private PendingMapping(MappedFileRegion region, int length, int prot, int flags, long offset,
                               boolean mayWrite) {
            this.region = region;
            this.length = length;
            this.prot = prot;
            this.flags = flags;
            this.offset = offset;
            this.mayWrite = mayWrite;
        }

        public MemoryMap map(long address) {
            if (committed) {
                throw new IllegalStateException("File mapping is already committed");
            }
            ((HostMemoryBackend) backend).mem_map_host(address, region.buffer(), prot);
            allocations.put(region, offset);
            committed = true;
            return new MemoryMap(address, length, prot, flags, offset, region, mayWrite);
        }

        @Override
        public void close() throws IOException {
            if (!committed) {
                region.close();
            }
        }
    }

    public void checkProtection(long address, long length, int prot) {
        for (MemoryMap map : maps.values()) {
            if (map.backing != null && address < map.base + map.size && address + length > map.base) {
                rejectExecutable(prot);
            }
        }
    }

    private static void rejectExecutable(int prot) {
        if ((prot & UnicornConst.UC_PROT_EXEC) != 0) {
            throw new UnsupportedOperationException("Executable file aliases require coherent JIT invalidation");
        }
    }

    /** Linux raw syscall result; no platform errno storage is modified. */
    public int sync(long address, int length, int flags) throws IOException {
        if (address % pageSize != 0 || length < 0 ||
                (flags & ~(MappedFileRegion.MS_SYNC | MappedFileRegion.MS_ASYNC | MappedFileRegion.MS_INVALIDATE)) != 0 ||
                (flags & (MappedFileRegion.MS_SYNC | MappedFileRegion.MS_ASYNC)) ==
                        (MappedFileRegion.MS_SYNC | MappedFileRegion.MS_ASYNC)) {
            return -UnixEmulator.EINVAL;
        }
        if (length == 0) {
            return 0;
        }
        long end = address + ((length + (long) pageSize - 1) / pageSize) * pageSize;
        List<MemoryMap> affected = covering(address, end);
        if (affected == null) {
            return -UnixEmulator.ENOMEM;
        }
        for (MemoryMap map : affected) {
            if (map.backing != null) {
                long start = Math.max(address, map.base);
                long count = Math.min(end, map.base + map.size) - start;
                long offset = map.offset + start - map.base - allocations.get(map.backing);
                map.backing.sync(offset, count, flags);
            }
        }
        return 0;
    }

    public int discard(long address, int length) throws IOException {
        if (address % pageSize != 0 || length < 0) {
            return -UnixEmulator.EINVAL;
        }
        if (length == 0) {
            return 0;
        }
        long end = address + ((length + (long) pageSize - 1) / pageSize) * pageSize;
        List<MemoryMap> affected = covering(address, end);
        if (affected == null) {
            return -UnixEmulator.ENOMEM;
        }
        for (MemoryMap map : affected) {
            if (map.backing != null) {
                long start = Math.max(address, map.base);
                long count = Math.min(end, map.base + map.size) - start;
                long offset = map.offset + start - map.base - allocations.get(map.backing);
                map.backing.discard(offset, count);
            }
        }
        return 0;
    }

    private List<MemoryMap> covering(long address, long end) {
        long cursor = address;
        List<MemoryMap> affected = new ArrayList<>();
        for (MemoryMap map : maps.values()) {
            if (cursor >= end) {
                break;
            }
            if (map.base + map.size <= cursor) {
                continue;
            }
            if (map.base > cursor) {
                return null;
            }
            affected.add(map);
            cursor = Math.min(end, map.base + map.size);
        }
        return cursor == end ? affected : null;
    }

    public void releaseUnused() {
        Set<MappedFileRegion> referenced = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MemoryMap map : maps.values()) {
            if (map.backing != null) {
                referenced.add(map.backing);
            }
        }
        Iterator<MappedFileRegion> iterator = allocations.keySet().iterator();
        while (iterator.hasNext()) {
            MappedFileRegion region = iterator.next();
            if (!referenced.contains(region)) {
                close(region);
                iterator.remove();
            }
        }
    }

    public void close() {
        for (MemoryMap map : new ArrayList<>(maps.values())) {
            if (map.backing != null) {
                backend.mem_unmap(map.base, map.size);
                maps.remove(map.base);
            }
        }
        releaseUnused();
    }

    private static void close(MappedFileRegion region) {
        try {
            region.close();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
