package com.github.unidbg.linux.file;

import com.github.unidbg.file.FileIO;
import com.github.unidbg.memory.mapping.HostMappingException;
import com.github.unidbg.file.StdoutCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

public class Stdout extends SimpleFileIO {

    private static final Logger log = LoggerFactory.getLogger(Stdout.class);

    private final boolean err;
    private final PrintStream out;
    private final StdoutCallback callback;

    public Stdout(int oflags, File file, String path, boolean err, StdoutCallback callback) {
        super(oflags, file, path);
        this.callback = callback;

        this.err = err;
        out = err ? System.err : System.out;

        if (log.isDebugEnabled()) {
            setDebugStream(err ? System.err : System.out);
        }

        stdio = true;
    }

    private boolean outputInitialized;

    @Override
    public int write(byte[] data) {
        try {
            if (!outputInitialized) {
                getMappingSource().truncate(0);
                outputInitialized = true;
            }
            boolean enablePrint = true;
            if (callback != null) {
                enablePrint = callback.notifyOut(data, err);
            }
            if (enablePrint && log.isWarnEnabled()) {
                out.write(data);
                out.flush();
            }

            return super.write(data);
        } catch (HostMappingException error) {
            return -error.getErrno();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public FileIO dup2() {
        Stdout dup = new Stdout(0, file, path, err, callback);
        dup.debugStream = debugStream;
        dup.op = op;
        dup.oflags = oflags;
        return dup;
    }
}
