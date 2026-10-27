package com.github.unidbg.linux.android.dvm.jni;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

class ProxyConstructor implements ProxyCall {
    private final ProxyDvmObjectVisitor visitor;
    private final Constructor<?> constructor;
    private final Object[] args;

    ProxyConstructor(ProxyDvmObjectVisitor visitor, Constructor<?> constructor, Object[] args) {
        this.visitor = visitor;
        this.constructor = constructor;
        this.args = args;
    }

    @Override
    public Object call(Object receiver) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        Object instance;
        try {
            instance = constructor.newInstance(args);
        } catch (InvocationTargetException failure) {
            throw new ProxyTargetException(failure.getTargetException());
        } catch (ExceptionInInitializerError failure) {
            throw new ProxyTargetException(failure);
        }
        if (visitor != null) {
            visitor.onProxyVisit(constructor, instance, args);
            instance = visitor.postProxyVisit(constructor, instance, args, instance);
        }
        return instance;
    }
}
