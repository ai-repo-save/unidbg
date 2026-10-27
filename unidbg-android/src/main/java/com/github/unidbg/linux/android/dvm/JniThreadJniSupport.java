package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.context.RegisterContext;
import com.sun.jna.Pointer;
import com.github.unidbg.linux.android.dvm.host.HostJavaCall;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import static com.github.unidbg.linux.android.dvm.JniInvocationSupport.*;

final class JniThreadJniSupport {
    static Pointer invocation(Emulator<?> emulator, BaseVM vm, String operation) {
        return raw(emulator, () -> {
            RegisterContext context = emulator.getContext();
            switch (operation) {
                case "GetEnv": return vm.threads().getEnv(context.getPointerArg(1), context.getIntArg(2));
                case "AttachCurrentThread": return vm.threads().attach(context.getPointerArg(1), context.getPointerArg(2), false);
                case "AttachCurrentThreadAsDaemon": return vm.threads().attach(context.getPointerArg(1), context.getPointerArg(2), true);
                case "DetachCurrentThread": return vm.threads().detach();
                default: throw new IllegalArgumentException(operation);
            }
        });
    }

    static Pointer monitor(Emulator<?> emulator, BaseVM vm, boolean enter) {
        return register(emulator, vm, () -> {
            Object object = JniObjectIdentity.identity(vm.resolveJniObject(emulator.getContext().getIntArg(1)));
            HostJavaCall<Integer> call = enter ? vm.threads().hostRuntime().monitorEnter(object) : vm.threads().hostRuntime().monitorExit(object);
            return GuestJavaWaiter.suspend(emulator, call, outcome -> {
                int result;
                if (outcome.failure() instanceof IllegalMonitorStateException) {
                    vm.throwException(ProxyDvmObject.createObject(vm, outcome.failure()));
                    result = VM.JNI_ERR;
                } else if (outcome.failure() != null) throw hostFailure(outcome.failure());
                else result = (Integer) successValue(outcome);
                emulator.getBackend().reg_write(emulator.is64Bit() ? Arm64Const.UC_ARM64_REG_X0 : ArmConst.UC_ARM_REG_R0, result);
            });
        });
    }

    private JniThreadJniSupport() {}
}
