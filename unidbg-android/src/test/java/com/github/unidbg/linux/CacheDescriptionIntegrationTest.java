package com.github.unidbg.linux;

import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** The cache description a run copies reaches the CPU model as the register values it names. */
public class CacheDescriptionIntegrationTest {

    @Test
    public void cacheDescriptionOutside32BitsIsRefused() {
        String previous = System.getProperty("unidbg.ctrEl0");
        System.setProperty("unidbg.ctrEl0", "0x184448004");
        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> AndroidEmulatorBuilder.for64Bit()
                            .addBackendFactory(new Unicorn2Factory(false)).build());
            assertEquals("unidbg.ctrEl0 must be a 32-bit unsigned value: 0x184448004",
                    failure.getMessage());
        } finally {
            if (previous == null) {
                System.clearProperty("unidbg.ctrEl0");
            } else {
                System.setProperty("unidbg.ctrEl0", previous);
            }
        }
    }
}
