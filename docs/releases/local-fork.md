# Local fork release

## Source

- Fork SCM: `https://github.com/ai-repo-save/unidbg`.
- Upstream: `https://github.com/zhkl0228/unidbg`.
- Fork baseline: `129151171590d0cfaaf46f98f93f1ac5f97fc401`.
- License: Apache 2.0, retained in `LICENSE`.

Build and release records include the source commit, Git tree, working-tree status and file hashes. Release records also include the optional baseline and fork commit URL. These values describe the source used; they do not require a clean or frozen checkout. Source publication to the fork is separate from local Maven installation and deployment.

## Coordinates

All reactor artifacts use group `io.github.ai-repo-save.unidbg` and version `0.13.2`:

- `unidbg` (parent POM).
- `unidbg-api`.
- `unidbg-android`.
- `unidbg-ios`.
- `unidbg-dynarmic`.
- `unidbg-hypervisor`.
- `unidbg-kvm`.
- `unidbg-unicorn2`.

Each JAR module provides its main, sources and Javadoc JARs. Java packages, native JNI symbols and the baseline patches retain their existing identities. Third-party libraries, including `com.github.zhkl0228:{unicorn,capstone,keystone,demumble}`, retain their published coordinates.

## Class-level JNI extension

Version `0.10.0` exposes the `Jni` interface throughout the class-level extension:

```java
// DvmClass
protected final void setJni(Jni jni);

// ProxyDvmClass
protected Jni createJni(ProxyClassLoader classLoader,
                        ProxyDvmObjectVisitor visitor, Jni fallbackJni);
```

Class-level JNI dispatch retains priority over VM-level JNI. `DvmMethod` and `DvmField` continue calling the interface overloads carrying their metadata objects. The default factory still creates `ProxyJni`; `JniFunction` and its fallback behavior are unchanged. Implementations can return a composed `Jni`, including a JDK interface proxy, without generating a `JniFunction` subclass. Existing source overrides returning `JniFunction` remain valid covariant overrides.

The protected method descriptors changed, so extensions compiled against `0.9.10` must be rebuilt against `0.10.0`. Java package names and native JNI symbols retain their identities. The existing `0.9.10` Maven artifacts and `target/release-repository/` remain available separately.

## JNI_OnLoad result

Version `0.11.0` adds `public int DalvikModule.callJNI_OnLoadWithResult(Emulator<?> emulator)`. It returns the actual native JNI version after the existing VM version check and releases local references in `finally`, including on native-call or validation failure. A missing module-local `JNI_OnLoad` throws `IllegalStateException`, following `Module.callFunction`'s missing-symbol contract; it does not synthesize a return value. Missing-symbol lookup does not start a JNI call or clear references.

The existing `void callJNI_OnLoad` retains its descriptor and missing-symbol no-op. When the symbol exists, it delegates to the result API. `VM.callJNI_OnLoad` retains the same behavior through its existing delegation. Existing callers retain their behavior; consumers of the current fork release declare `0.13.2`.

## Proxy host values

Version `0.12.0` adds `public static Object ProxyDvmObject.unpack(DvmObject<?> dvmObject)`, delegating to the existing proxy argument conversion. Null input returns null. A wrapper without a host value throws `UnsupportedOperationException`; Class values require the existing `ProxyClassFactory` configuration. Proxy object arrays return their original backing array, retaining component type and reference identity. The operation neither adds nor deletes VM references and does not transfer ownership. Non-proxy DVM arrays retain the existing conversion behavior rather than a backing-array identity guarantee.

Version `0.13.1` preserves primitive, array and ordinary Class identities while distinguishing eager metadata candidates from actual bindings. Proxy metadata resolves exact Class.getName forms through Commons Lang's public strict class resolver without initialization. The first actual Object/Class binding may replace the metadata candidate; a subsequent conflicting actual Class remains rejected. Reflection uses the actual binding when present and re-resolves cached members whose declaring Class differs. Explicit ProxyClassMapper priority remains unchanged for name-based metadata resolution.

## Primitive JNI and emulation failures

Version `0.13.0` completes all eight primitive instance/static field reads and `Call<Type>MethodA` slots on ARM32 and ARM64. Shared handlers decode JNI `jvalue` arguments, dispatch through the class/VM `Jni` binding and encode the architecture's return registers. `Jni` now includes the missing char/short/double field reads, static char/short/float/double field reads, instance double V binding and static byte/char/short/float/double V bindings, each with metadata and signature overloads. `AbstractJni`, `JniFunction`, opt-in `FallbackJni` and the standard proxy implement the new interface surface; direct `Jni` implementers must implement these methods when rebuilding against `0.13.0`.

Unchecked host implementation failures propagate from `Emulator.eFunc` / `Module.emulateFunction`; they no longer produce a fabricated `-1` return value. The emulation boundary logs the original stack and execution context. Dispatcher failure cleanup removes failed tasks and releases their resources before propagating; thread context switches and PopContext retain their existing control-flow handling. A Java target exception remains a JNI pending exception, observable through ExceptionOccurred and cleared through ExceptionClear. A legitimate native `-1` result remains a valid value.

