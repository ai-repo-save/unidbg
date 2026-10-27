package com.github.unidbg.linux.file;

import com.alibaba.fastjson.util.IOUtils;
import com.github.unidbg.Emulator;
import com.github.unidbg.Utils;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.NewFileIO;
import com.github.unidbg.file.linux.BaseAndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.file.linux.StatStructure;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.memory.mapping.HostMappingException;
import com.github.unidbg.memory.mapping.LinuxMappingSource;
import com.github.unidbg.memory.mapping.MappingSource;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.IO;
import com.github.unidbg.utils.Inspector;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;

public class SimpleFileIO extends BaseAndroidFileIO implements NewFileIO {

    private static final Logger log = LoggerFactory.getLogger(SimpleFileIO.class);

    protected final File file;
    protected final String path;
    private final RandomAccessFile _randomAccessFile;
    private final MappingSource mappingSource;

    private RandomAccessFile checkOpenFile() {
        return _randomAccessFile;
    }

    public SimpleFileIO(int oflags, File file, String path) {
        super(oflags);
        this.file = file;
        this.path = path;

        if (file.isDirectory()) {
            throw new IllegalArgumentException("file is directory: " + file);
        }
        if (!file.exists()) {
            throw new IllegalArgumentException("file not exists: " + file);
        }
        MappingSource source = null;
        RandomAccessFile opened = null;
        try {
            source = LinuxMappingSource.open(file.toPath(), true);
            opened = new RandomAccessFile(file, "rws");
            mappingSource = source;
            _randomAccessFile = opened;
            onFileOpened(opened);
        } catch (IOException error) {
            IOUtils.close(opened);
            IOUtils.close(source);
            if (error instanceof HostMappingException) {
                throw new LinuxErrnoException(((HostMappingException) error).getErrno(), error);
            }
            throw new IllegalStateException("Opening file resource: " + file, error);
        } catch (RuntimeException | Error failure) {
            IOUtils.close(opened);
            IOUtils.close(source);
            throw failure;
        }
    }

    void onFileOpened(RandomAccessFile randomAccessFile) throws IOException {
    }

    @Override
    public void close() {
        IOUtils.close(_randomAccessFile);
        try {
            mappingSource.close();
        } catch (IOException error) {
            throw new IllegalStateException("Closing file mapping source: " + file, error);
        }

        if (debugStream != null) {
            try {
                debugStream.flush();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public int write(byte[] data) {
        try {
            if (debugStream != null) {
                debugStream.write(data);
                debugStream.flush();
            }

            if (log.isDebugEnabled() && data.length < 0x3000) {
                Inspector.inspect(data, "write");
            }

            RandomAccessFile randomAccessFile = checkOpenFile();
            if ((oflags & IOConstants.O_APPEND) != 0) {
                randomAccessFile.seek(randomAccessFile.length());
            }
            randomAccessFile.write(data);
            return data.length;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    OutputStream debugStream;

    void setDebugStream(OutputStream stream) {
        this.debugStream = new BufferedOutputStream(stream);
    }

    @Override
    public int read(Backend backend, Pointer pointer, final int _count) {
        RandomAccessFile randomAccessFile = checkOpenFile();
        return Utils.readFile(randomAccessFile, pointer, _count);
    }

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        int st_mode;
        if (IO.STDOUT.equals(file.getName())) {
            st_mode = IO.S_IFCHR | 0x777;
        } else if(Files.isSymbolicLink(file.toPath())) {
            st_mode = IO.S_IFLNK;
        } else {
            st_mode = IO.S_IFREG;
        }
        stat.st_dev = 1;
        stat.st_mode = st_mode;
        stat.st_uid = 0;
        stat.st_gid = 0;
        try {
            stat.st_size = _randomAccessFile.length();
        } catch (IOException error) {
            throw new IllegalStateException("Reading file size: " + path, error);
        }
        stat.st_blksize = emulator.getPageAlign();
        stat.st_ino = 1;
        stat.st_blocks = ((stat.st_size + emulator.getPageAlign() - 1) / emulator.getPageAlign());
        stat.setLastModification(file.lastModified());
        stat.pack();
        return 0;
    }

    @Override
    protected byte[] getMmapData(long addr, long offset, int length) throws IOException {
        RandomAccessFile randomAccessFile = checkOpenFile();
        randomAccessFile.seek(offset);
        long remaining = randomAccessFile.length() - randomAccessFile.getFilePointer();
        ByteArrayOutputStream baos = remaining <= 0 ? new ByteArrayOutputStream() : new ByteArrayOutputStream(Math.toIntExact(Math.min(length, remaining)));
        byte[] buf = new byte[1024];
        do {
            int count = length - baos.size();
            if (count == 0) {
                break;
            }

            if (count > buf.length) {
                count = buf.length;
            }

            int read = randomAccessFile.read(buf, 0, count);
            if (read == -1) {
                break;
            }

            baos.write(buf, 0, read);
        } while (true);
        return baos.toByteArray();
    }

    @Override
    public MappingSource getMappingSource() {
        return mappingSource;
    }

    @Override
    public String toString() {
        return path;
    }

    @Override
    public int ioctl(Emulator<?> emulator, long request, long argp) {
        if (IO.STDOUT.equals(path) || IO.STDERR.equals(path)) {
            return 0;
        }

        return super.ioctl(emulator, request, argp);
    }

    @Override
    public FileIO dup2() {
        SimpleFileIO dup = new SimpleFileIO(oflags, file, path);
        dup.debugStream = debugStream;
        dup.op = op;
        dup.oflags = oflags;
        return dup;
    }

    @Override
    public int lseek(int offset, int whence) {
        try {
            RandomAccessFile randomAccessFile = checkOpenFile();
            switch (whence) {
                case SEEK_SET:
                    randomAccessFile.seek(offset);
                    return (int) randomAccessFile.getFilePointer();
                case SEEK_CUR:
                    randomAccessFile.seek(randomAccessFile.getFilePointer() + offset);
                    return (int) randomAccessFile.getFilePointer();
                case SEEK_END:
                    randomAccessFile.seek(randomAccessFile.length() + offset);
                    return (int) randomAccessFile.getFilePointer();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return super.lseek(offset, whence);
    }

    @Override
    public int llseek(long offset, Pointer result, int whence) {
        try {
            RandomAccessFile randomAccessFile = checkOpenFile();
            switch (whence) {
                case SEEK_SET:
                    randomAccessFile.seek(offset);
                    result.setLong(0, randomAccessFile.getFilePointer());
                    return 0;
                case SEEK_END:
                    randomAccessFile.seek(randomAccessFile.length() - offset);
                    result.setLong(0, randomAccessFile.getFilePointer());
                    return 0;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return super.llseek(offset, result, whence);
    }

    @Override
    public int ftruncate(int length) {
        if (length < 0 || !isWritable()) {
            return -UnixEmulator.EINVAL;
        }
        try {
            mappingSource.truncate(length);
            return 0;
        } catch (HostMappingException error) {
            return -error.getErrno();
        } catch (IOException error) {
            throw new IllegalStateException("ftruncate failed: " + file, error);
        }
    }

    @Override
    public String getPath() {
        return path;
    }
}
