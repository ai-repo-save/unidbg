package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.sun.jna.Pointer;

import static com.github.unidbg.linux.android.dvm.JniInvocationSupport.*;

final class JniExceptionSupport {
    static Pointer exception(Emulator<?> emulator, BaseVM vm, String operation) {
        return register(emulator, vm, () -> {
            switch (operation) {
                case "Throw": vm.throwException(vm.resolveJniObject(emulator.getContext().getIntArg(1))); return 0;
                case "ExceptionOccurred": return vm.addLocalObject(vm.pendingException());
                case "ExceptionCheck": return vm.pendingException() == null ? 0 : 1;
                case "ExceptionClear": vm.throwException(null); return 0;
                case "ExceptionDescribe": {
                    DvmObject<?> pending = vm.pendingException();
                    if (pending == null) return 0;
                    Object value = pending.getValue();
                    if (value instanceof Throwable) {
                        return invoke(emulator, vm, "JNIEnv.ExceptionDescribe",
                                completion -> HostJni.submit(vm, 'V', () -> { ((Throwable) value).printStackTrace(System.err); return null; }, completion),
                                ignored -> { vm.throwException(null); return 0; },
                                failure -> { vm.throwException(ProxyDvmObject.createObject(vm, failure)); return 0; });
                    }
                    System.err.println(pending.getObjectType().getClassName());
                    vm.throwException(null);
                    return 0;
                }
                case "ThrowNew": {
                    DvmClass clazz = vm.resolveJniObject(emulator.getContext().getIntArg(1));
                    Pointer message = emulator.getContext().getPointerArg(2);
                    String text = message == null ? null : JniStringSupport.readModifiedUtf8(message);
                    Jni jni = provider(vm, clazz);
                    java.util.function.Function<Jni, Object> backend = modeled -> modelException(modeled, vm, clazz, text);
                    return invoke(emulator, vm, clazz.getClassName() + "-><init>(Ljava/lang/String;)V [ThrowNew]",
                            completion -> jni instanceof HostJni
                                    ? ((HostJni) jni).invokeException(clazz, text, backend, completion) : backend.apply(jni),
                            value -> { vm.throwException((DvmObject<?>) value); return VM.JNI_OK; },
                            failure -> { vm.throwException(ProxyDvmObject.createObject(vm, failure)); return VM.JNI_ERR; });
                }
                default: throw new IllegalArgumentException(operation);
            }
        });
    }

    private static DvmObject<?> modelException(Jni jni, BaseVM vm, DvmClass clazz, String message) {
        DvmMethod constructor = new DvmMethod(clazz, "<init>", "(Ljava/lang/String;)V", false);
        VarArg args = new VarArg(vm, constructor) {};
        int string = vm.addLocalObject(message == null ? null : new StringObject(vm, message));
        args.args.add(string);
        try { return jni.newObject(vm, clazz, constructor, args); }
        finally { vm.locals().deleteLocal(string); }
    }

    private JniExceptionSupport() {}
}
