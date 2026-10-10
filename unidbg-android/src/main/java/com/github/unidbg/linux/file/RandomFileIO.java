package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.unix.IO;
import com.sun.jna.Pointer;

import java.security.SecureRandom;

public class RandomFileIO extends DriverFileIO {

    public RandomFileIO(Emulator<?> emulator, String path) {
        super(emulator, IOConstants.O_RDONLY, path);
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long RNDGETENTCNT = 0x80045200L;

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        stat.st_mode = IO.S_IFCHR | 0666;
        stat.st_nlink = 1;
        return super.fstat(emulator, stat);
    }

    @Override
    public int ioctl(Emulator<?> emulator, long request, long argp) {
        // Linux ioctl commands are unsigned 32-bit values, also on ARM64.
        if ((request & 0xffffffffL) == RNDGETENTCNT) {
            // This virtual device has no entropy-accounting input pool. Do not
            // invent a host entropy count: urandom reads also work at zero bits.
            UnidbgPointer.pointer(emulator, argp).setInt(0, 0);
            return 0;
        }
        return super.ioctl(emulator, request, argp);
    }

    @Override
    public int read(Backend backend, Pointer buffer, int count) {
        int total = 0;
        byte[] buf = new byte[Math.min(0x1000, count)];
        Pointer pointer = buffer;
        while (total < count) {
            int read = Math.min(buf.length, count - total);
            if (read != buf.length) {
                buf = new byte[read];
            }
            randBytes(buf);
            pointer.write(0, buf, 0, read);
            total += read;
            pointer = pointer.share(read);
        }
        return total;
    }

    protected void randBytes(byte[] bytes) {
        RANDOM.nextBytes(bytes);
    }
}
