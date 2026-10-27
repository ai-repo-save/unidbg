package com.github.unidbg.memory.mapping;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

public interface MappedFileRegion extends Closeable {

    int MS_ASYNC = 1;
    int MS_INVALIDATE = 2;
    int MS_SYNC = 4;

    /**
     * Borrows the complete host read/write buffer until close. The caller must
     * remove all guest mappings before closing this region.
     */
    ByteBuffer buffer();

    /**
     * Synchronizes a page-aligned relative range using Linux msync flags.
     * Private changes remain private. This does not change buffer position.
     */
    void sync(long offset, long length, int flags) throws IOException;
}
