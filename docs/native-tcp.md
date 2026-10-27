# Native TCP

## Runtime

AF_INET and AF_INET6 SOCK_STREAM sockets use the Linux x86_64 host kernel through the shared `LinuxSocketCalls` JNA interface. ARM32 and ARM64 guest sockaddr layouts match the host's little-endian Linux layouts. Guest descriptors remain separate from host descriptors. Other host platforms fail at the native socket capability entry; there is no Java fallback.

`NativeTcpSocketFactory.create` receives the original socket type, including SOCK_NONBLOCK and SOCK_CLOEXEC, and preserves the domain and protocol. Creation and descriptor duplication failures carry `LinuxErrnoException`. Scalar operations return raw Linux results, including negative errno. Bionic owns libc result and thread-local errno conversion.

Host descriptors always use O_NONBLOCK and FD_CLOEXEC. Guest O_NONBLOCK state belongs to a shared status object across dup; guest FD_CLOEXEC belongs to each descriptor. F_SETFL preserves host O_NONBLOCK while updating the shared guest state. Accepted sockets start with the flags requested by accept4 rather than inheriting the listener's guest flags.

Connect, accept, send and receive use task Waiters. Each operation owns a private duplicate descriptor. Host calls run without blocking the dispatcher. Connect completion uses host poll and SO_ERROR. Blocking sends retain their offset across short host writes until completion or interruption, error or timeout. Guest nonblocking sends retain the immediate host result. Receive honors SO_RCVLOWAT, supports peeking and accumulates MSG_WAITALL data; host sends suppress SIGPIPE while preserving EPIPE. Guest signal delivery remains the syscall layer's responsibility.

Each connect, accept, send or receive operation snapshots the kernel-normalized SO_SNDTIMEO or SO_RCVTIMEO and uses a monotonic elapsed budget. Zero means indefinite waiting. Nonblocking calls return the kernel's immediate result. Receive/send/accept timeout returns EAGAIN; connect timeout returns EINPROGRESS. A signal completes pending I/O with EINTR or an already transferred partial result. The dispatcher run budget does not become a socket timeout.

Socket options pass through the kernel. SO_RCVTIMEO and SO_SNDTIMEO translate between ARM32's two signed 32-bit fields or ARM64's two signed 64-bit fields and the host timeval. Address outputs honor the guest's capacity and publish the kernel's actual length.

The existing poll, ppoll, select and pselect6 syscall entries use `ReadinessOperation`. It snapshots guest pollfd or fd-set inputs, calls `FileIO.poll` without blocking, retains results across readiness checks and publishes guest outputs only on synchronous completion or task resumption. It returns actual revents, including POLLERR, POLLHUP and POLLNVAL, and honors monotonic timeout budgets and EINTR. Select returns EBADF for invalid descriptors and counts each ready set bit. Unsupported descriptor implementations fail explicitly rather than reporting requested events as ready.

Null ppoll/pselect signal masks use the existing task signal mechanism. Non-null temporary masks remain unsupported because the dispatcher does not implement atomic pending-signal promotion and mask restoration. Those calls fail with an explicit capability exception; an invalid kernel sigset size returns EINVAL.

`AndroidFileIO.accept(addr, addrlen, flags, installer)` installs the accepted wrapper only on the synchronous completion path or when the suspended task resumes. Readiness checks retain host results without writing guest memory or allocating guest descriptors. A nonnegative installer result transfers the accepted wrapper to the descriptor table. A negative result or exception transfers the uninstalled host descriptor to the close service. Unsupported provider capabilities fail explicitly rather than inventing errno.

## Closing

`FileIO.close(emulator, completion)` separates logical descriptor removal from native completion. TCP transfers its owned host descriptor to `NativeSocketCloseService` and waits through `SocketCloseWaiter`. Only the restored backend owner invokes the completion once with the actual kernel close result. The completion cannot yield. Dup2 and dup3 install the new descriptor view before waiting for implicit target closure; a close error does not change their successful descriptor result.

The close service runs libc close on managed cached executor workers named `unidbg-socket-close-*`. Positive SO_LINGER can block even an O_NONBLOCK host descriptor. The kernel retains its actual linger behavior while other guest tasks continue. Workers only own transferred host descriptors and native results. A private operation descriptor also uses this service; the original I/O result and guest output wait for its close to complete.

Waiter cancellation detaches the waiting guest without cancelling the native close or closing its numeric descriptor again. Linux can reuse that number while the original close is still running. Guest signals do not interrupt a running host close or manufacture a close result. Void `close()` transfers cleanup responsibility without waiting.

