package com.github.unidbg.linux.android.dvm;

import java.util.Objects;

/** JNI MUTF-8, without a length prefix or C terminator; surrogate code units stay separate. */
final class ModifiedUtf8 {
    static int encodedLength(String value) {
        Objects.requireNonNull(value, "value");
        return encodedLength(value, 0, value.length());
    }

    static byte[] encode(String value) {
        Objects.requireNonNull(value, "value");
        return encodeRegion(value, 0, value.length());
    }

    static byte[] encodeRegion(String value, int start, int length) {
        Objects.requireNonNull(value, "value");
        if (start < 0 || length < 0 || start > value.length() || length > value.length() - start)
            throw new StringIndexOutOfBoundsException("start=" + start + ", length=" + length + ", size=" + value.length());
        byte[] bytes = new byte[encodedLength(value, start, length)];
        int offset = 0;
        for (int index = start; index < start + length; index++) {
            char unit = value.charAt(index);
            if (unit != 0 && unit < 0x80) {
                bytes[offset++] = (byte) unit;
            } else if (unit < 0x800) {
                bytes[offset++] = (byte) (0xc0 | (unit >>> 6));
                bytes[offset++] = (byte) (0x80 | (unit & 0x3f));
            } else {
                bytes[offset++] = (byte) (0xe0 | (unit >>> 12));
                bytes[offset++] = (byte) (0x80 | ((unit >>> 6) & 0x3f));
                bytes[offset++] = (byte) (0x80 | (unit & 0x3f));
            }
        }
        return bytes;
    }

    private static int encodedLength(String value, int start, int length) {
        long bytes = 0;
        for (int index = start; index < start + length; index++) {
            char unit = value.charAt(index);
            bytes += unit != 0 && unit < 0x80 ? 1 : unit < 0x800 ? 2 : 3;
        }
        if (bytes > Integer.MAX_VALUE) throw new OutOfMemoryError("JNI MUTF-8 byte array is too large");
        return (int) bytes;
    }

    static String decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        char[] units = new char[bytes.length];
        int count = 0;
        for (int offset = 0; offset < bytes.length;) {
            int start = offset;
            int first = bytes[offset++] & 0xff;
            if (first > 0 && first < 0x80) {
                units[count++] = (char) first;
            } else if ((first & 0xe0) == 0xc0) {
                int second = continuation(bytes, offset++);
                int unit = ((first & 0x1f) << 6) | second;
                if (unit != 0 && unit < 0x80) throw invalid(start);
                units[count++] = (char) unit;
            } else if ((first & 0xf0) == 0xe0) {
                int second = continuation(bytes, offset++);
                int third = continuation(bytes, offset++);
                int unit = ((first & 0x0f) << 12) | (second << 6) | third;
                if (unit < 0x800) throw invalid(start);
                units[count++] = (char) unit;
            } else {
                throw invalid(start);
            }
        }
        return new String(units, 0, count);
    }

    private static int continuation(byte[] bytes, int offset) {
        if (offset >= bytes.length || (bytes[offset] & 0xc0) != 0x80) throw invalid(offset);
        return bytes[offset] & 0x3f;
    }

    private static IllegalArgumentException invalid(int offset) {
        return new IllegalArgumentException("Invalid JNI MUTF-8 at byte offset " + offset);
    }

    private ModifiedUtf8() {}
}
