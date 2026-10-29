# Native JVM invocation

## Host contract

`JvmInvocationBridge` executes standard host JNI operations on the guest's fixed platform Java thread. Its inputs are host objects, actual host classes, original declared reflection metadata and boxed arguments. Reflection metadata supplies names and types; invocation never uses `Method.invoke`, accessible public replacements, `Constructor.newInstance` or `Unsafe`.

The public static API is:

```java
Result allocate(Class<?> targetClass);
Result construct(Object sameReceiver, Class<?> dispatchClass,
                 Constructor<?> ctor, Object[] boxedArgs);
Result nonvirtual(Object sameReceiver, Class<?> dispatchClass,
                  Method original, Object[] boxedArgs);
```

`Result.value()` returns a host reference, an exact primitive wrapper, or null for void. `Result.targetThrowable()` returns the original Throwable from the standard JNI operation, or null on success. Parameter, loader, boxing and bridge failures throw directly rather than becoming target failures. The JNI adapter translates only `targetThrowable()` into its target-exception wrapper; backend completion owns guest pending exceptions and reference encoding.

Arguments require exact primitive wrappers and assignable reference values. The bridge snapshots the argument array without copying its referents. `MethodType` generates the descriptor from actual host types. C converts these values into a local `jvalue` array and does not access backend state, guest memory or guest reference tables.

`allocate` uses `AllocObject`, which does not invoke a constructor. `construct` obtains `<init>` from the explicit dispatch class and executes `CallNonvirtualVoidMethodA` on the existing receiver. Its constructor must be declared by that class. Successful construction returns null; the original host object, wrapper, references and monitor identity remain unchanged. Failed construction retains the original object and actual constructor side effects. Ordinary JNI constructor calls additionally require method IDs from the receiver's real class; the JNI adapter enforces that entry-specific requirement.

`nonvirtual` obtains the ID with `GetMethodID(dispatchClass, name, hostDescriptor)` and passes the same explicit class into the appropriate `CallNonvirtual*MethodA`. It supports all eight primitive returns, objects, arrays and void, including default interface methods. The member's declaring class does not replace the explicit dispatch class. `FromReflectedMethod` converts metadata into an ID but does not implement this explicit-class lookup contract.

Class initialization, allocation, constructors, target methods and host visitors run on the fixed host thread. Backend code performs guest decoding before submission and normalization after completion. A running invocation is not replayed on cancellation, and cancellation does not roll back arbitrary Java side effects. Native temporary resources are confined to each call.

## Production routing

Both JNI tables route every ordinary/V/A Nonvirtual return family through `JniInvocationSupport.nonvirtual` and `HostJni.invokeNonvirtual`. Ordinary Nonvirtual arguments start after the explicit class and method ID; V/A pointers occupy that same argument position. Proxy binding retains the explicit host dispatch class and declared member descriptor, independently of virtual reflection caches. `HostJni.submit` uses the existing guest worker and backend completion.

`ProxyJni.invokeAllocation` calls `allocate` without looking for a constructor. Ordinary instance calls carrying a constructor ID and Nonvirtual constructor calls use `construct` on the unpacked receiver. `NewObject*` retains its separate new-instance path. Runtime JNI composition forwards the Nonvirtual capability, including its explicit class, receiver and completion.

## Native resource

`JvmNativeLibrary` loads `unidbg_jvm_monitor` for both monitor and invocation bridges. CMake builds `jvm-monitor.c` and `jvm-invocation.c` into that target. The existing release entry point builds and verifies the complete generated resource at `natives/linux_64/libunidbg_jvm_monitor.so`; no second native artifact is required. Missing support fails initialization.

## Explicit integration gate

`JniNonvirtualConstructionIntegrationTest` is opt-in. A single test executes real NDK fixtures through production ARM32 and ARM64 JNI tables, Proxy binding and the host bridge. It installs no replacement SVCs.

Java compiler-generated superclass and interface-super calls provide the dispatch oracle. Independent Java observations and native result memory cover all return families and mixed arguments, Base/Derived side effects, private declared methods, constructor-free allocation, the same receiver before/inside/after construction, global-reference identity, original thrown objects, host visitors, class initialization and fixed worker identity. `Thread.holdsLock` observes the actual monitor before and after construction. Guest execution hooks independently identify the backend owner thread.

Run from the checkout on Linux x86_64 with JDK 21 and the NDK. The incremental preparation below uses an existing Unicorn archive and its source headers. Run any Maven clean before native preparation because it removes generated module resources. Full preparation remains available through `scripts/release.py --build-only`. These commands do not install or deploy Maven artifacts.

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/28.2.13676358"
UNICORN_SOURCE="$HOME/git/references/unicorn"
UNICORN_BUILD="$PWD/target/unicorn-build"
cmake -S backend/unicorn2/src/main/native -B target/unicorn-jni-build \
  -DUNICORN_HOME="$UNICORN_SOURCE" -DUNICORN_BUILD="$UNICORN_BUILD"
cmake --build target/unicorn-jni-build --parallel 4
mkdir -p backend/unicorn2/target/generated-resources/natives/linux_64
cp -p target/unicorn-jni-build/libunicorn.so \
  backend/unicorn2/target/generated-resources/natives/linux_64/libunicorn.so
cmake -S unidbg-android/src/main/native/jvm-monitor \
  -B unidbg-android/target/native-monitor -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$PWD/unidbg-android/target/generated-resources/natives/linux_64"
cmake --build unidbg-android/target/native-monitor \
  --target unidbg_jvm_monitor --parallel 4
./mvnw -B -pl unidbg-android,backend/unicorn2 -am compile
bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
fixture=unidbg-android/src/test/native/android/jni-nonvirtual-construction.c
output="$PWD/unidbg-android/target/jni-invocation"
mkdir -p "$output"
"$bin/armv7a-linux-androideabi23-clang" -O2 -fPIC \
  -fno-stack-protector -fno-builtin -nostdlib -shared -Wl,--no-undefined \
  -Wall -Wextra -Werror "$fixture" -o "$output/arm32.so"
"$bin/aarch64-linux-android23-clang" -O2 -fPIC -mno-outline-atomics \
  -fno-stack-protector -fno-builtin -nostdlib -shared -Wl,--no-undefined \
  -Wall -Wextra -Werror "$fixture" -o "$output/arm64.so"
./mvnw -B -pl unidbg-android -am -Dmaven.test.skip=false \
  -Dtest=JniNonvirtualConstructionIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dunidbg.jniInvocationDirectory="$output" test
```

Contracts: https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html, https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html and https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/MethodType.html#toMethodDescriptorString().
