package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual SDK23 libc openat: an empty pathname fails ENOENT independently of dirfd.
 * https://man7.org/linux/man-pages/man2/openat.2.html
 */
public class OpenatIntegrationTest {
    @Test public void emptyPathDoesNotDereferenceDirectoryDescriptorOnEitherAbi() throws Exception {
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
            }
        }
    }
}
