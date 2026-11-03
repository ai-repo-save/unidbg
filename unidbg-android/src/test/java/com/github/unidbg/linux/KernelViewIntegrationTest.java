package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.linux.LinuxFileSystem;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.kernel.AnonymousName;
import com.github.unidbg.linux.kernel.ProcessMemory;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.kernel.KernelIdentity;
import com.github.unidbg.unix.time.ClockSource;
import org.junit.Test;
import unicorn.UnicornConst;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The kernel view a native process reads, answered from the facts the runtime holds: the identity
 * of the machine it runs on, the capability words of its auxiliary vector, the entries of its
 * process directory, the mount table, the kernel files and the name of a range of its address
 * space.
 */
public class KernelViewIntegrationTest {
    private static final int PID = 4242;
    private static final String NAME = "com.example.process";
    /** The kernel build of the recorded device. */
    private static final KernelIdentity IDENTITY = new KernelIdentity("localhost", "4.9.227-perf+",
            "#3 SMP PREEMPT Fri Oct 30 10:38:55 CST 2026", "aarch64", "localdomain", 2047L, null);
    /** A recorded kernel command line: one run of tokens, with no line structure of its own. */
    private static final byte[] RECORDED_KERNEL_CMDLINE =
            "console=ttyMSM0,115200n8\0androidboot.serialno=06789546".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECORDED_MODULES =
            "wlan 7409664 0 - Live 0x0000000000000000 (OE)\nwcd_core 126976 0 - Live 0x0 (E)\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECORDED_INTERRUPTS =
            "           CPU0       CPU1\n  1:          0          0       GICv3   9 Level     arch_timer\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final String VMA_NAME = "runtime-eval-vma";

    private static final int SYS_OPENAT = 56;
    private static final int SYS_CLOSE = 57;
    private static final int SYS_GETDENTS64 = 61;
    private static final int SYS_READ = 63;
    private static final int SYS_CLOCK_GETTIME = 113;
    private static final int SYS_UNAME = 160;
    private static final int SYS_PRCTL = 167;

