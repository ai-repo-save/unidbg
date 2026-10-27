package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.memory.MemoryBlock;
import org.junit.Test;
import static org.junit.Assert.*;

/** SDK23 Bionic translates Linux syscall errors into the calling thread's errno.
 * https://man7.org/linux/man-pages/man2/openat.2.html
 * https://man7.org/linux/man-pages/man2/read.2.html
 */
public class OpenatIntegrationTest {
    @Test public void bionicErrorsPreserveErrnoOnEitherAbi() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                AndroidResolver resolver = new AndroidResolver(23);
                emulator.getMemory().setLibraryResolver(resolver);
                emulator.getMemory().setCallInitFunction(false);
                Module libc = emulator.getMemory().load(resolver.resolveLibrary(emulator, "libc.so"), false);
                for (int dirfd : new int[] {-100, 0, -1, 12345}) {
                    assertEquals(-1, libc.findSymbolByName("openat").call(emulator, dirfd, "", 0, 0).intValue());
                    UnidbgPointer errno = UnidbgPointer.pointer(emulator, libc.findSymbolByName("__errno").call(emulator).longValue());
                    assertEquals(2, errno.getInt(0));
                }
                MemoryBlock buffer = emulator.getMemory().malloc(256, true);
                try {
                    String[] operations = {"read", "write", "close", "lseek", "fcntl", "ioctl", "fstat"};
                    for (String operation : operations) {
                        Object[] arguments;
                        switch (operation) {
                            case "read":
                            case "write":
                                arguments = new Object[] {-1, buffer.getPointer(), 1};
                                break;
                            case "close":
                                arguments = new Object[] {-1};
                                break;
                            case "fstat":
                                arguments = new Object[] {-1, buffer.getPointer()};
                                break;
                            case "fcntl":
                                arguments = new Object[] {-1, 3, 0};
                                break;
                            default:
                                arguments = new Object[] {-1, 0, 0};
                                break;
                        }
                        assertEquals(operation, -1, libc.findSymbolByName(operation).call(emulator, arguments).intValue());
                        UnidbgPointer errno = UnidbgPointer.pointer(emulator, libc.findSymbolByName("__errno").call(emulator).longValue());
                        assertEquals(operation, 9, errno.getInt(0));
                    }
                } finally {
                    buffer.free();
                }
            }
        }
    }
}
