package com.github.unidbg.linux.file;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.memory.MemRegion;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.UnicornConst;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class MapsFileIO extends ByteArrayFileIO implements FileIO {

    private static final Logger log = LoggerFactory.getLogger(MapsFileIO.class);

    public MapsFileIO(Emulator<?> emulator, int oflags, String path, Collection<Module> modules) {
        super(oflags, path, getMapsData(emulator, modules, null));
    }

    @SuppressWarnings("unused")
    protected MapsFileIO(Emulator<?> emulator, int oflags, String path, Collection<Module> modules, String additionContent) {
        this(oflags, path, getMapsData(emulator, modules, additionContent));
    }

    protected MapsFileIO(int oflags, String path, byte[] bytes) {
        super(oflags, path, bytes);
    }

    protected static byte[] getMapsData(Emulator<?> emulator, Collection<Module> modules, String additionContent) {
        List<MemRegion> list = new ArrayList<>(modules.size());
        for (Module module : modules) {
            list.addAll(module.getRegions());
        }
        Collections.sort(list);
        // show_vma_header_prefix sets the width 25 + sizeof(void*) * 6 - 1 and show_map_vma pads
        // the path to it with seq_pad(' '), so the path starts one column past that width.
        int labelColumn = 25 + emulator.getPointerSize() * 6 - 1;
        List<MapItem> items = new ArrayList<>();
        for (MemRegion memRegion : list) {
            items.add(new MapItem(memRegion.virtualAddress, memRegion.end, memRegion.perms, 0, "b3:19", memRegion.getName(), labelColumn));
        }
        for (MemRegion region : emulator.getSvcMemory().getRegions()) {
            items.add(new MapItem(region.begin, region.end, region.perms, 0, "00:00", region.getName(), labelColumn));
        }
        long stackSize = (long) Memory.STACK_SIZE_OF_PAGE * emulator.getPageAlign();
        items.add(new MapItem(Memory.STACK_BASE - stackSize, Memory.STACK_BASE, UnicornConst.UC_PROT_WRITE | UnicornConst.UC_PROT_READ, 0, "00:00", "[stack]", labelColumn));

        List<MapItem> mapItems = new ArrayList<>();
        for (MemoryMap memoryMap : emulator.getMemory().getMemoryMap()) {
            boolean contains = false;
            for (MapItem item : items) {
                if (Math.max(memoryMap.base, item.start) <= Math.min(memoryMap.base + memoryMap.size, item.end)) {
                    contains = true;
                    break;
                }
            }
            if (!contains) {
                // The kernel prints no path for a range the process did not name, and the name a
                // process gave an anonymous range is the one it prints.
                mapItems.add(new MapItem(memoryMap.base, memoryMap.base + memoryMap.size, memoryMap.prot,
                        memoryMap.offset, "00:00",
                        memoryMap.name == null ? "" : "[anon:" + memoryMap.name + "]", labelColumn));
            }
        }
        items.addAll(mapItems);

        StringBuilder builder = new StringBuilder();
        for (MapItem item : items) {
            builder.append(item);
        }
        if (additionContent != null) {
            builder.append(additionContent).append('\n');
        }
        if (log.isDebugEnabled()) {
            log.debug("\n{}", builder);
        }

        return builder.toString().getBytes();
    }

    private static class MapItem {
        private final long start;
        private final long end;
        private final int perms;
        private final long offset;
        private final String device;
        private final String label;
        private final int labelColumn;
        public MapItem(long start, long end, int perms, long offset, String device, String label,
                       int labelColumn) {
            this.start = start;
            this.end = end;
            this.perms = perms;
            this.offset = offset;
            this.device = device;
            this.label = label;
            this.labelColumn = labelColumn;
        }
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(String.format("%08x-%08x", start, end)).append(' ');
            if ((perms & UnicornConst.UC_PROT_READ) != 0) {
                builder.append('r');
            } else {
                builder.append('-');
            }
            if ((perms & UnicornConst.UC_PROT_WRITE) != 0) {
                builder.append('w');
            } else {
                builder.append('-');
            }
            if ((perms & UnicornConst.UC_PROT_EXEC) != 0) {
                builder.append('x');
            } else {
                builder.append('-');
            }
            builder.append("p ");
            builder.append(String.format("%08x", offset));
            builder.append(' ').append(device).append(" 0 ");
            if (!label.isEmpty()) {
                while (builder.length() < labelColumn) {
                    builder.append(' ');
                }
                builder.append(' ').append(label);
            }
            builder.append('\n');
            return builder.toString();
        }
    }

    @Override
    public int ioctl(Emulator<?> emulator, long request, long argp) {
        return 0;
    }
}
