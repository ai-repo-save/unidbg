package com.github.unidbg.memory.mapping;

import java.io.Closeable;
import java.io.IOException;

public interface MappingSource extends Closeable {

    /**
     * Maps a byte range of the already opened file without changing its position.
     * Offset must be nonnegative and page-aligned; length must be a positive
     * page multiple. Each mapped page must contain file data; the kernel supplies
     * the zero-filled tail of the final partial page without extending the file.
     * Each returned region survives closing this source. File resizing must use
     * truncate so that all open descriptions coordinate with live mappings.
     */
    MappedFileRegion map(long offset, long length, boolean shared) throws IOException;

    /**
     * Resizes the already opened file without changing its position. A resize
     * requiring guest SIGBUS delivery is rejected before changing the host file.
     */
    void truncate(long length) throws IOException;
}
