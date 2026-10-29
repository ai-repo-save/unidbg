package com.github.unidbg.file.linux;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.BaseFileSystem;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.FileSystem;
import com.github.unidbg.linux.android.LogCatHandler;
import com.github.unidbg.linux.file.DirectoryFileIO;
import com.github.unidbg.linux.file.MapsFileIO;
import com.github.unidbg.linux.file.NullFileIO;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.linux.file.Stdin;
import com.github.unidbg.linux.file.Stdout;
import com.github.unidbg.unix.IO;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class LinuxFileSystem extends BaseFileSystem<AndroidFileIO> implements FileSystem<AndroidFileIO>, IOConstants {

    public LinuxFileSystem(Emulator<AndroidFileIO> emulator, File rootDir) {
        super(emulator, rootDir);
    }

    @Override
    public FileResult<AndroidFileIO> open(String pathname, int oflags) {
        if ("/dev/tty".equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new NullFileIO(pathname));
        }
        if (isMaps(pathname)) {
            return FileResult.<AndroidFileIO>success(new MapsFileIO(emulator, oflags, pathname, emulator.getMemory().getLoadedModules()));
        }

        return super.open(pathname, oflags);
    }

    private boolean isMaps(String path) {
        return "/proc/self/maps".equals(path) || ("/proc/" + emulator.getPid() + "/maps").equals(path)
                || ("/proc/self/task/" + emulator.getPid() + "/maps").equals(path);
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata(String path, boolean followLinks) {
        if (isMaps(path)) return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFREG).build());
        if ("/dev/tty".equals(path)) return FileQueryResult.success(FileMetadata.builder().build());
        return super.queryMetadata(path, followLinks);
    }

    @Override
    public FileQueryResult<java.util.List<DirectoryEntry>> queryDirectory(String path) {
        if (isMaps(path) || "/dev/tty".equals(path)) return FileQueryResult.failed(com.github.unidbg.unix.UnixEmulator.ENOTDIR);
        return super.queryDirectory(path);
    }

    public LogCatHandler getLogCatHandler() {
        return null;
    }

    @Override
    protected void initialize(File rootDir) throws IOException {
        super.initialize(rootDir);

        FileUtils.forceMkdir(new File(rootDir, "system"));
        FileUtils.forceMkdir(new File(rootDir, "data"));
    }

    @Override
    public AndroidFileIO createSimpleFileIO(File file, int oflags, String path) {
        return new SimpleFileIO(oflags, file, path);
    }

    @Override
    public AndroidFileIO createDirectoryFileIO(File file, int oflags, String path) {
        return new DirectoryFileIO(oflags, path, file);
    }

    @Override
    protected AndroidFileIO createStdin(int oflags) {
        return new Stdin(oflags);
    }

    @Override
    protected AndroidFileIO createStdout(int oflags, File stdio, String pathname) {
        return new Stdout(oflags, stdio, pathname, IO.STDERR.equals(pathname), null);
    }

    @Override
    protected boolean hasCreat(int oflags) {
        return (oflags & O_CREAT) != 0;
    }

    @Override
    protected boolean hasDirectory(int oflags) {
        return (oflags & O_DIRECTORY) != 0;
    }

    @Override
    protected boolean hasAppend(int oflags) {
        return (oflags & O_APPEND) != 0;
    }

    @Override
    protected boolean hasExcl(int oflags) {
        return (oflags & O_EXCL) != 0;
    }
}
