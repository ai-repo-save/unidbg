package com.github.unidbg.linux.android.dvm;

interface JniThreadResources {
    JniLocalFrames createLocals(JniThreadState state);
    void clearThreadReferences(JniThreadState state);
    boolean supportsJniVersion(int version);
}
