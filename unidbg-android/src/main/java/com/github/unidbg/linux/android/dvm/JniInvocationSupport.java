package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.LongJumpException;
import com.github.unidbg.arm.Arm64Hook;
import com.github.unidbg.arm.ArmHook;
import com.github.unidbg.arm.HookStatus;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.linux.android.dvm.host.HostJavaCall;
import com.github.unidbg.linux.android.dvm.host.HostJavaResult;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.linux.android.dvm.jni.ProxyTargetException;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

final class JniInvocationSupport {
    private static final Logger log = LoggerFactory.getLogger(JniInvocationSupport.class);
    enum Arguments { ORDINARY, V, A }

    private interface Binding { Object invoke(HostJni jni, Function<Jni, Object> backend, HostJni.Completion completion) throws Throwable; }

    static Pointer register(Emulator<?> emulator, BaseVM vm, LongSupplier operation) {
        return raw(emulator, () -> {
            vm.threads().requireAttachedState(emulator.getContext().getPointerArg(0));
            return operation.getAsLong();
        });
    }

    static Pointer raw(Emulator<?> emulator, LongSupplier operation) {
        return emulator.getSvcMemory().registerSvc(emulator.is64Bit() ? new Arm64Hook() {
            @Override protected HookStatus hook(Emulator<?> current) {
                return HookStatus.LR(current, operation.getAsLong());
            }
        } : new ArmHook() {
            @Override protected HookStatus hook(Emulator<?> current) {
                return HookStatus.LR(current, operation.getAsLong());
            }
        });
    }

    static Jni provider(BaseVM vm, DvmClass type) {
        Jni provider = type.getJni() == null ? vm.jni : type.getJni();
        if (provider == null) throw new IllegalStateException("JNI provider is required");
        return provider;
    }

    private static VarArg arguments(Emulator<?> emulator, BaseVM vm, DvmMethod method, Arguments mode) {
        return arguments(emulator, vm, method, mode, 3);
    }

    private static VarArg arguments(Emulator<?> emulator, BaseVM vm, DvmMethod method, Arguments mode, int firstArgument) {
        if (mode == Arguments.ORDINARY) return ArmVarArg.create(emulator, vm, method, firstArgument);
        com.github.unidbg.pointer.UnidbgPointer pointer = emulator.getContext().getPointerArg(firstArgument);
        if (mode == Arguments.A) return new JValueList(vm, pointer, method);
        return emulator.is64Bit() ? new VaList64(emulator, vm, pointer, method) : new VaList32(emulator, vm, pointer, method);
    }

    /** Prints every Java method the guest invokes; the targeted logs only cover a few functions. */
    private static boolean methodCallTrace() {
        return Boolean.getBoolean("unidbg.traceMethodCalls")
                || "1".equals(System.getenv("UNIDBG_TRACE_METHOD_CALLS"));
    }

    private static long dispatch(Emulator<?> emulator, BaseVM vm, Jni provider, char resultType, String member,
                         Binding binding, Function<Jni, Object> backend) {
        if (methodCallTrace()) {
            System.out.println("event=guest_call emulator=" + System.identityHashCode(emulator)
                    + " signature=" + member);
        }
        return invoke(emulator, vm, member, completion -> provider instanceof HostJni
                        ? binding.invoke((HostJni) provider, backend, completion) : backend.apply(provider),
                value -> result(emulator, vm, resultType, value),
                failure -> {
                    vm.throwException(ProxyDvmObject.createObject(vm, failure));
                    return zero(emulator, vm, resultType);
                });
    }

    interface Invocation { Object invoke(HostJni.Completion completion) throws Throwable; }

