package com.github.unidbg.pointer;

import com.github.unidbg.InvalidMemoryAccessException;
import com.sun.jna.Pointer;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ByteArrayPointerIntegrationTest {
    public static class Buffer extends UnidbgStructure {
        public byte[] bytes = new byte[256];

        public Buffer(byte[] data) {
            super(data);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Collections.singletonList("bytes");
        }
    }

    @Test
    public void sharedViewsReadAndWriteTheOriginalBacking() {
        byte[] backing = new byte[256];
        Pointer root = new Buffer(backing).getPointer();
        root.setString(0, "root");
        Pointer view = root.share(16, 224).share(8, 128);
        int base = 24;
        byte[] expected = backing.clone();
        ByteBuffer raw = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);

        view.setByte(0, (byte) 0x71);
        raw.put(base, (byte) 0x71);
        view.setShort(2, (short) 0x1234);
        raw.putShort(base + 2, (short) 0x1234);
        view.setChar(4, '\u754c');
        raw.putChar(base + 4, '\u754c');
        view.setInt(8, 0x12345678);
        raw.putInt(base + 8, 0x12345678);
        view.setLong(16, 0x1122334455667788L);
        raw.putLong(base + 16, 0x1122334455667788L);
        view.setFloat(24, 1.5f);
        raw.putFloat(base + 24, 1.5f);
        view.setDouble(32, 2.5);
        raw.putDouble(base + 32, 2.5);
        view.write(40, new short[] {7, 8}, 0, 2);
        raw.putShort(base + 40, (short) 7).putShort(base + 42, (short) 8);
        view.write(48, new int[] {9, 10}, 0, 2);
        raw.putInt(base + 48, 9).putInt(base + 52, 10);
        view.write(56, new long[] {11, 12}, 0, 2);
        raw.putLong(base + 56, 11).putLong(base + 64, 12);
        view.write(72, new float[] {3.5f, 4.5f}, 0, 2);
        raw.putFloat(base + 72, 3.5f).putFloat(base + 76, 4.5f);
        view.write(88, new double[] {5.5, 6.5}, 0, 2);
        raw.putDouble(base + 88, 5.5).putDouble(base + 96, 6.5);
        view.setString(104, "shared");
        byte[] text = "shared\0".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(text, 0, expected, base + 104, text.length);
        view.setString(116, "\u00e9", "ISO-8859-1");
        expected[base + 116] = (byte) 0xe9;
        view.setString(124, "\u754c");
        System.arraycopy("\u754c\0".getBytes(StandardCharsets.UTF_8), 0, expected, base + 124, 4);
        assertArrayEquals(expected, backing);

        assertEquals((byte) 0x71, view.getByte(0));
        assertEquals((short) 0x1234, view.getShort(2));
        assertEquals('\u754c', view.getChar(4));
        assertEquals(0x12345678, view.getInt(8));
        assertEquals(0x1122334455667788L, view.getLong(16));
        assertEquals(1.5f, view.getFloat(24), 0);
        assertEquals(2.5, view.getDouble(32), 0);
        assertArrayEquals(new int[] {9, 10}, view.getIntArray(48, 2));
        assertArrayEquals(new long[] {11, 12}, view.getLongArray(56, 2));
        assertArrayEquals(new float[] {3.5f, 4.5f}, view.getFloatArray(72, 2), 0);
        assertArrayEquals(new double[] {5.5, 6.5}, view.getDoubleArray(88, 2), 0);
        assertEquals("shared", view.getString(104));
        assertEquals("\u00e9", view.getString(116, "ISO-8859-1"));
        assertEquals("\u754c", view.share(124, 4).getString(0));
        assertArrayEquals(Arrays.copyOfRange(expected, base, base + 128), view.getByteArray(0, 128));
        byte[] read = new byte[8];
        view.read(8, read, 0, read.length);
        assertArrayEquals(Arrays.copyOfRange(expected, base + 8, base + 16), read);
        assertEquals(0x12345678, view.getByteBuffer(8, 4).getInt());

        root.setString(base + 104, "update");
        assertEquals("update", view.getString(104));
        view.setMemory(104, 6, (byte) 'x');
        assertArrayEquals("xxxxxx\0".getBytes(StandardCharsets.UTF_8), root.getByteArray(base + 104, 7));
        assertThrows(InvalidMemoryAccessException.class, () -> view.share(104, 6).getString(0));
        assertThrows(InvalidMemoryAccessException.class, () -> view.getByte(128));
        assertThrows(InvalidMemoryAccessException.class, () -> view.setByte(128, (byte) 1));
        assertEquals("root", root.getString(0));
    }
}
