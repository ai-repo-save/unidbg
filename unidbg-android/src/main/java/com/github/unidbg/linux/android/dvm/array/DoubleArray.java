package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.linux.android.dvm.VM;
import com.sun.jna.Pointer;

public class DoubleArray extends CopiedPrimitiveArray<double[]> {
    public DoubleArray(VM vm, double[] value) {
        super(vm, "[D", value, 8);
    }

    @Override
    public int length() { return value.length; }

    public void setValue(double[] value) { super.value = value; }

    @Override
    protected void copyTo(Pointer pointer) {
        for (int index = 0; index < value.length; index++)
            pointer.setLong(index * 8L, Double.doubleToRawLongBits(value[index]));
    }

    @Override
    protected void copyFrom(Pointer pointer) {
        for (int index = 0; index < value.length; index++)
            value[index] = Double.longBitsToDouble(pointer.getLong(index * 8L));
    }
}
