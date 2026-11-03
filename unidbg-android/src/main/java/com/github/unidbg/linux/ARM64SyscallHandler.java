package com.github.unidbg.linux;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.LongJumpException;
import com.github.unidbg.StopEmulatorException;
import com.github.unidbg.Svc;
import com.github.unidbg.arm.ARM;
import com.github.unidbg.arm.ARMEmulator;
import com.github.unidbg.arm.Arm64Svc;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.BackendException;
import com.github.unidbg.arm.context.Arm64RegisterContext;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.file.ByteArrayFileIO;
import com.github.unidbg.linux.file.DriverFileIO;
import com.github.unidbg.linux.file.LocalAndroidUdpSocket;
import com.github.unidbg.linux.file.LocalSocketIO;
import com.github.unidbg.linux.file.NetLinkSocket;
import com.github.unidbg.linux.file.PipedSocketIO;
import com.github.unidbg.linux.file.SocketIO;
import com.github.unidbg.linux.file.UdpSocket;
import com.github.unidbg.linux.file.seqpacket.NativeSeqpacketSocketFactory;
import com.github.unidbg.linux.kernel.AnonymousName;
import com.github.unidbg.linux.kernel.KernelSyscalls;
import com.github.unidbg.unix.kernel.KernelIdentity;
import com.github.unidbg.linux.struct.Stat64;
import com.github.unidbg.linux.thread.MarshmallowThread;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.thread.PopContextException;
import com.github.unidbg.thread.Task;
import com.github.unidbg.thread.ThreadContextSwitchException;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.time.ClockSource;
import com.github.unidbg.utils.Inspector;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;

import java.util.ArrayList;
import java.util.List;

/**
 * <a href="http://androidxref.com/6.0.0_r5/xref/external/kernel-headers/original/uapi/asm-generic/unistd.h">unistd</a>
 */
public class ARM64SyscallHandler extends AndroidSyscallHandler {

    private static final Logger log = LoggerFactory.getLogger(ARM64SyscallHandler.class);

    private final SvcMemory svcMemory;
    private final NativeSeqpacketSocketFactory seqpacketSockets = new NativeSeqpacketSocketFactory();
    private final KernelSyscalls kernel;

    @Override
    public void destroy() {
        seqpacketSockets.destroy();
        super.destroy();
    }

