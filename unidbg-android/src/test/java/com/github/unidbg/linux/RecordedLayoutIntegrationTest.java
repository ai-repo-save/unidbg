package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.linux.LinuxFileSystem;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixEmulator;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The answers a recording gives for the files a device laid out under the root directory: which
 * paths a read is refused and what a path below a regular file answers.
 *
 * A root directory holds files and nothing else, so a refusal is the runtime's answer:
 * {@code /sys/class/net/wlan0/address} holds the bytes another context read while the process this
 * recording was taken from is refused them, and a name below a file answers {@code ENOTDIR}
 * because the kernel resolves one component at a time.
 */
public class RecordedLayoutIntegrationTest {
    private static final int PID = 6302;
    private static final int UID = 10123;
    private static final String NAME = "com.example.process";
    /** The mount table the recording holds: the device of a path is the device of its mount. */
    private static final byte[] RECORDED_MOUNTINFO = ("1 0 0:1 / / rw - rootfs rootfs rw\n"
            + "2 1 0:4 / /proc rw - proc proc rw\n"
            + "3 1 0:19 / /sys rw - sysfs sysfs rw\n").getBytes(StandardCharsets.UTF_8);
    private static final int AT_FDCWD = IO.AT_FDCWD;
    private static final int O_DIRECTORY = 0x10000;

    @Test
    public void refusedPathsAndPathResolutionAnswerFromTheRecording() throws Exception {
        Path parent = Paths.get("target/recorded-layout");
        Files.createDirectories(parent);
        for (boolean wide : new boolean[] {false, true}) {
            Path root = Files.createTempDirectory(parent, wide ? "arm64-" : "arm32-").toAbsolutePath();
            layOut(root);
            try (AndroidEmulator emulator = (wide ? AndroidEmulatorBuilder.for64Bit()
                    : AndroidEmulatorBuilder.for32Bit())
                    .setRootDir(root.toFile()).setProcessName(NAME)
                    .addBackendFactory(new Unicorn2Factory(true)).build();
                 RawSyscalls calls = new RawSyscalls(emulator)) {
                emulator.setPid(PID);
                ((AndroidSyscallHandler) emulator.getSyscallHandler()).setGuestCredentials(
                        new GuestCredentials(OptionalInt.of(UID), OptionalInt.of(UID),
                                OptionalInt.of(UID), OptionalInt.of(UID), java.util.Optional.of(Set.of(1005))));
                LinuxFileSystem files = (LinuxFileSystem) emulator.getFileSystem();
                files.procfs().declare(PID, "/proc/self/mountinfo", RECORDED_MOUNTINFO);
                // The recording refused these paths to the process it was taken from.
                files.refusals().declare("/sys/class/net/wlan0/address", UnixEmulator.EACCES);
                files.refusals().declare("/sys/block/loop0/device", UnixEmulator.EACCES);
                files.refusals().declare("/proc/self/cmdline", UnixEmulator.EACCES);

                MemoryBlock scratch = emulator.getMemory().malloc(8192, true);
                try {
                    verifyRefusedReads(files, calls, scratch.getPointer());
                    verifyNonDirectoryComponents(files, calls, scratch.getPointer());
                    verifyEnumeration(calls, scratch.getPointer());
                } finally {
                    scratch.free();
                }
            }
        }
    }

    /**
     * One device layout: the class directories a probe lists, the attributes it reads, and the
     * process files a recording holds. The host user that creates them owns every file, which is
     * what the generated files must not report.
     */
    private static void layOut(Path root) throws Exception {
        write(root, "/sys/class/net/bonding_masters", "+bond0\n");
        write(root, "/sys/class/net/wlan0/address", "aa:bb:cc:dd:ee:ff\n");
        write(root, "/sys/class/net/wlan0/type", "1\n");
        write(root, "/sys/class/net/lo/address", "00:00:00:00:00:00\n");
        write(root, "/sys/block/loop0/size", "2097152\n");
        write(root, "/sys/block/loop0/removable", "0\n");
        Files.createDirectories(root.resolve("sys/block/loop0/device"));
        write(root, "/sys/class/thermal/thermal_zone0/type", "mtktscpu\n");
        write(root, "/sys/class/thermal/thermal_zone0/temp", "42000\n");
        write(root, "/proc/self/cmdline", NAME + "\0");
        write(root, "/proc/self/fdinfo/0", "pos:\t0\nflags:\t0100000\nmnt_id:\t1\n");
        write(root, "/proc/self/fdinfo/1", "pos:\t0\nflags:\t0100001\nmnt_id:\t1\n");
    }

