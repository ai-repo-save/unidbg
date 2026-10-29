package com.github.unidbg.linux;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.struct.Stat32;
import com.github.unidbg.linux.struct.Stat64;
import com.github.unidbg.pointer.UnidbgStructure;
import com.github.unidbg.unix.UnixEmulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

final class FileQuerySupport {
    private static final Logger log = LoggerFactory.getLogger(FileQuerySupport.class);

    static <V> V require(FileQueryResult<V> result, String path) {
        if (result.isSuccess()) return result.value;
        if (result.status() == FileQueryResult.Status.ERROR) throw new LinuxErrnoException(result.errno);
        throw new UnsupportedOperationException("File facts unavailable: " + path);
    }

    static StatStructure snapshot(Emulator<AndroidFileIO> emulator, FileMetadata facts, String path) {
        List<String> missing = new ArrayList<>();
        if (!facts.mode.isPresent()) missing.add("mode");
        if (!facts.uid.isPresent()) missing.add("uid");
        if (!facts.gid.isPresent()) missing.add("gid");
        if (!facts.nlink.isPresent()) missing.add("nlink");
        if (!facts.blksize.isPresent()) missing.add("blksize");
        if (!facts.dev.isPresent()) missing.add("dev");
        if (!facts.ino.isPresent()) missing.add("ino");
        if (!facts.rdev.isPresent()) missing.add("rdev");
        if (!facts.size.isPresent()) missing.add("size");
        if (!facts.blocks.isPresent()) missing.add("blocks");
        if (!facts.atime.isPresent()) missing.add("atime");
        if (!facts.mtime.isPresent()) missing.add("mtime");
        if (!facts.ctime.isPresent()) missing.add("ctime");
        if (!missing.isEmpty()) throw new UnsupportedOperationException("File facts unavailable: " + path + ", missing=" + missing);
        StatStructure stat = emulator.is64Bit()
                ? new Stat64(new byte[UnidbgStructure.calculateSize(Stat64.class)])
                : new Stat32(new byte[UnidbgStructure.calculateSize(Stat32.class)]);
        stat.st_mode = facts.mode.getAsInt();
        stat.st_uid = facts.uid.getAsInt();
        stat.st_gid = facts.gid.getAsInt();
        stat.st_nlink = facts.nlink.getAsInt();
        stat.st_blksize = facts.blksize.getAsInt();
        stat.st_dev = facts.dev.getAsLong();
        stat.setSt_ino(facts.ino.getAsLong());
        stat.st_rdev = facts.rdev.getAsLong();
        stat.st_size = facts.size.getAsLong();
        stat.st_blocks = facts.blocks.getAsLong();
        setTime(facts.atime.get(), stat::setSt_atim);
        setTime(facts.mtime.get(), stat::setSt_mtim);
        setTime(facts.ctime.get(), stat::setSt_ctim);
        stat.pack();
        return stat;
    }