    @Test
    public void kernelViewAnswersFromTheRuntimeFacts() throws Exception {
        Path parent = Paths.get("target/kernel-view");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "kernel-view-").toAbsolutePath();
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setRootDir(root.toFile()).setProcessName(NAME)
                .addBackendFactory(new Unicorn2Factory(true)).build();
             RawSyscalls calls = new RawSyscalls(emulator)) {
            emulator.setPid(PID);
            emulator.setKernelIdentity(IDENTITY);
            // The machine's uptime is the boot clock; the process reads it without taking the
            // reading its own clock_gettime(CLOCK_MONOTONIC) answers with.
            ClockSource.install(emulator, ClockSource.of(
                    new long[] {1_700_000_000_000_000_000L}, new long[] {2_500_000_000L},
                    500_000_000L, 0L));

            MemoryBlock scratch = emulator.getMemory().malloc(8192, true);
            try {
                UnidbgPointer buffer = scratch.getPointer();
                verifyIdentity(emulator, calls, buffer);
                verifyAuxiliaryVector(emulator);
                verifyDirectoryUnion(root, emulator, calls, buffer);
                verifyKernelFiles(root, emulator, calls, buffer);
                verifyAddressSpaceNames(emulator, calls, buffer);
            } finally {
                scratch.free();
            }
        }
    }

    /** The six fields of uname(2) and the kernel files that report the same build. */
    private static void verifyIdentity(AndroidEmulator emulator, RawSyscalls calls, UnidbgPointer buffer) {
        assertEquals(0, calls.call(SYS_UNAME, buffer.peer));
        String[] fields = new String[6];
        for (int index = 0; index < fields.length; index++) {
            fields[index] = buffer.getString(index * 65L, "UTF-8");
        }
        assertArrayEquals(new String[] {"Linux", "localhost", "4.9.227-perf+",
                "#3 SMP PREEMPT Fri Oct 30 10:38:55 CST 2026", "aarch64", "localdomain"}, fields);

        assertEquals("Linux version 4.9.227-perf+ #3 SMP PREEMPT Fri Oct 30 10:38:55 CST 2026\n",
                text(read(calls, buffer, "/proc/version")));
        // Two readings of the uptime report the same boot clock, which keeps its recorded reading.
        String uptime = text(read(calls, buffer, "/proc/uptime"));
        assertEquals("3.00 0.00\n", uptime);
        assertEquals(uptime, text(read(calls, buffer, "/proc/uptime")));
        UnidbgPointer timespec = UnidbgPointer.pointer(emulator, buffer.peer + 2048);
        assertEquals(0, calls.call(SYS_CLOCK_GETTIME, ClockSource.CLOCK_MONOTONIC, timespec.peer));
        assertEquals(2L, timespec.getLong(0));
        assertEquals(500_000_000L, timespec.getLong(8));
    }

    /**
     * The auxiliary vector the runtime builds publishes the capability words the machine states,
     * and a word the kernel did not publish has no entry: the libc's own getauxval reports ENOENT.
     */
    private static void verifyAuxiliaryVector(AndroidEmulator emulator) {
        emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
        Module libc = emulator.loadLibrary(new File("src/main/resources/android/sdk23/lib64/libc.so"));
        assertEquals(4096L, libc.callFunction(emulator, "getauxval", 6L).longValue());
        // The query reports ENOENT only for a word the vector holds no entry for, so the errno
        // the caller reads is the one a cleared errno leaves behind.
        emulator.getMemory().setErrno(0);
        assertEquals(2047L, libc.callFunction(emulator, "getauxval", 16L).longValue());
        assertEquals(0, errno(emulator, libc));
        emulator.getMemory().setErrno(0);
        assertEquals(0L, libc.callFunction(emulator, "getauxval", 26L).longValue());
        assertEquals(2, errno(emulator, libc));
    }

    private static int errno(AndroidEmulator emulator, Module libc) {
        long pointer = libc.callFunction(emulator, "__errno").intValue() & 0xffffffffL;
        return UnidbgPointer.pointer(emulator, pointer).getInt(0);
    }

    /**
     * {@code /proc/self} lists what the view renders, what a caller declares and what the root
     * directory holds, and each entry carries the kind the kernel gives it.
     */
    private static void verifyDirectoryUnion(Path root, AndroidEmulator emulator, RawSyscalls calls,
                                             UnidbgPointer buffer) throws Exception {
        Files.createDirectories(root.resolve("proc/self/cwd"));
        Files.write(root.resolve("proc/self/auxv"), new byte[0]);
        Files.write(root.resolve("proc/self/wchan"), "0\n".getBytes(StandardCharsets.UTF_8));
        LinuxFileSystem files = (LinuxFileSystem) emulator.getFileSystem();
        files.procfs().declare(PID, "/proc/self/attr/current", "u:r:untrusted_app:s0\n".getBytes(StandardCharsets.UTF_8));

        List<Entry> entries = directories(calls, buffer, "/proc/self");
        assertEquals(java.util.Arrays.asList(".", "..", "attr", "auxv", "cmdline", "comm", "cwd", "exe",
                "maps", "mountinfo", "mounts", "stat", "status", "wchan"), sorted(entries));
        assertEquals(DirectoryEntry.DirentType.DT_DIR, type(entries, "attr"));
        assertEquals(DirectoryEntry.DirentType.DT_DIR, type(entries, "cwd"));
        assertEquals(DirectoryEntry.DirentType.DT_REG, type(entries, "auxv"));
        assertEquals(DirectoryEntry.DirentType.DT_REG, type(entries, "comm"));
        assertEquals(DirectoryEntry.DirentType.DT_LNK, type(entries, "exe"));
    }

    /**
     * The mount table of the file systems that answer the process, the recorded kernel files as
     * they are stored, and a refused path answering the errno the recording states.
     */
    private static void verifyKernelFiles(Path root, AndroidEmulator emulator, RawSyscalls calls,
                                          UnidbgPointer buffer) throws Exception {
        String mounts = text(read(calls, buffer, "/proc/self/mounts"));
        assertEquals("rootfs / rootfs rw,relatime 0 0\n"
                + "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0\n"
                + "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0\n", mounts);
        String mountinfo = text(read(calls, buffer, "/proc/self/mountinfo"));
        assertEquals("1 0 0:1 / / rw,relatime - rootfs rootfs rw\n"
                + "2 1 0:2 / /proc rw,nosuid,nodev,noexec,relatime - proc proc rw\n"
                + "3 1 0:3 / /sys rw,nosuid,nodev,noexec,relatime - sysfs sysfs rw\n", mountinfo);

        Files.createDirectories(root.resolve("proc"));
        Files.write(root.resolve("proc/modules"), RECORDED_MODULES);
        Files.write(root.resolve("proc/interrupts"), RECORDED_INTERRUPTS);
        assertArrayEquals(RECORDED_MODULES, read(calls, buffer, "/proc/modules"));
        assertArrayEquals(RECORDED_INTERRUPTS, read(calls, buffer, "/proc/interrupts"));
        assertGeneratedFacts(emulator, "/proc/modules");

        // The kernel command line of another process is the bytes the tree holds, not text: no
        // line is added and nothing is decoded.
        Files.createDirectories(root.resolve("proc/1"));
        Files.write(root.resolve("proc/1/cmdline"), RECORDED_KERNEL_CMDLINE);
        assertArrayEquals(RECORDED_KERNEL_CMDLINE, read(calls, buffer, "/proc/1/cmdline"));

        // A path the recording was refused keeps its facts but hands out no bytes.
        LinuxFileSystem files = (LinuxFileSystem) emulator.getFileSystem();
        files.refusals().declare("/proc/sys/kernel/hostname", 13);
        assertEquals(-13, calls.open("/proc/sys/kernel/hostname", 0));
    }

    /**
     * The name a process gives a range is the name the address space reports for that range
     * alone: the parts of the mapping beside it keep their own lines and the facts they had.
     */
    private static void verifyAddressSpaceNames(AndroidEmulator emulator, RawSyscalls calls,
                                                UnidbgPointer buffer) {
        int page = (int) emulator.getPageAlign();
        UnidbgPointer range = emulator.getMemory().mmap(4 * page,
                UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_WRITE);
        UnidbgPointer name = emulator.getMemory().writeStackString(VMA_NAME);
        long mapped = ProcessMemory.mappedBytes(emulator);

        // A range the process did not name carries no path, and the line ends where the header
        // of show_map_vma ends.
        String maps = text(read(calls, buffer, "/proc/self/maps"));
        assertEquals(String.format("%08x-%08x rw-p 00000000 00:00 0 ", range.peer,
                range.peer + 4L * page), mapsLine(maps, range.peer, range.peer + 4L * page));

        // Naming the middle of the mapping splits it into the three ranges the kernel reports,
        // and each part keeps the offset it has in the mapping it comes from.
        assertEquals(0, calls.call(SYS_PRCTL, AnonymousName.PR_SET_VMA, 0, range.peer + page,
                2L * page, name.peer));
        maps = text(read(calls, buffer, "/proc/self/maps"));
        assertEquals(String.format("%08x-%08x rw-p 00000000 00:00 0 ", range.peer,
                range.peer + page), mapsLine(maps, range.peer, range.peer + page));
        assertEquals(mapsLine(range.peer + page, range.peer + 3L * page, "rw-p", page,
                "[anon:" + VMA_NAME + "]"),
                mapsLine(maps, range.peer + page, range.peer + 3L * page));
        assertEquals(String.format("%08x-%08x rw-p 00003000 00:00 0 ", range.peer + 3L * page,
                range.peer + 4L * page),
                mapsLine(maps, range.peer + 3L * page, range.peer + 4L * page));
        assertEquals(1, namedLines(maps, "[anon:" + VMA_NAME + "]"));
        assertEquals(mapped, ProcessMemory.mappedBytes(emulator));

        // A range that is not a whole number of pages is not a range the kernel can name.
        assertEquals(-UnixEmulator.EINVAL, calls.call(SYS_PRCTL, AnonymousName.PR_SET_VMA, 0,
                range.peer + 1, page, name.peer));
        assertEquals(-UnixEmulator.EINVAL, calls.call(SYS_PRCTL, AnonymousName.PR_SET_VMA, 0,
                range.peer, page / 2, name.peer));
        // A range that leaves the mapping covers an unmapped address, which the kernel answers
        // with ENOMEM without naming any part of it.
        assertEquals(-UnixEmulator.ENOMEM, calls.call(SYS_PRCTL, AnonymousName.PR_SET_VMA, 0,
                range.peer + 3L * page, 2L * page, name.peer));
        assertEquals(mapped, ProcessMemory.mappedBytes(emulator));

        // Two mappings of different permissions are two ranges, not one range the runtime can
        // name with one set of permissions.
        UnidbgPointer split = emulator.getMemory().mmap(2 * page,
                UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_WRITE);
        assertEquals(0, emulator.getMemory().mprotect(split.peer + page, page, UnicornConst.UC_PROT_READ));
        assertEquals(-UnixEmulator.EINVAL, calls.call(SYS_PRCTL, AnonymousName.PR_SET_VMA, 0,
                split.peer, 2L * page, name.peer));
        assertEquals(0, calls.call(SYS_PRCTL, AnonymousName.PR_SET_VMA, 0, split.peer + page,
                page, name.peer));
        assertEquals(mapped + 2L * page, ProcessMemory.mappedBytes(emulator));
    }

    /** One address-space line, found by the range it reports. */
    private static String mapsLine(String maps, long start, long end) {
        String range = String.format("%08x-%08x", start, end);
        for (String line : maps.split("\n")) {
            if (line.startsWith(range + " ")) return line;
        }
        throw new AssertionError("no range " + range + " in\n" + maps);
    }

    /**
     * One address-space line as a 64-bit kernel prints it: the header, then the path at the
     * column {@code seq_pad} pads it to.
     */
    private static String mapsLine(long start, long end, String perms, long offset, String label) {
        StringBuilder line = new StringBuilder(String.format("%08x-%08x %s %08x 00:00 0",
                start, end, perms, offset));
        line.append(' ');
        while (line.length() < 73) line.append(' ');
        return line.append(label).toString();
    }

    /** The number of address-space lines that report the given path or name. */
    private static int namedLines(String maps, String label) {
        int found = 0;
        for (String line : maps.split("\n")) {
            if (line.endsWith(label)) found++;
        }
        return found;
    }

    /** The facts of a generated file: a read-only regular file of procfs that stores no bytes. */
    private static void assertGeneratedFacts(AndroidEmulator emulator, String path) {
        FileMetadata facts = emulator.getFileSystem().queryMetadata(path, true).value;
        assertEquals(IO.S_IFREG, facts.mode.getAsInt() & 0170000);
        assertEquals(0444, facts.mode.getAsInt() & 0777);
        assertEquals(1024, facts.blksize.getAsInt());
        assertEquals(0L, facts.size.getAsLong());
    }

    private static byte[] read(RawSyscalls calls, UnidbgPointer buffer, String path) {
        int fd = calls.open(path, 0);
        assertTrue(path, fd >= 0);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                int count = calls.call(SYS_READ, fd, buffer.peer, 512);
                assertTrue(path + ": " + count, count >= 0);
                if (count == 0) return bytes.toByteArray();
                bytes.write(buffer.getByteArray(0, count), 0, count);
            }
        } finally {
            assertEquals(0, calls.call(SYS_CLOSE, fd));
        }
    }

    private static List<Entry> directories(RawSyscalls calls, UnidbgPointer buffer, String path) {
        int fd = calls.open(path, 0x10000);
        assertTrue(path, fd >= 0);
        try {
            List<Entry> entries = new ArrayList<Entry>();
            while (true) {
                int count = calls.call(SYS_GETDENTS64, fd, buffer.peer, 2048);
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
            assertEquals(0, calls.call(SYS_CLOSE, fd));
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

    /** arm64 syscalls issued from emulated code, so the syscall handler answers them. */
    private static final class RawSyscalls implements AutoCloseable {
        private final AndroidEmulator emulator;
        private final UnidbgPointer code;
        private final UnidbgPointer path;

        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(16384, 7);
            path = emulator.getSvcMemory().allocate(256, "kernel-view-test-path");
            for (int number : new int[] {SYS_OPENAT, SYS_CLOSE, SYS_GETDENTS64, SYS_READ,
                    SYS_CLOCK_GETTIME, SYS_UNAME, SYS_PRCTL}) {
                long offset = number * 16L;
                code.setInt(offset, 0xd2800008 | (number << 5));
                code.setInt(offset + 4, 0xd4000001);
                code.setInt(offset + 8, 0xd65f03c0);
            }
        }

        int open(String name, int flags) {
            path.setString(0, name);
            return call(SYS_OPENAT, -100, path.peer, flags, 0);
        }

        int call(int number, Number... arguments) {
            return emulator.eFunc(code.peer + number * 16L, arguments).intValue();
        }

        @Override
        public void close() {
            emulator.getMemory().munmap(code.peer, 16384);
        }
    }
}
