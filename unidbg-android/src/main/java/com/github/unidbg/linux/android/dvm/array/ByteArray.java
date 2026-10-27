package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.linux.android.dvm.VM;
import com.sun.jna.Pointer;
import org.apache.commons.codec.binary.Hex;

public class ByteArray extends CopiedPrimitiveArray<byte[]> {
    public ByteArray(VM vm, byte[] value) {
        super(vm, "[B", value, 1);
    }

    @Override
    public int length() { return value.length; }

    public void setValue(byte[] value) { super.value = value; }

    @Override
    protected void copyTo(Pointer pointer) {
        pointer.write(0, value, 0, value.length);
    }

    @Override
    protected void copyFrom(Pointer pointer) {
        pointer.read(0, value, 0, value.length);
    }

    @Override
    public String toString() {
        if (value != null && value.length <= 64) {
            return "[B@0x" + Hex.encodeHexString(value);
        } else {
            return super.toString();
        }
    }
}
