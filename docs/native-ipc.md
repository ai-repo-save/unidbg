# Native IPC

## Runtime

ARM64 `socketpair(AF_UNIX, SOCK_SEQPACKET, 0)` uses the Linux x86_64 host kernel through JNA. STREAM socketpairs keep their existing implementation. The adapter supports unnamed connected endpoints with null address arguments to `sendto` and `recvfrom`.

The kernel owns packet boundaries, ordering, truncation, peeking and socket options. Guest pointers are copied through host byte arrays; guest descriptor numbers never become host descriptors. The adapter returns raw Linux syscall results. The bundled SDK23 Bionic `recvfrom` and `sendto` SVC wrappers translate negative errno values into the calling native thread's TLS.

Each receive or send uses `MSG_DONTWAIT`. A blocking guest call installs a task Waiter and yields through `ThreadContextSwitchException` inside `emulate`. The Waiter completes exactly one operation, retains its result across readiness checks and writes guest memory/registers only when that task resumes. `onContinueRun` does not yield. Host sends suppress host SIGPIPE; the adapter returns EPIPE without implementing guest SIGPIPE delivery.

Each operation snapshots the kernel-normalized SO_RCVTIMEO or SO_SNDTIMEO and measures its elapsed budget with a monotonic clock. A zero timeout waits indefinitely. A guest nonblocking operation returns EAGAIN immediately. No scheduler budget becomes a native socket timeout.

Guest `dup` and `dup3` create independent owned host descriptors sharing the kernel socket state. Pending operations hold private duplicate descriptors, preserving Linux's open-file-description lifetime when another guest thread closes its descriptor. Completion releases the operation descriptor. Replacing/destroying a task's Waiter cancels its operation through `Waiter.onDestroy`; the existing task DestroyListener remains independent. Emulator shutdown cancels outstanding socket operations before closing guest descriptors and destroying the backend.

Enable `setEnableThreadDispatcher(true)` for SDK23 Bionic pthreads. `runThreads` promotes pending children after the previous main returns and restores the caller's CPU context and stack afterward, including its TLS register. Debug logging does not add scheduling sleeps.

## Session boundary

A guest fd belongs to one emulator's descriptor table. Future same-process endpoint transfer must duplicate the host fd and install an independently owned wrapper in the receiving emulator. Transfer between processes on one Linux host requires SCM_RIGHTS over a control AF_UNIX socket. The receiver assigns its own guest fd. These host transfer APIs are outside the current implementation.

## Integration validation

`NativeSeqpacketLifecycleIntegrationTest` is opt-in. It executes ARM64 native C against the bundled SDK23 Bionic libc. One lifecycle covers pending pthread dispatch, two peeks followed by full receive, short receive truncation, packet boundaries, receive/send timeouts, same-emulator blocking sends and receives, dup/dup2, close during pending receive, peer EOF/EPIPE, thread-local errno, an existing DestroyListener's persisted side effect and shutdown cancellation. Independent `/proc/self/fd` snapshots verify socket descriptor release.

Build and run on a Linux x86_64 development server with JDK 21 and Android NDK r28. Set `ANDROID_NDK_HOME` to the NDK directory:

```sh
mkdir -p target/native-ipc
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang" \
  -shared -fPIC -mno-outline-atomics -Wl,-z,max-page-size=4096 \
  -Wall -Wextra -Werror -Wno-unused-parameter \
  unidbg-android/src/test/native/android/native-ipc.c \
  -o target/native-ipc/libnative-ipc.so
./mvnw -pl unidbg-android -am -Dmaven.test.skip=false \
  -Dtest=NativeSeqpacketLifecycleIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dunidbg.nativeIpcLibrary="$PWD/target/native-ipc/libnative-ipc.so" test
```

The required library property names the generated fixture. Native source and test remain versioned; the fixture binary and Maven reports are reproducible outputs under `target/`.

The adapter's debug events use pair and operation identifiers and retain send/receive bytes, socket option bytes and host errno. They use the existing SLF4J logging configuration.

Kernel contracts: https://man7.org/linux/man-pages/man7/unix.7.html, https://man7.org/linux/man-pages/man2/recv.2.html, https://man7.org/linux/man-pages/man7/socket.7.html, https://man7.org/linux/man-pages/man2/dup.2.html and https://man7.org/linux/man-pages/man2/close.2.html.
