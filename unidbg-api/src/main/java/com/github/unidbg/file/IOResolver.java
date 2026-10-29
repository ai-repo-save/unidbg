package com.github.unidbg.file;

import com.github.unidbg.Emulator;

public interface IOResolver<T extends NewFileIO> {

    FileResult<T> resolve(Emulator<T> emulator, String pathname, int oflags);

    /** Pure facts only: must not call resolve or advance operation replay state. */
    FileQueryResult<FileMetadata> queryMetadata(Emulator<T> emulator, String pathname, boolean followLinks);

    /** A successful value is the complete directory, excluding dot entries. */
    FileQueryResult<java.util.List<DirectoryEntry>> queryDirectory(Emulator<T> emulator, String pathname);

}
