package com.github.unidbg.linux.file;

import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.unix.UnixEmulator;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/** Host directory descriptor acquired when the guest directory is opened. */
final class DirectoryIdentity {
    private static final Logger log = LoggerFactory.getLogger(DirectoryIdentity.class);
    private static final AtomicLong IDENTITIES = new AtomicLong();
    private static final int O_DIRECTORY = 0x10000;
    private static final int O_CLOEXEC = 0x80000;
    private static final int O_PATH = 0x200000;
    private static final Calls CALLS = load();

    private final long id = IDENTITIES.incrementAndGet();
    private int fd;

    private DirectoryIdentity(int fd) {
        this.fd = fd;
    }

    static DirectoryIdentity open(Path path) {
        if (CALLS == null) {
            log.debug("event=directory_identity_unavailable path={} reason=host_platform", path);
            return null;
        }
        int opened = CALLS.open(path.toString(), O_DIRECTORY | O_CLOEXEC);
        if (opened < 0) {
            log.debug("event=directory_identity_unavailable path={} errno={}", path, Native.getLastError());
            return null;
        }
        DirectoryIdentity identity = new DirectoryIdentity(opened);
        log.debug("event=directory_identity_opened identity={} host_fd={} path={}", identity.id, opened, path);
        return identity;
    }

    synchronized Path resolve(String relative) {
        if (fd < 0) throw new LinuxErrnoException(UnixEmulator.EBADF);
        int child = CALLS.openat(fd, relative, O_PATH | O_CLOEXEC, 0);
        if (child < 0) {
            int errno = Native.getLastError();
            log.debug("event=directory_identity_resolve_failed identity={} relative={} errno={}", id, relative, errno);
            if (errno == 0) throw new IllegalStateException("openat failed without errno: identity=" + id + ", relative=" + relative);
            throw new LinuxErrnoException(errno);
        }
        try {
            Path resolved = Paths.get("/proc/self/fd/" + child).toRealPath();
            log.debug("event=directory_identity_resolved identity={} relative={} host={}", id, relative, resolved);
            return resolved;
        } catch (IOException failure) {
            throw new IllegalStateException("Reading directory anchor target: identity=" + id + ", relative=" + relative, failure);
        } finally {
            if (CALLS.close(child) < 0) {
                log.warn("event=directory_identity_probe_close_failed identity={} host_fd={} errno={}",
                        id, child, Native.getLastError());
            }
        }
    }

    synchronized void close() {
        if (fd < 0) return;
        int closing = fd;
        fd = -1;
        if (CALLS.close(closing) < 0) {
            int errno = Native.getLastError();
            IllegalStateException failure = new IllegalStateException("Closing directory identity " + id + ": errno=" + errno);
            log.warn("event=directory_identity_close_failed identity={} host_fd={} errno={}", id, closing, errno, failure);
            throw failure;
        }
        log.debug("event=directory_identity_closed identity={} host_fd={}", id, closing);
    }

    private static Calls load() {
        if (!Platform.isLinux() || !Platform.isIntel() || !Platform.is64Bit() || Native.LONG_SIZE != 8) return null;
        return Native.load(Platform.C_LIBRARY_NAME, Calls.class);
    }

    private interface Calls extends Library {
        int open(String path, int flags);
        int openat(int dirfd, String path, int flags, int mode);
        int close(int fd);
    }
}
