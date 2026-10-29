package com.github.unidbg.ios.ipa;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.HostFileFacts;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.ios.file.ByteArrayFileIO;
import com.github.unidbg.ios.file.DirectoryFileIO;
import com.github.unidbg.ios.file.JarEntryFileIO;
import com.github.unidbg.unix.IO;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

class IpaResolver implements IOResolver<DarwinFileIO> {

    private static final Logger log = LoggerFactory.getLogger(IpaResolver.class);

    private final String appDir;
    private final File ipa;
    private final String randomDir;

    IpaResolver(String appDir, File ipa) {
        this.appDir = FilenameUtils.normalize(appDir, true);
        this.ipa = ipa;
        this.randomDir = FilenameUtils.normalize(new File(appDir).getParentFile().getPath(), true);
    }

    @Override
    public FileResult<DarwinFileIO> resolve(Emulator<DarwinFileIO> emulator, String pathname, int oflags) {
        if ((randomDir + "/StoreKit/receipt").equals(pathname)) {
            return FileResult.success(new ByteArrayFileIO(oflags, pathname, new byte[0]));
        }

        pathname = FilenameUtils.normalize(pathname, true);
        String path = archivePath(pathname);
        if (path != null) {
            try (JarFile jarFile = new JarFile(ipa)) {
                JarEntry entry = findEntry(jarFile, path);
                if (entry == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Resolve appDir={}, path={}", appDir, path);
                    }
                    return null;
                }

                if (entry.isDirectory()) {
                    return FileResult.success(createDirectoryFileIO(entry.getName(), pathname, oflags));
                } else {
                    return FileResult.success(new JarEntryFileIO(oflags, pathname, ipa, entry));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        return null;
    }

    private DarwinFileIO createDirectoryFileIO(String dirEntry, String pathname, int oflags) throws IOException {
        List<DirectoryFileIO.DirectoryEntry> list = new ArrayList<>();
        list.add(new DirectoryFileIO.DirectoryEntry(false, "."));
        list.add(new DirectoryFileIO.DirectoryEntry(false, ".."));
        Set<String> dirSet = new HashSet<>();
        try (JarFile jarFile = new JarFile(ipa)) {
            Enumeration<JarEntry> enumeration = jarFile.entries();
            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();
                if (entry.getName().startsWith(dirEntry)) {
                    String subName = entry.getName().substring(dirEntry.length());
                    if (subName.isEmpty()) {
                        continue;
                    }
                    int index = subName.indexOf('/');
                    if (index == -1) { // file
                        list.add(new DirectoryFileIO.DirectoryEntry(true, subName));
                    } else {
                        int endIndex = subName.indexOf('/', index + 1);
                        if (endIndex == -1 || endIndex == subName.length() - 1) { // dir
                            String dir = subName.substring(0, index);
                            if (dirSet.add(dir)) {
                                list.add(new DirectoryFileIO.DirectoryEntry(false, dir));
                            }
                        }
                    }
                }
            }
        }
        return new DirectoryFileIO(oflags, pathname, ipa.getParentFile(), list.toArray(new DirectoryFileIO.DirectoryEntry[0]));
    }

    private String archivePath(String pathname) {
        pathname = FilenameUtils.normalize(pathname, true);
        return pathname.startsWith(appDir) ? IpaLoader.PAYLOAD_PREFIX + pathname.substring(randomDir.length()) : null;
    }

    private JarEntry findEntry(JarFile jar, String path) {
        JarEntry entry = jar.getJarEntry(path);
        if (entry == null) entry = jar.getJarEntry(path + "/");
        if (entry != null) return entry;
        Enumeration<JarEntry> iterator = jar.entries();
        while (iterator.hasMoreElements()) {
            if (iterator.nextElement().getName().startsWith(path + "/")) return new JarEntry(path + "/");
        }
        return null;
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata(Emulator<DarwinFileIO> emulator, String path, boolean followLinks) {
        if ((randomDir + "/StoreKit/receipt").equals(path)) return FileQueryResult.success(FileMetadata.builder().mode(IO.S_IFREG).size(0).build());
        String archive = archivePath(path);
        if (archive == null) return null;
        try (JarFile jar = new JarFile(ipa)) {
            JarEntry entry = findEntry(jar, archive);
            if (entry == null) return null;
            FileMetadata.Builder facts = FileMetadata.builder().mode(entry.isDirectory() ? IO.S_IFDIR : IO.S_IFREG);
            if (entry.getSize() >= 0) facts.size(entry.getSize());
            if (entry.getTime() >= 0) facts.mtime(java.nio.file.attribute.FileTime.fromMillis(entry.getTime()));
            return FileQueryResult.success(facts.build());
        } catch (IOException error) { throw new IllegalStateException("Reading IPA facts: " + path, error); }
    }

    @Override
    public FileQueryResult<List<DirectoryEntry>> queryDirectory(Emulator<DarwinFileIO> emulator, String path) {
        if ((randomDir + "/StoreKit/receipt").equals(path)) return FileQueryResult.failed(com.github.unidbg.unix.UnixEmulator.ENOTDIR);
        String archive = archivePath(path);
        if (archive == null) return null;
        try (JarFile jar = new JarFile(ipa)) {
            JarEntry entry = findEntry(jar, archive);
            if (entry == null) return null;
            if (!entry.isDirectory()) return FileQueryResult.failed(com.github.unidbg.unix.UnixEmulator.ENOTDIR);
            return FileQueryResult.success(HostFileFacts.archiveDirectory(jar, entry.getName()));
        } catch (IOException error) { throw new IllegalStateException("Reading IPA directory facts: " + path, error); }
    }

}
