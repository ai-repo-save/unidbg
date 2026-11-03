package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.AndroidSyscallHandler;
import com.github.unidbg.linux.GuestCredentials;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.unix.IO;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files the kernel renders for a process under {@code /proc}, and the declarations that
 * replace them with what an observer recorded.
 *
 * The runtime holds one process, so {@code /proc/self} and {@code /proc/<pid>} name the same
 * process and every lookup resolves through that equality. A name without a declaration is
 * rendered from what the runtime holds: the process identity (pid, name, credentials), the
 * argument vector, the executable, the mount table and the address space. Facts only the
 * device has (the SELinux domain, the parent chain, the recorded text) arrive as declarations.
 *
 * A declaration for another pid ({@code /proc/1/stat}) belongs to that process and is never
 * aliased onto this one: the runtime knows nothing about a parent, so it serves only what an
 * observer recorded for it.
 *
 * The facts of a file in this view are the view's own: {@link #facts} answers them from the
 * path, the process credentials and the file's kind, whether the bytes came from a declaration,
 * a render or the root directory, so the guest sees one answer for one path.
 */
public final class ProcfsFiles {

    private static final String PROC = "/proc";
    private static final String SELF = PROC + "/self";
    private static final String STATUS = "status";
    private static final String STAT = "stat";
    private static final String COMM = "comm";
    private static final String CMDLINE = "cmdline";
    private static final String MOUNTS = "mounts";
    private static final String MOUNTINFO = "mountinfo";
    private static final String MAPS = "maps";
    private static final String EXE = "exe";
    private static final String ATTR = "attr";

    /** Names rendered from the runtime when no declaration replaces them. */
    private static final List<String> RENDERED = Collections.unmodifiableList(Arrays.asList(
            STATUS, STAT, COMM, CMDLINE, MOUNTS, MOUNTINFO, MAPS, EXE));

    private static final String MOUNTS_TEXT = "rootfs / rootfs rw 0 0\nproc /proc proc rw,relatime 0 0\n";
    private static final String MOUNTINFO_TEXT =
            "1 0 0:1 / / rw - rootfs rootfs rw\n2 1 0:2 / /proc rw,relatime - proc proc rw\n";

    /** A generated file is read-only: the guest reads it and the runtime answers it. */
    private static final int FILE_MODE = IO.S_IFREG | 0444;
    /** The block size the runtime reports, which is the page size it maps with. */
    private static final int FILE_BLOCK_SIZE = 4096;
    /**
     * A generated file stores no bytes: the kernel reports zero for every {@code /proc} entry it
     * renders on read, so the length of the declaration is not what a stat reports.
     */
    private static final long FILE_SIZE = 0;
    private static final long FILE_BLOCKS = 0;
    /** The instant a generated file reports; a replay must not depend on when it ran. */
    private static final FileTime GENERATED_AT = FileTime.from(Instant.EPOCH);

    /**
     * One file, directory or link of the process view. A directory carries the facts of a
     * directory; a file's facts come from {@link #facts} and a link's from its target. An absent
     * entry states that the process has nothing at that path.
     */
    public static final class Item {
        public enum Kind { FILE, DIRECTORY, LINK, ABSENT }

        public final Kind kind;
        public final byte[] content;
        public final FileMetadata metadata;
        public final String target;
        public final List<DirectoryEntry> entries;

        private Item(Kind kind, byte[] content, FileMetadata metadata, String target,
                     List<DirectoryEntry> entries) {
            this.kind = kind;
            this.content = content;
            this.metadata = metadata;
            this.target = target;
            this.entries = entries;
        }
    }

    private final Map<String, Item> declared = new LinkedHashMap<String, Item>();
    private List<String> argv;
    private String exe;

    /** The argument vector {@code /proc/self/cmdline} renders; the process name when unset. */
    public void setArgv(List<String> argv) {
        this.argv = Collections.unmodifiableList(new ArrayList<String>(argv));
    }

    /** The executable {@code /proc/self/exe} links to; the first argument when unset. */
    public void setExe(String path) {
        this.exe = path;
    }

    /** Replaces the bytes of one file, which may name this process. */
    public void declare(long pid, String path, byte[] content) {
        declared.put(canonical(pid, path), new Item(Item.Kind.FILE, content, null, null, null));
    }

    /** States that the process has no file at this path, whatever the root directory holds. */
    public void declareAbsent(long pid, String path) {
        declared.put(canonical(pid, path), new Item(Item.Kind.ABSENT, null, null, null, null));
    }

