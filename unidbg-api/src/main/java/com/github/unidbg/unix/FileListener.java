package com.github.unidbg.unix;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.FileIO;

public interface FileListener {

    /** Receives the final open return value; errno is zero on success, not a TLS snapshot. */
    default void onOpenResult(Emulator<?> emulator, String pathname, int flags, int result, int errno) {
    }

    void onOpenSuccess(Emulator<?> emulator, String pathname, FileIO io);

    void onRead(Emulator<?> emulator, String pathname, byte[] bytes);
    void onWrite(Emulator<?> emulator, String pathname, byte[] bytes);

    void onClose(Emulator<?> emulator, FileIO io);

}