## Boolean, char and long arrays

Version `0.13.0` adds public `BooleanArray(VM, boolean[])`, `CharArray(VM, char[])` and `LongArray(VM, long[])` wrappers through `ProxyDvmObject.createObject`. Their JNI New/Elements/Release/Region slots work on both architectures, with 8-bit jboolean, 16-bit jchar and 64-bit jlong storage. Each elements/critical acquisition owns an independent copy. Release mode 0 copies back into the original host array and frees that copy; JNI_COMMIT copies back while retaining it; JNI_ABORT frees without copying back. VM reference cleanup releases outstanding copies through the new protected `DvmObject.releaseAdditionalMemory()` hook. Existing wrappers retain their behavior.

## Native IPC and dispatch

Version `0.13.2` fixes ARM64 AF_UNIX SOCK_SEQPACKET socketpairs on Linux x86_64 using kernel sockets through JNA. SDK23 Bionic calls preserve packet boundaries, peeking, socket timeouts and descriptor lifetime while yielding native tasks for blocking I/O. The adapter returns raw Linux syscall errors for Bionic to set the calling thread's errno.

`Waiter.onDestroy(Emulator<?>)` releases an operation when its Waiter is replaced or its task is destroyed, independently of the existing DestroyListener. Pending native threads remain dispatchable after main returns. `runThreads` restores the caller's CPU context and stack, including TLS; debug logging adds no scheduling delay. Socket state changes carry correlated debug events.

The opt-in `NativeSeqpacketLifecycleIntegrationTest` validates the native IPC lifecycle separately from the release entry point's five JNI tests. Build its ARM64 fixture with the NDK and supply `-Dunidbg.nativeIpcLibrary=<fixture>` when invoking that test explicitly.

## Unpublished development preparation

The default mode of `scripts/release.py` prepares a Linux x86_64 native-backed build without Maven install or deploy. `--build-only` selects the same mode explicitly.

```sh
uv run --script scripts/release.py --build-only \
  --java-home /usr/lib/jvm/java-21-openjdk-amd64 \
  --unicorn-source /absolute/unicorn-source \
  --consumer-classpath-file /absolute/collector-runtime-dependencies.txt \
  --build-output target/evaluation-build/current \
  --offline
```

The selected Unicorn checkout supplies the engine source. Its actual commit and working-tree status are recorded. The consumer classpath supplies the collector's Jackson, picocli and logging dependencies. Maven exports the reactor's runtime dependencies with reactor core coordinates excluded.

Preparation cleans child modules, rebuilds the Unicorn2 JNI binding and host JVM monitor bridge, stages generated resources under each module's `target/generated-resources/natives/linux_64`, then runs Maven resources/Java compilation. The output classpath points directly to the three core `target/classes` directories and their dependencies. Maven `compile` includes `process-resources`, but neither phase builds these native libraries. Running only Maven can leave new Java native declarations paired with an old JNI library, or omit the monitor library.

For an incremental build with an existing Unicorn archive and exported core classpath, run the following from the unidbg checkout. `UNICORN_SOURCE` supplies the engine headers; `UNICORN_BUILD` contains `libunicorn.a`. Run any Maven clean before native preparation because clean removes generated resources.

```sh
export JAVA_HOME=/absolute/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
UNICORN_SOURCE=/absolute/unicorn-source
UNICORN_BUILD=/absolute/unicorn-build
JOBS=4

cmake -S backend/unicorn2/src/main/native -B target/unicorn-jni-build \
  -DUNICORN_HOME="$UNICORN_SOURCE" -DUNICORN_BUILD="$UNICORN_BUILD"
cmake --build target/unicorn-jni-build --parallel "$JOBS"
mkdir -p backend/unicorn2/target/generated-resources/natives/linux_64
cp -p target/unicorn-jni-build/libunicorn.so \
  backend/unicorn2/target/generated-resources/natives/linux_64/libunicorn.so
cmake -S unidbg-android/src/main/native/jvm-monitor \
  -B unidbg-android/target/native-monitor -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$PWD/unidbg-android/target/generated-resources/natives/linux_64"
cmake --build unidbg-android/target/native-monitor \
  --target unidbg_jvm_monitor --parallel "$JOBS"
./mvnw -pl unidbg-android,backend/unicorn2 -am process-resources
```

`process-resources` copies the staged libraries into `target/classes`. If Java sources also changed, run `./mvnw -pl unidbg-android,backend/unicorn2 -am compile` after native preparation. The existing exported classpath continues to reference these classes directories. Confirm the generated and effective resource pairs before packaging the runtime:

```sh
cmp backend/unicorn2/target/generated-resources/natives/linux_64/libunicorn.so \
  backend/unicorn2/target/classes/natives/linux_64/libunicorn.so
cmp unidbg-android/target/generated-resources/natives/linux_64/libunidbg_jvm_monitor.so \
  unidbg-android/target/classes/natives/linux_64/libunidbg_jvm_monitor.so
```

