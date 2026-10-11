package com.github.unidbg.linux.android.dvm.jni;

import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmField;
import com.github.unidbg.linux.android.dvm.DvmMethod;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.Jni;
import com.github.unidbg.linux.android.dvm.JniFunction;
import com.github.unidbg.linux.android.dvm.VaList;
import com.github.unidbg.linux.android.dvm.VarArg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Constructor, static call and reflected-method proxy bindings. */
public class ProxyJni extends ProxyInstanceJni {
    public ProxyJni(ProxyClassLoader loader, ProxyDvmObjectVisitor visitor, Jni fallback) { super(loader, visitor, fallback); }

    @Override
    public DvmObject<?> allocObject(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findAllocConstructor(clazz, visitor);
            Object obj = proxyCall.call(vm, null);
            return ProxyDvmObject.createObject(vm, obj);
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return null;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("allocObject", e);
        }
        return super.allocObject(vm, dvmClass, signature);
    }

    @Override
    public DvmObject<?> newObject(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findConstructor(clazz, dvmMethod, varArg, visitor);
            Object obj = proxyCall.call(vm, null);
            return ProxyDvmObject.createObject(vm, obj);
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return null;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("newObject", e);
        }

        return super.newObject(vm, dvmClass, dvmMethod, varArg);
    }

    @Override
    public DvmObject<?> newObjectV(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findConstructor(clazz, dvmMethod, vaList, visitor);
            Object obj = proxyCall.call(vm, null);
            return ProxyDvmObject.createObject(vm, obj);
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return null;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("newObjectV", e);
        }

        return super.newObjectV(vm, dvmClass, dvmMethod, vaList);
    }

    @Override
    public float callStaticFloatMethod(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor);
            return (Float) proxyCall.call(vm, null);
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticFloatMethod", e);
        }
        return super.callStaticFloatMethod(vm, dvmClass, dvmMethod, varArg);
    }

    @Override
    public double callStaticDoubleMethod(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor);
            return (Double) proxyCall.call(vm, null);
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticDoubleMethod", e);
        }
        return super.callStaticDoubleMethod(vm, dvmClass, dvmMethod, varArg);
    }

    @Override
    public void callStaticVoidMethod(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor);
            proxyCall.call(vm, null);
            return;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticVoidMethod", e);
        }
        super.callStaticVoidMethod(vm, dvmClass, dvmMethod, varArg);
    }

    @Override
    public void callStaticVoidMethodV(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor);
            proxyCall.call(vm, null);
            return;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticVoidMethodV", e);
        }
        super.callStaticVoidMethodV(vm, dvmClass, dvmMethod, vaList);
    }

    @Override
    public boolean callStaticBooleanMethod(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor);
            Object obj = proxyCall.call(vm, null);
            return (Boolean) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return false;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticBooleanMethod", e);
        }
        return super.callStaticBooleanMethod(vm, dvmClass, dvmMethod, varArg);
    }

    @Override
    public boolean callStaticBooleanMethodV(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor);
            Object obj = proxyCall.call(vm, null);
            return (Boolean) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return false;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticBooleanMethodV", e);
        }
        return super.callStaticBooleanMethodV(vm, dvmClass, dvmMethod, vaList);
    }

    @Override
    public int callStaticIntMethod(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor);
            Object obj = proxyCall.call(vm, null);
            return (Integer) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticIntMethod", e);
        }
        return super.callStaticIntMethod(vm, dvmClass, dvmMethod, varArg);
    }

    @Override
    public int callStaticIntMethodV(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor);
            Object obj = proxyCall.call(vm, null);
            return (Integer) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticIntMethodV", e);
        }
        return super.callStaticIntMethodV(vm, dvmClass, dvmMethod, vaList);
    }

    @Override
    public long callStaticLongMethod(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor);
            Object obj = proxyCall.call(vm, null);
            return (Long) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticLongMethod", e);
        }
        return super.callStaticLongMethod(vm, dvmClass, dvmMethod, varArg);
    }

    @Override
    public long callStaticLongMethodV(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor);
            Object obj = proxyCall.call(vm, null);
            return (Long) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticLongMethodV", e);
        }
        return super.callStaticLongMethodV(vm, dvmClass, dvmMethod, vaList);
    }

    @Override
    public DvmObject<?> callStaticObjectMethod(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor);
            Object obj = proxyCall.call(vm, null);
            return ProxyDvmObject.createObject(vm, obj);
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return null;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticObjectMethod", e);
        }
        return super.callStaticObjectMethod(vm, dvmClass, dvmMethod, varArg);
    }

    @Override
    public DvmObject<?> callStaticObjectMethodV(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor);
            Object obj = proxyCall.call(vm, null);
            return ProxyDvmObject.createObject(vm, obj);
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return null;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callStaticObjectMethodV", e);
        }
        return super.callStaticObjectMethodV(vm, dvmClass, dvmMethod, vaList);
    }

    @Override
    public DvmObject<?> toReflectedMethod(BaseVM vm, DvmClass dvmClass, DvmMethod dvmMethod) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            List<Class<?>> classes = new ArrayList<>(10);
            ProxyUtils.parseMethodArgs(dvmMethod, classes, clazz.getClassLoader());
            Class<?>[] types = classes.toArray(new Class[0]);
            Member method = ProxyUtils.matchMethodTypes(clazz, dvmMethod.getMethodName(), types, dvmMethod.isStatic());
            if (method instanceof Method) {
                return ProxyDvmObject.createObject(vm, new ProxyReflectedMethod((Method) method));
            } else {
                return ProxyDvmObject.createObject(vm, new ProxyReflectedConstructor((Constructor<?>) method));
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            log.warn("toReflectedMethod", e);
        }

        return super.toReflectedMethod(vm, dvmClass, dvmMethod);
    }

}
