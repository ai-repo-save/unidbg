package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.linux.android.dvm.VM;
import com.sun.jna.Pointer;

public class FloatArray extends CopiedPrimitiveArray<float[]> {
    public FloatArray(VM vm, float[] value) {
        super(vm, "[F", value, 4);
    }

    @Override
    public int length() { return value.length; }

    public void setValue(float[] value) { super.value = value; }

    @Override
    protected void copyTo(Pointer pointer) {
        for (int index = 0; index < value.length; index++)
            pointer.setInt(index * 4L, Float.floatToRawIntBits(value[index]));
    }

    @Override
    protected void copyFrom(Pointer pointer) {
        for (int index = 0; index < value.length; index++)
            value[index] = Float.intBitsToFloat(pointer.getInt(index * 4L));
    }
}
