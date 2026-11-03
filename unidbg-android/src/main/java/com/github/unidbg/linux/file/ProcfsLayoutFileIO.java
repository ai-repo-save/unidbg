package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.FileQuerySupport;

import java.io.File;

/**
 * One file of the process view whose bytes the root directory holds. The recording supplies the
 * content, which the host file system also answers, but not the facts: the process view owns them,
 * so this file reports what {@code ProcfsFiles} answers for its path instead of the mode, owner
 * and timestamps of the file that happens to store the bytes.
 */
public final class ProcfsLayoutFileIO extends SimpleFileIO {

    private final FileMetadata facts;

    public ProcfsLayoutFileIO(int oflags, File file, String path, FileMetadata facts) {
        super(oflags, file, path);
        this.facts = facts;
    }

    private ProcfsLayoutFileIO(ProcfsLayoutFileIO original) {
        super(original);
        facts = original.facts;
    }

    private ProcfsLayoutFileIO(ProcfsLayoutFileIO original, int flags) {
        super(original, flags);
        facts = original.facts;
    }

    @Override
    public FileIO dup2() {
        return new ProcfsLayoutFileIO(this);
    }

    @Override
    public FileIO reopen(int flags) {
        return new ProcfsLayoutFileIO(this, flags);
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata() {
        return FileQueryResult.success(facts);
    }

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        return FileQuerySupport.writeStat(queryMetadata(), stat, path);
    }
}
