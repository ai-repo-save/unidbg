# Proxy Class 身份与 metadata 绑定

## 实测缺口

fork `0.12.0` 通过 JNI 读取 Integer.TYPE 或调用 Class.forName("[J") 后，底座返回 value 为 null 的 DvmClass，公开 unpack 抛 UnsupportedOperationException。Class.forName("java.lang.String") 正常。

ProxyDvmObject.createObject 已经收到实际 Class，却只将 Class.getName 交给 VM 解析。ProxyDvmClass 再用 ClassLoader.loadClass 解析名称。该 JDK API 接受普通类的二进制名称，不解析 primitive 名称 `int` 或数组名称 `[J`，导致已知宿主 Class 丢失。

`0.13.0` 的真实 Security.getProviders() 重放又暴露候选与实际绑定混淆：构造器按名称解析得到 sandbox 的 XMLDSigRI Class，Provider 对象却由平台 ClassLoader 创建。两者是正常的不同来源，构造器候选不能作为已经实际绑定的 Class。原 bindHostClass 因 value 非空而拒绝真实 Provider[]。

[JDK Class.getName](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Class.html#getName()) 明确规定 primitive 名称与数组编码。[Class 身份](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html#jvms-5.3) 由名称和定义 ClassLoader 共同决定；根据名称构造替代对象不能替代保留实际 Class。

## 修复

`0.13.1` 在 proxy 包装和解析负责层区分两种来源：

- 构造器按名称 eager 解析的 value 是 metadata 候选，不标记为实际绑定。
- getObjectType 继续复用 VM 的类缓存和继承元数据，将实际 Object/Class 携带的 Class 通过包内 bindHostClass 绑定。第一次实际绑定可替换候选；已实际绑定后，另一个不同 Class 仍被拒绝，并保留 ClassLoader 诊断。
- ProxyClassLoader 的包内 DvmClass 解析入口优先返回实际绑定的 Class。实例方法、构造器、静态方法和字段使用同一入口，避免后续又按名称选回候选。
- 反射成员缓存只在声明 Class 精确匹配当前解析 Class 时复用，否则重新解析。候选期产生的缓存不跨实际绑定错误复用。
- 尚无实际绑定时，既有显式 ProxyClassMapper 保持名称解析优先级；其后使用 Commons Lang 3.21.0 正式 getClassStrict(loader, name, false)，不触发类初始化。
- 解析结果的 Class.getName 必须与输入精确一致，不接受名称猜测或空白修补。primitive、array 和普通 Class 的真实身份继续保留。

[Commons Lang 固定源码](https://github.com/apache/commons-lang/blob/155db038d73fe0ea49612d42ae70f6e48ee3ebb9/src/main/java/org/apache/commons/lang3/ClassUtils.java) 提供 primitive/array 名称解析与严格空白处理的公开 API。底座不添加 primitive 手写映射，也不通过反射修改 VM 或消费者对象。

现有 ProxyClassFactory 配置仍是公开 unpack Class 的前提；未绑定宿主 Class 的普通 DVM 元数据不被猜测修补。Class.forName 的业务执行继续调用 JDK 原方法，未改变它对输入或异常的合同。

是否向上游提交此缺口由用户决定；本次不提交 issue 或推送源码。

## 定向验证

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniProxyUnpackIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

扩展现有单个整体集成，在原 ARM32/ARM64 VM 内执行：

- 九种 primitive/void Class、primitive/对象数组 Class、多维数组 Class 与普通 Class 通过 Java Object 参数 roundtrip，公开 unpack 精确返回原 Class 引用。
- JNI GetStaticObjectField 读取 Integer.TYPE，精确等于宿主 int.class。
- JNI 调用 Class.forName 读取 `[J`、`[[J`、`[Ljava.lang.String;` 和 `java.lang.String`，精确等于独立宿主 Class literal。
- JNI GetObjectClass 读取 boolean[]、char[]、long[] 的类，精确等于原宿主数组的 Class。

同一整体测试还在隔离 metadata loader 下直接通过 JNI 调用真实 Security.getProviders()。测试逐项比较宿主 Provider 引用、真实 Class、getName/getInfo，并执行实际实例方法和字段读取。metadata loader 仅隔离已安装 Provider 的 Class 字节，不修改注册表或伪造 Provider[]。

一个具有独立静态字段的最小 Provider fixture 先在候选期调用方法并缓存字段，再绑定真实对象。测试通过实际宿主静态值与实例写入副作用验证解析和缓存已切换；输入第二个实际 Class 时仍精确断言冲突，并验证原绑定保持不变。

该扩展测试在已发布 `0.13.0` JAR 上直接复现真实 Provider[] 的 Class 冲突，在修复源码上通过。断言使用实际 Class 和 Provider 身份，不用名称相等代替，也不复制底座解析算法作为预期。
