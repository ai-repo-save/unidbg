package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.linux.android.dvm.Array;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VM;

import java.util.Arrays;

public class ArrayObject extends BaseArray<DvmObject<?>[]> implements Array<DvmObject<?>[]> {

    @SuppressWarnings("unused")
    public static ArrayObject newStringArray(VM vm, String... strings) {
        StringObject[] objects = new StringObject[strings.length];
        for (int i = 0; i < strings.length; i++) {
            String str = strings[i];
            if (str != null) {
                objects[i] = new StringObject(vm, str);
            }
        }
        return new ArrayObject(typeOf(vm, vm.resolveClass("java/lang/String")), objects);
    }

    public static ArrayObject newObjectArray(DvmClass elementClass, DvmObject<?>... value) {
        return new ArrayObject(typeOf(elementClass.vm, elementClass), value);
    }

    public ArrayObject(DvmObject<?>... value) {
        super(null, value);
    }

    public ArrayObject(DvmClass objectType, DvmObject<?>[] value) {
        super(objectType, value);
    }

    public static DvmClass typeOf(VM vm, DvmClass element) {
        String name = element.getClassName();
        DvmClass objectArray = vm.resolveClass("[Ljava/lang/Object;", vm.resolveClass("java/lang/Object"));
        if ("java/lang/Object".equals(name)) {
            return objectArray;
        }
        String arrayName = name.startsWith("[") ? "[" + name : "[L" + name + ";";
        return vm.resolveClass(arrayName, objectArray);
    }

    /**
     * Every object array is an Object[], Object, Cloneable and Serializable, whether or not its component type is known.
     */
    @Override
    protected boolean isInstanceOf(DvmClass dvmClass) {
        if (super.isInstanceOf(dvmClass)) {
            return true;
        }
        switch (dvmClass.getClassName()) {
            case "[Ljava/lang/Object;":
            case "java/lang/Object":
            case "java/lang/Cloneable":
            case "java/io/Serializable":
                return true;
            default:
                return false;
        }
    }

    @Override
    public int length() {
        return value.length;
    }

    public void setObject(int index, DvmObject<?> object) {
        value[index] = object;
    }

    @Override
    public void setData(int start, DvmObject<?>[] data) {
        System.arraycopy(data, 0, value, start, data.length);
    }

    @Override
    public String toString() {
        return Arrays.toString(value);
    }
}
