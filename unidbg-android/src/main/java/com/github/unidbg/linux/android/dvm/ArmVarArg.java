package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.pointer.UnidbgPointer;

public abstract class ArmVarArg extends VarArg {

    static VarArg create(Emulator<?> emulator, BaseVM vm, DvmMethod method) {
        return create(emulator, vm, method, 3);
    }

    static VarArg create(Emulator<?> emulator, BaseVM vm, DvmMethod method, int firstArgument) {
        return emulator.is64Bit() ? new ArmVarArg64(emulator, vm, method, firstArgument)
                : new ArmVarArg32(emulator, vm, method, firstArgument);
    }

    protected final Emulator<?> emulator;
    protected final int firstArgument;

    protected ArmVarArg(Emulator<?> emulator, BaseVM vm, DvmMethod method, int firstArgument) {
        super(vm, method);
        this.emulator = emulator;
        this.firstArgument = firstArgument;
    }

    protected final UnidbgPointer getArg(int index) {
        return emulator.getContext().getPointerArg(firstArgument + index);
    }

    protected final int getInt(int index) {
        UnidbgPointer ptr = getArg(index);
        return ptr == null ? 0 : ptr.toIntPeer();
    }

}
