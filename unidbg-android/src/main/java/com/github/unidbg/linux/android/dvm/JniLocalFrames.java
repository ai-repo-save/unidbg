package com.github.unidbg.linux.android.dvm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Objects;

/** Backend-owned local frames of one guest Java thread, independent of host ThreadLocal. */
final class JniLocalFrames {
    private static final Logger log = LoggerFactory.getLogger(JniLocalFrames.class);
    private final JniReferences references;
    private final int tid;
    private final Deque<ArrayList<Integer>> frames = new ArrayDeque<>();

    JniLocalFrames(JniReferences references, int tid) {
        this.references = Objects.requireNonNull(references, "references");
        references.requireOwner();
        this.tid = tid;
        frames.push(new ArrayList<>(16));
    }

    int tid() {
        references.requireOwner();
        return tid;
    }

    /** Allocation failure propagates to the JNI shim, which installs pending OOME. */
    int push(int capacity) {
        references.requireOwner();
        if (capacity < 0) throw new IllegalArgumentException("Negative JNI local frame capacity: " + capacity);
        frames.push(new ArrayList<>(capacity));
        log.debug("event=jni_local_frame_pushed guest_tid={} capacity={} depth={}", tid, capacity, frames.size());
        return VM.JNI_OK;
    }

    int ensureCapacity(int capacity) {
        references.requireOwner();
        if (capacity < 0) throw new IllegalArgumentException("Negative JNI local capacity: " + capacity);
        ArrayList<Integer> frame = frames.element();
        long required = (long) frame.size() + capacity;
        if (required > Integer.MAX_VALUE) throw new OutOfMemoryError("JNI local reference capacity exhausted");
        frame.ensureCapacity((int) required);
        log.debug("event=jni_local_capacity_reserved guest_tid={} capacity={} depth={}", tid, capacity, frames.size());
        return VM.JNI_OK;
    }

    int newLocal(DvmObject<?> object) {
        references.requireOwner();
        if (object == null) return VM.JNI_NULL;
        int handle = references.newLocal(object, this);
        try {
            frames.element().add(handle);
            return handle;
        } catch (RuntimeException | Error failure) {
            try {
                references.deleteLocal(handle, this);
            } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    DvmObject<?> lookup(int handle) {
        references.requireOwner();
        return references.lookup(handle, this);
    }

    void deleteLocal(int handle) {
        references.requireOwner();
        if (handle == VM.JNI_NULL) return;
        for (ArrayList<Integer> frame : frames) {
            if (frame.remove(Integer.valueOf(handle))) {
                references.deleteLocal(handle, this);
                return;
            }
        }
        throw new IllegalArgumentException("JNI local reference is outside this frame stack: " + handle);
    }

    int pop(int escapedHandle) {
        references.requireOwner();
        if (frames.size() == 1) throw new IllegalStateException("Cannot pop the JNI thread root frame");
        DvmObject<?> escaped = lookup(escapedHandle);
        try (JniReferenceRoot root = escaped == null ? null : references.retain(escaped)) {
            ArrayList<Integer> removed = frames.pop();
            release(removed);
            int result = newLocal(escaped);
            log.debug("event=jni_local_frame_popped guest_tid={} escaped_handle={} result_handle={} depth={}",
                    tid, escapedHandle, result, frames.size());
            return result;
        }
    }

    void clear() {
        references.requireOwner();
        ArrayList<Integer> removed = new ArrayList<>();
        for (ArrayList<Integer> frame : frames) removed.addAll(frame);
        frames.clear();
        frames.push(new ArrayList<>());
        release(removed);
        log.debug("event=jni_local_frames_cleared guest_tid={}", tid);
    }

    private void release(ArrayList<Integer> handles) {
        Throwable failure = null;
        for (int handle : handles) {
            try {
                references.deleteLocal(handle, this);
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
}
