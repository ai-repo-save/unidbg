package com.github.unidbg.linux.android.dvm;

import org.junit.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.*;

/** JNI Functions: reference kinds, identity, local frames and independent roots. */
public class JniReferenceOwnerIntegrationTest {
    @Test
    public void referenceFramesPreserveSharedResourcesUntilTheirLastRoot() throws Exception {
        Path path = Files.createTempFile("jni-reference-owner-", ".bin");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
             JniReferences references = new JniReferences()) {
            DvmObject<?> object = new DvmObject<FileChannel>(null, channel) {
                @Override
                protected void releaseAdditionalMemory() {
                    try {
                        getValue().close();
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                }
            };
            JniLocalFrames first = new JniLocalFrames(references, 101);
            JniLocalFrames second = new JniLocalFrames(references, 102);
            int local = first.newLocal(object);
            int global = references.newGlobal(object);
            int weak = references.newWeakGlobal(object);
            assertArrayEquals(new int[] {1, 2, 3}, new int[] {
                    references.referenceType(local, first), references.referenceType(global, first),
                    references.referenceType(weak, first)});
            assertEquals(true, references.isSameObject(local, global, first));
            assertEquals(true, references.isSameObject(local, weak, first));
            assertEquals(true, references.isSameObject(0, 0, first));
            assertEquals(false, references.isSameObject(local, 0, first));
            int distinct = first.newLocal(new DvmObject<>(null, channel));
            assertEquals(false, references.isSameObject(local, distinct, first));
            assertEquals(0, first.ensureCapacity(16));
            assertEquals(0, first.push(16));
            int escaped = first.pop(first.newLocal(object));
            assertEquals(1, references.referenceType(escaped, first));
            assertSame(channel, first.lookup(escaped).getValue());
            second.newLocal(references.lookup(global, second));
            try (JniReferenceRoot lease = references.retain(object)) {
                first.clear();
                assertEquals(true, channel.isOpen());
                references.deleteGlobal(global);
                assertEquals(true, channel.isOpen());
                second.clear();
                assertEquals(true, channel.isOpen());
                assertSame(channel, lease.object().getValue());
                references.deleteWeakGlobal(weak);
            }
            assertEquals(false, channel.isOpen());
        } finally {
            Files.delete(path);
        }
    }

    @Test
    public void hostIdentityUsesReferenceEqualityAcrossWrappers() {
        try (JniReferences references = new JniReferences(DvmObject::getValue,
                key -> new DvmObject<>(null, key), DvmObject::onDeleteRef)) {
            JniLocalFrames locals = new JniLocalFrames(references, 103);
            Object host = new String("identity");
            DvmObject<?> first = new DvmObject<>(null, host);
            DvmObject<?> alias = new DvmObject<>(null, host);
            DvmObject<?> distinct = new DvmObject<>(null, new String("identity"));
            int local = locals.newLocal(first);
            int global = references.newGlobal(alias);
            int weak = references.newWeakGlobal(alias);
            int other = locals.newLocal(distinct);
            assertEquals(true, references.isSameObject(local, global, locals));
            assertEquals(true, references.isSameObject(local, weak, locals));
            assertEquals(false, references.isSameObject(local, other, locals));
            assertSame(first, references.lookup(global, locals));
            locals.clear();
            assertSame(first, references.lookup(global, locals));
            references.deleteGlobal(global);
            // host is still strongly held by this Java scope; weak lookup must preserve it.
            assertSame(host, references.lookup(weak, locals).getValue());
            references.deleteWeakGlobal(weak);
        }
    }
}
