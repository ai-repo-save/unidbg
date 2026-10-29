package com.github.unidbg.ios.ipa;

import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListParser;
import com.github.unidbg.Emulator;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.ios.file.ByteArrayFileIO;
import com.github.unidbg.unix.IO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

public class NSUserDefaultsResolver implements IOResolver<DarwinFileIO> {

    private final String bundleIdentifier;
    private final Map<String, Object> map;

    public NSUserDefaultsResolver(String bundleIdentifier, Map<String, Object> map) {
        this.bundleIdentifier = bundleIdentifier;
        this.map = map;
    }

    @Override
    public FileResult<DarwinFileIO> resolve(Emulator<DarwinFileIO> emulator, String pathname, int oflags) {
        return supplies(pathname) ? FileResult.<DarwinFileIO>success(new ByteArrayFileIO(oflags, pathname, preferenceBytes())) : null;
    }

    private boolean supplies(String path) {
        return path.endsWith("/Library/Preferences/" + bundleIdentifier + ".plist");
    }

    private byte[] preferenceBytes() {
        NSDictionary root = (NSDictionary) NSDictionary.fromJavaObject(map);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try { PropertyListParser.saveAsBinary(root, output); }
        catch (IOException error) { throw new IllegalStateException("Saving preferences", error); }
        return output.toByteArray();
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata(Emulator<DarwinFileIO> emulator, String path, boolean followLinks) {
        return supplies(path) ? FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFREG).size(preferenceBytes().length).build()) : null;
    }

    @Override
    public FileQueryResult<java.util.List<DirectoryEntry>> queryDirectory(Emulator<DarwinFileIO> emulator, String path) {
        return supplies(path) ? FileQueryResult.failed(com.github.unidbg.unix.UnixEmulator.ENOTDIR) : null;
    }

}
