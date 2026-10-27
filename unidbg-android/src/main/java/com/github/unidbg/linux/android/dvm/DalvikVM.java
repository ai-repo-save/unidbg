package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.ArmHook;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.HookStatus;
import com.github.unidbg.arm.backend.BackendException;
import com.github.unidbg.arm.context.EditableArm32RegisterContext;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.linux.android.dvm.apk.Apk;
import com.github.unidbg.linux.android.dvm.array.ArrayObject;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.android.dvm.array.DoubleArray;
import com.github.unidbg.linux.android.dvm.array.FloatArray;
import com.github.unidbg.linux.android.dvm.array.IntArray;
import com.github.unidbg.linux.android.dvm.array.PrimitiveArray;
import com.github.unidbg.linux.android.dvm.array.ShortArray;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.utils.Inspector;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public class DalvikVM extends BaseVM implements VM {

    private static final Logger log = LoggerFactory.getLogger(DalvikVM.class);

    private final UnidbgPointer _JavaVM;

    public DalvikVM(AndroidEmulator emulator, File apkFile) {
        super(emulator, apkFile);

        final SvcMemory svcMemory = emulator.getSvcMemory();
        _JavaVM = svcMemory.allocate(emulator.getPointerSize(), "_JavaVM");

        Pointer _GetVersion = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                return JNI_VERSION_1_6;
            }
        });

        Pointer _DefineClass = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _FindClass = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                Pointer env = context.getPointerArg(0);
                Pointer className = context.getPointerArg(1);
                String name = JniStringSupport.readModifiedUtf8(className);

                boolean notFound = notFoundClassSet.contains(name);
                if (verbose) {
                    if (notFound) {
                        System.out.printf("JNIEnv->FindNoClass(%s) was called from %s%n", name, context.getLRPointer());
                    } else {
                        System.out.printf("JNIEnv->FindClass(%s) was called from %s%n", name, context.getLRPointer());
                    }
                }

                if (notFound) {
                    throwException(resolveClass("java/lang/NoClassDefFoundError").newObject(name));
                    return 0;
                }

                DvmClass dvmClass = resolveClass(name);
                long hash = addLocalObject(dvmClass) & 0xffffffffL;
                if (log.isDebugEnabled()) {
                    log.debug("FindClass env={}, className={}, hash=0x{}", env, name, Long.toHexString(hash));
                }
                return hash;
            }
        });

        Pointer _FromReflectedMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _FromReflectedField = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _ToReflectedMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer clazz = context.getPointerArg(1);
                UnidbgPointer jmethodID = context.getPointerArg(2);
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                DvmMethod dvmMethod = null;
                if (dvmClass != null) {
                    dvmMethod = dvmClass.getStaticMethod(jmethodID.toIntPeer());
                    if (dvmMethod == null) {
                        dvmMethod = dvmClass.getMethod(jmethodID.toIntPeer());
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("ToReflectedMethod clazz={}, jmethodID={}, lr={}", dvmClass, jmethodID, context.getLRPointer());
                }
                if (dvmMethod == null) {
                    throw new BackendException();
                } else {
                    if (verbose) {
                        System.out.printf("JNIEnv->ToReflectedMethod(%s, %s, %s) was called from %s%n", dvmClass.getClassName(), dvmMethod.methodName, dvmMethod.isStatic ? "is static" : "not static", context.getLRPointer());
                    }

                    return addLocalObject(dvmMethod.toReflectedMethod());
                }
            }
        });
        
        Pointer _GetSuperclass = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer clazz = context.getPointerArg(1);
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                if (verbose) {
                    System.out.printf("JNIEnv->GetSuperClass(%s) was called from %s%n", dvmClass, context.getLRPointer());
                }
                if (dvmClass.getClassName().equals("java/lang/Object")) {
                    if (log.isDebugEnabled()) {
                        log.debug("JNIEnv->GetSuperClass(class=Object) returns NULL per JNI spec.");
                    }
                    return 0;
                }
                DvmClass superClass = dvmClass.getSuperclass();
                if (superClass == null) {
                    // 未声明父类: 按 ART 语义回落 java/lang/Object, 不再抛异常中断模拟
                    if (log.isDebugEnabled()) {
                        log.debug("JNIEnv->GetSuperClass was called, class = {}, superClass undeclared, fallback to java/lang/Object.", dvmClass.getClassName());
                    }
                    return addLocalObject(resolveClass("java/lang/Object"));
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("JNIEnv->GetSuperClass was called, class = {}, superClass = {}", dvmClass.getClassName(), superClass.getClassName());
                    }
                    return addLocalObject(superClass);
                }
            }
        });

        Pointer _IsAssignableFrom = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _ToReflectedField = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _Throw = JniExceptionSupport.exception(emulator, DalvikVM.this, "Throw");

        Pointer _ThrowNew = JniExceptionSupport.exception(emulator, DalvikVM.this, "ThrowNew");

        Pointer _ExceptionOccurred = JniExceptionSupport.exception(emulator, DalvikVM.this, "ExceptionOccurred");

        Pointer _ExceptionDescribe = JniExceptionSupport.exception(emulator, DalvikVM.this, "ExceptionDescribe");

        Pointer _ExceptionClear = JniExceptionSupport.exception(emulator, DalvikVM.this, "ExceptionClear");

        Pointer _FatalError = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _PushLocalFrame = JniReferenceSupport.reference(emulator, DalvikVM.this, "PushLocalFrame");

        Pointer _PopLocalFrame = JniReferenceSupport.reference(emulator, DalvikVM.this, "PopLocalFrame");

        Pointer _NewGlobalRef = JniReferenceSupport.reference(emulator, DalvikVM.this, "NewGlobalRef");

        Pointer _DeleteGlobalRef = JniReferenceSupport.reference(emulator, DalvikVM.this, "DeleteGlobalRef");

        Pointer _DeleteLocalRef = JniReferenceSupport.reference(emulator, DalvikVM.this, "DeleteLocalRef");

        Pointer _IsSameObject = JniReferenceSupport.reference(emulator, DalvikVM.this, "IsSameObject");

        Pointer _NewLocalRef = JniReferenceSupport.reference(emulator, DalvikVM.this, "NewLocalRef");

        Pointer _EnsureLocalCapacity = JniReferenceSupport.reference(emulator, DalvikVM.this, "EnsureLocalCapacity");

        Pointer _AllocObject = JniInvocationSupport.allocate(emulator, DalvikVM.this);

        Pointer _NewObject = JniInvocationSupport.construct(emulator, DalvikVM.this, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _NewObjectV = JniInvocationSupport.construct(emulator, DalvikVM.this, JniInvocationSupport.Arguments.V);

        Pointer _NewObjectA = JniInvocationSupport.construct(emulator, DalvikVM.this, JniInvocationSupport.Arguments.A);

        Pointer _GetObjectClass = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                DvmObject<?> dvmObject = object == null ? null : resolveJniObject(object.toIntPeer());
                if (log.isDebugEnabled()) {
                    log.debug("GetObjectClass object={}, dvmObject={}", object, dvmObject);
                }
                if (dvmObject == null) {
                    throw new BackendException();
                } else {
                    DvmClass dvmClass = dvmObject.getObjectType();
                    return addLocalObject(dvmClass);
                }
            }
        });

        Pointer _IsInstanceOf = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                UnidbgPointer clazz = context.getPointerArg(2);
                DvmObject<?> dvmObject = object == null ? null : resolveJniObject(object.toIntPeer());
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                if (log.isDebugEnabled()) {
                    log.debug("IsInstanceOf object={}, clazz={}, dvmObject={}, dvmClass={}", object, clazz, dvmObject, dvmClass);
                }
                if (dvmObject == null || dvmClass == null) {
                    throw new BackendException();
                }
                boolean flag = dvmObject.isInstanceOf(dvmClass);
                return flag ? JNI_TRUE : JNI_FALSE;
            }
        });

        Pointer _GetMethodID = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer clazz = context.getPointerArg(1);
                Pointer methodName = context.getPointerArg(2);
                Pointer argsPointer = context.getPointerArg(3);
                String name = JniStringSupport.readModifiedUtf8(methodName);
                String args = JniStringSupport.readModifiedUtf8(argsPointer);
                if (log.isDebugEnabled()) {
                    log.debug("GetMethodID class={}, methodName={}, args={}, LR={}", clazz, name, args, context.getLRPointer());
                }
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                if (dvmClass == null) {
                    throw new BackendException();
                } else {
                    int hash = dvmClass.getMethodID(name, args);
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetMethodID(%s.%s%s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash & 0xffffffffL, context.getLRPointer());
                    }
                    return hash;
                }
            }
        });

        Pointer _CallObjectMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'L', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallObjectMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'L', false, JniInvocationSupport.Arguments.V);

        Pointer _CallObjectMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'L', false, JniInvocationSupport.Arguments.A);

        Pointer _CallBooleanMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'Z', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallBooleanMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'Z', false, JniInvocationSupport.Arguments.V);

        Pointer _CallBooleanMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'Z', false, JniInvocationSupport.Arguments.A);

        Pointer _CallByteMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'B', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallByteMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'B', false, JniInvocationSupport.Arguments.V);

        Pointer _CallByteMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'B', false, JniInvocationSupport.Arguments.A);

        Pointer _CallCharMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'C', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallCharMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'C', false, JniInvocationSupport.Arguments.V);

        Pointer _CallCharMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'C', false, JniInvocationSupport.Arguments.A);

        Pointer _CallShortMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'S', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallShortMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'S', false, JniInvocationSupport.Arguments.V);

        Pointer _CallShortMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'S', false, JniInvocationSupport.Arguments.A);

        Pointer _CallIntMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'I', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallIntMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'I', false, JniInvocationSupport.Arguments.V);

        Pointer _CallIntMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'I', false, JniInvocationSupport.Arguments.A);

        Pointer _CallLongMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'J', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallLongMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'J', false, JniInvocationSupport.Arguments.V);

        Pointer _CallLongMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'J', false, JniInvocationSupport.Arguments.A);

        Pointer _CallFloatMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'F', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallFloatMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'F', false, JniInvocationSupport.Arguments.V);

        Pointer _CallFloatMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'F', false, JniInvocationSupport.Arguments.A);

        Pointer _CallDoubleMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'D', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallDoubleMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'D', false, JniInvocationSupport.Arguments.V);

        Pointer _CallDoubleMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'D', false, JniInvocationSupport.Arguments.A);

        Pointer _CallVoidMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'V', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallVoidMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'V', false, JniInvocationSupport.Arguments.V);

        Pointer _CallVoidMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'V', false, JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualObjectMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualObjectMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualObjectMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualBooleanMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualBooleanMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualBooleanMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                UnidbgPointer clazz = context.getPointerArg(2);
                UnidbgPointer jmethodID = context.getPointerArg(3);
                UnidbgPointer jvalue = context.getPointerArg(4);
                if (log.isDebugEnabled()) {
                    log.debug("CallNonvirtualBooleanMethodA object={}, clazz={}, jmethodID={}, jvalue={}", object, clazz, jmethodID, jvalue);
                }
                DvmObject<?> dvmObject = object == null ? null : resolveJniObject(object.toIntPeer());
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                DvmMethod dvmMethod = dvmClass == null ? null : dvmClass.getMethod(jmethodID.toIntPeer());
                if (dvmMethod == null) {
                    throw new BackendException();
                } else {
                    VaList vaList = new JValueList(DalvikVM.this, jvalue, dvmMethod);
                    if (dvmMethod.isConstructor()) {
                        throw new IllegalStateException();
                    }
                    boolean ret = dvmMethod.callBooleanMethodA(dvmObject, vaList);
                    if (verbose) {
                        System.out.printf("JNIEnv->CallNonvirtualBooleanMethodA(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer());
                    }
                    return ret ? JNI_TRUE : JNI_FALSE;
                }
            }
        });

        Pointer _CallNonvirtualByteMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualByteMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualByteMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualCharMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualCharMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualCharMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualShortMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualShortMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualShortMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualIntMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualIntMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualIntMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualLongMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualLongMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualLongMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualFloatMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualFloatMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualFloatMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualDoubleMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualDoubleMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualDoubleMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualVoidMethod = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _CallNonvirtualVoidMethodV = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                UnidbgPointer clazz = context.getPointerArg(2);
                UnidbgPointer jmethodID = context.getPointerArg(3);
                UnidbgPointer va_list = context.getPointerArg(4);
                if (log.isDebugEnabled()) {
                    log.debug("CallNonvirtualVoidMethodV object={}, clazz={}, jmethodID={}, va_list={}", object, clazz, jmethodID, va_list);
                }
                DvmObject<?> dvmObject = object == null ? null : resolveJniObject(object.toIntPeer());
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                DvmMethod dvmMethod = dvmClass == null ? null : dvmClass.getMethod(jmethodID.toIntPeer());
                if (dvmMethod == null) {
                    throw new BackendException();
                } else {
                    VaList vaList = new VaList32(emulator, DalvikVM.this, va_list, dvmMethod);
                    if (dvmMethod.isConstructor()) {
                        DvmObject<?> obj = dvmMethod.newObjectV(vaList);
                        Objects.requireNonNull(dvmObject).setValue(obj.value);
                    } else {
                        dvmMethod.callVoidMethodV(dvmObject, vaList);
                    }
                    if (verbose) {
                        System.out.printf("JNIEnv->CallNonvirtualVoidMethodV(%s, %s, %s(%s)) was called from %s%n", dvmObject, dvmClass.getClassName(), dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer());
                    }
                    return 0;
                }
            }
        });

        Pointer _CallNonVirtualVoidMethodA = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                UnidbgPointer clazz = context.getPointerArg(2);
                UnidbgPointer jmethodID = context.getPointerArg(3);
                UnidbgPointer jvalue = context.getPointerArg(4);
                if (log.isDebugEnabled()) {
                    log.debug("CallNonVirtualVoidMethodA object={}, clazz={}, jmethodID={}, jvalue={}", object, clazz, jmethodID, jvalue);
                }
                DvmObject<?> dvmObject = object == null ? null : resolveJniObject(object.toIntPeer());
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                DvmMethod dvmMethod = dvmClass == null ? null : dvmClass.getMethod(jmethodID.toIntPeer());
                if (dvmMethod == null) {
                    throw new BackendException();
                } else {
                    VaList vaList = new JValueList(DalvikVM.this, jvalue, dvmMethod);
                    if (dvmMethod.isConstructor()) {
                        DvmObject<?> obj = dvmMethod.newObjectV(vaList);
                        Objects.requireNonNull(dvmObject).setValue(obj.value);
                    } else {
                        dvmMethod.callVoidMethodA(dvmObject, vaList);
                    }
                    if (verbose) {
                        System.out.printf("JNIEnv->CallNonVirtualVoidMethodA(%s, %s, %s(%s)) was called from %s%n", dvmObject, dvmClass.getClassName(), dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer());
                    }
                    return 0;
                }
            }
        });

        Pointer _GetFieldID = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer clazz = context.getPointerArg(1);
                UnidbgPointer fieldName = context.getPointerArg(2);
                Pointer argsPointer = context.getPointerArg(3);
                String name = JniStringSupport.readModifiedUtf8(fieldName);
                String args = JniStringSupport.readModifiedUtf8(argsPointer);
                if (log.isDebugEnabled()) {
                    log.debug("GetFieldID class={}, fieldName={}, args={}", clazz, name, args);
                }
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                if (dvmClass == null) {
                    throw new BackendException();
                } else {
                    int hash = dvmClass.getFieldID(name, args);
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetFieldID(%s.%s %s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash & 0xffffffffL, context.getLRPointer());
                    }
                    return hash;
                }
            }
        });

        Pointer _GetObjectField = JniInvocationSupport.field(emulator, DalvikVM.this, 'L', false, false);

        Pointer _GetBooleanField = JniInvocationSupport.field(emulator, DalvikVM.this, 'Z', false, false);

        Pointer _GetByteField = JniInvocationSupport.field(emulator, DalvikVM.this, 'B', false, false);

        Pointer _GetCharField = JniInvocationSupport.field(emulator, DalvikVM.this, 'C', false, false);

        Pointer _GetShortField = JniInvocationSupport.field(emulator, DalvikVM.this, 'S', false, false);

        Pointer _GetIntField = JniInvocationSupport.field(emulator, DalvikVM.this, 'I', false, false);

        Pointer _GetLongField = JniInvocationSupport.field(emulator, DalvikVM.this, 'J', false, false);

        Pointer _GetFloatField = JniInvocationSupport.field(emulator, DalvikVM.this, 'F', false, false);

        Pointer _GetDoubleField = JniInvocationSupport.field(emulator, DalvikVM.this, 'D', false, false);

        Pointer _SetObjectField = JniInvocationSupport.field(emulator, DalvikVM.this, 'L', false, true);

        Pointer _SetBooleanField = JniInvocationSupport.field(emulator, DalvikVM.this, 'Z', false, true);

        Pointer _SetByteField = JniInvocationSupport.field(emulator, DalvikVM.this, 'B', false, true);

        Pointer _SetCharField = JniInvocationSupport.field(emulator, DalvikVM.this, 'C', false, true);

        Pointer _SetShortField = JniInvocationSupport.field(emulator, DalvikVM.this, 'S', false, true);

        Pointer _SetIntField = JniInvocationSupport.field(emulator, DalvikVM.this, 'I', false, true);

        Pointer _SetLongField = JniInvocationSupport.field(emulator, DalvikVM.this, 'J', false, true);

        Pointer _SetFloatField = JniInvocationSupport.field(emulator, DalvikVM.this, 'F', false, true);

        Pointer _SetDoubleField = JniInvocationSupport.field(emulator, DalvikVM.this, 'D', false, true);

        Pointer _GetStaticMethodID = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer clazz = context.getPointerArg(1);
                Pointer methodName = context.getPointerArg(2);
                Pointer argsPointer = context.getPointerArg(3);
                String name = JniStringSupport.readModifiedUtf8(methodName);
                String args = JniStringSupport.readModifiedUtf8(argsPointer);
                if (log.isDebugEnabled()) {
                    log.debug("GetStaticMethodID class={}, methodName={}, args={}, LR={}", clazz, name, args, context.getLRPointer());
                }
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                if (dvmClass == null) {
                    throw new BackendException();
                } else {
                    int hash = dvmClass.getStaticMethodID(name, args);
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetStaticMethodID(%s.%s%s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash & 0xffffffffL, context.getLRPointer());
                    }
                    return hash;
                }
            }
        });

        Pointer _CallStaticObjectMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'L', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticBooleanMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'Z', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticObjectMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'L', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticObjectMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'L', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticBooleanMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'Z', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticBooleanMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'Z', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticByteMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'B', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticByteMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'B', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticByteMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'B', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticCharMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'C', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticCharMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'C', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticCharMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'C', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticShortMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'S', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticShortMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'S', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticShortMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'S', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticIntMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'I', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticIntMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'I', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticIntMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'I', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticLongMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'J', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticLongMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'J', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticLongMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'J', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticFloatMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'F', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticFloatMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'F', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticFloatMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'F', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticDoubleMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'D', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticDoubleMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'D', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticDoubleMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'D', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticVoidMethod = JniInvocationSupport.method(emulator, DalvikVM.this, 'V', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticVoidMethodV = JniInvocationSupport.method(emulator, DalvikVM.this, 'V', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticVoidMethodA = JniInvocationSupport.method(emulator, DalvikVM.this, 'V', true, JniInvocationSupport.Arguments.A);

        Pointer _GetStaticFieldID = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer clazz = context.getPointerArg(1);
                UnidbgPointer fieldName = context.getPointerArg(2);
                Pointer argsPointer = context.getPointerArg(3);
                String name = JniStringSupport.readModifiedUtf8(fieldName);
                String args = JniStringSupport.readModifiedUtf8(argsPointer);
                if (log.isDebugEnabled()) {
                    log.debug("GetStaticFieldID class={}, fieldName={}, args={}", clazz, name, args);
                }
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                if (dvmClass == null) {
                    throw new BackendException();
                } else {
                    int hash = dvmClass.getStaticFieldID(name, args);
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetStaticFieldID(%s.%s%s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash & 0xffffffffL, context.getLRPointer());
                    }
                    return hash;
                }
            }
        });

        Pointer _GetStaticObjectField = JniInvocationSupport.field(emulator, DalvikVM.this, 'L', true, false);

        Pointer _GetStaticBooleanField = JniInvocationSupport.field(emulator, DalvikVM.this, 'Z', true, false);

        Pointer _GetStaticByteField = JniInvocationSupport.field(emulator, DalvikVM.this, 'B', true, false);

        Pointer _GetStaticCharField = JniInvocationSupport.field(emulator, DalvikVM.this, 'C', true, false);

        Pointer _GetStaticShortField = JniInvocationSupport.field(emulator, DalvikVM.this, 'S', true, false);

        Pointer _GetStaticIntField = JniInvocationSupport.field(emulator, DalvikVM.this, 'I', true, false);

        Pointer _GetStaticLongField = JniInvocationSupport.field(emulator, DalvikVM.this, 'J', true, false);

        Pointer _GetStaticFloatField = JniInvocationSupport.field(emulator, DalvikVM.this, 'F', true, false);

        Pointer _GetStaticDoubleField = JniInvocationSupport.field(emulator, DalvikVM.this, 'D', true, false);

        Pointer _SetStaticObjectField = JniInvocationSupport.field(emulator, DalvikVM.this, 'L', true, true);

        Pointer _SetStaticBooleanField = JniInvocationSupport.field(emulator, DalvikVM.this, 'Z', true, true);

        Pointer _SetStaticByteField = JniInvocationSupport.field(emulator, DalvikVM.this, 'B', true, true);

        Pointer _SetStaticCharField = JniInvocationSupport.field(emulator, DalvikVM.this, 'C', true, true);

        Pointer _SetStaticShortField = JniInvocationSupport.field(emulator, DalvikVM.this, 'S', true, true);

        Pointer _SetStaticIntField = JniInvocationSupport.field(emulator, DalvikVM.this, 'I', true, true);

        Pointer _SetStaticLongField = JniInvocationSupport.field(emulator, DalvikVM.this, 'J', true, true);

        Pointer _SetStaticFloatField = JniInvocationSupport.field(emulator, DalvikVM.this, 'F', true, true);

        Pointer _SetStaticDoubleField = JniInvocationSupport.field(emulator, DalvikVM.this, 'D', true, true);

        Pointer _GetStringUTFLength = JniStringSupport.length(emulator, DalvikVM.this, true);

        Pointer _GetStringUTFChars = JniStringSupport.borrow(emulator, DalvikVM.this, true);

        Pointer _ReleaseStringUTFChars = JniStringSupport.release(emulator, DalvikVM.this, true);

        Pointer _GetArrayLength = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer pointer = context.getPointerArg(1);
                Array<?> array = Objects.requireNonNull((Array<?>) resolveJniObject(pointer.toIntPeer()));
                if (log.isDebugEnabled()) {
                    log.debug("GetArrayLength array={}, lr={}", array, context.getLRPointer());
                }
                if (verbose) {
                    System.out.printf("JNIEnv->GetArrayLength(%s => %s) was called from %s%n", array, array.length(), context.getLRPointer());
                }
                return array.length();
            }
        });

        Pointer _NewObjectArray = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                int size = context.getIntArg(1);
                UnidbgPointer elementClass = context.getPointerArg(2);
                UnidbgPointer initialElement = context.getPointerArg(3);
                if (log.isDebugEnabled()) {
                    log.debug("NewObjectArray size={}, elementClass={}, initialElement={}", size, elementClass, initialElement);
                }
                DvmClass dvmClass = (DvmClass) resolveJniObject(elementClass.toIntPeer());
                if (dvmClass == null) {
                    throw new BackendException("elementClass=" + elementClass);
                }

                DvmObject<?> obj = size == 0 ? null : initialElement == null ? null : resolveJniObject(initialElement.toIntPeer());
                DvmObject<?>[] array = new DvmObject[size];
                Arrays.fill(array, obj);

                return addLocalObject(ArrayObject.newObjectArray(dvmClass, array));
            }
        });

        Pointer _GetObjectArrayElement = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                int index = context.getIntArg(2);
                ArrayObject array = resolveJniObject(object.toIntPeer());
                if (log.isDebugEnabled()) {
                    log.debug("GetObjectArrayElement array={}, index={}", array, index);
                }
                DvmObject<?> obj = Objects.requireNonNull(array).getValue()[index];
                if (verbose) {
                    System.out.printf("JNIEnv->GetObjectArrayElement(%s, %d) => %s was called from %s%n", array, index, obj, context.getLRPointer());
                }
                return addLocalObject(obj);
            }
        });

        Pointer _SetObjectArrayElement = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                int index = context.getIntArg(2);
                UnidbgPointer element = context.getPointerArg(3);
                ArrayObject array = resolveJniObject(object.toIntPeer());
                DvmObject<?> obj = element == null ? null : resolveJniObject(element.toIntPeer());
                if (log.isDebugEnabled()) {
                    log.debug("setObjectArrayElement array={}, index={}, obj={}", array, index, obj);
                }
                Objects.requireNonNull(array).setObject(index, obj);
                return 0;
            }
        });

        Pointer _NewLongArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM.this, 'J');

        Pointer _NewFloatArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM.this, 'F');

        Pointer _GetLongArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM.this, 'J');

        Pointer _GetFloatArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM.this, 'F');

        Pointer _GetDoubleArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM.this, 'D');

        Pointer _NewBooleanArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM.this, 'Z');

        Pointer _NewByteArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM.this, 'B');

        Pointer _NewCharArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM.this, 'C');

        Pointer _NewShortArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM.this, 'S');

        Pointer _NewIntArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM.this, 'I');

        Pointer _NewDoubleArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM.this, 'D');

        Pointer _GetBooleanArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM.this, 'Z');

        Pointer _GetByteArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM.this, 'B');

        Pointer _GetCharArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM.this, 'C');

        Pointer _GetShortArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM.this, 'S');

        Pointer _GetIntArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM.this, 'I');

        Pointer _NewString = JniStringSupport.create(emulator, DalvikVM.this, false);

        Pointer _GetStringLength = JniStringSupport.length(emulator, DalvikVM.this, false);

        Pointer _GetStringChars = JniStringSupport.borrow(emulator, DalvikVM.this, false);

        Pointer _ReleaseStringChars = JniStringSupport.release(emulator, DalvikVM.this, false);

        Pointer _NewStringUTF = JniStringSupport.create(emulator, DalvikVM.this, true);

        Pointer _ReleaseBooleanArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM.this, 'Z');

        Pointer _ReleaseByteArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM.this, 'B');

        Pointer _ReleaseCharArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM.this, 'C');

        Pointer _ReleaseShortArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM.this, 'S');

        Pointer _ReleaseIntArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM.this, 'I');

        Pointer _ReleaseLongArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM.this, 'J');

        Pointer _ReleaseFloatArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM.this, 'F');

        Pointer _ReleaseDoubleArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM.this, 'D');

        Pointer _GetBooleanArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Boolean, false);

        Pointer _GetByteArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Byte, false);

        Pointer _GetCharArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Char, false);

        Pointer _GetShortArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Short, false);

        Pointer _GetIntArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Int, false);

        Pointer _GetLongArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Long, false);

        Pointer _GetFloatArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Float, false);

        Pointer _GetDoubleArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Double, false);

        Pointer _SetBooleanArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Boolean, true);

        Pointer _SetByteArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Byte, true);

        Pointer _SetCharArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Char, true);

        Pointer _SetShortArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Short, true);

        Pointer _SetIntArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Int, true);

        Pointer _SetLongArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Long, true);

        Pointer _SetFloatArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Float, true);

        Pointer _SetDoubleArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM.this, JniArrayRegion.Type.Double, true);

        Pointer _RegisterNatives = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer clazz = context.getPointerArg(1);
                Pointer methods = context.getPointerArg(2);
                int nMethods = context.getIntArg(3);
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                if (log.isDebugEnabled()) {
                    log.debug("RegisterNatives dvmClass={}, methods={}, nMethods={}", dvmClass, methods, nMethods);
                }
                if (verbose) {
                    System.out.printf("JNIEnv->RegisterNatives(%s, %s, %d) was called from %s%n", dvmClass.getClassName(), methods, nMethods, context.getLRPointer());
                }
                for (int i = 0; i < nMethods; i++) {
                    Pointer method = methods.share(i * 0xcL);
                    Pointer name = method.getPointer(0);
                    Pointer signature = method.getPointer(4);
                    Pointer fnPtr = method.getPointer(8);
                    String methodName = JniStringSupport.readModifiedUtf8(name);
                    String signatureValue = JniStringSupport.readModifiedUtf8(signature);
                    if (log.isDebugEnabled()) {
                        log.debug("RegisterNatives dvmClass={}, name={}, signature={}, fnPtr={}", dvmClass, methodName, signatureValue, fnPtr);
                    }
                    dvmClass.nativesMap.put(methodName + signatureValue, (UnidbgPointer) fnPtr);

                    if (verbose) {
                        System.out.printf("RegisterNative(%s, %s%s, %s)%n", dvmClass.getClassName(), methodName, signatureValue, fnPtr);
                    }
                }
                return JNI_OK;
            }
        });

        Pointer _UnregisterNatives = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _MonitorEnter = JniThreadJniSupport.monitor(emulator, DalvikVM.this, true);

        Pointer _MonitorExit = JniThreadJniSupport.monitor(emulator, DalvikVM.this, false);

        Pointer _GetJavaVM = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer vm = context.getPointerArg(1);
                if (log.isDebugEnabled()) {
                    log.debug("GetJavaVM vm={}", vm);
                }
                vm.setPointer(0, _JavaVM);
                return JNI_OK;
            }
        });

        Pointer _GetStringRegion = JniStringSupport.region(emulator, DalvikVM.this, false);

        Pointer _GetStringUTFRegion = JniStringSupport.region(emulator, DalvikVM.this, true);

        Pointer _GetPrimitiveArrayCritical = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                Pointer isCopy = context.getPointerArg(2);
                PrimitiveArray<?> array = resolveJniObject(object.toIntPeer());
                if (log.isDebugEnabled()) {
                    log.debug("GetPrimitiveArrayCritical array={}, isCopy={}", array, isCopy);
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).toIntPeer();
            }
        });

        Pointer _ReleasePrimitiveArrayCritical = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                Pointer pointer = context.getPointerArg(2);
                int mode = context.getIntArg(3);
                PrimitiveArray<?> array = resolveJniObject(object.toIntPeer());
                if (log.isDebugEnabled()) {
                    log.debug("ReleasePrimitiveArrayCritical array={}, pointer={}, mode={}", array, pointer, mode);
                }
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer, mode);
                return 0;
            }
        });

        Pointer _GetStringCritical = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _ReleaseStringCritical = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _NewWeakGlobalRef = JniReferenceSupport.reference(emulator, DalvikVM.this, "NewWeakGlobalRef");

        Pointer _DeleteWeakGlobalRef = JniReferenceSupport.reference(emulator, DalvikVM.this, "DeleteWeakGlobalRef");

        Pointer _ExceptionCheck = JniExceptionSupport.exception(emulator, DalvikVM.this, "ExceptionCheck");

        Pointer _NewDirectByteBuffer = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _GetDirectBufferAddress = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _GetDirectBufferCapacity = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _GetObjectRefType = JniReferenceSupport.reference(emulator, DalvikVM.this, "GetObjectRefType");

        Pointer _GetModule = svcMemory.registerSvc(new ArmSvc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        final int last = 0x3a4;
        final UnidbgPointer impl = svcMemory.allocate(last + 4, "JNIEnv.impl");
        for (int i = 0; i <= last; i += 4) {
            impl.setInt(i, i);
        }
        impl.setPointer(0x10, _GetVersion);
        impl.setPointer(0x14, _DefineClass);
        impl.setPointer(0x18, _FindClass);
        impl.setPointer(0x1c, _FromReflectedMethod);
        impl.setPointer(0x20, _FromReflectedField);
        impl.setPointer(0x24, _ToReflectedMethod);
        impl.setPointer(0x28, _GetSuperclass);
        impl.setPointer(0x2c, _IsAssignableFrom);
        impl.setPointer(0x30, _ToReflectedField);
        impl.setPointer(0x34, _Throw);
        impl.setPointer(0x38, _ThrowNew);
        impl.setPointer(0x3c, _ExceptionOccurred);
        impl.setPointer(0x40, _ExceptionDescribe);
        impl.setPointer(0x44, _ExceptionClear);
        impl.setPointer(0x48, _FatalError);
        impl.setPointer(0x4c, _PushLocalFrame);
        impl.setPointer(0x50, _PopLocalFrame);
        impl.setPointer(0x54, _NewGlobalRef);
        impl.setPointer(0x58, _DeleteGlobalRef);
        impl.setPointer(0x5c, _DeleteLocalRef);
        impl.setPointer(0x60, _IsSameObject);
        impl.setPointer(0x64, _NewLocalRef);
        impl.setPointer(0x68, _EnsureLocalCapacity);
        impl.setPointer(0x6c, _AllocObject);
        impl.setPointer(0x70, _NewObject);
        impl.setPointer(0x74, _NewObjectV);
        impl.setPointer(0x78, _NewObjectA);
        impl.setPointer(0x7c, _GetObjectClass);
        impl.setPointer(0x80, _IsInstanceOf);
        impl.setPointer(0x84, _GetMethodID);
        impl.setPointer(0x88, _CallObjectMethod);
        impl.setPointer(0x8c, _CallObjectMethodV);
        impl.setPointer(0x90, _CallObjectMethodA);
        impl.setPointer(0x94, _CallBooleanMethod);
        impl.setPointer(0x98, _CallBooleanMethodV);
        impl.setPointer(0x9c, _CallBooleanMethodA);
        impl.setPointer(0xa0, _CallByteMethod);
        impl.setPointer(0xa4, _CallByteMethodV);
        impl.setPointer(0xa8, _CallByteMethodA);
        impl.setPointer(0xac, _CallCharMethod);
        impl.setPointer(0xb0, _CallCharMethodV);
        impl.setPointer(0xb4, _CallCharMethodA);
        impl.setPointer(0xb8, _CallShortMethod);
        impl.setPointer(0xbc, _CallShortMethodV);
        impl.setPointer(0xc0, _CallShortMethodA);
        impl.setPointer(0xc4, _CallIntMethod);
        impl.setPointer(0xc8, _CallIntMethodV);
        impl.setPointer(0xcc, _CallIntMethodA);
        impl.setPointer(0xd0, _CallLongMethod);
        impl.setPointer(0xd4, _CallLongMethodV);
        impl.setPointer(0xd8, _CallLongMethodA);
        impl.setPointer(0xdc, _CallFloatMethod);
        impl.setPointer(0xe0, _CallFloatMethodV);
        impl.setPointer(0xe4, _CallFloatMethodA);
        impl.setPointer(0xe8, _CallDoubleMethod);
        impl.setPointer(0xec, _CallDoubleMethodV);
        impl.setPointer(0xf0, _CallDoubleMethodA);
        impl.setPointer(0xf4, _CallVoidMethod);
        impl.setPointer(0xf8, _CallVoidMethodV);
        impl.setPointer(0xfc, _CallVoidMethodA);
        impl.setPointer(0x100, _CallNonvirtualObjectMethod);
        impl.setPointer(0x104, _CallNonvirtualObjectMethodV);
        impl.setPointer(0x108, _CallNonvirtualObjectMethodA);
        impl.setPointer(0x10c, _CallNonvirtualBooleanMethod);
        impl.setPointer(0x110, _CallNonvirtualBooleanMethodV);
        impl.setPointer(0x114, _CallNonvirtualBooleanMethodA);
        impl.setPointer(0x118, _CallNonvirtualByteMethod);
        impl.setPointer(0x11c, _CallNonvirtualByteMethodV);
        impl.setPointer(0x120, _CallNonvirtualByteMethodA);
        impl.setPointer(0x124, _CallNonvirtualCharMethod);
        impl.setPointer(0x128, _CallNonvirtualCharMethodV);
        impl.setPointer(0x12c, _CallNonvirtualCharMethodA);
        impl.setPointer(0x130, _CallNonvirtualShortMethod);
        impl.setPointer(0x134, _CallNonvirtualShortMethodV);
        impl.setPointer(0x138, _CallNonvirtualShortMethodA);
        impl.setPointer(0x13c, _CallNonvirtualIntMethod);
        impl.setPointer(0x140, _CallNonvirtualIntMethodV);
        impl.setPointer(0x144, _CallNonvirtualIntMethodA);
        impl.setPointer(0x148, _CallNonvirtualLongMethod);
        impl.setPointer(0x14c, _CallNonvirtualLongMethodV);
        impl.setPointer(0x150, _CallNonvirtualLongMethodA);
        impl.setPointer(0x154, _CallNonvirtualFloatMethod);
        impl.setPointer(0x158, _CallNonvirtualFloatMethodV);
        impl.setPointer(0x15c, _CallNonvirtualFloatMethodA);
        impl.setPointer(0x160, _CallNonvirtualDoubleMethod);
        impl.setPointer(0x164, _CallNonvirtualDoubleMethodV);
        impl.setPointer(0x168, _CallNonvirtualDoubleMethodA);
        impl.setPointer(0x16c, _CallNonvirtualVoidMethod);
        impl.setPointer(0x170, _CallNonvirtualVoidMethodV);
        impl.setPointer(0x174, _CallNonVirtualVoidMethodA);
        impl.setPointer(0x178, _GetFieldID);
        impl.setPointer(0x17c, _GetObjectField);
        impl.setPointer(0x180, _GetBooleanField);
        impl.setPointer(0x184, _GetByteField);
        impl.setPointer(0x188, _GetCharField);
        impl.setPointer(0x18c, _GetShortField);
        impl.setPointer(0x190, _GetIntField);
        impl.setPointer(0x194, _GetLongField);
        impl.setPointer(0x198, _GetFloatField);
        impl.setPointer(0x19c, _GetDoubleField);
        impl.setPointer(0x1a0, _SetObjectField);
        impl.setPointer(0x1a4, _SetBooleanField);
        impl.setPointer(0x1a8, _SetByteField);
        impl.setPointer(0x1ac, _SetCharField);
        impl.setPointer(0x1b0, _SetShortField);
        impl.setPointer(0x1b4, _SetIntField);
        impl.setPointer(0x1b8, _SetLongField);
        impl.setPointer(0x1bc, _SetFloatField);
        impl.setPointer(0x1c0, _SetDoubleField);
        impl.setPointer(0x1c4, _GetStaticMethodID);
        impl.setPointer(0x1c8, _CallStaticObjectMethod);
        impl.setPointer(0x1cc, _CallStaticObjectMethodV);
        impl.setPointer(0x1d0, _CallStaticObjectMethodA);
        impl.setPointer(0x1d4, _CallStaticBooleanMethod);
        impl.setPointer(0x1d8, _CallStaticBooleanMethodV);
        impl.setPointer(0x1dc, _CallStaticBooleanMethodA);
        impl.setPointer(0x1e0, _CallStaticByteMethod);
        impl.setPointer(0x1e4, _CallStaticByteMethodV);
        impl.setPointer(0x1e8, _CallStaticByteMethodA);
        impl.setPointer(0x1ec, _CallStaticCharMethod);
        impl.setPointer(0x1f0, _CallStaticCharMethodV);
        impl.setPointer(0x1f4, _CallStaticCharMethodA);
        impl.setPointer(0x1f8, _CallStaticShortMethod);
        impl.setPointer(0x1fc, _CallStaticShortMethodV);
        impl.setPointer(0x200, _CallStaticShortMethodA);
        impl.setPointer(0x204, _CallStaticIntMethod);
        impl.setPointer(0x208, _CallStaticIntMethodV);
        impl.setPointer(0x20c, _CallStaticIntMethodA);
        impl.setPointer(0x210, _CallStaticLongMethod);
        impl.setPointer(0x214, _CallStaticLongMethodV);
        impl.setPointer(0x218, _CallStaticLongMethodA);
        impl.setPointer(0x21c, _CallStaticFloatMethod);
        impl.setPointer(0x220, _CallStaticFloatMethodV);
        impl.setPointer(0x224, _CallStaticFloatMethodA);
        impl.setPointer(0x228, _CallStaticDoubleMethod);
        impl.setPointer(0x22c, _CallStaticDoubleMethodV);
        impl.setPointer(0x230, _CallStaticDoubleMethodA);
        impl.setPointer(0x234, _CallStaticVoidMethod);
        impl.setPointer(0x238, _CallStaticVoidMethodV);
        impl.setPointer(0x23c, _CallStaticVoidMethodA);
        impl.setPointer(0x240, _GetStaticFieldID);
        impl.setPointer(0x244, _GetStaticObjectField);
        impl.setPointer(0x248, _GetStaticBooleanField);
        impl.setPointer(0x24c, _GetStaticByteField);
        impl.setPointer(0x250, _GetStaticCharField);
        impl.setPointer(0x254, _GetStaticShortField);
        impl.setPointer(0x258, _GetStaticIntField);
        impl.setPointer(0x25c, _GetStaticLongField);
        impl.setPointer(0x260, _GetStaticFloatField);
        impl.setPointer(0x264, _GetStaticDoubleField);
        impl.setPointer(0x268, _SetStaticObjectField);
        impl.setPointer(0x26c, _SetStaticBooleanField);
        impl.setPointer(0x270, _SetStaticByteField);
        impl.setPointer(0x274, _SetStaticCharField);
        impl.setPointer(0x278, _SetStaticShortField);
        impl.setPointer(0x27c, _SetStaticIntField);
        impl.setPointer(0x280, _SetStaticLongField);
        impl.setPointer(0x284, _SetStaticFloatField);
        impl.setPointer(0x288, _SetStaticDoubleField);
        impl.setPointer(0x28c, _NewString);
        impl.setPointer(0x290, _GetStringLength);
        impl.setPointer(0x294, _GetStringChars);
        impl.setPointer(0x298, _ReleaseStringChars);
        impl.setPointer(0x29c, _NewStringUTF);
        impl.setPointer(0x2a0, _GetStringUTFLength);
        impl.setPointer(0x2a4, _GetStringUTFChars);
        impl.setPointer(0x2a8, _ReleaseStringUTFChars);
        impl.setPointer(0x2ac, _GetArrayLength);
        impl.setPointer(0x2b0, _NewObjectArray);
        impl.setPointer(0x2b4, _GetObjectArrayElement);
        impl.setPointer(0x2b8, _SetObjectArrayElement);
        impl.setPointer(0x2bc, _NewBooleanArray);
        impl.setPointer(0x2c0, _NewByteArray);
        impl.setPointer(0x2c4, _NewCharArray);
        impl.setPointer(0x2c8, _NewShortArray);
        impl.setPointer(0x2cc, _NewIntArray);
        impl.setPointer(0x2d0, _NewLongArray);
        impl.setPointer(0x2d4, _NewFloatArray);
        impl.setPointer(0x2d8, _NewDoubleArray);
        impl.setPointer(0x2dc, _GetBooleanArrayElements);
        impl.setPointer(0x2e0, _GetByteArrayElements);
        impl.setPointer(0x2e4, _GetCharArrayElements);
        impl.setPointer(0x2e8, _GetShortArrayElements);
        impl.setPointer(0x2ec, _GetIntArrayElements);
        impl.setPointer(0x2f0, _GetLongArrayElements);
        impl.setPointer(0x2f4, _GetFloatArrayElements);
        impl.setPointer(0x2f8, _GetDoubleArrayElements);
        impl.setPointer(0x2fc, _ReleaseBooleanArrayElements);
        impl.setPointer(0x300, _ReleaseByteArrayElements);
        impl.setPointer(0x304, _ReleaseCharArrayElements);
        impl.setPointer(0x308, _ReleaseShortArrayElements);
        impl.setPointer(0x30c, _ReleaseIntArrayElements);
        impl.setPointer(0x310, _ReleaseLongArrayElements);
        impl.setPointer(0x314, _ReleaseFloatArrayElements);
        impl.setPointer(0x318, _ReleaseDoubleArrayElements);
        impl.setPointer(0x31c, _GetBooleanArrayRegion);
        impl.setPointer(0x320, _GetByteArrayRegion);
        impl.setPointer(0x324, _GetCharArrayRegion);
        impl.setPointer(0x328, _GetShortArrayRegion);
        impl.setPointer(0x32c, _GetIntArrayRegion);
        impl.setPointer(0x330, _GetLongArrayRegion);
        impl.setPointer(0x334, _GetFloatArrayRegion);
        impl.setPointer(0x338, _GetDoubleArrayRegion);
        impl.setPointer(0x33c, _SetBooleanArrayRegion);
        impl.setPointer(0x340, _SetByteArrayRegion);
        impl.setPointer(0x344, _SetCharArrayRegion);
        impl.setPointer(0x348, _SetShortArrayRegion);
        impl.setPointer(0x34c, _SetIntArrayRegion);
        impl.setPointer(0x350, _SetLongArrayRegion);
        impl.setPointer(0x354, _SetFloatArrayRegion);
        impl.setPointer(0x358, _SetDoubleArrayRegion);
        impl.setPointer(0x35c, _RegisterNatives);
        impl.setPointer(0x360, _UnregisterNatives);
        impl.setPointer(0x364, _MonitorEnter);
        impl.setPointer(0x368, _MonitorExit);
        impl.setPointer(0x36c, _GetJavaVM);
        impl.setPointer(0x370, _GetStringRegion);
        impl.setPointer(0x374, _GetStringUTFRegion);
        impl.setPointer(0x378, _GetPrimitiveArrayCritical);
        impl.setPointer(0x37c, _ReleasePrimitiveArrayCritical);
        impl.setPointer(0x380, _GetStringCritical);
        impl.setPointer(0x384, _ReleaseStringCritical);
        impl.setPointer(0x388, _NewWeakGlobalRef);
        impl.setPointer(0x38c, _DeleteWeakGlobalRef);
        impl.setPointer(0x390, _ExceptionCheck);
        impl.setPointer(0x394, _NewDirectByteBuffer);
        impl.setPointer(0x398, _GetDirectBufferAddress);
        impl.setPointer(0x39c, _GetDirectBufferCapacity);
        impl.setPointer(0x3a0, _GetObjectRefType);
        impl.setPointer(last, _GetModule);

        bindJniThreads(impl);

        Pointer _AttachCurrentThread = JniThreadJniSupport.invocation(emulator, DalvikVM.this, "AttachCurrentThread");

        Pointer _GetEnv = JniThreadJniSupport.invocation(emulator, DalvikVM.this, "GetEnv");

        UnidbgPointer _JNIInvokeInterface = svcMemory.allocate(emulator.getPointerSize() * 8, "_JNIInvokeInterface");
        for (int i = 0; i < emulator.getPointerSize() * 8; i += emulator.getPointerSize()) {
            _JNIInvokeInterface.setInt(i, i);
        }
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 4L, _AttachCurrentThread);
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 6L, _GetEnv);

        _JavaVM.setPointer(0, _JNIInvokeInterface);

        if (log.isDebugEnabled()) {
            log.debug("_JavaVM={}, _JNIInvokeInterface={}, _JNIEnv={}", _JavaVM, _JNIInvokeInterface, getJNIEnv());
        }
    }

    @Override
    public Pointer getJavaVM() {
        return _JavaVM;
    }


    byte[] loadLibraryData(Apk apk, String soName) {
        byte[] soData = apk.getFileData("lib/armeabi-v7a/" + soName);
        if (soData != null) {
            if (log.isDebugEnabled()) {
                log.debug("resolve armeabi-v7a library: {}", soName);
            }
            return soData;
        }
        soData = apk.getFileData("lib/armeabi/" + soName);
        if (soData != null && log.isDebugEnabled()) {
            log.debug("resolve armeabi library: {}", soName);
        }
        return soData;
    }
}
