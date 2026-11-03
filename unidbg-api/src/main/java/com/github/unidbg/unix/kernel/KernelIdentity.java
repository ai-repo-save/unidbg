package com.github.unidbg.unix.kernel;

/**
 * The identity the kernel reports for the machine the guest runs on: the fields of
 * {@code uname(2)} and the capability words the auxiliary vector publishes.
 *
 * Every kernel the runtime emulates is Linux, so the system name is not part of the identity. The
 * release and the version name the kernel build, the machine names the processor architecture and
 * the node and domain names belong to the host the device was given; a recording states them, and
 * the runtime cannot derive a build identity from itself. The platform answers the release and the
 * version with the empty string when nothing recorded them, because the runtime holds no kernel
 * build of its own.
 *
 * {@code AT_HWCAP} and {@code AT_HWCAP2} are the optional capability words: a device kernel that
 * publishes no second word has no {@code AT_HWCAP2} entry, which {@code getauxval(2)} answers with
 * {@code ENOENT} rather than with a zero word.
 */
public final class KernelIdentity {

    /** The name every emulated kernel reports. */
    public static final String SYSNAME = "Linux";

    /** Android gives an unnamed device the local host name. */
    public static final String DEFAULT_NODENAME = "localhost";

    /** The NIS domain name a device without one reports. */
    public static final String DEFAULT_DOMAINNAME = "localdomain";

    private final String nodename;
    private final String release;
    private final String version;
    private final String machine;
    private final String domainname;
    private final Long hwcap;
    private final Long hwcap2;

    public KernelIdentity(String nodename, String release, String version, String machine,
                          String domainname, Long hwcap, Long hwcap2) {
        this.nodename = require(nodename, "nodename");
        this.release = require(release, "release");
        this.version = require(version, "version");
        this.machine = require(machine, "machine");
        this.domainname = require(domainname, "domainname");
        this.hwcap = hwcap;
        this.hwcap2 = hwcap2;
    }

    /**
     * The identity of a machine nothing recorded: the runtime's own platform. The release and the
     * version stay empty because a kernel build is a fact only a device record has.
     */
    public static KernelIdentity platform(boolean is64Bit) {
        return new KernelIdentity(DEFAULT_NODENAME, "", "", machine(is64Bit), DEFAULT_DOMAINNAME,
                null, null);
    }

    /** The architecture name of the processor the runtime emulates. */
    public static String machine(boolean is64Bit) {
        return is64Bit ? "aarch64" : "armv7l";
    }

    private static String require(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " must be stated");
        return value;
    }

    public String nodename() {
        return nodename;
    }

    public String release() {
        return release;
    }

    public String version() {
        return version;
    }

    public String machine() {
        return machine;
    }

    public String domainname() {
        return domainname;
    }

    /** The {@code AT_HWCAP} word, or {@code null} when the kernel publishes none. */
    public Long hwcap() {
        return hwcap;
    }

    /** The {@code AT_HWCAP2} word, or {@code null} when the kernel publishes none. */
    public Long hwcap2() {
        return hwcap2;
    }

    /**
     * The release token of {@code /proc/version}: the kernel prints
     * {@code Linux version <release> ...} with the build identity after it.
     */
    public String procVersion() {
        return SYSNAME + " version " + release + (version.isEmpty() ? "" : " " + version) + "\n";
    }
}