    static long invoke(Emulator<?> emulator, BaseVM vm, String member, Invocation invocation,
                       ToLongFunction<Object> terminal,
                       ToLongFunction<Throwable> targetFailure) {
        String nativeCaller = String.valueOf(emulator.getContext().getLRPointer());
        int tid = vm.threads().currentThreadState().tid();
        HostJni.Completion completion = new HostJni.Completion() {
            @Override public void complete(Object value, Throwable failure, boolean targetThrown) {
                long encoded;
                if (failure == null) encoded = terminal.applyAsLong(value);
                else if (targetThrown) encoded = targetFailure.applyAsLong(failure);
                else {
                    if (!(failure instanceof LongJumpException)) {
                        log.error("event=jni_host_failed emulator_id={} guest_tid={} member={} native_caller={}",
                                System.identityHashCode(emulator), tid, member, nativeCaller, failure);
                    }
                    throw hostFailure(failure);
                }
                emulator.getBackend().reg_write(emulator.is64Bit() ? Arm64Const.UC_ARM64_REG_X0 : ArmConst.UC_ARM_REG_R0,
                        emulator.is64Bit() ? encoded : (int) encoded);
            }
            @Override public void cancelled(Throwable cancellation) {
                log.debug("event=jni_host_cancelled emulator_id={} guest_tid={} member={} native_caller={}",
                        System.identityHashCode(emulator), tid, member, nativeCaller, cancellation);
            }
        };
        try { return terminal.applyAsLong(invocation.invoke(completion)); }
        catch (ProxyTargetException failure) { return targetFailure.applyAsLong(failure.target()); }
        catch (Throwable failure) { throw hostFailure(failure); }
    }

    static Object submit(BaseVM vm, char resultType, Callable<?> operation, HostJni.Completion completion) {
        HostJavaCall<?> call = vm.threads().hostRuntime().submit(operation);
        return GuestJavaWaiter.suspend(vm.getEmulator(), call, result -> {
            Throwable failure = result.failure();
            if (failure instanceof ProxyTargetException) {
                completion.complete(null, ((ProxyTargetException) failure).target(), true);
            } else if (failure != null) {
                completion.complete(null, failure, false);
            } else {
                Object value;
                try {
                    Object hostValue = successValue(result);
                    value = resultType == 'L' ? ProxyDvmObject.createObject(vm, hostValue)
                            : resultType == 'V' ? null : hostValue;
                } catch (Throwable conversionFailure) {
                    completion.complete(null, conversionFailure, false);
                    return;
                }
                completion.complete(value, null, false);
            }
        }, completion::cancelled);
    }

    static Object successValue(HostJavaResult<?> result) {
        try { return result.get(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("JNI host completion failed", failure); }
    }

    static RuntimeException hostFailure(Throwable failure) {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) return (RuntimeException) failure;
        return new IllegalStateException("JNI host execution failed", failure);
    }

    static long result(Emulator<?> emulator, BaseVM vm, char type, Object value) {
        if (type == 'V') return 0;
        if (type == 'L') return vm.addLocalObject((DvmObject<?>) value);
        if (type == 'Z') return (Boolean) value ? VM.JNI_TRUE : VM.JNI_FALSE;
        if (type == 'C') return (Character) value;
        return PrimitiveJniSupport.result(emulator, type, (Number) value);
    }

    private static long zero(Emulator<?> emulator, BaseVM vm, char type) {
        if (type == 'Z') return result(emulator, vm, type, Boolean.FALSE);
        if (type == 'C') return result(emulator, vm, type, Character.valueOf((char) 0));
        return result(emulator, vm, type, type == 'L' ? null : Integer.valueOf(0));
    }

    static Pointer method(Emulator<?> emulator, BaseVM vm, char returnType, boolean isStatic, Arguments mode) {
        return register(emulator, vm, () -> {
            RegisterContext context = emulator.getContext();
            DvmObject<?> receiver = isStatic ? null : vm.resolveJniObject(context.getIntArg(1));
            DvmClass clazz = isStatic ? vm.resolveJniObject(context.getIntArg(1)) : receiver.getObjectType();
            DvmMethod method = isStatic ? clazz.getStaticMethod(context.getIntArg(2)) : clazz.getMethod(context.getIntArg(2));
            if (method == null) throw new IllegalArgumentException("Unknown JNI method");
            if (method.isConstructor() && method.getDvmClass() != clazz) {
                throw new IllegalArgumentException("Constructor method ID must come from the receiver class");
            }
            VarArg args = arguments(emulator, vm, method, mode);
            return dispatch(emulator, vm, provider(vm, clazz), returnType, method.getSignature(),
                    (host, backend, completion) -> host.invokeMethod(clazz, receiver, method, args, isStatic, backend, completion),
                    jni -> modelMethod(jni, vm, clazz, receiver, method, args, isStatic, returnType, mode));
        });
    }

