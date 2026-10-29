package com.github.unidbg.linux.android.dvm.jni;

import com.github.unidbg.linux.android.dvm.DvmMethod;
import com.github.unidbg.linux.android.dvm.VarArg;
import com.github.unidbg.linux.android.dvm.host.JvmInvocationBridge;

import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class ProxyInvocation implements ProxyCall {
    private final Class<?> dispatch;
    private final Member member;
    private final Object[] arguments;
    private final ProxyDvmObjectVisitor visitor;

    private ProxyInvocation(Class<?> dispatch, Member member, Object[] arguments, ProxyDvmObjectVisitor visitor) {
        this.dispatch = dispatch;
        this.member = member;
        this.arguments = arguments;
        this.visitor = visitor;
    }

    static ProxyInvocation bind(Class<?> dispatch, DvmMethod method, VarArg arguments, ProxyDvmObjectVisitor visitor)
            throws NoSuchMethodException {
        MethodType signature = MethodType.fromMethodDescriptorString(method.getArgs(), dispatch.getClassLoader());
        Member member = method.isConstructor() ? dispatch.getDeclaredConstructor(signature.parameterArray())
                : findMethod(dispatch, method.getMethodName(), signature);
        if (member == null) throw new NoSuchMethodException(dispatch.getName() + "." + method.getMethodName() + signature);
        return new ProxyInvocation(dispatch, member,
                ProxyUtils.unpackArguments(method, arguments, dispatch.getClassLoader()), visitor);
    }

    private static Method findMethod(Class<?> type, String name, MethodType signature) {
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getName().equals(name)
                    && MethodType.methodType(method.getReturnType(), method.getParameterTypes()).equals(signature)) {
                return method;
            }
        }
        Class<?> parent = type.getSuperclass();
        if (parent != null) {
            Method method = findMethod(parent, name, signature);
            if (method != null) return method;
        }
        for (Class<?> contract : type.getInterfaces()) {
            Method method = findMethod(contract, name, signature);
            if (method != null) return method;
        }
        return null;
    }

    static Object value(JvmInvocationBridge.Result result) {
        if (result.targetThrowable() != null) throw new ProxyTargetException(result.targetThrowable());
        return result.value();
    }

    @Override
    public Object call(Object receiver) {
        if (visitor != null) visitor.onProxyVisit(member, receiver, arguments);
        Object result = value(member instanceof Constructor
                ? JvmInvocationBridge.construct(receiver, dispatch, (Constructor<?>) member, arguments)
                : JvmInvocationBridge.nonvirtual(receiver, dispatch, (Method) member, arguments));
        if (visitor != null) result = visitor.postProxyVisit(member, receiver, arguments, result);
        return result;
    }
}
