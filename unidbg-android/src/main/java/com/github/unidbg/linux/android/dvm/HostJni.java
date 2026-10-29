package com.github.unidbg.linux.android.dvm;

import java.util.concurrent.Callable;
import java.util.function.Function;

/** Host members suspend once; modeled members return their JNI value on the backend. */
public interface HostJni extends Jni {
    interface Completion {
        void complete(Object value, Throwable failure, boolean targetThrown);
        default void cancelled(Throwable cancellation) {}
    }

    Object invokeMethod(DvmClass type, DvmObject<?> receiver, DvmMethod method, VarArg arguments,
                        boolean isStatic, Function<Jni, Object> backend, Completion completion) throws Throwable;
    Object invokeNonvirtual(DvmClass dispatch, DvmObject<?> receiver, DvmMethod method, VarArg arguments,
                            Function<Jni, Object> backend, Completion completion) throws Throwable;
    Object invokeConstructor(DvmClass type, DvmMethod method, VarArg arguments,
                             Function<Jni, Object> backend, Completion completion) throws Throwable;
    Object invokeAllocation(DvmClass type, Function<Jni, Object> backend, Completion completion) throws Throwable;
    Object invokeField(DvmClass type, DvmObject<?> receiver, DvmField field, boolean isStatic, boolean write,
                       Object value, Function<Jni, Object> backend, Completion completion) throws Throwable;
    Object invokeException(DvmClass type, String message, Function<Jni, Object> backend,
                           Completion completion) throws Throwable;

    static Object submit(BaseVM vm, char resultType, Callable<?> operation, Completion completion) {
        return JniInvocationSupport.submit(vm, resultType, operation, completion);
    }
}