Install the prepared core through the runtime's existing development entry point. `CORE_CLASSPATH` selects the exported core classpath; `CONSUMER_CLASSPATH` selects the collector dependencies supplied during full preparation. The repository parameters select local Maven repositories for the runtime's fork dependencies.

```sh
RUNTIME_SOURCE=/absolute/unidbg-robolectric
CORE_CLASSPATH=/absolute/core/classpath.txt
CONSUMER_CLASSPATH=/absolute/collector-runtime-dependencies.txt
ROBOLECTRIC_REPOSITORY=file:///absolute/robolectric-repository
APK_PARSER_REPOSITORY=file:///absolute/apk-parser-repository

"$RUNTIME_SOURCE/gradlew" -p "$RUNTIME_SOURCE" \
  -PdevelopmentCoreClasspath="$CORE_CLASSPATH" \
  -PdevelopmentConsumerClasspath="$CONSUMER_CLASSPATH" \
  -ProbolectricRepository="$ROBOLECTRIC_REPOSITORY" \
  -PapkParserRepository="$APK_PARSER_REPOSITORY" \
  developmentInstall
```

`developmentInstall` packages the selected classes directories and their native resources into development core JARs, then installs the worker under `worker/build/development/install/unidbg-worker`. It does not build the native resources or change an installed JAR in place.

The build output directory may be reused. `build-identity.json` and `classpath.txt` are overwritten with the current build facts. The identity records `kind=build`, `published=false`, the POM version, source state, directory/JAR hashes, generated and effective native resources, tools and commands. Source-before/source-after JSON and patches record the working tree around compilation. These records are observational; consumers do not need an identity file to execute the classpath. Rebuilding updates the same classes directories.

A completed build reports compilation, not semantic test results. Logs remain under `logs/`; build records remain in the selected output directory until the caller removes or replaces them.

## Release

The release entry point uses Git, uv, the selected JDK and the repository Maven Wrapper. Maven uses `~/.m2/settings.xml` for configured HTTP/HTTPS proxies.

```sh
uv run --script scripts/release.py --release \
  --java-home /usr/lib/jvm/java-21-openjdk-amd64 \
  --unicorn-source /absolute/unicorn-source \
  --consumer-classpath-file /absolute/collector-runtime-dependencies.txt \
  --repository target/release-output
```

The reactor POMs supply the version. `--baseline` optionally records a Git baseline commit. `--repository` defaults to `target/release-<POM version>`; `--maven-local-repository` selects the Maven cache/install repository. Installation and deployment use ordinary Maven behavior, including replacement of existing coordinate files.

The entry point performs these steps:

1. Record source and toolchain information, then prepare the native-backed build.
2. Run `JniProxyReflectionAccessIntegrationTest`, `JniProxyExceptionIntegrationTest`, `JniOnLoadResultIntegrationTest`, `JniProxyUnpackIntegrationTest` and `JniPrimitiveIntegrationTest` separately. Record the actual Surefire results and fail if a selected test did not complete successfully.
3. Run package and record the native resources read from the core JARs.
4. Run Maven install and file-repository deploy with local GPG signing skipped. Read the installed and deployed native resources. This does not invoke the Central publishing lifecycle.
5. Write `release-manifest.json`, `release-identity.json` and LICENSE in the selected repository. Records include source, tests, classpath, file hashes, native resources, toolchain and commands.

stdout contains the final JSON result. Structured events and subprocess diagnostics go to stderr. JSON logs and raw Maven output are stored in `logs/release-<run-id>.jsonl` and `logs/release-<run-id>.maven.log`. Java and Maven probes use the same command boundary and retain their original output. Failure returns 1; cancellation returns 130 and terminates the active process group. A failed release may leave partial Maven installations or file-repository updates. The caller manages log retention; no automatic cleanup runs.

## Release entry point verification

Run each integration test explicitly. The clean fixture validates that child-module cleanup preserves files outside those module outputs. It performs no install or deployment.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
uv run --script scripts/test_release.py \
  ReleaseIntegrationTest.test_clean_preserves_published_files
```

The diagnostic fixture runs local child processes that emit stdout/stderr and exit with code 23, then independently reads the persisted raw and JSON logs.

```sh
uv run --script scripts/test_release.py \
  ReleaseIntegrationTest.test_probe_failures_preserve_original_output
```

## Consume

Same-user Maven and Gradle builds can use the ordinary Maven installation. To consume the exported repository explicitly, configure its absolute file URL:

```xml
<repository>
    <id>unidbg-local-release</id>
    <url>file:///home/bluebird/git/unidbg/target/release-0.13.2</url>
</repository>
```

For example, the Android runtime dependency is `io.github.ai-repo-save.unidbg:unidbg-android:0.13.2`. Transitive reactor dependencies resolve from the same repository. Third-party dependencies still resolve from their configured upstream repositories or Maven cache. The exported fork repository is not a complete offline dependency mirror.
