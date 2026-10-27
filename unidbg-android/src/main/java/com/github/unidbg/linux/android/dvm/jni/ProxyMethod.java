package com.github.unidbg.linux.android.dvm.jni;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

class ProxyMethod implements ProxyCall {
    private final ProxyDvmObjectVisitor visitor;
    private final Member method;
    private final Object[] args;

    ProxyMethod(ProxyDvmObjectVisitor visitor, Member method, Object[] args) {
        this.visitor = visitor;
        this.method = method;
        this.args = args;
    }

    @Override
    public Object call(Object receiver) throws IllegalAccessException, InvocationTargetException {
        if (receiver instanceof ClassLoader && args.length == 1 &&
                ("loadClass".equals(method.getName()) || "findClass".equals(method.getName())))
            args[0] = ((String) args[0]).replace('/', '.');
        if (visitor != null) visitor.onProxyVisit(method, receiver, args);
        Object result;
        try {
            if (!(method instanceof Method)) throw new UnsupportedOperationException("method=" + method);
            result = ((Method) method).invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            throw new ProxyTargetException(failure.getTargetException());
        } catch (ExceptionInInitializerError failure) {
            throw new ProxyTargetException(failure);
        }
        if (visitor != null) result = visitor.postProxyVisit(method, receiver, args, result);
        return result;
    }
}
