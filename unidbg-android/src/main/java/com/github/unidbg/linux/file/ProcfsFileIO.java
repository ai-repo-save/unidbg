package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.FileQuerySupport;

/**
 * One file of the process view. A declaration answers its recorded facts; a render answers the
 * mode and size the runtime knows and leaves the remaining facts to the byte-array defaults.
 */
public final class ProcfsFileIO extends ByteArrayFileIO {

    private final FileMetadata metadata;

    public ProcfsFileIO(int oflags, String path, byte[] bytes, FileMetadata metadata) {
        super(oflags, path, bytes);
        this.metadata = metadata;
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata() {
        return metadata != null ? FileQueryResult.success(metadata) : super.queryMetadata();
    }

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        return metadata != null ? FileQuerySupport.writeStat(queryMetadata(), stat, path)
                : super.fstat(emulator, stat);
    }
}
