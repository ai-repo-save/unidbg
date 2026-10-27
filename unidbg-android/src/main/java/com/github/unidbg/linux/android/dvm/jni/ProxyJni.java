package com.github.unidbg.linux.android.dvm.jni;

import com.github.unidbg.linux.android.dvm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Binds host-only reflection plans; synchronous Jni callbacks belong to the modeled fallback. */
public class ProxyJni extends JniFunction implements HostJni {
    private static final Logger log = LoggerFactory.getLogger(ProxyJni.class);
    private final ProxyClassLoader classLoader;
    private final ProxyDvmObjectVisitor visitor;

    private interface Alternative { JniCallPlan bind(HostJni provider) throws ReflectiveOperationException; }

    public ProxyJni(ProxyClassLoader loader, ProxyDvmObjectVisitor visitor, Jni fallback) {
        super(fallback);
        this.classLoader = loader;
        this.visitor = visitor;
    }

    private JniCallPlan unresolved(String signature, ReflectiveOperationException missing, Alternative alternative)
            throws ReflectiveOperationException {
        log.debug("event=jni_host_binding_unresolved signature={}", signature, missing);
        Jni fallback = configuredFallback();
        if (fallback == null) throw new UnsupportedOperationException(signature, missing);
        return fallback instanceof HostJni ? alternative.bind((HostJni) fallback) : JniCallPlan.backend(fallback);
    }

    @Override
    public JniCallPlan planMethod(DvmClass type, DvmObject<?> receiver, DvmMethod method,
                                  VarArg arguments, boolean isStatic) throws ReflectiveOperationException {
        try {
            ProxyCall call = ProxyUtils.findMethod(classLoader.loadClass(type), method, arguments, isStatic, visitor);
            Object target = isStatic ? null : ProxyDvmObject.unpack(receiver);
            return JniCallPlan.host(() -> call.call(target));
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return unresolved(method.getSignature(), missing, provider -> provider.planMethod(type, receiver, method, arguments, isStatic));
        }
    }

    @Override
    public JniCallPlan planConstructor(DvmClass type, DvmMethod method, VarArg arguments)
            throws ReflectiveOperationException {
        try {
            ProxyCall call = ProxyUtils.findConstructor(classLoader.loadClass(type), method, arguments, visitor);
            return JniCallPlan.host(() -> call.call(null));
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return unresolved(method.getSignature(), missing, provider -> provider.planConstructor(type, method, arguments));
        }
    }

    @Override
    public JniCallPlan planAllocation(DvmClass type) throws ReflectiveOperationException {
        try {
            ProxyCall call = ProxyUtils.findAllocConstructor(classLoader.loadClass(type), visitor);
            return JniCallPlan.host(() -> call.call(null));
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return unresolved(type.getClassName() + "->allocObject", missing, provider -> provider.planAllocation(type));
        }
    }

    @Override
    public JniCallPlan planField(DvmClass type, DvmObject<?> receiver, DvmField field,
                                 boolean isStatic, boolean write, Object value) throws ReflectiveOperationException {
        try {
            ProxyField bound = ProxyUtils.findField(classLoader.loadClass(type), field, visitor);
            Object target = isStatic ? null : ProxyDvmObject.unpack(receiver);
            String descriptor = field.getFieldType();
            Object hostValue = write && (descriptor.startsWith("L") || descriptor.startsWith("["))
                    ? ProxyDvmObject.unpack((DvmObject<?>) value) : value;
            return JniCallPlan.host(() -> {
                if (write) { bound.setObject(target, hostValue); return null; }
                return bound.get(target);
            });
        } catch (ClassNotFoundException | NoSuchFieldException missing) {
            String signature = type.getClassName() + "->" + field.getFieldName() + ":" + field.getFieldType();
            return unresolved(signature, missing, provider -> provider.planField(type, receiver, field, isStatic, write, value));
        }
    }

    @Override
    public JniCallPlan planException(DvmClass type, String message) throws ReflectiveOperationException {
        Class<?> clazz = classLoader.loadClass(type);
        if (!Throwable.class.isAssignableFrom(clazz)) throw new IllegalArgumentException("ThrowNew requires a Throwable class");
        ProxyCall call = new ProxyConstructor(visitor, clazz.getConstructor(String.class), new Object[] {message});
        return JniCallPlan.host(() -> call.call(null));
    }

    @Override
    public DvmObject<?> toReflectedMethod(BaseVM vm, DvmClass type, DvmMethod method) {
        try {
            Class<?> clazz = classLoader.loadClass(type);
            List<Class<?>> parameters = new ArrayList<>();
            ProxyUtils.parseMethodArgs(method, parameters, clazz.getClassLoader());
            Member original = ProxyUtils.matchMethodTypes(clazz, method.getMethodName(), parameters.toArray(new Class<?>[0]), method.isStatic());
            return ProxyDvmObject.createObject(vm, original instanceof Method
                    ? new ProxyReflectedMethod((Method) original) : new ProxyReflectedConstructor((Constructor<?>) original));
        } catch (ClassNotFoundException | NoSuchMethodException failure) {
            log.debug("event=jni_reflection_metadata_unresolved signature={}", method.getSignature(), failure);
            return super.toReflectedMethod(vm, type, method);
        }
    }
}
