# JNI_OnLoad 返回值公开 API 缺口

## 来源与调查

调用方需要记录实际 `JNI_OnLoad` 返回的 JNI 版本。fork `0.10.0` 的 `DalvikModule.callJNI_OnLoad(Emulator<?>)` 返回 `void`，内部取得 `Symbol.call` 的结果，执行 `BaseVM.checkVersion`，并在 `finally` 中清理局部引用。`VM.callJNI_OnLoad(Emulator<?>, Module)` 同样返回 `void`。

Maven Central 的 [unidbg-android 元数据](https://repo.maven.apache.org/maven2/com/github/zhkl0228/unidbg-android/maven-metadata.xml) 在本次调查时列出的最新正式版为 `0.9.9`。[v0.9.9 的 DalvikModule](https://github.com/zhkl0228/unidbg/blob/ee936a4bedb02b1e2d61533275246c49ed390e22/unidbg-android/src/main/java/com/github/unidbg/linux/android/dvm/DalvikModule.java) 和 [上游 master 固定提交的 DalvikModule](https://github.com/zhkl0228/unidbg/blob/718997e80ce508c7f72ee7a71cdfd8f10cf3db3f/unidbg-android/src/main/java/com/github/unidbg/linux/android/dvm/DalvikModule.java) 均只提供该 `void` 入口。fork 使用独立坐标；本次查询其 Central 元数据返回 HTTP 404，本地正式基线为 `0.10.0`。

`Module.callFunction` 和 `Symbol.call` 能返回 native 数值，但不承担 VM 的版本校验和局部引用清理。调用方直接使用它们需要复制底座的保护流程。反射访问 `BaseVM` 的包内方法也无法构成正式 API。

这是公开 API 缺口，现有 `void` 方法的执行行为本身符合其合同。此文档供用户决定是否向上游提交 issue；本次不提交 issue 或推送源码。

## 合同与修复

[JNI Invocation API 的 JNI_OnLoad 规范](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/invocation.html#jni_onload) 规定，函数返回其所需 JNI 版本，未知版本代表装载失败。unidbg 的现有校验接受 `JNI_VERSION_1_1`、`1_2`、`1_4`、`1_6` 和 `1_8`。

fork `0.11.0` 增加：

```java
public int callJNI_OnLoadWithResult(Emulator<?> emulator);
```

执行顺序为模块内符号查找、native 调用、版本校验、返回原始 `jint`。`finally` 保留局部引用及 pending exception 清理；全局引用继续由 VM 原有生命周期管理。native 调用或版本校验失败时传播异常，不返回伪造的成功值。

新方法要求模块导出 `JNI_OnLoad`。缺符号时按 `Module.callFunction` 的既有约定抛 `IllegalStateException`，此时尚未进入 JNI 调用，不清理局部引用。`0` 既不是缺符号占位值，也不是成功版本。需要可选初始化的调用方可继续使用原 `void` 方法；它在缺符号时保持 no-op，存在符号时委托新方法。

父 POM、全部子模块与自依赖统一使用 `0.11.0`。原方法描述符与行为保持不变；需要新入口的消费者显式依赖 `0.11.0`。

## 定向验证

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniOnLoadResultIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该显式集成测试使用真实 ARM32、ARM64 Unicorn2 模拟器与 guest SVC 入口，按 JNI 规范传递 JavaVM 和空 reserved 参数。测试精确比较五个支持版本的返回值，并通过公开 `VM.getObject` 读取句柄验证调用前及调用期间创建的局部引用已释放、全局引用仍指向原对象。版本 `0` 和 `JNI_ERR`（`-1`）必须失败且清理引用。原 `void` 入口也验证成功及失败清理；缺符号的两个入口分别验证 no-op 与显式失败，并保留未进入 JNI 调用的局部引用。

发行脚本单独执行该测试及已有两个 JNI 集成测试，生成包含精确命令、报告计数、源码提交与制品坐标的发行 manifest。测试不进入全量测试流程。
