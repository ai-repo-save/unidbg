package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.file.ByteArrayFileIO;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.memory.MemoryBlock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression: a lower-priority ENOENT must not replace an explicit EINTR response. */
public class ResolverPriorityIntegrationTest {
    @Test public void nativeOpenPreservesFirstFailureAndCanRetryOnBothAbis() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                AndroidResolver libraries = new AndroidResolver(23);
                emulator.getMemory().setLibraryResolver(libraries);
                emulator.getMemory().setCallInitFunction(false);
                Module libc = emulator.getMemory().load(libraries.resolveLibrary(emulator, "libc.so"), false);
                String path = "/proc/bus/pci/devices";
                emulator.getSyscallHandler().addIOResolver((emu, name, flags) ->
                        path.equals(name) ? FileResult.failed(2) : null);
                AtomicInteger calls = new AtomicInteger();
                emulator.getSyscallHandler().addIOResolver((emu, name, flags) -> {
                    if (!path.equals(name)) return null;
                    if (calls.getAndIncrement() == 0) return FileResult.failed(4);
                    return FileResult.success(new ByteArrayFileIO(flags, name, new byte[] {49, 9, 50}));
                });
                assertEquals(-1, libc.findSymbolByName("openat").call(emulator, 0, path, 0, 0).intValue());
                UnidbgPointer errno = UnidbgPointer.pointer(emulator, libc.findSymbolByName("__errno").call(emulator).longValue());
                assertEquals("original high-priority EINTR, not later ENOENT", 4, errno.getInt(0));
                int fd = libc.findSymbolByName("openat").call(emulator, 0, path, 0, 0).intValue();
                assertTrue(fd >= 0);
                MemoryBlock buffer = emulator.getMemory().malloc(3, true);
                try {
                    assertEquals(3, libc.findSymbolByName("read").call(emulator, fd, buffer.getPointer(), 3).intValue());
                    assertArrayEquals(new byte[] {49, 9, 50}, buffer.getPointer().getByteArray(0, 3));
                } finally { buffer.free(); }
                assertEquals(0, libc.findSymbolByName("close").call(emulator, fd).intValue());
                assertEquals(2, calls.get());
            }
        }
    }
}
