package com.github.unidbg.linux.android.dvm.jni;

import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.Jni;

public class ProxyDvmClass extends DvmClass {

    protected ProxyDvmClass(BaseVM vm, String className, DvmClass superClass, DvmClass[] interfaceClasses, ProxyClassLoader classLoader, ProxyDvmObjectVisitor visitor,
                            Jni fallbackJni) {
        super(vm, className, superClass, interfaceClasses, null);

        setJni(createJni(classLoader, visitor, fallbackJni));

        try {
            this.value = classLoader.loadClass(getName());
        } catch (ClassNotFoundException ignored) {
        }
    }

    void bindHostClass(Class<?> hostClass) {
        if (value != null && value != hostClass) {
            throw new IllegalStateException("Conflicting host Class identity: " + getName()
                    + ", existingLoader=" + value.getClassLoader() + ", incomingLoader=" + hostClass.getClassLoader());
        }
        value = hostClass;
    }

    protected Jni createJni(ProxyClassLoader classLoader, ProxyDvmObjectVisitor visitor, Jni fallbackJni) {
        return new ProxyJni(classLoader, visitor, fallbackJni);
    }

}
