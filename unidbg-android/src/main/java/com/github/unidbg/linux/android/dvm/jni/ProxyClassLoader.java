package com.github.unidbg.linux.android.dvm.jni;

import org.apache.commons.lang3.ClassUtils;

public class ProxyClassLoader {

    private final ClassLoader classLoader;

    ProxyClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    private ProxyClassMapper classNameMapper;

    final void setClassNameMapper(ProxyClassMapper classNameMapper) {
        this.classNameMapper = classNameMapper;
    }

    final Class<?> loadClass(String name) throws ClassNotFoundException {
        Class<?> newClass = classNameMapper == null ? null : classNameMapper.map(name);
        if (newClass != null) {
            return newClass;
        }
        Class<?> resolved = ClassUtils.getClassStrict(classLoader, name, false);
        if (!resolved.getName().equals(name)) {
            throw new ClassNotFoundException(name);
        }
        return resolved;
    }

}
