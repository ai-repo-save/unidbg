package com.github.unidbg.file;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The paths a recording answers with a refusal.
 *
 * A root directory holds files and cannot state that a process may not read one: the device a
 * recording comes from refuses some paths to the context it ran in while the bytes are the ones
 * another context read, and a path refused before it exists has no bytes at all. A caller that
 * holds that recording declares the answer here, and the file system gives it for the path: the
 * guest reads nothing there and gets the errno the recording reports.
 *
 * A refusal answers a read and a listing, not the facts of the path: the kernel checks permission
 * when the bytes are handed out, so {@code stat} still describes the file, which is why a probe
 * that stats before it reads still reaches the refusal.
 */
public final class PathRefusals {

    private final Map<String, Integer> errno = new LinkedHashMap<String, Integer>();

    /** States that a read of this path answers {@code errno}. */
    public void declare(String pathname, int errno) {
        if (pathname == null || !pathname.startsWith("/")) {
            throw new IllegalArgumentException("refused path=" + pathname);
        }
        if (errno <= 0) throw new IllegalArgumentException("refused errno=" + errno);
        this.errno.put(pathname, errno);
    }

    /** The errno a read of this path answers, or {@code 0} when no recording refused it. */
    public int refused(String pathname) {
        Integer refused = errno.get(pathname);
        return refused == null ? 0 : refused;
    }
}
