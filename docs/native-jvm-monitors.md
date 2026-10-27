# Native JVM monitors

## Execution contract

Each guest thread has one fixed platform Java thread for host Java operations and JNI-acquired monitors. `GuestJavaRuntime` executes host-only `Callable` operations there; these operations must not access the emulator backend, guest memory or VM reference tables. `HostJavaCall` publishes an original value or Throwable. `GuestJavaWaiter` delivers one call's explicit completion on the backend owner thread.

`JvmMonitorBridge` calls the host JVM's standard JNI MonitorEnter/MonitorExit. Ownership persists between calls and across native execution. `JvmMonitorOwnership` retains object identities and JNI acquisition depths for owner-thread cleanup; it does not replace JVM locking. Normal Java `synchronized` competes for the same object monitor. Native-acquired locks are released with JNI MonitorExit, including on guest detach, rather than a Java synchronized return.

`GuestNativeGateway.Request` separates backend preparation, native result conversion and resource cleanup. A host callback waiting for a native reply pumps its own nested callback chain. Dispatcher native frames keep the root guest identity, TLS, CPU context and stack while running nested native entries; completing a frame does not terminate the root guest thread. Backend access remains on the original backend owner thread. JNI slot integration must supply an explicit result continuation; replaying an interrupted SVC is not supported.

`JniThreadRegistry` owns guest attachment and per-thread JNIEnv. Main JNI state is initialized once after the function table is bound. New native threads are detached until attach. Local-reference frame creation and cleanup are delegated to `JniThreadResources`; BaseVM retains exception identity/root ownership. Normal native-call frame cleanup does not detach the main thread. Root thread termination listeners remain independent of the existing DestroyListener.

## Cancellation and shutdown

Cancellation suppresses a queued operation. An in-progress JNI MonitorEnter cannot be interrupted: after acquisition its owner compensates with MonitorExit before publishing cancellation. Native-frame failure is delivered to the original host callback. Java target exceptions preserve their original identity and do not implicitly release JNI-acquired monitors.

Emulator close first closes dispatcher event sources, cancels native frames and waits for owner-thread monitor cleanup and Java-thread termination. The shutdown budget is five seconds. Exhausting it reports thread/lock diagnostics and leaves the backend intact; close can be retried after the blocking application work returns. JVM APIs cannot safely force an arbitrary permanently blocked callback or monitor cycle to terminate. Such failures require termination of the containing worker process. No unsafe thread termination is used.

## Host library

The bridge is backend-independent and uses `NativeLoader.loadLibrary("unidbg_jvm_monitor")`. The current validated host target is Linux x86_64 with JDK 21. The generated library belongs at `unidbg-android/target/generated-resources/natives/linux_64/libunidbg_jvm_monitor.so`. Generated binaries are not committed. Missing native runtime support fails initialization rather than installing empty monitor operations.

Run the following in the dedicated source checkout on `192.168.50.8`. These commands perform no Maven install or deploy.

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_NDK_HOME=/home/dev/Android/Sdk/ndk/28.2.13676358
: "${UNICORN_HOME:?Set UNICORN_HOME to the Unicorn source checkout}"
./mvnw -pl unidbg-android -am clean
cmake -S "$UNICORN_HOME" -B target/unicorn-build -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON '-DUNICORN_ARCH=arm;aarch64' -DUNICORN_BUILD_TESTS=OFF
cmake --build target/unicorn-build --target unicorn_archive --parallel 32
cmake -S backend/unicorn2/src/main/native -B target/unicorn-jni-build \
  -DUNICORN_HOME="$UNICORN_HOME" -DUNICORN_BUILD="$PWD/target/unicorn-build"
cmake --build target/unicorn-jni-build --parallel 2
mkdir -p backend/unicorn2/target/generated-resources/natives/linux_64
cp target/unicorn-jni-build/libunicorn.so backend/unicorn2/target/generated-resources/natives/linux_64/
cmake -S unidbg-android/src/main/native/jvm-monitor \
  -B unidbg-android/target/jvm-monitor -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$PWD/unidbg-android/target/generated-resources/natives/linux_64"
cmake --build unidbg-android/target/jvm-monitor --parallel 2
mkdir -p unidbg-android/target/guest-monitor
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang" \
  -shared -fPIC -mno-outline-atomics -Wl,-z,max-page-size=4096 \
  -Wall -Wextra -Werror -Wno-unused-parameter \
  unidbg-android/src/test/native/android/guest-monitor-runtime.c \
  -o unidbg-android/target/guest-monitor/libguest-monitor-runtime.so
./mvnw -pl unidbg-android -am -Dmaven.test.skip=false \
  -Dtest=GuestMonitorRuntimeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dunidbg.guestMonitorLibrary="$PWD/unidbg-android/target/guest-monitor/libguest-monitor-runtime.so" test
```

The release integration must preserve this ordering: Maven clean, native bridge generation, Maven resources/tests/package. Pre-generating into target before release clean loses the library. The release coordinator owns the Maven/release entry point integration; these explicit gate commands do not by themselves change the release pipeline or claim another host architecture is supported.

## Explicit architecture gate

`GuestMonitorRuntimeIntegrationTest` is excluded from the default test set. Its single lifecycle uses real Bionic pthreads, Unicorn ARM64 execution, ordinary reflective Java callbacks and the production host runtime/monitor bridge. Test SVCs supply explicit backend continuations without replacing JVM locking or the JNI oracle.

The lifecycle observes fixed distinct guest Java threads, continuous monitor ownership through native execution, reentrant acquisition, Java-to-native-to-Java reentry and a single backend execution thread. Independent Java contenders and ThreadMXBean verify actual BLOCKED/owner states. Native and independent observer files witness execution and release. Original target exception identity, cancellation during a real blocked acquisition, cancellation of a guest task waiting for one host call, queued host-call cancellation, close-time release and actual Java-thread termination are verified in the same run.

This gate covers the guest thread and monitor runtime. JNI-table method, constructor, field and visitor integration has separate gates.

Contracts: https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#monitorenter, https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#monitorexit, https://docs.oracle.com/en/java/javase/21/docs/specs/jni/invocation.html and https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadInfo.html#getLockOwnerId().
