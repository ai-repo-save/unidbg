package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class StringObject extends DvmObject<String> {
    private static final class Lease implements AutoCloseable {
        final MemoryBlock block;
        final JniReferenceRoot root;
        final boolean utf;

        Lease(MemoryBlock block, JniReferenceRoot root, boolean utf) {
            this.block = block; this.root = root; this.utf = utf;
        }

        @Override
        public void close() { try (JniReferenceRoot ignored = root) { block.free(); } }
    }

    private final Map<Long, Lease> leases = new LinkedHashMap<>();

    public StringObject(VM vm, String value) {
        super(vm.resolveClass("java/lang/String"), value);
        if (value == null) throw new NullPointerException("value");
    }

    UnidbgPointer borrow(Emulator<?> emulator, Pointer isCopy, boolean utf) {
        byte[] bytes;
        if (utf) {
            byte[] encoded = ModifiedUtf8.encode(value);
            if (encoded.length == Integer.MAX_VALUE) throw new OutOfMemoryError("JNI string buffer is too large");
            bytes = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        } else {
            if (value.length() > Integer.MAX_VALUE / 2) throw new OutOfMemoryError("JNI UTF-16 buffer is too large");
            ByteBuffer buffer = ByteBuffer.allocate(value.length() * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < value.length(); index++) buffer.putChar(value.charAt(index));
            bytes = buffer.array();
        }
        MemoryBlock block = emulator.getMemory().malloc(Math.max(1, bytes.length), true);
        JniReferenceRoot root = null;
        try {
            root = retainNativeLease();
            UnidbgPointer pointer = block.getPointer();
            pointer.write(0, bytes, 0, bytes.length);
            if (isCopy != null) isCopy.setByte(0, (byte) VM.JNI_TRUE);
            leases.put(pointer.peer, new Lease(block, root, utf));
            return pointer;
        } catch (RuntimeException | Error failure) {
            try (JniReferenceRoot ignored = root) { block.free(); }
            catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void release(Pointer pointer, boolean utf) {
        long address = UnidbgPointer.nativeValue(pointer);
        Lease lease = leases.get(address);
        if (lease == null || lease.utf != utf) throw new IllegalArgumentException("Unknown JNI string lease: " + pointer);
        leases.remove(address);
        lease.close();
    }

    @Override
    protected void releaseAdditionalMemory() {
        ArrayList<Lease> remaining = new ArrayList<>(leases.values());
        leases.clear();
        Throwable failure = null;
        for (Lease lease : remaining) {
            try { lease.close(); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    @Override
    public String toString() { return '"' + value + '"'; }
}
