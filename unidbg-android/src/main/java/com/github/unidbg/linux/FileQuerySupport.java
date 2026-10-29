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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class FileQuerySupport {
    private static final Logger log = LoggerFactory.getLogger(FileQuerySupport.class);
    private static final int ELOOP = 40;
    private static final int S_IFMT = 0170000;
    private static final int S_IFLNK = 0120000;
    private static final int S_IFDIR = 0040000;

    static <V> V require(FileQueryResult<V> result, String path) {
        if (result.isSuccess()) return result.value;
        if (result.status() == FileQueryResult.Status.ERROR) throw new LinuxErrnoException(result.errno);
        throw new UnsupportedOperationException("File facts unavailable: " + path);
    }

    public static int writeStat(FileQueryResult<FileMetadata> result, StatStructure target, String source) {
        try {
            fillStat(require(result, source), target, source);
            return 0;
        } catch (LinuxErrnoException failure) {
            return -failure.getErrno();
        }
    }

    static StatStructure snapshot(Emulator<AndroidFileIO> emulator, FileMetadata facts, String path) {
        StatStructure stat = emulator.is64Bit()
                ? new Stat64(new byte[UnidbgStructure.calculateSize(Stat64.class)])
                : new Stat32(new byte[UnidbgStructure.calculateSize(Stat32.class)]);
        fillStat(facts, stat, path);
        return stat;
    }

    private static void fillStat(FileMetadata facts, StatStructure stat, String path) {
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
    }

    private static void setTime(FileTime time, java.util.function.BiConsumer<Long, Long> setter) {
        java.time.Instant instant = time.toInstant();
        setter.accept(Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1000), instant.getNano() / 1000000), (long) instant.getNano());
    }

    static void accessPath(String path, int mode, GuestCredentials credentials, int emulator, long check,
                           java.util.function.Function<String, FileQueryResult<FileMetadata>> metadata) {
        Walked walked = walk(path, true, true, credentials, emulator, check, metadata, 0);
        if (walked.trailingDirectory) requireDirectory(walked.facts, walked.path);
        access(walked.facts, walked.path, mode, credentials, emulator, check);
    }

    static String collapseDotComponents(String path) {
        return followLinkTargets(path, true, component -> null);
    }

    static String followLinkTargets(String path, boolean followFinal,
                                    java.util.function.Function<String, FileQueryResult<FileMetadata>> metadata) {
        if (path.isEmpty()) return path;
        String resolved = walk(path, followFinal, false, null, 0, 0, metadata, 0).path;
        if (resolved.length() > 1 && resolved.endsWith("/")) return resolved.substring(0, resolved.length() - 1);
        return resolved;
    }

    private static Walked walk(String path, boolean followFinal, boolean enforceSearch, GuestCredentials credentials,
                               int emulator, long check,
                               java.util.function.Function<String, FileQueryResult<FileMetadata>> metadata, int followed) {
        boolean absolute = path.startsWith("/");
        boolean trailing = path.endsWith("/") && !"/".equals(path);
        Deque<String> pending = new ArrayDeque<>(components(path));
        List<String> stack = new ArrayList<>();
        String directory = absolute ? "/" : ".";
        FileMetadata facts = enforceSearch ? require(metadata.apply(directory), directory) : null;
        while (!pending.isEmpty()) {
            String component = pending.removeFirst();
            if (".".equals(component) || "..".equals(component)) {
                if (enforceSearch) {
                    requireDirectory(facts, directory);
                    access(facts, directory, 1, credentials, emulator, check);
                }
                if ("..".equals(component)) {
                    if (!stack.isEmpty() && !"..".equals(stack.get(stack.size() - 1))) stack.remove(stack.size() - 1);
                    else if (!absolute) stack.add("..");
                    directory = join(absolute, stack);
                    if (enforceSearch) facts = require(metadata.apply(directory), directory);
                }
                continue;
            }
            if (enforceSearch) {
                requireDirectory(facts, directory);
                access(facts, directory, 1, credentials, emulator, check);
            }
            boolean last = pending.isEmpty();
            stack.add(component);
            directory = join(absolute, stack);
            FileQueryResult<FileMetadata> result = metadata.apply(directory);
            facts = enforceSearch ? require(result, directory) : result != null && result.isSuccess() ? result.value : null;
            if (facts == null || !isSymlink(facts)) continue;
            if (last && !followFinal) {
                log.debug("event=file_symlink_rejected path={} reason=nofollow errno={}", directory, ELOOP);
                throw new LinuxErrnoException(ELOOP);
            }
            if (!facts.linkTarget.isPresent()) throw unavailable(directory, "symlink target and search ancestors");
            if (++followed > 40) {
                log.debug("event=file_symlink_rejected path={} reason=loop errno={}", directory, ELOOP);
                throw new LinuxErrnoException(ELOOP);
            }
            String target = facts.linkTarget.get();
            log.debug("event=file_symlink_followed path={} target={}", directory, target);
            stack.remove(stack.size() - 1);
            String continued = compose(join(absolute, stack), target, remainder(pending, trailing));
            return walk(continued, followFinal, enforceSearch, credentials, emulator, check, metadata, followed);
        }
        return new Walked(directory, facts, trailing);
    }

    private static List<String> components(String path) {
        List<String> components = new ArrayList<>();
        int offset = 0;
        while (offset < path.length()) {
            while (offset < path.length() && path.charAt(offset) == '/') offset++;
            if (offset == path.length()) break;
            int end = path.indexOf('/', offset);
            if (end < 0) end = path.length();
            components.add(path.substring(offset, end));
            offset = end;
        }
        return components;
    }

    private static String join(boolean absolute, List<String> stack) {
        if (stack.isEmpty()) return absolute ? "/" : ".";
        String body = String.join("/", stack);
        return absolute ? "/" + body : body;
    }

    private static String remainder(Deque<String> pending, boolean trailing) {
        String rest = pending.isEmpty() ? "" : "/" + String.join("/", pending);
        return trailing ? rest + "/" : rest;
    }

    private static String compose(String parent, String target, String rest) {
        String base = target.startsWith("/") ? target
                : "/".equals(parent) ? "/" + target
                : ".".equals(parent) ? target : parent + "/" + target;
        return rest.isEmpty() ? base : base + rest;
    }

    private static boolean isSymlink(FileMetadata facts) {
        return facts.mode.isPresent() && (facts.mode.getAsInt() & S_IFMT) == S_IFLNK;
    }

    private static final class Walked {
        private final String path;
        private final FileMetadata facts;
        private final boolean trailingDirectory;

        private Walked(String path, FileMetadata facts, boolean trailingDirectory) {
            this.path = path;
            this.facts = facts;
            this.trailingDirectory = trailingDirectory;
        }
    }

    private static void requireDirectory(FileMetadata facts, String path) {
        if (!facts.mode.isPresent()) throw unavailable(path, "mode");
        if ((facts.mode.getAsInt() & S_IFMT) != S_IFDIR) throw new LinuxErrnoException(UnixEmulator.ENOTDIR);
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
