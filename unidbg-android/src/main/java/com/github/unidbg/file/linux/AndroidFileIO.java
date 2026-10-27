package com.github.unidbg.file.linux;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.NewFileIO;
import com.github.unidbg.linux.struct.StatFS;
import com.sun.jna.Pointer;
import java.util.function.ToIntFunction;

/**
 * Scalar operations return Linux kernel syscall results, including negative errno values.
 * Bionic translates those values into libc results and thread-local errno.
 */
public interface AndroidFileIO extends NewFileIO {

    int SIOCGIFNAME = 0x8910;		/* get iface name		*/
    int SIOCGIFCONF = 0x8912;		/* get iface list		*/
    int SIOCGIFFLAGS = 0x8913;		/* get flags			*/

    int fstat(Emulator<?> emulator, StatStructure stat);

    int getdents64(Pointer dirp, int size);

    /** Installer runs on the restored backend owner; returns a guest fd or raw negative errno. */
    int accept(Pointer addr, Pointer addrlen, int flags, ToIntFunction<AndroidFileIO> installer);

    int statfs(StatFS statFS);
}
