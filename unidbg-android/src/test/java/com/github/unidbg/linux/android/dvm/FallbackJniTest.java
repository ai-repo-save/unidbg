package com.github.unidbg.linux.android.dvm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** FallbackJni 语义: 未覆写 JNI 回调 → 聚合 WARN + 类型化默认值, 不抛异常。 */
public class FallbackJniTest {

    @Test
    public void unimplementedMethodsReturnTypedDefaults() {
        FallbackJni jni = new FallbackJni() {};

        assertEquals(0, jni.callStaticIntMethodV(null, null, "test/x->a()I", null));
        assertEquals(0L, jni.callStaticLongMethodV(null, null, "test/x->b()J", null));
        assertEquals(false, jni.callStaticBooleanMethodV(null, null, "test/x->c()Z", null));
        assertEquals(0, jni.callIntMethodV(null, null, "test/x->d()I", null));
        assertNull(jni.callObjectMethodV(null, null, "test/x->e()V", null));
        assertNull(jni.callStaticObjectMethodV(null, null, "test/x->f()V", null));
        assertEquals(0, jni.getIntField(null, null, "test/x->g()I"));
        assertEquals((char) 0, jni.getCharField(null, null, "test/x->getCharField"));
        assertEquals((short) 0, jni.getShortField(null, null, "test/x->getShortField"));
        assertEquals(0d, jni.getDoubleField(null, null, "test/x->getDoubleField"), 0);
        assertEquals((char) 0, jni.getStaticCharField(null, null, "test/x->getStaticCharField"));
        assertEquals((short) 0, jni.getStaticShortField(null, null, "test/x->getStaticShortField"));
        assertEquals(0f, jni.getStaticFloatField(null, null, "test/x->getStaticFloatField"), 0);
        assertEquals(0d, jni.getStaticDoubleField(null, null, "test/x->getStaticDoubleField"), 0);
        assertEquals((byte) 0, jni.callStaticByteMethodV(null, null, "test/x->callStaticByteMethodV", null));
        assertEquals((char) 0, jni.callStaticCharMethodV(null, null, "test/x->callStaticCharMethodV", null));
        assertEquals((short) 0, jni.callStaticShortMethodV(null, null, "test/x->callStaticShortMethodV", null));
        assertEquals(0f, jni.callStaticFloatMethodV(null, null, "test/x->callStaticFloatMethodV", null), 0);
        assertEquals(0d, jni.callStaticDoubleMethodV(null, null, "test/x->callStaticDoubleMethodV", null), 0);
        assertEquals(0d, jni.callDoubleMethodV(null, null, "test/x->callDoubleMethodV", null), 0);
        // void/写操作静默通过
        jni.callStaticVoidMethodV(null, null, "test/x->h()V", null);
        jni.setIntField(null, null, "test/x->i()V", 42);
    }

    @Test
    public void subclassOverrideWins() {
        FallbackJni jni = new FallbackJni() {
            @Override
            public int callStaticIntMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
                return 77;
            }
        };
        assertEquals(77, jni.callStaticIntMethodV(null, null, "test/x->a()I", null));
    }
}
