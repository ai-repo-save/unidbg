package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.Emulator;
import com.github.unidbg.linux.android.dvm.JniReferenceRoot;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class CopiedPrimitiveArray<T> extends BaseArray<T> implements PrimitiveArray<T> {
    private static final Logger log = LoggerFactory.getLogger(CopiedPrimitiveArray.class);

    private static final class Lease implements AutoCloseable {
        final MemoryBlock block;
        final JniReferenceRoot root;

        Lease(MemoryBlock block, JniReferenceRoot root) { this.block = block; this.root = root; }

        @Override
        public void close() {
            try (JniReferenceRoot ignored = root) { block.free(); }
        }
    }

    private final int elementSize;
    private final Map<Long, Lease> leases = new LinkedHashMap<>();

    CopiedPrimitiveArray(VM vm, String descriptor, T value, int elementSize) {
        super(vm.resolveClass(descriptor), value);
        this.elementSize = elementSize;
    }

    @Override
    public final void setData(int start, T data) {
        System.arraycopy(data, 0, value, start, java.lang.reflect.Array.getLength(data));
    }

    protected abstract void copyTo(Pointer pointer);
    protected abstract void copyFrom(Pointer pointer);

    @Override
    public final UnidbgPointer _GetArrayCritical(Emulator<?> emulator, Pointer isCopy) {
        long bytes = (long) length() * elementSize;
        if (bytes > Integer.MAX_VALUE) throw new OutOfMemoryError("JNI array buffer is too large");
        MemoryBlock block = emulator.getMemory().malloc((int) Math.max(1, bytes), true);
        JniReferenceRoot root = null;
        try {
            root = retainNativeLease();
            UnidbgPointer pointer = block.getPointer();
            copyTo(pointer);
            if (isCopy != null) isCopy.setByte(0, (byte) VM.JNI_TRUE);
            leases.put(pointer.peer, new Lease(block, root));
            log.debug("event=jni_array_copy_acquired array={} pointer={} length={} element_size={}",
                    getObjectType(), pointer, length(), elementSize);
            return pointer;
        } catch (RuntimeException | Error failure) {
            try (JniReferenceRoot ignored = root) {
                block.free();
            } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public final void _ReleaseArrayCritical(Pointer pointer, int mode) {
        long address = UnidbgPointer.nativeValue(pointer);
        Lease lease = leases.get(address);
        if (lease == null) throw new IllegalArgumentException("Unknown JNI array copy: " + pointer);
        if (mode != 0 && mode != VM.JNI_COMMIT && mode != VM.JNI_ABORT)
            throw new IllegalArgumentException("Invalid JNI array release mode: " + mode);
        if (mode == VM.JNI_COMMIT) {
            copyFrom(pointer);
        } else {
            leases.remove(address);
            try (Lease ignored = lease) {
                if (mode == 0) copyFrom(pointer);
            }
        }
        log.debug("event=jni_array_copy_released array={} pointer={} mode={} retained={}",
                getObjectType(), pointer, mode, mode == VM.JNI_COMMIT);
    }

    @Override
    protected void releaseAdditionalMemory() {
        List<Lease> remaining = new ArrayList<>(leases.values());
        leases.clear();
        Throwable failure = null;
        for (Lease lease : remaining) {
            try {
                lease.close();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        log.debug("event=jni_array_copies_cleaned array={} copies={}", getObjectType(), remaining.size());
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
}
