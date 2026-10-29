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
import com.github.unidbg.ios.file.DirectoryFileIO;
import com.github.unidbg.unix.IO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class BundleResolver implements IOResolver<DarwinFileIO> {

    private final String executableBundleDir;
    private final String bundleIdentifier;

    BundleResolver(String executableBundleDir, String bundleIdentifier) {
        this.executableBundleDir = executableBundleDir;
        this.bundleIdentifier = bundleIdentifier;
    }

    @Override
    public FileResult<DarwinFileIO> resolve(Emulator<DarwinFileIO> emulator, String pathname, int oflags) {
        if (executableBundleDir.equals(pathname)) {
            List<DirectoryFileIO.DirectoryEntry> list = new ArrayList<>();
            list.add(new DirectoryFileIO.DirectoryEntry(true, "Info.plist"));
            return FileResult.<DarwinFileIO>success(new DirectoryFileIO(oflags, pathname, list.toArray(new DirectoryFileIO.DirectoryEntry[0])));
        }
        if (isInfo(pathname)) {
            return FileResult.<DarwinFileIO>success(new ByteArrayFileIO(oflags, pathname, infoBytes()));
        }
        return null;
    }

    private boolean isInfo(String path) { return (executableBundleDir + "/Info.plist").equals(path); }

    private byte[] infoBytes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("CFBundleExecutable", BundleLoader.APP_NAME);
        map.put("CFBundleIdentifier", bundleIdentifier);
        map.put("CFBundleInfoDictionaryVersion", "6.0");
        map.put("CFBundleName", BundleLoader.APP_NAME);
        map.put("CFBundleVersion", "1.0.0");
        NSDictionary root = (NSDictionary) NSDictionary.fromJavaObject(map);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try { PropertyListParser.saveAsBinary(root, output); }
        catch (IOException error) { throw new IllegalStateException("Saving bundle info", error); }
        return output.toByteArray();
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata(Emulator<DarwinFileIO> emulator, String path, boolean followLinks) {
        if (executableBundleDir.equals(path)) return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFDIR).build());
        return isInfo(path) ? FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFREG).size(infoBytes().length).build()) : null;
    }

    @Override
    public FileQueryResult<java.util.List<DirectoryEntry>> queryDirectory(Emulator<DarwinFileIO> emulator, String path) {
        if (executableBundleDir.equals(path)) return FileQueryResult.success(java.util.Collections.singletonList(new DirectoryEntry(true, "Info.plist")));
        return isInfo(path) ? FileQueryResult.failed(com.github.unidbg.unix.UnixEmulator.ENOTDIR) : null;
    }

}
