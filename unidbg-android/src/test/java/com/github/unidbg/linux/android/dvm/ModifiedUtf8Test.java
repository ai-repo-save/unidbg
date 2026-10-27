package com.github.unidbg.linux.android.dvm;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Oracle JNI Types, Modified UTF-8 Strings: NUL and each surrogate code unit. */
public class ModifiedUtf8Test {
    @Test
    public void codecMatchesJniCodeUnitEncoding() throws Exception {
        String[] values = {"", "A", "\u0000", "\u007f\u0080\u07ff\u0800",
                "\u4e2d", "\ud83d\ude03", "\ud800", "\udc00", "A\u0000\u4e2d\ud83d\ude03"};
        byte[][] expected = { {}, {0x41}, {(byte) 0xc0, (byte) 0x80},
                {0x7f, (byte) 0xc2, (byte) 0x80, (byte) 0xdf, (byte) 0xbf,
                        (byte) 0xe0, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xe4, (byte) 0xb8, (byte) 0xad},
                {(byte) 0xed, (byte) 0xa0, (byte) 0xbd, (byte) 0xed, (byte) 0xb8, (byte) 0x83},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xed, (byte) 0xb0, (byte) 0x80},
                {0x41, (byte) 0xc0, (byte) 0x80, (byte) 0xe4, (byte) 0xb8, (byte) 0xad,
                        (byte) 0xed, (byte) 0xa0, (byte) 0xbd, (byte) 0xed, (byte) 0xb8, (byte) 0x83}};
        for (int index = 0; index < values.length; index++) {
            assertArrayEquals(expected[index], ModifiedUtf8.encode(values[index]));
            assertEquals(expected[index].length, ModifiedUtf8.encodedLength(values[index]));
            assertEquals(values[index], ModifiedUtf8.decode(expected[index]));
        }
        String regionSource = "xA\u0000\u4e2d\ud83d\ude03y";
        assertArrayEquals(expected[8], ModifiedUtf8.encodeRegion(regionSource, 1, 5));
        assertArrayEquals(new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0xbd},
                ModifiedUtf8.encodeRegion(regionSource, 4, 1));
        assertThrows(StringIndexOutOfBoundsException.class,
                () -> ModifiedUtf8.encodeRegion(regionSource, Integer.MAX_VALUE, 1));
        assertThrows(StringIndexOutOfBoundsException.class,
                () -> ModifiedUtf8.encodeRegion(regionSource, 1, Integer.MAX_VALUE));

        // DataOutput's public MUTF-8 contract is an independent oracle, minus its u16 framing.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(stream)) {
            data.writeUTF(regionSource);
        }
        byte[] framed = stream.toByteArray();
        assertArrayEquals(Arrays.copyOfRange(framed, 2, framed.length), ModifiedUtf8.encode(regionSource));

        // JNI has no DataOutput.writeUTF 65535-byte frame limit.
        char[] large = new char[32768];
        byte[] largeExpected = new byte[65536];
        for (int offset = 0; offset < largeExpected.length; offset += 2) {
            largeExpected[offset] = (byte) 0xc0;
            largeExpected[offset + 1] = (byte) 0x80;
        }
        assertArrayEquals(largeExpected, ModifiedUtf8.encode(new String(large)));
        assertEquals(new String(large), ModifiedUtf8.decode(largeExpected));
    }

    @Test
    public void invalidEncodingIsRejectedWithoutReplacement() {
        byte[][] invalid = {{0}, {(byte) 0x80}, {(byte) 0xc0}, {(byte) 0xc0, (byte) 0xbf},
                {(byte) 0xc1, (byte) 0x80}, {(byte) 0xe0, (byte) 0x80, (byte) 0x80},
                {(byte) 0xe4, (byte) 0xb8}, {(byte) 0xe4, 0x41, (byte) 0xad},
                {(byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x83}};
        for (byte[] bytes : invalid)
            assertThrows(IllegalArgumentException.class, () -> ModifiedUtf8.decode(bytes));
    }
}
