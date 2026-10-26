# Primitive 与 array Class 身份

## 实测缺口

fork `0.12.0` 通过 JNI 读取 Integer.TYPE 或调用 Class.forName("[J") 后，底座返回 value 为 null 的 DvmClass，公开 unpack 抛 UnsupportedOperationException。Class.forName("java.lang.String") 正常。

ProxyDvmObject.createObject 已经收到实际 Class，却只将 Class.getName 交给 VM 解析。ProxyDvmClass 再用 ClassLoader.loadClass 解析名称。该 JDK API 接受普通类的二进制名称，不解析 primitive 名称 `int` 或数组名称 `[J`，导致已知宿主 Class 丢失。

[JDK Class.getName](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Class.html#getName()) 明确规定 primitive 名称与数组编码。[Class 身份](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html#jvms-5.3) 由名称和定义 ClassLoader 共同决定；根据名称构造替代对象不能替代保留实际 Class。

## 修复

`0.13.0` 在 proxy 包装负责层处理：

- getObjectType 继续复用 VM 的类缓存和继承元数据，并将已经持有的实际 Class 通过包内 bindHostClass 绑定到 ProxyDvmClass。
- 同一 VM 类名已绑定其他 Class 身份时明确拒绝，并保留现有/输入 ClassLoader 诊断，不静默更换已缓存 Class。
- ProxyClassLoader 使用已依赖的 Commons Lang 3.21.0 正式 getClassStrict(loader, name, false) 解析 Class.getName 的合法形式，不触发类初始化。
- 返回 Class 的 getName 必须与输入精确一致；不接受规范名替换、空白修补或其他名称推断。既有显式 ProxyClassMapper 仍按其合同优先处理。

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

断言对象为实际 Class 身份，不用类名相等代替，也不复制底座内部解析算法作为预期。