    private static void setTime(FileTime time, java.util.function.BiConsumer<Long, Long> setter) {
        java.time.Instant instant = time.toInstant();
        setter.accept(Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1000), instant.getNano() / 1000000), (long) instant.getNano());
    }

    static void accessPath(String path, int mode, GuestCredentials credentials, int emulator, long check,
                           java.util.function.Function<String, FileQueryResult<FileMetadata>> metadata) {
        String current = path.startsWith("/") ? "/" : ".";
        FileMetadata facts = require(metadata.apply(current), current);
        int offset = 0;
        while (offset < path.length()) {
            while (offset < path.length() && path.charAt(offset) == '/') offset++;
            if (offset == path.length()) break;
            requireDirectory(facts, current);
            access(facts, current, 1, credentials, emulator, check);
            int end = path.indexOf('/', offset);
            if (end < 0) end = path.length();
            String component = path.substring(offset, end);
            current = current.equals("/") ? current + component
                    : current.equals(".") && offset == 0 ? component : current + "/" + component;
            facts = require(metadata.apply(current), current);
            if (facts.mode.isPresent() && (facts.mode.getAsInt() & 0170000) == 0120000) {
                throw unavailable(current, "symlink target and search ancestors");
            }
            offset = end;
        }
        if (path.endsWith("/")) requireDirectory(facts, current);
        access(facts, current, mode, credentials, emulator, check);
    }

    private static void requireDirectory(FileMetadata facts, String path) {
        if (!facts.mode.isPresent()) throw unavailable(path, "mode");
        if ((facts.mode.getAsInt() & 0170000) != 0040000) throw new LinuxErrnoException(UnixEmulator.ENOTDIR);
    }

    private static void access(FileMetadata facts, String path, int mode, GuestCredentials credentials, int emulator, long check) {
        if (mode == 0) {
            requireAccess(true, path, mode, "existence", emulator, check);
            return;
        }
        if (facts.mode.isPresent()) {
            int permissions = facts.mode.getAsInt();
            if (((permissions >> 6) & mode) == mode && ((permissions >> 3) & mode) == mode
                    && (permissions & mode) == mode) {
                requireAccess(true, path, mode, "all_classes", emulator, check);
                return;
            }
        }
        int uid = credentials.requireRealUid();
        if (uid == 0 && (mode & 1) == 0) {
            requireAccess(true, path, mode, "root", emulator, check);
            return;
        }
        if (!facts.mode.isPresent()) throw unavailable(path, "mode");
        int permissions = facts.mode.getAsInt();
        if (uid == 0) {
            requireAccess((permissions & 0170000) == 0040000 || (permissions & 0111) != 0,
                    path, mode, "root", emulator, check);
            return;
        }
        int owner = (permissions >> 6) & 7;
        int group = (permissions >> 3) & 7;
        int other = permissions & 7;
        boolean ownerAllows = (owner & mode) == mode;
        boolean groupAllows = (group & mode) == mode;
        boolean otherAllows = (other & mode) == mode;
        if (ownerAllows == groupAllows && groupAllows == otherAllows) {
            requireAccess(ownerAllows, path, mode, "all_classes", emulator, check);
            return;
        }
        if (!facts.uid.isPresent()) throw unavailable(path, "uid");
        if (uid == facts.uid.getAsInt()) {
            requireAccess(ownerAllows, path, mode, "owner", emulator, check);
            return;
        }
        if (groupAllows == otherAllows) {
            requireAccess(groupAllows, path, mode, "group_and_other", emulator, check);
            return;
        }
        if (!facts.gid.isPresent()) throw unavailable(path, "gid");
        int gid = facts.gid.getAsInt();
        if (credentials.realGid.isPresent() && credentials.realGid.getAsInt() == gid) {
            requireAccess(groupAllows, path, mode, "real_group", emulator, check);
            return;
        }
        if (credentials.supplementaryGroups.isPresent() && credentials.supplementaryGroups.get().contains(gid)) {
            requireAccess(groupAllows, path, mode, "supplementary_group", emulator, check);
            return;
        }
        if (!credentials.realGid.isPresent()) throw unavailable(path, "realGid");
        if (!credentials.supplementaryGroups.isPresent()) throw unavailable(path, "supplementaryGroups");
        requireAccess(otherAllows, path, mode, "other", emulator, check);
    }

    private static void requireAccess(boolean allowed, String path, int mode, String permissionClass, int emulator, long check) {
        log.debug("event=file_access_dac_checked emulator={} check={} path={} mode={} permission_class={} status={}",
                emulator, check, path, mode, permissionClass, allowed ? "allowed" : "denied");
        if (!allowed) throw new LinuxErrnoException(UnixEmulator.EACCES);
    }

    private static UnsupportedOperationException unavailable(String path, String field) {
        return new UnsupportedOperationException("File access facts unavailable: " + path + ", missing=" + field);
    }

    private FileQuerySupport() { }
}
