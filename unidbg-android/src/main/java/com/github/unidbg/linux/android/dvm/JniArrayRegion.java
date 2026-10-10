package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.context.RegisterContext;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared ARM32/ARM64 implementation of JNI Get<PrimitiveType>ArrayRegion. */
final class JniArrayRegion {
    private static final Logger log = LoggerFactory.getLogger(JniArrayRegion.class);

    enum Type { Boolean, Byte, Char, Short, Int, Long, Float, Double }

    static long read(BaseVM vm, Emulator<?> emulator, Type type) {
        RegisterContext context = emulator.getContext();
        DvmObject<?> array = vm.getObject(context.getIntArg(1));
        Object value = array.getValue();
        int start = context.getIntArg(2);
        int length = context.getIntArg(3);
        Pointer buf = context.getPointerArg(4);
        int size = java.lang.reflect.Array.getLength(value);
        if (vm.verbose || vm.verboseFieldOperation) {
            System.out.printf("JNIEnv->Get%sArrayRegion(%s, %d, %d, %s) was called from %s%n",
                    type, array, start, length, buf, context.getLRPointer());
        }
        log.debug("Get{}ArrayRegion array={}, start={}, length={}, buf={}, lr={}",
                type, value, start, length, buf, context.getLRPointer());
        // Subtraction avoids signed jsize overflow. Validate before any write.
        if (start < 0 || length < 0 || start > size || length > size - start) {
            log.warn("JniArrayRegionOutOfBounds type={}, start={}, length={}, arrayLength={}, lr={}",
                    type, start, length, size, context.getLRPointer());
            vm.throwException(vm.resolveClass("java/lang/ArrayIndexOutOfBoundsException")
                    .newObject("start=" + start + ", length=" + length + ", arrayLength=" + size));
            return 0;
        }
        if (length == 0) {
            return 0;
        }
        switch (type) {
            case Boolean:
                boolean[] booleans = (boolean[]) value;
                for (int i = 0; i < length; i++) buf.setByte(i, (byte) (booleans[start + i] ? 1 : 0));
                break;
            case Byte:
                buf.write(0, (byte[]) value, start, length);
                break;
            case Char:
                char[] chars = (char[]) value;
                for (int i = 0; i < length; i++) buf.setShort(i * 2L, (short) chars[start + i]);
                break;
            case Short:
                buf.write(0, (short[]) value, start, length);
                break;
            case Int:
                buf.write(0, (int[]) value, start, length);
                break;
            case Long:
                buf.write(0, (long[]) value, start, length);
                break;
            case Float:
                float[] floats = (float[]) value;
                for (int i = 0; i < length; i++) buf.setInt(i * 4L, Float.floatToRawIntBits(floats[start + i]));
                break;
            case Double:
                double[] doubles = (double[]) value;
                for (int i = 0; i < length; i++) buf.setLong(i * 8L, Double.doubleToRawLongBits(doubles[start + i]));
                break;
        }
        return 0;
    }

    private JniArrayRegion() {
    }
}
