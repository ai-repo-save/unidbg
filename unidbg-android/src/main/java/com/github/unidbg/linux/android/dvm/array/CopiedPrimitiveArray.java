package com.github.unidbg.linux.android.dvm.array;

import com.github.unidbg.Emulator;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

abstract class CopiedPrimitiveArray<T> extends BaseArray<T> implements PrimitiveArray<T> {
    private static final Logger log = LoggerFactory.getLogger(CopiedPrimitiveArray.class);
    private final int elementSize;
    private final Map<Long, MemoryBlock> leases = new LinkedHashMap<>();

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
        MemoryBlock block = emulator.getMemory().malloc(Math.max(1, length() * elementSize), true);
        try {
            UnidbgPointer pointer = block.getPointer();
            copyTo(pointer);
            if (isCopy != null) isCopy.setByte(0, (byte) VM.JNI_TRUE);
            leases.put(pointer.peer, block);
            log.debug("event=jni_array_copy_acquired array={} pointer={} length={} element_size={}",
                    this, pointer, length(), elementSize);
            return pointer;
        } catch (RuntimeException | Error failure) {
            block.free();
            throw failure;
        }
    }

    @Override
    public final void _ReleaseArrayCritical(Pointer pointer, int mode) {
        long address = UnidbgPointer.nativeValue(pointer);
        MemoryBlock block = leases.get(address);
        if (block == null) throw new IllegalArgumentException("Unknown JNI array copy: " + pointer);
        if (mode != 0 && mode != VM.JNI_COMMIT && mode != VM.JNI_ABORT) {
            throw new IllegalArgumentException("Invalid JNI array release mode: " + mode);
        }
        if (mode == VM.JNI_COMMIT) {
            copyFrom(pointer);
        } else {
            try {
                if (mode == 0) copyFrom(pointer);
            } finally {
                leases.remove(address);
                block.free();
            }
        }
        log.debug("event=jni_array_copy_released array={} pointer={} mode={} retained={}",
                this, pointer, mode, mode == VM.JNI_COMMIT);
    }

    @Override
    protected void releaseAdditionalMemory() {
        int count = leases.size();
        for (MemoryBlock block : leases.values()) block.free();
        leases.clear();
        log.debug("event=jni_array_copies_cleaned array={} copies={}", this, count);
    }
}
