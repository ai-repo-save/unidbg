package com.github.unidbg.file;

import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Immutable facts. Empty fields are unknown; zero is a known value. */
public final class FileMetadata {
    public final OptionalInt mode;
    public final OptionalInt uid;
    public final OptionalInt gid;
    public final OptionalInt nlink;
    public final OptionalInt blksize;
    public final OptionalLong dev;
    public final OptionalLong ino;
    public final OptionalLong rdev;
    public final OptionalLong size;
    public final OptionalLong blocks;
    public final Optional<FileTime> atime;
    public final Optional<FileTime> mtime;
    public final Optional<FileTime> ctime;
    public final Optional<String> linkTarget;

    private FileMetadata(Builder builder) {
        mode = builder.mode;
        uid = builder.uid;
        gid = builder.gid;
        nlink = builder.nlink;
        blksize = builder.blksize;
        dev = builder.dev;
        ino = builder.ino;
        rdev = builder.rdev;
        size = builder.size;
        blocks = builder.blocks;
        atime = builder.atime;
        mtime = builder.mtime;
        ctime = builder.ctime;
        linkTarget = builder.linkTarget;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private OptionalInt mode = OptionalInt.empty();
        private OptionalInt uid = OptionalInt.empty();
        private OptionalInt gid = OptionalInt.empty();
        private OptionalInt nlink = OptionalInt.empty();
        private OptionalInt blksize = OptionalInt.empty();
        private OptionalLong dev = OptionalLong.empty();
        private OptionalLong ino = OptionalLong.empty();
        private OptionalLong rdev = OptionalLong.empty();
        private OptionalLong size = OptionalLong.empty();
        private OptionalLong blocks = OptionalLong.empty();
        private Optional<FileTime> atime = Optional.empty();
        private Optional<FileTime> mtime = Optional.empty();
        private Optional<FileTime> ctime = Optional.empty();
        private Optional<String> linkTarget = Optional.empty();

        public Builder mode(int value) { mode = OptionalInt.of(value); return this; }
        public Builder uid(int value) { uid = OptionalInt.of(value); return this; }
        public Builder gid(int value) { gid = OptionalInt.of(value); return this; }
        public Builder nlink(int value) { nlink = OptionalInt.of(value); return this; }
        public Builder blksize(int value) { blksize = OptionalInt.of(value); return this; }
        public Builder dev(long value) { dev = OptionalLong.of(value); return this; }
        public Builder ino(long value) { ino = OptionalLong.of(value); return this; }
        public Builder rdev(long value) { rdev = OptionalLong.of(value); return this; }
        public Builder size(long value) { size = OptionalLong.of(value); return this; }
        public Builder blocks(long value) { blocks = OptionalLong.of(value); return this; }
        public Builder atime(FileTime value) { atime = Optional.of(value); return this; }
        public Builder mtime(FileTime value) { mtime = Optional.of(value); return this; }
        public Builder ctime(FileTime value) { ctime = Optional.of(value); return this; }
        public Builder linkTarget(String value) { linkTarget = Optional.of(value); return this; }
        public FileMetadata build() { return new FileMetadata(this); }
    }
}
