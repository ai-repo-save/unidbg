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

/** Shared proxy ownership and field bindings. Lookup failures still use the configured fallback. */
class ProxyFieldJni extends JniFunction {
    protected static final Logger log = LoggerFactory.getLogger(ProxyJni.class);
    protected final ProxyClassLoader classLoader;
    protected final ProxyDvmObjectVisitor visitor;
    ProxyFieldJni(ProxyClassLoader loader, ProxyDvmObjectVisitor visitor, Jni fallback) {
        super(fallback); classLoader = loader; this.visitor = visitor;
    }
    protected final void pendingException(BaseVM vm, InvocationTargetException failure) {
        vm.throwException(ProxyDvmObject.createObject(vm, failure.getCause()));
    }

    @Override
    public DvmObject<?> getObjectField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            Object obj = field.get(thisObj);
            return ProxyDvmObject.createObject(vm, obj);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getObjectField: {}", dvmField, e);
        }

        return super.getObjectField(vm, dvmObject, dvmField);
    }

    @Override
    public long getLongField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            return field.getLong(thisObj);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getLongField: {}", dvmField, e);
        }

        return super.getLongField(vm, dvmObject, dvmField);
    }

    @Override
    public float getFloatField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            return field.getFloat(thisObj);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getFloatField: {}", dvmField, e);
        }

        return super.getFloatField(vm, dvmObject, dvmField);
    }

    @Override
    public boolean getBooleanField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            return field.getBoolean(thisObj);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getBooleanField: {}", dvmField, e);
        }
        return super.getBooleanField(vm, dvmObject, dvmField);
    }

    @Override
    public byte getByteField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            return field.getByte(thisObj);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getByteField: {}", dvmField, e);
        }
        return super.getByteField(vm, dvmObject, dvmField);
    }

    @Override
    public int getIntField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            return field.getInt(thisObj);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getIntField: {}", dvmField, e);
        }
        return super.getIntField(vm, dvmObject, dvmField);
    }

    @Override
    public void setIntField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField, int value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            field.setInt(thisObj, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setIntField: {}", dvmField, e);
        }
        super.setIntField(vm, dvmObject, dvmField, value);
    }

    @Override
    public void setFloatField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField, float value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            field.setFloat(thisObj, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setFloatField: {}", dvmField, e);
        }
        super.setFloatField(vm, dvmObject, dvmField, value);
    }

    @Override
    public void setDoubleField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField, double value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            field.setDouble(thisObj, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setDoubleField: {}", dvmField, e);
        }
        super.setDoubleField(vm, dvmObject, dvmField, value);
    }

    @Override
    public void setLongField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField, long value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            field.setLong(thisObj, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setLongField: {}", dvmField, e);
        }
        super.setLongField(vm, dvmObject, dvmField, value);
    }

    @Override
    public void setBooleanField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField, boolean value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            field.setBoolean(thisObj, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setBooleanField: {}", dvmField, e);
        }
        super.setBooleanField(vm, dvmObject, dvmField, value);
    }

    @Override
    public void setObjectField(BaseVM vm, DvmObject<?> dvmObject, DvmField dvmField, DvmObject<?> value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmObject.getObjectType().getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object thisObj = dvmObject.getValue();
            if (thisObj == null) {
                throw new IllegalStateException("obj is null: " + dvmObject);
            }
            field.setObject(thisObj, value == null ? null : value.getValue());
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setObjectField: {}", dvmField, e);
        }
        super.setObjectField(vm, dvmObject, dvmField, value);
    }

    @Override
    public DvmObject<?> getStaticObjectField(BaseVM vm, DvmClass dvmClass, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            Object obj = field.get(null);
            return ProxyDvmObject.createObject(vm, obj);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getStaticObjectField", e);
        }

        return super.getStaticObjectField(vm, dvmClass, dvmField);
    }

    @Override
    public boolean getStaticBooleanField(BaseVM vm, DvmClass dvmClass, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            return field.getBoolean(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getStaticBooleanField", e);
        }

        return super.getStaticBooleanField(vm, dvmClass, dvmField);
    }

    @Override
    public byte getStaticByteField(BaseVM vm, DvmClass dvmClass, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            return field.getByte(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getStaticByteField", e);
        }
        return super.getStaticByteField(vm, dvmClass, dvmField);
    }

    @Override
    public int getStaticIntField(BaseVM vm, DvmClass dvmClass, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            return field.getInt(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getStaticIntField", e);
        }

        return super.getStaticIntField(vm, dvmClass, dvmField);
    }

    @Override
    public long getStaticLongField(BaseVM vm, DvmClass dvmClass, DvmField dvmField) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            return field.getLong(null);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("getStaticLongField", e);
        }
        return super.getStaticLongField(vm, dvmClass, dvmField);
    }

    @Override
    public void setStaticBooleanField(BaseVM vm, DvmClass dvmClass, DvmField dvmField, boolean value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            field.setBoolean(null, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setStaticBooleanField", e);
        }
        super.setStaticBooleanField(vm, dvmClass, dvmField, value);
    }

    @Override
    public void setStaticIntField(BaseVM vm, DvmClass dvmClass, DvmField dvmField, int value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            field.setInt(null, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setStaticIntField", e);
        }
        super.setStaticIntField(vm, dvmClass, dvmField, value);
    }

    @Override
    public void setStaticObjectField(BaseVM vm, DvmClass dvmClass, DvmField dvmField, DvmObject<?> value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            field.setObject(null, value == null ? null : value.getValue());
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setStaticObjectField", e);
        }
        super.setStaticObjectField(vm, dvmClass, dvmField, value);
    }

    @Override
    public void setStaticLongField(BaseVM vm, DvmClass dvmClass, DvmField dvmField, long value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            field.setLong(null, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setStaticLongField", e);
        }
        super.setStaticLongField(vm, dvmClass, dvmField, value);
    }

    @Override
    public void setStaticFloatField(BaseVM vm, DvmClass dvmClass, DvmField dvmField, float value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            field.setFloat(null, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setStaticFloatField", e);
        }
        super.setStaticFloatField(vm, dvmClass, dvmField, value);
    }

    @Override
    public void setStaticDoubleField(BaseVM vm, DvmClass dvmClass, DvmField dvmField, double value) {
        try {
            Class<?> clazz = classLoader.loadClass(dvmClass.getName());
            ProxyField field = ProxyUtils.findField(clazz, dvmField, visitor);
            field.setDouble(null, value);
            return;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.warn("setStaticDoubleField", e);
        }
        super.setStaticDoubleField(vm, dvmClass, dvmField, value);
    }
}
