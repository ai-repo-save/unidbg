package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DalvikModule {

    private static final Logger log = LoggerFactory.getLogger(DalvikModule.class);

    private final BaseVM vm;
    private final Module module;

    DalvikModule(BaseVM vm, Module module) {
        this.vm = vm;
        this.module = module;
    }

    public Module getModule() {
        return module;
    }

    /**
     * Calls JNI_OnLoad when exported by this module; otherwise does nothing.
     *
     * @param emulator emulator owning this VM
     */
    public void callJNI_OnLoad(Emulator<?> emulator) {
        if (module.findSymbolByName("JNI_OnLoad", false) != null) {
            callJNI_OnLoadWithResult(emulator);
        }
    }

    /**
     * Calls this module's JNI_OnLoad and releases local references even when the call fails.
     *
     * @param emulator emulator owning this VM
     * @return the JNI version returned by JNI_OnLoad, after VM version validation
     * @throws IllegalStateException if this module does not export JNI_OnLoad or returns an unsupported JNI version
     * @since 0.11.0
     */
    public int callJNI_OnLoadWithResult(Emulator<?> emulator) {
        Symbol onLoad = module.findSymbolByName("JNI_OnLoad", false);
        if (onLoad == null) {
            throw new IllegalStateException("find symbol failed: JNI_OnLoad");
        }
        try {
            long start = System.currentTimeMillis();
            if (log.isDebugEnabled()) {
                log.debug("Call [{}]JNI_OnLoad: 0x{}", module.name, Long.toHexString(onLoad.getAddress()));
            }
            Number ret = onLoad.call(emulator, vm.getJavaVM(), null);
            int version = ret.intValue();
            if (log.isDebugEnabled()) {
                log.debug("Call [{}]JNI_OnLoad finished: version=0x{}, offset={}ms", module.name, Integer.toHexString(version), System.currentTimeMillis() - start);
            }

            vm.checkVersion(version);
            return version;
        } finally {
            vm.deleteLocalRefs();
        }
    }

}
