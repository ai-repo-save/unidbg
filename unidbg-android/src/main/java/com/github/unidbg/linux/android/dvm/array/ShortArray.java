package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.linux.android.dvm.VM;
import com.sun.jna.Pointer;

public class ShortArray extends CopiedPrimitiveArray<short[]> {
    public ShortArray(VM vm, short[] value) {
        super(vm, "[S", value, 2);
    }

    @Override
    public int length() { return value.length; }

    public void setValue(short[] value) { super.value = value; }

    @Override
    protected void copyTo(Pointer pointer) {
        pointer.write(0, value, 0, value.length);
    }

    @Override
    protected void copyFrom(Pointer pointer) {
        for (int index = 0; index < value.length; index++) value[index] = pointer.getShort(index * 2L);
    }
}
