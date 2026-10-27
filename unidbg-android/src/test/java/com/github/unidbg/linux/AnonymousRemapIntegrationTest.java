package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Explicit-only Bionic mremap integration: https://man7.org/linux/man-pages/man2/mremap.2.html */
public class AnonymousRemapIntegrationTest {
    @Test
    public void anonymousResizePreservesPagesAndFailureOnBothAbis() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = wide ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                AndroidResolver resolver = new AndroidResolver(23);
                emulator.getMemory().setLibraryResolver(resolver);
                emulator.getMemory().setCallInitFunction(false);
                Module libc = emulator.getMemory().load(resolver.resolveLibrary(emulator, "libc.so"), false);
                int page = emulator.getPageAlign();
                long originalBytes = emulator.getBackend().getMemAllocatedSize();
                long address = address(wide, libc.findSymbolByName("mmap").call(emulator, 0, 3 * page, 3, 0x22, -1, 0));
                assertEquals(0, address % page);
                byte[] original = new byte[3 * page];
                Arrays.fill(original, (byte) 0xa5);
                emulator.getBackend().mem_write(address, original);
                int mappedSize = 3 * page;
                try {
                    long grown = address(wide, libc.findSymbolByName("mremap").call(emulator, UnidbgPointer.pointer(emulator, address), mappedSize, 4 * page, 1));
                    assertEquals(0, grown % page);
                    address = grown;
                    mappedSize = 4 * page;
                    byte[] expected = Arrays.copyOf(original, mappedSize);
                    assertArrayEquals(expected, emulator.getBackend().mem_read(address, mappedSize));
                    assertEquals(-1, libc.findSymbolByName("mremap").call(emulator, UnidbgPointer.pointer(emulator, address), mappedSize, 0, 1).intValue());
                    UnidbgPointer errno = UnidbgPointer.pointer(emulator, libc.findSymbolByName("__errno").call(emulator).longValue());
                    assertEquals(22, errno.getInt(0));
                    assertArrayEquals(expected, emulator.getBackend().mem_read(address, mappedSize));
                    long shrunk = address(wide, libc.findSymbolByName("mremap").call(emulator, UnidbgPointer.pointer(emulator, address), mappedSize, page + 1, 1));
                    assertEquals(0, shrunk % page);
                    address = shrunk;
                    mappedSize = 2 * page;
                    assertArrayEquals(Arrays.copyOf(original, mappedSize), emulator.getBackend().mem_read(address, mappedSize));
                } finally {
                    assertEquals(0, libc.findSymbolByName("munmap").call(emulator, UnidbgPointer.pointer(emulator, address), mappedSize).intValue());
                }
                assertEquals(originalBytes, emulator.getBackend().getMemAllocatedSize());
            }
        }
    }

    private static long address(boolean wide, Number result) {
        return wide ? result.longValue() : Integer.toUnsignedLong(result.intValue());
    }
}
