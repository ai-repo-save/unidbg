package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.linux.android.dvm.VM;
import com.sun.jna.Pointer;

/** JNI boolean array retaining its original host backing array. */
public class BooleanArray extends CopiedPrimitiveArray<boolean[]> {
    public BooleanArray(VM vm, boolean[] value) {
        super(vm, "[Z", value, 1);
    }

    @Override
    public int length() { return value.length; }

    @Override
    protected void copyTo(Pointer pointer) {
        for (int i = 0; i < value.length; i++) pointer.setByte(i, (byte) (value[i] ? 1 : 0));
    }

    @Override
    protected void copyFrom(Pointer pointer) {
        for (int i = 0; i < value.length; i++) value[i] = pointer.getByte(i) != 0;
    }
}
