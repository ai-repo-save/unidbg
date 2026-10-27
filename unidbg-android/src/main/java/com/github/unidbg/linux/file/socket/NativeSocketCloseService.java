package com.github.unidbg.linux.file.socket;

import com.github.unidbg.Emulator;
import com.github.unidbg.linux.LinuxErrnoException;
import com.github.unidbg.thread.DispatcherEventSource;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class NativeSocketCloseService implements DispatcherEventSource {
    private static final Logger log = LoggerFactory.getLogger(NativeSocketCloseService.class);
    private static final AtomicLong SERVICES = new AtomicLong();
    private static final AtomicLong REQUESTS = new AtomicLong();
    private final long id = SERVICES.incrementAndGet();
    private final int emulator;
    private final Thread owner = Thread.currentThread();
    private final Runnable releaseOwners;
    private final Set<Request> pending = new HashSet<>();
    private final ConcurrentLinkedQueue<Request> completed = new ConcurrentLinkedQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final ExecutorService executor;
    private long workerSequence;
    private boolean closing, closed;
    private Throwable failure;

    public NativeSocketCloseService(Emulator<?> emulator, Runnable releaseOwners) {
        this.emulator = System.identityHashCode(emulator);
        this.releaseOwners = releaseOwners;
        ThreadFactory factory = Executors.defaultThreadFactory();
        executor = Executors.newCachedThreadPool(work -> {
            Thread thread = factory.newThread(work);
            thread.setName("unidbg-socket-close-" + id + "-" + (++workerSequence));
            thread.setDaemon(true);
            workers.add(thread);
            log.debug("event=native_socket_close_worker_created emulator={} service={} worker={}", this.emulator, id, thread.getName());
            return thread;
        });
        emulator.getThreadDispatcher().addEventSource(this);
        log.debug("event=native_socket_close_service_started emulator={} service={}", this.emulator, id);
    }

    public Request submit(long socket, int fd) {
        requireOwner();
        if (closing) {
            throw new IllegalStateException("Native socket close service is sealed");
        }
        Request request = new Request(REQUESTS.incrementAndGet(), socket, fd);
        pending.add(request);
        log.debug("event=native_socket_close_submitted emulator={} service={} request={} socket={} host_fd={}",
                emulator, id, request.id, socket, fd);
        executor.execute(() -> close(request));
        return request;
    }

    private void close(Request request) {
        log.debug("event=native_socket_close_started emulator={} service={} request={} socket={} host_fd={} worker={}",
                emulator, id, request.id, request.socket, request.fd, Thread.currentThread().getName());
        try {
            int result = LinuxSocketCalls.LIBC.close(request.fd);
            request.result = result < 0 ? -Native.getLastError() : result;
            if (request.result < 0) {
                log.warn("event=native_socket_close_error emulator={} service={} request={} socket={} host_fd={} result={}",
                        emulator, id, request.id, request.socket, request.fd, request.result,
                        new LinuxErrnoException(-request.result));
            } else {
                log.debug("event=native_socket_close_completed emulator={} service={} request={} socket={} result={}",
                        emulator, id, request.id, request.socket, request.result);
            }
        } catch (RuntimeException | Error error) {
            request.failure = error;
            log.error("event=native_socket_close_failed emulator={} service={} request={} socket={} host_fd={} worker={}",
                    emulator, id, request.id, request.socket, request.fd, Thread.currentThread().getName(), error);
        } finally {
            request.done = true;
            completed.add(request);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Socket resource management requires the backend owner thread");
        }
    }

    @Override
    public void dispatchPending() {
        requireOwner();
        Request request;
        while ((request = completed.poll()) != null) {
            pending.remove(request);
            if (request.failure != null) {
                if (failure == null) {
                    failure = request.failure;
                } else if (failure != request.failure) {
                    failure.addSuppressed(request.failure);
                }
            }
        }
        for (Iterator<Thread> iterator = workers.iterator(); iterator.hasNext();) {
            Thread worker = iterator.next();
            if (!worker.isAlive()) {
                iterator.remove();
                log.debug("event=native_socket_close_worker_terminated emulator={} service={} worker={}", emulator, id, worker.getName());
            }
        }
        if (!closed && closing && isClosed()) {
            closed = true;
            log.debug("event=native_socket_close_service_closed emulator={} service={} workers_created={}", emulator, id, workerSequence);
        }
    }

    @Override
    public void beginClose() {
        requireOwner();
        if (!closing) {
            releaseOwners.run();
            closing = true;
            executor.shutdown();
            log.debug("event=native_socket_close_service_closing emulator={} service={} pending={} workers={}",
                    emulator, id, pending.size(), workers.size());
        }
    }

    @Override
    public boolean isClosed() {
        if (!closing || !pending.isEmpty() || !executor.isTerminated()) {
            return false;
        }
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Throwable closeFailure() {
        return failure;
    }

    public static final class Request {
        private final long id, socket;
        private final int fd;
        private volatile boolean done;
        private int result;
        private Throwable failure;

        private Request(long id, long socket, int fd) {
            this.id = id;
            this.socket = socket;
            this.fd = fd;
        }

        public boolean isDone() {
            return done;
        }

        public int result() {
            if (!done) {
                throw new IllegalStateException("Native close is still running: " + id);
            }
            if (failure != null) {
                throw new IllegalStateException("Native close failed: " + id, failure);
            }
            return result;
        }

        public long id() {
            return id;
        }
    }
}
