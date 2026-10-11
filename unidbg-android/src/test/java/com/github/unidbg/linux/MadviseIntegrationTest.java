package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/** Integration correctness and allocator regression for private anonymous MADV_DONTNEED.
 * https://man7.org/linux/man-pages/man2/madvise.2.html
 */
public class MadviseIntegrationTest {
    @Test
    public void discardZeroesPagesWithoutUnmappingOnBothArchitectures() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                AndroidResolver resolver = new AndroidResolver(23);
                emulator.getMemory().setLibraryResolver(resolver);
                Module libc = emulator.getMemory().load(resolver.resolveLibrary(emulator, "libc.so"), false);
                UnidbgPointer memory = emulator.getMemory().mmap(4 * 4096, 3);
                long address = UnidbgPointer.nativeValue(memory);
                byte[] dirty = new byte[4 * 4096];
                Arrays.fill(dirty, (byte) 0x5a);
                try {
                    memory.write(0, dirty, 0, dirty.length);
                    // Linux rounds the length up; the surrounding pages retain their bytes.
                    assertEquals(0, libc.findSymbolByName("madvise").call(emulator, memory.share(4096), 4097, 4).intValue());
                    byte[] expected = dirty.clone();
                    Arrays.fill(expected, 4096, 3 * 4096, (byte) 0);
                    assertArrayEquals(expected, memory.getByteArray(0, expected.length));
                    memory.setInt(4096, 0x12345678);
                    expected[4096] = 0x78; expected[4097] = 0x56; expected[4098] = 0x34; expected[4099] = 0x12;
                    assertArrayEquals(expected, memory.getByteArray(0, expected.length));
                    assertEquals(-1, libc.findSymbolByName("madvise").call(emulator, memory.share(1), 4096, 4).intValue());
                    UnidbgPointer errno = UnidbgPointer.pointer(emulator, libc.findSymbolByName("__errno").call(emulator).longValue());
                    assertEquals(22, errno.getInt(0));
                    assertArrayEquals(expected, memory.getByteArray(0, expected.length));
                    // Advice about access patterns must not discard storage.
                    for (int advice : new int[] {0, 1, 2, 3}) {
                        assertEquals(0, libc.findSymbolByName("madvise").call(emulator, memory, dirty.length, advice).intValue());
                        assertArrayEquals(expected, memory.getByteArray(0, expected.length));
                    }
                } finally {
                    emulator.getMemory().munmap(address, dirty.length);
                }
            }
        }
    }
}
