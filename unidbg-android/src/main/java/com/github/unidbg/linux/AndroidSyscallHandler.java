package com.github.unidbg.linux;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.BackendException;
import com.github.unidbg.arm.context.Arm32RegisterContext;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.file.DirectoryFileIO;
import com.github.unidbg.linux.file.DriverFileIO;
import com.github.unidbg.linux.file.EventFD;
import com.github.unidbg.linux.file.PipedReadFileIO;
import com.github.unidbg.linux.file.PipedWriteFileIO;
import com.github.unidbg.linux.file.socket.ReadinessOperation;
import com.github.unidbg.linux.file.tcp.NativeTcpSocketFactory;
import com.github.unidbg.linux.signal.SigAction;
import com.github.unidbg.linux.signal.SignalFunction;
import com.github.unidbg.linux.signal.SignalTask;
import com.github.unidbg.linux.struct.StatFS32;
import com.github.unidbg.linux.struct.StatFS64;
import com.github.unidbg.linux.struct.StatFS;
import com.github.unidbg.linux.thread.FutexIndefinitelyWaiter;
import com.github.unidbg.linux.thread.FutexNanoSleepWaiter;
import com.github.unidbg.linux.thread.FutexWaiter;
import com.github.unidbg.linux.thread.MarshmallowThread;
import com.github.unidbg.linux.thread.NanoSleepWaiter;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.signal.SigSet;
import com.github.unidbg.signal.SignalOps;
import com.github.unidbg.signal.UnixSigSet;
import com.github.unidbg.spi.SyscallHandler;
import com.github.unidbg.thread.MainTask;
import com.github.unidbg.thread.RunnableTask;
import com.github.unidbg.thread.Task;
import com.github.unidbg.thread.ThreadContextSwitchException;
import com.github.unidbg.thread.ThreadDispatcher;
import com.github.unidbg.thread.ThreadTask;
import com.github.unidbg.thread.Waiter;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.UnixSyscallHandler;
import com.github.unidbg.unix.struct.TimeSpec;
import com.github.unidbg.utils.Inspector;
import com.sun.jna.Pointer;
import net.dongliu.apk.parser.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AndroidSyscallHandler extends UnixSyscallHandler<AndroidFileIO> implements SyscallHandler<AndroidFileIO> {

    private static final Logger log = LoggerFactory.getLogger(AndroidSyscallHandler.class);
    private static final AtomicLong REMAPS = new AtomicLong();
    private static final AtomicLong ACCESS_CHECKS = new AtomicLong();
    private final NativeTcpSocketFactory tcpSockets = new NativeTcpSocketFactory();
    private GuestCredentials credentials = new GuestCredentials(java.util.OptionalInt.of(0), java.util.OptionalInt.of(0),
            java.util.OptionalInt.of(0), java.util.OptionalInt.of(0),
            java.util.Optional.of(java.util.Collections.emptySet()));

    public final GuestCredentials getGuestCredentials() { return credentials; }

    public final void setGuestCredentials(GuestCredentials credentials) {
        this.credentials = java.util.Objects.requireNonNull(credentials);
        log.debug("event=guest_credentials_configured handler={} ruid={} euid={} rgid={} egid={} supplementary_groups={}",
                System.identityHashCode(this), credentials.realUid, credentials.effectiveUid,
                credentials.realGid, credentials.effectiveGid, credentials.supplementaryGroups);
    }

    protected final int queryGroups(int size, Pointer buffer) {
        if (size < 0) return -UnixEmulator.EINVAL;
        java.util.Set<Integer> groups = credentials.supplementaryGroups.orElseThrow(() ->
                new UnsupportedOperationException("Guest supplementary groups are unavailable"));
        if (size == 0) return groups.size();
        if (size < groups.size()) return -UnixEmulator.EINVAL;
        int index = 0;
        for (int group : groups) buffer.setInt((index++) * 4L, group);
        return groups.size();
    }

    @Override
    public void destroy() {
        tcpSockets.destroy();
        super.destroy();
    }

    protected final int tcpSocket(Emulator<?> emulator, int domain, int type, int protocol) {
        try {
            return addFileIO(tcpSockets.create(emulator, domain, type, protocol));
        } catch (LinuxErrnoException failure) {
            return -failure.getErrno();
        }
    }

    protected final int duplicate(Emulator<?> emulator, int oldfd) {
        AndroidFileIO old = fdMap.get(oldfd);
        if (old == null) {
            return -UnixEmulator.EBADF;
        }
        try {
            return addFileIO((AndroidFileIO) old.dup2());
        } catch (LinuxErrnoException failure) {
            return -failure.getErrno();
        }
    }

    protected final int duplicateTo(Emulator<?> emulator, int oldfd, int newfd, int flags, boolean dup3) {
        if (dup3 && (oldfd == newfd || (flags & ~0x80000) != 0)) {
            return -UnixEmulator.EINVAL;
        }
        AndroidFileIO old = fdMap.get(oldfd);
        if (old == null || newfd < 0) {
            return -UnixEmulator.EBADF;
        }
        if (oldfd == newfd) {
            return newfd;
        }
        AndroidFileIO replacement;
        try {
            replacement = (AndroidFileIO) old.dup2();
        } catch (LinuxErrnoException failure) {
            return -failure.getErrno();
        }
        boolean installed = false;
        try {
            if ((flags & 0x80000) != 0) {
                int result = replacement.fcntl(emulator, 2, 1);
                if (result < 0) {
                    return result;
                }
            }
            AndroidFileIO previous = fdMap.put(newfd, replacement);
            installed = true;
            log.debug("event=descriptor_duplicated emulator={} old_fd={} new_fd={} flags={}",
                    System.identityHashCode(emulator), oldfd, newfd, flags);
            return previous == null ? newfd : previous.close(emulator, ignored -> newfd);
        } catch (LinuxErrnoException failure) {
            if (installed) {
                log.warn("event=descriptor_implicit_close_failed emulator={} old_fd={} new_fd={}",
                        System.identityHashCode(emulator), oldfd, newfd, failure);
                return newfd;
            }
            return -failure.getErrno();
        } finally {
            if (!installed) {
                replacement.close();
            }
        }
    }

    protected final int shutdown(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        AndroidFileIO file = fdMap.get(fd);
        return file == null ? -UnixEmulator.EBADF : file.shutdown(context.getIntArg(1));
    }

    protected final int pollDescriptors(Emulator<?> emulator, Pointer fds, int count, long timeout) {
        return ReadinessOperation.poll(emulator, fdMap::get, fds, count, timeout, null);
    }

    protected final int ppoll(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer timeout = context.getPointerArg(2);
        int maskSize = emulator.is64Bit() ? context.getIntArg(4) : ((Arm32RegisterContext) context).getR4Int();
        int maskError = readinessMask(context.getPointerArg(3), maskSize);
        if (maskError != 0) {
            return maskError;
        }
        try {
            return ReadinessOperation.poll(emulator, fdMap::get, context.getPointerArg(0), context.getIntArg(1),
                    ReadinessOperation.duration(emulator, timeout, false), timeout);
        } catch (LinuxErrnoException failure) {
            return -failure.getErrno();
        }
    }

    protected final int selectDescriptors(Emulator<?> emulator, int count, Pointer reads, Pointer writes,
                                          Pointer exceptions, Pointer timeout, boolean timeval, Pointer signalArgument) {
        if (signalArgument != null) {
            long address = emulator.is64Bit() ? signalArgument.getLong(0) : Integer.toUnsignedLong(signalArgument.getInt(0));
            long size = emulator.is64Bit() ? signalArgument.getLong(8) : Integer.toUnsignedLong(signalArgument.getInt(4));
            int maskError = readinessMask(UnidbgPointer.pointer(emulator, address), size);
            if (maskError != 0) {
                return maskError;
            }
        }
        return ReadinessOperation.select(emulator, fdMap::get, count, reads, writes, exceptions, timeout, timeval);
    }

    private int readinessMask(Pointer mask, long size) {
        if (mask == null) {
            return 0;
        }
        if (size != 8) {
            return -UnixEmulator.EINVAL;
        }
        log.debug("event=native_readiness_rejected reason=temporary_signal_mask capability=unsupported mask=0x{}",
                Long.toHexString(mask.getLong(0)));
        throw new UnsupportedOperationException("Atomic temporary signal masks in ppoll/pselect are not implemented");
    }

    @Override
    protected final int syscallError(Emulator<?> emulator, int errno) {
        return -errno;
    }

    @Override
    protected final void clearErrnoOnSuccess(Emulator<?> emulator) {
    }

    @Override
    protected final FileResult<AndroidFileIO> resolve(Emulator<AndroidFileIO> emulator, String pathname, int oflags) {
        try {
            return super.resolve(emulator, pathname, oflags);
        } catch (LinuxErrnoException failure) {
            return FileResult.failed(failure.getErrno());
        }
    }

    public final FileQueryResult<FileMetadata> queryMetadataAt(Emulator<AndroidFileIO> emulator,
                                                               int dirfd, String path, int flags) {
        if ((flags & ~(0x100 | 0x800 | 0x1000)) != 0) throw new LinuxErrnoException(UnixEmulator.EINVAL);
        if (path.isEmpty() && (flags & 0x1000) != 0) {
            if (dirfd == IO.AT_FDCWD) return queryMetadata(emulator, ".", true);
            AndroidFileIO held = fdMap.get(dirfd);
            if (held == null) throw new LinuxErrnoException(UnixEmulator.EBADF);
            return held.queryMetadata();
        }
        String pathname = queryPath(emulator, dirfd, path);
        return queryMetadata(emulator, pathname, (flags & 0x100) == 0);
    }

    public final StatStructure statAt(Emulator<AndroidFileIO> emulator, int dirfd, String path, int flags) {
        return FileQuerySupport.snapshot(emulator, FileQuerySupport.require(queryMetadataAt(emulator, dirfd, path, flags), path), path);
    }

    public final void checkAccessAt(Emulator<AndroidFileIO> emulator, int dirfd, String path, int mode) {
        long check = ACCESS_CHECKS.incrementAndGet();
        log.debug("event=file_access_started emulator={} check={} handler={} dirfd={} path={} mode={}",
                System.identityHashCode(emulator), check, System.identityHashCode(this), dirfd, path, mode);
        try {
            if ((mode & ~7) != 0) throw new LinuxErrnoException(UnixEmulator.EINVAL);
            String pathname = queryPath(emulator, dirfd, path);
            FileQuerySupport.accessPath(pathname, mode, credentials, System.identityHashCode(emulator), check,
                    component -> {
                        FileQueryResult<FileMetadata> result = queryMetadata(emulator, component, false);
                        FileMetadata facts = result.isSuccess() ? result.value : null;
                        log.debug("event=file_access_metadata_queried emulator={} check={} path={} status={} errno={} mode_octal={} uid={} gid={}",
                                System.identityHashCode(emulator), check, component, result.status(), result.errno,
                                facts != null && facts.mode.isPresent() ? Integer.toOctalString(facts.mode.getAsInt()) : "unknown",
                                facts != null ? facts.uid : "unknown", facts != null ? facts.gid : "unknown");
                        return result;
                    });
            log.debug("event=file_access_checked emulator={} check={} dirfd={} path={} mode={} status=allowed errno=0",
                    System.identityHashCode(emulator), check, dirfd, path, mode);
        } catch (LinuxErrnoException failure) {
            log.debug("event=file_access_checked emulator={} check={} dirfd={} path={} mode={} status=denied errno={}",
                    System.identityHashCode(emulator), check, dirfd, path, mode, failure.getErrno());
            throw failure;
        } catch (UnsupportedOperationException failure) {
            log.debug("event=file_access_checked emulator={} check={} dirfd={} path={} mode={} status=unknown errno=unknown reason={}",
                    System.identityHashCode(emulator), check, dirfd, path, mode, failure.getMessage());
            throw failure;
        } catch (RuntimeException failure) {
            log.warn("event=file_access_failed emulator={} check={} dirfd={} path={} mode={}",
                    System.identityHashCode(emulator), check, dirfd, path, mode, failure);
            throw failure;
        }
    }

    public final List<DirectoryEntry> listDirectoryAt(Emulator<AndroidFileIO> emulator, int dirfd, String path) {
        return FileQuerySupport.require(queryDirectory(emulator, queryPath(emulator, dirfd, path)), path);
    }

    protected final String queryPath(Emulator<AndroidFileIO> emulator, int dirfd, String path) {
        if (path.isEmpty()) throw new LinuxErrnoException(UnixEmulator.ENOENT);
        if (!path.startsWith("/") && dirfd != IO.AT_FDCWD) {
            AndroidFileIO held = fdMap.get(dirfd);
            if (held == null) throw new LinuxErrnoException(UnixEmulator.EBADF);
            String source = "dirfd=" + dirfd + ", path=" + path + ", source=" + held.getClass().getName();
            FileMetadata facts = FileQuerySupport.require(held.queryMetadata(), source);
            if (!facts.mode.isPresent()) throw new UnsupportedOperationException("Directory type unavailable: " + source);
            if ((facts.mode.getAsInt() & 0170000) != IO.S_IFDIR) throw new LinuxErrnoException(UnixEmulator.ENOTDIR);
            if (!(held instanceof DirectoryFileIO)) {
                throw new UnsupportedOperationException("Held directory anchors are unavailable: dirfd=" + dirfd + ", path=" + path);
            }
            String guest = guestPath(emulator, ((DirectoryFileIO) held).resolveRelative(path));
            log.debug("event=directory_anchor_resolved emulator={} dirfd={} relative={} guest={}",
                    System.identityHashCode(emulator), dirfd, path, guest);
            return guest;
        }
        return path;
    }

    private String guestPath(Emulator<AndroidFileIO> emulator, Path host) {
        try {
            Path root = emulator.getFileSystem().getRootDir().getCanonicalFile().toPath();
            Path real = host.toRealPath();
            if (!real.startsWith(root)) throw new LinuxErrnoException(UnixEmulator.EACCES);
            String relative = root.relativize(real).toString().replace(File.separatorChar, '/');
            return relative.isEmpty() ? "/" : "/" + relative;
        } catch (IOException failure) {
            throw new IllegalStateException("Resolving directory anchor path: " + host, failure);
        }
    }

    protected final int statAt(Emulator<AndroidFileIO> emulator, int dirfd, String path, int flags, Pointer buffer) {
        try {
            StatStructure stat = statAt(emulator, dirfd, path, flags);
            buffer.write(0, stat.getPointer().getByteArray(0, stat.size()), 0, stat.size());
            return 0;
        } catch (LinuxErrnoException failure) {
            return -failure.getErrno();
        }
    }

    protected final int accessAt(Emulator<AndroidFileIO> emulator, int dirfd, String path, int mode) {
        try {
            checkAccessAt(emulator, dirfd, path, mode);
            return 0;
        } catch (LinuxErrnoException failure) {
            return -failure.getErrno();
        }
    }

    private List<DirectoryEntry> fdEntries() {
        List<DirectoryEntry> entries = new ArrayList<>();
        for (Integer fd : fdMap.keySet()) entries.add(new DirectoryEntry(DirectoryEntry.DirentType.DT_LNK, fd.toString()));
        return java.util.Collections.unmodifiableList(entries);
    }

    private List<DirectoryEntry> taskEntries(Emulator<AndroidFileIO> emulator) {
        return java.util.Collections.singletonList(new DirectoryEntry(false, Integer.toString(emulator.getPid())));
    }

    @Override
    protected FileQueryResult<FileMetadata> querySpecialMetadata(Emulator<AndroidFileIO> emulator, String pathname, boolean followLinks) {
        FileQueryResult<FileMetadata> held = super.querySpecialMetadata(emulator, pathname, followLinks);
        if (held != null) return held;
        if (isFdDirectory(emulator, pathname) || isTaskDirectory(emulator, pathname)) {
            return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFDIR).build());
        }
        return DriverFileIO.queryMetadata(pathname);
    }

    @Override
    protected FileQueryResult<List<DirectoryEntry>> querySpecialDirectory(Emulator<AndroidFileIO> emulator, String pathname) {
        if (isFdDirectory(emulator, pathname)) return FileQueryResult.success(fdEntries());
        if (isTaskDirectory(emulator, pathname)) return FileQueryResult.success(taskEntries(emulator));
        if (DriverFileIO.queryMetadata(pathname) != null) return FileQueryResult.failed(UnixEmulator.ENOTDIR);
        ProcFdPath descriptor = procFdPath(emulator, pathname);
        if (descriptor != null) {
            if (!descriptor.fd.isPresent()) return FileQueryResult.unknown();
            AndroidFileIO held = fdMap.get(descriptor.fd.getAsInt());
            if (held == null) return FileQueryResult.failed(UnixEmulator.ENOENT);
            FileQueryResult<FileMetadata> facts = held.queryMetadata();
            if (facts.isSuccess() && facts.value.mode.isPresent() && (facts.value.mode.getAsInt() & 0170000) != IO.S_IFDIR) {
                return FileQueryResult.failed(UnixEmulator.ENOTDIR);
            }
            if (facts.status() == FileQueryResult.Status.ERROR) return FileQueryResult.failed(facts.errno);
            if (!descriptor.hasChildPath && held instanceof DirectoryFileIO) {
                return FileQueryResult.success(((DirectoryFileIO) held).queryDirectory());
            }
            return FileQueryResult.unknown();
        }
        return super.querySpecialDirectory(emulator, pathname);
    }

    protected final long remapAnonymous(Emulator<?> emulator, long oldAddress, int oldSize,
                                       int newSize, int flags, long newAddress) {
        long operation = REMAPS.incrementAndGet();
        log.debug("event=memory_remap_started emulator={} remap={} old_address=0x{} old_size={} new_size={} flags={} new_address=0x{}",
                System.identityHashCode(emulator), operation, Long.toHexString(oldAddress), oldSize, newSize, flags, Long.toHexString(newAddress));
        int page = emulator.getPageAlign();
        if (oldSize <= 0 || newSize <= 0 || oldAddress % page != 0 ||
                ((flags & MREMAP_FIXED) != 0 && newAddress % page != 0)) {
            log.debug("event=memory_remap_rejected remap={} reason=invalid_arguments errno={}", operation, UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        if ((flags & ~(MREMAP_MAYMOVE | MREMAP_FIXED)) != 0 ||
                ((flags & MREMAP_FIXED) != 0 && (flags & MREMAP_MAYMOVE) == 0)) {
            log.debug("event=memory_remap_rejected remap={} reason=invalid_flags errno={}", operation, UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        if ((flags & MREMAP_MAYMOVE) == 0) {
            log.debug("event=memory_remap_rejected remap={} reason=in_place_resize capability=unsupported", operation);
            throw new UnsupportedOperationException("In-place mremap without MAYMOVE is not implemented");
        }
        long oldLength = com.github.unidbg.arm.ARM.alignSize(oldSize, page);
        long newLength = com.github.unidbg.arm.ARM.alignSize(newSize, page);
        if (newLength > Integer.MAX_VALUE) {
            log.debug("event=memory_remap_rejected remap={} reason=mapping_capacity errno={}", operation, UnixEmulator.ENOMEM);
            return -UnixEmulator.ENOMEM;
        }
        long oldEnd = oldAddress + oldLength;
        long newEnd = newAddress + newLength;
        if ((flags & MREMAP_FIXED) != 0 && oldAddress < newEnd && newAddress < oldEnd) {
            log.debug("event=memory_remap_rejected remap={} reason=overlapping_ranges errno={}", operation, UnixEmulator.EINVAL);
            return -UnixEmulator.EINVAL;
        }
        Memory memory = emulator.getMemory();
        MemoryMap source = null;
        for (MemoryMap map : memory.getMemoryMap()) {
            if (oldAddress >= map.base + map.size || map.base >= oldEnd) continue;
            if (map.backing != null || (map.flags & AndroidElfLoader.MAP_ANONYMOUS) == 0 ||
                    (map.flags & 0xf) != AndroidElfLoader.MAP_PRIVATE) {
                log.debug("event=memory_remap_rejected remap={} reason=unsupported_source source_flags={} file_backing={}",
                        operation, map.flags, map.backing != null);
                throw new UnsupportedOperationException("mremap requires an explicitly private anonymous source");
            }
            if (oldAddress >= map.base && oldEnd <= map.base + map.size) source = map;
        }
        if (source == null) {
            log.debug("event=memory_remap_rejected remap={} reason=source_range errno={}", operation, UnixEmulator.EFAULT);
            return -UnixEmulator.EFAULT;
        }
        int mappingFlags = AndroidElfLoader.MAP_ANONYMOUS | AndroidElfLoader.MAP_PRIVATE;
        if ((flags & MREMAP_FIXED) != 0) mappingFlags |= AndroidElfLoader.MAP_FIXED;
        byte[] bytes = emulator.getBackend().mem_read(oldAddress, Math.min(oldLength, newLength));
        long address = memory.mmap2((flags & MREMAP_FIXED) != 0 ? newAddress : 0,
                newSize, source.prot, mappingFlags, -1, 0);
        if (address < 0) {
            log.debug("event=memory_remap_failed remap={} phase=allocate errno={}", operation, -address);
            return address;
        }
        try {
            emulator.getBackend().mem_write(address, bytes);
        } catch (RuntimeException | Error failure) {
            try {
                int rollback = memory.munmap(address, newSize);
                if (rollback < 0) failure.addSuppressed(new IllegalStateException("mremap rollback failed: " + rollback));
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            log.error("event=memory_remap_failed remap={} phase=copy new_address=0x{}", operation, Long.toHexString(address), failure);
            throw failure;
        }
        int removed = memory.munmap(oldAddress, oldSize);
        if (removed < 0) {
            int rollback = memory.munmap(address, newSize);
            if (rollback < 0) throw new IllegalStateException("mremap rollback failed: " + rollback + ", original=" + removed);
            log.debug("event=memory_remap_failed remap={} phase=unmap_old errno={}", operation, -removed);
            return removed;
        }
        log.debug("event=memory_remap_completed remap={} new_address=0x{}", operation, Long.toHexString(address));
        return address;
    }

    static final int MREMAP_MAYMOVE = 1;
    static final int MREMAP_FIXED = 2;

    private byte[] sched_cpu_mask;

    final int mlock(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer addr = context.getPointerArg(0);
        int len = context.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("mlock addr={}, len={}", addr, len);
        }
        return 0;
    }

    final int munlock(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer addr = context.getPointerArg(0);
        int len = context.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("munlock addr={}, len={}", addr, len);
        }
        return 0;
    }

    final long sched_setaffinity(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        int cpusetsize = context.getIntArg(1);
        Pointer mask = context.getPointerArg(2);
        if (mask != null) {
            sched_cpu_mask = mask.getByteArray(0, cpusetsize);
        }
        if (log.isDebugEnabled()) {
            log.debug(Inspector.inspectString(sched_cpu_mask, "sched_setaffinity pid=" + pid + ", cpusetsize=" + cpusetsize + ", mask=" + mask));
        }
        return 0;
    }

    final long sched_getaffinity(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        int cpusetsize = context.getIntArg(1);
        Pointer mask = context.getPointerArg(2);
        int ret = 0;
        if (mask != null && sched_cpu_mask != null) {
            mask.write(0, sched_cpu_mask, 0, cpusetsize);
            ret = cpusetsize;
        }
        if (log.isDebugEnabled()) {
            log.debug(Inspector.inspectString(sched_cpu_mask, "sched_getaffinity pid=" + pid + ", cpusetsize=" + cpusetsize + ", mask=" + mask));
        }
        return ret;
    }

    private static final int EFD_SEMAPHORE = 1;
    private static final int EFD_NONBLOCK = IOConstants.O_NONBLOCK;
    private static final int EFD_CLOEXEC = IOConstants.O_CLOEXEC;

    final int eventfd2(Emulator<?> emulator) {
        RegisterContext ctx = emulator.getContext();
        int initval = ctx.getIntArg(0);
        int flags = ctx.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("eventfd2 initval={}, flags=0x{}", initval, Integer.toHexString(flags));
        }
        if ((flags & EFD_CLOEXEC) != 0) {
            throw new UnsupportedOperationException("eventfd2 flags=0x" + Integer.toHexString(flags));
        }
        boolean nonblock = (flags & EFD_NONBLOCK) != 0;
        boolean semaphore = (flags & EFD_SEMAPHORE) != 0;
        AndroidFileIO fileIO = new EventFD(initval, semaphore, nonblock);
        int minFd = this.getMinFd();
        this.fdMap.put(minFd, fileIO);
        if (verbose) {
            System.out.printf("eventfd(%d) with flags=0x%x fd=%d from %s%n", initval, flags, minFd, emulator.getContext().getLRPointer());
        }
        return minFd;
    }

    protected int sched_setscheduler(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        int policy = context.getIntArg(1);
        Pointer param = context.getPointerArg(2);
        if (log.isDebugEnabled()) {
            log.debug("sched_setscheduler pid={}, policy={}, param={}", pid, policy, param);
        }
        return 0;
    }

    protected int getcwd(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        UnidbgPointer buf = context.getPointerArg(0);
        int size = context.getIntArg(1);
        File workDir = emulator.getFileSystem().createWorkDir();
        String path = workDir.getPath();
        if (log.isDebugEnabled()) {
            log.debug("getcwd buf={}, size={}, path={}", buf, size, path);
        }
        buf.setString(0, ".");
        return (int) buf.peer;
    }

    private static final int SCHED_OTHER = 0;

    protected int sched_getscheduler(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        if (log.isDebugEnabled()) {
            log.debug("sched_getscheduler pid={}", pid);
        }
        return SCHED_OTHER;
    }

    protected int sched_getparam(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        Pointer param = context.getPointerArg(1);
        if (log.isDebugEnabled()) {
            log.debug("sched_getparam pid={}, param={}", pid, param);
        }
        param.setInt(0, ANDROID_PRIORITY_NORMAL);
        return 0;
    }

    protected int sched_yield(Emulator<AndroidFileIO> emulator) {
        if (log.isDebugEnabled()) {
            log.debug("sched_yield");
        }
        if (emulator.getThreadDispatcher().getTaskCount() <= 1) {
            return 0;
        } else {
            throw new ThreadContextSwitchException().setReturnValue(0);
        }
    }

    private static final int ANDROID_PRIORITY_NORMAL = 0; /* most threads run at normal priority */

    protected int getpriority(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int which = context.getIntArg(0);
        int who = context.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("getpriority which={}, who={}", which, who);
        }
        return ANDROID_PRIORITY_NORMAL;
    }

    protected int setpriority(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int which = context.getIntArg(0);
        int who = context.getIntArg(1);
        int prio = context.getIntArg(2);
        if (log.isDebugEnabled()) {
            log.debug("setpriority which={}, who={}, prio={}", which, who, prio);
        }
        return 0;
    }

    private static final int SIG_BLOCK = 0;
    private static final int SIG_UNBLOCK = 1;
    private static final int SIG_SETMASK = 2;

    @Override
    protected int sigprocmask(Emulator<?> emulator, int how, Pointer set, Pointer oldset) {
        Task task = emulator.get(Task.TASK_KEY);
        SignalOps signalOps = task.isMainThread() ? emulator.getThreadDispatcher() : task;
        SigSet old = signalOps.getSigMaskSet();
        if (oldset != null && old != null) {
            if (emulator.is32Bit()) {
                oldset.setInt(0, (int) old.getMask());
            } else {
                oldset.setLong(0, old.getMask());
            }
        }
        if (set == null) {
            return 0;
        }
        long mask = emulator.is32Bit() ? set.getInt(0) : set.getLong(0);
        switch (how) {
            case SIG_BLOCK:
                if (old == null) {
                    SigSet sigSet = new UnixSigSet(mask);
                    SigSet sigPendingSet = new UnixSigSet(0);
                    signalOps.setSigMaskSet(sigSet);
                    signalOps.setSigPendingSet(sigPendingSet);
                } else {
                    old.blockSigSet(mask);
                }
                return 0;
            case SIG_UNBLOCK:
                if (old != null) {
                    old.unblockSigSet(mask);
                }
                return 0;
            case SIG_SETMASK:
                SigSet sigSet = new UnixSigSet(mask);
                SigSet sigPendingSet = new UnixSigSet(0);
                signalOps.setSigMaskSet(sigSet);
                signalOps.setSigPendingSet(sigPendingSet);
                return 0;
        }
        return super.sigprocmask(emulator, how, set, oldset);
    }

    protected int rt_sigpending(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer set = context.getPointerArg(0);
        if (log.isDebugEnabled()) {
            log.debug("rt_sigpending set={}", set);
        }
        Task task = emulator.get(Task.TASK_KEY);
        SignalOps signalOps = task.isMainThread() ? emulator.getThreadDispatcher() : task;
        SigSet sigSet = signalOps.getSigPendingSet();
        if (set != null && sigSet != null) {
            if (emulator.is32Bit()) {
                set.setInt(0, (int) sigSet.getMask());
            } else {
                set.setLong(0, sigSet.getMask());
            }
        }
        return 0;
    }

    private static final int FUTEX_CMD_MASK = 0x7f;
    private static final int FUTEX_PRIVATE_FLAG = 0x80;
    private static final int MUTEX_SHARED_MASK = 0x2000;
    private static final int MUTEX_TYPE_MASK = 0xc000;
    private static final int FUTEX_WAIT = 0;
    private static final int FUTEX_WAKE = 1;
    private static final int FUTEX_FD = 2;
    private static final int FUTEX_REQUEUE = 3;
    private static final int FUTEX_CMP_REQUEUE = 4;

    public static final int ETIMEDOUT = 110;

    protected int futex(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer uaddr = context.getPointerArg(0);
        int futex_op = context.getIntArg(1);
        int val = context.getIntArg(2);
        int old = uaddr.getInt(0);
        boolean isPrivate = (futex_op & FUTEX_PRIVATE_FLAG) != 0;
        int cmd = futex_op & FUTEX_CMD_MASK;
        if (log.isDebugEnabled()) {
            log.debug("futex uaddr={}, isPrivate={}, cmd={}, val=0x{}, old=0x{}, LR={}", uaddr, isPrivate, cmd, Integer.toHexString(val), Integer.toHexString(old), context.getLRPointer());
        }

        Task task = emulator.get(Task.TASK_KEY);
        switch (cmd) {
            case FUTEX_WAIT:
                if (old != val) {
                    return -UnixEmulator.EAGAIN;
                }
                Pointer timeout = context.getPointerArg(3);
                TimeSpec timeSpec = timeout == null ? null : TimeSpec.createTimeSpec(emulator, timeout);
                int mtype = val & MUTEX_TYPE_MASK;
                int shared = val & MUTEX_SHARED_MASK;
                if (log.isDebugEnabled()) {
                    log.debug("futex FUTEX_WAIT mtype=0x{}, shared={}, timeSpec={}, test={}, task={}", Integer.toHexString(mtype), shared, timeSpec, mtype | shared, task);
                }
                RunnableTask runningTask = emulator.getThreadDispatcher().getRunningTask();
                if (threadDispatcherEnabled && runningTask != null) {
                    if (timeSpec == null) {
                        runningTask.setWaiter(emulator, new FutexIndefinitelyWaiter(uaddr, val));
                    } else {
                        runningTask.setWaiter(emulator, new FutexNanoSleepWaiter(uaddr, val, timeSpec));
                    }
                    throw new ThreadContextSwitchException();
                }
                if (threadDispatcherEnabled && emulator.getThreadDispatcher().getTaskCount() > 1) {
                    throw new ThreadContextSwitchException().setReturnValue(-ETIMEDOUT);
                } else {
                    return 0;
                }
            case FUTEX_WAKE:
                if (log.isDebugEnabled()) {
                    log.debug("futex FUTEX_WAKE val=0x{}, old={}, task={}", Integer.toHexString(val), old, task);
                }
                if (emulator.getThreadDispatcher().getTaskCount() <= 1) {
                    return 0;
                }
                int count = 0;
                for (Task t : emulator.getThreadDispatcher().getTaskList()) {
                    Waiter waiter = t.getWaiter();
                    if (waiter instanceof FutexWaiter) {
                        if (((FutexWaiter) waiter).wakeUp(uaddr)) {
                            if (++count >= val) {
                                break;
                            }
                        }
                    }
                }
                if (count > 0) {
                    throw new ThreadContextSwitchException().setReturnValue(count);
                }
                if (threadDispatcherEnabled && task != null) {
                    throw new ThreadContextSwitchException().setReturnValue(1);
                }
                return 0;
            case FUTEX_CMP_REQUEUE:
                if (log.isDebugEnabled()) {
                    log.debug("futex FUTEX_CMP_REQUEUE val=0x{}, old={}, task={}", Integer.toHexString(val), old, task);
                }
                return 0;
            default:
                if (log.isDebugEnabled()) {
                    emulator.attach().debug("Unsupported futex_op=0x" + Integer.toHexString(futex_op));
                }
                throw new AbstractMethodError("futex_op=0x" + Integer.toHexString(futex_op));
        }
    }

    protected int rt_sigtimedwait(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer set = context.getPointerArg(0);
        Pointer info = context.getPointerArg(1);
        Pointer timeout = context.getPointerArg(2);
        int sigsetsize = context.getIntArg(3);
        long mask = emulator.is32Bit() ? set.getInt(0) : set.getLong(0);
        Task task = emulator.get(Task.TASK_KEY);
        SigSet sigSet = new UnixSigSet(mask);
        SignalOps signalOps = task.isMainThread() ? emulator.getThreadDispatcher() : task;
        SigSet sigPendingSet = signalOps.getSigPendingSet();
        if (sigPendingSet != null) {
            for (Integer signum : sigSet) {
                if (sigPendingSet.containsSigNumber(signum)) {
                    sigPendingSet.removeSigNumber(signum);
                    return signum;
                }
            }
        }
        if (!task.isMainThread()) {
            throw new ThreadContextSwitchException().setReturnValue(-UnixEmulator.EINTR);
        }
        log.info("rt_sigtimedwait set={}, info={}, timeout={}, sigsetsize={}, sigSet={}, task={}", set, info, timeout, sigsetsize, sigSet, task);
        Logger log = LoggerFactory.getLogger(AbstractEmulator.class);
        if (log.isDebugEnabled()) {
            emulator.attach().debug("rt_sigtimedwait sigSet=" + sigSet);
        }
        return 0;
    }

    protected int rt_sigqueue(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int tgid = context.getIntArg(0);
        int sig = context.getIntArg(1);
        UnidbgPointer info = context.getPointerArg(2);
        if (log.isDebugEnabled()) {
            log.debug("rt_sigqueue tgid={}, sig={}", tgid, sig);
        }
        Task task = emulator.get(Task.TASK_KEY);
        // 检查pid是有匹配进程存在
        if (!(tgid == 0 || tgid == -1 || Math.abs(tgid) == emulator.getPid())) {
            return -UnixEmulator.ESRCH;
        }
        // 检查进程是否存在, 无需发送信号
        if (sig == 0) {
            return 0;
        }
        if (sig < 0 || sig > 64) {
            return -UnixEmulator.EINVAL;
        }
        if (task != null) {
            SigAction sigAction = sigActionMap.get(sig);
            return processSignal(emulator.getThreadDispatcher(), sig, task, sigAction, info);
        }
        throw new UnsupportedOperationException();
    }

    @Override
    protected FileResult<AndroidFileIO> createFdDir(int oflags, String pathname) {
        return FileResult.<AndroidFileIO>success(new DirectoryFileIO(oflags, pathname, fdEntries().toArray(new DirectoryEntry[0])));
    }

    @Override
    protected FileResult<AndroidFileIO> createTaskDir(Emulator<AndroidFileIO> emulator, int oflags, String pathname) {
        return FileResult.<AndroidFileIO>success(new DirectoryFileIO(oflags, pathname, taskEntries(emulator).toArray(new DirectoryEntry[0])));
    }

    protected long statfs64(Emulator<AndroidFileIO> emulator, String path, Pointer buf) {
        FileResult<AndroidFileIO> result = resolve(emulator, path, IOConstants.O_RDONLY);
        if (result == null) {
            log.info("statfs64 buf={}, path={}", buf, path);
            return -UnixEmulator.ENOENT;
        }
        if (result.isSuccess()) {
            StatFS statFS = emulator.is64Bit() ? new StatFS64(buf) : new StatFS32(buf);
            int ret = result.io.statfs(statFS);
            if (ret != 0) {
                log.info("statfs64 buf={}, path={}, ret={}", buf, path, ret);
            } else {
                if (verbose) {
                    System.out.printf("File statfs '%s' from %s%n", result.io, emulator.getContext().getLRPointer());
                }
                if (log.isDebugEnabled()) {
                    log.debug("statfs64 buf={}, path={}", buf, path);
                }
            }
            return ret;
        } else {
            log.info("statfs64 buf={}, path={}", buf, path);
            return -result.errno;
        }
    }

    protected int pipe2(Emulator<?> emulator) {
        try {
            RegisterContext context = emulator.getContext();
            Pointer pipefd = context.getPointerArg(0);
            int flags = context.getIntArg(1);
            int writefd = getMinFd();
            Pair<AndroidFileIO, AndroidFileIO> pair = getPipePair(emulator, writefd);
            this.fdMap.put(writefd, pair.getLeft());
            int readfd = getMinFd();
            this.fdMap.put(readfd, pair.getRight());
            pipefd.setInt(0, readfd);
            pipefd.setInt(4, writefd);
            if (log.isDebugEnabled()) {
                log.debug("pipe2 pipefd={}, flags=0x{}, readfd={}, writefd={}", pipefd, flags, readfd, writefd);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return 0;
    }

    protected Pair<AndroidFileIO, AndroidFileIO> getPipePair(Emulator<?> emulator, int writefd) throws IOException {
        PipedInputStream inputStream = new PipedInputStream();
        PipedOutputStream outputStream = new PipedOutputStream(inputStream);
        AndroidFileIO writeIO = new PipedWriteFileIO(outputStream, writefd);
        AndroidFileIO readIO = new PipedReadFileIO(inputStream, writefd);
        log.info("Return default pipe pair.");
        return new Pair<>(writeIO, readIO);
    }

    protected int fchmodat(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname_p = context.getPointerArg(1);
        int mode = context.getIntArg(2);
        int flags = context.getIntArg(3);
        String pathname = pathname_p.getString(0);
        if (log.isDebugEnabled()) {
            log.debug("fchmodat dirfd={}, pathname={}, mode=0x{}, flags=0x{}", dirfd, pathname, Integer.toHexString(mode), Integer.toHexString(flags));
        }
        return 0;
    }

    protected int fchownat(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname_p = context.getPointerArg(1);
        int owner = context.getIntArg(2);
        int group = context.getIntArg(3);
        int flags = context.getIntArg(4);
        String pathname = pathname_p.getString(0);
        if (log.isDebugEnabled()) {
            log.debug("fchownat dirfd={}, pathname={}, owner={}, group={}, flags=0x{}", dirfd, pathname, owner, group, Integer.toHexString(flags));
        }
        return 0;
    }

    protected int mkdirat(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname_p = context.getPointerArg(1);
        int mode = context.getIntArg(2);
        String pathname = pathname_p.getString(0);
        if (log.isDebugEnabled()) {
            log.debug("mkdirat dirfd={}, pathname={}, mode={}", dirfd, pathname, Integer.toHexString(mode));
        }
        if (dirfd != IO.AT_FDCWD) {
            throw new BackendException();
        }
        if (emulator.getFileSystem().mkdir(pathname, mode)) {
            if (log.isDebugEnabled()) {
                log.debug("mkdir pathname={}, mode={}", pathname, mode);
            }
            return 0;
        } else {
            log.info("mkdir pathname={}, mode={}", pathname, mode);
            return -UnixEmulator.EACCES;
        }
    }

    protected int sigaltstack(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer ss = context.getPointerArg(0);
        Pointer old_ss = context.getPointerArg(1);
        if (log.isDebugEnabled()) {
            log.debug("sigaltstack ss={}, old_ss={}", ss, old_ss);
        }
        return 0;
    }

    protected int renameat(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int olddirfd = context.getIntArg(0);
        String oldpath = context.getPointerArg(1).getString(0);
        int newdirfd = context.getIntArg(2);
        String newpath = context.getPointerArg(3).getString(0);
        int ret = emulator.getFileSystem().rename(oldpath, newpath);
        if (ret != 0) {
            log.info("renameat olddirfd={}, oldpath={}, newdirfd={}, newpath={}", olddirfd, oldpath, newdirfd, newpath);
        } else {
            log.debug("renameat olddirfd={}, oldpath={}, newdirfd={}, newpath={}", olddirfd, oldpath, newdirfd, newpath);
        }
        return 0;
    }

    protected int unlinkat(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname = context.getPointerArg(1);
        int flags = context.getIntArg(2);
        emulator.getFileSystem().unlink(pathname.getString(0));
        if (log.isDebugEnabled()) {
            log.info("unlinkat dirfd={}, pathname={}, flags={}", dirfd, pathname.getString(0), flags);
        }
        return 0;
    }

    protected void exit(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int status = context.getIntArg(0);
        Task task = emulator.get(Task.TASK_KEY);
        if (task instanceof ThreadTask) {
            ThreadTask threadTask = (ThreadTask) task;
            threadTask.setExitStatus(status);
            throw new ThreadContextSwitchException().setReturnValue(0);
        }
        System.out.println("exit status=" + status);
        if (LoggerFactory.getLogger(AbstractEmulator.class).isDebugEnabled()) {
            emulator.attach().debug("exit status=" + status);
        }
        emulator.getBackend().emu_stop();
    }

    private static final int SIGKILL = 9;
    private static final int SIGSTOP = 19;
    private static final int SIG_ERR = -1;

    private final Map<Integer, SigAction> sigActionMap = new HashMap<>();

    @Override
    public MainTask createSignalHandlerTask(Emulator<?> emulator, int sig) {
        SigAction action = sigActionMap.get(sig);
        if (action != null) {
            return new SignalFunction(emulator, sig, action);
        }
        return super.createSignalHandlerTask(emulator, sig);
    }

    @Override
    protected int sigaction(Emulator<?> emulator, int signum, Pointer act, Pointer oldact) {
        SigAction action = SigAction.create(emulator, act);
        SigAction oldAction = SigAction.create(emulator, oldact);
        if (log.isDebugEnabled()) {
            log.debug("sigaction signum={}, action={}, oldAction={}", signum, action, oldAction);
        }
        if (SIGKILL == signum || SIGSTOP == signum) {
            if (oldAction != null) {
                oldAction.setSaHandler(SIG_ERR);
                oldAction.pack();
            }
            return -UnixEmulator.EINVAL;
        }
        SigAction lastAction = sigActionMap.put(signum, action);
        if (oldAction != null) {
            if (lastAction == null) {
                oldact.write(0, new byte[oldAction.size()], 0, oldAction.size());
            } else {
                oldAction.setSaHandler(lastAction.getSaHandler());
                oldAction.setSaRestorer(lastAction.getSaRestorer());
                oldAction.setFlags(lastAction.getFlags());
                oldAction.setMask(lastAction.getMask());
                oldAction.pack();
            }
        }
        return 0;
    }

    protected int kill(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int pid = context.getIntArg(0);
        int sig = context.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("kill pid={}, sig={}", pid, sig);
        }
        if (sig == 0) {
            return 0;
        }
        if (sig < 0 || sig > 64) {
            return -UnixEmulator.EINVAL;
        }
        Task task = emulator.get(Task.TASK_KEY);
        if ((pid == 0 || pid == emulator.getPid()) && task != null) {
            SigAction action = sigActionMap.get(sig);
            return processSignal(emulator.getThreadDispatcher(), sig, task, action, null);
        }
        throw new UnsupportedOperationException("kill pid=" + pid + ", sig=" + sig + ", LR=" + context.getLRPointer());
    }

    private int processSignal(ThreadDispatcher threadDispatcher, int sig, Task task, SigAction action, Pointer sig_info) {
        if (action != null) {
            SignalOps signalOps = task.isMainThread() ? threadDispatcher : task;
            SigSet sigMaskSet = signalOps.getSigMaskSet();
            SigSet sigPendingSet = signalOps.getSigPendingSet();
            if (sigMaskSet == null || !sigMaskSet.containsSigNumber(sig)) {
                task.addSignalTask(new SignalTask(sig, action, sig_info));
                throw new ThreadContextSwitchException().setReturnValue(0);
            } else if (sigPendingSet != null) {
                sigPendingSet.addSigNumber(sig);
            }
        }
        return 0;
    }

    protected int tgkill(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int tgid = context.getIntArg(0);
        int tid = context.getIntArg(1);
        int sig = context.getIntArg(2);
        if (log.isDebugEnabled()) {
            log.debug("tgkill tgid={}, tid={}, sig={}", tgid, tid, sig);
        }
        if (sig == 0) {
            return 0;
        }
        if (sig < 0 || sig > 64) {
            return -UnixEmulator.EINVAL;
        }
        SigAction action = sigActionMap.get(sig);
        if (threadDispatcherEnabled &&
                emulator.getThreadDispatcher().sendSignal(tid, sig, action == null || action.getSaHandler() == 0L ? null : new SignalTask(sig, action))) {
            throw new ThreadContextSwitchException().setReturnValue(0);
        }
        return 0;
    }

    protected int set_tid_address(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer tidptr = context.getPointerArg(0);
        if (log.isDebugEnabled()) {
            log.debug("set_tid_address tidptr={}", tidptr);
        }
        Task task = emulator.get(Task.TASK_KEY);
        if (task instanceof MarshmallowThread) {
            MarshmallowThread thread = (MarshmallowThread) task;
            thread.set_tid_address(tidptr);
        }
        return 0;
    }

    private int threadId;

    protected final int incrementThreadId(Emulator<?> emulator) {
        if (threadId == 0) {
            threadId = emulator.getPid();
        }
        return (++threadId) & 0xffff; // http://androidxref.com/6.0.1_r10/xref/bionic/libc/bionic/pthread_mutex.cpp#215
    }

    protected int nanosleep(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer req = context.getPointerArg(0);
        Pointer rem = context.getPointerArg(1);
        TimeSpec timeSpec = TimeSpec.createTimeSpec(emulator, req);
        if (log.isDebugEnabled()) {
            log.debug("nanosleep req={}, rem={}, timeSpec={}", req, rem, timeSpec);
        }
        RunnableTask runningTask = emulator.getThreadDispatcher().getRunningTask();
        if (threadDispatcherEnabled && runningTask != null) {
            runningTask.setWaiter(emulator, new NanoSleepWaiter(emulator, rem, timeSpec));
            throw new ThreadContextSwitchException().setReturnValue(0);
        } else {
            try {
                java.lang.Thread.sleep(timeSpec.toMillis());
            } catch (InterruptedException ignored) {
            }
            return 0;
        }
    }

    protected int fallocate(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        int mode = context.getIntArg(1);
        int offset = context.getIntArg(2);
        int len = context.getIntArg(3);
        if (log.isDebugEnabled()) {
            log.debug("fallocate fd={}, mode=0x{}, offset={}, len={}", fd, Integer.toHexString(mode), offset, len);
        }
        return 0;
    }

}
