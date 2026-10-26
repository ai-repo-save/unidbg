# Primitive JNI 槽位与宿主失败传播

## 实测问题

unidbg `0.12.0` 的真实 ARM64 JNI 重放出现以下结果：

| 调用 | 正确结果 | 实际结果 |
| --- | --- | --- |
| Math.abs(-2.5f) | 2.5f | 0.0f |
| Math.sqrt(9d) | 3d | 0.0d |
| Short.MAX_VALUE | 32767 | -1 |
| Double2(3d, 3d).x | 3d | 残留浮点寄存器数据 |

调用方获得普通成功响应，未收到宿主实现异常。问题涉及 GetShortField（99）、GetDoubleField（103）、GetStaticShortField（149）、GetStaticFloatField（152）、GetStaticDoubleField（153）、CallStaticFloatMethodA（137）和 CallStaticDoubleMethodA（140）。调查同族槽位后，ARM32 与 ARM64 均发现其他 primitive 占位实现。

## 标准与责任

[JNI Functions 规范](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html) 定义表索引、Get<Type>Field 和 Call<Type>MethodA 的结果类型，以及 jvalue 数组参数。byte、short 为有符号值，char 为无符号 16 位值，long 为 64 位，float/double 分别为 IEEE 754 binary32/binary64。

[AAPCS64](https://github.com/ARM-software/abi-aa/blob/2025Q1/aapcs64/aapcs64.rst) 定义 ARM64 浮点结果通过 v0 返回；[AAPCS32](https://github.com/ARM-software/abi-aa/blob/2025Q1/aapcs32/aapcs32.rst) 定义基础调用约定的 r0/r1 结果布局。Android ARM32 JNI 使用基础过程调用约定。

修复责任分为三层：

1. DalvikVM / DalvikVM64 将 JNI 表槽位绑定到正式 Jni 路由，并按架构写入结果寄存器。占位槽位必须由底座实现，消费者不切换调用形式。
2. Jni、DvmField、DvmMethod 与标准 proxy 提供同族 primitive 绑定。JValueList 按 JNI 类型读取 jvalue，不使用宿主 wchar 宽度读取 jchar。
3. AbstractEmulator 管理执行失败边界。原 catch 将 RuntimeException 转为 -1，dispatcher 随后将其视为成功结果。宿主异常必须传播，dispatcher 必须清理失败任务。

## 修复

`0.13.0` 统一实现八种 primitive 的实例/静态字段读取与 MethodA，共 32 个槽位。共享 PrimitiveJniSupport 承担槽位查找、jvalue 解析、类级/VM 级 Jni 分派和返回寄存器编码，双架构 VM 使用同一实现。

补充 Jni 的正式 metadata/signature 重载：实例 char/short/double 字段，静态 char/short/float/double 字段，实例 double V 方法及静态 byte/char/short/float/double V 方法。A 入口在底座内部使用既有 VaList/JValueList 参数抽象，不改变消费者调用的 JNI 槽位。AbstractJni、JniFunction 与 proxy 同步实现这些成员。新增公开接口使用 MINOR 版本，直接实现 Jni 的调用方重新实现新增成员并编译。

ARM64 的 float/double 写入 Q0；ARM32 的 float 写入 r0 位表示，double/long 写入 r0:r1。jchar 读取无符号 16 位，jbyte 保留有符号值。

宿主 RuntimeException/Error 在执行边界记录原堆栈、执行地址、栈地址、任务和耗时后传播，不再返回伪造的 -1。Android syscall handler 同步传播宿主 Error。dispatcher 清理失败 task/signal 及中止的 main task，原 ThreadContextSwitchException、PopContextException、正常退出和返回值保持各自控制流。合法 native -1 不作为失败标记。

Java 目标方法抛出的异常继续作为 JNI pending exception，通过 ExceptionOccurred 读取、ExceptionClear 清除；这与 JNI 实现本身失败的宿主异常边界不同。消费者不通过猜测 -1 或 Q0 判断执行失败。

此文档供用户决定是否向上游提交 issue；本次不提交 issue 或推送源码。不包含 primitive array 新 API、primitive 字段写入或其他调用形式的扩展。

## 定向验证

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniPrimitiveIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

单个整体测试复用每种架构的真实 Unicorn2 VM，验证全部 32 个槽位。JNI 字段结果与宿主 Java 对象公开 getter 返回值精确比较；A 调用写入 Java 对象的参数状态由公开 getter 独立读回。样本覆盖负 byte/short/int、无符号高位 char、高 32 位非零 long、float/double 以及 jvalue 未使用字节中的非零数据。Math.abs(-2.5f) 与 Math.sqrt(9d) 验证原始失败样本。

实例和静态 Java 方法抛出同一真实 IOException 后，测试通过 JNI 读取原异常对象，清除 pending 状态，再继续调用。Jni 实现分别抛出 UnsupportedOperationException 与 AssertionError 时，测试断言同步调用向宿主传播原异常对象、公开 dispatcher 任务计数恢复为零，随后再次执行 double A 调用并验证结果及 Java 对象副作用。测试不对 pending exception 时的 primitive 返回值作成功断言。

发行脚本显式执行该测试及其他受影响 JNI 集成测试，不进入默认全量测试流程。
