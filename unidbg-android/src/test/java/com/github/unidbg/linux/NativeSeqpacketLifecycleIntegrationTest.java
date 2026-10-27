package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class NativeSeqpacketLifecycleIntegrationTest {
    @Test
    public void nativeLifecycle() throws Exception {
        String libraryPath = System.getProperty("unidbg.nativeIpcLibrary");
        if (libraryPath == null || libraryPath.isEmpty()) {
            throw new IllegalArgumentException("Required property: -Dunidbg.nativeIpcLibrary=<ARM64 native fixture>");
        }
        File library = new File(libraryPath);
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(true)).build()) {
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
            emulator.getSyscallHandler().setEnableThreadDispatcher(true);
            Module module = emulator.loadLibrary(library, false);
            assertEquals(0, module.callFunction(emulator, "ipc_start").intValue());
            assertEquals(0, module.findSymbolByName("ipc_progress").createPointer(emulator).getInt(0));
            emulator.getThreadDispatcher().runThreads(1, TimeUnit.SECONDS);
            assertEquals(1, module.findSymbolByName("ipc_progress").createPointer(emulator).getInt(0));
            assertEquals(0, emulator.getThreadDispatcher().getTaskCount());
        }
    }
}
