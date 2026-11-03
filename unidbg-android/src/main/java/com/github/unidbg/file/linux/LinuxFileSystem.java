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
import com.github.unidbg.linux.file.NullFileIO;
import com.github.unidbg.linux.file.ProcfsFileIO;
import com.github.unidbg.linux.file.ProcfsFiles;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.linux.file.Stdin;
import com.github.unidbg.linux.file.Stdout;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixEmulator;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LinuxFileSystem extends BaseFileSystem<AndroidFileIO> implements FileSystem<AndroidFileIO>, IOConstants {

    private static final int MAX_LINK_HOPS = 8;

    private final ProcfsFiles procfs = new ProcfsFiles();

    public LinuxFileSystem(Emulator<AndroidFileIO> emulator, File rootDir) {
        super(emulator, rootDir);
    }

    /** The process files the runtime renders, and the declarations that replace them. */
    public ProcfsFiles procfs() {
        return procfs;
    }

    /**
     * Resolution order of a process file: a caller's declaration, the live render of the
     * running process, the file laid out under the root directory, and finally the render of
     * the process facts. A declaration is an explicit statement of what the guest must see; a
     * layout is a recorded snapshot of facts the runtime cannot reconstruct (the parent chain,
     * the device identity) and therefore precedes the render that fills in for a process
     * nobody recorded.
     */
    @Override
    public FileResult<AndroidFileIO> open(String pathname, int oflags) {
        if ("/dev/tty".equals(pathname)) {
            return FileResult.success(new NullFileIO(pathname));
        }
        ProcfsFiles.Item item = resolve(pathname);
        return item != null ? openItem(pathname, item, oflags) : super.open(processPath(pathname), oflags);
    }

    private FileResult<AndroidFileIO> openItem(String pathname, ProcfsFiles.Item item, int oflags) {
        switch (item.kind) {
            case FILE:
                return FileResult.success(new ProcfsFileIO(oflags, pathname, item.content, item.metadata));
            case DIRECTORY:
                return FileResult.success(new DirectoryFileIO(oflags, pathname,
                        item.entries.toArray(new DirectoryEntry[0])));
            default:
                // Opening a link reads its target, which resolves through the root directory.
                return super.open(item.target, oflags);
        }
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata(String pathname, boolean followLinks) {
        if ("/dev/tty".equals(pathname)) return FileQueryResult.success(FileMetadata.builder().build());
        return metadata(pathname, followLinks, 0);
    }

    private FileQueryResult<FileMetadata> metadata(String pathname, boolean followLinks, int hops) {
        ProcfsFiles.Item item = resolve(pathname);
        if (item == null) return super.queryMetadata(processPath(pathname), followLinks);
        if (item.kind != ProcfsFiles.Item.Kind.FILE) return facts(item, followLinks, hops);
        return FileQueryResult.success(item.metadata != null ? item.metadata
                : ProcfsFiles.renderedFacts(item.content));
    }

    private FileQueryResult<FileMetadata> facts(ProcfsFiles.Item item, boolean followLinks, int hops) {
        if (item.kind == ProcfsFiles.Item.Kind.DIRECTORY) return FileQueryResult.success(item.metadata);
        if (!followLinks) {
            return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFLNK | 0777)
                    .linkTarget(item.target).build());
        }
        if (hops >= MAX_LINK_HOPS) return FileQueryResult.failed(UnixEmulator.ELOOP);
        return metadata(item.target, true, hops + 1);
    }

    @Override
    public FileQueryResult<List<DirectoryEntry>> queryDirectory(String pathname) {
        if ("/dev/tty".equals(pathname)) return FileQueryResult.failed(UnixEmulator.ENOTDIR);
        String path = processPath(pathname);
        ProcfsFiles.Item rendered = procfs.rendered(emulator, path);
        ProcfsFiles.Item item = resolve(pathname);
        if (item != null && item.kind != ProcfsFiles.Item.Kind.DIRECTORY) {
            return FileQueryResult.failed(UnixEmulator.ENOTDIR);
        }
        FileQueryResult<List<DirectoryEntry>> layout = super.queryDirectory(path);
        if (rendered == null) return layout;
        List<DirectoryEntry> entries = new ArrayList<>();
        if (layout.isSuccess()) entries.addAll(layout.value);
        for (DirectoryEntry entry : rendered.entries) {
            boolean listed = false;
            for (DirectoryEntry present : entries) listed |= present.name.equals(entry.name);
            if (!listed) entries.add(entry);
        }
        return FileQueryResult.success(entries);
    }

    @Override
    public String readlink(String pathname) {
        ProcfsFiles.Item item = resolve(pathname);
        if (item != null) return item.kind == ProcfsFiles.Item.Kind.LINK ? item.target : null;
        return super.readlink(processPath(pathname));
    }

    /**
     * The process file answering this path: a declaration first, then the live render, then
     * the file laid out under the root directory, then the render of the process facts.
     */
    private ProcfsFiles.Item resolve(String pathname) {
        String path = processPath(pathname);
        ProcfsFiles.Item declared = procfs.declared(emulator.getPid(), path);
        if (declared != null) return declared;
        ProcfsFiles.Item live = procfs.live(emulator, path);
        if (live != null) return live;
        if (laidOut(path)) return null;
        return procfs.rendered(emulator, path);
    }

    /** The path of this process, with {@code /proc/<pid>} resolved to {@code /proc/self}. */
    private String processPath(String pathname) {
        return ProcfsFiles.canonical(emulator.getPid(), pathname);
    }

    /** A layout may hold a symbolic link whose target the layout does not: the link is still there. */
    private boolean laidOut(String path) {
        File file = new File(getRootDir(), path);
        return file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath());
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
