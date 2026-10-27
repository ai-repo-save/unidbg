package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.sun.jna.Pointer;

import java.io.ByteArrayOutputStream;

final class JniStringSupport {
    static String readModifiedUtf8(Pointer pointer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (long offset = 0;; offset++) {
            int value = pointer.getByte(offset) & 0xff;
            if (value == 0) return ModifiedUtf8.decode(bytes.toByteArray());
            bytes.write(value);
        }
    }

    static Pointer create(Emulator<?> emulator, BaseVM vm, boolean utf) {
        return JniInvocationSupport.register(emulator, vm, () -> {
            RegisterContext context = emulator.getContext();
            Pointer input = context.getPointerArg(1);
            String value;
            if (utf) {
                if (input == null) return VM.JNI_NULL;
                value = readModifiedUtf8(input);
            } else {
                int length = context.getIntArg(2);
                if (length < 0) throw new IllegalArgumentException("Negative JNI string length: " + length);
                char[] units = new char[length];
                if (length > 0 && input == null) throw new IllegalArgumentException("Missing JNI UTF-16 input");
                for (int index = 0; index < length; index++) units[index] = (char) (input.getShort(index * 2L) & 0xffff);
                value = new String(units);
            }
            return vm.addLocalObject(new StringObject(vm, value));
        });
    }

    static Pointer length(Emulator<?> emulator, BaseVM vm, boolean utf) {
        return JniInvocationSupport.register(emulator, vm, () -> {
            String value = ((StringObject) vm.resolveJniObject(emulator.getContext().getIntArg(1))).getValue();
            return utf ? ModifiedUtf8.encodedLength(value) : value.length();
        });
    }

    static Pointer borrow(Emulator<?> emulator, BaseVM vm, boolean utf) {
        return JniInvocationSupport.register(emulator, vm, () -> {
            RegisterContext context = emulator.getContext();
            StringObject string = vm.resolveJniObject(context.getIntArg(1));
            return string.borrow(emulator, context.getPointerArg(2), utf).peer;
        });
    }

    static Pointer release(Emulator<?> emulator, BaseVM vm, boolean utf) {
        return JniInvocationSupport.register(emulator, vm, () -> {
            RegisterContext context = emulator.getContext();
            StringObject string = vm.resolveJniObject(context.getIntArg(1));
            string.release(context.getPointerArg(2), utf);
            return 0;
        });
    }

    static Pointer region(Emulator<?> emulator, BaseVM vm, boolean utf) {
        return JniInvocationSupport.register(emulator, vm, () -> {
            RegisterContext context = emulator.getContext();
            String value = ((StringObject) vm.resolveJniObject(context.getIntArg(1))).getValue();
            int start = context.getIntArg(2), length = context.getIntArg(3);
            if (start < 0 || length < 0 || start > value.length() || length > value.length() - start) {
                vm.throwException(ProxyDvmObject.createObject(vm, new StringIndexOutOfBoundsException(
                        "start=" + start + ", length=" + length + ", size=" + value.length())));
                return 0;
            }
            if (length == 0) return 0;
            Pointer output = context.getPointerArg(4);
            if (utf) {
                byte[] bytes = ModifiedUtf8.encodeRegion(value, start, length);
                output.write(0, bytes, 0, bytes.length);
            } else {
                for (int index = 0; index < length; index++) output.setShort(index * 2L, (short) value.charAt(start + index));
            }
            return 0;
        });
    }

    private JniStringSupport() {}
}
