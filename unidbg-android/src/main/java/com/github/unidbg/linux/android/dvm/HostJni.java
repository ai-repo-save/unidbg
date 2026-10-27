package com.github.unidbg.linux.android.dvm;

/** Backend binding of reflection; returned operations contain host values, never VM state. */
public interface HostJni extends Jni {
    JniCallPlan planMethod(DvmClass type, DvmObject<?> receiver, DvmMethod method,
                                VarArg arguments, boolean isStatic) throws ReflectiveOperationException;
    JniCallPlan planConstructor(DvmClass type, DvmMethod method, VarArg arguments)
            throws ReflectiveOperationException;
    JniCallPlan planAllocation(DvmClass type) throws ReflectiveOperationException;
    JniCallPlan planField(DvmClass type, DvmObject<?> receiver, DvmField field,
                               boolean isStatic, boolean write, Object value) throws ReflectiveOperationException;
    JniCallPlan planException(DvmClass type, String message) throws ReflectiveOperationException;
}
