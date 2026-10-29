package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.HostFileFacts;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.file.ByteArrayFileIO;
import com.github.unidbg.linux.file.DirectoryFileIO;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.linux.file.Stdin;
import com.github.unidbg.linux.struct.Stat32;
import com.github.unidbg.linux.struct.Stat64;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.memory.mapping.LinuxFileMetadata;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.pointer.UnidbgStructure;
import com.github.unidbg.unix.IO;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** access(2), stat(2), open(2): raw guest SVC instructions, without a target library or device. */
public class FileFactsIntegrationTest {
    @Test
    public void queriesPreserveGuestOperationsAndHeldHostObjects() throws Exception {
        Path parent = Paths.get("target/file-facts");
        Files.createDirectories(parent);
        for (boolean wide : new boolean[] {false, true}) {
            Path root = Files.createTempDirectory(parent, wide ? "arm64-" : "arm32-").toAbsolutePath();
            Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            Path source = root.resolve("source");
            byte[] contents = {10, 20, 30, 40};
            Files.write(source, contents);
            java.time.Instant modification = java.time.Instant.ofEpochSecond(1600000000, 123456789);
            Files.setLastModifiedTime(source, FileTime.from(modification));
            Files.createDirectory(root.resolve("entries"));
            Files.write(root.resolve("entries/alpha"), contents);
            Files.createDirectories(root.resolve("actual/dir"));
            Files.write(root.resolve("actual/source"), new byte[] {1, 2, 3, 4, 5, 6});
            Files.createSymbolicLink(root.resolve("link"), Paths.get("actual/dir"));
            Files.createDirectory(root.resolve("locked"));
            Files.setPosixFilePermissions(root.resolve("locked"), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Files.write(root.resolve("locked/readable"), contents);
            Files.setPosixFilePermissions(root.resolve("locked/readable"), java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
            try (AndroidEmulator emulator = (wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit())
                    .setRootDir(root.toFile()).addBackendFactory(new Unicorn2Factory(true)).build()) {
                AndroidSyscallHandler handler = (AndroidSyscallHandler) emulator.getSyscallHandler();
                FileMetadata facts = HostFileFacts.metadata(source.toFile(), true).value;
                handler.addIOResolver(new ReplayFiles("/baseline", facts));
                handler.addIOResolver(new ReplayFiles("/queried", facts));
                MemoryBlock scratch = emulator.getMemory().malloc(4096, true);
                try (RawSyscalls calls = new RawSyscalls(emulator)) {
                    verifyStdin(emulator, handler, calls, scratch.getPointer(), root);
                    verifyPathResolution(emulator, handler, calls, scratch.getPointer(), root);
                    List<byte[]> baseline = replay(emulator, handler, calls, scratch.getPointer(), "/baseline", false);
                    List<byte[]> queried = replay(emulator, handler, calls, scratch.getPointer(), "/queried", true);
                    assertEquals(baseline.size(), queried.size());
                    for (int i = 0; i < baseline.size(); i++) assertArrayEquals(baseline.get(i), queried.get(i));

                    Map<String, Object> expected = Files.readAttributes(source, "unix:*");
                    StatStructure stat = handler.statAt(emulator, IO.AT_FDCWD, "/source", 0);
                    assertEquals(((Number) expected.get("size")).longValue(), stat.st_size);
                    assertEquals(((Number) expected.get("ino")).longValue(), stat.st_ino);
                    assertEquals(((Number) expected.get("mode")).intValue(), stat.st_mode);
                    assertEquals(((Number) expected.get("uid")).intValue(), stat.st_uid);
                    assertEquals(((Number) expected.get("gid")).intValue(), stat.st_gid);
                    assertEquals(expected.get("lastModifiedTime"), facts.mtime.get());
                    Pointer view = stat.getPointer().share(8, 8);
                    long original = view.getLong(0);
                    view.setLong(0, 0x1234567812345678L);
                    assertEquals(0x1234567812345678L, stat.getPointer().getLong(8));
                    stat.getPointer().setLong(8, 0x7654321076543210L);
                    assertEquals(0x7654321076543210L, view.getLong(0));
                    view.setLong(0, original);
                    assertEquals(modification.getEpochSecond(), wide ? ((Stat64) stat).st_mtim.tv_sec : ((Stat32) stat).st_mtim.tv_sec);
                    assertEquals(modification.getNano(), wide ? ((Stat64) stat).st_mtim.tv_nsec : ((Stat32) stat).st_mtim.tv_nsec);
                    scratch.getPointer().setString(0, "/source");
                    assertEquals(0, calls.call(wide ? 79 : 327, IO.AT_FDCWD, scratch.getPointer().peer,
                            scratch.getPointer().peer + 512, 0));
                    assertArrayEquals(stat.getPointer().getByteArray(0, stat.size()), scratch.getPointer().getByteArray(512, stat.size()));
                    scratch.getPointer().setString(0, "/queried/pre-epoch");
                    assertEquals(0, calls.call(wide ? 79 : 327, IO.AT_FDCWD, scratch.getPointer().peer,
                            scratch.getPointer().peer + 512, 0));
                    long seconds = wide ? scratch.getPointer().getLong(512 + 88) : scratch.getPointer().getInt(512 + 80);
                    long nanoseconds = wide ? scratch.getPointer().getLong(512 + 96) : scratch.getPointer().getInt(512 + 84);
                    assertEquals(-1, seconds);
                    assertEquals(123456789, nanoseconds);

                    int targetUid = 12345;
                    handler.setGuestCredentials(new GuestCredentials(OptionalInt.of(targetUid), OptionalInt.empty(),
                            OptionalInt.empty(), OptionalInt.empty(), Optional.empty()));
                    assertEquals(targetUid, calls.call(wide ? 174 : 199));
                    handler.checkAccessAt(emulator, IO.AT_FDCWD, "/queried/dac", 4);
                    for (String path : new String[] {"/locked/readable", "/locked/../source", "/locked/."}) {
                        for (int mode : new int[] {0, 4}) {
                            scratch.getPointer().setString(0, path);
                            assertEquals(-13, calls.call(wide ? 48 : 334, IO.AT_FDCWD, scratch.getPointer().peer, mode));
                        }
                    }
                    scratch.getPointer().setString(0, "/locked/");
                    assertEquals(0, calls.call(wide ? 48 : 334, IO.AT_FDCWD, scratch.getPointer().peer, 0));
                    assertEquals(-22, calls.call(wide ? 48 : 334, IO.AT_FDCWD, scratch.getPointer().peer, 8));
                    assertThrows(UnsupportedOperationException.class,
                            () -> handler.checkAccessAt(emulator, IO.AT_FDCWD, "/queried/unknown-parent/file", 0));
                    assertThrows(UnsupportedOperationException.class,
                            () -> handler.checkAccessAt(emulator, IO.AT_FDCWD, "/link/../source", 0));
                    assertEquals(13, assertThrows(LinuxErrnoException.class,
                            () -> handler.checkAccessAt(emulator, IO.AT_FDCWD, "/queried/dac", 2)).getErrno());
                    assertThrows(UnsupportedOperationException.class,
                            () -> handler.checkAccessAt(emulator, IO.AT_FDCWD, "/queried/group", 4));
                    handler.setGuestCredentials(new GuestCredentials(OptionalInt.of(targetUid), OptionalInt.of(targetUid),
                            OptionalInt.of(222), OptionalInt.of(222), Optional.of(Collections.singleton(333))));
                    handler.checkAccessAt(emulator, IO.AT_FDCWD, "/queried/group", 4);
                    assertEquals(222, calls.call(wide ? 176 : 200));
                    assertEquals(1, calls.call(wide ? 158 : 205, 1, scratch.getPointer().peer));
                    assertEquals(333, scratch.getPointer().getInt(0));

                    int held = handler.open(emulator, "/source", 0);
                    SimpleFileIO opened = (SimpleFileIO) handler.getFileIO(held);
                    long count = targetHandles(source);
                    int first = opened.read(emulator.getBackend(), scratch.getPointer(), 1);
                    assertEquals(1, first);
                    assertArrayEquals(new byte[] {10}, scratch.getPointer().getByteArray(0, 1));
                    for (int i = 0; i < 20; i++) {
                        handler.queryMetadataAt(emulator, IO.AT_FDCWD, "/source", 0);
                        handler.listDirectoryAt(emulator, IO.AT_FDCWD, "/entries");
                    }
                    assertEquals(count, targetHandles(source));
                    Path moved = root.resolve("moved");
                    Files.move(source, moved);
                    Files.write(source, new byte[] {99});
                    assertEquals(4, handler.queryMetadataAt(emulator, IO.AT_FDCWD, "/proc/self/fd/" + held, 0).value.size.getAsLong());
                    assertEquals(1, handler.queryMetadataAt(emulator, IO.AT_FDCWD, "/source", 0).value.size.getAsLong());
                    assertEquals(1, opened.read(emulator.getBackend(), scratch.getPointer(), 1));
                    assertArrayEquals(new byte[] {20}, scratch.getPointer().getByteArray(0, 1));
                    assertEquals(0, calls.call(wide ? 57 : 6, held));
                    assertEquals(0, targetHandles(moved));
                    assertEquals(0, targetHandles(source));
                    assertArrayEquals(new byte[] {99}, Files.readAllBytes(source));
                } finally {
                    scratch.free();
                }
            }
        }
    }

    private interface LinuxLibC extends Library {
        LinuxLibC INSTANCE = Native.load("c", LinuxLibC.class);
        int stat(String path, Pointer stat);
        int access(String path, int mode);
        int open(String path, int flags);
        int close(int fd);
        int pipe(int[] fds);
        long write(int fd, byte[] bytes, long size);
        int faccessat(int fd, String path, int mode, int flags);
    }

    private static void verifyStdin(AndroidEmulator emulator, AndroidSyscallHandler handler,
                                    RawSyscalls calls, UnidbgPointer buffer, Path root) throws Exception {
        LinuxLibC libc = LinuxLibC.INSTANCE;
        byte[] bytes = {10, 20, 30, 40};
        Path regular = root.resolve("stdin-source");
        Files.write(regular, bytes);
        int[] pipe = new int[2];
        assertEquals(0, libc.pipe(pipe));
        int file = libc.open(regular.toString(), 0);
        int device = libc.open("/dev/null", 0);
        InputStream original = System.in;
        try {
            assertEquals(bytes.length, libc.write(pipe[1], bytes, bytes.length));
            Stdin dynamic = new Stdin(0);
            System.setIn(new ByteArrayInputStream(new byte[] {99}));
            assertEquals(FileQueryResult.Status.UNKNOWN, dynamic.queryMetadata().status());
            assertThrows(UnsupportedOperationException.class,
                    () -> dynamic.fstat(emulator, new Stat64(new byte[UnidbgStructure.calculateSize(Stat64.class)])));
            int dynamicFd = handler.addFileIO(dynamic);
            assertEquals(1, calls.call(emulator.is64Bit() ? 63 : 3, dynamicFd, buffer.peer, 1));
            assertArrayEquals(new byte[] {99}, buffer.getByteArray(0, 1));
            assertEquals(0, calls.call(emulator.is64Bit() ? 57 : 6, dynamicFd));
            for (int hostFd : new int[] {file, pipe[0], device}) {
                Path held = Paths.get("/proc/self/fd/" + hostFd);
                try (InputStream source = Files.newInputStream(held)) {
                    Stdin stdin = new Stdin(0);
                    stdin.setInput(source, () -> LinuxFileMetadata.query(hostFd));
                    int fd = handler.addFileIO(stdin);
                    System.setIn(new ByteArrayInputStream(new byte[] {88}));
                    Map<String, Object> expected = Files.readAttributes(held, "unix:*");
                    FileMetadata facts = handler.queryMetadataAt(emulator, fd, "", 0x1000).value;
                    assertEquals(((Number) expected.get("mode")).intValue(), facts.mode.getAsInt());
                    assertEquals(((Number) expected.get("ino")).longValue(), facts.ino.getAsLong());
                    assertEquals(((Number) expected.get("size")).longValue(), facts.size.getAsLong());
                    assertEquals(0, calls.call(emulator.is64Bit() ? 80 : 197, fd, buffer.peer + 512));
                    StatStructure stat = emulator.is64Bit() ? new Stat64(buffer.share(512)) : new Stat32(buffer.share(512));
                    stat.unpack();
                    assertEquals(((Number) expected.get("mode")).intValue(), stat.st_mode);
                    assertEquals(((Number) expected.get("ino")).longValue(), stat.st_ino);
                    assertEquals(((Number) expected.get("dev")).longValue(), stat.st_dev);
                    assertEquals(((Number) expected.get("rdev")).longValue(), stat.st_rdev);
                    assertEquals(((Number) expected.get("size")).longValue(), stat.st_size);
                    assertEquals(((Number) expected.get("uid")).intValue(), stat.st_uid);
                    assertEquals(((Number) expected.get("gid")).intValue(), stat.st_gid);
                    assertEquals(((Number) expected.get("nlink")).intValue(), stat.st_nlink);
                    for (String path : new String[] {"relative/child", "null/cache"}) {
                        assertEquals(-1, libc.faccessat(hostFd, path, 0, 0));
                        assertEquals(20, Native.getLastError());
                        buffer.setString(0, path);
                        assertEquals(-20, calls.call(emulator.is64Bit() ? 48 : 334, fd, buffer.peer, 0));
                    }
                    int length = hostFd == device ? 0 : 3;
                    assertEquals(length, calls.call(emulator.is64Bit() ? 63 : 3, fd, buffer.peer, 3));
                    assertArrayEquals(Arrays.copyOf(bytes, length), buffer.getByteArray(0, length));
                    assertEquals(88, System.in.read());
                    assertEquals(0, calls.call(emulator.is64Bit() ? 57 : 6, fd));
                    assertEquals(hostFd == device ? -1 : 40, source.read());
                }
            }
        } finally {
            System.setIn(original);
            assertEquals(0, libc.close(file));
            assertEquals(0, libc.close(device));
            assertEquals(0, libc.close(pipe[0]));
            assertEquals(0, libc.close(pipe[1]));
        }
    }

    private static void verifyPathResolution(AndroidEmulator emulator, AndroidSyscallHandler handler,
                                             RawSyscalls calls, UnidbgPointer buffer, Path root) throws Exception {
        LinuxLibC libc = LinuxLibC.INSTANCE;
        Memory nativeStat = new Memory(256);
        String missing = "/missing/../source";
        assertEquals(-1, libc.stat(root + missing, nativeStat));
        assertEquals(2, Native.getLastError());
        assertEquals(2, handler.queryMetadataAt(emulator, IO.AT_FDCWD, missing, 0).errno);
        buffer.setString(0, missing);
        assertEquals(-2, calls.call(emulator.is64Bit() ? 48 : 334, IO.AT_FDCWD, buffer.peer, 0));
        assertEquals(-1, libc.stat(root + "/source/../source", nativeStat));
        assertEquals(20, Native.getLastError());
        assertEquals(20, handler.queryMetadataAt(emulator, IO.AT_FDCWD, "/source/../source", 0).errno);
        assertEquals(0, libc.stat(root + "/link/../source", nativeStat));
        assertEquals(Files.size(root.resolve("actual/source")),
                handler.statAt(emulator, IO.AT_FDCWD, "/link/../source", 0).st_size);
        assertEquals(IO.S_IFLNK, handler.statAt(emulator, IO.AT_FDCWD, "/link", 0x100).st_mode & 0170000);

        int file = handler.open(emulator, "/source", 0);
        int directory = handler.open(emulator, "/entries", 0x10000);
        int nativeFile = libc.open(root + "/source", 0);
        int nativeDirectory = libc.open(root + "/entries", 0x10000);
        try {
            String[][] paths = {
                    {"/proc/self/fd/name", "/proc/self/fd/name", "2"},
                    {"/proc/self/fd/2147483648", "/proc/self/fd/2147483648", "2"},
                    {"/proc/self/fd/0" + file, "/proc/self/fd/0" + nativeFile, "2"},
                    {"/proc/self/fd/" + file + "/" + directory, "/proc/self/fd/" + nativeFile + "/" + nativeDirectory, "20"},
                    {"/proc/self/fd/" + file + "/", "/proc/self/fd/" + nativeFile + "/", "20"}
            };
            for (String[] sample : paths) {
                int errno = Integer.parseInt(sample[2]);
                assertEquals(-1, libc.stat(sample[1], nativeStat));
                assertEquals(errno, Native.getLastError());
                assertEquals(errno, handler.queryMetadataAt(emulator, IO.AT_FDCWD, sample[0], 0).errno);
                buffer.setString(0, sample[0]);
                assertEquals(-errno, calls.call(emulator.is64Bit() ? 79 : 327, IO.AT_FDCWD, buffer.peer, buffer.peer + 512, 0));
                assertEquals(errno, assertThrows(LinuxErrnoException.class,
                        () -> handler.listDirectoryAt(emulator, IO.AT_FDCWD, sample[0])).getErrno());
            }
            for (String node : new String[] {"fd", "task"}) {
                List<String> entries = "fd".equals(node)
                        ? Arrays.asList(Integer.toString(file), Integer.toString(directory))
                        : Collections.singletonList(Integer.toString(emulator.getPid()));
                for (String process : new String[] {"self", Integer.toString(emulator.getPid())}) {
                    for (String suffix : new String[] {"", "/", "/.", "/./", "//././//"}) {
                        String path = "/proc/" + process + "/" + node + suffix;
                        assertEquals(0, libc.stat("/proc/self/" + node + suffix, nativeStat));
                        assertEquals(IO.S_IFDIR, handler.queryMetadataAt(emulator, IO.AT_FDCWD, path, 0).value.mode.getAsInt() & 0170000);
                        assertEquals(entries, handler.listDirectoryAt(emulator, IO.AT_FDCWD, path).stream()
                                .map(entry -> entry.name).collect(java.util.stream.Collectors.toList()));
                    }
                }
                String dotted = "/./proc/./self/./" + node + "/.";
                assertEquals(0, libc.stat(dotted, nativeStat));
                assertEquals(IO.S_IFDIR, handler.queryMetadataAt(emulator, IO.AT_FDCWD, dotted, 0).value.mode.getAsInt() & 0170000);
                assertEquals(entries, handler.listDirectoryAt(emulator, IO.AT_FDCWD, dotted).stream()
                        .map(entry -> entry.name).collect(java.util.stream.Collectors.toList()));
            }
            assertEquals(Files.size(root.resolve("source")), handler.queryMetadataAt(emulator, IO.AT_FDCWD,
                    "/proc/self/fd/./" + file, 0).value.size.getAsLong());
            assertEquals(FileQueryResult.Status.UNKNOWN, handler.queryMetadataAt(emulator, IO.AT_FDCWD,
                    "/proc/self/fd/..", 0).status());
            assertEquals(0, libc.stat("/proc/self/fd/" + nativeDirectory + "/", nativeStat));
            assertEquals(IO.S_IFDIR, handler.queryMetadataAt(emulator, IO.AT_FDCWD,
                    "/proc/self/fd/" + directory + "/", 0).value.mode.getAsInt() & 0170000);
            for (String child : new String[] {"alpha", "."}) {
                String path = "/proc/self/fd/" + directory + "/" + child;
                assertEquals(FileQueryResult.Status.UNKNOWN, handler.queryMetadataAt(emulator, IO.AT_FDCWD, path, 0).status());
                assertThrows(UnsupportedOperationException.class,
                        () -> handler.listDirectoryAt(emulator, IO.AT_FDCWD, path));
            }
            assertThrows(UnsupportedOperationException.class,
                    () -> handler.queryMetadataAt(emulator, directory, ".", 0));
        } finally {
            handler.closeFileIO(file);
            handler.closeFileIO(directory);
            assertEquals(0, libc.close(nativeFile));
            assertEquals(0, libc.close(nativeDirectory));
        }

        assertEquals(0, libc.access(root + "/locked/readable", 4));
        Files.setPosixFilePermissions(root.resolve("locked"), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try {
            for (String path : new String[] {"/locked/readable", "/locked/../source", "/locked/."}) {
                for (int mode : new int[] {0, 4}) {
                    assertEquals(-1, libc.access(root + path, mode));
                    assertEquals(13, Native.getLastError());
                }
            }
            assertEquals(0, libc.access(root + "/locked/", 0));
        } finally {
            Files.setPosixFilePermissions(root.resolve("locked"), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static List<byte[]> replay(AndroidEmulator emulator, AndroidSyscallHandler handler, RawSyscalls calls,
                                       UnidbgPointer buffer, String prefix, boolean query) {
        boolean wide = emulator.is64Bit();
        buffer.setMemory(0, 4096, (byte) 0);
        List<byte[]> observations = new ArrayList<>();
        String path = prefix + "/file";
        if (query) {
            handler.checkAccessAt(emulator, IO.AT_FDCWD, path, 0);
            handler.statAt(emulator, IO.AT_FDCWD, path, 0);
        }
        buffer.setString(0, path);
        // A real O_PATH open is still an operation and consumes the first EINTR.
        assertEquals(-4, calls.call(wide ? 56 : 322, IO.AT_FDCWD, buffer.peer, 0x200000, 0));
        if (query) handler.queryMetadataAt(emulator, IO.AT_FDCWD, path, 0);
        int fd = calls.call(wide ? 56 : 322, IO.AT_FDCWD, buffer.peer, 0, 0);
        assertEquals(0, fd);
        if (query) handler.queryMetadataAt(emulator, IO.AT_FDCWD, "/proc/self/fd/" + fd, 0);
        assertEquals(-4, calls.call(wide ? 80 : 197, fd, buffer.peer + 512));
        if (query) handler.statAt(emulator, IO.AT_FDCWD, path, 0);
        assertEquals(0, calls.call(wide ? 80 : 197, fd, buffer.peer + 512));
        observations.add(buffer.getByteArray(512, wide ? 128 : 104));
        assertEquals(-4, calls.call(wide ? 63 : 3, fd, buffer.peer + 512, 2));
        if (query) handler.queryMetadataAt(emulator, IO.AT_FDCWD, path, 0);
        assertEquals(2, calls.call(wide ? 63 : 3, fd, buffer.peer + 512, 2));
        observations.add(buffer.getByteArray(512, 2));
        if (query) handler.checkAccessAt(emulator, IO.AT_FDCWD, path, 0);
        assertEquals(2, calls.call(wide ? 63 : 3, fd, buffer.peer + 512, 8));
        observations.add(buffer.getByteArray(512, 2));
        assertEquals(0, calls.call(wide ? 57 : 6, fd));

        String unknown = prefix + "/unknown";
        if (query) {
            assertEquals(FileQueryResult.Status.UNKNOWN, handler.queryMetadataAt(emulator, IO.AT_FDCWD, unknown, 0).status());
            assertThrows(UnsupportedOperationException.class, () -> handler.checkAccessAt(emulator, IO.AT_FDCWD, unknown, 0));
        }
        buffer.setString(0, unknown);
        assertEquals(-4, calls.call(wide ? 56 : 322, IO.AT_FDCWD, buffer.peer, 0, 0));
        int unknownFd = calls.call(wide ? 56 : 322, IO.AT_FDCWD, buffer.peer, 0, 0);
        assertEquals(0, unknownFd);
        assertEquals(-4, calls.call(wide ? 63 : 3, unknownFd, buffer.peer + 512, 4));
        assertEquals(4, calls.call(wide ? 63 : 3, unknownFd, buffer.peer + 512, 4));
        observations.add(buffer.getByteArray(512, 4));
        assertEquals(0, calls.call(wide ? 57 : 6, unknownFd));

        String directory = prefix + "/directory";
        if (query) assertEquals("alpha", handler.listDirectoryAt(emulator, IO.AT_FDCWD, directory).get(0).name);
        buffer.setString(0, directory);
        int dirfd = calls.call(wide ? 56 : 322, IO.AT_FDCWD, buffer.peer, 0x10000, 0);
        assertEquals(0, dirfd);
        assertEquals(-4, calls.call(wide ? 61 : 217, dirfd, buffer.peer + 512, 24));
        if (query) handler.listDirectoryAt(emulator, IO.AT_FDCWD, directory);
        assertEquals(24, calls.call(wide ? 61 : 217, dirfd, buffer.peer + 512, 24));
        observations.add(buffer.getByteArray(512, 24));
        if (query) handler.listDirectoryAt(emulator, IO.AT_FDCWD, directory);
        assertEquals(56, calls.call(wide ? 61 : 217, dirfd, buffer.peer + 512, 512));
        observations.add(buffer.getByteArray(512, 56));
        assertEquals(0, calls.call(wide ? 61 : 217, dirfd, buffer.peer + 512, 512));
        assertEquals(0, calls.call(wide ? 57 : 6, dirfd));
        return observations;
    }

    private static long targetHandles(Path target) throws Exception {
        try (Stream<Path> descriptors = Files.list(Paths.get("/proc/self/fd"))) {
            return descriptors.filter(path -> {
                try { return Files.readSymbolicLink(path).equals(target); }
                catch (java.nio.file.NoSuchFileException disappeared) { return false; }
                catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            }).count();
        }
    }

    private static final class ReplayFiles implements IOResolver<AndroidFileIO> {
        private final String prefix;
        private final FileMetadata metadata;
        private final FileMetadata preEpoch;
        private int opens;
        private int unknownOpens;

        ReplayFiles(String prefix, FileMetadata metadata) {
            this.prefix = prefix;
            this.metadata = metadata;
            this.preEpoch = FileMetadata.builder().dev(metadata.dev.getAsLong()).ino(metadata.ino.getAsLong())
                    .mode(metadata.mode.getAsInt()).uid(metadata.uid.getAsInt()).gid(metadata.gid.getAsInt())
                    .nlink(metadata.nlink.getAsInt()).rdev(metadata.rdev.getAsLong()).size(metadata.size.getAsLong())
                    .blocks(metadata.blocks.getAsLong()).blksize(metadata.blksize.getAsInt())
                    .atime(metadata.atime.get()).ctime(metadata.ctime.get())
                    .mtime(FileTime.from(java.time.Instant.ofEpochSecond(-1, 123456789))).build();
        }

        @Override public FileResult<AndroidFileIO> resolve(Emulator<AndroidFileIO> emulator, String path, int flags) {
            if ((prefix + "/file").equals(path) || (prefix + "/unknown").equals(path)) {
                boolean unknown = path.endsWith("/unknown");
                if ((unknown ? unknownOpens++ : opens++) == 0) return FileResult.failed(4);
                return FileResult.success(new ByteArrayFileIO(flags, path, new byte[] {1, 2, 3, 4}) {
                    boolean readInterrupted;
                    boolean statInterrupted;
                    @Override public FileQueryResult<FileMetadata> queryMetadata() {
                        return unknown ? FileQueryResult.unknown() : FileQueryResult.success(metadata);
                    }
                    @Override public int read(Backend backend, Pointer buffer, int size) {
                        if (!readInterrupted) { readInterrupted = true; return -4; }
                        return super.read(backend, buffer, size);
                    }
                    @Override public int fstat(Emulator<?> target, StatStructure stat) {
                        if (!statInterrupted) { statInterrupted = true; return -4; }
                        stat.st_size = 4;
                        stat.pack();
                        return 0;
                    }
                });
            }
            if ((prefix + "/directory").equals(path)) {
                return FileResult.success(new DirectoryFileIO(flags, path, new DirectoryEntry(true, "alpha")) {
                    boolean interrupted;
                    @Override public int getdents64(Pointer buffer, int size) {
                        if (!interrupted) { interrupted = true; return -4; }
                        return super.getdents64(buffer, size);
                    }
                });
            }
            return null;
        }

        @Override public FileQueryResult<FileMetadata> queryMetadata(Emulator<AndroidFileIO> emulator, String path, boolean followLinks) {
            if (prefix.equals(path)) return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFDIR | 0755).build());
            if ((prefix + "/unknown-parent").equals(path)) return FileQueryResult.unknown();
            if ((prefix + "/unknown-parent/file").equals(path)) return FileQueryResult.success(metadata);
            if ((prefix + "/file").equals(path)) return FileQueryResult.success(metadata);
            if ((prefix + "/pre-epoch").equals(path)) return FileQueryResult.success(preEpoch);
            if ((prefix + "/unknown").equals(path)) return FileQueryResult.unknown();
            if ((prefix + "/directory").equals(path)) return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFDIR).build());
            if ((prefix + "/dac").equals(path)) return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFREG | 0444).build());
            if ((prefix + "/group").equals(path)) return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFREG | 0040).uid(999).gid(333).build());
            return null;
        }

        @Override public FileQueryResult<List<DirectoryEntry>> queryDirectory(Emulator<AndroidFileIO> emulator, String path) {
            if ((prefix + "/directory").equals(path)) return FileQueryResult.success(Collections.singletonList(new DirectoryEntry(true, "alpha")));
            if ((prefix + "/unknown").equals(path)) return FileQueryResult.unknown();
            return (prefix + "/file").equals(path) ? FileQueryResult.failed(20) : null;
        }
    }

    private static final class RawSyscalls implements AutoCloseable {
        private final AndroidEmulator emulator;
        private final UnidbgPointer code;
        RawSyscalls(AndroidEmulator emulator) {
            this.emulator = emulator;
            code = emulator.getMemory().mmap(8192, 7);
            for (int number : new int[] {3, 6, 48, 56, 57, 61, 63, 79, 80, 158, 174, 176, 197, 199, 200, 205, 217, 322, 327, 334}) {
                long offset = number * 16L;
                code.setInt(offset, emulator.is64Bit() ? 0xd2800008 | (number << 5)
                        : 0xe3007000 | (number & 0xfff) | ((number & 0xf000) << 4));
                code.setInt(offset + 4, emulator.is64Bit() ? 0xd4000001 : 0xef000000);
                code.setInt(offset + 8, emulator.is64Bit() ? 0xd65f03c0 : 0xe12fff1e);
            }
        }
        int call(int number, Number... arguments) {
            if (emulator.is32Bit()) {
                arguments = Arrays.stream(arguments).map(Number::intValue).toArray(Number[]::new);
            }
            return emulator.eFunc(code.peer + number * 16L, arguments).intValue();
        }
        @Override public void close() { emulator.getMemory().munmap(code.peer, 8192); }
    }
}
