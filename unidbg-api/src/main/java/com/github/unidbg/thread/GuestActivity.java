package com.github.unidbg.thread;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which host threads run guest code right now.
 *
 * A socket timeout reports how long a peer stayed silent. In this emulator the peer may be another
 * emulator on another host thread, and its guest code runs far slower than the device's, so counting
 * the time it spends executing as silence would let the host's speed decide the guest's result: it
 * would see a timeout while the peer was in fact busy producing the answer. A waiting side therefore
 * discounts the time during which some other host thread executed guest code, and expires on the time
 * during which none did.
 */
public final class GuestActivity {
    private static final Set<Thread> DISPATCHING = ConcurrentHashMap.newKeySet();

    private GuestActivity() {
    }

    static void enter() {
        DISPATCHING.add(Thread.currentThread());
    }

    static void leave() {
        DISPATCHING.remove(Thread.currentThread());
    }

    /** Whether a host thread other than the calling one runs guest code. */
    public static boolean elsewhere() {
        Thread current = Thread.currentThread();
        for (Thread thread : DISPATCHING) {
            if (thread != current) {
                return true;
            }
        }
        return false;
    }
}
