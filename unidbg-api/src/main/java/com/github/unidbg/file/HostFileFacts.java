package com.github.unidbg.file;

import com.github.unidbg.memory.mapping.LinuxFileMetadata;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.utils.ResourceUtils;
import com.sun.jna.Platform;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class HostFileFacts {
    public static FileQueryResult<FileMetadata> metadata(File file, boolean followLinks) {
        if (Platform.isLinux() && Platform.isIntel() && Platform.is64Bit()) {
            return LinuxFileMetadata.query(file.toPath(), followLinks);
        }
        LinkOption[] options = followLinks ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
        try {
            FileMetadata.Builder facts = FileMetadata.builder();
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("unix")) {
                Map<String, Object> attributes = Files.readAttributes(file.toPath(), "unix:*", options);
                facts.mode(((Number) attributes.get("mode")).intValue()).uid(((Number) attributes.get("uid")).intValue())
                        .gid(((Number) attributes.get("gid")).intValue()).nlink(((Number) attributes.get("nlink")).intValue())
                        .dev(((Number) attributes.get("dev")).longValue()).ino(((Number) attributes.get("ino")).longValue())
                        .rdev(((Number) attributes.get("rdev")).longValue()).size(((Number) attributes.get("size")).longValue())
                        .atime((FileTime) attributes.get("lastAccessTime")).mtime((FileTime) attributes.get("lastModifiedTime"))
                        .ctime((FileTime) attributes.get("ctime"));
            } else {
                BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class, options);
                facts.size(attributes.size()).atime(attributes.lastAccessTime()).mtime(attributes.lastModifiedTime());
            }
            return FileQueryResult.success(facts.build());
        } catch (NoSuchFileException error) {
            return FileQueryResult.failed(UnixEmulator.ENOENT);
        } catch (NotDirectoryException error) {
            return FileQueryResult.failed(UnixEmulator.ENOTDIR);
        } catch (AccessDeniedException error) {
            return FileQueryResult.failed(UnixEmulator.EACCES);
        } catch (IOException error) {
            throw new IllegalStateException("Reading file facts: " + file, error);
        }
    }

    public static FileQueryResult<List<DirectoryEntry>> directory(File file) {
        List<DirectoryEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(file.toPath())) {
            for (Path child : stream) {
                if (new UnidbgFileFilter().accept(child.toFile())) {
                    BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    DirectoryEntry.DirentType type = attributes.isSymbolicLink() ? DirectoryEntry.DirentType.DT_LNK
                            : attributes.isDirectory() ? DirectoryEntry.DirentType.DT_DIR
                            : attributes.isRegularFile() ? DirectoryEntry.DirentType.DT_REG : DirectoryEntry.DirentType.DT_UNKNOWN;
                    entries.add(new DirectoryEntry(type, child.getFileName().toString()));
                }
            }
            entries.sort(java.util.Comparator.comparing(entry -> entry.name));
            return FileQueryResult.success(Collections.unmodifiableList(entries));
        } catch (NoSuchFileException error) {
            return FileQueryResult.failed(UnixEmulator.ENOENT);
        } catch (NotDirectoryException error) {
            return FileQueryResult.failed(UnixEmulator.ENOTDIR);
        } catch (AccessDeniedException error) {
            return FileQueryResult.failed(UnixEmulator.EACCES);
        } catch (IOException error) {
            throw new IllegalStateException("Reading directory facts: " + file, error);
        }
    }

    public static FileQueryResult<FileMetadata> metadata(URL url, boolean followLinks) {
        File file = ResourceUtils.toFile(url);
        if (file != null) return metadata(file, followLinks);
        try {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                JarEntry entry = jar.getJarEntry(connection.getEntryName());
                FileMetadata.Builder facts = FileMetadata.builder().mode(entry.isDirectory() ? IO.S_IFDIR : IO.S_IFREG);
                if (entry.getSize() >= 0) facts.size(entry.getSize());
                if (entry.getTime() >= 0) facts.mtime(FileTime.fromMillis(entry.getTime()));
                return FileQueryResult.success(facts.build());
            }
        } catch (IOException error) {
            throw new IllegalStateException("Reading resource facts: " + url, error);
        }
    }

    public static FileQueryResult<List<DirectoryEntry>> directory(URL url) {
        File file = ResourceUtils.toFile(url);
        if (file != null) return directory(file);
        try {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                JarEntry parent = jar.getJarEntry(connection.getEntryName());
                if (!parent.isDirectory()) return FileQueryResult.failed(UnixEmulator.ENOTDIR);
                return FileQueryResult.success(archiveDirectory(jar, parent.getName()));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Reading resource directory: " + url, error);
        }
    }

    public static List<DirectoryEntry> archiveDirectory(JarFile jar, String prefix) {
        java.util.Map<String, DirectoryEntry> entries = new java.util.TreeMap<>();
        Enumeration<JarEntry> iterator = jar.entries();
        while (iterator.hasMoreElements()) {
            JarEntry entry = iterator.nextElement();
            if (!entry.getName().startsWith(prefix)) continue;
            String child = entry.getName().substring(prefix.length());
            if (child.isEmpty()) continue;
            int slash = child.indexOf('/');
            String name = slash < 0 ? child : child.substring(0, slash);
            entries.put(name, new DirectoryEntry(slash < 0 && !entry.isDirectory(), name));
        }
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    private HostFileFacts() { }
}
