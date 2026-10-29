package com.github.unidbg.linux.android;

import com.github.unidbg.memory.SvcMemory;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

final class HookSvcEntries {

    private final Map<SvcMemory, Map<String, Map<Long, Long>>> entries = new IdentityHashMap<>();

    long getOrCreate(SvcMemory memory, String symbol, long old, LongSupplier factory) {
        Map<Long, Long> targets = entries.computeIfAbsent(memory, ignored -> new HashMap<>())
                .computeIfAbsent(symbol, ignored -> new HashMap<>());
        Long entry = targets.get(old);
        if (entry != null) {
            return entry;
        }
        long address = factory.getAsLong();
        if (address != 0) {
            targets.put(old, address);
        }
        return address;
    }
}