    static Pointer nonvirtual(Emulator<?> emulator, BaseVM vm, char returnType, Arguments mode) {
        return register(emulator, vm, () -> {
            RegisterContext context = emulator.getContext();
            DvmObject<?> receiver = vm.resolveJniObject(context.getIntArg(1));
            DvmClass dispatch = vm.resolveJniObject(context.getIntArg(2));
            DvmMethod method = dispatch.getMethod(context.getIntArg(3));
            if (method == null) throw new IllegalArgumentException("Unknown JNI nonvirtual method");
            VarArg args = arguments(emulator, vm, method, mode, 4);
            return dispatch(emulator, vm, provider(vm, dispatch), returnType, method.getSignature(),
                    (host, backend, completion) -> host.invokeNonvirtual(dispatch, receiver, method, args, backend, completion),
                    jni -> modelMethod(jni, vm, dispatch, receiver, method, args, false, returnType, mode));
        });
    }

    static Pointer construct(Emulator<?> emulator, BaseVM vm, Arguments mode) {
        return register(emulator, vm, () -> {
            RegisterContext context = emulator.getContext();
            DvmClass clazz = vm.resolveJniObject(context.getIntArg(1));
            DvmMethod constructor = clazz.getMethod(context.getIntArg(2));
            if (constructor == null) throw new IllegalArgumentException("Unknown JNI constructor");
            VarArg args = arguments(emulator, vm, constructor, mode);
            return dispatch(emulator, vm, provider(vm, clazz), 'L', constructor.getSignature(), (host, backend, completion) -> host.invokeConstructor(clazz, constructor, args, backend, completion),
                    jni -> mode == Arguments.ORDINARY ? jni.newObject(vm, clazz, constructor, args)
                            : jni.newObjectV(vm, clazz, constructor, (VaList) args));
        });
    }

    static Pointer allocate(Emulator<?> emulator, BaseVM vm) {
        return register(emulator, vm, () -> {
            DvmClass clazz = vm.resolveJniObject(emulator.getContext().getIntArg(1));
            return dispatch(emulator, vm, provider(vm, clazz), 'L', clazz.getClassName() + "->allocObject", (host, backend, completion) -> host.invokeAllocation(clazz, backend, completion),
                    jni -> jni.allocObject(vm, clazz, clazz.getClassName() + "->allocObject"));
        });
    }

    static Pointer field(Emulator<?> emulator, BaseVM vm, char type, boolean isStatic, boolean write) {
        return register(emulator, vm, () -> {
            RegisterContext context = emulator.getContext();
            DvmObject<?> receiver = isStatic ? null : vm.resolveJniObject(context.getIntArg(1));
            DvmClass clazz = isStatic ? vm.resolveJniObject(context.getIntArg(1)) : receiver.getObjectType();
            DvmField field = isStatic ? clazz.getStaticField(context.getIntArg(2)) : clazz.getField(context.getIntArg(2));
            if (field == null) throw new IllegalArgumentException("Unknown JNI field");
            Object value = write ? fieldInput(emulator, vm, type) : null;
            return dispatch(emulator, vm, provider(vm, clazz), write ? 'V' : type, field.getSignature(),
                    (host, backend, completion) -> host.invokeField(clazz, receiver, field, isStatic, write, value, backend, completion),
                    jni -> modelField(jni, vm, clazz, receiver, field, type, isStatic, write, value));
        });
    }

