package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.AndroidSyscallHandler;
import com.github.unidbg.linux.GuestCredentials;
import com.github.unidbg.linux.kernel.ProcessCapabilities;
import com.github.unidbg.linux.kernel.ProcessMemory;
import com.github.unidbg.memory.Memory;
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
    private static final String SYS = "/sys";
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
    /**
     * The block size procfs reports. A generated file stores no bytes, so the kernel reports the
     * block size of the file system that renders it: procfs reports 1024 and sysfs a page.
     */
    private static final int PROC_BLOCK_SIZE = 1024;
    /**
     * A generated file stores no bytes: the kernel reports zero for every {@code /proc} entry it
     * renders on read, so the length of the declaration is not what a stat reports.
     */
    private static final long FILE_SIZE = 0;
    private static final long FILE_BLOCKS = 0;
    /**
     * The instant a generated file reports. The kernel stamps a file when it creates it, and the
     * runtime keeps no creation history, so every generated file reports one fixed instant: the
     * same path answers the same time in every run, and the epoch, which no kernel stamps, is not
     * that instant.
     */
    private static final FileTime GENERATED_AT = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

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

    /**
     * Replaces the bytes of one file, which may name this process. The process's own identity
     * fields are rendered from its pid, because the pid is what the runtime runs as and not what
     * an observer happened to record.
     */
    public void declare(long pid, String path, byte[] content) {
        declared.put(canonical(pid, path), new Item(Item.Kind.FILE, identity(pid, path, content), null, null, null));
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
     * Whether the kernel generates the file at this path on read. {@code /proc} and {@code /sys}
     * hold no stored bytes: the kernel renders a file when a process reads it, so the facts of one
     * come from its path and from the file system that renders it, whatever the root directory
     * holds for it.
     */
    public static boolean generated(String pathname) {
        if (pathname == null) return false;
        return pathname.equals(PROC) || pathname.startsWith(PROC + "/")
                || pathname.equals(SYS) || pathname.startsWith(SYS + "/");
    }

    /**
     * The facts of a generated file: a read-only regular file of the file system that renders it.
     * Nothing about it is recorded, so the same path answers the same facts in every run: procfs
     * files belong to the process that reads them and report the block size procfs reports, sysfs
     * files belong to the kernel, the device is the one the mount table gives the mount that holds
     * the path, the inode comes from the path, and the times are one fixed instant, because the
     * runtime keeps no creation history for what it generates and a replay must not depend on
     * when it ran.
     */
    public FileMetadata facts(Emulator<AndroidFileIO> emulator, String pathname) {
        GuestCredentials credentials = credentials(emulator);
        // Every name of one file answers the same numbers, so the view resolves the path first.
        String path = canonical(emulator.getPid(), pathname);
        boolean sysfs = isSysfs(path);
        return FileMetadata.builder()
                .mode(FILE_MODE)
                .uid(sysfs ? 0 : credentials.realUid.orElse(0))
                .gid(sysfs ? 0 : credentials.realGid.orElse(0))
                .nlink(1)
                .blksize(sysfs ? emulator.getPageAlign() : PROC_BLOCK_SIZE)
                .dev(device(path))
                .ino(hashed(path))
                .rdev(0)
                .size(FILE_SIZE)
                .blocks(FILE_BLOCKS)
                .atime(GENERATED_AT)
                .mtime(GENERATED_AT)
                .ctime(GENERATED_AT)
                .build();
    }

    private static boolean isSysfs(String path) {
        return path.equals(SYS) || path.startsWith(SYS + "/");
    }

    /**
     * The device of a path: one file system is one device, so a path belongs to the mount with
     * the longest mount point that holds it. A path no mount holds reports no device.
     */
    private long device(String path) {
        long device = 0;
        int length = -1;
        for (String line : mountinfo().split("\n")) {
            String[] fields = line.split(" ");
            if (fields.length < 5) continue;
            String point = fields[4];
            boolean holds = point.equals("/")
                    ? path.startsWith("/") : path.equals(point) || path.startsWith(point + "/");
            if (!holds || point.length() < length) continue;
            length = point.length();
            device = deviceNumber(fields[2]);
        }
        return device;
    }

    /** The mount table the guest reads: a caller's declaration when there is one. */
    private String mountinfo() {
        Item item = declared.get(SELF + "/" + MOUNTINFO);
        return item != null && item.kind == Item.Kind.FILE
                ? new String(item.content, StandardCharsets.ISO_8859_1) : MOUNTINFO_TEXT;
    }

    /** The device number of a mountinfo {@code major:minor}, packed as the kernel reports it. */
    private static long deviceNumber(String majorMinor) {
        int colon = majorMinor.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("mountinfo device=" + majorMinor);
        long major = Long.parseLong(majorMinor.substring(0, colon));
        long minor = Long.parseLong(majorMinor.substring(colon + 1));
        return (minor & 0xff) | ((major & 0xfff) << 8)
                | ((minor & ~0xffL) << 12) | ((major & ~0xfffL) << 32);
    }

    /** FNV-1a over the UTF-8 bytes, so a path keeps its inode across runs and processes. */
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

    /**
     * The recorded bytes of one file with this process's identity in the fields that hold it:
     * {@code status} names the pid and thread group id, {@code stat} names the pid in its first
     * field. Everything else, the parent included, stays what the observer recorded, because the
     * runtime has no parent and no kernel counters of its own.
     */
    private static byte[] identity(long pid, String path, byte[] content) {
        String canonical = canonical(pid, path);
        if (canonical.equals(SELF + "/" + STATUS)) return rewritten(content, line -> identityLine(line, pid));
        if (canonical.equals(SELF + "/" + STAT)) return rewritten(content, line -> identityStat(line, pid));
        return content;
    }

    private static byte[] rewritten(byte[] content, java.util.function.UnaryOperator<String> rewrite) {
        // The bytes are decoded one to one: only ASCII digits are replaced, everything else is
        // written back as it was recorded.
        String text = new String(content, StandardCharsets.ISO_8859_1);
        StringBuilder result = new StringBuilder(text.length());
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            end = end < 0 ? text.length() : end + 1;
            result.append(rewrite.apply(text.substring(start, end)));
            start = end;
        }
        return result.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** One {@code status} line, with the value of an identity field replaced. */
    private static String identityLine(String line, long pid) {
        for (String field : new String[] {"Pid:", "Tgid:"}) {
            if (!line.startsWith(field)) continue;
            int value = field.length();
            while (value < line.length() && (line.charAt(value) == ' ' || line.charAt(value) == '\t')) value++;
            int end = value;
            while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
            if (end == value) return line;
            return line.substring(0, value) + pid + line.substring(end);
        }
        return line;
    }

    /** The {@code stat} line, whose first field is the pid and whose second is the name. */
    private static String identityStat(String line, long pid) {
        int name = line.indexOf('(');
        int end = name < 0 ? line.indexOf(' ') : line.lastIndexOf(' ', name);
        if (end < 1) return line;
        return pid + line.substring(end);
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
        long mapped = ProcessMemory.mappedBytes(emulator) / 1024;
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
        ProcessCapabilities capabilities = ProcessCapabilities.of(credentials);
        builder.append("CapInh:\t").append(capability(capabilities.inheritable)).append('\n');
        builder.append("CapPrm:\t").append(capability(capabilities.permitted)).append('\n');
        builder.append("CapEff:\t").append(capability(capabilities.effective)).append('\n');
        builder.append("CapBnd:\t").append(capability(capabilities.bounding)).append('\n');
        builder.append("CapAmb:\t").append(capability(capabilities.ambient)).append('\n');
        builder.append("VmSize:\t").append(mapped).append(" kB\n");
        builder.append("VmRSS:\t").append(mapped).append(" kB\n");
        builder.append("VmStk:\t").append(ProcessMemory.stackBytes(emulator) / 1024).append(" kB\n");
        // zygote installs the process's seccomp filter before the app runs, as prctl reports it.
        builder.append("Seccomp:\t2\n");
        return builder.toString();
    }

    /**
     * The kernel's stat line. The parent, the CPU times and the start time are facts only the
     * device has, so a recorded line replaces this one whenever an observer supplied it.
     */
    private String renderStat(Emulator<AndroidFileIO> emulator) {
        long mapped = ProcessMemory.mappedBytes(emulator);
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

    /** One capability set of {@code status}, which the kernel renders as 16 hex digits. */
    private static String capability(long set) {
        return String.format("%016x", set);
    }
}
