package com.github.unidbg.linux.android.dvm;

/** A backend-owned strong JNI root independent of a particular local/global handle. */
public final class JniReferenceRoot implements AutoCloseable {
    private final JniReferences references;
    private final Object identity;
    private final DvmObject<?> object;
    private boolean closed;

    JniReferenceRoot(JniReferences references, Object identity, DvmObject<?> object) {
        this.references = references;
        this.identity = identity;
        this.object = object;
    }

    DvmObject<?> object() {
        references.requireOwner();
        return object;
    }

    @Override
    public void close() {
        references.requireOwner();
        if (closed) return;
        closed = true;
        references.releaseRoot(identity, object);
    }
}
