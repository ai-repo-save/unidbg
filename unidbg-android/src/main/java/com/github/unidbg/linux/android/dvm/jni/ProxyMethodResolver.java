package com.github.unidbg.linux.android.dvm.jni;

import org.apache.commons.lang3.ClassUtils;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class ProxyMethodResolver {

    private ProxyMethodResolver() {
    }

    static Member resolve(Member member) {
        if (!(member instanceof Method) || !Modifier.isPublic(member.getModifiers()) ||
                Modifier.isStatic(member.getModifiers())) {
            return member;
        }
        Method method = (Method) member;
        if (isPubliclyAccessible(method)) {
            return method;
        }
        for (Class<?> type : ClassUtils.hierarchy(method.getDeclaringClass(), ClassUtils.Interfaces.INCLUDE)) {
            Method candidate;
            try {
                candidate = type.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                continue;
            }
            if (!Modifier.isStatic(candidate.getModifiers()) && isPubliclyAccessible(candidate)) {
                return candidate;
            }
        }
        return method;
    }

    private static boolean isPubliclyAccessible(Method method) {
        try {
            MethodHandles.publicLookup().unreflect(method);
            return true;
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }
}
