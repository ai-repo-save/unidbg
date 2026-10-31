package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.Arm64Hook;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.HookStatus;
import com.github.unidbg.arm.NestedRun;
import com.github.unidbg.arm.backend.BackendException;
import com.github.unidbg.arm.context.Arm64RegisterContext;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.linux.android.dvm.apk.Apk;
import com.github.unidbg.linux.android.dvm.array.*;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.utils.Inspector;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public class DalvikVM64 extends BaseVM implements VM {

    private static final Logger log = LoggerFactory.getLogger(DalvikVM64.class);

    private final UnidbgPointer _JavaVM;

    public DalvikVM64(AndroidEmulator emulator, File apkFile) {
        super(emulator, apkFile);

        final SvcMemory svcMemory = emulator.getSvcMemory();
        _JavaVM = svcMemory.allocate(emulator.getPointerSize(), "_JavaVM");

        Pointer _GetVersion = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                return JNI_VERSION_1_8;
            }
        });

        Pointer _DefineClass = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _FindClass = svcMemory.registerSvc(new Arm64Svc() {
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

                log.info("event=guest_find_class name={} notFound={}", name, notFound);
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

        Pointer _FromReflectedMethod = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _FromReflectedField = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _ToReflectedMethod = svcMemory.registerSvc(new Arm64Svc() {
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
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->ToReflectedMethod(%s, \"%s\", %s) was called from %s%n", dvmClass.getClassName(), dvmMethod.methodName, dvmMethod.isStatic ? "is static" : "not static", context.getLRPointer());
                    }

                    return addLocalObject(dvmMethod.toReflectedMethod());
                }
            }
        }) ;

        Pointer _GetSuperclass = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer clazz = context.getPointerArg(1);
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->GetSuperClass(%s) was called from %s%n", dvmClass, context.getLRPointer());
                }
                // JNI 规范: GetSuperclass(Object) 返回 NULL 而非异常 —— 加固 so 在 JNI_OnLoad
                // 中遍历继承链, Object 命中是正常路径(原抛 BackendException 迫使用户内存 patch)
                if (dvmClass.getClassName().equals("java/lang/Object")) {
                    if (log.isDebugEnabled()) {
                        log.debug("JNIEnv->GetSuperClass(class=Object) returns NULL per JNI spec.");
                    }
                    return 0;
                }
                DvmClass superClass = dvmClass.getSuperclass();
                if (superClass == null) {
                    // 未声明父类(桩 resolveClass 未给 superClass): 按 ART 语义回落 java/lang/Object,
                    // so 若依赖精确父类应显式 resolveClass 声明继承链
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

        Pointer _IsAssignableFrom = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _ToReflectedField = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _Throw = JniExceptionSupport.exception(emulator, DalvikVM64.this, "Throw");

        Pointer _ThrowNew = JniExceptionSupport.exception(emulator, DalvikVM64.this, "ThrowNew");

        Pointer _ExceptionOccurred = JniExceptionSupport.exception(emulator, DalvikVM64.this, "ExceptionOccurred");

        Pointer _ExceptionDescribe = JniExceptionSupport.exception(emulator, DalvikVM64.this, "ExceptionDescribe");

        Pointer _ExceptionClear = JniExceptionSupport.exception(emulator, DalvikVM64.this, "ExceptionClear");

        Pointer _FatalError = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _PushLocalFrame = JniReferenceSupport.reference(emulator, DalvikVM64.this, "PushLocalFrame");

        Pointer _PopLocalFrame = JniReferenceSupport.reference(emulator, DalvikVM64.this, "PopLocalFrame");

        Pointer _NewGlobalRef = JniReferenceSupport.reference(emulator, DalvikVM64.this, "NewGlobalRef");

        Pointer _DeleteGlobalRef = JniReferenceSupport.reference(emulator, DalvikVM64.this, "DeleteGlobalRef");

        Pointer _DeleteLocalRef = JniReferenceSupport.reference(emulator, DalvikVM64.this, "DeleteLocalRef");

        Pointer _IsSameObject = JniReferenceSupport.reference(emulator, DalvikVM64.this, "IsSameObject");

        Pointer _NewLocalRef = JniReferenceSupport.reference(emulator, DalvikVM64.this, "NewLocalRef");

        Pointer _EnsureLocalCapacity = JniReferenceSupport.reference(emulator, DalvikVM64.this, "EnsureLocalCapacity");

        Pointer _AllocObject = JniInvocationSupport.allocate(emulator, DalvikVM64.this);

        Pointer _NewObject = JniInvocationSupport.construct(emulator, DalvikVM64.this, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _NewObjectV = JniInvocationSupport.construct(emulator, DalvikVM64.this, JniInvocationSupport.Arguments.V);

        Pointer _NewObjectA = JniInvocationSupport.construct(emulator, DalvikVM64.this, JniInvocationSupport.Arguments.A);

        Pointer _GetObjectClass = svcMemory.registerSvc(new Arm64Svc() {
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

        Pointer _IsInstanceOf = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                UnidbgPointer clazz = context.getPointerArg(2);
                DvmObject<?> dvmObject = object == null ? null : resolveJniObject(object.toIntPeer());
                if (clazz == null) {
                    throw new IllegalStateException("LR=" + context.getLRPointer());
                }
                DvmClass dvmClass = (DvmClass) resolveJniObject(clazz.toIntPeer());
                if (dvmObject == null || dvmClass == null) {
                    throw new BackendException();
                }
                boolean flag = dvmObject.isInstanceOf(dvmClass);
                log.debug("IsInstanceOf object={}, clazz={}, dvmObject={}, dvmClass={}, flag={}", object, clazz, dvmObject, dvmClass, flag);
                return flag ? JNI_TRUE : JNI_FALSE;
            }
        });

        Pointer _GetMethodID = svcMemory.registerSvc(new Arm64Svc() {
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
                    if (hash == 0) {
                        log.info("event=guest_method_missing class={} name={} args={}", dvmClass.getClassName(), name, args);
                    }
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetMethodID(%s.%s%s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash & 0xffffffffL, context.getLRPointer());
                    }
                    return hash;
                }
            }
        });

        Pointer _CallObjectMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'L', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallObjectMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'L', false, JniInvocationSupport.Arguments.V);

        Pointer _CallObjectMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'L', false, JniInvocationSupport.Arguments.A);

        Pointer _CallBooleanMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'Z', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallBooleanMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'Z', false, JniInvocationSupport.Arguments.V);

        Pointer _CallBooleanMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'Z', false, JniInvocationSupport.Arguments.A);

        Pointer _CallByteMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'B', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallByteMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'B', false, JniInvocationSupport.Arguments.V);

        Pointer _CallByteMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'B', false, JniInvocationSupport.Arguments.A);

        Pointer _CallCharMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'C', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallCharMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'C', false, JniInvocationSupport.Arguments.V);

        Pointer _CallCharMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'C', false, JniInvocationSupport.Arguments.A);

        Pointer _CallShortMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'S', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallShortMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'S', false, JniInvocationSupport.Arguments.V);

        Pointer _CallShortMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'S', false, JniInvocationSupport.Arguments.A);

        Pointer _CallIntMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'I', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallIntMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'I', false, JniInvocationSupport.Arguments.V);

        Pointer _CallIntMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'I', false, JniInvocationSupport.Arguments.A);

        Pointer _CallLongMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'J', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallLongMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'J', false, JniInvocationSupport.Arguments.V);

        Pointer _CallLongMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'J', false, JniInvocationSupport.Arguments.A);

        Pointer _CallFloatMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'F', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallFloatMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'F', false, JniInvocationSupport.Arguments.V);

        Pointer _CallFloatMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'F', false, JniInvocationSupport.Arguments.A);

        Pointer _CallDoubleMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'D', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallDoubleMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'D', false, JniInvocationSupport.Arguments.V);

        Pointer _CallDoubleMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'D', false, JniInvocationSupport.Arguments.A);

        Pointer _CallVoidMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'V', false, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallVoidMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'V', false, JniInvocationSupport.Arguments.V);

        Pointer _CallVoidMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'V', false, JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualObjectMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'L', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualObjectMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'L', JniInvocationSupport.Arguments.V);

        Pointer _CallNonvirtualObjectMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'L', JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualBooleanMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'Z', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualBooleanMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'Z', JniInvocationSupport.Arguments.V);

        Pointer _CallNonvirtualBooleanMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'Z', JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualByteMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'B', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualByteMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'B', JniInvocationSupport.Arguments.V);

        Pointer _CallNonvirtualByteMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'B', JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualCharMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'C', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualCharMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'C', JniInvocationSupport.Arguments.V);

        Pointer _CallNonvirtualCharMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'C', JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualShortMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'S', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualShortMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'S', JniInvocationSupport.Arguments.V);

        Pointer _CallNonvirtualShortMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'S', JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualIntMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'I', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualIntMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'I', JniInvocationSupport.Arguments.V);

        Pointer _CallNonvirtualIntMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'I', JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualLongMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'J', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualLongMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'J', JniInvocationSupport.Arguments.V);

        Pointer _CallNonvirtualLongMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'J', JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualFloatMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'F', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualFloatMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'F', JniInvocationSupport.Arguments.V);

        Pointer _CallNonvirtualFloatMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'F', JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualDoubleMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'D', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualDoubleMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'D', JniInvocationSupport.Arguments.V);

        Pointer _CallNonvirtualDoubleMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'D', JniInvocationSupport.Arguments.A);

        Pointer _CallNonvirtualVoidMethod = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'V', JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallNonvirtualVoidMethodV = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'V', JniInvocationSupport.Arguments.V);

        Pointer _CallNonVirtualVoidMethodA = JniInvocationSupport.nonvirtual(emulator, DalvikVM64.this, 'V', JniInvocationSupport.Arguments.A);

        Pointer _GetFieldID = svcMemory.registerSvc(new Arm64Svc() {
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
                    if (hash == 0) {
                        log.info("event=guest_field_missing class={} name={} args={}", dvmClass.getClassName(), name, args);
                    }
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetFieldID(%s.%s %s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash & 0xffffffffL, context.getLRPointer());
                    }
                    return hash;
                }
            }
        });

        Pointer _GetObjectField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'L', false, false);

        Pointer _GetBooleanField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'Z', false, false);

        Pointer _GetByteField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'B', false, false);

        Pointer _GetCharField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'C', false, false);

        Pointer _GetShortField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'S', false, false);

        Pointer _GetIntField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'I', false, false);

        Pointer _GetLongField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'J', false, false);

        Pointer _GetFloatField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'F', false, false);

        Pointer _GetDoubleField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'D', false, false);

        Pointer _SetObjectField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'L', false, true);

        Pointer _SetBooleanField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'Z', false, true);

        Pointer _SetByteField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'B', false, true);

        Pointer _SetCharField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'C', false, true);

        Pointer _SetShortField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'S', false, true);

        Pointer _SetIntField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'I', false, true);

        Pointer _SetLongField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'J', false, true);

        Pointer _SetFloatField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'F', false, true);

        Pointer _SetDoubleField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'D', false, true);

        Pointer _GetStaticMethodID = svcMemory.registerSvc(new Arm64Svc() {
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
                    if (hash == 0) {
                        log.info("event=guest_static_method_missing class={} name={} args={}", dvmClass.getClassName(), name, args);
                    }
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetStaticMethodID(%s.%s%s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash & 0xffffffffL, context.getLRPointer());
                    }
                    return hash;
                }
            }
        });

        Pointer _CallStaticObjectMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'L', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticObjectMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'L', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticObjectMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'L', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticBooleanMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'Z', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticBooleanMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'Z', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticBooleanMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'Z', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticByteMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'B', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticByteMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'B', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticByteMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'B', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticCharMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'C', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticCharMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'C', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticCharMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'C', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticShortMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'S', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticShortMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'S', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticShortMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'S', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticIntMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'I', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticIntMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'I', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticIntMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'I', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticLongMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'J', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticLongMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'J', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticLongMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'J', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticFloatMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'F', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticFloatMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'F', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticFloatMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'F', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticDoubleMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'D', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticDoubleMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'D', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticDoubleMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'D', true, JniInvocationSupport.Arguments.A);

        Pointer _CallStaticVoidMethod = JniInvocationSupport.method(emulator, DalvikVM64.this, 'V', true, JniInvocationSupport.Arguments.ORDINARY);

        Pointer _CallStaticVoidMethodV = JniInvocationSupport.method(emulator, DalvikVM64.this, 'V', true, JniInvocationSupport.Arguments.V);

        Pointer _CallStaticVoidMethodA = JniInvocationSupport.method(emulator, DalvikVM64.this, 'V', true, JniInvocationSupport.Arguments.A);

        Pointer _GetStaticFieldID = svcMemory.registerSvc(new Arm64Svc() {
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

        Pointer _GetStaticObjectField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'L', true, false);

        Pointer _GetStaticBooleanField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'Z', true, false);

        Pointer _GetStaticByteField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'B', true, false);

        Pointer _GetStaticCharField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'C', true, false);

        Pointer _GetStaticShortField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'S', true, false);

        Pointer _GetStaticIntField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'I', true, false);

        Pointer _GetStaticLongField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'J', true, false);

        Pointer _GetStaticFloatField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'F', true, false);

        Pointer _GetStaticDoubleField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'D', true, false);

        Pointer _SetStaticObjectField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'L', true, true);

        Pointer _SetStaticBooleanField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'Z', true, true);

        Pointer _SetStaticByteField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'B', true, true);

        Pointer _SetStaticCharField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'C', true, true);

        Pointer _SetStaticShortField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'S', true, true);

        Pointer _SetStaticIntField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'I', true, true);

        Pointer _SetStaticLongField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'J', true, true);

        Pointer _GetStringUTFLength = JniStringSupport.length(emulator, DalvikVM64.this, true);

        Pointer _GetStringUTFChars = JniStringSupport.borrow(emulator, DalvikVM64.this, true);

        Pointer _ReleaseStringUTFChars = JniStringSupport.release(emulator, DalvikVM64.this, true);

        Pointer _GetArrayLength = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer pointer = context.getPointerArg(1);
                Array<?> array = Objects.requireNonNull((Array<?>) resolveJniObject(pointer.toIntPeer()));
                if (log.isDebugEnabled()) {
                    log.debug("GetArrayLength array={}", array);
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetArrayLength(%s => %s) was called from %s%n", array, array.length(), context.getLRPointer());
                }
                return array.length();
            }
        });

        Pointer _NewObjectArray = svcMemory.registerSvc(new Arm64Svc() {
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
                ArrayObject arrayObject = ArrayObject.newObjectArray(dvmClass, array);
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->NewObjectArray(%s => %s) was called from %s%n", arrayObject, size, context.getLRPointer());
                }
                return addLocalObject(arrayObject);
            }
        });

        Pointer _GetObjectArrayElement = svcMemory.registerSvc(new Arm64Svc() {
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

        Pointer _SetObjectArrayElement = svcMemory.registerSvc(new Arm64Svc() {
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
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->SetObjectArrayElement(%s, %d) => %s was called from %s%n", array, index, obj, context.getLRPointer());
                }
                Objects.requireNonNull(array).setObject(index, obj);
                return 0;
            }
        });

        Pointer _NewBooleanArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM64.this, 'Z');

        Pointer _NewByteArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM64.this, 'B');

        Pointer _NewCharArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM64.this, 'C');

        Pointer _NewShortArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM64.this, 'S');

        Pointer _NewIntArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM64.this, 'I');

        Pointer _NewLongArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM64.this, 'J');

        Pointer _NewFloatArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM64.this, 'F');

        Pointer _NewDoubleArray = PrimitiveArrayJniSupport.create(emulator, DalvikVM64.this, 'D');

        Pointer _GetBooleanArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM64.this, 'Z');

        Pointer _GetByteArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM64.this, 'B');

        Pointer _GetCharArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM64.this, 'C');

        Pointer _GetShortArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM64.this, 'S');

        Pointer _GetIntArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM64.this, 'I');

        Pointer _SetStaticFloatField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'F', true, true);

        Pointer _SetStaticDoubleField = JniInvocationSupport.field(emulator, DalvikVM64.this, 'D', true, true);

        Pointer _NewString = JniStringSupport.create(emulator, DalvikVM64.this, false);

        Pointer _GetStringLength = JniStringSupport.length(emulator, DalvikVM64.this, false);

        Pointer _GetStringChars = JniStringSupport.borrow(emulator, DalvikVM64.this, false);

        Pointer _ReleaseStringChars = JniStringSupport.release(emulator, DalvikVM64.this, false);

        Pointer _NewStringUTF = JniStringSupport.create(emulator, DalvikVM64.this, true);

        Pointer _GetLongArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM64.this, 'J');

        Pointer _GetFloatArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM64.this, 'F');

        Pointer _GetDoubleArrayElements = PrimitiveArrayJniSupport.elements(emulator, DalvikVM64.this, 'D');

        Pointer _ReleaseBooleanArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM64.this, 'Z');

        Pointer _ReleaseByteArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM64.this, 'B');

        Pointer _ReleaseCharArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM64.this, 'C');

        Pointer _ReleaseShortArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM64.this, 'S');

        Pointer _ReleaseIntArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM64.this, 'I');

        Pointer _ReleaseLongArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM64.this, 'J');

        Pointer _ReleaseFloatArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM64.this, 'F');

        Pointer _ReleaseDoubleArrayElements = PrimitiveArrayJniSupport.release(emulator, DalvikVM64.this, 'D');

        Pointer _GetBooleanArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Boolean, false);

        Pointer _GetByteArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Byte, false);

        Pointer _GetCharArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Char, false);

        Pointer _GetShortArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Short, false);

        Pointer _GetIntArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Int, false);

        Pointer _GetLongArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Long, false);

        Pointer _GetFloatArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Float, false);

        Pointer _GetDoubleArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Double, false);

        Pointer _SetBooleanArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Boolean, true);

        Pointer _SetByteArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Byte, true);

        Pointer _SetCharArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Char, true);

        Pointer _SetShortArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Short, true);

        Pointer _SetIntArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Int, true);

        Pointer _SetLongArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Long, true);

        Pointer _SetFloatArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Float, true);

        Pointer _SetDoubleArrayRegion = JniInvocationSupport.arrayRegion(emulator, DalvikVM64.this, JniArrayRegion.Type.Double, true);

        Pointer _RegisterNatives = svcMemory.registerSvc(new Arm64Svc() {
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
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->RegisterNatives(%s, %s, %d) was called from %s%n", dvmClass.getClassName(), methods, nMethods, context.getLRPointer());
                }
                for (int i = 0; i < nMethods; i++) {
                    Pointer method = methods.share((long) i * emulator.getPointerSize() * 3);
                    Pointer name = method.getPointer(0);
                    Pointer signature = method.getPointer(emulator.getPointerSize());
                    Pointer fnPtr = method.getPointer(emulator.getPointerSize() * 2L);
                    String methodName = JniStringSupport.readModifiedUtf8(name);
                    String signatureValue = JniStringSupport.readModifiedUtf8(signature);
                    if (log.isDebugEnabled()) {
                        log.debug("RegisterNatives dvmClass={}, name={}, signature={}, fnPtr={}", dvmClass, methodName, signatureValue, fnPtr);
                    }
                    dvmClass.nativesMap.put(methodName + signatureValue, (UnidbgPointer) fnPtr);

                    if (verbose || verboseMethodOperation) {
                        System.out.printf("RegisterNative(%s, %s%s, %s)%n", dvmClass.getClassName(), methodName, signatureValue, fnPtr);
                    }
                }
                return JNI_OK;
            }
        });

        Pointer _UnregisterNatives = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _MonitorEnter = JniThreadJniSupport.monitor(emulator, DalvikVM64.this, true);

        Pointer _MonitorExit = JniThreadJniSupport.monitor(emulator, DalvikVM64.this, false);

        Pointer _GetJavaVM = svcMemory.registerSvc(new Arm64Svc() {
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

        Pointer _GetStringRegion = JniStringSupport.region(emulator, DalvikVM64.this, false);

        Pointer _GetStringUTFRegion = JniStringSupport.region(emulator, DalvikVM64.this, true);

        Pointer _GetPrimitiveArrayCritical = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                RegisterContext context = emulator.getContext();
                UnidbgPointer object = context.getPointerArg(1);
                Pointer isCopy = context.getPointerArg(2);
                PrimitiveArray<?> array = resolveJniObject(object.toIntPeer());
                if (log.isDebugEnabled()) {
                    log.debug("GetPrimitiveArrayCritical array={}, isCopy={}", array, isCopy);
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).peer;
            }
        });

        Pointer _ReleasePrimitiveArrayCritical = svcMemory.registerSvc(new Arm64Svc() {
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

        Pointer _GetStringCritical = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _ReleaseStringCritical = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _NewWeakGlobalRef = JniReferenceSupport.reference(emulator, DalvikVM64.this, "NewWeakGlobalRef");

        Pointer _DeleteWeakGlobalRef = JniReferenceSupport.reference(emulator, DalvikVM64.this, "DeleteWeakGlobalRef");

        Pointer _ExceptionCheck = JniExceptionSupport.exception(emulator, DalvikVM64.this, "ExceptionCheck");

        Pointer _NewDirectByteBuffer = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _GetDirectBufferAddress = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _GetDirectBufferCapacity = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        Pointer _GetObjectRefType = JniReferenceSupport.reference(emulator, DalvikVM64.this, "GetObjectRefType");

        Pointer _GetModule = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });

        final int last = 0x748;
        final UnidbgPointer impl = svcMemory.allocate(last + 8, "JNIEnv.impl");
        for (int i = 0; i <= last; i += 8) {
            impl.setLong(i, i);
        }
        impl.setPointer(0x20, _GetVersion);
        impl.setPointer(0x28, _DefineClass);
        impl.setPointer(0x30, _FindClass);
        impl.setPointer(0x38, _FromReflectedMethod);
        impl.setPointer(0x40, _FromReflectedField);
        impl.setPointer(0x48, _ToReflectedMethod);
        impl.setPointer(0x50, _GetSuperclass);
        impl.setPointer(0x58, _IsAssignableFrom);
        impl.setPointer(0x60, _ToReflectedField);
        impl.setPointer(0x68, _Throw);
        impl.setPointer(0x70, _ThrowNew);
        impl.setPointer(0x78, _ExceptionOccurred);
        impl.setPointer(0x80, _ExceptionDescribe);
        impl.setPointer(0x88, _ExceptionClear);
        impl.setPointer(0x90, _FatalError);
        impl.setPointer(0x98, _PushLocalFrame);
        impl.setPointer(0xa0, _PopLocalFrame);
        impl.setPointer(0xa8, _NewGlobalRef);
        impl.setPointer(0xb0, _DeleteGlobalRef);
        impl.setPointer(0xb8, _DeleteLocalRef);
        impl.setPointer(0xc0, _IsSameObject);
        impl.setPointer(0xc8, _NewLocalRef);
        impl.setPointer(0xd0, _EnsureLocalCapacity);
        impl.setPointer(0xd8, _AllocObject);
        impl.setPointer(0xe0, _NewObject);
        impl.setPointer(0xe8, _NewObjectV);
        impl.setPointer(0xf0, _NewObjectA);
        impl.setPointer(0xf8, _GetObjectClass);
        impl.setPointer(0x100, _IsInstanceOf);
        impl.setPointer(0x108, _GetMethodID);
        impl.setPointer(0x110, _CallObjectMethod);
        impl.setPointer(0x118, _CallObjectMethodV);
        impl.setPointer(0x120, _CallObjectMethodA);
        impl.setPointer(0x128, _CallBooleanMethod);
        impl.setPointer(0x130, _CallBooleanMethodV);
        impl.setPointer(0x138, _CallBooleanMethodA);
        impl.setPointer(0x140, _CallByteMethod);
        impl.setPointer(0x148, _CallByteMethodV);
        impl.setPointer(0x150, _CallByteMethodA);
        impl.setPointer(0x158, _CallCharMethod);
        impl.setPointer(0x160, _CallCharMethodV);
        impl.setPointer(0x168, _CallCharMethodA);
        impl.setPointer(0x170, _CallShortMethod);
        impl.setPointer(0x178, _CallShortMethodV);
        impl.setPointer(0x180, _CallShortMethodA);
        impl.setPointer(0x188, _CallIntMethod);
        impl.setPointer(0x190, _CallIntMethodV);
        impl.setPointer(0x198, _CallIntMethodA);
        impl.setPointer(0x1a0, _CallLongMethod);
        impl.setPointer(0x1a8, _CallLongMethodV);
        impl.setPointer(0x1b0, _CallLongMethodA);
        impl.setPointer(0x1b8, _CallFloatMethod);
        impl.setPointer(0x1c0, _CallFloatMethodV);
        impl.setPointer(0x1c8, _CallFloatMethodA);
        impl.setPointer(0x1d0, _CallDoubleMethod);
        impl.setPointer(0x1d8, _CallDoubleMethodV);
        impl.setPointer(0x1e0, _CallDoubleMethodA);
        impl.setPointer(0x1e8, _CallVoidMethod);
        impl.setPointer(0x1f0, _CallVoidMethodV);
        impl.setPointer(0x1f8, _CallVoidMethodA);
        impl.setPointer(0x200, _CallNonvirtualObjectMethod);
        impl.setPointer(0x208, _CallNonvirtualObjectMethodV);
        impl.setPointer(0x210, _CallNonvirtualObjectMethodA);
        impl.setPointer(0x218, _CallNonvirtualBooleanMethod);
        impl.setPointer(0x220, _CallNonvirtualBooleanMethodV);
        impl.setPointer(0x228, _CallNonvirtualBooleanMethodA);
        impl.setPointer(0x230, _CallNonvirtualByteMethod);
        impl.setPointer(0x238, _CallNonvirtualByteMethodV);
        impl.setPointer(0x240, _CallNonvirtualByteMethodA);
        impl.setPointer(0x248, _CallNonvirtualCharMethod);
        impl.setPointer(0x250, _CallNonvirtualCharMethodV);
        impl.setPointer(0x258, _CallNonvirtualCharMethodA);
        impl.setPointer(0x260, _CallNonvirtualShortMethod);
        impl.setPointer(0x268, _CallNonvirtualShortMethodV);
        impl.setPointer(0x270, _CallNonvirtualShortMethodA);
        impl.setPointer(0x278, _CallNonvirtualIntMethod);
        impl.setPointer(0x280, _CallNonvirtualIntMethodV);
        impl.setPointer(0x288, _CallNonvirtualIntMethodA);
        impl.setPointer(0x290, _CallNonvirtualLongMethod);
        impl.setPointer(0x298, _CallNonvirtualLongMethodV);
        impl.setPointer(0x2a0, _CallNonvirtualLongMethodA);
        impl.setPointer(0x2a8, _CallNonvirtualFloatMethod);
        impl.setPointer(0x2b0, _CallNonvirtualFloatMethodV);
        impl.setPointer(0x2b8, _CallNonvirtualFloatMethodA);
        impl.setPointer(0x2c0, _CallNonvirtualDoubleMethod);
        impl.setPointer(0x2c8, _CallNonvirtualDoubleMethodV);
        impl.setPointer(0x2d0, _CallNonvirtualDoubleMethodA);
        impl.setPointer(0x2d8, _CallNonvirtualVoidMethod);
        impl.setPointer(0x2e0, _CallNonvirtualVoidMethodV);
        impl.setPointer(0x2e8, _CallNonVirtualVoidMethodA);
        impl.setPointer(0x2f0, _GetFieldID);
        impl.setPointer(0x2f8, _GetObjectField);
        impl.setPointer(0x300, _GetBooleanField);
        impl.setPointer(0x308, _GetByteField);
        impl.setPointer(0x310, _GetCharField);
        impl.setPointer(0x318, _GetShortField);
        impl.setPointer(0x320, _GetIntField);
        impl.setPointer(0x328, _GetLongField);
        impl.setPointer(0x330, _GetFloatField);
        impl.setPointer(0x338, _GetDoubleField);
        impl.setPointer(0x340, _SetObjectField);
        impl.setPointer(0x348, _SetBooleanField);
        impl.setPointer(0x350, _SetByteField);
        impl.setPointer(0x358, _SetCharField);
        impl.setPointer(0x360, _SetShortField);
        impl.setPointer(0x368, _SetIntField);
        impl.setPointer(0x370, _SetLongField);
        impl.setPointer(0x378, _SetFloatField);
        impl.setPointer(0x380, _SetDoubleField);
        impl.setPointer(0x388, _GetStaticMethodID);
        impl.setPointer(0x390, _CallStaticObjectMethod);
        impl.setPointer(0x398, _CallStaticObjectMethodV);
        impl.setPointer(0x3a0, _CallStaticObjectMethodA);
        impl.setPointer(0x3a8, _CallStaticBooleanMethod);
        impl.setPointer(0x3b0, _CallStaticBooleanMethodV);
        impl.setPointer(0x3b8, _CallStaticBooleanMethodA);
        impl.setPointer(0x3c0, _CallStaticByteMethod);
        impl.setPointer(0x3c8, _CallStaticByteMethodV);
        impl.setPointer(0x3d0, _CallStaticByteMethodA);
        impl.setPointer(0x3d8, _CallStaticCharMethod);
        impl.setPointer(0x3e0, _CallStaticCharMethodV);
        impl.setPointer(0x3e8, _CallStaticCharMethodA);
        impl.setPointer(0x3f0, _CallStaticShortMethod);
        impl.setPointer(0x3f8, _CallStaticShortMethodV);
        impl.setPointer(0x400, _CallStaticShortMethodA);
        impl.setPointer(0x408, _CallStaticIntMethod);
        impl.setPointer(0x410, _CallStaticIntMethodV);
        impl.setPointer(0x418, _CallStaticIntMethodA);
        impl.setPointer(0x420, _CallStaticLongMethod);
        impl.setPointer(0x428, _CallStaticLongMethodV);
        impl.setPointer(0x430, _CallStaticLongMethodA);
        impl.setPointer(0x438, _CallStaticFloatMethod);
        impl.setPointer(0x440, _CallStaticFloatMethodV);
        impl.setPointer(0x448, _CallStaticFloatMethodA);
        impl.setPointer(0x450, _CallStaticDoubleMethod);
        impl.setPointer(0x458, _CallStaticDoubleMethodV);
        impl.setPointer(0x460, _CallStaticDoubleMethodA);
        impl.setPointer(0x468, _CallStaticVoidMethod);
        impl.setPointer(0x470, _CallStaticVoidMethodV);
        impl.setPointer(0x478, _CallStaticVoidMethodA);
        impl.setPointer(0x480, _GetStaticFieldID);
        impl.setPointer(0x488, _GetStaticObjectField);
        impl.setPointer(0x490, _GetStaticBooleanField);
        impl.setPointer(0x498, _GetStaticByteField);
        impl.setPointer(0x4a0, _GetStaticCharField);
        impl.setPointer(0x4a8, _GetStaticShortField);
        impl.setPointer(0x4b0, _GetStaticIntField);
        impl.setPointer(0x4b8, _GetStaticLongField);
        impl.setPointer(0x4c0, _GetStaticFloatField);
        impl.setPointer(0x4c8, _GetStaticDoubleField);
        impl.setPointer(0x4d0, _SetStaticObjectField);
        impl.setPointer(0x4d8, _SetStaticBooleanField);
        impl.setPointer(0x4e0, _SetStaticByteField);
        impl.setPointer(0x4e8, _SetStaticCharField);
        impl.setPointer(0x4f0, _SetStaticShortField);
        impl.setPointer(0x4f8, _SetStaticIntField);
        impl.setPointer(0x500, _SetStaticLongField);
        impl.setPointer(0x508, _SetStaticFloatField);
        impl.setPointer(0x510, _SetStaticDoubleField);
        impl.setPointer(0x518, _NewString);
        impl.setPointer(0x520, _GetStringLength);
        impl.setPointer(0x528, _GetStringChars);
        impl.setPointer(0x530, _ReleaseStringChars);
        impl.setPointer(0x538, _NewStringUTF);
        impl.setPointer(0x540, _GetStringUTFLength);
        impl.setPointer(0x548, _GetStringUTFChars);
        impl.setPointer(0x550, _ReleaseStringUTFChars);
        impl.setPointer(0x558, _GetArrayLength);
        impl.setPointer(0x560, _NewObjectArray);
        impl.setPointer(0x568, _GetObjectArrayElement);
        impl.setPointer(0x570, _SetObjectArrayElement);
        impl.setPointer(0x578, _NewBooleanArray);
        impl.setPointer(0x580, _NewByteArray);
        impl.setPointer(0x588, _NewCharArray);
        impl.setPointer(0x590, _NewShortArray);
        impl.setPointer(0x598, _NewIntArray);
        impl.setPointer(0x5a0, _NewLongArray);
        impl.setPointer(0x5a8, _NewFloatArray);
        impl.setPointer(0x5b0, _NewDoubleArray);
        impl.setPointer(0x5b8, _GetBooleanArrayElements);
        impl.setPointer(0x5c0, _GetByteArrayElements);
        impl.setPointer(0x5c8, _GetCharArrayElements);
        impl.setPointer(0x5d0, _GetShortArrayElements);
        impl.setPointer(0x5d8, _GetIntArrayElements);
        impl.setPointer(0x5e0, _GetLongArrayElements);
        impl.setPointer(0x5e8, _GetFloatArrayElements);
        impl.setPointer(0x5f0, _GetDoubleArrayElements);
        impl.setPointer(0x5f8, _ReleaseBooleanArrayElements);
        impl.setPointer(0x600, _ReleaseByteArrayElements);
        impl.setPointer(0x608, _ReleaseCharArrayElements);
        impl.setPointer(0x610, _ReleaseShortArrayElements);
        impl.setPointer(0x618, _ReleaseIntArrayElements);
        impl.setPointer(0x620, _ReleaseLongArrayElements);
        impl.setPointer(0x628, _ReleaseFloatArrayElements);
        impl.setPointer(0x630, _ReleaseDoubleArrayElements);
        impl.setPointer(0x638, _GetBooleanArrayRegion);
        impl.setPointer(0x640, _GetByteArrayRegion);
        impl.setPointer(0x648, _GetCharArrayRegion);
        impl.setPointer(0x650, _GetShortArrayRegion);
        impl.setPointer(0x658, _GetIntArrayRegion);
        impl.setPointer(0x660, _GetLongArrayRegion);
        impl.setPointer(0x668, _GetFloatArrayRegion);
        impl.setPointer(0x670, _GetDoubleArrayRegion);
        impl.setPointer(0x678, _SetBooleanArrayRegion);
        impl.setPointer(0x680, _SetByteArrayRegion);
        impl.setPointer(0x688, _SetCharArrayRegion);
        impl.setPointer(0x690, _SetShortArrayRegion);
        impl.setPointer(0x698, _SetIntArrayRegion);
        impl.setPointer(0x6a0, _SetLongArrayRegion);
        impl.setPointer(0x6a8, _SetFloatArrayRegion);
        impl.setPointer(0x6b0, _SetDoubleArrayRegion);
        impl.setPointer(0x6b8, _RegisterNatives);
        impl.setPointer(0x6c0, _UnregisterNatives);
        impl.setPointer(0x6c8, _MonitorEnter);
        impl.setPointer(0x6d0, _MonitorExit);
        impl.setPointer(0x6d8, _GetJavaVM);
        impl.setPointer(0x6e0, _GetStringRegion);
        impl.setPointer(0x6e8, _GetStringUTFRegion);
        impl.setPointer(0x6f0, _GetPrimitiveArrayCritical);
        impl.setPointer(0x6f8, _ReleasePrimitiveArrayCritical);
        impl.setPointer(0x700, _GetStringCritical);
        impl.setPointer(0x708, _ReleaseStringCritical);
        impl.setPointer(0x710, _NewWeakGlobalRef);
        impl.setPointer(0x718, _DeleteWeakGlobalRef);
        impl.setPointer(0x720, _ExceptionCheck);
        impl.setPointer(0x728, _NewDirectByteBuffer);
        impl.setPointer(0x730, _GetDirectBufferAddress);
        impl.setPointer(0x738, _GetDirectBufferCapacity);
        impl.setPointer(0x740, _GetObjectRefType);
        impl.setPointer(last, _GetModule);

        bindJniThreads(impl);

        UnidbgPointer _DestroyJavaVM = svcMemory.registerSvc(new Arm64Svc() {
            @Override
            public long handle(Emulator<?> emulator) {
                throw new UnsupportedOperationException();
            }
        });
        Pointer _AttachCurrentThread = JniThreadJniSupport.invocation(emulator, DalvikVM64.this, "AttachCurrentThread");

        Pointer _DetachCurrentThread = JniThreadJniSupport.invocation(emulator, DalvikVM64.this, "DetachCurrentThread");

        Pointer _GetEnv = JniThreadJniSupport.invocation(emulator, DalvikVM64.this, "GetEnv");

        Pointer _AttachCurrentThreadAsDaemon = JniThreadJniSupport.invocation(emulator, DalvikVM64.this, "AttachCurrentThreadAsDaemon");

        UnidbgPointer _JNIInvokeInterface = svcMemory.allocate(emulator.getPointerSize() * 8, "_JNIInvokeInterface");
        for (int i = 0; i < emulator.getPointerSize() * 8; i += emulator.getPointerSize()) {
            _JNIInvokeInterface.setLong(i, i);
        }
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 3L, _DestroyJavaVM);
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 4L, _AttachCurrentThread);
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 5L, _DetachCurrentThread);
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 6L, _GetEnv);
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 7L, _AttachCurrentThreadAsDaemon);

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
        byte[] soData = apk.getFileData("lib/arm64-v8a/" + soName);
        if (soData != null) {
            if (log.isDebugEnabled()) {
                log.debug("resolve arm64-v8a library: {}", soName);
            }
            return soData;
        } else {
            return null;
        }
    }
}
