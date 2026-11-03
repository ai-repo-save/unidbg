package com.github.unidbg.linux;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

/** The cache description a run copies reaches the CPU model as the register values it names. */
public class CacheDescriptionIntegrationTest {

    /** CTR_EL0 is 32 bits wide: the widest register value is accepted, a wider one is refused. */
    @Test
    public void cacheDescriptionIsLimitedTo32Bits() throws Exception {
        String previous = System.getProperty("unidbg.ctrEl0");
        try {
            System.setProperty("unidbg.ctrEl0", "0xffffffff");
            try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                    .addBackendFactory(new Unicorn2Factory(false)).build()) {
                assertNotNull(emulator);
            }
            System.setProperty("unidbg.ctrEl0", "0x184448004");
            assertThrows(IllegalArgumentException.class,
                    () -> AndroidEmulatorBuilder.for64Bit()
                            .addBackendFactory(new Unicorn2Factory(false)).build());
        } finally {
            if (previous == null) {
                System.clearProperty("unidbg.ctrEl0");
            } else {
                System.setProperty("unidbg.ctrEl0", previous);
            }
        }
    }
}
