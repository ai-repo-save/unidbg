package net.fornwall.jelf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/** Package internal class used for parsing ELF files. */
class ElfParser implements ElfDataIn {

	private static final Logger log = LoggerFactory.getLogger(ElfParser.class);

	final ElfFile elfFile;
	private final ByteBuffer fsFile;
	private boolean overflowLogged;
	private long loggedOverflowOffset = Long.MIN_VALUE;

	ElfParser(ElfFile elfFile, ByteBuffer fsFile) {
		this.elfFile = elfFile;
		this.fsFile = fsFile;
	}

	void seek(long offset) {
		int capacity = fsFile.capacity();
		int position = (int) offset;
		if (position < 0 || position > capacity) {
			if (offset != loggedOverflowOffset) {
				log.warn("event=elf_offset_clamped offset={} file_length={}", offset, capacity);
				loggedOverflowOffset = offset;
			}
			overflowLogged = true;
			position = position < 0 ? 0 : capacity;
		} else {
			overflowLogged = false;
			loggedOverflowOffset = Long.MIN_VALUE;
		}
		fsFile.position(position);
	}

	int getCapacity() {
		return fsFile.capacity();
	}

	/**
	 * Signed byte utility functions used for converting from big-endian (MSB) to little-endian (LSB).
	 */
	short byteSwap(short arg) {
		return Short.reverseBytes(arg);
	}

	int byteSwap(int arg) {
		return Integer.reverseBytes(arg);
	}

	long byteSwap(long arg) {
		return Long.reverseBytes(arg);
	}

	@Override
	public short readUnsignedByte() {
		if (!fsFile.hasRemaining()) {
			if (!overflowLogged) {
				log.warn("event=elf_read_truncated offset={} file_length={}", fsFile.position(), fsFile.capacity());
				overflowLogged = true;
			}
			return 0;
		}
		int val = fsFile.get() & 0xff;
		return (short) val;
	}

	@Override
	public short readShort() throws ElfException {
		int ch1 = readUnsignedByte();
		int ch2 = readUnsignedByte();
		short val = (short) ((ch1 << 8) + (ch2));
		if (elfFile.encoding == ElfFile.DATA_LSB) val = byteSwap(val);
		return val;
	}

	@Override
	public int readInt() throws ElfException {
		int ch1 = readUnsignedByte();
		int ch2 = readUnsignedByte();
		int ch3 = readUnsignedByte();
		int ch4 = readUnsignedByte();
		int val = ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4));

		if (elfFile.encoding == ElfFile.DATA_LSB) {
			val = byteSwap(val);
		}
		return val;
	}

	@Override
	public long readLong() {
		int ch1 = readUnsignedByte();
		int ch2 = readUnsignedByte();
		int ch3 = readUnsignedByte();
		int ch4 = readUnsignedByte();
		int val1 = ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4));
		int ch5 = readUnsignedByte();
		int ch6 = readUnsignedByte();
		int ch7 = readUnsignedByte();
		int ch8 = readUnsignedByte();
		int val2 = ((ch5 << 24) + (ch6 << 16) + (ch7 << 8) + (ch8));

		long val = ((long) (val1) << 32) + (val2 & 0xFFFFFFFFL);
		if (elfFile.encoding == ElfFile.DATA_LSB) {
			val = byteSwap(val);
		}
		return val;
	}

	/** Read four-byte int or eight-byte long depending on if {@link ElfFile#objectSize}. */
	long readIntOrLong() {
		return elfFile.objectSize == ElfFile.CLASS_32 ? readInt() : readLong();
	}

	/** Returns a big-endian unsigned representation of the int. */
	@SuppressWarnings("unused")
	long unsignedByte(int arg) {
		long val;
		if (arg >= 0) {
			val = arg;
		} else {
			val = (unsignedByte((short) (arg >>> 16)) << 16) | ((short) arg);
		}
		return val;
	}

	int read(byte[] data) {
		fsFile.get(data);
		return data.length;
	}

	ByteBuffer readBuffer(int length) {
		int limit = fsFile.limit();
		try {
			int capacity = fsFile.capacity();
			int position = fsFile.position();
			int end = position + length;
			if (end > capacity) {
				log.warn("event=elf_read_truncated requested_length={} offset={} file_length={}", length, position, capacity);
				overflowLogged = true;
				end = capacity;
			}
			fsFile.limit(end);
			return fsFile.slice();
		} finally {
			fsFile.limit(limit);
		}
	}

}
