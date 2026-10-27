package com.github.unidbg.arm.backend;

import java.nio.ByteBuffer;

public interface HostMemoryBackend {

    /**
     * Maps the entire writable direct buffer, independently of its position and limit.
     * The caller owns the storage and must keep it alive until the mapping is
     * unmapped or the backend is destroyed. Guest permissions do not change the
     * required host read/write access.
     */
    void mem_map_host(long address, ByteBuffer buffer, int perms) throws BackendException;
}
