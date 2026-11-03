package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.linux.LinuxFileSystem;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.file.ProcfsFiles;
import com.github.unidbg.linux.thread.PThreadInternal;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.IO;
import org.junit.Test;
import unicorn.Arm64Const;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The process view: what the runtime renders, how {@code /proc/<pid>} names {@code /proc/self},
 * and what a recorded declaration replaces.
 */
public class ProcfsFilesIntegrationTest {
    private static final int PID = 4242;
    private static final String NAME = "com.example.process";
    /** The kernel keeps at most 15 characters of task name. */
    private static final String COMM = "com.example.pro";
    private static final byte[] RECORDED_STATUS =
            "Name:\tcom.example.process\nPid:\t4242\nPPid:\t17\nTracerPid:\t0\n".getBytes(StandardCharsets.UTF_8);
    /** A recording names the process it came from, which is not the process this runtime is. */
    private static final byte[] FOREIGN_STATUS = ("Name:\tcom.example.process\nState:\tS (sleeping)\n"
            + "Tgid:\t999\nPid:\t999\nPPid:\t17\nTracerPid:\t0\nSeccomp:\t2\n")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] FOREIGN_STAT =
            "999 (com.example.process) S 17 999 0 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 0 0 0\n"
                    .getBytes(StandardCharsets.UTF_8);
    /** A recorded mount table: the device numbers of the mounts it lists carry the facts. */
    private static final byte[] RECORDED_MOUNTINFO = ("1 0 0:1 / / rw - rootfs rootfs rw\n"
            + "9293 1 0:4 / /proc rw - proc proc rw\n"
            + "9294 1 0:19 / /sys rw - sysfs sysfs rw\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] PARENT_STAT = "1 (init) S 0 0 0 0 0 0 0 0 0 0 0\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECORDED_ATTR = "u:r:untrusted_app:s0:c0,c0\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LAYOUT_STAT =
            "4242 (recorded) S 1 4242 0 0 0 0 0 0 0 0 0\n".getBytes(StandardCharsets.UTF_8);
    /** The page of svc instructions a svc return address points into. */
    private static final long SVC_TRAP = 0x7ffff0000L;

    @Test
    public void processViewRendersAliasesAndTakesRecordedDeclarations() throws Exception {
        Path parent = Paths.get("target/procfs");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "procfs-").toAbsolutePath();
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setRootDir(root.toFile()).setProcessName(NAME)
                .addBackendFactory(new Unicorn2Factory(true)).build();
             RawSyscalls calls = new RawSyscalls(emulator)) {
            emulator.setPid(PID);
            AndroidSyscallHandler handler = (AndroidSyscallHandler) emulator.getSyscallHandler();
            handler.setGuestCredentials(new GuestCredentials(OptionalInt.of(10123), OptionalInt.of(10123),
                    OptionalInt.of(10123), OptionalInt.of(10123), java.util.Optional.of(Set.of(1005))));
            LinuxFileSystem files = (LinuxFileSystem) emulator.getFileSystem();
            ProcfsFiles procfs = files.procfs();
            procfs.setArgv(Arrays.asList("/system/bin/app_process64", NAME));

            MemoryBlock scratch = emulator.getMemory().malloc(8192, true);
            try {
                UnidbgPointer buffer = scratch.getPointer();
                verifyRendered(emulator, files, calls, buffer);
                verifyAlias(emulator, calls, buffer);
                verifyLayoutPrecedence(emulator, root, calls, buffer);
                verifyRecordedDeclarations(emulator, calls, buffer);
                verifyAncestry(calls, buffer);
            } finally {
                scratch.free();
            }
        }
    }

    private static void verifyRendered(AndroidEmulator emulator, LinuxFileSystem files, RawSyscalls calls,
                                       UnidbgPointer buffer) {
        String status = text(read(calls, buffer, "/proc/self/status"));
        assertTrue(status, status.contains("Name:\t" + COMM + "\n"));
        assertTrue(status, status.contains("Tgid:\t" + PID + "\n"));
        assertTrue(status, status.contains("Pid:\t" + PID + "\n"));
        assertTrue(status, status.contains("Uid:\t10123\t10123\t10123\t10123\n"));
        assertTrue(status, status.contains("Gid:\t10123\t10123\t10123\t10123\n"));
        assertTrue(status, status.contains("Groups:\t1005\n"));
        assertTrue(status, status.contains("Seccomp:\t2\n"));

        String stat = text(read(calls, buffer, "/proc/self/stat"));
        assertTrue(stat, stat.startsWith(PID + " (" + COMM + ") S 0 "));

        assertEquals(COMM + "\n", text(read(calls, buffer, "/proc/self/comm")));
        assertArrayEquals(("/system/bin/app_process64\u0000" + NAME + "\u0000").getBytes(StandardCharsets.UTF_8),
                read(calls, buffer, "/proc/self/cmdline"));
        String mounts = text(read(calls, buffer, "/proc/self/mounts"));
        assertTrue(mounts, mounts.startsWith("rootfs / rootfs rw,relatime 0 0\n"
                + "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0\n"
                + "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0\n"));
        String mountinfo = text(read(calls, buffer, "/proc/self/mountinfo"));
        assertTrue(mountinfo, mountinfo.contains(" - proc proc rw\n"));
        String maps = text(read(calls, buffer, "/proc/self/maps"));
        assertTrue(maps, maps.contains("[stack]"));
        // The pages the runtime maps for its own svc code are part of the address space the guest
        // reads, with the extent and the permissions they were mapped with.
        assertSingleMapping(maps, emulator.getSvcMemory().getBase(),
                emulator.getSvcMemory().getBase() + emulator.getSvcMemory().getSize(), "r-xp");
        assertSingleMapping(maps, SVC_TRAP, SVC_TRAP + emulator.getPageAlign(), "r-xp");
        assertEquals("/system/bin/app_process64", calls.readlink("/proc/self/exe", buffer));

        assertGeneratedFacts(files, "/proc/self/status");
        assertGeneratedFacts(files, "/proc/self/maps");

        List<Entry> entries = directories(calls, buffer, "/proc/" + PID);
        assertEquals(Arrays.asList(".", "..", "cmdline", "comm", "exe", "maps", "mountinfo", "mounts", "stat",
                "status"), sorted(entries));
        assertEquals(DirectoryEntry.DirentType.DT_LNK, type(entries, "exe"));
        assertEquals(DirectoryEntry.DirentType.DT_REG, type(entries, "status"));
        assertEquals(Arrays.asList(".", "..", "self", "uptime", "version"),
                sorted(directories(calls, buffer, "/proc")));
    }

    /** The address space holds one mapping for a runtime page, carrying the mapped permissions. */
    private static void assertSingleMapping(String maps, long start, long end, String perms) {
        int found = 0;
        for (String line : maps.split("\n")) {
            String[] fields = line.split(" +");
            if (fields.length < 2) continue;
            String[] range = fields[0].split("-");
            if (range.length != 2 || Long.parseUnsignedLong(range[0], 16) != start) continue;
            found++;
            assertEquals(end, Long.parseUnsignedLong(range[1], 16));
            assertEquals(perms, fields[1]);
        }
        assertEquals("mapping at 0x" + Long.toHexString(start) + " in\n" + maps, 1, found);
    }

    private static void verifyAlias(AndroidEmulator emulator, RawSyscalls calls, UnidbgPointer buffer) {
        for (String name : new String[] {"status", "stat", "comm", "cmdline", "mounts", "mountinfo", "maps"}) {
            assertArrayEquals(name, read(calls, buffer, "/proc/self/" + name),
                    read(calls, buffer, "/proc/" + PID + "/" + name));
        }
        assertArrayEquals(read(calls, buffer, "/proc/self/stat"),
                read(calls, buffer, "/proc/" + PID + "/task/" + PID + "/stat"));
        assertEquals("/system/bin/app_process64", calls.readlink("/proc/" + PID + "/exe", buffer));
        assertEquals(emulator.getFileSystem().queryMetadata("/proc/self/status", true).value.mode,
                emulator.getFileSystem().queryMetadata("/proc/" + PID + "/status", true).value.mode);
    }

    private static void verifyLayoutPrecedence(AndroidEmulator emulator, Path root, RawSyscalls calls,
                                               UnidbgPointer buffer) throws Exception {
        Files.createDirectories(root.resolve("proc/self"));
        Files.write(root.resolve("proc/self/stat"), LAYOUT_STAT);
        assertArrayEquals(LAYOUT_STAT, read(calls, buffer, "/proc/self/stat"));
        assertArrayEquals(LAYOUT_STAT, read(calls, buffer, "/proc/" + PID + "/stat"));
        // The address space is the running one, so a copy of another instance never answers it.
        Files.write(root.resolve("proc/self/maps"), "recorded maps\n".getBytes(StandardCharsets.UTF_8));
        String maps = text(read(calls, buffer, "/proc/self/maps"));
        assertTrue(maps, maps.contains("[stack]"));
        // A recorded link answers even when the layout does not hold its target.
        Files.createSymbolicLink(root.resolve("proc/self/exe"), Paths.get("/system/bin/logd"));
        assertEquals("/system/bin/logd", calls.readlink("/proc/self/exe", buffer));
        assertEquals("/system/bin/logd", calls.readlink("/proc/" + PID + "/exe", buffer));
        // The file system holding the bytes does not describe them: the view answers the facts.
        LinuxFileSystem files = (LinuxFileSystem) emulator.getFileSystem();
        assertGeneratedFacts(files, "/proc/self/stat");
        assertGeneratedFacts(files, "/proc/self/status");
    }

    private static void verifyRecordedDeclarations(AndroidEmulator emulator, RawSyscalls calls, UnidbgPointer buffer) {
        LinuxFileSystem files = (LinuxFileSystem) emulator.getFileSystem();
        files.procfs().declare(PID, "/proc/self/status", RECORDED_STATUS);
        assertArrayEquals(RECORDED_STATUS, read(calls, buffer, "/proc/self/status"));
        assertArrayEquals(RECORDED_STATUS, read(calls, buffer, "/proc/" + PID + "/status"));
        assertGeneratedFacts(files, "/proc/self/status");

        files.procfs().declare(PID, "/proc/self/attr/current", RECORDED_ATTR);
        assertArrayEquals(RECORDED_ATTR, read(calls, buffer, "/proc/self/attr/current"));
        assertEquals(Arrays.asList(".", "..", "current"), sorted(directories(calls, buffer, "/proc/self/attr")));

        // One file system is one device: a path takes the device of the mount that holds it.
        files.procfs().declare(PID, "/proc/self/mountinfo", RECORDED_MOUNTINFO);
        assertEquals(4L, files.queryMetadata("/proc/self/status", true).value.dev.getAsLong());
        files.procfs().declare(PID, "/sys/fs/selinux/enforce", RECORDED_ATTR);
        assertEquals(19L, files.queryMetadata("/sys/fs/selinux/enforce", true).value.dev.getAsLong());
        assertSysfsFacts(files, "/sys/fs/selinux/enforce", emulator.getPageAlign());

        // A path a caller states absent has nothing there, not even the render.
        files.procfs().declareAbsent(PID, "/proc/self/comm");
        assertEquals(-2, calls.open("/proc/self/comm", 0));
        assertEquals(FileQueryResult.Status.ERROR, files.queryMetadata("/proc/self/comm", true).status());
    }

    /**
     * The facts of a generated file: a read-only regular file with a usable block size, no stored
     * bytes, the owner the process runs as, and numbers that depend on the path alone.
     */
    private static void assertGeneratedFacts(LinuxFileSystem files, String path) {
        FileMetadata facts = files.queryMetadata(path, true).value;
        assertEquals(path, IO.S_IFREG, facts.mode.getAsInt() & 0170000);
        assertEquals(path, 0444, facts.mode.getAsInt() & 0777);
        // procfs reports the block size of a file system that stores nothing.
        assertEquals(path, 1024, facts.blksize.getAsInt());
        assertEquals(path, 0L, facts.size.getAsLong());
        assertEquals(path, 0L, facts.blocks.getAsLong());
        assertEquals(path, 1, facts.nlink.getAsInt());
        assertEquals(path, 10123, facts.uid.getAsInt());
        assertEquals(path, 10123, facts.gid.getAsInt());
        // A file the kernel creates is never stamped with the epoch, and a replay answers one
        // fixed instant, so two runs of the same path report the same time.
        assertEquals(path, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")), facts.mtime.get());
        FileMetadata sibling = files.queryMetadata("/proc/self/cmdline", true).value;
        assertEquals(path, facts.dev, sibling.dev);
        assertTrue(path, facts.ino.getAsLong() != sibling.ino.getAsLong());
        assertEquals(path, files.queryMetadata("/proc/" + PID + path.substring("/proc/self".length()), true).value.ino,
                facts.ino);
    }

    /**
     * The facts of a generated sysfs file: sysfs reports a page as the size and the block size of
     * the file it renders on read, as the recorded device does, and owns it.
     */
    private static void assertSysfsFacts(LinuxFileSystem files, String path, int pageSize) {
        FileMetadata facts = files.queryMetadata(path, true).value;
        assertEquals(path, IO.S_IFREG, facts.mode.getAsInt() & 0170000);
        assertEquals(path, 0444, facts.mode.getAsInt() & 0777);
        assertEquals(path, pageSize, facts.blksize.getAsInt());
        assertEquals(path, pageSize, facts.size.getAsLong());
        assertEquals(path, 0L, facts.blocks.getAsLong());
        assertEquals(path, 0, facts.uid.getAsInt());
        assertEquals(path, 0, facts.gid.getAsInt());
    }

    /**
     * One pid source: the id the runtime runs as answers getpid, the thread id the guest reads,
     * and the identity fields of the files that report them, whatever the recording named.
     */
    @Test
    public void processIdentityComesFromOnePid() throws Exception {
        Path parent = Paths.get("target/procfs");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "identity-").toAbsolutePath();
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setRootDir(root.toFile()).setProcessName(NAME)
                .addBackendFactory(new Unicorn2Factory(true)).build();
             RawSyscalls calls = new RawSyscalls(emulator)) {
            // The host gives the id before anything runs; the recording names another process.
            emulator.setPid(PID);
            LinuxFileSystem files = (LinuxFileSystem) emulator.getFileSystem();
            files.procfs().declare(PID, "/proc/self/status", FOREIGN_STATUS);
            files.procfs().declare(PID, "/proc/self/stat", FOREIGN_STAT);

            MemoryBlock scratch = emulator.getMemory().malloc(4096, true);
            try {
                UnidbgPointer buffer = scratch.getPointer();
                assertEquals(PID, calls.call(172));
                assertEquals(PID, calls.call(178));
                assertEquals(PID, threadId(emulator));
                String status = text(read(calls, buffer, "/proc/self/status"));
                assertTrue(status, status.contains("Pid:\t" + PID + "\n"));
                assertTrue(status, status.contains("Tgid:\t" + PID + "\n"));
                // The parent is a device fact the runtime cannot derive, so it stays recorded.
                assertTrue(status, status.contains("PPid:\t17\n"));
                assertTrue(status, status.contains("Seccomp:\t2\n"));
                String stat = text(read(calls, buffer, "/proc/self/stat"));
                assertTrue(stat, stat.startsWith(PID + " (com.example.process) S 17 "));
                assertTrue(stat, stat.startsWith(PID + " "));
            } finally {
                scratch.free();
            }
        }
    }

    /** The thread id bionic keeps in the thread structure the guest's thread-local storage names. */
    private static int threadId(AndroidEmulator emulator) {
        UnidbgPointer tls = UnidbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_TPIDR_EL0);
        UnidbgPointer thread = tls.getPointer(emulator.getPointerSize());
        PThreadInternal internal = PThreadInternal.create(emulator, thread);
        internal.unpack();
        return internal.tid;
    }

    private static void verifyAncestry(RawSyscalls calls, UnidbgPointer buffer) {
        calls.declare("/proc/1/stat", PARENT_STAT);
        assertArrayEquals(PARENT_STAT, read(calls, buffer, "/proc/1/stat"));
        assertEquals(-2, calls.open("/proc/1/status", 0));
        assertEquals(-2, calls.open("/proc/2/stat", 0));
    }

    private static byte[] read(RawSyscalls calls, UnidbgPointer buffer, String path) {
        int fd = calls.open(path, 0);
        assertTrue(path, fd >= 0);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                int count = calls.call(63, fd, buffer.peer, 512);
                assertTrue(path + ": " + count, count >= 0);
                if (count == 0) return bytes.toByteArray();
                bytes.write(buffer.getByteArray(0, count), 0, count);
            }
        } finally {
            assertEquals(0, calls.call(57, fd));
        }
    }

    private static List<Entry> directories(RawSyscalls calls, UnidbgPointer buffer, String path) {
        int fd = calls.open(path, 0x10000);
        assertTrue(path, fd >= 0);
        try {
            List<Entry> entries = new ArrayList<Entry>();
            while (true) {
                int count = calls.call(61, fd, buffer.peer, 2048);
                assertTrue(path + ": " + count, count >= 0);
                if (count == 0) return entries;
                int offset = 0;
                while (offset < count) {
                    int record = buffer.getShort(offset + 16) & 0xffff;
                    entries.add(new Entry((byte) (buffer.getByte(offset + 18) & 0xff),
                            buffer.getString(offset + 19, "UTF-8")));
                    offset += record;
                }
            }
        } finally {
            assertEquals(0, calls.call(57, fd));
        }
    }

    /** The kernel does not order a directory, so the entries are compared as a sorted list. */
    private static List<String> sorted(List<Entry> entries) {
        List<String> names = new ArrayList<String>();
        for (Entry entry : entries) names.add(entry.name);
        Collections.sort(names);
        return names;
    }

    private static DirectoryEntry.DirentType type(List<Entry> entries, String name) {
        for (Entry entry : entries) {
            if (!entry.name.equals(name)) continue;
            for (DirectoryEntry.DirentType value : DirectoryEntry.DirentType.values()) {
                if (value.value == entry.type) return value;
            }
        }
        throw new AssertionError("no entry named " + name);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class Entry {
        private final byte type;
        private final String name;

        private Entry(byte type, String name) {
            this.type = type;
            this.name = name;
        }
    }

    /** arm64 syscalls issued from emulated code, so the file system resolution layer answers. */
    private static final class RawSyscalls implements AutoCloseable {
        private final AndroidEmulator emulator;
        private final UnidbgPointer code;
        private final UnidbgPointer path;

        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(8192, 7);
            path = emulator.getSvcMemory().allocate(256, "procfs-test-path");
            for (int number : new int[] {56, 57, 61, 63, 78, 172, 178}) {
                long offset = number * 16L;
                code.setInt(offset, 0xd2800008 | (number << 5));
                code.setInt(offset + 4, 0xd4000001);
                code.setInt(offset + 8, 0xd65f03c0);
            }
        }

        int open(String name, int flags) {
            path.setString(0, name);
            return call(56, -100, path.peer, flags, 0);
        }

        String readlink(String name, UnidbgPointer buffer) {
            path.setString(0, name);
            int count = call(78, -100, path.peer, buffer.peer, 255);
            assertTrue(name + ": " + count, count > 0);
            return buffer.getString(0, "UTF-8");
        }

        void declare(String name, byte[] content) {
            LinuxFileSystem files = (LinuxFileSystem) emulator.getFileSystem();
            files.procfs().declare(emulator.getPid(), name, content);
        }

        int call(int number, Number... arguments) {
            return emulator.eFunc(code.peer + number * 16L, arguments).intValue();
        }

        @Override
        public void close() {
            emulator.getMemory().munmap(code.peer, 8192);
        }
    }
}
