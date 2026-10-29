package com.github.unidbg.linux.android;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.LibraryResolver;
import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.FileSystem;
import com.github.unidbg.file.HostFileFacts;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.hook.InlineHook;
import com.github.unidbg.linux.file.DirectoryFileIO;
import com.github.unidbg.linux.file.LogCatFileIO;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.linux.thread.ThreadJoin19;
import com.github.unidbg.linux.thread.ThreadJoin23;
import com.github.unidbg.spi.LibraryFile;
import com.github.unidbg.spi.SyscallHandler;
import com.github.unidbg.unix.ThreadJoinVisitor;
import com.github.unidbg.utils.ResourceUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class AndroidResolver implements LibraryResolver, IOResolver<AndroidFileIO> {

    private static final Logger log = LoggerFactory.getLogger(AndroidResolver.class);

    private final int sdk;
    private final List<String> needed;

    public AndroidResolver(int sdk, String... needed) {
        this.sdk = sdk;
        this.needed = needed == null ? null : Arrays.asList(needed);
    }

    public void patchThread(Emulator<?> emulator, InlineHook inlineHook, ThreadJoinVisitor visitor) {
        switch (sdk) {
            case 19:
                ThreadJoin19.patch(emulator, inlineHook, visitor);
                break;
            case 23:
                ThreadJoin23.patch(emulator, inlineHook, visitor);
                break;
            default:
                throw new UnsupportedOperationException();
        }
    }

    public int getSdk() {
        return sdk;
    }

    @Override
    public LibraryFile resolveLibrary(Emulator<?> emulator, String libraryName) {
        if (needed == null) {
            return null;
        }

        if (!needed.isEmpty() && !needed.contains(libraryName)) {
            return null;
        }

        return resolveLibrary(emulator, libraryName, sdk, getClass());
    }

    static LibraryFile resolveLibrary(Emulator<?> emulator, String libraryName, int sdk) {
        return resolveLibrary(emulator, libraryName, sdk, AndroidResolver.class);
    }

    protected static LibraryFile resolveLibrary(Emulator<?> emulator, String libraryName, int sdk, Class<?> resClass) {
        final String lib = emulator.is32Bit() ? "lib" : "lib64";
        String name = "/android/sdk" + sdk + "/" + lib + "/" + libraryName.replace('+', 'p');
        URL url = resClass.getResource(name);
        if (url != null) {
            return new URLibraryFile(url, libraryName, sdk, emulator.is64Bit());
        }
        return null;
    }

    @Override
    public FileResult<AndroidFileIO> resolve(Emulator<AndroidFileIO> emulator, String path, int oflags) {
        FileSystem<AndroidFileIO> fileSystem = emulator.getFileSystem();
        File rootDir = fileSystem.getRootDir();
        if (path.startsWith(LogCatFileIO.LOG_PATH_PREFIX)) {
            try {
                File log = new File(rootDir, path);
                File logDir = log.getParentFile();
                if (!logDir.exists() && !logDir.mkdirs()) {
                    throw new IOException("mkdirs failed: " + logDir);
                }
                if (!log.exists() && !log.createNewFile()) {
                    throw new IOException("create new file failed: " + log);
                }
                return FileResult.<AndroidFileIO>success(new LogCatFileIO(emulator, oflags, log, path));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        if (".".equals(path)) {
            return FileResult.success(createFileIO(fileSystem.createWorkDir(), path, oflags));
        }

        URL url = resource(path);
        if (url != null) {
            return FileResult.fallback(createFileIO(url, fileSystem, path, oflags));
        }

        return null;
    }

    private URL resource(String path) {
        return getClass().getResource(FilenameUtils.normalize("/android/sdk" + sdk + "/" + path, true));
    }

    @Override
    public FileQueryResult<FileMetadata> queryMetadata(Emulator<AndroidFileIO> emulator, String path, boolean followLinks) {
        if (path.startsWith(LogCatFileIO.LOG_PATH_PREFIX)) return null;
        if (".".equals(path)) {
            File work = new File(emulator.getFileSystem().getRootDir(), FileSystem.DEFAULT_WORK_DIR);
            return work.exists() ? HostFileFacts.metadata(work, followLinks)
                    : FileQueryResult.success(FileMetadata.builder().mode(com.github.unidbg.unix.IO.S_IFDIR).build());
        }
        URL url = resource(path);
        if (url == null) return null;
        FileQueryResult<FileMetadata> result = HostFileFacts.metadata(url, followLinks);
        return result.isSuccess() ? FileQueryResult.fallback(result.value) : result;
    }

    @Override
    public FileQueryResult<List<DirectoryEntry>> queryDirectory(Emulator<AndroidFileIO> emulator, String path) {
        if (path.startsWith(LogCatFileIO.LOG_PATH_PREFIX)) return null;
        if (".".equals(path)) {
            File work = new File(emulator.getFileSystem().getRootDir(), FileSystem.DEFAULT_WORK_DIR);
            return work.exists() ? HostFileFacts.directory(work) : FileQueryResult.success(java.util.Collections.emptyList());
        }
        URL url = resource(path);
        if (url == null) return null;
        FileQueryResult<List<DirectoryEntry>> result = HostFileFacts.directory(url);
        return result.isSuccess() ? FileQueryResult.fallback(result.value) : result;
    }

    private AndroidFileIO createFileIO(URL url, FileSystem<AndroidFileIO> fileSystem, String pathname, int oflags) {
        File file = ResourceUtils.toFile(url);
        if (file != null) {
            return createFileIO(file, pathname, oflags);
        }

        try {
            URLConnection connection = url.openConnection();
            try (InputStream inputStream = connection.getInputStream()) {
                if (connection instanceof JarURLConnection) {
                    JarURLConnection jarURLConnection = (JarURLConnection) connection;
                    JarFile jarFile = jarURLConnection.getJarFile();
                    JarEntry entry = jarURLConnection.getJarEntry();
                    if (entry.isDirectory()) {
                        Enumeration<JarEntry> entryEnumeration = jarFile.entries();
                        List<DirectoryEntry> list = new ArrayList<>();
                        while (entryEnumeration.hasMoreElements()) {
                            JarEntry check = entryEnumeration.nextElement();
                            if (entry.getName().equals(check.getName())) {
                                continue;
                            }
                            if (check.getName().startsWith(entry.getName())) {
                                boolean isDir = check.isDirectory();
                                String sub = check.getName().substring(entry.getName().length());
                                if (isDir) {
                                    sub = sub.substring(0, sub.length() - 1);
                                }
                                if (!sub.contains("/")) {
                                    list.add(new DirectoryEntry(true, sub));
                                }
                            }
                        }
                        return new DirectoryFileIO(oflags, pathname, list.toArray(new DirectoryEntry[0]));
                    } else {
                        File backing = new File(fileSystem.createWorkDir(), entry.getName());
                        if (!backing.exists()) {
                            FileUtils.copyInputStreamToFile(inputStream, backing);
                            log.debug("event=classpath_resource_materialized resource={} file={}", url, backing);
                        }
                        return createFileIO(backing, pathname, oflags);
                    }
                } else {
                    throw new IllegalStateException(connection.getClass().getName());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(pathname, e);
        }
    }

    private AndroidFileIO createFileIO(File file, String pathname, int oflags) {
        if (file.canRead()) {
            return file.isDirectory() ? new DirectoryFileIO(oflags, pathname) : new SimpleFileIO(oflags, file, pathname);
        }

        return null;
    }

    @Override
    public void onSetToLoader(Emulator<?> emulator) {
        AndroidEmulator androidEmulator = (AndroidEmulator) emulator;
        SyscallHandler<AndroidFileIO> syscallHandler = androidEmulator.getSyscallHandler();
        syscallHandler.addIOResolver(this);
    }

}
