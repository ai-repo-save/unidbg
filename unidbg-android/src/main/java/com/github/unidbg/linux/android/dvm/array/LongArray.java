package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.linux.android.dvm.VM;
import com.sun.jna.Pointer;

/** JNI long array retaining its original host backing array. */
public class LongArray extends CopiedPrimitiveArray<long[]> {
    public LongArray(VM vm, long[] value) {
        super(vm, "[J", value, 8);
    }

    @Override
    public int length() { return value.length; }

    @Override
    protected void copyTo(Pointer pointer) {
        for (int i = 0; i < value.length; i++) pointer.setLong(i * 8L, value[i]);
    }

    @Override
    protected void copyFrom(Pointer pointer) {
        for (int i = 0; i < value.length; i++) value[i] = pointer.getLong(i * 8L);
    }
}
