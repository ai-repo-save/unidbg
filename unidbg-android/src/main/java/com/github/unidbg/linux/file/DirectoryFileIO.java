package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.ARM;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.UnidbgFileFilter;
import com.github.unidbg.file.linux.BaseAndroidFileIO;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.struct.Dirent;
import com.github.unidbg.linux.struct.StatFS;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixEmulator;
import com.sun.jna.Pointer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class DirectoryFileIO extends BaseAndroidFileIO {

    private static DirectoryEntry[] createEntries(File dir) {
        List<DirectoryEntry> list = new ArrayList<>();
        File[] files = dir.listFiles(new UnidbgFileFilter());
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                list.add(new DirectoryEntry(file.isFile(), file.getName()));
            }
        }
        return list.toArray(new DirectoryEntry[0]);
    }

    private final String path;
    private final DirectoryIdentity identity;

    private final List<DirectoryEntry> entries;
    private final List<DirectoryEntry> facts;

    public DirectoryFileIO(int oflags, String path, File dir) {
        this(oflags, path, createEntries(dir), DirectoryIdentity.open(dir.toPath()));
    }

    public DirectoryFileIO(int oflags, String path, DirectoryEntry...entries) {
        this(oflags, path, entries, null);
    }

    private DirectoryFileIO(int oflags, String path, DirectoryEntry[] entries, DirectoryIdentity identity) {
        super(oflags);

        this.path = path;
        this.identity = identity;

        this.facts = entries == null ? Collections.emptyList() : Collections.unmodifiableList(Arrays.asList(entries.clone()));
        this.entries = new ArrayList<>();
        this.entries.add(new DirectoryEntry(false, "."));
        this.entries.add(new DirectoryEntry(false, ".."));
        if (entries != null) {
            Collections.addAll(this.entries, entries);
        }
    }

    public Path resolveRelative(String relative) {
        if (identity == null) {
            throw new UnsupportedOperationException("Held directory anchors are unavailable: path=" + path + ", relative=" + relative);
        }
        return identity.resolve(relative);
    }

    public List<DirectoryEntry> queryDirectory() { return facts; }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata() {
        return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFDIR).build());
    }

    @Override
    public int getdents64(Pointer dirp, int size) {
        int offset = 0;
        for (Iterator<DirectoryEntry> iterator = this.entries.iterator(); iterator.hasNext(); ) {
            DirectoryEntry entry = iterator.next();
            byte[] data = entry.name.getBytes(StandardCharsets.UTF_8);
            long d_reclen = ARM.alignSize(19 + data.length + 1, 8);

            if (d_reclen > size - offset) {
                return offset == 0 ? -UnixEmulator.EINVAL : offset;
            }

            Dirent dirent = new Dirent(dirp.share(offset));
            dirent.d_ino = 0;
            dirent.d_off = 0;
            dirent.d_reclen = (short) d_reclen;
            dirent.d_type = entry.type.value;
            dirent.d_name = Arrays.copyOf(data, data.length + 1);
            dirent.pack();
            offset += d_reclen;

            iterator.remove();
        }

        return offset;
    }

    @Override
    public void close() {
        if (identity != null) identity.close();
    }

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        stat.st_mode = IO.S_IFDIR;
        stat.st_dev = 0;
        stat.st_size = 0;
        stat.st_blksize = 0;
        stat.st_ino = 0;
        stat.pack();
        return 0;
    }

    @Override
    public String toString() {
        return path;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public int statfs(StatFS statFS) {
        statFS.setType(0xef53);
        statFS.setBlockSize(0x1000);
        statFS.f_blocks = 0x3235af;
        statFS.f_bfree = 0x2b5763;
        statFS.f_bavail = 0x2b5763;
        statFS.f_files = 0xcccb0;
        statFS.f_ffree = 0xcbd2e;
        statFS.f_fsid = new int[] { 0xd3609fe8, 0x4970d6b };
        statFS.setNameLen(0xff);
        statFS.setFrSize(0x1000);
        statFS.setFlags(0x426);
        statFS.pack();
        return 0;
    }
}
