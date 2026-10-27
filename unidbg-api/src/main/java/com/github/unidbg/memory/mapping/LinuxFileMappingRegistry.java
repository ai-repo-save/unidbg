package com.github.unidbg.memory.mapping;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class LinuxFileMappingRegistry {

    static final Object LOCK = new Object();
    private static final Map<Key, Set<LinuxMappedFileRegion>> REGIONS = new HashMap<>();

    static final class Key {
        final long device, inode;

        Key(long device, long inode) {
            this.device = device;
            this.inode = inode;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return device == key.device && inode == key.inode;
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(device) + Long.hashCode(inode);
        }
    }

    static void register(Key key, LinuxMappedFileRegion region) {
        REGIONS.computeIfAbsent(key, ignored -> new HashSet<>()).add(region);
    }

    static void unregister(Key key, LinuxMappedFileRegion region) {
        Set<LinuxMappedFileRegion> regions = REGIONS.get(key);
        regions.remove(region);
        if (regions.isEmpty()) {
            REGIONS.remove(key);
        }
    }

    static void checkTruncate(Key key, long size) {
        Set<LinuxMappedFileRegion> regions = REGIONS.get(key);
        if (regions == null) {
            return;
        }
        for (LinuxMappedFileRegion region : regions) {
            if (size == 0 || region.lastFilePage() > (size - 1) / LinuxMappingCalls.PAGE_SIZE) {
                throw new UnsupportedOperationException("Truncating mapped pages requires guest SIGBUS handling: " +
                        "device=" + key.device + ", inode=" + key.inode + ", length=" + size);
            }
        }
    }

    private LinuxFileMappingRegistry() {
    }
}
