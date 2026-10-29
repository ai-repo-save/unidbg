package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.BaseAndroidFileIO;
import com.github.unidbg.linux.FileQuerySupport;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Supplier;

public class Stdin extends BaseAndroidFileIO implements AndroidFileIO {

    private Supplier<InputStream> input = () -> System.in;
    private Supplier<FileQueryResult<FileMetadata>> metadata = FileQueryResult::unknown;

    public Stdin(int oflags) {
        super(oflags);

        stdio = true;
    }

    /** The caller supplies facts for the same input and retains ownership of the stream. */
    public void setInput(InputStream source, Supplier<FileQueryResult<FileMetadata>> metadata) {
        this.input = () -> source;
        this.metadata = metadata;
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata() {
        return metadata.get();
    }

    @Override
    public void close() {
    }

    @Override
    public int write(byte[] data) {
        throw new AbstractMethodError(new String(data));
    }

    @Override
    public int read(Backend backend, Pointer buffer, int count) {
        try {
            byte[] data = new byte[count];
            int read = input.get().read(data, 0, count);
            if (read <= 0) {
                return 0;
            }

            buffer.write(0, Arrays.copyOf(data, read), 0, read);
            return read;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int fstat(Emulator<?> emulator, com.github.unidbg.file.linux.StatStructure stat) {
        return FileQuerySupport.writeStat(queryMetadata(), stat, "stdin");
    }

    @Override
    public FileIO dup2() {
        return this;
    }

    @Override
    public int ioctl(Emulator<?> emulator, long request, long argp) {
        return 0;
    }

    @Override
    public int getdents64(Pointer dirp, int size) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return "stdin";
    }
}
