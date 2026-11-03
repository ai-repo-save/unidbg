package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.FileQuerySupport;

/**
 * One file of the process view: the bytes a caller declared or the runtime rendered, with the
 * facts {@code ProcfsFiles} answers for its path.
 */
public class ProcfsFileIO extends ByteArrayFileIO {

    private final FileMetadata metadata;

    public ProcfsFileIO(int oflags, String path, byte[] bytes, FileMetadata metadata) {
        super(oflags, path, bytes);
        this.metadata = metadata;
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata() {
        return FileQueryResult.success(metadata);
    }

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        return FileQuerySupport.writeStat(queryMetadata(), stat, path);
    }
}
