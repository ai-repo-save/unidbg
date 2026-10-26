package com.github.unidbg.linux.android.dvm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JNI 全表回退基类(阶段2 现代化): 未覆写的 JNI 回调不再抛异常中断模拟,
 * 而是聚合 WARN + 返回类型化默认值(void 字段写操作静默跳过)。
 *
 * 用途: 接入新 so 时先跑起来, 从 WARN 汇总里找需要补桩的签名 —— 支撑
 * "先全默认跑通、再按缺口补桩"的接入流程; 行为确定化(默认值而非未定义)。
 *
 * 上游兼容: 纯新增类, 默认行为不变(AbstractJni 仍抛异常); 需要回退语义的
 * 桩改为继承本类。WARN 去重(每签名一次), 避免日志风暴。
 */
public class FallbackJni extends AbstractJni {

    private static final Logger log = LoggerFactory.getLogger(FallbackJni.class);

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static void warnFallback(String kind, String signature) {
        if (WARNED.add(kind + "|" + signature)) {
            log.warn("event=jni_fallback kind={} signature={} result=typed_default", kind, signature);
        }
    }

    @Override
    public boolean callStaticBooleanMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            return super.callStaticBooleanMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticBooleanMethodV", signature);
            return false;
        }
    }

    @Override
    public int callStaticIntMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            return super.callStaticIntMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticIntMethodV", signature);
            return 0;
        }
    }

    @Override
    public long callStaticLongMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            return super.callStaticLongMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticLongMethodV", signature);
            return 0L;
        }
    }

    @Override
    public byte callByteMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        try {
            return super.callByteMethodV(vm, dvmObject, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callByteMethodV", signature);
            return (byte) 0;
        }
    }

    @Override
    public char callCharMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        try {
            return super.callCharMethodV(vm, dvmObject, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callCharMethodV", signature);
            return (char) 0;
        }
    }

    @Override
    public short callShortMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        try {
            return super.callShortMethodV(vm, dvmObject, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callShortMethodV", signature);
            return (short) 0;
        }
    }

    @Override
    public float callFloatMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        try {
            return super.callFloatMethodV(vm, dvmObject, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callFloatMethodV", signature);
            return 0f;
        }
    }

    @Override
    public int callIntMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        try {
            return super.callIntMethodV(vm, dvmObject, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callIntMethodV", signature);
            return 0;
        }
    }

    @Override
    public long callLongMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        try {
            return super.callLongMethodV(vm, dvmObject, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callLongMethodV", signature);
            return 0L;
        }
    }

    @Override
    public boolean callStaticBooleanMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        try {
            return super.callStaticBooleanMethod(vm, dvmClass, signature, varArg);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticBooleanMethod", signature);
            return false;
        }
    }

    @Override
    public int callStaticIntMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        try {
            return super.callStaticIntMethod(vm, dvmClass, signature, varArg);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticIntMethod", signature);
            return 0;
        }
    }

    @Override
    public long callStaticLongMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        try {
            return super.callStaticLongMethod(vm, dvmClass, signature, varArg);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticLongMethod", signature);
            return 0L;
        }
    }

    @Override
    public float callStaticFloatMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        try {
            return super.callStaticFloatMethod(vm, dvmClass, signature, varArg);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticFloatMethod", signature);
            return 0f;
        }
    }

    @Override
    public double callStaticDoubleMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        try {
            return super.callStaticDoubleMethod(vm, dvmClass, signature, varArg);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticDoubleMethod", signature);
            return 0d;
        }
    }

    @Override
    public double callDoubleMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        try {
            return super.callDoubleMethod(vm, dvmObject, signature, varArg);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callDoubleMethod", signature);
            return 0d;
        }
    }

    @Override
    public void callStaticVoidMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        try {
            super.callStaticVoidMethod(vm, dvmClass, signature, varArg);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticVoidMethod", signature);
        }
    }

    @Override
    public void callStaticVoidMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            super.callStaticVoidMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticVoidMethodV", signature);
        }
    }

    @Override
    public void callVoidMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        try {
            super.callVoidMethod(vm, dvmObject, signature, varArg);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callVoidMethod", signature);
        }
    }

    @Override
    public DvmObject<?> callObjectMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        try {
            return super.callObjectMethodV(vm, dvmObject, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callObjectMethodV", signature);
            return null;
        }
    }

    @Override
    public DvmObject<?> callStaticObjectMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            return super.callStaticObjectMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticObjectMethodV", signature);
            return null;
        }
    }

    @Override
    public boolean getBooleanField(BaseVM vm, DvmObject<?> dvmObject, String signature) {
        try {
            return super.getBooleanField(vm, dvmObject, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getBooleanField", signature);
            return false;
        }
    }

    @Override
    public byte getByteField(BaseVM vm, DvmObject<?> dvmObject, String signature) {
        try {
            return super.getByteField(vm, dvmObject, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getByteField", signature);
            return (byte) 0;
        }
    }

    @Override
    public int getIntField(BaseVM vm, DvmObject<?> dvmObject, String signature) {
        try {
            return super.getIntField(vm, dvmObject, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getIntField", signature);
            return 0;
        }
    }

    @Override
    public long getLongField(BaseVM vm, DvmObject<?> dvmObject, String signature) {
        try {
            return super.getLongField(vm, dvmObject, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getLongField", signature);
            return 0L;
        }
    }

    @Override
    public float getFloatField(BaseVM vm, DvmObject<?> dvmObject, String signature) {
        try {
            return super.getFloatField(vm, dvmObject, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getFloatField", signature);
            return 0f;
        }
    }

    @Override
    public boolean getStaticBooleanField(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            return super.getStaticBooleanField(vm, dvmClass, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getStaticBooleanField", signature);
            return false;
        }
    }

    @Override
    public byte getStaticByteField(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            return super.getStaticByteField(vm, dvmClass, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getStaticByteField", signature);
            return (byte) 0;
        }
    }

    @Override
    public int getStaticIntField(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            return super.getStaticIntField(vm, dvmClass, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getStaticIntField", signature);
            return 0;
        }
    }

    @Override
    public long getStaticLongField(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            return super.getStaticLongField(vm, dvmClass, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getStaticLongField", signature);
            return 0L;
        }
    }

    @Override
    public DvmObject<?> toReflectedMethod(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            return super.toReflectedMethod(vm, dvmClass, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("toReflectedMethod", signature);
            return null;
        }
    }

    @Override
    public void setBooleanField(BaseVM vm, DvmObject<?> dvmObject, String signature, boolean value) {
        try {
            super.setBooleanField(vm, dvmObject, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setBooleanField", signature);
        }
    }

    @Override
    public void setDoubleField(BaseVM vm, DvmObject<?> dvmObject, String signature, double value) {
        try {
            super.setDoubleField(vm, dvmObject, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setDoubleField", signature);
        }
    }

    @Override
    public void setFloatField(BaseVM vm, DvmObject<?> dvmObject, String signature, float value) {
        try {
            super.setFloatField(vm, dvmObject, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setFloatField", signature);
        }
    }

    @Override
    public void setIntField(BaseVM vm, DvmObject<?> dvmObject, String signature, int value) {
        try {
            super.setIntField(vm, dvmObject, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setIntField", signature);
        }
    }

    @Override
    public void setLongField(BaseVM vm, DvmObject<?> dvmObject, String signature, long value) {
        try {
            super.setLongField(vm, dvmObject, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setLongField", signature);
        }
    }

    @Override
    public void setObjectField(BaseVM vm, DvmObject<?> dvmObject, String signature, DvmObject<?> value) {
        try {
            super.setObjectField(vm, dvmObject, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setObjectField", signature);
        }
    }

    @Override
    public void setStaticBooleanField(BaseVM vm, DvmClass dvmClass, String signature, boolean value) {
        try {
            super.setStaticBooleanField(vm, dvmClass, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setStaticBooleanField", signature);
        }
    }

    @Override
    public void setStaticDoubleField(BaseVM vm, DvmClass dvmClass, String signature, double value) {
        try {
            super.setStaticDoubleField(vm, dvmClass, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setStaticDoubleField", signature);
        }
    }

    @Override
    public void setStaticFloatField(BaseVM vm, DvmClass dvmClass, String signature, float value) {
        try {
            super.setStaticFloatField(vm, dvmClass, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setStaticFloatField", signature);
        }
    }

    @Override
    public void setStaticIntField(BaseVM vm, DvmClass dvmClass, String signature, int value) {
        try {
            super.setStaticIntField(vm, dvmClass, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setStaticIntField", signature);
        }
    }

    @Override
    public void setStaticLongField(BaseVM vm, DvmClass dvmClass, String signature, long value) {
        try {
            super.setStaticLongField(vm, dvmClass, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setStaticLongField", signature);
        }
    }

    @Override
    public void setStaticObjectField(BaseVM vm, DvmClass dvmClass, String signature, DvmObject<?> value) {
        try {
            super.setStaticObjectField(vm, dvmClass, signature, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setStaticObjectField", signature);
        }
    }

    @Override
    public void setStaticObjectField(BaseVM vm, DvmClass dvmClass, DvmField dvmField, DvmObject<?> value) {
        try {
            super.setStaticObjectField(vm, dvmClass, dvmField, value);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("setStaticObjectField", dvmField == null ? "?" : dvmField.getSignature());
        }
    }


    @Override
    public char getCharField(BaseVM vm, DvmObject<?> dvmObject, String signature) {
        try {
            return super.getCharField(vm, dvmObject, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getCharField", signature);
            return (char) 0;
        }
    }

    @Override
    public short getShortField(BaseVM vm, DvmObject<?> dvmObject, String signature) {
        try {
            return super.getShortField(vm, dvmObject, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getShortField", signature);
            return (short) 0;
        }
    }

    @Override
    public double getDoubleField(BaseVM vm, DvmObject<?> dvmObject, String signature) {
        try {
            return super.getDoubleField(vm, dvmObject, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getDoubleField", signature);
            return 0d;
        }
    }

    @Override
    public char getStaticCharField(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            return super.getStaticCharField(vm, dvmClass, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getStaticCharField", signature);
            return (char) 0;
        }
    }

    @Override
    public short getStaticShortField(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            return super.getStaticShortField(vm, dvmClass, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getStaticShortField", signature);
            return (short) 0;
        }
    }

    @Override
    public float getStaticFloatField(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            return super.getStaticFloatField(vm, dvmClass, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getStaticFloatField", signature);
            return 0f;
        }
    }

    @Override
    public double getStaticDoubleField(BaseVM vm, DvmClass dvmClass, String signature) {
        try {
            return super.getStaticDoubleField(vm, dvmClass, signature);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("getStaticDoubleField", signature);
            return 0d;
        }
    }

    @Override
    public byte callStaticByteMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            return super.callStaticByteMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticByteMethodV", signature);
            return (byte) 0;
        }
    }

    @Override
    public char callStaticCharMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            return super.callStaticCharMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticCharMethodV", signature);
            return (char) 0;
        }
    }

    @Override
    public short callStaticShortMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            return super.callStaticShortMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticShortMethodV", signature);
            return (short) 0;
        }
    }

    @Override
    public float callStaticFloatMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            return super.callStaticFloatMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticFloatMethodV", signature);
            return 0f;
        }
    }

    @Override
    public double callStaticDoubleMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        try {
            return super.callStaticDoubleMethodV(vm, dvmClass, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callStaticDoubleMethodV", signature);
            return 0d;
        }
    }

    @Override
    public double callDoubleMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        try {
            return super.callDoubleMethodV(vm, dvmObject, signature, vaList);
        } catch (UnsupportedOperationException | IllegalStateException e) {
            warnFallback("callDoubleMethodV", signature);
            return 0d;
        }
    }
}
