package com.github.unidbg.virtualmodule.android;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.Arm64Hook;
import com.github.unidbg.arm.ArmHook;
import com.github.unidbg.arm.HookStatus;
import com.github.unidbg.arm.NestedRun;
import com.github.unidbg.arm.context.EditableArm32RegisterContext;
import com.github.unidbg.arm.context.EditableArm64RegisterContext;
import com.github.unidbg.linux.android.SystemPropertyHook;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.virtualmodule.VirtualModule;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class SystemProperties extends VirtualModule<Void> {

    private static final Logger log = LoggerFactory.getLogger(SystemProperties.class);

    // AOSP prop_info preserves name at +96; read-only long values use a relative offset at +60.
    private static Pointer propertyValue(Pointer pi, Pointer name, int serial) {
        if ((serial & (1 << 16)) != 0 && name.getString(0).startsWith("ro.")) {
            return pi.share(Integer.toUnsignedLong(pi.getInt(60)));
        }
        return pi.share(4);
    }

    public SystemProperties(Emulator<?> emulator, Void extra) {
        super(emulator, extra, "libsystemproperties.so");
    }

    @Override
    protected void onInitialize(Emulator<?> emulator, Void extra, Map<String, UnidbgPointer> symbols) {
        boolean is64Bit = emulator.is64Bit();
        SvcMemory svcMemory = emulator.getSvcMemory();
        symbols.put("__system_property_read_callback", svcMemory.registerSvc(is64Bit ? new Arm64Hook() {
            @Override
            public HookStatus hook(Emulator<?> emulator) {
                EditableArm64RegisterContext context = emulator.getContext();
                Pointer pi = context.getPointerArg(0);
                Pointer callback = context.getPointerArg(1);
                Pointer cookie = context.getPointerArg(2);
                log.debug("__system_property_read_callback pi={}, callback={}, cookie={}", pi, callback, cookie);
                int serial = pi.getInt(0);
                Pointer name = pi.share(SystemPropertyHook.PROP_VALUE_MAX + 4);
                Pointer value = propertyValue(pi, name, serial);
                context.setXLong(0, UnidbgPointer.nativeValue(cookie));
                context.setXLong(1, UnidbgPointer.nativeValue(name));
                context.setXLong(2, UnidbgPointer.nativeValue(value));
                context.setXLong(3, Integer.toUnsignedLong(serial));
                return HookStatus.RET(emulator, UnidbgPointer.nativeValue(callback));
            }
        } : new ArmHook() {
            @Override
            protected HookStatus hook(Emulator<?> emulator) throws NestedRun {
                EditableArm32RegisterContext context = emulator.getContext();
                Pointer pi = context.getPointerArg(0);
                Pointer callback = context.getPointerArg(1);
                Pointer cookie = context.getPointerArg(2);
                log.debug("__system_property_read_callback pi={}, callback={}, cookie={}", pi, callback, cookie);
                int serial = pi.getInt(0);
                Pointer name = pi.share(SystemPropertyHook.PROP_VALUE_MAX + 4);
                Pointer value = propertyValue(pi, name, serial);
                context.setR0((int) UnidbgPointer.nativeValue(cookie));
                context.setR1((int) UnidbgPointer.nativeValue(name));
                context.setR2((int) UnidbgPointer.nativeValue(value));
                context.setR3(serial);
                return HookStatus.RET(emulator, UnidbgPointer.nativeValue(callback));
            }
        }));
    }

}
