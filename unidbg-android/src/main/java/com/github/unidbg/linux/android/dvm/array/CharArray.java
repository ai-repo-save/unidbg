package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.linux.android.dvm.VM;
import com.sun.jna.Pointer;

/** JNI char array retaining its original host backing array. */
public class CharArray extends CopiedPrimitiveArray<char[]> {
    public CharArray(VM vm, char[] value) {
        super(vm, "[C", value, 2);
    }

    @Override
    public int length() { return value.length; }

    @Override
    protected void copyTo(Pointer pointer) {
        for (int i = 0; i < value.length; i++) pointer.setShort(i * 2L, (short) value[i]);
    }

    @Override
    protected void copyFrom(Pointer pointer) {
        for (int i = 0; i < value.length; i++) value[i] = (char) (pointer.getShort(i * 2L) & 0xffff);
    }
}
