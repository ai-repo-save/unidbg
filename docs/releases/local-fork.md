# Local fork release

## Source

- Fork SCM: `https://github.com/ai-repo-save/unidbg`.
- Upstream: `https://github.com/zhkl0228/unidbg`.
- Fork baseline: `129151171590d0cfaaf46f98f93f1ac5f97fc401`.
- License: Apache 2.0, retained in `LICENSE`.

The release uses committed, clean source descended from the baseline. The generated manifest records the baseline, exact source commit, Git tree ID and fork commit URL used by fixed-source builds. These identities are generated after the source commit, outside Git, so the versioned source contains no self-referencing release commit hash. Source publication to the fork is coordinated separately from the local Maven artifact release.

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

The default mode of `scripts/release.py` prepares a Linux x86_64 / JDK 21 build without Maven install or deploy. `--build-only` states the same choice explicitly. Each checkout has an exclusive preparation lock. Source commit/tree/status and file hashes are captured before and after compilation; a changing source snapshot is rejected.

```sh
uv run --script scripts/release.py --build-only \
  --java-home /usr/lib/jvm/java-21-openjdk-amd64 \
  --unicorn-source /absolute/unchanged-unicorn-source \
  --consumer-classpath-file /absolute/collector-runtime-dependencies.txt \
  --build-output /absolute/unidbg-checkout/target/evaluation-build/new-run \
  --offline
```

Unicorn source must be the unchanged 2.1.4 commit `8028ec436f2d9376525352dd38ed9ed6b9f6be10`. The consumer classpath explicitly supplies the collector's Jackson, picocli and logging dependencies, not another unidbg core. Maven exports the selected reactor's real runtime dependencies with core GAVs excluded; no version-name replacement or cache search selects them.

Preparation cleans child modules, rebuilds the Unicorn2 JNI binding and host JVM monitor bridge, stages both under their module's `target/generated-resources/natives/linux_64`, and only then runs Maven resources/Java compilation. The tracked legacy Linux Unicorn JNI resource is excluded. Generated bytes must match the effective classes resources. The script copies the three core classes/resource directories into an immutable per-run snapshot and writes `build-identity.json` and `classpath.txt`. The identity states `kind=build`, `published=false`, the declared POM version, full ordered directory/JAR hashes, native resources, tools and commands. A declared 0.13.2 POM does not make changed development bytes a published 0.13.2 artifact.

Existing release directories and Maven GAVs remain untouched. The same explicit collector can consume the snapshot after independently checking actual class/resource origins. A completed build is not a semantic test verdict. Preparation logs remain under `logs/`; failed attempts retain their source/build evidence under the new output directory.

## Release

Requirements: Git, uv, JDK 21 and the repository Maven Wrapper (Maven 3.9.9). Maven uses `~/.m2/settings.xml` for configured HTTP/HTTPS proxies; configure the local proxy there when network access requires it.

From the repository root:

```sh
uv run --python 3.12 scripts/release.py --release \
  --baseline 129151171590d0cfaaf46f98f93f1ac5f97fc401 \
  --java-home /usr/lib/jvm/java-21-openjdk-amd64 \
  --repository target/release-0.13.2
```

`--help` lists repository and Maven cache options. `--repository` selects a Maven-layout directory below this project's `target/` and defaults to `<checkout>/target/release-0.13.2`. Maven file deploy creates or reuses that directory and updates artifacts and metadata according to normal Maven semantics. The entry point records the latest completed release in `release-manifest.json`. Maven `clean` selects only the child modules listed in the parent POM; it does not clean the parent project's `target/` or the release repository.

The entry point performs these steps with JDK 21:

1. Verify clean committed source, baseline ancestry and toolchain.
2. Clean the compilation modules with `-pl <child-module-paths> clean`, excluding the parent POM project.
3. Run `JniProxyReflectionAccessIntegrationTest`, `JniProxyExceptionIntegrationTest`, `JniOnLoadResultIntegrationTest`, `JniProxyUnpackIntegrationTest` and `JniPrimitiveIntegrationTest` separately with `-pl unidbg-android -am -Dmaven.test.skip=false -Dtest=<single-class> -Dsurefire.failIfNoSpecifiedTests=false test`. Each report must contain exactly one passed test and zero skipped tests. The reflection-access test composes the default `ProxyJni` through a JDK `Jni` interface proxy, exercising the protected extension with the existing exact JNI results; the exception test uses the default factory.
4. Run `install org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy -Dgpg.skip=true -DaltDeploymentRepository=local-release::file://<repository>`. The ordinary install and file-repository deployment use the same Maven session and attached artifacts. Existing Javadoc production remains enabled; local GPG signing is skipped. The Central publishing lifecycle is not invoked.
5. Write the release manifest with source identities, coordinates, produced POM/JAR paths, exact commands and test results.

Default outputs:

- Maven-layout repository: `target/release-0.13.2/`.
- Ordinary Maven installation/cache: `~/.m2/repository/`.
- Provenance: `target/release-0.13.2/release-manifest.json`, with source identities, toolchain, exact commands, test results, stable GAVs and produced artifact paths.
- License: `target/release-0.13.2/LICENSE`.
- Structured release events: `logs/release-<run-id>.jsonl`.
- Original Maven output: `logs/release-<run-id>.maven.log`.

stdout contains the final JSON result. Events and subprocess diagnostics go to stderr. Java and Maven version probes use the same command boundary as build commands; their combined stdout/stderr is also persisted in the per-run Maven log. A failed probe retains its original output in the structured failure event and the propagated subprocess exception. Failure returns exit code 1; cancellation returns 130 and terminates the active Maven process group. A failed attempt may leave partial Maven installations or file-repository updates. The manifest records the last completed invocation. Retain the two per-run log files through release acceptance, then remove them when no longer needed for diagnosis. No automatic retention cleanup runs.

## Release entry point verification

Run the filesystem-preservation integration test explicitly with JDK 21. It creates a minimal Maven fixture under `target/`, runs only the fixture's clean command, and independently reads the retained published files. It performs no install or deployment.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
uv run --python 3.12 scripts/test_release.py \
  ReleaseIntegrationTest.test_clean_preserves_published_files
```

Run the subprocess-diagnostic integration test separately. Its two local child processes emit fixed stdout/stderr and exit with code 23. The test independently reads the persistent raw and JSON logs and compares the complete emitted diagnostics.

```sh
uv run --python 3.12 scripts/test_release.py \
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
