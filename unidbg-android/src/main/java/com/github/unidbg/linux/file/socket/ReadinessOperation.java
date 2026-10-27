package com.github.unidbg.linux.file.socket;

import com.github.unidbg.Emulator;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.thread.RunnableTask;
import com.github.unidbg.thread.ThreadContextSwitchException;
import com.github.unidbg.thread.Waiter;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import static com.github.unidbg.linux.file.socket.LinuxSocketCalls.*;

public final class ReadinessOperation implements Waiter {
    private static final Logger log = LoggerFactory.getLogger(ReadinessOperation.class);
    private static final AtomicLong OPERATIONS = new AtomicLong();
    private static final short POLLIN = 1, POLLPRI = 2, POLLOUT = 4, POLLERR = 8, POLLHUP = 16, POLLNVAL = 32;
    private final Emulator<?> emulator;
    private final IntFunction<? extends FileIO> lookup;
    private final long id = OPERATIONS.incrementAndGet(), started = System.nanoTime(), timeout;
    private final Pointer pollfds, readfds, writefds, exceptfds, timePointer;
    private final boolean timeval;
    private final int[] descriptors;
    private final short[] events, returned;
    private final byte[] reads, writes, exceptions, readyReads, readyWrites, readyExceptions;
    private boolean interrupted, finished, destroyed;
    private int result;

    private ReadinessOperation(Emulator<?> emulator, IntFunction<? extends FileIO> lookup, Pointer pollfds, int count,
                               Pointer readfds, Pointer writefds, Pointer exceptfds, long timeout, Pointer timePointer, boolean timeval) {
        this.emulator = emulator;
        this.lookup = lookup;
        this.pollfds = pollfds;
        this.readfds = readfds;
        this.writefds = writefds;
        this.exceptfds = exceptfds;
        this.timeout = timeout;
        this.timePointer = timePointer;
        this.timeval = timeval;
        descriptors = new int[count];
        events = new short[count];
        returned = new short[count];
        int word = emulator.is64Bit() ? 8 : 4;
        int bytes = (int) (((long) count + word * 8 - 1) / (word * 8) * word);
        reads = readfds == null ? null : readfds.getByteArray(0, bytes);
        writes = writefds == null ? null : writefds.getByteArray(0, bytes);
        exceptions = exceptfds == null ? null : exceptfds.getByteArray(0, bytes);
        readyReads = reads == null ? null : new byte[bytes];
        readyWrites = writes == null ? null : new byte[bytes];
        readyExceptions = exceptions == null ? null : new byte[bytes];
        for (int i = 0; i < count; i++) {
            if (pollfds != null) {
                descriptors[i] = pollfds.getInt(i * 8L);
                events[i] = pollfds.getShort(i * 8L + 4);
            } else {
                descriptors[i] = i;
                events[i] = (short) ((contains(reads, i) ? POLLIN : 0) | (contains(writes, i) ? POLLOUT : 0) |
                        (contains(exceptions, i) ? POLLPRI : 0));
            }
        }
        log.debug("event=native_readiness_started emulator={} operation={} poll={} descriptors={} timeout_ns={}",
                System.identityHashCode(emulator), id, pollfds != null, descriptors, timeout);
    }

    public static int poll(Emulator<?> emulator, IntFunction<? extends FileIO> lookup, Pointer fds, int count,
                           long timeout, Pointer timePointer) {
        if (count < 0) {
            return -EINVAL;
        }
        if (fds == null && count != 0) {
            return -EFAULT;
        }
        return new ReadinessOperation(emulator, lookup, fds, count, null, null, null, timeout, timePointer, false).execute();
    }

    public static int select(Emulator<?> emulator, IntFunction<? extends FileIO> lookup, int count, Pointer reads,
                             Pointer writes, Pointer exceptions, Pointer timePointer, boolean timeval) {
        if (count < 0) {
            return -EINVAL;
        }
        long timeout;
        try {
            timeout = duration(emulator, timePointer, timeval);
        } catch (LinuxErrnoException failure) {
            return -failure.getErrno();
        }
        return new ReadinessOperation(emulator, lookup, null, count, reads, writes, exceptions, timeout, timePointer, timeval).execute();
    }

