package com.github.unidbg.linux.android.dvm.jni;

import com.github.unidbg.linux.android.dvm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Invokes host-only reflection; synchronous Jni callbacks belong to the modeled fallback. */
public class ProxyJni extends JniFunction implements HostJni {
    private static final Logger log = LoggerFactory.getLogger(ProxyJni.class);
    private final ProxyClassLoader classLoader;
    private final ProxyDvmObjectVisitor visitor;

    private interface Alternative { Object invoke(HostJni provider) throws Throwable; }

    public ProxyJni(ProxyClassLoader loader, ProxyDvmObjectVisitor visitor, Jni fallback) {
        super(fallback);
        this.classLoader = loader;
        this.visitor = visitor;
    }

    private Object unresolved(String signature, ReflectiveOperationException missing, Alternative alternative, Function<Jni, Object> backend)
            throws Throwable {
        log.debug("event=jni_host_binding_unresolved signature={}", signature, missing);
        Jni fallback = configuredFallback();
        if (fallback == null) throw new UnsupportedOperationException(signature, missing);
        return fallback instanceof HostJni ? alternative.invoke((HostJni) fallback) : backend.apply(fallback);
    }

    @Override
    public Object invokeMethod(DvmClass type, DvmObject<?> receiver, DvmMethod method,
                                  VarArg arguments, boolean isStatic, Function<Jni, Object> backend, Completion completion) throws Throwable {
        try {
            ProxyCall call = ProxyUtils.findMethod(classLoader.loadClass(type), method, arguments, isStatic, visitor);
            Object target = isStatic ? null : ProxyDvmObject.unpack(receiver);
            String descriptor = method.getArgs();
            char resultType = descriptor.charAt(descriptor.lastIndexOf(')') + 1);
            return HostJni.submit(type.vm, resultType == '[' ? 'L' : resultType, () -> call.call(target), completion);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return unresolved(method.getSignature(), missing, provider -> provider.invokeMethod(type, receiver, method, arguments, isStatic, backend, completion), backend);
        }
    }

    @Override
    public Object invokeConstructor(DvmClass type, DvmMethod method, VarArg arguments, Function<Jni, Object> backend, Completion completion)
            throws Throwable {
        try {
            ProxyCall call = ProxyUtils.findConstructor(classLoader.loadClass(type), method, arguments, visitor);
            return HostJni.submit(type.vm, 'L', () -> call.call(null), completion);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return unresolved(method.getSignature(), missing, provider -> provider.invokeConstructor(type, method, arguments, backend, completion), backend);
        }
    }

    @Override
    public Object invokeAllocation(DvmClass type, Function<Jni, Object> backend, Completion completion) throws Throwable {
        try {
            ProxyCall call = ProxyUtils.findAllocConstructor(classLoader.loadClass(type), visitor);
            return HostJni.submit(type.vm, 'L', () -> call.call(null), completion);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return unresolved(type.getClassName() + "->allocObject", missing, provider -> provider.invokeAllocation(type, backend, completion), backend);
        }
    }

    @Override
    public Object invokeField(DvmClass type, DvmObject<?> receiver, DvmField field,
                                 boolean isStatic, boolean write, Object value, Function<Jni, Object> backend, Completion completion) throws Throwable {
        try {
            ProxyField bound = ProxyUtils.findField(classLoader.loadClass(type), field, visitor);
            Object target = isStatic ? null : ProxyDvmObject.unpack(receiver);
            String descriptor = field.getFieldType();
            Object hostValue = write && (descriptor.startsWith("L") || descriptor.startsWith("["))
                    ? ProxyDvmObject.unpack((DvmObject<?>) value) : value;
            char resultType = write ? 'V' : descriptor.charAt(0);
            return HostJni.submit(type.vm, resultType == '[' ? 'L' : resultType, () -> {
                if (write) { bound.setObject(target, hostValue); return null; }
                return bound.get(target);
            }, completion);
        } catch (ClassNotFoundException | NoSuchFieldException missing) {
            String signature = type.getClassName() + "->" + field.getFieldName() + ":" + field.getFieldType();
            return unresolved(signature, missing, provider -> provider.invokeField(type, receiver, field, isStatic, write, value, backend, completion), backend);
        }
    }

    @Override
    public Object invokeException(DvmClass type, String message, Function<Jni, Object> backend, Completion completion) throws Throwable {
        Class<?> clazz = classLoader.loadClass(type);
        if (!Throwable.class.isAssignableFrom(clazz)) throw new IllegalArgumentException("ThrowNew requires a Throwable class");
        ProxyCall call = new ProxyConstructor(visitor, clazz.getConstructor(String.class), new Object[] {message});
        return HostJni.submit(type.vm, 'L', () -> call.call(null), completion);
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
