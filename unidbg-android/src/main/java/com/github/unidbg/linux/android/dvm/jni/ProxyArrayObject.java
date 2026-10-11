package com.github.unidbg.linux.android.dvm.jni;

import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.array.ArrayObject;

import java.util.IdentityHashMap;
import java.util.Map;

/** Object array shared by reflected Java calls and the JNI array interface. */
final class ProxyArrayObject extends ArrayObject {
    private final VM vm;
    private final Object[] backing;
    private final Map<Object, DvmObject<?>> elements = new IdentityHashMap<>();

    ProxyArrayObject(VM vm, DvmClass type, Object[] backing) {
        super(type, new DvmObject<?>[backing.length]);
        this.vm = vm;
        this.backing = backing;
        getValue();
    }

    Object[] backingArray() {
        return backing;
    }

    @Override
    public DvmObject<?>[] getValue() {
        for (int i = 0; i < backing.length; i++) {
            Object element = backing[i];
            value[i] = element == null ? null : elements.computeIfAbsent(element,
                    key -> ProxyDvmObject.createObject(vm, key));
        }
        return value;
    }

    @Override
    public void setObject(int index, DvmObject<?> object) {
        Object element = ProxyUtils.unpack(object);
        backing[index] = element;
        if (element != null) {
            elements.put(element, object);
        }
        value[index] = object;
    }

    @Override
    public void setData(int start, DvmObject<?>[] data) {
        for (int i = 0; i < data.length; i++) {
            setObject(start + i, data[i]);
        }
    }

    @Override
    public String toString() {
        getValue();
        return super.toString();
    }
}
