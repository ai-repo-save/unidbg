package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.linux.android.dvm.VM;
import com.sun.jna.Pointer;

public class IntArray extends CopiedPrimitiveArray<int[]> {
    public IntArray(VM vm, int[] value) {
        super(vm, "[I", value, 4);
    }

    @Override
    public int length() { return value.length; }

    public void setValue(int[] value) { super.value = value; }

    @Override
    protected void copyTo(Pointer pointer) {
        pointer.write(0, value, 0, value.length);
    }

    @Override
    protected void copyFrom(Pointer pointer) {
        pointer.read(0, value, 0, value.length);
    }
}
