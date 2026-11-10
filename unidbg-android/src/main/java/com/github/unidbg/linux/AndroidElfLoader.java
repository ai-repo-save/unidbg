package com.github.unidbg.linux;

import com.github.unidbg.*;
import com.github.unidbg.Module;
import com.github.unidbg.arm.ARM;
import com.github.unidbg.arm.ARMEmulator;
import com.github.unidbg.file.FileIO;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.file.linux.IOConstants;
import com.github.unidbg.hook.HookListener;
import com.github.unidbg.linux.android.ElfLibraryFile;
import com.github.unidbg.linux.thread.PThreadInternal;
import com.github.unidbg.memory.MemRegion;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryAllocBlock;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.memory.MemoryBlockImpl;
import com.github.unidbg.memory.MemoryMap;
import com.github.unidbg.memory.mapping.FileMappingManager;
import com.github.unidbg.memory.mapping.HostMappingException;
import com.github.unidbg.memory.mapping.MappingSource;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.spi.*;
import com.github.unidbg.thread.Task;
import com.github.unidbg.unix.IO;
import com.github.unidbg.unix.UnixSyscallHandler;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.kernel.KernelIdentity;
import com.github.unidbg.virtualmodule.VirtualSymbol;
import com.sun.jna.Pointer;
import net.fornwall.jelf.ArmExIdx;
import net.fornwall.jelf.ElfDynamicStructure;
import net.fornwall.jelf.ElfException;
import net.fornwall.jelf.ElfFile;
import net.fornwall.jelf.ElfRelocation;
import net.fornwall.jelf.ElfSection;
import net.fornwall.jelf.ElfSegment;
import net.fornwall.jelf.ElfSymbol;
import net.fornwall.jelf.GnuEhFrameHeader;
import net.fornwall.jelf.MemoizedObject;
import net.fornwall.jelf.PtLoadData;
import net.fornwall.jelf.SymbolLocator;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;
import unicorn.Unicorn;
import unicorn.UnicornConst;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AndroidElfLoader extends AbstractLoader<AndroidFileIO> implements Memory, Loader {

    private static final Logger log = LoggerFactory.getLogger(AndroidElfLoader.class);
    private static final Logger symbolLog = LoggerFactory.getLogger(AndroidElfLoader.class.getName() + ".symbols");

    private Symbol malloc, free;

    public AndroidElfLoader(Emulator<AndroidFileIO> emulator, UnixSyscallHandler<AndroidFileIO> syscallHandler) {
        super(emulator, syscallHandler);

        // init stack
        stackSize = STACK_SIZE_OF_PAGE * emulator.getPageAlign();
        backend.mem_map(STACK_BASE - stackSize, stackSize, UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_WRITE);

        setStackPoint(STACK_BASE);
        this.environ = initializeTLS(new String[] {
                "ANDROID_DATA=/data",
                "ANDROID_ROOT=/system",
                "PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin",
                "NO_ADDR_COMPAT_LAYOUT_FIXUP=1"
        });
        this.setErrno(0);
    }

    @Override
    public void setLibraryResolver(LibraryResolver libraryResolver) {
        super.setLibraryResolver(libraryResolver);

        /*
         * 注意打开顺序很重要
         */
        syscallHandler.open(emulator, IO.STDIN, IOConstants.O_RDONLY);
        syscallHandler.open(emulator, IO.STDOUT, IOConstants.O_WRONLY);
        syscallHandler.open(emulator, IO.STDERR, IOConstants.O_WRONLY);
    }

    @Override
    protected LibraryFile createLibraryFile(File file) {
        return new ElfLibraryFile(file, emulator.is64Bit());
    }

    /**
     * Keeps the bionic thread identity in step with the process id: the guest reads
     * {@code gettid()} from the thread structure in its thread-local storage, so that value
     * follows the id the runtime runs as rather than the one it started with.
     */
    public void processIdChanged(int pid) {
        UnidbgPointer tls = UnidbgPointer.register(emulator,
                emulator.is32Bit() ? ArmConst.UC_ARM_REG_C13_C0_3 : Arm64Const.UC_ARM64_REG_TPIDR_EL0);
        if (tls == null) return;
        UnidbgPointer thread = tls.getPointer(emulator.getPointerSize());
        if (thread == null) return;
        PThreadInternal internal = PThreadInternal.create(emulator, thread);
        internal.unpack();
        internal.tid = pid;
        internal.pack();
    }

    /** The {@code AT_HWCAP} and {@code AT_HWCAP2} entries of the auxiliary vector. */
    private static final int AT_HWCAP = 16;
    private static final int AT_HWCAP2 = 26;
    private static final int AT_NULL = 0;
    private static final int AT_RANDOM = 25; // AT_RANDOM is a pointer to 16 bytes of randomness on the stack.
    private static final int AT_PAGESZ = 6;

    /** The auxiliary vector the guest's libc reads, kept so a stated identity reaches it. */
    private UnidbgPointer auxv;

    /**
     * The auxiliary vector the guest's libc reads: the pointer to the random bytes, the page size
     * and the capability words of the machine. A word the kernel did not publish has no entry, so
     * {@code getauxval} answers it with {@code ENOENT} instead of a zero value; the vector ends
     * where the first zero type is written.
     */
    private void writeCapabilityWords(KernelIdentity identity) {
        final int pointerSize = emulator.getPointerSize();
        long offset = pointerSize * 4L;
        if (identity.hwcap() != null) {
            auxv.setPointer(offset, UnidbgPointer.pointer(emulator, AT_HWCAP));
            auxv.setPointer(offset += pointerSize, UnidbgPointer.pointer(emulator, identity.hwcap()));
            offset += pointerSize;
        }
        if (identity.hwcap2() != null) {
            auxv.setPointer(offset, UnidbgPointer.pointer(emulator, AT_HWCAP2));
            auxv.setPointer(offset += pointerSize, UnidbgPointer.pointer(emulator, identity.hwcap2()));
            offset += pointerSize;
        }
        auxv.setPointer(offset, UnidbgPointer.pointer(emulator, AT_NULL));
    }

    /** Rewrites the capability words once the machine's identity is stated. */
    public void kernelIdentityChanged(KernelIdentity identity) {
        writeCapabilityWords(identity);
    }

    private UnidbgPointer initializeTLS(String[] envs) {
        final Pointer thread = allocateStack(0x400); // reserve space for pthread_internal_t
        PThreadInternal pThread = PThreadInternal.create(emulator, thread);
        pThread.tid = emulator.getPid();
        pThread.pack();

        final Pointer __stack_chk_guard = allocateStack(emulator.getPointerSize());

        final Pointer programName = writeStackString(emulator.getProcessName());

        final Pointer programNamePointer = allocateStack(emulator.getPointerSize());
        assert programNamePointer != null;
        programNamePointer.setPointer(0, programName);

        final UnidbgPointer auxv = allocateStack(0x100);
        assert auxv != null;
        this.auxv = auxv;
        auxv.setPointer(0, UnidbgPointer.pointer(emulator, AT_RANDOM));
        auxv.setPointer(emulator.getPointerSize(), __stack_chk_guard);
        auxv.setPointer(emulator.getPointerSize() * 2L, UnidbgPointer.pointer(emulator, AT_PAGESZ));
        auxv.setPointer(emulator.getPointerSize() * 3L, UnidbgPointer.pointer(emulator, ARMEmulator.PAGE_ALIGN));
        writeCapabilityWords(emulator.getKernelIdentity());

        List<String> envList = new ArrayList<>();
        for (String env : envs) {
            int index = env.indexOf('=');
            if (index != -1) {
                envList.add(env);
            }
        }
        final Pointer environ = allocateStack(emulator.getPointerSize() * (envList.size() + 1));
        assert environ != null;
        Pointer pointer = environ;
        for (String env : envList) {
            Pointer envPointer = writeStackString(env);
            pointer.setPointer(0, envPointer);
            pointer = pointer.share(emulator.getPointerSize());
        }
        pointer.setPointer(0, null);

        final UnidbgPointer argv = allocateStack(0x100);
        assert argv != null;
        argv.setPointer(emulator.getPointerSize(), programNamePointer);
        argv.setPointer(2L * emulator.getPointerSize(), environ);
        argv.setPointer(3L * emulator.getPointerSize(), auxv);

        final UnidbgPointer tls = allocateStack(0x80 * 4); // tls size
        assert tls != null;
        tls.setPointer(emulator.getPointerSize(), thread);
        this.errno = tls.share(emulator.getPointerSize() * 2L);
        tls.setPointer(emulator.getPointerSize() * 3L, argv);

        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_C13_C0_3, tls.peer);
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_TPIDR_EL0, tls.peer);
        }

        long sp = getStackPoint();
        sp &= (~(emulator.is64Bit() ? 15 : 7));
        setStackPoint(sp);

        if (log.isDebugEnabled()) {
            log.debug("initializeTLS tls={}, argv={}, auxv={}, thread={}, environ={}, sp=0x{}", tls, argv, auxv, thread, environ, Long.toHexString(getStackPoint()));
        }
        return argv.share(2L * emulator.getPointerSize(), 0);
    }

    private final Map<String, LinuxModule> modules = new LinkedHashMap<>();

    protected final LinuxModule loadInternal(LibraryFile libraryFile, boolean forceCallInit) {
        try {
            LinuxModule module = loadInternal(libraryFile);
            resolveSymbols(!forceCallInit);
            if (callInitFunction || forceCallInit) {
                for (LinuxModule m : modules.values().toArray(new LinuxModule[0])) {
                    boolean forceCall = (forceCallInit && m == module) || m.isForceCallInit();
                    if (callInitFunction) {
                        m.callInitFunction(emulator, forceCall);
                    } else if (forceCall) {
                        m.callInitFunction(emulator, true);
                    }
                    m.initFunctionList.clear();
                }
            }
            module.addReferenceCount();
            return module;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void resolveSymbols(boolean showWarning) throws IOException {
        Collection<LinuxModule> linuxModules = modules.values();
        for (LinuxModule m : linuxModules) {
            for (Iterator<ModuleSymbol> iterator = m.getUnresolvedSymbol().iterator(); iterator.hasNext(); ) {
                ModuleSymbol moduleSymbol = iterator.next();
                ModuleSymbol resolved = moduleSymbol.resolve(new HashSet<>(linuxModules), true, hookListeners, emulator.getSvcMemory());
                if (resolved != null) {
                    log.debug("resolveSymbols[{}]{} symbol resolved to {}", moduleSymbol.soName, moduleSymbol.symbol.getName(), resolved.toSoName);
                    resolved.relocation(emulator, m);
                    iterator.remove();
                } else if(showWarning) {
                    log.info("[{}]symbol {} is missing relocationAddr={}, offset=0x{}", moduleSymbol.soName, moduleSymbol.symbol, moduleSymbol.relocationAddr, Long.toHexString(moduleSymbol.offset));
                }
            }
        }
    }

    @Override
    public Module dlopen(String filename, boolean calInit) {
        LinuxModule loaded = modules.get(FilenameUtils.getName(filename));
        if (loaded != null) {
            loaded.addReferenceCount();
            return loaded;
        }

        for (Module module : getLoadedModules()) {
            for (MemRegion memRegion : module.getRegions()) {
                if (filename.equals(memRegion.getName())) {
                    module.addReferenceCount();
                    return module;
                }
            }
        }

        LibraryFile file = libraryResolver == null ? null : libraryResolver.resolveLibrary(emulator, filename);
        if (file == null) {
            return null;
        }

        if (calInit) {
            return loadInternal(file, false);
        }

        try {
            LinuxModule module = loadInternal(file);
            resolveSymbols(false);
            if (!callInitFunction) { // No need call init array
                for (LinuxModule m : modules.values()) {
                    m.initFunctionList.clear();
                }
            }
            module.addReferenceCount();
            return module;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * dlopen调用init_array会崩溃
     */
    @Override
    public Module dlopen(String filename) {
        return dlopen(filename, true);
    }

    private final UnidbgPointer environ;

    private static final long RTLD_NEXT_LP64 = -1L;
    private static final long RTLD_DEFAULT_LP32 = 0xffffffffL;
    private static final long RTLD_NEXT_LP32 = 0xfffffffeL;

    @Override
    public Symbol dlsym(long handle, String symbolName) {
        String caller = symbolLog.isDebugEnabled() ? String.valueOf(emulator.getContext().getLRPointer()) : null;
        if ("environ".equals(symbolName)) {
            return logDlsymResult(handle, symbolName, new VirtualSymbol(symbolName, null, environ.toUIntPeer()), caller, "environ");
        }
        Module sm = null;
        Symbol ret = null;
        String source = "unresolved";
        if (isRtldNext(handle)) {
            UnidbgPointer lr = emulator.getContext().getLRPointer();
            Module callerModule = lr == null ? null : findModuleByAddress(lr.peer);
            boolean afterCaller = false;
            if (callerModule != null) {
                for (LinuxModule module : modules.values()) {
                    if (!afterCaller) {
                        if (module == callerModule) {
                            afterCaller = true;
                        }
                        continue;
                    }
                    Symbol symbol = module.findSymbolByName(symbolName, false);
                    if (symbol != null) {
                        ret = symbol;
                        sm = module;
                        break;
                    }
                }
            }
            if (ret != null) {
                source = "rtld_next";
            } else {
                for (Module module : modules.values()) {
                    if (module == callerModule) {
                        continue;
                    }
                    Symbol symbol = module.findSymbolByName(symbolName, false);
                    if (symbol != null) {
                        ret = symbol;
                        sm = module;
                        source = "rtld_next_global";
                        break;
                    }
                }
                if (ret == null && (callerModule == null || !afterCaller)) {
                    source = "rtld_next_no_caller";
                }
            }
        } else {
            for (LinuxModule module : modules.values()) {
                if (module.base == handle) { // virtual module may have same base address
                    Symbol symbol = module.findSymbolByName(symbolName, false);
                    if (symbol != null) {
                        ret = symbol;
                        sm = module;
                        source = "module";
                        break;
                    }
                }
            }
            if (ret == null && isRtldDefault(handle)) {
                for (Module module : modules.values()) {
                    Symbol symbol = module.findSymbolByName(symbolName, false);
                    if (symbol != null) {
                        ret = symbol;
                        sm = module;
                        source = "module";
                        break;
                    }
                }
            }
        }
        for (HookListener listener : hookListeners) {
            long hook = listener.hook(emulator.getSvcMemory(), sm == null ? null : sm.name, symbolName, ret == null ? 0L : ret.getAddress());
            if (hook != 0) {
                if (sm != null) {
                    ((LinuxModule) sm).recordHookSymbol(symbolName, hook);
                }
                return logDlsymResult(handle, symbolName, new VirtualSymbol(symbolName, sm, hook), caller, "hook");
            }
        }
        return logDlsymResult(handle, symbolName, ret, caller, source);
    }

    public Module findModuleForDladdr(long address) {
        for (LinuxModule module : modules.values()) {
            if (module.findHookSymbolByAddress(address) != null) {
                return module;
            }
        }
        return findModuleByAddress(address);
    }

    private boolean isRtldNext(long handle) {
        if (emulator.is64Bit()) {
            return handle == RTLD_NEXT_LP64;
        }
        return (handle & 0xffffffffL) == RTLD_NEXT_LP32;
    }

    private boolean isRtldDefault(long handle) {
        if (handle == 0L) {
            return true;
        }
        return !emulator.is64Bit() && (handle & 0xffffffffL) == RTLD_DEFAULT_LP32;
    }

    private Symbol logDlsymResult(long handle, String symbolName, Symbol result, String caller, String source) {
        if (symbolLog.isDebugEnabled()) {
            symbolLog.debug("event=dlsym_result emulator_id={} guest_pid={} handle=0x{} symbol={} address=0x{} caller_lr={} source={}",
                    Integer.toHexString(System.identityHashCode(emulator)), emulator.getPid(), Long.toHexString(handle),
                    symbolName, Long.toHexString(result == null ? 0L : result.getAddress()), caller, source);
        }
        return result;
    }

    @Override
    public boolean dlclose(long handle) {
        for (Iterator<Map.Entry<String, LinuxModule>> iterator = modules.entrySet().iterator(); iterator.hasNext(); ) {
            LinuxModule module = iterator.next().getValue();
            if (module.base == handle) {
                if (module.decrementReferenceCount() <= 0) {
                    module.unload(backend);
                    iterator.remove();
                }
                return true;
            }
        }
        return false;
    }

    private LinuxModule loadInternal(LibraryFile libraryFile) throws IOException {
        final ElfFile elfFile = ElfFile.fromBuffer(libraryFile.mapBuffer());

        if (emulator.is32Bit() && elfFile.objectSize != ElfFile.CLASS_32) {
            throw new ElfException("Must be 32-bit");
        }
        if (emulator.is64Bit() && elfFile.objectSize != ElfFile.CLASS_64) {
            throw new ElfException("Must be 64-bit");
        }

        if (elfFile.encoding != ElfFile.DATA_LSB) {
            throw new ElfException("Must be LSB");
        }

        if (emulator.is32Bit() && elfFile.arch != ElfFile.ARCH_ARM) {
            throw new ElfException("Must be ARM arch.");
        }

        if (emulator.is64Bit() && elfFile.arch != ElfFile.ARCH_AARCH64) {
            throw new ElfException("Must be ARM64 arch.");
        }

        long start = System.currentTimeMillis();
        long bound_high = 0;
        long align = 0;
        for (int i = 0; i < elfFile.num_ph; i++) {
            ElfSegment ph = elfFile.getProgramHeader(i);
            if (ph.type == ElfSegment.PT_LOAD && ph.mem_size > 0) {
                long high = ph.virtual_address + ph.mem_size;

                if (bound_high < high) {
                    bound_high = high;
                }
                if (ph.alignment > align) {
                    align = ph.alignment;
                }
            }
        }

        ElfDynamicStructure dynamicStructure = null;

        final long baseAlign = Math.max(emulator.getPageAlign(), align);
        final long load_base = ((mmapBaseAddress - 1) / baseAlign + 1) * baseAlign;
        long load_virtual_address = 0;
        long size = ARM.align(0, bound_high, baseAlign).size;
        setMMapBaseAddress(load_base + size);

        final List<MemRegion> regions = new ArrayList<>(5);
        MemoizedObject<ArmExIdx> armExIdx = null;
        MemoizedObject<GnuEhFrameHeader> ehFrameHeader = null;
        Alignment lastAlignment = null;
        for (int i = 0; i < elfFile.num_ph; i++) {
            ElfSegment ph = elfFile.getProgramHeader(i);
            switch (ph.type) {
                case ElfSegment.PT_LOAD:
                    int prot = get_segment_protection(ph.flags);
                    if (prot == UnicornConst.UC_PROT_NONE) {
                        prot = UnicornConst.UC_PROT_ALL;
                    }

                    final long begin = load_base + ph.virtual_address;
                    if (load_virtual_address == 0) {
                        load_virtual_address = begin;
                    }

                    Alignment check = ARM.align(begin, ph.mem_size, Math.max(emulator.getPageAlign(), ph.alignment));
                    final int regionSize = regions.size();
                    MemRegion last = regionSize == 0 ? null : regions.get(regionSize - 1);
                    MemRegion overall = null;
                    if (last != null && check.address >= last.begin && check.address < last.end) {
                        overall = last;
                    }
                    if (overall != null) {
                        long overallSize = overall.end - check.address;
                        int perms = overall.perms | prot;
                        if (mMapListener != null) {
                            perms = mMapListener.onProtect(check.address, overallSize, perms);
                        }
                        backend.mem_protect(check.address, overallSize, perms);
                        if (ph.mem_size > overallSize) {
                            Alignment alignment = this.mem_map(begin + overallSize, ph.mem_size - overallSize, prot, libraryFile.getName(), Math.max(emulator.getPageAlign(), ph.alignment));
                            regions.add(new MemRegion(begin, alignment.address, alignment.address + alignment.size, prot, libraryFile, ph.virtual_address));
                            if (lastAlignment != null && lastAlignment.begin + lastAlignment.dataSize > begin) {
                                throw new UnsupportedOperationException();
                            }
                            lastAlignment = alignment;
                            lastAlignment.begin = begin;
                        }
                    } else {
                        Alignment alignment = this.mem_map(begin, ph.mem_size, prot, libraryFile.getName(), Math.max(emulator.getPageAlign(), ph.alignment));
                        regions.add(new MemRegion(begin, alignment.address, alignment.address + alignment.size, prot, libraryFile, ph.virtual_address));
                        if (lastAlignment != null) {
                            long base = lastAlignment.address + lastAlignment.size;
                            long off = alignment.address - base;
                            if (off < 0) {
                                throw new IllegalStateException();
                            }
                            if (off > 0) {
                                backend.mem_map(base, off, UnicornConst.UC_PROT_NONE);
                                if (mMapListener != null) {
                                    mMapListener.onMap(base, off, UnicornConst.UC_PROT_NONE);
                                }
                                if (memoryMap.put(base, new MemoryMap(base, off, UnicornConst.UC_PROT_NONE)) != null) {
                                    log.warn("mem_map replace exists memory map base={}", Long.toHexString(base));
                                }
                            }
                        }
                        lastAlignment = alignment;
                        lastAlignment.begin = begin;
                    }

                    PtLoadData loadData = ph.getPtLoadData();
                    loadData.writeTo(pointer(begin));
                    if (lastAlignment != null) {
                        lastAlignment.dataSize = loadData.getDataSize();
                    }
                    break;
                case ElfSegment.PT_DYNAMIC:
                    dynamicStructure = ph.getDynamicStructure();
                    break;
                case ElfSegment.PT_INTERP:
                    if (log.isDebugEnabled()) {
                        log.debug("[{}]interp={}", libraryFile.getName(), ph.getInterpreter());
                    }
                    break;
                case ElfSegment.PT_GNU_EH_FRAME:
                    ehFrameHeader = ph.getEhFrameHeader();
                    break;
                case ElfSegment.PT_ARM_EXIDX:
                    armExIdx = ph.getARMExIdxData();
                    break;
                default:
                    if (log.isDebugEnabled()) {
                        log.debug("[{}]segment type=0x{}, offset=0x{}", libraryFile.getName(), Integer.toHexString(ph.type), Long.toHexString(ph.offset));
                    }
                    break;
            }
        }

        if (dynamicStructure == null) {
            throw new IllegalStateException("dynamicStructure is empty.");
        }
        final String soName = dynamicStructure.getSOName(libraryFile.getName());

        Map<String, Module> neededLibraries = new HashMap<>();
        for (String neededLibrary : dynamicStructure.getNeededLibraries()) {
            if (log.isDebugEnabled()) {
                log.debug("{} need dependency {}", soName, neededLibrary);
            }

            LinuxModule loaded = modules.get(neededLibrary);
            if (loaded != null) {
                loaded.addReferenceCount();
                neededLibraries.put(FilenameUtils.getBaseName(loaded.name), loaded);
                continue;
            }
            LibraryFile neededLibraryFile = libraryFile.resolveLibrary(emulator, neededLibrary);
            if (libraryResolver != null && neededLibraryFile == null) {
                neededLibraryFile = libraryResolver.resolveLibrary(emulator, neededLibrary);
            }
            if (neededLibraryFile != null) {
                LinuxModule needed = loadInternal(neededLibraryFile);
                needed.addReferenceCount();
                neededLibraries.put(FilenameUtils.getBaseName(needed.name), needed);
            } else {
                log.info("{} load dependency {} failed", soName, neededLibrary);
            }
        }

        for (LinuxModule module : modules.values()) {
            for (Iterator<ModuleSymbol> iterator = module.getUnresolvedSymbol().iterator(); iterator.hasNext(); ) {
                ModuleSymbol moduleSymbol = iterator.next();
                ModuleSymbol resolved = moduleSymbol.resolve(module.getNeededLibraries(), false, hookListeners, emulator.getSvcMemory());
                if (resolved != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}]{} symbol resolved to {}", moduleSymbol.soName, moduleSymbol.symbol.getName(), resolved.toSoName);
                    }
                    resolved.relocation(emulator, module);
                    iterator.remove();
                }
            }
        }

        List<ModuleSymbol> list = new ArrayList<>();
        List<ModuleSymbol> resolvedSymbols = new ArrayList<>();
        for (MemoizedObject<ElfRelocation> object : dynamicStructure.getRelocations()) {
            ElfRelocation relocation = object.getValue();
            final int type = relocation.type();
            if (type == 0) {
                log.warn("Unhandled relocation type {}", type);
                continue;
            }
            ElfSymbol symbol = relocation.sym() == 0 ? null : relocation.symbol();
            long sym_value = symbol != null ? symbol.value : 0;
            Pointer relocationAddr = UnidbgPointer.pointer(emulator, load_base + relocation.offset());
            assert relocationAddr != null;

            Logger log = LoggerFactory.getLogger("com.github.unidbg.linux." + soName);
            if (log.isDebugEnabled()) {
                log.debug("symbol={}, type={}, relocationAddr={}, offset=0x{}, addend={}, sym={}, android={}", symbol, type, relocationAddr, Long.toHexString(relocation.offset()), relocation.addend(), relocation.sym(), relocation.isAndroid());
            }

            ModuleSymbol moduleSymbol;
            switch (type) {
                case ARMEmulator.R_ARM_ABS32: {
                    int offset = relocationAddr.getInt(0);
                    moduleSymbol = resolveSymbol(load_base, symbol, relocationAddr, soName, neededLibraries.values(), offset);
                    if (moduleSymbol == null) {
                        list.add(new ModuleSymbol(soName, load_base, symbol, relocationAddr, null, offset));
                    } else {
                        resolvedSymbols.add(moduleSymbol);
                    }
                    break;
                }
                case ARMEmulator.R_AARCH64_ABS64: {
                    long offset = relocationAddr.getLong(0) + relocation.addend();
                    moduleSymbol = resolveSymbol(load_base, symbol, relocationAddr, soName, neededLibraries.values(), offset);
                    if (moduleSymbol == null) {
                        list.add(new ModuleSymbol(soName, load_base, symbol, relocationAddr, null, offset));
                    } else {
                        resolvedSymbols.add(moduleSymbol);
                    }
                    break;
                }
                case ARMEmulator.R_ARM_RELATIVE: {
                    int offset = relocationAddr.getInt(0);
                    if (sym_value == 0) {
                        relocationAddr.setInt(0, (int) load_base + offset);
                    } else {
                        throw new IllegalStateException("sym_value=0x" + Long.toHexString(sym_value));
                    }
                    break;
                }
                case ARMEmulator.R_AARCH64_RELATIVE:
                    if (sym_value == 0) {
                        relocationAddr.setLong(0, load_base + relocation.addend());
                    } else {
                        throw new IllegalStateException("sym_value=0x" + Long.toHexString(sym_value));
                    }
                    break;
                case ARMEmulator.R_ARM_GLOB_DAT:
                case ARMEmulator.R_ARM_JUMP_SLOT:
                    moduleSymbol = resolveSymbol(load_base, symbol, relocationAddr, soName, neededLibraries.values(), 0);
                    if (moduleSymbol == null) {
                        list.add(new ModuleSymbol(soName, load_base, symbol, relocationAddr, null, 0));
                    } else {
                        resolvedSymbols.add(moduleSymbol);
                    }
                    break;
                case ARMEmulator.R_AARCH64_GLOB_DAT:
                case ARMEmulator.R_AARCH64_JUMP_SLOT:
                    moduleSymbol = resolveSymbol(load_base, symbol, relocationAddr, soName, neededLibraries.values(), relocation.addend());
                    if (moduleSymbol == null) {
                        list.add(new ModuleSymbol(soName, load_base, symbol, relocationAddr, null, relocation.addend()));
                    } else {
                        resolvedSymbols.add(moduleSymbol);
                    }
                    break;
                case ARMEmulator.R_ARM_COPY:
                    throw new IllegalStateException("R_ARM_COPY relocations are not supported");
                case ARMEmulator.R_AARCH64_COPY:
                    throw new IllegalStateException("R_AARCH64_COPY relocations are not supported");
                case ARMEmulator.R_AARCH64_ABS32:
                case ARMEmulator.R_AARCH64_ABS16:
                case ARMEmulator.R_AARCH64_PREL64:
                case ARMEmulator.R_AARCH64_PREL32:
                case ARMEmulator.R_AARCH64_PREL16:
                case ARMEmulator.R_AARCH64_IRELATIVE:
                case ARMEmulator.R_AARCH64_TLS_TPREL64:
                case ARMEmulator.R_AARCH64_TLS_DTPREL32:
                case ARMEmulator.R_ARM_IRELATIVE:
                case ARMEmulator.R_ARM_REL32:
                default:
                    log.warn("[{}]Unhandled relocation type {}, symbol={}, relocationAddr={}, offset=0x{}, addend={}, android={}", soName, type, symbol, relocationAddr, Long.toHexString(relocation.offset()), relocation.addend(), relocation.isAndroid());
                    break;
            }
        }

        List<InitFunction> initFunctionList = new ArrayList<>();
        int preInitArraySize = dynamicStructure.getPreInitArraySize();
        boolean executable = elfFile.file_type == ElfFile.FT_EXEC || preInitArraySize > 0;
        if (executable) {
            int count = preInitArraySize / emulator.getPointerSize();
            if (count > 0) {
                UnidbgPointer pointer = UnidbgPointer.pointer(emulator, load_base + dynamicStructure.getPreInitArrayOffset());
                if (pointer == null) {
                    throw new IllegalStateException("DT_PREINIT_ARRAY is null");
                }
                for (int i = 0; i < count; i++) {
                    UnidbgPointer ptr = pointer.share((long) i * emulator.getPointerSize(), 0);
                    initFunctionList.add(new AbsoluteInitFunction(load_base, soName, ptr));
                }
            }
        }
        if (elfFile.file_type == ElfFile.FT_DYN) { // not executable
            int init = dynamicStructure.getInit();
            if (init != 0) {
                initFunctionList.add(new LinuxInitFunction(load_base, soName, init));
            }

            int initArraySize = dynamicStructure.getInitArraySize();
            int count = initArraySize / emulator.getPointerSize();
            if (count > 0) {
                UnidbgPointer pointer = UnidbgPointer.pointer(emulator, load_base + dynamicStructure.getInitArrayOffset());
                if (pointer == null) {
                    throw new IllegalStateException("DT_INIT_ARRAY is null");
                }
                for (int i = 0; i < count; i++) {
                    UnidbgPointer ptr = pointer.share((long) i * emulator.getPointerSize(), 0);
                    initFunctionList.add(new AbsoluteInitFunction(load_base, soName, ptr));
                }
            }
        }

        SymbolLocator dynsym = dynamicStructure.getSymbolStructure();
        if (dynsym == null) {
            log.warn("event=dynsym_missing library={} path={} file_size={}", libraryFile.getName(), libraryFile.getPath(), libraryFile.getFileSize());
            dynsym = new EmptySymbolLocator();
        }
        ElfSection symbolTableSection = null;
        try {
            symbolTableSection = elfFile.getSymbolTableSection();
        } catch(Throwable ignored) {}
        if (load_virtual_address == 0) {
            throw new IllegalStateException("load_virtual_address");
        }
        InitFunctionFilter initFunctionFilter = null;
        if(libraryResolver instanceof InitFunctionFilter) {
            initFunctionFilter = (InitFunctionFilter) libraryResolver;
        }
        LinuxModule module = new LinuxModule(load_virtual_address, load_base, size, soName, dynsym, list, initFunctionList, neededLibraries, regions,
                armExIdx, ehFrameHeader, symbolTableSection, elfFile, dynamicStructure, libraryFile, initFunctionFilter);
        for (ModuleSymbol symbol : resolvedSymbols) {
            symbol.relocation(emulator, module);
        }
        if (executable) {
            for (LinuxModule linuxModule : modules.values()) {
                for (Map.Entry<String, ModuleSymbol> entry : linuxModule.resolvedSymbols.entrySet()) {
                    ElfSymbol symbol = module.getELFSymbolByName(entry.getKey());
                    if (symbol != null && !symbol.isUndef()) {
                        long value = entry.getValue().relocation(emulator, module, symbol);
                        linuxModule.registerImportedSymbol(UnidbgPointer.nativeValue(entry.getValue().getRelocationAddr()),
                                module.name, symbol.getName(), value);
                    }
                }
                linuxModule.resolvedSymbols.clear();
            }
        }
        if ("libc.so".equals(soName)) { // libc
            malloc = module.findSymbolByName("malloc", false);
            free = module.findSymbolByName("free", false);
            if (module.findSymbolByName("__system_property_read_callback", false) == null) {
                Symbol propertyGet = module.findSymbolByName("__system_property_get", false);
                if (propertyGet != null) {
                    module.registerSymbol("__system_property_read_callback", propertyGet.getAddress());
                }
            }
        }

        modules.put(soName, module);
        if (maxSoName == null || soName.length() > maxSoName.length()) {
            maxSoName = soName;
        }
        if (bound_high > maxSizeOfSo) {
            maxSizeOfSo = bound_high;
        }
        module.setEntryPoint(elfFile.entry_point);
        log.debug("Load library {} offset={}ms, entry_point=0x{}", soName, System.currentTimeMillis() - start, Long.toHexString(elfFile.entry_point));
        notifyModuleLoaded(module);
        return module;
    }

    @Override
    public Module loadVirtualModule(String name, Map<String, UnidbgPointer> symbols) {
        LinuxModule module = LinuxModule.createVirtualModule(name, symbols, emulator);
        modules.put(name, module);
        if (maxSoName == null || name.length() > maxSoName.length()) {
            maxSoName = name;
        }
        return module;
    }

    private String maxSoName;
    private long maxSizeOfSo;

    private ModuleSymbol resolveSymbol(long load_base, ElfSymbol symbol, Pointer relocationAddr, String soName, Collection<Module> neededLibraries, long offset) throws IOException {
        if (symbol == null) {
            return new ModuleSymbol(soName, load_base, null, relocationAddr, soName, offset);
        }

        if (!symbol.isUndef()) {
            for (HookListener listener : hookListeners) {
                long hook = listener.hook(emulator.getSvcMemory(), soName, symbol.getName(), load_base + symbol.value + offset);
                if (hook > 0) {
                    return new ModuleSymbol(soName, ModuleSymbol.WEAK_BASE, symbol, relocationAddr, soName, hook);
                }
            }
            return new ModuleSymbol(soName, load_base, symbol, relocationAddr, soName, offset);
        }

        return new ModuleSymbol(soName, load_base, symbol, relocationAddr, null, offset).resolve(neededLibraries, false, hookListeners, emulator.getSvcMemory());
    }

    private int get_segment_protection(int flags) {
        int prot = Unicorn.UC_PROT_NONE;
        if ((flags & ElfSegment.PF_R) != 0) prot |= Unicorn.UC_PROT_READ;
        if ((flags & ElfSegment.PF_W) != 0) prot |= Unicorn.UC_PROT_WRITE;
        if ((flags & ElfSegment.PF_X) != 0) prot |= Unicorn.UC_PROT_EXEC;
        return prot;
    }

    @Override
    public MemoryBlock malloc(int length, boolean runtime) {
        if (runtime) {
            return MemoryBlockImpl.alloc(this, length);
        } else {
            return MemoryAllocBlock.malloc(emulator, malloc, free, length);
        }
    }

    private static final long HEAP_BASE = 0x8048000;
    private long brk;

    @Override
    public int brk(long address) {
        if (address == 0) {
            this.brk = HEAP_BASE;
            return (int) this.brk;
        }

        if (address % emulator.getPageAlign() != 0) {
            throw new UnsupportedOperationException();
        }

        if (address > brk) {
            backend.mem_map(brk, address - brk, UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_WRITE);
            if (mMapListener != null) {
                mMapListener.onMap(brk, address - brk, UnicornConst.UC_PROT_READ | UnicornConst.UC_PROT_WRITE);
            }
            this.brk = address;
        } else if(address < brk) {
            backend.mem_unmap(address, brk - address);
            if (mMapListener != null) {
                mMapListener.onUnmap(address, brk - address);
            }
            this.brk = address;
        }

        return (int) this.brk;
    }

    public static final int MAP_SHARED = 1;
    public static final int MAP_PRIVATE = 2;
    public static final int MAP_FIXED = 0x10;
    public static final int MAP_ANONYMOUS = 0x20;

    @Override
    protected int memoryError(int errno) {
        return -errno;
    }

    @Override
    public int msync(long address, int length, int flags) {
        try {
            int result = fileMappings.sync(address, length, flags);
            log.debug("event=memory_synced address=0x{} length={} flags={} result={}",
                    Long.toHexString(address), length, flags, result);
            return result;
        } catch (HostMappingException error) {
            return -error.getErrno();
        } catch (IOException error) {
            log.error("event=memory_sync_failed address=0x{} length={} flags={}",
                    Long.toHexString(address), length, flags, error);
            throw new IllegalStateException(error);
        }
    }

    @Override
    public int discardFileMapping(long address, int length) {
        try {
            return fileMappings.discard(address, length);
        } catch (HostMappingException error) {
            return -error.getErrno();
        } catch (IOException error) {
            log.error("event=file_mapping_discard_failed address=0x{} length={}",
                    Long.toHexString(address), length, error);
            throw new IllegalStateException(error);
        }
    }

    @Override
    public long mmap2(long start, int length, int prot, int flags, int fd, long offset) {
        log.debug("event=memory_map_requested address=0x{} length={} prot={} flags={} fd={} offset={}",
                Long.toHexString(start), length, prot, flags, fd, offset);
        int page = emulator.getPageAlign();
        long alignedLength = ARM.alignSize(length, page);
        if (length <= 0 || offset < 0 || offset % page != 0 ||
                ((flags & MAP_FIXED) != 0 && start % page != 0)) {
            return -UnixEmulator.EINVAL;
        }
        if (alignedLength > Integer.MAX_VALUE) {
            return -UnixEmulator.ENOMEM;
        }
        int aligned = (int) alignedLength;
        // AbstractLoader.mmap uses a platform-neutral anonymous allocation request.
        boolean internalAnonymous = flags == 0 && fd == -1 && start == 0 && offset == 0;
        boolean anonymous = (flags & MAP_ANONYMOUS) != 0 || internalAnonymous;
        int type = flags & 0xf;
        if (!internalAnonymous && type != MAP_SHARED && type != MAP_PRIVATE) {
            return -UnixEmulator.EINVAL;
        }
        FileIO file = anonymous ? null : syscallHandler.getFileIO(fd);
        if (!anonymous && file == null) {
            return -UnixEmulator.EBADF;
        }
        if (file != null && (!file.isReadable() ||
                (type == MAP_SHARED && (prot & UnicornConst.UC_PROT_WRITE) != 0 && !file.isWritable()))) {
            return -UnixEmulator.EACCES;
        }
        boolean mayWrite = file == null || type != MAP_SHARED || file.isWritable();
        boolean fixed = (flags & MAP_FIXED) != 0;
        long address = fixed ? start : allocateMapAddress(0, aligned);
        try {
            MemoryMap map;
            MappingSource source = file == null ? null : file.getMappingSource();
            if (source != null) {
                try (FileMappingManager.PendingMapping pending = fileMappings.prepare(source, aligned, prot, flags, offset, mayWrite)) {
                    if (fixed) {
                        unmapOverlaps(address, aligned);
                    }
                    map = pending.map(address);
                }
            } else {
                if (file != null && type == MAP_SHARED) {
                    throw new UnsupportedOperationException("File does not provide host-backed shared mappings: " + file);
                }
                if (fixed) {
                    unmapOverlaps(address, aligned);
                }
                if (anonymous) {
                    backend.mem_map(address, aligned, prot);
                } else {
                    file.mmap2(emulator, address, aligned, prot, offset, length);
                }
                int mapFlags = internalAnonymous ? MAP_ANONYMOUS | MAP_PRIVATE : flags;
                map = new MemoryMap(address, aligned, prot, mapFlags, offset, null, mayWrite);
            }
            memoryMap.put(address, map);
            if (mMapListener != null) {
                mMapListener.onMap(address, aligned, prot);
            }
            log.debug("event=memory_mapped address=0x{} length={} fd={} backing={}",
                    Long.toHexString(address), aligned, fd, map.backing);
            return address;
        } catch (HostMappingException error) {
            return -error.getErrno();
        } catch (IOException error) {
            log.error("event=memory_map_failed address=0x{} length={} fd={} offset={}",
                    Long.toHexString(address), aligned, fd, offset, error);
            throw new IllegalStateException(error);
        }
    }

    private void unmapOverlaps(long address, int length) {
        long end = address + length;
        for (MemoryMap map : new ArrayList<>(memoryMap.values())) {
            long from = Math.max(address, map.base);
            long to = Math.min(end, map.base + map.size);
            if (from < to) {
                munmap(from, (int) (to - from));
            }
        }
    }

    private Pointer errno;

    private int lastErrno;

    @Override
    public int getLastErrno() {
        return lastErrno;
    }

    @Override
    public void setErrno(int errno) {
        this.lastErrno = errno;
        Task task = emulator.get(Task.TASK_KEY);
        if (task != null && task.setErrno(emulator, errno)) {
            return;
        }
        this.errno.setInt(0, errno);
    }

    @Override
    public String getMaxLengthLibraryName() {
        return maxSoName;
    }

    @Override
    public long getMaxSizeOfLibrary() {
        return maxSizeOfSo;
    }

    @Override
    public Collection<Module> getLoadedModules() {
        return new ArrayList<>(modules.values());
    }
}