The factory tracks owned TCP views and pending operations. The service's dispatcher `beginClose` callback cancels operations, collects private duplicates and uninstalled accepted descriptors, transfers all remaining views, then seals the executor. The existing dispatcher shutdown drains requests and waits for actual worker thread termination within its single five-second budget. No per-request timeout resets that budget. A native close that exceeds it remains service-owned and causes shutdown failure; process termination is the final boundary for an uninterruptible close. Factory destruction after dispatcher drain is idempotent.

## Explicit loopback validation

`NativeTcpLoopbackIntegrationTest` is opt-in. Its single lifecycle runs both guest ABIs and both IP families against independent host loopback peers and a same-emulator pthread peer. Assertions cover externally received bytes, a one-megabyte blocking send, kernel errno, socket timeout round trips, nonblocking readiness, fd sets spanning machine words, descriptor status sharing, positive-linger close and dup2, cancelled close, open-view shutdown, and independent `/proc/self/fd` and worker-thread resource snapshots. A guest pthread releases the host peer's read gate during linger, proving that closure does not block guest dispatch. The host socket provider initializes before the resource baseline so its process-lifetime JDK descriptor belongs to the baseline. Tests do not connect to business networks.

Prepare a new build snapshot with JDK 21 and the fixed Unicorn source revision through the release entry point:

```sh
uv run scripts/release.py --build-only \
  --unicorn-source "$UNICORN_SOURCE" \
  --consumer-classpath-file "$CONSUMER_CLASSPATH_FILE" \
  --java-home "$JAVA_HOME" --offline \
  --build-output target/tcp-build
```

`UNICORN_SOURCE` must name revision `8028ec436f2d9376525352dd38ed9ed6b9f6be10`. `CONSUMER_CLASSPATH_FILE` supplies the existing consumer dependencies. The build produces generated native resources and an identified classpath without installing or deploying Maven artifacts.

Compile the fixtures with Android NDK r28 and execute only the TCP class against that snapshot:

```sh
mkdir -p target/native-tcp target/tcp-test-classes
CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$CC/aarch64-linux-android23-clang" -shared -fPIC \
  -Wall -Wextra -Werror -Wno-unused-parameter -Wl,-z,max-page-size=4096 \
  unidbg-android/src/test/native/android/native-tcp.c \
  -o target/native-tcp/libnative-tcp-aarch64.so
"$CC/armv7a-linux-androideabi23-clang" -shared -fPIC \
  -Wall -Wextra -Werror -Wno-unused-parameter -Wl,-z,max-page-size=4096 \
  unidbg-android/src/test/native/android/native-tcp.c \
  -o target/native-tcp/libnative-tcp-armv7a.so
CP="$(cat target/tcp-build/classpath.txt):$JUNIT_JAR:$HAMCREST_JAR"
"$JAVA_HOME/bin/javac" -cp "$CP" -d target/tcp-test-classes \
  unidbg-android/src/test/java/com/github/unidbg/linux/NativeTcpLoopbackIntegrationTest.java
"$JAVA_HOME/bin/java" -cp "target/tcp-test-classes:$CP" \
  -Dunidbg.nativeTcpLibrary64="$PWD/target/native-tcp/libnative-tcp-aarch64.so" \
  -Dunidbg.nativeTcpLibrary32="$PWD/target/native-tcp/libnative-tcp-armv7a.so" \
  org.junit.runner.JUnitCore com.github.unidbg.linux.NativeTcpLoopbackIntegrationTest
```

`JUNIT_JAR` names JUnit 4.13.2; `HAMCREST_JAR` names Hamcrest Core 1.3. These are the existing test dependencies. Generated fixtures, build identities and test classes remain under `target/`.

Blocking send reference: Linux v6.8 `tcp_sendmsg_locked` retains `copied` across `sk_stream_wait_memory`, returns the accumulated count on an error after progress and otherwise completes the remaining input: https://github.com/torvalds/linux/blob/v6.8/net/ipv4/tcp.c#L1033-L1333.

Close references: Linux v6.8 `inet_release` passes SO_LINGER to `tcp_close`, which calls `sk_stream_wait_close`: https://github.com/torvalds/linux/blob/v6.8/net/ipv4/af_inet.c#L414-L439 and https://github.com/torvalds/linux/blob/v6.8/net/ipv4/tcp.c#L2780-L2845. Dup2 replaces the descriptor before `filp_close` and preserves the new fd result: https://github.com/torvalds/linux/blob/v6.8/fs/file.c#L1219-L1269.

Kernel contracts: https://man7.org/linux/man-pages/man7/tcp.7.html, https://man7.org/linux/man-pages/man7/socket.7.html, https://man7.org/linux/man-pages/man2/connect.2.html, https://man7.org/linux/man-pages/man2/accept.2.html, https://man7.org/linux/man-pages/man2/recv.2.html, https://man7.org/linux/man-pages/man2/poll.2.html and https://man7.org/linux/man-pages/man2/dup.2.html.
