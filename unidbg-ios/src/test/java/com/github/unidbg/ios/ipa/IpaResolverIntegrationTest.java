package com.github.unidbg.ios.ipa;

import com.github.unidbg.file.DirectoryEntry;
import com.github.unidbg.file.FileMetadata;
import com.github.unidbg.file.FileQueryResult;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.pointer.UnidbgStructure;
import com.github.unidbg.unix.IO;
import com.sun.jna.Pointer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.*;

public class IpaResolverIntegrationTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    public static class Buffer extends UnidbgStructure {
        public byte[] bytes = new byte[4096];

        public Buffer() {
            super(new byte[4096]);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Collections.singletonList("bytes");
        }
    }

    @Test
    public void directoryOpenAndQueriesUseTheSameArchiveDirectory() throws Exception {
        Map<String, Byte> expected = new TreeMap<>();
        expected.put("item", DirectoryEntry.DirentType.DT_REG.value);
        expected.put("nested", DirectoryEntry.DirentType.DT_DIR.value);
        String appDir = "/var/containers/Bundle/Application/test/App.app";
        for (boolean explicitDirectory : new boolean[] {false, true}) {
            File ipa = temporary.newFile(explicitDirectory ? "explicit.ipa" : "implicit.ipa");
            try (JarOutputStream output = new JarOutputStream(new FileOutputStream(ipa))) {
                if (explicitDirectory) {
                    output.putNextEntry(new JarEntry("Payload/App.app/assets/"));
                    output.closeEntry();
                }
                for (String child : new String[] {"item", "nested/child"}) {
                    output.putNextEntry(new JarEntry("Payload/App.app/assets/" + child));
                    output.write(new byte[] {1, 2, 3});
                    output.closeEntry();
                }
            }
            IpaResolver resolver = new IpaResolver(appDir, ipa);
            for (String suffix : new String[] {"assets", "assets/"}) {
                String path = appDir + "/" + suffix;
                FileQueryResult<FileMetadata> metadata = resolver.queryMetadata(null, path, true);
                assertEquals(FileQueryResult.Status.PRESENT, metadata.status());
                assertEquals(IO.S_IFDIR, metadata.value.mode.getAsInt());
                FileQueryResult<List<DirectoryEntry>> listing = resolver.queryDirectory(null, path);
                assertEquals(FileQueryResult.Status.PRESENT, listing.status());
                Map<String, Byte> queried = new TreeMap<>();
                for (DirectoryEntry entry : listing.value) {
                    queried.put(entry.name, entry.type.value);
                }
                assertEquals(expected, queried);

                FileResult<DarwinFileIO> opened = resolver.resolve(null, path, 0);
                assertEquals(true, opened.isSuccess());
                try {
                    Pointer buffer = new Buffer().getPointer();
                    int size = opened.io.getdirentries64(buffer, 4096);
                    Map<String, Byte> observed = new TreeMap<>();
                    for (int offset = 0; offset < size; offset += Short.toUnsignedInt(buffer.getShort(offset + 16))) {
                        String name = buffer.getString(offset + 21);
                        if (!name.equals(".") && !name.equals("..")) {
                            observed.put(name, buffer.getByte(offset + 20));
                        }
                    }
                    assertEquals(expected, observed);
                    assertEquals(0, opened.io.getdirentries64(buffer, 4096));
                } finally {
                    opened.io.close();
                }
            }
        }
    }
}
