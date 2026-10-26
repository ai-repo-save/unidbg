# Boolean、char、long 数组包装

## 缺口

调用协议已支持全部 primitive array。fork `0.12.0` 的 ProxyDvmObject.createObject 能包装 byte/short/int/float/double 数组，但 boolean[]、char[]、long[] 进入拒绝 primitive array 的分支。相应 New、GetElements、ReleaseElements、SetRegion 槽位也未实现。

这三类输入是调用方的既有合同，不由消费者构造私有 wrapper 或复制底座数组转换。

## 正式 API

fork `0.13.0` 增加公开 wrapper：

```java
public BooleanArray(VM vm, boolean[] value);
public CharArray(VM vm, char[] value);
public LongArray(VM vm, long[] value);
```

ProxyDvmObject.createObject 自动选择这些类型，unpack 返回原宿主数组。wrapper 保留原数组引用；SetRegion 与 release copy-back 均原位修改其元素。

[JNI Functions 规范](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html) 规定 jboolean 元素按 8 位、jchar 按无符号 16 位、jlong 按 64 位存储。新 wrapper 的 isCopy 也仅写一个 jboolean 字节。ARM32 与 ARM64 的槽位共享同一实现。

覆盖范围为三类数组的 New、GetElements、ReleaseElements、GetRegion、SetRegion 和通用 Get/ReleasePrimitiveArrayCritical。GetRegion 和 critical 分派复用现有正式入口。负 New 长度产生 JNI pending NegativeArraySizeException；区域越界在写入前拒绝，产生 pending ArrayIndexOutOfBoundsException。

## 复制区生命周期

CopiedPrimitiveArray 统一管理三类 wrapper 的复制区。每次 GetElements 或 GetPrimitiveArrayCritical 分配独立复制区，零长度数组也返回可配对释放的有效指针。

- mode 0：原位 copy-back，释放该指针对应的复制区。
- JNI_COMMIT：原位 copy-back，保留该复制区；调用方之后继续修改并配对释放。
- JNI_ABORT：释放该复制区，不 copy-back。

释放指针必须来自该 wrapper 的有效获取操作，mode 必须为上述三种值，违反合同则拒绝。不同复制区的释放互不影响。

DvmObject 通过新增 protected releaseAdditionalMemory() hook 释放子类额外分配，原 final onDeleteRef 仍保证主 memoryBlock 在 finally 中释放。三类新 wrapper 在 VM 引用清理时释放所有尚未配对的复制区。此功能沿用既有 VM 引用清理时机，例如 DeleteGlobalRef 与局部引用帧清理；不改变其他数组 wrapper 或 JNI 引用表的行为。

## 定向验证

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniProxyUnpackIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

已有单个整体集成复用 ARM32、ARM64 的 Unicorn2 VM，新增以下实际 JNI 操作：

- 三类数组通过 Java Object 参数 roundtrip，公开 unpack 返回原引用。
- JNI IsInstanceOf、GetArrayLength、Get/SetRegion 验证类型、长度、值与原宿主数组写回。预期为独立宿主 primitive array 和 JNI 规定的字节布局。
- 两次 GetElements 创建独立复制区。修改第一份后，第二份仍保留获取时内容。COMMIT 保留第一份，最终 mode 0 释放它；第二份 ABORT 不改变宿主值。
- 通用 critical 入口完成真实 copy-back。
- DeleteGlobalRef 清理未释放的两份复制区，公开 guest memory map 完整恢复为分配前的地址/大小/权限集合；普通 release 也作同样验证。
- 零长度数组获取/释放、负长度和区域越界后的 pending exception 与继续调用。

所有数组内容通过宿主数组独立读取；资源释放通过真实 guest 映射观察，不通过检查内部释放函数是否调用判断。此功能不依赖反射访问底座私有实现。

是否向上游提交缺口 issue 由用户决定；本次不提交 issue 或推送源码。
