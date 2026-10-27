package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.context.RegisterContext;
import com.sun.jna.Pointer;

import static com.github.unidbg.linux.android.dvm.JniInvocationSupport.*;

final class JniReferenceSupport {
    static Pointer reference(Emulator<?> emulator, BaseVM vm, String operation) {
        return register(emulator, vm, () -> {
            RegisterContext context = emulator.getContext();
            int handle = context.getIntArg(1);
            switch (operation) {
                case "PushLocalFrame": return vm.locals().push(handle);
                case "PopLocalFrame": return vm.locals().pop(handle);
                case "EnsureLocalCapacity": return vm.locals().ensureCapacity(handle);
                case "NewLocalRef": return vm.addLocalObject(vm.resolveJniObject(handle));
                case "NewGlobalRef": return vm.addGlobalObject(vm.resolveJniObject(handle));
                case "NewWeakGlobalRef": return handle == 0 ? 0 : vm.references().newWeakGlobal(vm.resolveJniObject(handle));
                case "DeleteLocalRef": vm.locals().deleteLocal(handle); return 0;
                case "DeleteGlobalRef": vm.references().deleteGlobal(handle); return 0;
                case "DeleteWeakGlobalRef": vm.references().deleteWeakGlobal(handle); return 0;
                case "GetObjectRefType": return vm.references().referenceType(handle, vm.locals());
                case "IsSameObject": return vm.references().isSameObject(handle, context.getIntArg(2), vm.locals()) ? 1 : 0;
                default: throw new IllegalArgumentException(operation);
            }
        });
    }

    private JniReferenceSupport() {}
}
