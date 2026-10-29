package com.github.unidbg.ios.dmg;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.HostFileFacts;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.ios.file.DirectoryFileIO;
import com.github.unidbg.ios.file.SimpleFileIO;
import com.github.unidbg.unix.IO;
import org.apache.commons.io.FilenameUtils;

import java.io.File;

class DmgResolver implements IOResolver<DarwinFileIO> {

    private final File dmgDir;

    DmgResolver(File dmgDir) {
        this.dmgDir = dmgDir;
    }

    @Override
    public FileResult<DarwinFileIO> resolve(Emulator<DarwinFileIO> emulator, String pathname, int oflags) {
        File file = queryFile(pathname);
        if (file != null && file.exists()) {
            return file.isFile() ? FileResult.<DarwinFileIO>success(new SimpleFileIO(oflags, file, pathname))
                    : FileResult.<DarwinFileIO>success(new DirectoryFileIO(oflags, pathname, file));
        }

        return null;
    }

    private File queryFile(String pathname) {
        pathname = FilenameUtils.normalize(pathname, true);
        String dir = FilenameUtils.normalize(dmgDir.getAbsolutePath(), true);
        File file = new File(pathname);
        return pathname.startsWith(dir) ? file : null;
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata(Emulator<DarwinFileIO> emulator, String path, boolean followLinks) {
        File file = queryFile(path);
        return file == null ? null : HostFileFacts.metadata(file, followLinks);
    }

    @Override
    public FileQueryResult<java.util.List<DirectoryEntry>> queryDirectory(Emulator<DarwinFileIO> emulator, String path) {
        File file = queryFile(path);
        return file == null ? null : HostFileFacts.directory(file);
    }

}