    /** Replaces the target of one symbolic link, which may name this process. */
    public void declareLink(long pid, String path, String target) {
        declared.put(canonical(pid, path), new Item(Item.Kind.LINK, null, null, target, null));
    }

    /** The declaration for a path, or {@code null} when the caller supplied none. */
    public Item declared(long pid, String pathname) {
        return declared.get(canonical(pid, pathname));
    }

    /**
     * The canonical form of a path naming this process: {@code /proc/<pid>} and the main
     * thread's {@code /proc/<pid>/task/<pid>} both name {@code /proc/self}.
     */
    public static String canonical(long pid, String pathname) {
        if (pathname == null || !pathname.startsWith(PROC + "/")) return pathname;
        String rest = pathname.substring(PROC.length() + 1);
        String own = Long.toString(pid);
        for (String process : new String[] {"self", own}) {
            if (rest.equals(process)) return SELF;
            if (!rest.startsWith(process + "/")) continue;
            String tail = rest.substring(process.length() + 1);
            String thread = "task/" + own;
            if (tail.equals(thread)) return SELF;
            if (tail.startsWith(thread + "/")) tail = tail.substring(thread.length() + 1);
            return SELF + "/" + tail;
        }
        return pathname;
    }

    /**
     * The render that describes the running process itself rather than a recorded state: its
     * address space is the one the emulator allocated, so no snapshot of another process
     * instance can answer for it.
     */
    public Item live(Emulator<AndroidFileIO> emulator, String pathname) {
        String path = canonical(emulator.getPid(), pathname);
        return path.equals(SELF + "/" + MAPS) ? rendered(emulator, path) : null;
    }

    /** What the runtime renders for a path, or {@code null} when it renders nothing there. */
    public Item rendered(Emulator<AndroidFileIO> emulator, String pathname) {
        String path = canonical(emulator.getPid(), pathname);
        if (path.equals(PROC) || path.equals(SELF) || path.equals(SELF + "/" + ATTR)) {
            List<DirectoryEntry> entries = processEntries(emulator, path);
            return entries.isEmpty() ? null : directory(entries);
        }
        if (!path.startsWith(SELF + "/")) return null;
        switch (path.substring(SELF.length() + 1)) {
            case STATUS: return file(renderStatus(emulator).getBytes(StandardCharsets.UTF_8));
            case STAT: return file(renderStat(emulator).getBytes(StandardCharsets.UTF_8));
            case COMM: return file((comm(emulator) + "\n").getBytes(StandardCharsets.UTF_8));
            case CMDLINE: return file(renderCmdline(emulator));
            case MOUNTS: return file(MOUNTS_TEXT.getBytes(StandardCharsets.UTF_8));
            case MOUNTINFO: return file(MOUNTINFO_TEXT.getBytes(StandardCharsets.UTF_8));
            case MAPS: return file(MapsFileIO.getMapsData(emulator, emulator.getMemory().getLoadedModules(), null));
            case EXE: return link(executable(emulator));
            default: return null;
        }
    }

    /** The directory facts of a rendered directory. */
    public static FileMetadata directoryFacts() {
        return FileMetadata.builder().mode(IO.S_IFDIR | 0555).build();
    }

    /**
     * Whether the process view answers this path, whoever holds its bytes. A caller may hand the
     * view a file's content or lay it out under the root directory; the facts of the file are the
     * view's in both cases.
     */
    public static boolean serves(String pathname) {
        return pathname != null && (pathname.equals(PROC) || pathname.startsWith(PROC + "/"));
    }

    /**
     * The facts of a generated file: a read-only regular file the process may read under the
     * credentials it runs with. Nothing about it is recorded, so the same path answers the same
     * facts in every run: the device and inode come from the path, and the times are one fixed
     * instant, because the runtime keeps no creation history for what it generates and a replay
     * must not depend on when it ran.
     */
    public static FileMetadata facts(Emulator<AndroidFileIO> emulator, String pathname) {
        GuestCredentials credentials = credentials(emulator);
        // Every name of one file answers the same numbers, so the view resolves the path first.
        String path = canonical(emulator.getPid(), pathname);
        return FileMetadata.builder()
                .mode(FILE_MODE)
                .uid(credentials.realUid.orElse(0))
                .gid(credentials.realGid.orElse(0))
                .nlink(1)
                .blksize(FILE_BLOCK_SIZE)
                .dev(hashed(mountOf(path)))
                .ino(hashed(path))
                .rdev(0)
                .size(FILE_SIZE)
                .blocks(FILE_BLOCKS)
                .atime(GENERATED_AT)
                .mtime(GENERATED_AT)
                .ctime(GENERATED_AT)
                .build();
    }

