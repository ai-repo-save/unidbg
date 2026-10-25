# JDK 实现类的 JNI 反射访问失败

## 样本

JDK 21 下，代理调用 `Collections.enumeration(...).hasMoreElements()` 时出现：

```text
java.lang.reflect.InaccessibleObjectException:
Unable to make public boolean java.util.Collections$3.hasMoreElements() accessible:
module java.base does not "opens java.util" to unnamed module
```

调用链为 `ProxyInstanceJni.callBooleanMethodV` → `ProxyUtils.findMethod` → `DvmMethod.setMember` → `Method.setAccessible(true)`。匿名类编号由 JDK 实现决定。

上游 issue #332 的 [2021-10-15 评论](https://github.com/zhkl0228/unidbg/issues/332#issuecomment-944086442) 提供了同类真实样本：

```text
Unable to make public java.lang.Object[] java.util.HashMap$KeySet.toArray() accessible:
module java.base does not "opens java.util" to unnamed module
```

该评论的调用链经过 `ProxyJni.callObjectMethod`、`ProxyUtils.findMethod` 和 `DvmMethod.setMember`。

实际 DroidGuard native 调用在通过集合方法后，继续遇到 public 实现类所在包未向调用方导出的情况：

```text
Unable to make public byte[] sun.security.x509.X509CertImpl.getEncoded()
throws java.security.cert.CertificateEncodingException accessible:
module java.base does not "exports sun.security.x509" to unnamed module
```

该调用经过 `ProxyInstanceJni.callObjectMethodV`。公开调用入口为 `java.security.cert.Certificate.getEncoded()`。

## 复现

集成测试通过 JNI 方法表获取具体实现类的方法 ID，并调用以下宿主对象：

| 对象 | 方法 | 公开调用入口 | 精确预期 |
| --- | --- | --- | --- |
| `Collections.enumeration(Collections.singleton("entry"))` | `hasMoreElements()`、`nextElement()` | `Enumeration` | `true`、`"entry"`、`false` |
| 含单项 `"key" → "value"` 的 `HashMap` 的 `keySet()` | `toArray()` | `Set` | 仅包含 `"key"` 的数组 |
| `CertificateFactory` 从固定 X.509 DER 生成的证书 | `getEncoded()` | `Certificate` | 完整原始 DER 字节 |

使用 JDK 21 执行单项 JNI 集成测试：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniProxyReflectionAccessIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

未解析公开声明的源码在集合样本上失败。仅使用 `MethodUtils.getAccessibleMethod` 的源码可通过集合样本，但加入证书样本后仍在 JDK 21 精确复现 `X509CertImpl.getEncoded()` 的访问异常。

仓库原有测试资源没有独立证书。新增 fixture 为 Let's Encrypt 发布的 [ISRG Root X1 PEM](https://letsencrypt.org/certs/isrgrootx1.pem)，保存于 `unidbg-android/src/test/resources/certificates/isrg-root-x1.pem`。PEM 文件 SHA-256 为 `22b557a27055b33606b6559f37703928d3e4ad79f110b407d04986e1843543d1`。测试从固定 PEM 解码原始 DER，在宿主解析证书，再通过 JNI `GetByteArrayRegion` 读取代理调用返回的完整字节数组，与原始 DER 精确比较。

## 原因

`ProxyDvmObject` 保留宿主对象的具体实现类型。`ProxyUtils.matchMethodTypes` 对实例方法优先检查该类型的 `getDeclaredMethods()`，随后查找父类。它选出的 public 方法可能由非公开类声明，也可能由未导出包中的 public 类声明。

`DvmMethod.setMember` 无条件调用 `setAccessible(true)`。`java.base` 导出 `java.util`，但没有向调用方开放该包的深反射访问；`sun.security.x509` 也未向调用方导出。方法或声明类自身为 public，均不能替代模块访问要求。

