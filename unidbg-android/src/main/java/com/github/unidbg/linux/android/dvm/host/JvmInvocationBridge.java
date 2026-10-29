package com.github.unidbg.linux.android.dvm.host;

import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/** Host-only standard JNI operations; callers schedule these on the owning guest Java thread. */
public final class JvmInvocationBridge {
    static {
        JvmNativeLibrary.initialize();
    }

    public static final class Result {
        private final Object value;
        private final Throwable targetThrowable;

        private Result(Object value, Throwable targetThrowable) {
            this.value = value;
            this.targetThrowable = targetThrowable;
        }

        public Object value() {
            return value;
        }

        public Throwable targetThrowable() {
            return targetThrowable;
        }
    }

    public static Result allocate(Class<?> targetClass) {
        Objects.requireNonNull(targetClass, "targetClass");
        if (targetClass.isPrimitive() || targetClass.isArray()) {
            throw new IllegalArgumentException("AllocObject requires a non-array reference class");
        }
        return allocate0(targetClass);
    }

    public static Result construct(Object sameReceiver, Class<?> dispatchClass,
                                   Constructor<?> ctor, Object[] boxedArgs) {
        Objects.requireNonNull(ctor, "ctor");
        requireReceiver(sameReceiver, dispatchClass);
        if (ctor.getDeclaringClass() != dispatchClass) {
            throw new IllegalArgumentException("Constructor must be declared by dispatchClass");
        }
        Class<?>[] parameters = ctor.getParameterTypes();
        Object[] arguments = checkedArguments(parameters, boxedArgs);
        String descriptor = MethodType.methodType(void.class, parameters).toMethodDescriptorString();
        return invoke0(sameReceiver, dispatchClass, "<init>", descriptor, kinds(parameters), arguments, (byte) 'V');
    }

    public static Result nonvirtual(Object sameReceiver, Class<?> dispatchClass,
                                    Method original, Object[] boxedArgs) {
        Objects.requireNonNull(original, "original");
        requireReceiver(sameReceiver, dispatchClass);
        if (Modifier.isStatic(original.getModifiers()) ||
                !original.getDeclaringClass().isAssignableFrom(dispatchClass)) {
            throw new IllegalArgumentException("Method must be an instance member of dispatchClass");
        }
        Class<?>[] parameters = original.getParameterTypes();
        Object[] arguments = checkedArguments(parameters, boxedArgs);
        String descriptor = MethodType.methodType(original.getReturnType(), parameters).toMethodDescriptorString();
        return invoke0(sameReceiver, dispatchClass, original.getName(), descriptor,
                kinds(parameters), arguments, kind(original.getReturnType()));
    }

    private static void requireReceiver(Object receiver, Class<?> dispatchClass) {
        Objects.requireNonNull(dispatchClass, "dispatchClass");
        Objects.requireNonNull(receiver, "sameReceiver");
        if (!dispatchClass.isInstance(receiver)) {
            throw new IllegalArgumentException("Receiver must be an instance of dispatchClass");
        }
    }

    private static Object[] checkedArguments(Class<?>[] parameters, Object[] boxedArgs) {
        Object[] arguments = Objects.requireNonNull(boxedArgs, "boxedArgs").clone();
        if (arguments.length != parameters.length) {
            throw new IllegalArgumentException("Argument count differs from the declared parameter count");
        }
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i];
            Object argument = arguments[i];
            if (parameter.isPrimitive() ? !box(parameter).isInstance(argument)
                    : argument != null && !parameter.isInstance(argument)) {
                throw new IllegalArgumentException("Argument " + i + " must have declared type " + parameter.getTypeName());
            }
        }
        return arguments;
    }

    private static Class<?> box(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        throw new IllegalArgumentException("Not a parameter primitive: " + type);
    }

    private static byte[] kinds(Class<?>[] parameters) {
        byte[] result = new byte[parameters.length];
        for (int i = 0; i < result.length; i++) result[i] = kind(parameters[i]);
        return result;
    }

    private static byte kind(Class<?> type) {
        if (!type.isPrimitive()) return 'L';
        if (type == void.class) return 'V';
        if (type == boolean.class) return 'Z';
        if (type == byte.class) return 'B';
        if (type == char.class) return 'C';
        if (type == short.class) return 'S';
        if (type == int.class) return 'I';
        if (type == long.class) return 'J';
        if (type == float.class) return 'F';
        if (type == double.class) return 'D';
        throw new IllegalArgumentException("Unknown primitive: " + type);
    }

    private static native Result allocate0(Class<?> targetClass);
    private static native Result invoke0(Object receiver, Class<?> dispatchClass, String name,
                                         String descriptor, byte[] kinds, Object[] arguments, byte returnKind);

    private JvmInvocationBridge() {
    }
}
