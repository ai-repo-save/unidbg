package com.github.unidbg.linux.android.dvm.host;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

final class JvmMonitorOwnership {
    private final Map<Object, Integer> held = new IdentityHashMap<>();

    int enter(Object target) {
        Objects.requireNonNull(target, "monitor target");
        int result = JvmMonitorBridge.enter(target);
        if (result == 0) {
            held.put(target, held.getOrDefault(target, 0) + 1);
        }
        return result;
    }

    int exit(Object target) {
        Objects.requireNonNull(target, "monitor target");
        Integer depth = held.get(target);
        if (depth == null) {
            throw new IllegalMonitorStateException("Guest does not own this JNI monitor");
        }
        int result = JvmMonitorBridge.exit(target);
        if (result == 0) {
            if (depth == 1) {
                held.remove(target);
            } else {
                held.put(target, depth - 1);
            }
        }
        return result;
    }

    void releaseAll() {
        for (Map.Entry<Object, Integer> entry : held.entrySet()) {
            for (int depth = entry.getValue(); depth > 0; depth--) {
                int result = JvmMonitorBridge.exit(entry.getKey());
                if (result != 0) {
                    throw new IllegalStateException("JNI MonitorExit failed: " + result);
                }
            }
        }
        held.clear();
    }
}
