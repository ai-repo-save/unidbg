# Proxy 宿主值公开 API 缺口

## 来源

调用方需要将 JNI 回调中的 `DvmObject<?>` 还原为宿主对象。`DvmObject.getValue()` 不是 proxy 参数解包操作：proxy 对象数组返回的是 DVM 元素数组，未携带宿主值的普通 `DvmClass` 返回 null。

fork `0.11.0` 的 `ProxyUtils`、`ProxyUtils.unpack`、`ProxyArrayObject` 和 `backingArray` 均为包内实现。既有转换能保留 proxy 数组的真实组件类型和宿主引用身份，但没有公开调用入口。消费者自行遍历 DVM 数组会复制数组、改变组件类型或丢失共享引用；反射访问包内实现不构成正式 API。

这是公开 API 缺口。用户决定是否向上游提交 issue；本次不提交 issue 或推送源码。

## 合同

fork `0.12.0` 公开：

```java
public static Object ProxyDvmObject.unpack(DvmObject<?> dvmObject);
```

方法只委托既有 `ProxyUtils.unpack`，不复制或重写转换实现。

- null 输入返回 null。
- proxy 对象数组返回原始宿主 backing array，保留数组类型、空元素和引用身份。
- proxy Class 返回原宿主 Class；`0.13.0` 完整覆盖 primitive、array 与普通 Class。VM 使用 `ProxyClassFactory` 创建并绑定真实宿主 Class 的 `ProxyDvmClass`。
- 缺少宿主值的 wrapper 按既有合同抛 `UnsupportedOperationException`，不猜测对应 Class 或修补元数据。
- 普通 DVM 数组沿用既有转换规则，不声明拥有 proxy backing array 的身份保证。
- 方法不新增或清理 VM 引用，不转移宿主对象或 VM 的所有权。取得的宿主引用遵循正常 Java 引用生命周期。

## 定向验证

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
./mvnw -B -pl unidbg-android -am \
  -Dmaven.test.skip=false \
  -Dtest=JniProxyUnpackIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

单个整体集成测试通过真实 ARM32、ARM64 Unicorn2 JNI 表调用，将 typed 数组、空 typed 数组、含共享引用和空元素的 Object[]、Class[]、Class、byte[] 和 null 送入 Java 方法并返回。测试通过公开 unpack 精确断言原宿主引用身份。JNI GetObjectArrayElement 验证共享元素的引用身份；SetObjectArrayElement 写入 Class 后，直接读取原宿主数组验证真实副作用，再检查 unpack 返回同一原数组。

该测试由发行脚本显式执行，不加入默认测试流程。
