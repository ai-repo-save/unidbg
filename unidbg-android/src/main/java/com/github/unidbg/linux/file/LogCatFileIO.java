package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.file.linux.LinuxFileSystem;
import com.github.unidbg.linux.android.LogCatHandler;
import com.github.unidbg.linux.android.LogCatLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class LogCatFileIO extends SimpleFileIO {

    private static final Logger log = LoggerFactory.getLogger(LogCatFileIO.class);

    public static final String LOG_PATH_PREFIX = "/dev/log/";

    private final Emulator<?> emulator;
    private final String type;

    public LogCatFileIO(Emulator<?> emulator, int oflags, File file, String path) {
        super(oflags, file, path);
        this.emulator = emulator;
        this.type = path.substring(LOG_PATH_PREFIX.length());
        byteArrayOutputStream = new ByteArrayOutputStream();

        if (log.isDebugEnabled()) {
            setDebugStream(System.out);
        }
    }

    @Override
    void onFileOpened(RandomAccessFile randomAccessFile) throws IOException {
        super.onFileOpened(randomAccessFile);

        getMappingSource().truncate(0);
    }

    private final ByteArrayOutputStream byteArrayOutputStream;

    private LogCatFileIO(LogCatFileIO original) {
        super(original);
        emulator = original.emulator;
        type = original.type;
        byteArrayOutputStream = original.byteArrayOutputStream;
    }

    @Override
    public FileIO dup2() { return new LogCatFileIO(this); }

    @Override
    public FileIO reopen(int flags) {
        throw new UnsupportedOperationException("Reopening a log capture is not a regular-file open");
    }

    @Override
    public int write(byte[] data) {
        if (!isWritable()) return -UnixEmulator.EBADF;
        try {
            byteArrayOutputStream.write(data);

            if (byteArrayOutputStream.size() <= 1) {
                return data.length;
            }

            int tagIndex = -1;
            int bodyIndex = -1;
            byte[] body = byteArrayOutputStream.toByteArray();
            for (int i = 1; i < body.length; i++) {
                if (body[i] != 0) {
                    continue;
                }

                if (tagIndex == -1) {
                    tagIndex = i;
                    continue;
                }

                bodyIndex = i;
                break;
            }

            if (tagIndex != -1 && bodyIndex != -1) {
                byteArrayOutputStream.reset();

                int level = body[0] & 0xff;
                String tag = new String(body, 1, tagIndex - 1);
                String text = new String(body, tagIndex + 1, bodyIndex - tagIndex - 1);
                LogCatLevel value = LogCatLevel.valueOf(level);
                super.write(String.format("%s/%s: %s\n", value, tag, text).getBytes());

                LinuxFileSystem fileSystem = (LinuxFileSystem) emulator.getFileSystem();
                LogCatHandler handler = fileSystem.getLogCatHandler();
                if (handler != null) {
                    handler.handleLog(type, value, tag, text);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return data.length;
    }

}
