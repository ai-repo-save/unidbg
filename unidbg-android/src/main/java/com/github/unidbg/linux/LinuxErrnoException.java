package com.github.unidbg.linux;

/** Carries a Linux errno when a file operation returns an object rather than a scalar syscall result. */
public final class LinuxErrnoException extends RuntimeException {

    private final int errno;

    public LinuxErrnoException(int errno) {
        this.errno = errno;
    }

    public LinuxErrnoException(int errno, Throwable cause) {
        super(cause);
        this.errno = errno;
    }

    public int getErrno() {
        return errno;
    }
}
