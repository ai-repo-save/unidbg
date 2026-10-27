package com.github.unidbg.linux.android.dvm.jni;

import java.lang.reflect.Field;

/** Host-only bound field operation; boxing/unboxing is supplied by java.lang.reflect.Field. */
class ProxyField {
    private final ProxyDvmObjectVisitor visitor;
    private final Field field;

    ProxyField(ProxyDvmObjectVisitor visitor, Field field) {
        this.visitor = visitor;
        this.field = field;
    }

    Object get(Object receiver) throws IllegalAccessException {
        if (visitor != null) visitor.onProxyVisit(field, receiver, null);
        Object value;
        try {
            value = field.get(receiver);
        } catch (ExceptionInInitializerError failure) {
            throw new ProxyTargetException(failure);
        }
        return visitor == null ? value : visitor.postProxyVisit(field, receiver, null, value);
    }

    void setObject(Object receiver, Object value) throws IllegalAccessException {
        if (visitor != null) visitor.onProxyVisit(field, receiver, new Object[] {value});
        try {
            field.set(receiver, value);
        } catch (ExceptionInInitializerError failure) {
            throw new ProxyTargetException(failure);
        }
    }
}
