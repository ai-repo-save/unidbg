package com.github.unidbg.file;

public final class DirectoryEntry {
    public enum DirentType {
        DT_UNKNOWN(0), DT_FIFO(1), DT_CHR(2), DT_DIR(4), DT_BLK(6), DT_REG(8),
        DT_LNK(10), DT_SOCK(12), DT_WHT(14);

        public final byte value;
        DirentType(int value) { this.value = (byte) value; }
    }

    public final DirentType type;
    public final String name;

    public DirectoryEntry(boolean isFile, String name) {
        this(isFile ? DirentType.DT_REG : DirentType.DT_DIR, name);
    }

    public DirectoryEntry(DirentType type, String name) {
        this.type = type;
        this.name = name;
    }
}