    public static long duration(Emulator<?> emulator, Pointer pointer, boolean timeval) {
        if (pointer == null) {
            return -1;
        }
        long seconds = emulator.is64Bit() ? pointer.getLong(0) : pointer.getInt(0);
        long fraction = emulator.is64Bit() ? pointer.getLong(8) : pointer.getInt(4);
        long scale = timeval ? 1000000 : 1000000000;
        if (seconds < 0 || fraction < 0 || fraction >= scale) {
            throw new LinuxErrnoException(EINVAL);
        }
        long whole = TimeUnit.SECONDS.toNanos(seconds);
        long part = timeval ? TimeUnit.MICROSECONDS.toNanos(fraction) : fraction;
        return Long.MAX_VALUE - whole < part ? Long.MAX_VALUE : whole + part;
    }

    private static boolean contains(byte[] bits, int fd) {
        return bits != null && (bits[fd / 8] & (1 << (fd % 8))) != 0;
    }

    private static void include(byte[] bits, int fd) {
        bits[fd / 8] |= (byte) (1 << (fd % 8));
    }

    @Override
    public boolean canDispatch() {
        if (finished) {
            return true;
        }
        if (interrupted) {
            complete(-EINTR);
            return true;
        }
        int ready = 0;
        try {
            for (int i = 0; i < descriptors.length; i++) {
                int fd = descriptors[i];
                if (fd < 0 || (pollfds == null && events[i] == 0)) {
                    returned[i] = 0;
                    continue;
                }
                FileIO file = lookup.apply(fd);
                short value = file == null ? POLLNVAL : file.poll(events[i]);
                returned[i] = value;
                if (pollfds != null) {
                    ready += value == 0 ? 0 : 1;
                } else {
                    if ((value & POLLNVAL) != 0) {
                        complete(-EBADF);
                        return true;
                    }
                    if (contains(reads, i) && (value & (POLLIN | POLLERR | POLLHUP)) != 0) {
                        include(readyReads, i);
                        ready++;
                    }
                    if (contains(writes, i) && (value & (POLLOUT | POLLERR)) != 0) {
                        include(readyWrites, i);
                        ready++;
                    }
                    if (contains(exceptions, i) && (value & POLLPRI) != 0) {
                        include(readyExceptions, i);
                        ready++;
                    }
                }
            }
        } catch (LinuxErrnoException failure) {
            complete(-failure.getErrno());
            return true;
        }
        if (ready != 0 || (timeout >= 0 && System.nanoTime() - started >= timeout)) {
            complete(ready);
        }
        return finished;
    }

    private void complete(int value) {
        result = value;
        finished = true;
        log.debug("event=native_readiness_completed operation={} result={} revents={}", id, result, returned);
    }

    private int execute() {
        try {
            if (canDispatch()) {
                return publish();
            }
            RunnableTask task = emulator.getThreadDispatcher().getRunningTask();
            if (task == null) {
                throw new IllegalStateException("Blocking readiness requires a dispatcher task");
            }
            task.setWaiter(emulator, this);
        } catch (RuntimeException | Error failure) {
            onDestroy(emulator);
            throw failure;
        }
        throw new ThreadContextSwitchException();
    }

    private int publish() {
        if (result >= 0) {
            if (pollfds != null) {
                for (int i = 0; i < returned.length; i++) {
                    pollfds.setShort(i * 8L + 6, returned[i]);
                }
            } else {
                copy(readfds, readyReads);
                copy(writefds, readyWrites);
                copy(exceptfds, readyExceptions);
            }
        }
        if (timePointer != null) {
            long remaining = Math.max(0, timeout - (System.nanoTime() - started));
            long seconds = remaining / 1000000000;
            long fraction = remaining % 1000000000 / (timeval ? 1000 : 1);
            if (emulator.is64Bit()) {
                timePointer.setLong(0, seconds);
                timePointer.setLong(8, fraction);
            } else {
                timePointer.setInt(0, (int) seconds);
                timePointer.setInt(4, (int) fraction);
            }
        }
        destroyed = true;
        return result;
    }

    private static void copy(Pointer pointer, byte[] bytes) {
        if (pointer != null) {
            pointer.write(0, bytes, 0, bytes.length);
        }
    }

    @Override
    public void onContinueRun(Emulator<?> emulator) {
        emulator.getBackend().reg_write(emulator.is64Bit() ? Arm64Const.UC_ARM64_REG_X0 : ArmConst.UC_ARM_REG_R0, publish());
    }

    @Override
    public void onSignal(SignalTask task) {
        interrupted = true;
    }

    @Override
    public void onDestroy(Emulator<?> emulator) {
        if (!destroyed) {
            destroyed = true;
            log.debug("event=native_readiness_cancelled operation={}", id);
        }
    }
}
