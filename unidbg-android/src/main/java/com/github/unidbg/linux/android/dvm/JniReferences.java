package com.github.unidbg.linux.android.dvm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Backend-thread-owned handles; guest local visibility is scoped to a thread's frame stack. */
final class JniReferences implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JniReferences.class);

    private static final class Root {
        final DvmObject<?> object;
        int count;

        Root(DvmObject<?> object) {
            this.object = object;
            this.count = 1;
        }
    }

    private static final class Reference {
        final int kind;
        final JniLocalFrames owner;
        final JniReferenceRoot strong;
        final WeakReference<Object> weak;

        Reference(int kind, JniLocalFrames owner, JniReferenceRoot strong, WeakReference<Object> weak) {
            this.kind = kind;
            this.owner = owner;
            this.strong = strong;
            this.weak = weak;
        }
    }

    private final Thread backendThread = Thread.currentThread();
    private final Map<Integer, Reference> handles = new HashMap<>();
    private final Map<Object, Root> roots = new IdentityHashMap<>();
    private final Function<DvmObject<?>, Object> identity;
    private final Function<Object, DvmObject<?>> materialize;
    private final Consumer<DvmObject<?>> dispose;
    private int nextHandle = 1;
    private boolean closed;

    JniReferences() {
        this(object -> object, object -> (DvmObject<?>) object, DvmObject::onDeleteRef);
    }

    /** Callbacks run on the backend owner: identity bookkeeping or owned-resource disposal, never application Java. */
    JniReferences(Function<DvmObject<?>, Object> identity,
                  Function<Object, DvmObject<?>> materialize, Consumer<DvmObject<?>> dispose) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.materialize = Objects.requireNonNull(materialize, "materialize");
        this.dispose = Objects.requireNonNull(dispose, "dispose");
    }

    DvmObject<?> canonicalize(DvmObject<?> object) {
        requireOpen();
        if (object == null) return null;
        Root root = roots.get(Objects.requireNonNull(identity.apply(object), "object identity"));
        return root == null ? object : root.object;
    }

    JniReferenceRoot retain(DvmObject<?> object) {
        requireOpen();
        Objects.requireNonNull(object, "object");
        Object key = Objects.requireNonNull(identity.apply(object), "object identity");
        Root root = roots.get(key);
        JniReferenceRoot retained = new JniReferenceRoot(this, key, root == null ? object : root.object);
        if (root == null) {
            roots.put(key, new Root(object));
        } else {
            root.count++;
        }
        return retained;
    }

    void releaseRoot(Object key, DvmObject<?> object) {
        requireOwner();
        Root root = roots.get(key);
        if (root != null && root.object == object && --root.count == 0) {
            roots.remove(key);
            log.debug("event=jni_reference_object_unrooted reference_owner_id={}", System.identityHashCode(this));
            dispose.accept(object);
        }
    }

    int newLocal(DvmObject<?> object, JniLocalFrames owner) {
        return newStrong(object, VM.JNILocalRefType, Objects.requireNonNull(owner, "owner"));
    }

    int newGlobal(DvmObject<?> object) {
        return newStrong(object, VM.JNIGlobalRefType, null);
    }

    private int newStrong(DvmObject<?> object, int kind, JniLocalFrames owner) {
        requireOpen();
        if (object == null) return VM.JNI_NULL;
        JniReferenceRoot root = retain(object);
        try {
            int handle = allocateHandle();
            handles.put(handle, new Reference(kind, owner, root, null));
            log.debug("event=jni_reference_created reference_owner_id={} handle={} kind={} guest_tid={}",
                    System.identityHashCode(this), handle, kind, owner == null ? null : owner.tid());
            return handle;
        } catch (RuntimeException | Error failure) {
            try {
                root.close();
            } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    int newWeakGlobal(DvmObject<?> object) {
        requireOpen();
        if (object == null) return VM.JNI_NULL;
        Object key = Objects.requireNonNull(identity.apply(object), "object identity");
        int handle = allocateHandle();
        handles.put(handle, new Reference(VM.JNIWeakGlobalRefType, null, null, new WeakReference<>(key)));
        log.debug("event=jni_weak_reference_created reference_owner_id={} handle={}", System.identityHashCode(this), handle);
        return handle;
    }

    DvmObject<?> find(int handle, JniLocalFrames owner) {
        requireOpen();
        return handle != VM.JNI_NULL && !handles.containsKey(handle) ? null : lookup(handle, owner);
    }

    DvmObject<?> lookup(int handle, JniLocalFrames owner) {
        requireOpen();
        if (handle == VM.JNI_NULL) return null;
        Reference reference = visible(handle, owner);
        if (reference.strong != null) return reference.strong.object();
        Object key = reference.weak.get();
        if (key == null) return null;
        Root rooted = roots.get(key);
        if (rooted != null) return rooted.object;
        return Objects.requireNonNull(materialize.apply(key), "materialized object");
    }

    int count(int kind) {
        requireOpen();
        int count = 0;
        for (Reference reference : handles.values()) if (reference.kind == kind) count++;
        return count;
    }

    int referenceType(int handle, JniLocalFrames owner) {
        requireOpen();
        return handle == VM.JNI_NULL ? VM.JNIInvalidRefType : visible(handle, owner).kind;
    }

    boolean isSameObject(int first, int second, JniLocalFrames owner) {
        requireOpen();
        return referentIdentity(first, owner) == referentIdentity(second, owner);
    }

    private Object referentIdentity(int handle, JniLocalFrames owner) {
        if (handle == VM.JNI_NULL) return null;
        Reference reference = visible(handle, owner);
        return reference.strong == null ? reference.weak.get() : identity.apply(reference.strong.object());
    }

    private Reference visible(int handle, JniLocalFrames owner) {
        requireOpen();
        Reference reference = handles.get(handle);
        if (reference == null) throw new IllegalArgumentException("Unknown JNI reference: " + handle);
        if (reference.kind == VM.JNILocalRefType && reference.owner != owner)
            throw new IllegalArgumentException("JNI local reference belongs to another thread: " + handle);
        return reference;
    }

    void deleteLocal(int handle, JniLocalFrames owner) {
        delete(handle, VM.JNILocalRefType, owner);
    }

    void deleteGlobal(int handle) {
        delete(handle, VM.JNIGlobalRefType, null);
    }

    void deleteWeakGlobal(int handle) {
        delete(handle, VM.JNIWeakGlobalRefType, null);
    }

    private void delete(int handle, int kind, JniLocalFrames owner) {
        requireOpen();
        if (handle == VM.JNI_NULL) return;
        Reference reference = visible(handle, owner);
        if (reference.kind != kind) throw new IllegalArgumentException("JNI reference has another kind: " + handle);
        handles.remove(handle);
        if (reference.strong != null) reference.strong.close();
        log.debug("event=jni_reference_deleted reference_owner_id={} handle={} kind={}",
                System.identityHashCode(this), handle, kind);
    }

    private int allocateHandle() {
        if (nextHandle == 0) throw new OutOfMemoryError("JNI reference handle space exhausted");
        return nextHandle++;
    }

    void requireOwner() {
        if (Thread.currentThread() != backendThread)
            throw new IllegalStateException("JNI references require the backend owner thread");
    }

    private void requireOpen() {
        requireOwner();
        if (closed) throw new IllegalStateException("JNI references are closed");
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        List<DvmObject<?>> objects = new ArrayList<>();
        closed = true;
        for (Root root : roots.values()) objects.add(root.object);
        handles.clear();
        roots.clear();
        Throwable failure = null;
        for (DvmObject<?> object : objects) {
            try {
                dispose.accept(object);
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        log.debug("event=jni_references_closed reference_owner_id={} objects={}", System.identityHashCode(this), objects.size());
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
}
