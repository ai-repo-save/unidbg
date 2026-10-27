package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;

final class JniObjectIdentity {
    static Object identity(DvmObject<?> object) {
        return ProxyDvmObject.identityKey(object);
    }

    private JniObjectIdentity() {}
}