    private static void write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path.substring(1));
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** A refused read is the recorded errno, whatever bytes the layout holds for the path. */
    private static void verifyRefusedReads(LinuxFileSystem files, RawSyscalls calls,
                                           UnidbgPointer buffer) {
        assertEquals("/sys/class/net/wlan0/address", -UnixEmulator.EACCES,
                calls.open("/sys/class/net/wlan0/address", 0));
        assertEquals("/proc/self/cmdline", -UnixEmulator.EACCES, calls.open("/proc/self/cmdline", 0));
        assertEquals("/sys/block/loop0/device", -UnixEmulator.EACCES,
                calls.open("/sys/block/loop0/device", O_DIRECTORY));
        // A path the recording did not refuse still reads the bytes the layout holds.
        int fd = calls.open("/sys/class/net/wlan0/type", 0);
        assertTrue("/sys/class/net/wlan0/type: " + fd, fd >= 0);
        assertEquals(2, calls.read(fd, buffer));
        assertEquals("1\n", buffer.getString(0, "UTF-8"));
        assertEquals(0, calls.call(57, fd));
        // The refusal answers the read, not the facts: a probe that stats before it reads the
        // file still reaches the refusal instead of a missing name.
        FileMetadata facts = files.queryMetadata("/sys/class/net/wlan0/address", true).value;
        assertEquals(IO.S_IFREG, facts.mode.getAsInt() & 0170000);
        assertEquals(-UnixEmulator.ENOENT, calls.open("/sys/class/net/wlan0/missing", 0));
        assertEquals(-UnixEmulator.ENOENT, calls.open("/sys/class/net/missing/address", 0));
    }

    /**
     * A path below a regular file answers {@code ENOTDIR}: the kernel resolves one component at a
     * time, and {@code bonding_masters} is a file that several interfaces are not below.
     */
    private static void verifyNonDirectoryComponents(LinuxFileSystem files, RawSyscalls calls,
                                                     UnidbgPointer buffer) {
        assertEquals(-UnixEmulator.ENOTDIR, calls.open("/sys/class/net/bonding_masters/address", 0));
        assertEquals(-UnixEmulator.ENOTDIR, calls.open("/sys/class/net/bonding_masters/x/y", 0));
        assertEquals(-UnixEmulator.ENOTDIR, calls.open("/sys/class/net/bonding_masters", O_DIRECTORY));
        assertEquals(-UnixEmulator.ENOTDIR,
                calls.fstatatErrno("/sys/class/net/bonding_masters/address", buffer));
        assertEquals(UnixEmulator.ENOTDIR, files.queryMetadata("/sys/class/net/bonding_masters/address", true).errno);
        assertEquals(UnixEmulator.ENOTDIR, files.queryDirectory("/sys/class/net/bonding_masters/address").errno);
    }

    /** A class directory lists the entries the layout holds, and a refused directory lists none. */
    private static void verifyEnumeration(RawSyscalls calls, UnidbgPointer buffer) {
        List<Entry> net = calls.directories("/sys/class/net", buffer);
        assertEquals(Arrays.asList("bonding_masters", "lo", "wlan0"), sorted(net));
        assertEquals(DirectoryEntry.DirentType.DT_REG, type(net, "bonding_masters"));
        assertEquals(DirectoryEntry.DirentType.DT_DIR, type(net, "wlan0"));
        assertEquals(Arrays.asList("thermal_zone0"), sorted(calls.directories("/sys/class/thermal", buffer)));
        assertEquals(Arrays.asList("temp", "type"),
                sorted(calls.directories("/sys/class/thermal/thermal_zone0", buffer)));
        assertEquals(-UnixEmulator.EACCES, calls.opendir("/sys/block/loop0/device"));
        assertEquals(-UnixEmulator.ENOENT, calls.opendir("/sys/class/net/missing"));
    }

    /** The names of a listing, without the dot entries every directory answers. */
    private static List<String> sorted(List<Entry> entries) {
        List<String> names = new ArrayList<String>();
        for (Entry entry : entries) {
            if (!entry.name.equals(".") && !entry.name.equals("..")) names.add(entry.name);
        }
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

    private static final class Entry {
        private final byte type;
        private final String name;

        private Entry(byte type, String name) {
            this.type = type;
            this.name = name;
        }
    }

    /** Syscalls issued from emulated code, so the file system resolution layer answers them. */
    private static final class RawSyscalls implements AutoCloseable {
        private final AndroidEmulator emulator;
        private final UnidbgPointer code;
        private final UnidbgPointer path;
        private final int openAt, close, read, getdents, fstat, fstatat;

        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(8192, 7);
            path = emulator.getSvcMemory().allocate(256, "recorded-layout-path");
            openAt = emulator.is64Bit() ? 56 : 322;
            close = emulator.is64Bit() ? 57 : 6;
            read = emulator.is64Bit() ? 63 : 3;
            getdents = emulator.is64Bit() ? 61 : 217;
            fstat = emulator.is64Bit() ? 80 : 197;
            fstatat = emulator.is64Bit() ? 79 : 327;
            for (int number : new int[] {openAt, close, read, getdents, fstat, fstatat}) {
                long offset = number * 16L;
                code.setInt(offset, emulator.is64Bit() ? 0xd2800008 | (number << 5)
                        : 0xe3007000 | (number & 0xfff) | ((number & 0xf000) << 4));
                code.setInt(offset + 4, emulator.is64Bit() ? 0xd4000001 : 0xef000000);
                code.setInt(offset + 8, emulator.is64Bit() ? 0xd65f03c0 : 0xe12fff1e);
            }
        }

        int open(String name, int flags) {
            path.setString(0, name);
            return call(openAt, AT_FDCWD, path.peer, flags, 0);
        }

        int read(int fd, UnidbgPointer buffer) {
            return call(read, fd, buffer.peer, 64);
        }

        int opendir(String name) {
            return open(name, O_DIRECTORY);
        }

        /** The stat record the descriptor answers, read back through the guest's buffer. */
        byte[] fstat(int fd, UnidbgPointer buffer) {
            UnidbgPointer record = buffer.share(2048, 512);
            int rc = call(fstat, fd, record.peer);
            assertEquals("fstat " + fd, 0, rc);
            return record.getByteArray(0, emulator.is64Bit() ? 128 : 104);
        }

        byte[] fstatat(String name, UnidbgPointer buffer) {
            UnidbgPointer record = buffer.share(2560, 512);
            assertEquals("fstatat " + name, 0, fstatatErrno(name, buffer));
            return record.getByteArray(0, emulator.is64Bit() ? 128 : 104);
        }

        int fstatatErrno(String name, UnidbgPointer buffer) {
            path.setString(0, name);
            return call(fstatat, AT_FDCWD, path.peer, buffer.share(2560, 512).peer, 0);
        }

        List<Entry> directories(String name, UnidbgPointer buffer) {
            int fd = opendir(name);
            assertTrue(name + ": " + fd, fd >= 0);
            try {
                List<Entry> entries = new ArrayList<Entry>();
                while (true) {
                    int count = call(getdents, fd, buffer.peer + 4096, 2048);
                    assertTrue(name + ": " + count, count >= 0);
                    if (count == 0) return entries;
                    int offset = 0;
                    while (offset < count) {
                        int record = buffer.getShort(4096 + offset + 16) & 0xffff;
                        entries.add(new Entry((byte) (buffer.getByte(4096 + offset + 18) & 0xff),
                                buffer.getString(4096 + offset + 19, "UTF-8")));
                        offset += record;
                    }
                }
            } finally {
                assertEquals(0, call(close, fd));
            }
        }

        int call(int number, Number... arguments) {
            if (emulator.is32Bit()) {
                arguments = Arrays.stream(arguments).map(Number::intValue).toArray(Number[]::new);
            }
            return emulator.eFunc(code.peer + number * 16L, arguments).intValue();
        }

        @Override
        public void close() {
            emulator.getMemory().munmap(code.peer, 8192);
        }
    }
}