    /** The generated file system a path belongs to: every file of one tree shares its device. */
    private static String mountOf(String path) {
        int slash = path.indexOf('/', 1);
        return slash < 0 ? path : path.substring(0, slash);
    }

    /** FNV-1a over the UTF-8 bytes, so a path keeps its numbers across runs and processes. */
    private static long hashed(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static Item link(String target) {
        return target == null ? null : new Item(Item.Kind.LINK, null, null, target, null);
    }

    private static Item directory(List<DirectoryEntry> entries) {
        return new Item(Item.Kind.DIRECTORY, null, directoryFacts(), null,
                Collections.unmodifiableList(new ArrayList<DirectoryEntry>(entries)));
    }

    private static Item file(byte[] content) {
        return new Item(Item.Kind.FILE, content, null, null, null);
    }

    private String executable(Emulator<AndroidFileIO> emulator) {
        if (exe != null) return exe;
        if (argv != null && !argv.isEmpty()) return argv.get(0);
        return emulator.getProcessName();
    }

    /** The entries of {@code /proc}, {@code /proc/self} and {@code /proc/self/attr}. */
    private List<DirectoryEntry> processEntries(Emulator<AndroidFileIO> emulator, String directory) {
        Map<String, DirectoryEntry> entries = new LinkedHashMap<String, DirectoryEntry>();
        if (directory.equals(PROC)) {
            entries.put("self", new DirectoryEntry(DirectoryEntry.DirentType.DT_LNK, "self"));
            for (String path : declared.keySet()) {
                String process = processComponent(path);
                if (process != null && !process.equals("self")) {
                    entries.put(process, new DirectoryEntry(DirectoryEntry.DirentType.DT_DIR, process));
                }
            }
            return new ArrayList<DirectoryEntry>(entries.values());
        }
        for (String name : servedNames(emulator)) {
            if (directory.equals(SELF)) {
                int slash = name.indexOf('/');
                String child = slash < 0 ? name : name.substring(0, slash);
                if (!entries.containsKey(child)) {
                    entries.put(child, new DirectoryEntry(slash < 0
                            ? type(served(emulator, SELF + "/" + name)) : DirectoryEntry.DirentType.DT_DIR, child));
                }
            } else if (name.startsWith(ATTR + "/")) {
                String child = name.substring(ATTR.length() + 1);
                if (!entries.containsKey(child)) {
                    entries.put(child, new DirectoryEntry(type(served(emulator, SELF + "/" + name)), child));
                }
            }
        }
        return new ArrayList<DirectoryEntry>(entries.values());
    }

    /** The process component of a declared {@code /proc} path, or {@code null} for another tree. */
    private static String processComponent(String path) {
        if (!path.startsWith(PROC + "/")) return null;
        String rest = path.substring(PROC.length() + 1);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /** Every name this process serves: the renders first, then the declarations. */
    private List<String> servedNames(Emulator<AndroidFileIO> emulator) {
        List<String> names = new ArrayList<String>();
        for (String name : RENDERED) {
            if (rendered(emulator, SELF + "/" + name) != null && !names.contains(name)) names.add(name);
        }
        for (Map.Entry<String, Item> entry : declared.entrySet()) {
            if (entry.getValue().kind == Item.Kind.ABSENT) continue;
            String path = entry.getKey();
            if (!path.startsWith(SELF + "/")) continue;
            String name = path.substring(SELF.length() + 1);
            if (!names.contains(name)) names.add(name);
        }
        return names;
    }

    private Item served(Emulator<AndroidFileIO> emulator, String path) {
        Item item = declared(emulator.getPid(), path);
        return item != null ? item : rendered(emulator, path);
    }


    private static DirectoryEntry.DirentType type(Item item) {
        if (item == null) return DirectoryEntry.DirentType.DT_UNKNOWN;
        if (item.kind == Item.Kind.ABSENT) return DirectoryEntry.DirentType.DT_UNKNOWN;
        switch (item.kind) {
            case DIRECTORY: return DirectoryEntry.DirentType.DT_DIR;
            case LINK: return DirectoryEntry.DirentType.DT_LNK;
            default: return DirectoryEntry.DirentType.DT_REG;
        }
    }

    private static String comm(Emulator<AndroidFileIO> emulator) {
        String name = emulator.getProcessName();
        if (name == null) return "unknown";
        // The kernel keeps 16 bytes of task name, including the terminating NUL.
        return name.length() > 15 ? name.substring(0, 15) : name;
    }

    private byte[] renderCmdline(Emulator<AndroidFileIO> emulator) {
        List<String> arguments = argv;
        if (arguments == null) {
            arguments = emulator.getProcessName() == null
                    ? Collections.<String>emptyList() : Collections.singletonList(emulator.getProcessName());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String argument : arguments) {
            byte[] encoded = argument.getBytes(StandardCharsets.UTF_8);
            bytes.write(encoded, 0, encoded.length);
            bytes.write(0);
        }
        return bytes.toByteArray();
    }

    private static GuestCredentials credentials(Emulator<AndroidFileIO> emulator) {
        return ((AndroidSyscallHandler) emulator.getSyscallHandler()).getGuestCredentials();
    }

    private String renderStatus(Emulator<AndroidFileIO> emulator) {
        int pid = emulator.getPid();
        GuestCredentials credentials = credentials(emulator);
        int uid = credentials.realUid.orElse(0);
        int gid = credentials.realGid.orElse(0);
        int effectiveUid = credentials.effectiveUid.orElse(uid);
        int effectiveGid = credentials.effectiveGid.orElse(gid);
        long mapped = mappedBytes(emulator) / 1024;
        StringBuilder builder = new StringBuilder();
        builder.append("Name:\t").append(comm(emulator)).append('\n');
        builder.append("State:\tS (sleeping)\n");
        builder.append("Tgid:\t").append(pid).append('\n');
        builder.append("Ngid:\t0\n");
        builder.append("Pid:\t").append(pid).append('\n');
        builder.append("PPid:\t0\n");
        builder.append("TracerPid:\t0\n");
        builder.append("Uid:\t").append(uid).append('\t').append(effectiveUid).append('\t')
                .append(effectiveUid).append('\t').append(effectiveUid).append('\n');
        builder.append("Gid:\t").append(gid).append('\t').append(effectiveGid).append('\t')
                .append(effectiveGid).append('\t').append(effectiveGid).append('\n');
        if (credentials.supplementaryGroups.isPresent() && !credentials.supplementaryGroups.get().isEmpty()) {
            builder.append("Groups:");
            for (int group : credentials.supplementaryGroups.get()) builder.append('\t').append(group);
            builder.append('\n');
        }
        builder.append("VmSize:\t").append(mapped).append(" kB\n");
        builder.append("VmRSS:\t").append(mapped).append(" kB\n");
        builder.append("VmStk:\t").append(stackBytes(emulator) / 1024).append(" kB\n");
        // zygote installs the process's seccomp filter before the app runs, as prctl reports it.
        builder.append("Seccomp:\t2\n");
        return builder.toString();
    }

    /**
     * The kernel's stat line. The parent, the CPU times and the start time are facts only the
     * device has, so a recorded line replaces this one whenever an observer supplied it.
     */
    private String renderStat(Emulator<AndroidFileIO> emulator) {
        long mapped = mappedBytes(emulator);
        long[] fields = new long[52];
        fields[2] = 'S';                        // state
        fields[17] = 20;                        // priority
        fields[19] = 1;                         // num_threads
        fields[22] = mapped;                    // vsize
        fields[23] = mapped / emulator.getPageAlign();  // rss
        fields[24] = -1;                        // rsslim, RLIM_INFINITY
        fields[27] = Memory.STACK_BASE;         // startstack
        fields[37] = 17;                        // exit_signal, SIGCHLD
        StringBuilder builder = new StringBuilder();
        builder.append(emulator.getPid()).append(" (").append(comm(emulator)).append(") ");
        for (int index = 2; index < fields.length; index++) {
            if (index > 2) builder.append(' ');
            if (index == 2) builder.append((char) fields[index]);
            else builder.append(Long.toUnsignedString(fields[index]));
        }
        return builder.toString();
    }

    private static long mappedBytes(Emulator<AndroidFileIO> emulator) {
        long total = 0;
        for (MemoryMap map : emulator.getMemory().getMemoryMap()) total += map.size;
        return total;
    }

    private static long stackBytes(Emulator<AndroidFileIO> emulator) {
        return (long) Memory.STACK_SIZE_OF_PAGE * emulator.getPageAlign();
    }
}
