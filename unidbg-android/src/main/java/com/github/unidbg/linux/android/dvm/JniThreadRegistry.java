package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.dvm.host.GuestJavaRuntime;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.thread.DispatcherEventSource;
import com.github.unidbg.thread.Task;
import com.github.unidbg.thread.ThreadLifecycleListener;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class JniThreadRegistry implements DispatcherEventSource, ThreadLifecycleListener {
    private static final Logger log = LoggerFactory.getLogger(JniThreadRegistry.class);
    private final AndroidEmulator emulator;
    private final JniThreadResources resources;
    private final Thread backendThread = Thread.currentThread();
    private final Map<Integer, JniThreadState> states = new HashMap<>();
    private final Map<JniThreadState, CompletableFuture<Void>> retiring = new HashMap<>();
    private UnidbgPointer functionTable;
    private GuestJavaRuntime hostRuntime;
    private int mainTid;
    private boolean initialized;
    private boolean closing;

    JniThreadRegistry(AndroidEmulator emulator, JniThreadResources resources) {
        this.emulator = emulator;
        this.resources = resources;
        emulator.getThreadDispatcher().addEventSource(this);
        emulator.getThreadDispatcher().addThreadLifecycleListener(this);
    }

    private void requireOwner() {
        if (Thread.currentThread() != backendThread) {
            throw new IllegalStateException("JNI state requires the backend owner thread");
        }
    }

    void bindJniFunctionTable(UnidbgPointer table) {
        requireOwner();
        if (functionTable != null || table == null) {
            throw new IllegalStateException("JNI function table must be bound once");
        }
        functionTable = table;
    }

    void initializeMainThread(int tid) {
        requireOwner();
        if (initialized || functionTable == null) {
            throw new IllegalStateException("Initialize the main JNI thread after binding its function table");
        }
        mainTid = tid;
        initialized = true;
        attachState(states.computeIfAbsent(tid, JniThreadState::new), false);
    }

    JniThreadState currentThreadState() {
        requireOwner();
        if (!initialized || closing) {
            throw new IllegalStateException("JNI thread registry is not running");
        }
        Task task = emulator.get(Task.TASK_KEY);
        int tid = task == null ? mainTid : task.getId();
        return states.computeIfAbsent(tid, JniThreadState::new);
    }

    JniThreadState requireAttachedState(Pointer env) {
        JniThreadState state = currentThreadState();
        if (!state.attached() || Pointer.nativeValue(state.env()) != Pointer.nativeValue(env)) {
            throw new IllegalArgumentException("JNIEnv does not belong to the current attached guest thread");
        }
        return state;
    }

    int getEnv(Pointer outEnv, int version) {
        if (outEnv == null) {
            return VM.JNI_ERR;
        }
        JniThreadState state = currentThreadState();
        outEnv.setPointer(0, null);
        if (!state.attached()) {
            return -2; // JNI_EDETACHED
        }
        if (!resources.supportsJniVersion(version)) {
            return -3; // JNI_EVERSION
        }
        outEnv.setPointer(0, state.env());
        return VM.JNI_OK;
    }

    int attach(Pointer outEnv, Pointer attachArgs, boolean daemon) {
        if (outEnv == null) {
            return VM.JNI_ERR;
        }
        JniThreadState state = currentThreadState();
        if (!state.attached()) {
            if (attachArgs != null && !resources.supportsJniVersion(attachArgs.getInt(0))) {
                outEnv.setPointer(0, null);
                return -3;
            }
            attachState(state, daemon);
        }
        outEnv.setPointer(0, state.env());
        return VM.JNI_OK;
    }

    private void attachState(JniThreadState state, boolean daemon) {
        UnidbgPointer env = emulator.getSvcMemory().allocate(emulator.getPointerSize(), "JNIEnv-tid-" + state.tid());
        env.setPointer(0, functionTable);
        state.attach(env, daemon, resources);
        log.debug("event=jni_thread_attached tid={} env={} daemon={}", state.tid(), env, daemon);
    }

    GuestJavaRuntime hostRuntime() {
        requireOwner();
        if (hostRuntime == null) {
            hostRuntime = new GuestJavaRuntime((AbstractEmulator<?>) emulator);
        }
        return hostRuntime;
    }

    int detach() {
        JniThreadState state = currentThreadState();
        if (!state.attached()) {
            return VM.JNI_OK;
        }
        if (hostRuntime == null) {
            clearState(state);
            return VM.JNI_OK;
        }
        if (hostRuntime.hasActiveJavaCall(state.tid())) {
            return VM.JNI_ERR;
        }
        return (int) GuestJavaWaiter.suspend(emulator, hostRuntime.releaseMonitors(), result -> {
            try {
                result.get();
                clearState(state);
                emulator.getBackend().reg_write(emulator.is64Bit() ? Arm64Const.UC_ARM64_REG_X0 : ArmConst.UC_ARM_REG_R0, VM.JNI_OK);
            } catch (Exception failure) {
                throw new IllegalStateException("JNI thread monitor cleanup failed", failure);
            }
        });
    }

    private void clearState(JniThreadState state) {
        if (state.attached()) {
            resources.clearThreadReferences(state);
            state.detached();
            log.debug("event=jni_thread_detached tid={}", state.tid());
        }
    }

    void onThreadTerminated(int tid) {
        requireOwner();
        JniThreadState state = states.get(tid);
        if (state != null) {
            retiring.put(state, hostRuntime == null ? CompletableFuture.completedFuture(null) : hostRuntime.terminateThread(tid));
        }
    }

    @Override
    public void onThreadTerminated(Task task) {
        onThreadTerminated(task.getId());
    }

    @Override
    public void dispatchPending() {
        requireOwner();
        for (Iterator<Map.Entry<JniThreadState, CompletableFuture<Void>>> iterator = retiring.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<JniThreadState, CompletableFuture<Void>> entry = iterator.next();
            if (entry.getValue().isDone()) {
                entry.getValue().join();
                clearState(entry.getKey());
                states.remove(entry.getKey().tid());
                iterator.remove();
            }
        }
    }

    @Override
    public void beginClose() {
        requireOwner();
        closing = true;
        for (JniThreadState state : states.values()) {
            retiring.put(state, hostRuntime == null ? CompletableFuture.completedFuture(null) : hostRuntime.terminateThread(state.tid()));
        }
    }

    @Override
    public boolean isClosed() {
        return closing && retiring.isEmpty();
    }

    @Override
    public Throwable closeFailure() {
        return null;
    }

    void close() {
        requireOwner();
        emulator.getThreadDispatcher().shutdown(5, TimeUnit.SECONDS);
    }
}
