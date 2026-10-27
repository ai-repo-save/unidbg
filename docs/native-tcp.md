# Native TCP

## Runtime

AF_INET and AF_INET6 SOCK_STREAM sockets use the Linux x86_64 host kernel through the shared `LinuxSocketCalls` JNA interface. ARM32 and ARM64 guest sockaddr layouts match the host's little-endian Linux layouts. Guest descriptors remain separate from host descriptors.

`NativeTcpSocketFactory.create` receives the original socket type, including SOCK_NONBLOCK and SOCK_CLOEXEC, and preserves the domain and protocol. Creation and descriptor duplication failures carry `LinuxErrnoException`. Scalar operations return raw Linux results, including negative errno. Bionic owns libc result and thread-local errno conversion.

Host descriptors always use O_NONBLOCK and FD_CLOEXEC. Guest O_NONBLOCK state belongs to a shared status object across dup; guest FD_CLOEXEC belongs to each descriptor. F_SETFL preserves host O_NONBLOCK while updating the shared guest state. Accepted sockets start with the flags requested by accept4 rather than inheriting the listener's guest flags.

Connect, accept, send and receive use task Waiters. Each operation owns a private duplicate descriptor. Host calls run without blocking the dispatcher. Connect completion uses host poll and SO_ERROR. Blocking sends retain their offset across short host writes until completion or interruption, error or timeout. Guest nonblocking sends retain the immediate host result. Receive honors SO_RCVLOWAT, supports peeking and accumulates MSG_WAITALL data; host sends suppress SIGPIPE while preserving EPIPE. Guest signal delivery remains the syscall layer's responsibility.

Each blocking operation snapshots the kernel-normalized SO_SNDTIMEO or SO_RCVTIMEO and uses a monotonic elapsed budget. Zero means indefinite waiting. Nonblocking calls return the kernel's immediate result. Receive/send/accept timeout returns EAGAIN; connect timeout returns EINPROGRESS. A signal completes a pending operation with EINTR or an already received partial result. The dispatcher run budget does not become a socket timeout.

Socket options pass through the kernel. SO_RCVTIMEO and SO_SNDTIMEO translate between ARM32's two signed 32-bit fields or ARM64's two signed 64-bit fields and the host timeval. Address outputs honor the guest's capacity and publish the kernel's actual length.

The existing poll, ppoll, select and pselect6 syscall entries use `ReadinessOperation`. It snapshots guest pollfd or fd-set inputs, calls `FileIO.poll` without blocking, retains results across readiness checks and publishes guest outputs only on synchronous completion or task resumption. It returns actual revents, including POLLERR, POLLHUP and POLLNVAL, and honors monotonic timeout budgets and EINTR. Select returns EBADF for invalid descriptors and counts each ready set bit. Unsupported descriptor implementations fail explicitly rather than reporting requested events as ready.

Null ppoll/pselect signal masks use the existing task signal mechanism. Non-null temporary masks remain unsupported because the dispatcher does not implement atomic pending-signal promotion and mask restoration. Those calls fail with an explicit capability exception; an invalid kernel sigset size returns EINVAL.

`AndroidFileIO.accept(addr, addrlen, flags, installer)` installs the accepted wrapper only on the synchronous completion path or when the suspended task resumes. Readiness checks retain host results without writing guest memory or allocating guest descriptors. A nonnegative installer result transfers the accepted wrapper to the descriptor table. A negative result or exception closes the uninstalled host descriptor. Waiter replacement, task destruction and factory destruction cancel pending operations and release their private descriptors. Factory destruction precedes guest descriptor closure at emulator shutdown.

## Explicit loopback validation

`NativeTcpLoopbackIntegrationTest` is opt-in. Its single lifecycle runs both guest ABIs and both IP families against independent host loopback peers and a same-emulator pthread peer. Assertions cover externally received bytes, a one-megabyte blocking send, kernel errno, socket timeout round trips, nonblocking readiness, fd sets spanning machine words, descriptor status sharing and independent `/proc/self/fd` resource snapshots. The host socket provider initializes before the resource baseline so its process-lifetime JDK descriptor belongs to the baseline. Tests do not connect to business networks.

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

Kernel contracts: https://man7.org/linux/man-pages/man7/tcp.7.html, https://man7.org/linux/man-pages/man7/socket.7.html, https://man7.org/linux/man-pages/man2/connect.2.html, https://man7.org/linux/man-pages/man2/accept.2.html, https://man7.org/linux/man-pages/man2/recv.2.html, https://man7.org/linux/man-pages/man2/poll.2.html and https://man7.org/linux/man-pages/man2/dup.2.html.
