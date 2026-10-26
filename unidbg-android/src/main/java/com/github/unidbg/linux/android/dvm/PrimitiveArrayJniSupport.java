package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.linux.android.dvm.array.BooleanArray;
import com.github.unidbg.linux.android.dvm.array.CharArray;
import com.github.unidbg.linux.android.dvm.array.LongArray;
import com.github.unidbg.linux.android.dvm.array.PrimitiveArray;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

final class PrimitiveArrayJniSupport {
    private static final Logger log = LoggerFactory.getLogger(PrimitiveArrayJniSupport.class);

    static Pointer create(Emulator<?> emulator, BaseVM vm, char type) {
        return register(emulator, () -> {
            int size = emulator.getContext().getIntArg(1);
            log.debug("event=jni_primitive_array_create_started type={} length={} lr={}", type, size, emulator.getContext().getLRPointer());
            if (size < 0) {
                vm.throwException(ProxyDvmObject.createObject(vm, new NegativeArraySizeException("length=" + size)));
                log.debug("event=jni_primitive_array_create_rejected type={} length={}", type, size);
                return 0;
            }
            DvmObject<?> array;
            switch (type) {
                case 'Z': array = new BooleanArray(vm, new boolean[size]); break;
                case 'C': array = new CharArray(vm, new char[size]); break;
                case 'J': array = new LongArray(vm, new long[size]); break;
                default: throw new IllegalArgumentException("JNI primitive array type: " + type);
            }
            int handle = vm.addLocalObject(array);
            log.debug("event=jni_primitive_array_created type={} array={} length={}", type, handle, size);
            return handle;
        });
    }

    static Pointer elements(Emulator<?> emulator, BaseVM vm, char type) {
        return register(emulator, () -> {
            RegisterContext context = emulator.getContext();
            int handle = context.getIntArg(1);
            log.debug("event=jni_primitive_array_elements type={} array={} lr={}", type, handle, context.getLRPointer());
            return array(vm, handle, type)._GetArrayCritical(emulator, context.getPointerArg(2)).peer;
        });
    }

    static Pointer release(Emulator<?> emulator, BaseVM vm, char type) {
        return register(emulator, () -> {
            RegisterContext context = emulator.getContext();
            int handle = context.getIntArg(1), mode = context.getIntArg(3);
            Pointer pointer = context.getPointerArg(2);
            log.debug("event=jni_primitive_array_release type={} array={} pointer={} mode={} lr={}",
                    type, handle, pointer, mode, context.getLRPointer());
            array(vm, handle, type)._ReleaseArrayCritical(pointer, mode);
            return 0;
        });
    }

    private static PrimitiveArray<?> array(BaseVM vm, int handle, char type) {
        switch (type) {
            case 'Z': return (BooleanArray) vm.getObject(handle);
            case 'C': return (CharArray) vm.getObject(handle);
            case 'J': return (LongArray) vm.getObject(handle);
            default: throw new IllegalArgumentException("JNI primitive array type: " + type);
        }
    }

    private static Pointer register(Emulator<?> emulator, LongSupplier operation) {
        return emulator.getSvcMemory().registerSvc(emulator.is64Bit() ? new Arm64Svc() {
            @Override
            public long handle(Emulator<?> current) { return operation.getAsLong(); }
        } : new ArmSvc() {
            @Override
            public long handle(Emulator<?> current) { return operation.getAsLong(); }
        });
    }

    private PrimitiveArrayJniSupport() {}
}
