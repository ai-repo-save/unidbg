# Native IPC integration

`NativeSeqpacketLifecycleIntegrationTest` is opt-in and uses the bundled SDK23 ARM64 Bionic libc.

Build and run on a 64-bit Linux host with JDK 21 and Android NDK r28. Set `ANDROID_NDK_HOME` to the NDK directory:

```sh
mkdir -p target/native-ipc
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang" \
  -shared -fPIC -mno-outline-atomics -Wl,-z,max-page-size=4096 \
  unidbg-android/src/test/native/android/native-ipc.c \
  -o target/native-ipc/libnative-ipc.so
./mvnw -pl unidbg-android -am -Dmaven.test.skip=false \
  -Dtest=NativeSeqpacketLifecycleIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dunidbg.nativeIpcLibrary="$PWD/target/native-ipc/libnative-ipc.so" test
```

The native entry returns after `pthread_create`. Its child remains pending until the next dispatcher invocation; `runThreads` promotes pending children even when the previous main task has already finished. The scheduling budget does not replace a native socket timeout.
