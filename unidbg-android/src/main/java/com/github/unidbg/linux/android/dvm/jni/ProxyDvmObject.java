package com.github.unidbg.linux.android.dvm.jni;

import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.array.ArrayObject;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.android.dvm.array.BooleanArray;
import com.github.unidbg.linux.android.dvm.array.CharArray;
import com.github.unidbg.linux.android.dvm.array.LongArray;
import com.github.unidbg.linux.android.dvm.array.DoubleArray;
import com.github.unidbg.linux.android.dvm.array.FloatArray;
import com.github.unidbg.linux.android.dvm.array.IntArray;
import com.github.unidbg.linux.android.dvm.array.ShortArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;

public class ProxyDvmObject extends DvmObject<Object> {

    private static final Logger log = LoggerFactory.getLogger(ProxyDvmObject.class);

    private static DvmClass getObjectType(VM vm, Class<?> clazz) {
        Class<?> superClass = clazz.getSuperclass();
        DvmClass[] interfaces = new DvmClass[clazz.getInterfaces().length + (superClass == null ? 0 : 1)];
        int i = 0;
        if (superClass != null) {
            interfaces[i++] = getObjectType(vm, superClass);
        }
        for (Class<?> cc : clazz.getInterfaces()) {
            interfaces[i++] = getObjectType(vm, cc);
        }
        DvmClass type = vm.resolveClass(clazz.getName().replace('.', '/'), interfaces);
        if (type instanceof ProxyDvmClass) {
            ((ProxyDvmClass) type).bindHostClass(clazz);
        }
        return type;
    }

    /**
     * mapping java object to dvm object
     */
    public static DvmObject<?> createObject(VM vm, Object value) {
        return vm.canonicalizeObject(wrapObject(vm, value));
    }

    public static Object identityKey(DvmObject<?> object) {
        if (object instanceof ProxyArrayObject) return ((ProxyArrayObject) object).backingArray();
        if (object instanceof ProxyDvmObject || object instanceof StringObject ||
                object instanceof com.github.unidbg.linux.android.dvm.array.PrimitiveArray)
            return object.getValue();
        return object;
    }

    private static DvmObject<?> wrapObject(VM vm, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Class<?>) {
            return getObjectType(vm, (Class<?>) value);
        }
        if (value instanceof DvmObject) {
            return (DvmObject<?>) value;
        }

        if (value instanceof boolean[]) {
            return new BooleanArray(vm, (boolean[]) value);
        }
        if (value instanceof char[]) {
            return new CharArray(vm, (char[]) value);
        }
        if (value instanceof long[]) {
            return new LongArray(vm, (long[]) value);
        }

        if (value instanceof byte[]) {
            return new ByteArray(vm, (byte[]) value);
        }
        if (value instanceof short[]) {
            return new ShortArray(vm, (short[]) value);
        }
        if (value instanceof int[]) {
            return new IntArray(vm, (int[]) value);
        }
        if (value instanceof float[]) {
            return new FloatArray(vm, (float[]) value);
        }
        if (value instanceof double[]) {
            return new DoubleArray(vm, (double[]) value);
        }
        if (value instanceof String) {
            return new StringObject(vm, (String) value);
        }
        Class<?> clazz = value.getClass();
        if (clazz.isArray()) {
            if (clazz.getComponentType().isPrimitive()) {
                throw new UnsupportedOperationException(String.valueOf(value));
            }
            Object[] array = (Object[]) value;
            DvmClass element = getObjectType(vm, clazz.getComponentType());
            if (value instanceof Member[]) {
                array = withoutInjectedMembers(vm, (Member[]) value);
            }
            return new ProxyArrayObject(vm, ArrayObject.typeOf(vm, element), array);
        }

        return new ProxyDvmObject(vm, value);
    }

    /**
     * Robolectric instruments the platform classes it shadows and injects bookkeeping members into them:
     * a {@code __robo_data__} field and {@code $$robo$$...} shim methods. Platform classes on a device
     * declare no such members, so the guest only sees the members the platform declares.
     */
    private static Object[] withoutInjectedMembers(VM vm, Member[] members) {
        List<Member> visible = new ArrayList<>(members.length);
        for (Member member : members) {
            String name = member.getName();
            if (name.startsWith("__robo") || name.startsWith("$$robo")) {
                log.debug("event=jni_host_member_hidden emulator_id={} declaring_class={} member={}",
                        System.identityHashCode(vm.getEmulator()), member.getDeclaringClass().getName(), name);
            } else {
                visible.add(member);
            }
        }
        if (visible.size() == members.length) {
            return members;
        }
        return visible.toArray((Object[]) Array.newInstance(members.getClass().getComponentType(), visible.size()));
    }

    /**
     * Unwraps a proxy value using the same conversion as reflected JNI arguments.
     * Proxy object arrays retain their original backing array and component type.
     * Class values require a VM configured with {@link ProxyClassFactory}.
     * This operation neither adds nor deletes VM references and does not transfer ownership.
     *
     * @param dvmObject wrapped value, or null
     * @return the host value, or null for a null input
     * @throws UnsupportedOperationException if the wrapper has no host value
     * @since 0.12.0
     */
    public static Object unpack(DvmObject<?> dvmObject) {
        return ProxyUtils.unpack(dvmObject);
    }

    private ProxyDvmObject(VM vm, Object value) {
        super(getObjectType(vm, value.getClass()), value);
    }

}
