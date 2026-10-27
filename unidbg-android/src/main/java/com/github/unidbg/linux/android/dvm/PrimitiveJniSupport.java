package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** ABI result encoding shared by backend models and host-call completions. */
final class PrimitiveJniSupport {
    static long result(Emulator<?> emulator, char type, Number value) {
        if (emulator.is64Bit() && (type == 'F' || type == 'D')) {
            ByteBuffer vector = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            if (type == 'F') vector.putFloat(value.floatValue());
            else vector.putDouble(value.doubleValue());
            emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, vector.array());
            return emulator.getContext().getLongArg(0);
        }
        long bits = type == 'F' ? Float.floatToRawIntBits(value.floatValue()) & 0xffffffffL
                : type == 'D' ? Double.doubleToRawLongBits(value.doubleValue()) : value.longValue();
        if (emulator.is32Bit() && (type == 'J' || type == 'D'))
            emulator.getBackend().reg_write(ArmConst.UC_ARM_REG_R1, (int) (bits >>> 32));
        return bits;
    }

    private PrimitiveJniSupport() {}
}
