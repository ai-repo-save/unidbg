package com.github.unidbg.linux.file.tcp;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

final class SocketAddress {
    final byte[] bytes;
    final IntByReference length;
    private final Pointer destination, destinationLength;
    private final int capacity;

    SocketAddress(Pointer destination, Pointer destinationLength) {
        this.destination = destination;
        this.destinationLength = destinationLength;
        capacity = destinationLength == null ? 0 : destinationLength.getInt(0);
        bytes = destination == null ? null : new byte[128];
        length = destinationLength == null ? null : new IntByReference(capacity < 0 || destination == null ? capacity : 128);
    }

    void publish() {
        if (destination != null) {
            int count = (int) Math.min(Integer.toUnsignedLong(capacity), length.getValue());
            destination.write(0, bytes, 0, count);
            destinationLength.setInt(0, length.getValue());
        }
    }

    static byte[] input(Pointer address, int length) {
        return address == null ? null : address.getByteArray(0, length);
    }
}
