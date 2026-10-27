package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.pointer.UnidbgPointer;
import unicorn.Arm64Const;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class ArmVarArg64 extends ArmVarArg {
    private int general = 3;
    private int floating;
    private long stackOffset;
    private final UnidbgPointer stack;

    ArmVarArg64(Emulator<?> emulator, BaseVM vm, DvmMethod method) {
        super(emulator, vm, method);
        stack = emulator.getContext().getStackPointer();
        for (Shorty shorty : shorties) {
            switch (shorty.getType()) {
                case 'L': case 'B': case 'C': case 'I': case 'S': case 'Z':
                    args.add((int) nextGeneral());
                    break;
                case 'J':
                    args.add(nextGeneral());
                    break;
                case 'F':
                    args.add((float) nextFloating());
                    break;
                case 'D':
                    args.add(nextFloating());
                    break;
                default:
                    throw new IllegalStateException("c=" + shorty.getType());
            }
        }
    }

    private long nextGeneral() {
        if (general < 8) return emulator.getContext().getLongArg(general++);
        long value = stack.getLong(stackOffset);
        stackOffset += 8;
        return value;
    }

    private double nextFloating() {
        if (floating < 8) {
            byte[] vector = emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0 + floating++);
            return ByteBuffer.wrap(vector).order(ByteOrder.LITTLE_ENDIAN).getDouble();
        }
        double value = stack.getDouble(stackOffset);
        stackOffset += 8;
        return value;
    }
}