[JDK 21 AccessibleObject 规范](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/reflect/AccessibleObject.html#setAccessible(boolean)) 明确区分已导出包中的公开成员访问与非公开成员的访问开放条件。

[JDK 21 Method.invoke 规范](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/reflect/Method.html#invoke(java.lang.Object,java.lang.Object...)) 规定，实例方法遵循动态方法查找。使用公开接口或父类的 `Method` 调用同一接收对象，仍会执行对象的覆盖实现。

## 修复

`ProxyUtils.findMethod` 的两个调用准备入口在原匹配器完成重载选择后，交由独立的 `ProxyMethodResolver` 处理公开实例方法：

1. 使用 `MethodHandles.publicLookup().unreflect(method)` 检查原声明。
2. 原声明不满足公开访问要求时，使用 Commons Lang `ClassUtils.hierarchy(..., Interfaces.INCLUDE)` 遍历声明类型、接口和父类。
3. 使用原方法的名称和精确形参类型调用 `Class.getMethod`，排除 static 候选，并检查候选的公开可访问性。
4. 找到可访问声明时，成员准备和实际调用共同使用该声明；否则保留原成员及原访问处理。

[JDK 21 publicLookup 规范](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/MethodHandles.html#publicLookup()) 只允许公开类中、包无条件导出的公开成员。[unreflect 规范](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/MethodHandles.Lookup.html#unreflect(java.lang.reflect.Method)) 规定，方法的访问抑制标志未设置时，立即执行访问检查。此处检查发生在 `setMember` 开放访问之前；查找失败仅淘汰候选，实际反射访问失败仍按原路径传播。

[Commons Lang 3.21.0 MethodUtils 源码](https://github.com/apache/commons-lang/blob/155db038d73fe0ea49612d42ae70f6e48ee3ebb9/src/main/java/org/apache/commons/lang3/reflect/MethodUtils.java#L108-L223) 对 public 声明类直接返回原方法；[ClassUtils.getPublicMethod 源码](https://github.com/apache/commons-lang/blob/155db038d73fe0ea49612d42ae70f6e48ee3ebb9/src/main/java/org/apache/commons/lang3/ClassUtils.java#L957-L998) 也仅检查 public 修饰符。二者均未提供上述模块导出检查，因此修复复用该库正式提供的类型层级遍历，由 JDK API 判定访问权限。

方法句柄仅用于访问检查；实际调用仍使用 `Method.invoke`，保留目标异常包装与 JNI pending exception 处理。所用 JDK API 自 Java 7 提供，生产源码继续使用 source/target 8。

此修复保留自有私有方法、构造器和字段的访问处理。静态成员继续使用原声明。`toReflectedMethod` 继续使用原匹配器，保留原始声明元数据。该处理选择无条件公开的调用入口，不扩展模糊重载匹配、模块开放权限或调用方专属访问能力。

## 验证

环境为 Linux amd64、OpenJDK `21.0.12.1+1-1-24.04.4-Ubuntu` 和 `11.0.32.1+1-post-1ubuntu1-24.04-Ubuntu`、仓库 Maven Wrapper `3.5.4`、Unicorn2 后端。

证书样本在扩展修复前的 JDK 21 代码上失败。修复后，上述命令及以下 JDK 11 对照命令均得到 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`：

```sh
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 \
PATH=/usr/lib/jvm/java-11-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniProxyReflectionAccessIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

同一测试复用每种架构的模拟器，覆盖 ARM32、ARM64 及直接参数、`VaList` 两种调用方式，并验证：

- 三个 JDK 样本的精确返回结果，包括 JNI 内存中的完整证书 DER。
- `ToReflectedMethod` 返回的完整 `Method` 与独立宿主 `Class.getMethod` 的结果相等。
- 私有构造器执行、私有字段读写及私有方法产生的宿主对象状态变化。
- 私有方法抛出的原始异常对象进入 JNI pending exception，并在清除后消失；抛出异常的调用返回值不参与断言。

[Class.getMethod 的继承查找规则](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Class.html#getMethod(java.lang.String,java.lang.Class...)) 允许原始方法由父类声明。此处 JDK 11 的 `keySet().toArray()` 声明于 `AbstractCollection`，JDK 21 的同名方法声明于 `HashMap.KeySet`。测试按宿主反射 API 确定原始声明。

既有目标异常传递测试使用以下独立命令执行：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniProxyExceptionIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试同样得到 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。

验证命令执行受影响模块及其依赖的编译与显式单测试，不执行本地仓库安装。调用项目的 Gradle 构建和完整 native 流程不属于该 JNI 验证范围。