    private static Object fieldInput(Emulator<?> emulator, BaseVM vm, char type) {
        RegisterContext context = emulator.getContext();
        switch (type) {
            case 'L': return vm.resolveJniObject(context.getIntArg(3));
            case 'Z': return BaseVM.valueOf(context.getIntArg(3));
            case 'B': return (byte) context.getIntArg(3);
            case 'C': return (char) context.getIntArg(3);
            case 'S': return (short) context.getIntArg(3);
            case 'I': return context.getIntArg(3);
            case 'J': return emulator.is64Bit() ? context.getLongArg(3)
                    : (context.getIntArg(4) & 0xffffffffL) | ((long) context.getIntArg(5) << 32);
            case 'F': return emulator.is64Bit()
                    ? ByteBuffer.wrap(emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0)).order(ByteOrder.LITTLE_ENDIAN).getFloat()
                    : Float.intBitsToFloat(context.getIntArg(3));
            case 'D': return emulator.is64Bit()
                    ? ByteBuffer.wrap(emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0)).order(ByteOrder.LITTLE_ENDIAN).getDouble()
                    : Double.longBitsToDouble((context.getIntArg(4) & 0xffffffffL) | ((long) context.getIntArg(5) << 32));
            default: throw new IllegalArgumentException("JNI field type=" + type);
        }
    }

    static Pointer arrayRegion(Emulator<?> emulator, BaseVM vm, JniArrayRegion.Type type, boolean write) {
        return register(emulator, vm, () -> write ? JniArrayRegion.write(vm, emulator, type) : JniArrayRegion.read(vm, emulator, type));
    }

    private JniInvocationSupport() {}

    private static Object modelMethod(Jni jni, BaseVM vm, DvmClass clazz, DvmObject<?> receiver, DvmMethod method,
                                      VarArg args, boolean stat, char type, Arguments mode) {
        boolean plain = mode == Arguments.ORDINARY;
        switch (type) {
            case 'L': return stat ? plain ? jni.callStaticObjectMethod(vm, clazz, method, args) : jni.callStaticObjectMethodV(vm, clazz, method, (VaList) args)
                    : plain ? jni.callObjectMethod(vm, receiver, method, args) : jni.callObjectMethodV(vm, receiver, method, (VaList) args);
            case 'Z': return stat ? plain ? jni.callStaticBooleanMethod(vm, clazz, method, args) : jni.callStaticBooleanMethodV(vm, clazz, method, (VaList) args)
                    : plain ? jni.callBooleanMethod(vm, receiver, method, args) : jni.callBooleanMethodV(vm, receiver, method, (VaList) args);
            case 'B': return stat ? plain ? jni.callStaticByteMethod(vm, clazz, method, args) : jni.callStaticByteMethodV(vm, clazz, method, (VaList) args)
                    : plain ? jni.callByteMethod(vm, receiver, method, args) : jni.callByteMethodV(vm, receiver, method, (VaList) args);
            case 'C': return stat ? plain ? jni.callStaticCharMethod(vm, clazz, method, args) : jni.callStaticCharMethodV(vm, clazz, method, (VaList) args)
                    : plain ? jni.callCharMethod(vm, receiver, method, args) : jni.callCharMethodV(vm, receiver, method, (VaList) args);
            case 'S': return stat ? plain ? jni.callStaticShortMethod(vm, clazz, method, args) : jni.callStaticShortMethodV(vm, clazz, method, (VaList) args)
                    : plain ? jni.callShortMethod(vm, receiver, method, args) : jni.callShortMethodV(vm, receiver, method, (VaList) args);
            case 'I': return stat ? plain ? jni.callStaticIntMethod(vm, clazz, method, args) : jni.callStaticIntMethodV(vm, clazz, method, (VaList) args)
                    : plain ? jni.callIntMethod(vm, receiver, method, args) : jni.callIntMethodV(vm, receiver, method, (VaList) args);
            case 'J': return stat ? plain ? jni.callStaticLongMethod(vm, clazz, method, args) : jni.callStaticLongMethodV(vm, clazz, method, (VaList) args)
                    : plain ? jni.callLongMethod(vm, receiver, method, args) : jni.callLongMethodV(vm, receiver, method, (VaList) args);
            case 'F': return stat ? plain ? jni.callStaticFloatMethod(vm, clazz, method, args) : jni.callStaticFloatMethodV(vm, clazz, method, (VaList) args)
                    : plain ? jni.callFloatMethod(vm, receiver, method, args) : jni.callFloatMethodV(vm, receiver, method, (VaList) args);
            case 'D': return stat ? plain ? jni.callStaticDoubleMethod(vm, clazz, method, args) : jni.callStaticDoubleMethodV(vm, clazz, method, (VaList) args)
                    : plain ? jni.callDoubleMethod(vm, receiver, method, args) : jni.callDoubleMethodV(vm, receiver, method, (VaList) args);
            case 'V': if (stat) { if (plain) jni.callStaticVoidMethod(vm, clazz, method, args); else jni.callStaticVoidMethodV(vm, clazz, method, (VaList) args); }
                else { if (plain) jni.callVoidMethod(vm, receiver, method, args); else jni.callVoidMethodV(vm, receiver, method, (VaList) args); } return null;
            default: throw new IllegalArgumentException("JNI return type=" + type);
        }
    }

    private static Object modelField(Jni jni, BaseVM vm, DvmClass clazz, DvmObject<?> receiver, DvmField field,
                                     char type, boolean stat, boolean write, Object value) {
        if (!write) {
            switch (type) {
                case 'L': return stat ? jni.getStaticObjectField(vm, clazz, field) : jni.getObjectField(vm, receiver, field);
                case 'Z': return stat ? jni.getStaticBooleanField(vm, clazz, field) : jni.getBooleanField(vm, receiver, field);
                case 'B': return stat ? jni.getStaticByteField(vm, clazz, field) : jni.getByteField(vm, receiver, field);
                case 'C': return stat ? jni.getStaticCharField(vm, clazz, field) : jni.getCharField(vm, receiver, field);
                case 'S': return stat ? jni.getStaticShortField(vm, clazz, field) : jni.getShortField(vm, receiver, field);
                case 'I': return stat ? jni.getStaticIntField(vm, clazz, field) : jni.getIntField(vm, receiver, field);
                case 'J': return stat ? jni.getStaticLongField(vm, clazz, field) : jni.getLongField(vm, receiver, field);
                case 'F': return stat ? jni.getStaticFloatField(vm, clazz, field) : jni.getFloatField(vm, receiver, field);
                case 'D': return stat ? jni.getStaticDoubleField(vm, clazz, field) : jni.getDoubleField(vm, receiver, field);
                default: throw new IllegalArgumentException("JNI field type=" + type);
            }
        }
        switch (type) {
            case 'L': if (stat) jni.setStaticObjectField(vm, clazz, field, (DvmObject<?>) value);
                else jni.setObjectField(vm, receiver, field, (DvmObject<?>) value); break;
            case 'Z': if (stat) jni.setStaticBooleanField(vm, clazz, field, (Boolean) value);
                else jni.setBooleanField(vm, receiver, field, (Boolean) value); break;
            case 'B': if (stat) jni.setStaticByteField(vm, clazz, field, (Byte) value);
                else jni.setByteField(vm, receiver, field, (Byte) value); break;
            case 'C': if (stat) jni.setStaticCharField(vm, clazz, field, (Character) value);
                else jni.setCharField(vm, receiver, field, (Character) value); break;
            case 'S': if (stat) jni.setStaticShortField(vm, clazz, field, (Short) value);
                else jni.setShortField(vm, receiver, field, (Short) value); break;
            case 'I': if (stat) jni.setStaticIntField(vm, clazz, field, (Integer) value);
                else jni.setIntField(vm, receiver, field, (Integer) value); break;
            case 'J': if (stat) jni.setStaticLongField(vm, clazz, field, (Long) value);
                else jni.setLongField(vm, receiver, field, (Long) value); break;
            case 'F': if (stat) jni.setStaticFloatField(vm, clazz, field, (Float) value);
                else jni.setFloatField(vm, receiver, field, (Float) value); break;
            case 'D': if (stat) jni.setStaticDoubleField(vm, clazz, field, (Double) value);
                else jni.setDoubleField(vm, receiver, field, (Double) value); break;
            default: throw new IllegalArgumentException("JNI field type=" + type);
        }
        return null;
    }
}