    public ARM64SyscallHandler(SvcMemory svcMemory) {
        super();

        this.svcMemory = svcMemory;
        this.kernel = new KernelSyscalls(this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void hook(Backend backend, int intno, int swi, Object user) {
        Emulator<AndroidFileIO> emulator = (Emulator<AndroidFileIO>) user;
        UnidbgPointer pc = UnidbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_PC);

        if (intno == ARMEmulator.EXCP_BKPT) { // brk
            createBreaker(emulator).brk(pc, pc == null ? swi : (pc.getInt(0) >> 5) & 0xffff);
            return;
        }
        if (intno == ARMEmulator.EXCP_UDEF) {
            createBreaker(emulator).debug("Undefined instruction (EXCP_UDEF) at " + pc);
            return;
        }

        if (intno != ARMEmulator.EXCP_SWI) {
            throw new BackendException("intno=" + intno);
        }

        int NR = backend.reg_read(Arm64Const.UC_ARM64_REG_X8).intValue();
        String syscall = null;
        Throwable exception = null;
        try {
            if (swi == 0 && NR == 0 && backend.reg_read(Arm64Const.UC_ARM64_REG_X16).intValue() == Svc.POST_CALLBACK_SYSCALL_NUMBER) { // postCallback
                int number = backend.reg_read(Arm64Const.UC_ARM64_REG_X12).intValue();
                Svc svc = svcMemory.getSvc(number);
                if (svc != null) {
                    svc.handlePostCallback(emulator);
                    return;
                }
                backend.emu_stop();
                throw new IllegalStateException("svc number: " + swi);
            }
            if (swi == 0 && NR == 0 && backend.reg_read(Arm64Const.UC_ARM64_REG_X16).intValue() == Svc.PRE_CALLBACK_SYSCALL_NUMBER) { // preCallback
                int number = backend.reg_read(Arm64Const.UC_ARM64_REG_X12).intValue();
                Svc svc = svcMemory.getSvc(number);
                if (svc != null) {
                    svc.handlePreCallback(emulator);
                    return;
                }
                backend.emu_stop();
                throw new IllegalStateException("svc number: " + swi);
            }
            if (swi != 0) {
                if (swi == Arm64Svc.SVC_MAX) {
                    throw new PopContextException();
                }
                if (swi == Arm64Svc.SVC_MAX - 1) {
                    throw new ThreadContextSwitchException();
                }
                Svc svc = svcMemory.getSvc(swi);
                if (svc != null) {
                    log.debug("swi={}, svc={}", swi, svc);
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, svc.handle(emulator));
                    return;
                }
                backend.emu_stop();
                throw new BackendException("svc number: " + swi);
            }

            if (log.isTraceEnabled()) {
                ARM.showRegs64(emulator, null);
            }

            if (handleSyscall(emulator, NR)) {
                return;
            }

            switch (NR) {
                case 17:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getcwd(emulator));
                    return;
                case 19:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, eventfd2(emulator));
                    return;
                case 64:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, write(emulator));
                    return;
                case 221:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, execve(emulator));
                    return;
                case 62:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, lseek(emulator));
                    return;
                case 172: // getpid
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, emulator.getPid());
                    return;
                case 178: // gettid
                    Task task = emulator.get(Task.TASK_KEY);
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, task == null ? 0 : task.getId());
                    return;
                case 129:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kill(emulator));
                    return;
                case 29:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, ioctl(emulator));
                    return;
                case 34:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mkdirat(emulator));
                    return;
                case 35:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, unlinkat(emulator));
                    return;
                case 38:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, renameat(emulator));
                    return;
                case 47:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fallocate(emulator));
                    return;
                case 53:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fchmodat(emulator));
                    return;
                case 54:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fchownat(emulator));
                    return;
                case 56:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, openat(emulator));
                    return;
                case 57:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, close(backend, emulator));
                    return;
                case 59:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, pipe2(emulator));
                    return;
                case 63:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, read(backend, emulator));
                    return;
                case 67:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, pread64(emulator));
                    return;
                case 20:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kernel.epollCreate1(emulator.getContext().getIntArg(0)));
                    return;
                case 23:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, dup(emulator));
                    return;
                case 24:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, dup3(emulator));
                    return;
                case 40:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kernel.mount(
                            emulator.getContext().getPointerArg(0).getString(0),
                            emulator.getContext().getPointerArg(1).getString(0),
                            emulator.getContext().getPointerArg(2).getString(0),
                            emulator.getContext().getLongArg(3)));
                    return;
                case 43: {
                    RegisterContext context = emulator.getContext();
                    Pointer pathPointer = context.getPointerArg(0);
                    Pointer buf = context.getPointerArg(1);
                    String path = pathPointer.getString(0);
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, statfs64(emulator, path, buf));
                    return;
                }
                case 46: {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, ftruncate(emulator));
                    return;
                }
                case 134:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sigaction(emulator));
                    return;
                case 72:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, pselect6(emulator));
                    return;
                case 78:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, readlinkat(emulator));
                    return;
                case 80:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fstat(backend, emulator));
                    return;
                case 83:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fdatasync(emulator));
                    return;
                case 96:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, set_tid_address(emulator));
                    return;
                case 98:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, futex(emulator));
                    return;
                case 220: {
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, clone(emulator));
                    return;
                }
                case 160:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, uname(emulator));
                    return;
                case 132:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sigaltstack(emulator));
                    return;
                case 135:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sigprocmask(emulator));
                    return;
                case 32:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, flock(emulator));
                    return;
                case 66:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, writev(emulator));
                    return;
                case 101:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, nanosleep(emulator));
                    return;
                case 119:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_setscheduler(emulator));
                    return;
                case 122:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_setaffinity(emulator));
                    return;
                case 123:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_getaffinity(emulator));
                    return;
                case 124:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_yield(emulator));
                    return;
                case 136:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, rt_sigpending(emulator));
                    return;
                case 137:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, rt_sigtimedwait(emulator));
                    return;
                case 138:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, rt_sigqueue(emulator));
                    return;
                case 140:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, setpriority(emulator));
                    return;
                case 165:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kernel.getrusage(emulator,
                            emulator.getContext().getIntArg(0), emulator.getContext().getPointerArg(1)));
                    return;
                case 167:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, prctl(emulator));
                    return;
                case 168: {
                    Task thread = emulator.get(Task.TASK_KEY);
                    RegisterContext context = emulator.getContext();
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kernel.getcpu(thread == null ? 0 : thread.getId(),
                            context.getPointerArg(0), context.getPointerArg(1)));
                    return;
                }
                case 169:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, gettimeofday(emulator));
                    return;
                case 73:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, ppoll(emulator));
                    return;
                case 179:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0,
                            kernel.sysinfo(emulator, emulator.getContext().getPointerArg(0)));
                    return;
                case 173:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getppid(emulator));
                    return;
                case 174: // getuid
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getGuestCredentials().requireRealUid());
                    return;
                case 175: // geteuid
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getGuestCredentials().requireEffectiveUid());
                    return;
                case 176: // getgid
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getGuestCredentials().requireRealGid());
                    return;
                case 177: // getegid
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getGuestCredentials().requireEffectiveGid());
                    return;
                case 158: // getgroups
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0,
                            queryGroups(emulator.getContext().getIntArg(0), emulator.getContext().getPointerArg(1)));
                    return;
                case 200:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, bind(emulator));
                    return;
                case 201:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, listen(emulator));
                    return;
                case 214:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, brk(backend, emulator));
                    return;
                case 215:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, munmap(backend, emulator));
                    return;
                case 216:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mremap(emulator));
                    return;
                case 61:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getdents64(emulator));
                    return;
                case 241: {
                    RegisterContext context = emulator.getContext();
                    Pointer attributes = context.getPointerArg(0);
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kernel.perfEventOpen(attributes.getInt(0),
                            attributes.getLong(8), context.getIntArg(1), context.getIntArg(2), context.getIntArg(3)));
                    return;
                }
                case 233:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, MemoryAdvice.madvise(emulator));
                    return;
                case 25:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fcntl(emulator));
                    return;
                case 222:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mmap(backend, emulator));
                    return;
                case 226:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mprotect(backend, emulator));
                    return;
                case 227:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, msync(emulator));
                    return;
                case 85:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0,
                            kernel.timerfdCreate(emulator.getContext().getIntArg(0), emulator.getContext().getIntArg(1)));
                    return;
                case 90:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kernel.capget(emulator,
                            emulator.getContext().getPointerArg(0), emulator.getContext().getPointerArg(1)));
                    return;
                case 92:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0,
                            kernel.personality(emulator, emulator.getContext().getLongArg(0)));
                    return;
                case 93:
                    exit(emulator);
                    return;
                case 94:
                    exit_group(emulator);
                    return;
                case 113:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, clock_gettime(emulator));
                    return;
                case 114:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, clock_getres(emulator));
                    return;
                case 117:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, ptrace(emulator));
                    return;
                case 120:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_getscheduler(emulator));
                    return;
                case 121:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sched_getparam(emulator));
                    return;
                case 131:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, tgkill(emulator));
                    return;
                case 141:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getpriority(emulator));
                    return;
                case 163:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getrlimit64(emulator));
                    return;
                case 198:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, socket(emulator));
                    return;
                case 199:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, socketpair(emulator));
                    return;
                case 203:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, connect(emulator));
                    return;
                case 204:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getsockname(emulator));
                    return;
                case 261:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kernel.prlimit64(emulator,
                            emulator.getContext().getIntArg(0), emulator.getContext().getIntArg(1),
                            emulator.getContext().getPointerArg(2), emulator.getContext().getPointerArg(3)));
                    return;
                case 242:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, accept4(emulator));
                    return;
                case 205:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getpeername(emulator));
                    return;
                case 206:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, sendto(emulator));
                    return;
                case 207:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, recvfrom(emulator));
                    return;
                case 208:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, setsockopt(emulator));
                    return;
                case 209:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, getsockopt(emulator));
                    return;
                case 210:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, shutdown(emulator));
                    return;
                case 228:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, mlock(emulator));
                    return;
                case 278:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, gerrandom(emulator));
                    return;
                case 79:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, fstatat64(emulator));
                    return;
                case 279:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, kernel.memFdCreate(
                            emulator.getContext().getPointerArg(0).getString(0), emulator.getContext().getIntArg(1)));
                    return;
                case 283:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0,
                            kernel.membarrier(emulator.getContext().getIntArg(0), emulator.getContext().getIntArg(1)));
                    return;
                case 48:
                    backend.reg_write(Arm64Const.UC_ARM64_REG_X0, faccessat(emulator));
                    return;
            }
        } catch (StopEmulatorException e) {
            backend.emu_stop();
            return;
        } catch (LongJumpException e) {
            backend.emu_stop();
            throw e;
        } catch (Throwable e) {
            backend.emu_stop();
            exception = e;
        }

        if (exception == null && handleUnknownSyscall(emulator, NR)) {
            return;
        }

        log.warn("handleInterrupt intno={}, NR={}, svcNumber=0x{}, PC={}, LR={}, syscall={}", intno, NR, Integer.toHexString(swi), pc, UnidbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_LR), syscall, exception);
        if (exception == null) {
            backend.reg_write(Arm64Const.UC_ARM64_REG_X0, -UnixEmulator.ENOSYS);
            return;
        }
        if (log.isDebugEnabled()) {
            emulator.attach().debug("Unhandled syscall NR=" + NR + " (" + syscall + ") at " + pc);
        }
        if (exception instanceof RuntimeException) {
            throw (RuntimeException) exception;
        }
        if (exception instanceof Error) {
            throw (Error) exception;
        }
        throw new BackendException(exception);
    }

    private int pread64(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        Pointer buffer = context.getPointerArg(1);
        long count = context.getLongArg(2);
        long offset = context.getLongArg(3);
        if (count < 0) {
            if (offset < 0) {
                return -UnixEmulator.EINVAL;
            }
            FileIO file = fdMap.get(fd);
            if (file == null || !file.isReadable()) {
                return -UnixEmulator.EBADF;
            }
            // Linux arm64 access_ok rejects size >= 2^63 before rw_verify_area and MAX_RW_COUNT.
            return -UnixEmulator.EFAULT;
        }
        // Linux include/linux/fs.h: MAX_RW_COUNT is INT_MAX & PAGE_MASK.
        int maxRwCount = Integer.MAX_VALUE & -emulator.getPageAlign();
        int readCount = Long.compareUnsigned(count, maxRwCount) > 0 ? maxRwCount : (int) count;
        return pread(emulator, fd, buffer, readCount, offset);
    }

    private long getrlimit64(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        return kernel.prlimit64(emulator, 0, context.getIntArg(0), null, context.getPointerArg(1));
    }

    private long msync(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer addr = context.getPointerArg(0);
        int len = context.getIntArg(1);
        int flags = context.getIntArg(2);
        if (log.isDebugEnabled()) {
            log.debug("msync addr={}, len={}, flags=0x{}", addr, len, Integer.toHexString(flags));
        }
        return emulator.getMemory().msync(UnidbgPointer.nativeValue(addr), len, flags);
    }

    private long fdatasync(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        if (log.isDebugEnabled()) {
            log.debug("fdatasync fd={}", fd);
        }
        return 0;
    }

    private long gerrandom(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer buf = context.getPointerArg(0);
        int bufSize = context.getIntArg(1);
        int flags = context.getIntArg(2);
        return getrandom(buf, bufSize, flags);
    }

    private long clone(Emulator<?> emulator) {
        Arm64RegisterContext context = emulator.getContext();
        Pointer child_stack = context.getPointerArg(1);
        if (child_stack == null &&
                context.getPointerArg(2) == null) {
            // http://androidxref.com/6.0.1_r10/xref/bionic/libc/bionic/fork.cpp#47
            return fork(emulator); // vfork
        }

        long fn = context.getXLong(5);
        long arg = context.getXLong(6);
        if (child_stack != null && child_stack.getLong(0) == fn && child_stack.getLong(8) == arg) {
            // http://androidxref.com/6.0.1_r10/xref/bionic/libc/arch-arm/bionic/__bionic_clone.S#49
            return bionic_clone(emulator);
        } else {
            return pthread_clone(emulator);
        }
    }

    private int pthread_clone(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int flags = context.getIntArg(0);
        UnidbgPointer child_stack = context.getPointerArg(1);
        List<String> list = new ArrayList<>();
        if ((flags & CLONE_VM) != 0) {
            list.add("CLONE_VM");
        }
        if ((flags & CLONE_FS) != 0) {
            list.add("CLONE_FS");
        }
        if ((flags & CLONE_FILES) != 0) {
            list.add("CLONE_FILES");
        }
        if ((flags & CLONE_SIGHAND) != 0) {
            list.add("CLONE_SIGHAND");
        }
        if ((flags & CLONE_PTRACE) != 0) {
            list.add("CLONE_PTRACE");
        }
        if ((flags & CLONE_VFORK) != 0) {
            list.add("CLONE_VFORK");
        }
        if ((flags & CLONE_PARENT) != 0) {
            list.add("CLONE_PARENT");
        }
        if ((flags & CLONE_THREAD) != 0) {
            list.add("CLONE_THREAD");
        }
        if ((flags & CLONE_NEWNS) != 0) {
            list.add("CLONE_NEWNS");
        }
        if ((flags & CLONE_SYSVSEM) != 0) {
            list.add("CLONE_SYSVSEM");
        }
        if ((flags & CLONE_SETTLS) != 0) {
            list.add("CLONE_SETTLS");
        }
        if ((flags & CLONE_PARENT_SETTID) != 0) {
            list.add("CLONE_PARENT_SETTID");
        }
        if ((flags & CLONE_CHILD_CLEARTID) != 0) {
            list.add("CLONE_CHILD_CLEARTID");
        }
        if ((flags & CLONE_DETACHED) != 0) {
            list.add("CLONE_DETACHED");
        }
        if ((flags & CLONE_UNTRACED) != 0) {
            list.add("CLONE_UNTRACED");
        }
        if ((flags & CLONE_CHILD_SETTID) != 0) {
            list.add("CLONE_CHILD_SETTID");
        }
        if ((flags & CLONE_STOPPED) != 0) {
            list.add("CLONE_STOPPED");
        }
        int threadId = incrementThreadId(emulator);

        UnidbgPointer fn = child_stack.getPointer(0);
        child_stack = child_stack.share(8, 0);
        UnidbgPointer arg = child_stack.getPointer(0);
        child_stack = child_stack.share(8, 0);

        if (threadDispatcherEnabled) {
            throw new UnsupportedOperationException();
        }

        log.info("pthread_clone child_stack={}, thread_id={}, fn={}, arg={}, flags={}", child_stack, threadId, fn, arg, list);
        Logger log = LoggerFactory.getLogger(AbstractEmulator.class);
        if (log.isDebugEnabled()) {
            emulator.attach().debug("pthread_clone thread_id=" + threadId + ", fn=" + fn);
        }
        return threadId;
    }

    protected long fork(Emulator<?> emulator) {
        log.info("fork");
        return -UnixEmulator.ENOSYS;
    }

    private static final int CLONE_VM = 0x00000100;
    private static final int CLONE_FS = 0x00000200;
    private static final int CLONE_FILES = 0x00000400;
    private static final int CLONE_SIGHAND = 0x00000800;
    private static final int CLONE_PTRACE = 0x00002000;
    private static final int CLONE_VFORK = 0x00004000;
    private static final int CLONE_PARENT = 0x00008000;
    private static final int CLONE_THREAD = 0x00010000;
    private static final int CLONE_NEWNS = 0x00020000;
    private static final int CLONE_SYSVSEM = 0x00040000;
    private static final int CLONE_SETTLS = 0x00080000;
    private static final int CLONE_PARENT_SETTID = 0x00100000;
    private static final int CLONE_CHILD_CLEARTID = 0x00200000;
    private static final int CLONE_DETACHED = 0x00400000;
    private static final int CLONE_UNTRACED = 0x00800000;
    private static final int CLONE_CHILD_SETTID = 0x01000000;
    private static final int CLONE_STOPPED = 0x02000000;

    private int bionic_clone(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int flags = context.getIntArg(0);
        Pointer child_stack = context.getPointerArg(1);
        Pointer pid = context.getPointerArg(2);
        Pointer tls = context.getPointerArg(3);
        Pointer ctid = context.getPointerArg(4);
        UnidbgPointer fn = context.getPointerArg(5);
        UnidbgPointer arg = context.getPointerArg(6);
        List<String> list = new ArrayList<>();
        if ((flags & CLONE_VM) != 0) {
            list.add("CLONE_VM");
        }
        if ((flags & CLONE_FS) != 0) {
            list.add("CLONE_FS");
        }
        if ((flags & CLONE_FILES) != 0) {
            list.add("CLONE_FILES");
        }
        if ((flags & CLONE_SIGHAND) != 0) {
            list.add("CLONE_SIGHAND");
        }
        if ((flags & CLONE_PTRACE) != 0) {
            list.add("CLONE_PTRACE");
        }
        if ((flags & CLONE_VFORK) != 0) {
            list.add("CLONE_VFORK");
        }
        if ((flags & CLONE_PARENT) != 0) {
            list.add("CLONE_PARENT");
        }
        if ((flags & CLONE_THREAD) != 0) {
            list.add("CLONE_THREAD");
        }
        if ((flags & CLONE_NEWNS) != 0) {
            list.add("CLONE_NEWNS");
        }
        if ((flags & CLONE_SYSVSEM) != 0) {
            list.add("CLONE_SYSVSEM");
        }
        if ((flags & CLONE_SETTLS) != 0) {
            list.add("CLONE_SETTLS");
        }
        if ((flags & CLONE_PARENT_SETTID) != 0) {
            list.add("CLONE_PARENT_SETTID");
        }
        if ((flags & CLONE_CHILD_CLEARTID) != 0) {
            list.add("CLONE_CHILD_CLEARTID");
        }
        if ((flags & CLONE_DETACHED) != 0) {
            list.add("CLONE_DETACHED");
        }
        if ((flags & CLONE_UNTRACED) != 0) {
            list.add("CLONE_UNTRACED");
        }
        if ((flags & CLONE_CHILD_SETTID) != 0) {
            list.add("CLONE_CHILD_SETTID");
        }
        if ((flags & CLONE_STOPPED) != 0) {
            list.add("CLONE_STOPPED");
        }
        if (log.isDebugEnabled()) {
            log.debug("bionic_clone child_stack={}, pid={}, tls={}, ctid={}, fn={}, arg={}, flags={}", child_stack, pid, tls, ctid, fn, arg, list);
        }
        int threadId = incrementThreadId(emulator);
        if (threadDispatcherEnabled) {
            if (verbose) {
                System.out.printf("bionic_clone fn=%s, LR=%s%n", fn, context.getLRPointer());
            }
            emulator.getThreadDispatcher().addThread(new MarshmallowThread(emulator, fn, arg, ctid, threadId));
        }
        ctid.setInt(0, threadId);
        return threadId;
    }

    private int flock(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        int operation = context.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("flock fd={}, operation={}", fd, operation);
        }
        return 0;
    }

    private int execve(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer filename = context.getPointerArg(0);
        Pointer argv = context.getPointerArg(1);
        Pointer envp = context.getPointerArg(2);
        assert filename != null;
        List<String> args = new ArrayList<>();
        Pointer pointer;
        while ((pointer = argv.getPointer(0)) != null) {
            args.add(pointer.getString(0));
            argv = argv.share(8);
        }
        List<String> env = new ArrayList<>();
        while ((pointer = envp.getPointer(0)) != null) {
            env.add(pointer.getString(0));
            envp = envp.share(8);
        }
        log.info("execve filename={}, args={}, env={}", filename.getString(0), args, env);
        return -UnixEmulator.EACCES;
    }

    private int bind(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        Pointer addr = context.getPointerArg(1);
        int addrlen = context.getIntArg(2);
        return bind(emulator, sockfd, addr, addrlen);
    }

    private int listen(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        int backlog = context.getIntArg(1);
        return listen(emulator, sockfd, backlog);
    }

    protected int stat64(Emulator<AndroidFileIO> emulator, String pathname, Pointer statbuf) {
        return statAt(emulator, IO.AT_FDCWD, pathname, 0, statbuf);
    }

    private int getpeername(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        Pointer addr = context.getPointerArg(1);
        Pointer addrlen = context.getPointerArg(2);
        if (log.isDebugEnabled()) {
            log.debug("getpeername sockfd={}, addr={}, addrlen={}", sockfd, addr, addrlen);
        }

        FileIO io = fdMap.get(sockfd);
        if (io == null) {
            return -UnixEmulator.EBADF;
        }

        return io.getpeername(addr, addrlen);
    }

    private int sigprocmask(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int how = context.getIntArg(0);
        Pointer set = context.getPointerArg(1);
        Pointer oldset = context.getPointerArg(2);
        return sigprocmask(emulator, how, set, oldset);
    }

    private int ftruncate(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        int length = context.getIntArg(1);
        if (log.isDebugEnabled()) {
            log.debug("ftruncate fd={}, length={}", fd, length);
        }
        FileIO file = fdMap.get(fd);
        if (file == null) {
            throw new UnsupportedOperationException();
        }
        return file.ftruncate(length);
    }

    private int sigaction(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int signum = context.getIntArg(0);
        Pointer act = context.getPointerArg(1);
        Pointer oldact = context.getPointerArg(2);

        return sigaction(emulator, signum, act, oldact);
    }

    private int pselect6(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        return selectDescriptors(emulator, context.getIntArg(0), context.getPointerArg(1), context.getPointerArg(2),
                context.getPointerArg(3), context.getPointerArg(4), false, context.getPointerArg(5));
    }

    private int recvfrom(Emulator<?> emulator) {
        Backend backend = emulator.getBackend();
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        Pointer buf = context.getPointerArg(1);
        int len = context.getIntArg(2);
        int flags = context.getIntArg(3);
        Pointer src_addr = context.getPointerArg(4);
        Pointer addrlen = context.getPointerArg(5);

        log.debug("recvfrom sockfd={}, buf={}, len={}, flags={}, src_addr={}, addrlen={}", sockfd, buf, len, flags, src_addr, addrlen);
        FileIO file = fdMap.get(sockfd);
        if (file == null) {
            return -UnixEmulator.EBADF;
        }
        return file.recvfrom(backend, buf, len, flags, src_addr, addrlen);
    }

    private int sendto(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        Pointer buf = context.getPointerArg(1);
        int len = context.getIntArg(2);
        int flags = context.getIntArg(3);
        Pointer dest_addr = context.getPointerArg(4);
        int addrlen = context.getIntArg(5);

        return sendto(emulator, sockfd, buf, len, flags, dest_addr, addrlen);
    }

    private int connect(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        Pointer addr = context.getPointerArg(1);
        int addrlen = context.getIntArg(2);
        return connect(emulator, sockfd, addr, addrlen);
    }

    private int getsockname(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        Pointer addr = context.getPointerArg(1);
        Pointer addrlen = context.getPointerArg(2);
        if (log.isDebugEnabled()) {
            log.debug("getsockname sockfd={}, addr={}, addrlen={}", sockfd, addr, addrlen);
        }
        FileIO file = fdMap.get(sockfd);
        if (file == null) {
            return -UnixEmulator.EBADF;
        }
        return file.getsockname(addr, addrlen);
    }

    private int accept4(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        Pointer addr = context.getPointerArg(1);
        Pointer addrlen = context.getPointerArg(2);
        int flags = context.getIntArg(3);
        return accept(emulator, sockfd, addr, addrlen, flags);
    }

    protected final int accept(Emulator<AndroidFileIO> emulator, int sockfd, Pointer addr, Pointer addrlen, int flags) {
        if (log.isDebugEnabled()) {
            log.debug("accept sockfd={}, addr={}, addrlen={}, flags={}", sockfd, addr, addrlen, flags);
        }

        AndroidFileIO file = fdMap.get(sockfd);
        if (file == null) {
            return -UnixEmulator.EBADF;
        }
        return file.accept(addr, addrlen, flags, this::addFileIO);
    }

    private int getsockopt(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        int level = context.getIntArg(1);
        int optname = context.getIntArg(2);
        Pointer optval = context.getPointerArg(3);
        Pointer optlen = context.getPointerArg(4);
        if (log.isDebugEnabled()) {
            log.debug("getsockopt sockfd={}, level={}, optname={}, optval={}, optlen={}", sockfd, level, optname, optval, optlen);
        }

        FileIO file = fdMap.get(sockfd);
        if (file == null) {
            return -UnixEmulator.EBADF;
        }
        return file.getsockopt(level, optname, optval, optlen);
    }

    private int setsockopt(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int sockfd = context.getIntArg(0);
        int level = context.getIntArg(1);
        int optname = context.getIntArg(2);
        Pointer optval = context.getPointerArg(3);
        int optlen = context.getIntArg(4);
        if (log.isDebugEnabled()) {
            log.debug("setsockopt sockfd={}, level={}, optname={}, optval={}, optlen={}", sockfd, level, optname, optval, optlen);
        }

        FileIO file = fdMap.get(sockfd);
        if (file == null) {
            return -UnixEmulator.EBADF;
        }
        return file.setsockopt(level, optname, optval, optlen);
    }

    private int sdk;

    @Override
    public void addIOResolver(IOResolver<AndroidFileIO> resolver) {
        super.addIOResolver(resolver);

        if (resolver instanceof AndroidResolver) {
            sdk = ((AndroidResolver) resolver).getSdk();
        }
    }

    /**
     * create AF_UNIX local SOCK_STREAM
     */
    protected AndroidFileIO createLocalSocketIO(Emulator<?> emulator, int sdk) {
        return new LocalSocketIO(emulator, sdk);
    }

    private long socketpair(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int domain = context.getIntArg(0);
        int type = context.getIntArg(1) & 0x7ffff;
        int protocol = context.getIntArg(2);
        Pointer sv = context.getPointerArg(3);
        log.debug("socketpair domain={}, type={}, protocol={}, sv={}", domain, type, protocol, sv);

        if (protocol != SocketIO.AF_UNSPEC) {
            throw new UnsupportedOperationException();
        }
        if (domain == SocketIO.AF_LOCAL) {
            switch (type) {
                case SocketIO.SOCK_SEQPACKET: {
                    AndroidFileIO[] pair;
                    try {
                        pair = seqpacketSockets.createPair(emulator);
                    } catch (LinuxErrnoException failure) {
                        return -failure.getErrno();
                    }
                    if ((context.getIntArg(1) & 0x80000) != 0) {
                        pair[0].fcntl(emulator, 2, 1);
                        pair[1].fcntl(emulator, 2, 1);
                    }
                    int fd0 = addFileIO(pair[0]);
                    int fd1 = addFileIO(pair[1]);
                    sv.setInt(0, fd0);
                    sv.setInt(4, fd1);
                    log.debug("event=native_socketpair_mapped guest_fds={},{} socket={}", fd0, fd1, pair[0].getPath());
                    return 0;
                }
                case SocketIO.SOCK_STREAM: {
                    int fd0 = getMinFd();
                    PipedSocketIO one = new PipedSocketIO(emulator);
                    fdMap.put(fd0, one);
                    int fd1 = getMinFd();
                    PipedSocketIO two = new PipedSocketIO(emulator);
                    fdMap.put(fd1, two);
                    one.connectPeer(two);
                    sv.setInt(0, fd0);
                    sv.setInt(4, fd1);
                    return 0;
                }
                default:
                    break;
            }
        }
        throw new UnsupportedOperationException("domain=" + domain + ", type=" + type + ", LR=" + context.getLRPointer());
    }

    private int socket(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int domain = context.getIntArg(0);
        int originalType = context.getIntArg(1);
        int type = originalType & 0x7ffff;
        int protocol = context.getIntArg(2);
        log.debug("socket domain={}, type={}, protocol={}", domain, type, protocol);

        if ((domain == SocketIO.AF_INET || domain == SocketIO.AF_INET6) &&
                (originalType & ~(0x80000 | 0x800)) == SocketIO.SOCK_STREAM) {
            return tcpSocket(emulator, domain, originalType, protocol);
        }

        if (protocol == SocketIO.IPPROTO_ICMP) {
            throw new UnsupportedOperationException();
        }

        int fd;
        switch (domain) {
            case SocketIO.AF_UNSPEC:
                throw new UnsupportedOperationException();
            case SocketIO.AF_LOCAL:
                switch (type) {
                    case SocketIO.SOCK_STREAM:
                        fd = getMinFd();
                        fdMap.put(fd, createLocalSocketIO(emulator, sdk));
                        return fd;
                    case SocketIO.SOCK_DGRAM:
                        fd = getMinFd();
                        fdMap.put(fd, new LocalAndroidUdpSocket(emulator));
                        return fd;
                    default:
                        return -UnixEmulator.EACCES;
                }
            case SocketIO.AF_INET:
            case SocketIO.AF_INET6:
                switch (type) {
                    case SocketIO.SOCK_STREAM:
                        return tcpSocket(emulator, domain, originalType, protocol);
                    case SocketIO.SOCK_DGRAM:
                        fd = getMinFd();
                        fdMap.put(fd, new UdpSocket(emulator));
                        return fd;
                    case SocketIO.SOCK_RAW:
                        throw new UnsupportedOperationException();
                }
                break;
            case SocketIO.AF_NETLINK:
                switch (type) {
                    case SocketIO.SOCK_DGRAM:
                        fd = getMinFd();
                        fdMap.put(fd, new NetLinkSocket(emulator));
                        return fd;
                    case SocketIO.SOCK_RAW:
                    default:
                        throw new UnsupportedOperationException();
                }
        }
        log.info("socket domain={}, type={}, protocol={}", domain, type, protocol);
        return -UnixEmulator.EAFNOSUPPORT;
    }

    protected int uname(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer buf = context.getPointerArg(0);
        if (log.isDebugEnabled()) {
            log.debug("uname buf={}", buf);
        }

        final int SYS_NMLN = 65;
        KernelIdentity identity = emulator.getKernelIdentity();

        Pointer sysName = buf.share(0);
        sysName.setString(0, KernelIdentity.SYSNAME); /* Operating system name (e.g., "Linux") */

        Pointer nodeName = sysName.share(SYS_NMLN);
        nodeName.setString(0, identity.nodename()); /* Name within "some implementation-defined network" */

        Pointer release = nodeName.share(SYS_NMLN);
        release.setString(0, identity.release()); /* Operating system release (e.g., "2.6.28") */

        Pointer version = release.share(SYS_NMLN);
        version.setString(0, identity.version()); /* Operating system version */

        Pointer machine = version.share(SYS_NMLN);
        machine.setString(0, identity.machine()); /* Hardware identifier */

        Pointer domainName = machine.share(SYS_NMLN);
        domainName.setString(0, identity.domainname()); /* NIS or YP domain name */

        return 0;
    }

    private int getppid(Emulator<AndroidFileIO> emulator) {
        if (log.isDebugEnabled()) {
            log.debug("getppid");
        }
        return emulator.getPid();
    }

    private void exit_group(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int status = context.getIntArg(0);
        if (log.isDebugEnabled()) {
            log.debug("exit with code: {}", status, new Exception("exit_group status=" + status));
        } else {
            System.out.println("exit with code: " + status);
        }
        if (LoggerFactory.getLogger(AbstractEmulator.class).isDebugEnabled()) {
            createBreaker(emulator).debug("exit_group status=" + status);
        }
        emulator.getBackend().emu_stop();
    }

    private int munmap(Backend backend, Emulator<?> emulator) {
        long timeInMillis = System.currentTimeMillis();
        long start = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).longValue();
        int length = backend.reg_read(Arm64Const.UC_ARM64_REG_X1).intValue();
        int result = emulator.getMemory().munmap(start, length);
        if (log.isDebugEnabled()) {
            log.debug("munmap start=0x{}, length={}, offset={}, result={}", Long.toHexString(start), length, System.currentTimeMillis() - timeInMillis, result);
        }
        return result < 0 ? result : 0;
    }

    private long mremap(Emulator<?> emulator) {
        Arm64RegisterContext context = emulator.getContext();
        UnidbgPointer old_address = context.getXPointer(0);
        int old_size = context.getXInt(1);
        int new_size = context.getXInt(2);
        int flags = context.getXInt(3);
        UnidbgPointer new_address = context.getXPointer(4);
        if (log.isDebugEnabled()) {
            log.debug("mremap old_address={}, old_size={}, new_size={}, flags={}, new_address={}", old_address, old_size, new_size, flags, new_address);
        }
        return remapAnonymous(emulator, UnidbgPointer.nativeValue(old_address), old_size, new_size,
                flags, UnidbgPointer.nativeValue(new_address));
    }

    private static final int PR_GET_SECCOMP = 21;
    private static final int PR_SET_NAME = 15;
    private static final int PR_SET_NO_NEW_PRIVS = 38;
    private static final int PR_GET_NO_NEW_PRIVS = 39;
    private static final int PR_SET_THP_DISABLE = 41;
    /** SECCOMP_MODE_FILTER: the mode zygote leaves behind. */
    private static final int SECCOMP_MODE_FILTER = 2;
    private static final int NO_NEW_PRIVS_SET = 1;
    private static final int PR_SET_PTRACER = 0x59616d61;

    private int prctl(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int option = context.getIntArg(0);
        long arg2 = context.getLongArg(1);
        if (log.isDebugEnabled()) {
            log.debug("prctl option=0x{}, arg2=0x{}, task={}", Integer.toHexString(option), Long.toHexString(arg2), emulator.getThreadDispatcher().getRunningTask());
        }
        switch (option) {
            case PR_SET_NAME:
                Pointer threadName = context.getPointerArg(1);
                if (log.isDebugEnabled()) {
                    log.debug("prctl set thread name: {}", threadName.getString(0));
                }
                return 0;
            case AnonymousName.PR_SET_VMA: {
                long address = context.getLongArg(2);
                long length = context.getLongArg(3);
                return AnonymousName.apply(emulator, arg2, address, length,
                        context.getLongArg(4));
            }
            case PR_SET_PTRACER:
                int pid = (int) arg2;
                if (log.isDebugEnabled()) {
                    log.debug("prctl set ptracer: {}", pid);
                }
                return 0;
            case PR_SET_NO_NEW_PRIVS:
            case PR_SET_THP_DISABLE:
                return 0;
            case PR_GET_NO_NEW_PRIVS:
                // zygote sets no_new_privs on an app process before it installs the filter,
                // so every app process reports the flag set. A shell process is not filtered
                // and reports it unset; the emulated process is an app process.
                return NO_NEW_PRIVS_SET;
            case PR_GET_SECCOMP:
                // zygote installs a seccomp filter in every app process and leaves it there.
                // The emulation installs no filter, but the query answers what the platform
                // leaves in the process, not what the emulator itself runs under.
                return SECCOMP_MODE_FILTER;
            default:
                // An option this handler does not model gets the answer the kernel gives an
                // operation it does not know, so the guest receives -1 with errno ENOSYS
                // instead of the emulation aborting.
                return -UnixEmulator.ENOSYS;
        }
    }

    protected int clock_gettime(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int clk_id = context.getIntArg(0);
        Pointer tp = context.getPointerArg(1);
        if (!ClockSource.isModeled(clk_id)) {
            log.debug("clock_gettime clk_id={} is not a clock of the process", clk_id);
            return -UnixEmulator.EINVAL;
        }
        long offset = ClockSource.clockNanos(emulator, clk_id);
        long tv_sec = offset / 1000000000L;
        long tv_nsec = offset % 1000000000L;
        if (log.isDebugEnabled()) {
            log.debug("clock_gettime clk_id={}, tp={}, offset={}, tv_sec={}, tv_nsec={}", clk_id, tp, offset, tv_sec, tv_nsec);
        }
        tp.setLong(0, tv_sec);
        tp.setLong(8, tv_nsec);
        return 0;
    }

    protected int clock_getres(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int clk_id = context.getIntArg(0);
        Pointer res = context.getPointerArg(1);
        if (!ClockSource.isModeled(clk_id)) {
            log.debug("clock_getres clk_id={} is not a clock of the process", clk_id);
            return -UnixEmulator.EINVAL;
        }
        long resolution = ClockSource.resolutionNanos(clk_id);
        if (log.isDebugEnabled()) {
            log.debug("clock_getres clk_id={}, res={}, resolution={}", clk_id, res, resolution);
        }
        res.setLong(0, resolution / 1_000_000_000L);
        res.setLong(8, resolution % 1_000_000_000L);
        return 0;
    }

    protected long ptrace(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        return kernel.ptrace(emulator, context.getIntArg(0), context.getIntArg(1));
    }

    private int fcntl(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        int cmd = context.getIntArg(1);
        int arg = context.getIntArg(2);
        return fcntl(emulator, fd, cmd, arg);
    }

    private int writev(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        Pointer iov = context.getPointerArg(1);
        int iovcnt = context.getIntArg(2);
        if (log.isDebugEnabled()) {
            for (int i = 0; i < iovcnt; i++) {
                Pointer iov_base = iov.getPointer(i * 16L);
                long iov_len = iov.getLong(i * 16L + 8);
                byte[] data = iov_base.getByteArray(0, (int) iov_len);
                Inspector.inspect(data, "writev fd=" + fd + ", iov=" + iov + ", iov_base=" + iov_base);
            }
        }

        FileIO file = fdMap.get(fd);
        if (file == null) {
            return -UnixEmulator.EBADF;
        }

        int count = 0;
        for (int i = 0; i < iovcnt; i++) {
            Pointer iov_base = iov.getPointer(i * 16L);
            long iov_len = iov.getLong(i * 16L + 8);
            byte[] data = iov_base.getByteArray(0, (int) iov_len);
            count += file.write(data);
        }
        return count;
    }

    private long brk(Backend backend, Emulator<?> emulator) {
        long address = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).longValue();
        if (log.isDebugEnabled()) {
            log.debug("brk address=0x{}", Long.toHexString(address));
        }
        return emulator.getMemory().brk(address);
    }

    private int mprotect(Backend backend, Emulator<?> emulator) {
        long address = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).longValue();
        int length = backend.reg_read(Arm64Const.UC_ARM64_REG_X1).intValue();
        int prot = backend.reg_read(Arm64Const.UC_ARM64_REG_X2).intValue();
        long pageAlign = emulator.getPageAlign();
        long alignedAddress = address / pageAlign * pageAlign;
        long offset = address - alignedAddress;

        long alignedLength = ARM.alignSize(length + offset, emulator.getPageAlign());
        if (log.isDebugEnabled()) {
            log.debug("mprotect address=0x{}, alignedAddress=0x{}, offset={}, length={}, alignedLength={}, prot=0x{}", Long.toHexString(address), Long.toHexString(alignedAddress), offset, length, alignedLength, Integer.toHexString(prot));
        }
        return emulator.getMemory().mprotect(alignedAddress, (int) alignedLength, prot);
    }

    private long mmap(Backend backend, Emulator<?> emulator) {
        long start = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).longValue();
        int length = backend.reg_read(Arm64Const.UC_ARM64_REG_X1).intValue();
        int prot = backend.reg_read(Arm64Const.UC_ARM64_REG_X2).intValue();
        int flags = backend.reg_read(Arm64Const.UC_ARM64_REG_X3).intValue();
        int fd = backend.reg_read(Arm64Const.UC_ARM64_REG_X4).intValue();
        long offset = backend.reg_read(Arm64Const.UC_ARM64_REG_X5).longValue();
        if (offset < 0 || offset % emulator.getPageAlign() != 0 ||
                ((flags & 0xf) != AndroidElfLoader.MAP_SHARED && (flags & 0xf) != AndroidElfLoader.MAP_PRIVATE)) {
            return -UnixEmulator.EINVAL;
        }

        boolean warning = length > 0x10000000;
        if (log.isDebugEnabled() || warning) {
            String msg = "mmap start=0x" + Long.toHexString(start) + ", length=" + length + ", prot=0x" + Integer.toHexString(prot) + ", flags=0x" + Integer.toHexString(flags) + ", fd=" + fd + ", offset=" + offset;
            if (warning) {
                log.warn(msg);
                if (log.isTraceEnabled()) {
                    emulator.attach().debug("mmap warning");
                }
            } else {
                log.debug(msg);
            }
        }
        long mapped = emulator.getMemory().mmap2(start, length, prot, flags, fd, offset);
        if (log.isDebugEnabled()) {
            log.debug("mmap start=0x{}, mapped=0x{}, length=0x{}, prot=0x{}, flags=0x{}, fd={}, offset={}, task={}", Long.toHexString(start), Long.toHexString(mapped), Integer.toHexString(length), Integer.toHexString(prot), Integer.toHexString(flags), fd, offset, emulator.getThreadDispatcher().getRunningTask());
        }
        return mapped;
    }

    private int gettimeofday(Emulator<?> emulator) {
        Pointer tv = UnidbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X0);
        Pointer tz = UnidbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X1);
        return gettimeofday64(emulator, tv, tz);
    }

    private int faccessat(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        return accessAt(emulator, context.getIntArg(0), context.getPointerArg(1).getString(0), context.getIntArg(2));
    }

    private int fstatat64(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        return statAt(emulator, context.getIntArg(0), context.getPointerArg(1).getString(0),
                context.getIntArg(3), context.getPointerArg(2));
    }

    private int openat(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname_p = context.getPointerArg(1);
        int oflags = context.getIntArg(2);
        int mode = context.getIntArg(3);
        String pathname = pathname_p.getString(0);
        log.debug("openat dirfd={}, pathname={}, oflags=0x{}, mode={}", dirfd, pathname, Integer.toHexString(oflags), mode);
        if (pathname.isEmpty()) {
            return -UnixEmulator.ENOENT;
        }
        try {
            pathname = queryPath(emulator, dirfd, pathname);
        } catch (LinuxErrnoException failure) {
            log.debug("event=openat_rejected dirfd={} path={} errno={}", dirfd, pathname, failure.getErrno());
            return -failure.getErrno();
        }
        if ("/data/misc/zoneinfo/current/tzdata".equals(pathname) || "/dev/pmsg0".equals(pathname)) {
            return -UnixEmulator.ENOENT;
        }
        int result = open(emulator, pathname, oflags);
        if (result < 0) {
            log.debug("openat failed errno={}", -result);
        }
        return result;
    }

    private int lseek(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        int offset = context.getIntArg(1);
        int whence = context.getIntArg(2);
        FileIO file = fdMap.get(fd);
        if (file == null) {
            return -UnixEmulator.EBADF;
        }
        int pos = file.lseek(offset, whence);
        if (log.isDebugEnabled()) {
            log.debug("lseek fd={}, offset={}, whence={}, pos={}", fd, offset, whence, pos);
        }
        return pos;
    }

    private int close(Backend backend, Emulator<?> emulator) {
        int fd = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).intValue();
        if (log.isDebugEnabled()) {
            log.debug("close fd={}", fd);
        }

        return close(emulator, fd);
    }

    private int getdents64(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        UnidbgPointer dirp = context.getPointerArg(1);
        int size = context.getIntArg(2);
        if (log.isDebugEnabled()) {
            log.debug("getdents64 fd={}, dirp={}, size={}", fd, dirp, size);
        }

        AndroidFileIO io = fdMap.get(fd);
        if (io == null) {
            return -UnixEmulator.EBADF;
        } else {
            dirp.setSize(size);
            return io.getdents64(dirp, size);
        }
    }

    private int readlinkat(Emulator<AndroidFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        int dirfd = context.getIntArg(0);
        Pointer pathname = context.getPointerArg(1);
        Pointer buf = context.getPointerArg(2);
        int bufSize = context.getIntArg(3);
        String path = pathname.getString(0);
        if (dirfd != IO.AT_FDCWD) {
            throw new BackendException();
        }
        return readlink(emulator, path, buf, bufSize);
    }

    private int fstat(Backend backend, Emulator<?> emulator) {
        int fd = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).intValue();
        Pointer stat = UnidbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X1);
        return fstat(emulator, fd, stat);
    }

    protected int fstat(Emulator<?> emulator, int fd, Pointer stat) {
        AndroidFileIO file = fdMap.get(fd);
        if (file == null) {
            if (log.isDebugEnabled()) {
                log.debug("fstat fd={}, stat={}, errno=" + UnixEmulator.EBADF, fd, stat);
            }

            return -UnixEmulator.EBADF;
        }
        if (log.isDebugEnabled()) {
            log.debug("fstat file={}, stat={}, from={}", file, stat, emulator.getContext().getLRPointer());
        }
        return file.fstat(emulator, new Stat64(stat));
    }

    private int ioctl(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        long request = context.getLongArg(1);
        long argp = context.getLongArg(2);
        if (log.isDebugEnabled()) {
            log.debug("ioctl fd={}, request=0x{}, argp=0x{}", fd, Long.toHexString(request), Long.toHexString(argp));
        }

        FileIO file = fdMap.get(fd);
        if (file == null) {
            return -UnixEmulator.EBADF;
        }
        return file.ioctl(emulator, request, argp);
    }

    private int write(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        int fd = context.getIntArg(0);
        Pointer buffer = context.getPointerArg(1);
        int count = context.getIntArg(2);
        return write(emulator, fd, buffer, count);
    }

    private int read(Backend backend, Emulator<?> emulator) {
        int fd = backend.reg_read(Arm64Const.UC_ARM64_REG_X0).intValue();
        Pointer buffer = UnidbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_X1);
        int count = backend.reg_read(Arm64Const.UC_ARM64_REG_X2).intValue();
        return read(emulator, fd, buffer, count);
    }

    private int dup(Emulator<?> emulator) {
        return duplicate(emulator, emulator.getContext().getIntArg(0));
    }

    private int dup3(Emulator<?> emulator) {
        RegisterContext context = emulator.getContext();
        return duplicateTo(emulator, context.getIntArg(0), context.getIntArg(1), context.getIntArg(2), true);
    }

    @Override
    protected AndroidFileIO createByteArrayFileIO(String pathname, int oflags, byte[] data) {
        return new ByteArrayFileIO(oflags, pathname, data);
    }

    @Override
    protected AndroidFileIO createDriverFileIO(Emulator<?> emulator, int oflags, String pathname) {
        return DriverFileIO.create(emulator, oflags, pathname);
    }
}
