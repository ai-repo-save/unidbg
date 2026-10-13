# 系统属性 callback ABI

## 缺陷

`SystemProperties.__system_property_read_callback` 在 ARM32 和 ARM64 将 value 作为第二参数、name 作为第三参数传给 callback。官方参数顺序为 cookie、name、value、uint32 serial。

实现还固定读取 `prop_info + 4`。只读长属性的该位置是兼容错误文本，不是原始属性值。调用方因此读到错误文本，长度边界及直读与 callback 对照失真。

## 官方契约

依据 AOSP Bionic 提交 `06356e41c5ed7b12220b24c05ba4fdb873b126a7`：

- `prop_info` 固定头部为 96 字节，name 从偏移 96 开始。
- serial 的 `1 << 16` 标记只读长属性。
- 长属性兼容错误缓冲区为 56 字节，relative offset 位于 `prop_info + 60`。
- `long_value()` 返回 `prop_info + relative offset`。
- `ReadCallback` 在 `ro.` 只读分支选择 long_value；普通短属性选择 `prop_info + 4`。

来源：

- https://android.googlesource.com/platform/bionic/+/06356e41c5ed7b12220b24c05ba4fdb873b126a7/libc/system_properties/include/system_properties/prop_info.h
- https://android.googlesource.com/platform/bionic/+/06356e41c5ed7b12220b24c05ba4fdb873b126a7/libc/system_properties/system_properties.cpp

## 修复与验证

两种架构共享属性值指针选择，按官方顺序转交参数。长属性位移按 uint32 解释。该虚拟模块仍以稳定属性区为环境契约；修复不实现并发属性更新协议。

`SystemPropertiesCallbackTest` 从实际虚拟模块导出入口调用 ARM32/64 callback，核对四个参数及完整原始字节。覆盖空、1、91、92、510、511、512、513 字节，长属性的 inline 错误文本与实际值不同；值包含非 UTF-8 字节。

```fish
env JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./mvnw -o -pl unidbg-android -am test -Dmaven.test.skip=false -Dtest=SystemPropertiesCallbackTest -DfailIfNoTests=false
```
