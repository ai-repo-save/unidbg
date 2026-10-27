package com.github.unidbg.linux;

import com.github.unidbg.Emulator;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.arm.ARM;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.pointer.UnidbgPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.github.unidbg.linux.AndroidElfLoader.MAP_ANONYMOUS;
import static com.github.unidbg.linux.AndroidElfLoader.MAP_PRIVATE;
import static com.github.unidbg.linux.AndroidElfLoader.MAP_SHARED;

/** Linux memory advice for the emulator's mappings. */
final class MemoryAdvice {
    private static final Logger log = LoggerFactory.getLogger(MemoryAdvice.class);

    static int madvise(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        long address = UnidbgPointer.nativeValue(context.getPointerArg(0));
        long length = emulator.is64Bit() ? context.getLongArg(1) : Integer.toUnsignedLong(context.getIntArg(1));
        int advice = context.getIntArg(2);
        int pageSize = emulator.getPageAlign();
        if (address % pageSize != 0) {
            log.debug("madvise rejected unaligned address=0x{}, length={}, advice={}", Long.toHexString(address), length, advice);
            return -UnixEmulator.EINVAL;
        }
        if (advice == 12) {
            // MADV_MERGEABLE returns EINVAL without CONFIG_KSM; this emulator does not implement KSM.
            log.debug("madvise rejected unsupported KSM address=0x{}, length={}, advice={}", Long.toHexString(address), length, advice);
            return -UnixEmulator.EINVAL;
        }
        if (advice < 0 || advice > 4) {
            throw new UnsupportedOperationException("madvise advice=" + advice + ", address=0x" + Long.toHexString(address) + ", length=" + length);
        }
        long end = address + ARM.alignSize(length, pageSize);
        List<MemoryMap> maps = new ArrayList<>(emulator.getMemory().getMemoryMap());
        maps.sort(Comparator.comparingLong(map -> map.base));
        long cursor = address;
        for (MemoryMap map : maps) {
            if (map.base > cursor || map.base + map.size <= cursor) continue;
            cursor = Math.min(end, map.base + map.size);
            if (cursor == end) break;
        }
        if (cursor != end) {
            log.debug("madvise rejected unmapped address=0x{}, length={}, advice={}", Long.toHexString(address), length, advice);
            return -UnixEmulator.ENOMEM;
        }
        if (advice == 4) {
            for (MemoryMap map : maps) {
                if (Math.max(address, map.base) >= Math.min(end, map.base + map.size)) continue;
                if (map.backing == null &&
                        (map.flags & (MAP_ANONYMOUS | MAP_PRIVATE | MAP_SHARED)) != (MAP_ANONYMOUS | MAP_PRIVATE)) {
                    throw new UnsupportedOperationException("MADV_DONTNEED requires a host-backed file or private anonymous mapping: " +
                            map + ", flags=0x" + Integer.toHexString(map.flags));
                }
            }
            // MADV_DONTNEED preserves the mapping and its protections. Kernel writes do not
            // require guest write permission; private anonymous pages become zero-filled.
            byte[] zero = new byte[pageSize];
            for (MemoryMap map : maps) {
                long start = Math.max(address, map.base);
                long stop = Math.min(end, map.base + map.size);
                if (start >= stop) continue;
                if (map.backing != null) {
                    int result = emulator.getMemory().discardFileMapping(start, (int) (stop - start));
                    log.debug("madvise file discard address=0x{}, length={}, result={}", Long.toHexString(start), stop - start, result);
                    if (result != 0) return result;
                } else {
                    for (long page = start; page < stop; page += pageSize) emulator.getBackend().mem_write(page, zero);
                }
            }
        }
        log.debug("madvise completed address=0x{}, length={}, advice={}", Long.toHexString(address), length, advice);
        return 0;
    }
}
