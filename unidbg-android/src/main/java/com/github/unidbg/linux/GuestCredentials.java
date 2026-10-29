package com.github.unidbg.linux;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Identity used by identity syscalls and file DAC queries. Empty Optional means unknown groups. */
public final class GuestCredentials {
    public final OptionalInt realUid;
    public final OptionalInt effectiveUid;
    public final OptionalInt realGid;
    public final OptionalInt effectiveGid;
    public final Optional<Set<Integer>> supplementaryGroups;

    public GuestCredentials(OptionalInt realUid, OptionalInt effectiveUid, OptionalInt realGid, OptionalInt effectiveGid,
                            Optional<Set<Integer>> supplementaryGroups) {
        this.realUid = realUid;
        this.effectiveUid = effectiveUid;
        this.realGid = realGid;
        this.effectiveGid = effectiveGid;
        this.supplementaryGroups = supplementaryGroups.map(groups ->
                Collections.unmodifiableSet(new LinkedHashSet<>(groups)));
    }
    public int requireRealUid() { return require(realUid, "real uid"); }
    public int requireEffectiveUid() { return require(effectiveUid, "effective uid"); }
    public int requireRealGid() { return require(realGid, "real gid"); }
    public int requireEffectiveGid() { return require(effectiveGid, "effective gid"); }

    private static int require(OptionalInt value, String name) {
        return value.orElseThrow(() -> new UnsupportedOperationException("Guest " + name + " is unavailable"));
    }
}
