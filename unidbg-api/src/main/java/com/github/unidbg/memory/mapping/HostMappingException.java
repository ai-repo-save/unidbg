package com.github.unidbg.memory.mapping;

import java.io.IOException;

public final class HostMappingException extends IOException {

    private final int errno;

    HostMappingException(String operation, int errno, String context) {
        super(operation + " failed: errno=" + errno + ", " + context);
        this.errno = errno;
    }

    public int getErrno() {
        return errno;
    }
}
