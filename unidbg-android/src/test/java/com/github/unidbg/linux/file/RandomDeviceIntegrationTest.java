package com.github.unidbg.linux.file;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.struct.Stat32;
import com.github.unidbg.linux.struct.Stat64;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Integration regression: SDK23 getentropy must reach the random-device provider.
 * Device ABI authority: https://man7.org/linux/man-pages/man4/random.4.html
 */
public class RandomDeviceIntegrationTest {
    private static byte value(int index) {
        return (byte) ((index * 31) ^ (index >>> 8));
    }

    @Test
    public void bionicRandomDeviceContractOnBothArchitectures() throws Exception {
        for (boolean is64Bit : new boolean[] {false, true}) {
            AndroidEmulatorBuilder builder = is64Bit
                    ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            try (AndroidEmulator emulator = builder.addBackendFactory(new Unicorn2Factory(true)).build()) {
                AndroidResolver resolver = new AndroidResolver(23);
                emulator.getMemory().setLibraryResolver(resolver);
                AtomicInteger entropyBytes = new AtomicInteger();
                emulator.getSyscallHandler().addIOResolver((target, path, flags) -> {
                    if (!path.equals("/dev/random") && !path.equals("/dev/urandom") && !path.equals("/dev/srandom")) {
                        return null;
                    }
                    return FileResult.success(new RandomFileIO(target, path) {
                        private int index;
                        @Override
                        protected void randBytes(byte[] bytes) {
                            entropyBytes.addAndGet(bytes.length);
                            for (int i = 0; i < bytes.length; i++) {
                                bytes[i] = value(index++);
                            }
                        }
                    });
                });
                Module libc = emulator.getMemory().load(resolver.resolveLibrary(emulator, "libc.so"), false);
                MemoryBlock block = emulator.getMemory().malloc(8193, true);
                try {
                    UnidbgPointer output = block.getPointer();
                    for (String path : new String[] {"/dev/random", "/dev/urandom", "/dev/srandom"}) {
                        int fd = libc.findSymbolByName("open").call(emulator, path, 0).intValue();
                        assertEquals(0, libc.findSymbolByName("fstat").call(emulator, fd, output).intValue());
                        StatStructure stat = is64Bit ? new Stat64(output) : new Stat32(output);
                        stat.unpack();
                        assertEquals(0020000, stat.st_mode & 0170000);
                        output.setInt(0, -1);
                        output.setInt(4, 0x12345678);
                        assertEquals(0, libc.findSymbolByName("ioctl").call(emulator, fd, 0x80045200, output).intValue());
                        assertEquals(0, output.getInt(0)); // No entropy-accounting input pool is modeled.
                        assertEquals(0x12345678, output.getInt(4)); // Linux ioctl writes exactly one int.
                        assertEquals(8193, libc.findSymbolByName("read").call(emulator, fd, output, 8193).intValue());
                        byte[] expected = new byte[8193];
                        for (int i = 0; i < expected.length; i++) expected[i] = value(i);
                        assertArrayEquals(expected, output.getByteArray(0, expected.length));
                        assertEquals(0, libc.findSymbolByName("close").call(emulator, fd).intValue());
                    }
                    entropyBytes.set(0);
                    // SDK23 OpenBSD arc4random.c seeds with 32 key bytes + 8 IV bytes
                    // through its hidden getentropy function. Failure never calls read.
                    libc.findSymbolByName("arc4random_buf").call(emulator, output, 40);
                    assertEquals(40, entropyBytes.get());
                    libc.findSymbolByName("arc4random").call(emulator);
                    assertEquals(40, entropyBytes.get());
                } finally {
                    block.free();
                }
            }
        }
    }
}
