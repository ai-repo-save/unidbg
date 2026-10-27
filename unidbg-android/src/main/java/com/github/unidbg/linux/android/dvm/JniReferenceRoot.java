package com.github.unidbg.linux.android.dvm;

/** A strong JNI root independent of a particular local/global handle. */
final class JniReferenceRoot implements AutoCloseable {
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
        return object;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        references.releaseRoot(identity, object);
    }
}
