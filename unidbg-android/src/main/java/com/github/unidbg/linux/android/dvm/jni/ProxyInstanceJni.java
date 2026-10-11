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

/** Instance method proxy bindings; target exceptions are JNI pending exceptions. */
class ProxyInstanceJni extends ProxyFieldJni {
    ProxyInstanceJni(ProxyClassLoader loader, ProxyDvmObjectVisitor visitor, Jni fallback) { super(loader, visitor, fallback); }

    @Override
    public void callVoidMethod(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            proxyCall.call(vm, thisObj);
            return;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callVoidMethod", e);
        }
        super.callVoidMethod(vm, dvmObject, dvmMethod, varArg);
    }

    @Override
    public void callVoidMethodV(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            proxyCall.call(vm, thisObj);
            return;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callVoidMethodV", e);
        }
        super.callVoidMethodV(vm, dvmObject, dvmMethod, vaList);
    }

    @Override
    public boolean callBooleanMethod(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Boolean) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return false;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callBooleanMethod", e);
        }
        return super.callBooleanMethod(vm, dvmObject, dvmMethod, varArg);
    }

    @Override
    public boolean callBooleanMethodV(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Boolean) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return false;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callBooleanMethodV", e);
        }
        return super.callBooleanMethodV(vm, dvmObject, dvmMethod, vaList);
    }

    @Override
    public int callIntMethod(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Integer) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callIntMethod", e);
        }
        return super.callIntMethod(vm, dvmObject, dvmMethod, varArg);
    }

    @Override
    public double callDoubleMethod(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Double) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callDoubleMethod", e);
        }
        return super.callDoubleMethod(vm, dvmObject, dvmMethod, varArg);
    }

    @Override
    public byte callByteMethodV(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Byte) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callByteMethodV", e);
        }
        return super.callByteMethodV(vm, dvmObject, dvmMethod, vaList);
    }

    @Override
    public short callShortMethodV(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Short) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callShortMethodV", e);
        }
        return super.callShortMethodV(vm, dvmObject, dvmMethod, vaList);
    }

    @Override
    public int callIntMethodV(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Integer) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callIntMethodV", e);
        }
        return super.callIntMethodV(vm, dvmObject, dvmMethod, vaList);
    }

    @Override
    public DvmObject<?> callObjectMethod(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return ProxyDvmObject.createObject(vm, obj);
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return null;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callObjectMethod", e);
        }
        return super.callObjectMethod(vm, dvmObject, dvmMethod, varArg);
    }

    @Override
    public DvmObject<?> callObjectMethodV(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return ProxyDvmObject.createObject(vm, obj);
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return null;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callObjectMethodV", e);
        }

        return super.callObjectMethodV(vm, dvmObject, dvmMethod, vaList);
    }

    @Override
    public long callLongMethod(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VarArg varArg) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Long) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callLongMethod", e);
        }
        return super.callLongMethod(vm, dvmObject, dvmMethod, varArg);
    }

    @Override
    public long callLongMethodV(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Long) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callLongMethodV", e);
        }
        return super.callLongMethodV(vm, dvmObject, dvmMethod, vaList);
    }

    @Override
    public char callCharMethodV(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Character) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callCharMethodV", e);
        }
        return super.callCharMethodV(vm, dvmObject, dvmMethod, vaList);
    }

    @Override
    public float callFloatMethodV(BaseVM vm, DvmObject<?> dvmObject, DvmMethod dvmMethod, VaList vaList) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyCall proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = proxyCall.call(vm, thisObj);
            return (Float) obj;
        } catch (InvocationTargetException e) {
            pendingException(vm, e);
            return 0;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            log.warn("callFloatMethodV", e);
        }
        return super.callFloatMethodV(vm, dvmObject, dvmMethod, vaList);
    }

}
