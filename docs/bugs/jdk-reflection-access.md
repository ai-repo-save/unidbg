# JDK 非公开实现类的 JNI 反射访问失败

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

## 复现

在修复前的生产代码上加入 `JniProxyReflectionAccessIntegrationTest`，使用 JDK 21 执行单项 JNI 集成测试：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniProxyReflectionAccessIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试通过 JNI 方法表获取具体实现类的方法 ID，并调用以下宿主对象：

| 对象 | 方法 | 公开调用入口 | 精确预期 |
| --- | --- | --- | --- |
| `Collections.enumeration(Collections.singleton("entry"))` | `hasMoreElements()`、`nextElement()` | `Enumeration` | `true`、`"entry"`、`false` |
| 含单项 `"key" → "value"` 的 `HashMap` 的 `keySet()` | `toArray()` | `Set` | 仅包含 `"key"` 的数组 |

修复前，首个 `hasMoreElements()` 调用触发上述访问异常，JNI 模拟调用返回 `-1`，预期 `JNI_TRUE` 的断言失败。

## 原因

`ProxyDvmObject` 保留宿主对象的具体实现类型。`ProxyUtils.matchMethodTypes` 对实例方法优先检查该类型的 `getDeclaredMethods()`，随后查找父类。它选出的 public 方法可能由非公开 JDK 实现类声明。

`DvmMethod.setMember` 无条件调用 `setAccessible(true)`。`java.base` 导出 `java.util`，但没有向调用方开放该包的深反射访问。方法自身为 public 不能替代声明类的访问要求。

[JDK 21 AccessibleObject 规范](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/reflect/AccessibleObject.html#setAccessible(boolean)) 明确区分已导出包中的公开成员访问与非公开成员的访问开放条件。

[JDK 21 Method.invoke 规范](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/reflect/Method.html#invoke(java.lang.Object,java.lang.Object...)) 规定，实例方法遵循动态方法查找。使用公开接口或父类的 `Method` 调用同一接收对象，仍会执行对象的覆盖实现。

## 修复

`unidbg-android` 显式依赖 Apache Commons Lang 3.21.0。在 `ProxyUtils.findMethod` 的两个调用准备入口中，使用公开 API `MethodUtils.getAccessibleMethod(Method)` 处理已选中的非 static 方法：

1. 原匹配器完成重载选择。
2. Commons Lang 使用该方法的名称和精确形参类型查找公开声明。
3. 有公开声明时，成员准备和实际调用共同使用该声明。
4. API 返回 `null` 时，继续使用原成员及原有反射访问处理。

[Commons Lang 3.21.0 源码](https://github.com/apache/commons-lang/blob/155db038d73fe0ea49612d42ae70f6e48ee3ebb9/src/main/java/org/apache/commons/lang3/reflect/MethodUtils.java#L108-L223) 给出了该 API 的契约与接口、父类查找实现。私有方法返回 `null`；接口候选排除 static 和 private 方法。

此修复保留自有私有方法、构造器和字段的访问处理。静态成员继续使用原声明。`toReflectedMethod` 继续使用原匹配器，保留原始声明元数据。访问异常继续向原边界传播。

`MethodUtils` 会跳过非公开中间接口的祖先链，也不负责完整的 JPMS 导出判断。本次处理上述有证据的实例调用，不扩展重载解析器或模块访问权限。

## 验证

环境为 Linux amd64、OpenJDK `21.0.12.1+1-1-24.04.4-Ubuntu`、仓库 Maven Wrapper `3.5.4`、Unicorn2 后端。

上述单项测试在修复前失败；修复后结果为 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。同一测试复用每种架构的模拟器，覆盖 ARM32、ARM64 及直接参数、`VaList` 两种调用方式，并验证：

- 两个 JDK 样本的精确返回值。
- `ToReflectedMethod` 返回具体实现类的原始声明。
- 私有构造器执行、私有字段读写及私有方法产生的宿主对象状态变化。
- 私有方法抛出的原始异常对象进入 JNI pending exception，并在清除后消失。

既有目标异常传递测试也通过以下独立命令执行：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniProxyExceptionIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果为 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，覆盖 ARM32 和 ARM64 的既有实例、静态方法及构造器目标异常传递。

验证命令执行受影响模块及其依赖的编译与显式单测试，不执行本地仓库安装。调用项目的 Gradle 构建不属于该 JNI 验证范围。
