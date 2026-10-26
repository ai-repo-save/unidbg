package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.backend.BackendException;
import com.github.unidbg.arm.context.RegisterContext;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.LongSupplier;

final class PrimitiveJniSupport {
    private static final Logger log = LoggerFactory.getLogger(PrimitiveJniSupport.class);

    static Pointer field(Emulator<?> emulator, BaseVM vm, char type, boolean isStatic) {
        return register(emulator, () -> {
            RegisterContext context = emulator.getContext();
            int receiver = context.getIntArg(1);
            int id = context.getIntArg(2);
            DvmObject<?> object = isStatic ? null : vm.getObject(receiver);
            DvmClass clazz = isStatic ? vm.classMap.get(receiver) : object == null ? null : object.getObjectType();
            DvmField field = clazz == null ? null : isStatic ? clazz.getStaticField(id) : clazz.getField(id);
            if (field == null) {
                throw new BackendException("Unknown JNI field: receiver=" + receiver + ", id=" + id);
            }
            log.debug("event=jni_primitive_field_read_started field={} receiver={} static={} type={} lr={}",
                    field, receiver, isStatic, type, context.getLRPointer());
            Number value = read(field, object, type, isStatic);
            log.debug("event=jni_primitive_field_read_completed field={} value={}", field, value);
            if (vm.verbose || vm.verboseFieldOperation) {
                System.out.printf("JNIEnv->Get%sField(%s, %s => %s) was called from %s%n",
                        isStatic ? "Static" : "", object == null ? clazz : object, field, value, context.getLRPointer());
            }
            return result(emulator, type, value);
        });
    }

    static Pointer methodA(Emulator<?> emulator, BaseVM vm, char type, boolean isStatic) {
        return register(emulator, () -> {
            RegisterContext context = emulator.getContext();
            int receiver = context.getIntArg(1);
            int id = context.getIntArg(2);
            DvmObject<?> object = isStatic ? null : vm.getObject(receiver);
            DvmClass clazz = isStatic ? vm.classMap.get(receiver) : object == null ? null : object.getObjectType();
            DvmMethod method = clazz == null ? null : isStatic ? clazz.getStaticMethod(id) : clazz.getMethod(id);
            if (method == null) {
                throw new BackendException("Unknown JNI method: receiver=" + receiver + ", id=" + id);
            }
            VaList args = new JValueList(vm, context.getPointerArg(3), method);
            log.debug("event=jni_primitive_method_a_started method={} receiver={} static={} type={} args={} lr={}",
                    method, receiver, isStatic, type, args.formatArgs(), context.getLRPointer());
            Number value = call(method, object, args, type, isStatic);
            log.debug("event=jni_primitive_method_a_completed method={} value={}", method, value);
            if (vm.verbose || vm.verboseMethodOperation) {
                System.out.printf("JNIEnv->Call%sMethodA(%s, %s(%s) => %s) was called from %s%n",
                        isStatic ? "Static" : "", object == null ? clazz : object, method, args.formatArgs(), value, context.getLRPointer());
            }
            return result(emulator, type, value);
        });
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

    private static Number read(DvmField field, DvmObject<?> object, char type, boolean isStatic) {
        switch (type) {
            case 'Z': return isStatic ? (field.getStaticBooleanField() ? VM.JNI_TRUE : VM.JNI_FALSE) : field.getBooleanField(object);
            case 'B': return isStatic ? field.getStaticByteField() : field.getByteField(object);
            case 'C': return (int) (isStatic ? field.getStaticCharField() : field.getCharField(object));
            case 'S': return isStatic ? field.getStaticShortField() : field.getShortField(object);
            case 'I': return isStatic ? field.getStaticIntField() : field.getIntField(object);
            case 'J': return isStatic ? field.getStaticLongField() : field.getLongField(object);
            case 'F': return isStatic ? field.getStaticFloatField() : field.getFloatField(object);
            case 'D': return isStatic ? field.getStaticDoubleField() : field.getDoubleField(object);
            default: throw new IllegalArgumentException("JNI primitive type: " + type);
        }
    }

    private static Number call(DvmMethod method, DvmObject<?> object, VaList args, char type, boolean isStatic) {
        switch (type) {
            case 'Z': return (isStatic ? method.callStaticBooleanMethodV(args) : method.callBooleanMethodV(object, args)) ? VM.JNI_TRUE : VM.JNI_FALSE;
            case 'B': return isStatic ? method.callStaticByteMethodV(args) : method.callByteMethodV(object, args);
            case 'C': return (int) (isStatic ? method.callStaticCharMethodV(args) : method.callCharMethodV(object, args));
            case 'S': return isStatic ? method.callStaticShortMethodV(args) : method.callShortMethodV(object, args);
            case 'I': return isStatic ? method.callStaticIntMethodV(args) : method.callIntMethodV(object, args);
            case 'J': return isStatic ? method.callStaticLongMethodV(args) : method.callLongMethodV(object, args);
            case 'F': return isStatic ? method.callStaticFloatMethodV(args) : method.callFloatMethodV(object, args);
            case 'D': return isStatic ? method.callStaticDoubleMethodV(args) : method.callDoubleMethodV(object, args);
            default: throw new IllegalArgumentException("JNI primitive type: " + type);
        }
    }

    private static long result(Emulator<?> emulator, char type, Number value) {
        if (emulator.is64Bit() && (type == 'F' || type == 'D')) {
            ByteBuffer vector = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            if (type == 'F') vector.putFloat(value.floatValue());
            else vector.putDouble(value.doubleValue());
            emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, vector.array());
            return emulator.getContext().getLongArg(0);
        }
        long bits = type == 'F' ? Float.floatToRawIntBits(value.floatValue()) & 0xffffffffL
                : type == 'D' ? Double.doubleToRawLongBits(value.doubleValue()) : value.longValue();
        if (emulator.is32Bit() && (type == 'J' || type == 'D')) {
            emulator.getBackend().reg_write(ArmConst.UC_ARM_REG_R1, (int) (bits >>> 32));
        }
        return bits;
    }

    private PrimitiveJniSupport() {}
}
