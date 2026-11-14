# ASV

ASV 是一款以手机为主的 Android 应用，用于查看 ARM64 ELF / `.so` 的汇编、平展列表、控制流图，以及只读的寄存器变化重放。重放使用默认执行环境（与间接分支求解同类的初始状态），没有手工修改寄存器的界面。

本仓库只包含 **应用工程**：Kotlin、资源、Gradle，以及 **预编译** 的 `libicode_jni.so`。这里 **没有** 分析引擎的源码、头文件、CMake 或静态库（`.a`）。

当前版本：**1.0.8-debug**（versionCode 9）。ABI 仅 `arm64-v8a`。

## 构建

需要：

- JDK 17
- Android SDK（compile/target 35，min 24）
- 仓库自带的官方 Gradle wrapper（Gradle 8.11.1）
- 首次构建需能访问 Google / Maven Central

**不需要** 引擎源码树，也不需要指向私有引擎库的环境变量，或用 CMake/NDK 编译本地引擎。

```bash
# 在不会提交的 local.properties 中设置：
#   sdk.dir=/path/to/Android/sdk

./gradlew :app:assembleDebug
```

调试包输出在 `app/build/outputs/apk/debug/app-debug.apk`。请勿把 APK 提交进 git。

预编译库已放在 `app/src/main/jniLibs/arm64-v8a/libicode_jni.so`（以及 `libc++_shared.so`）。Kotlin 侧通过 `System.loadLibrary("icode_jni")` 加载。

## 包含与不包含

- 本仓库包含：ASV 界面源码、测试、`app/src/main/assets/samples/` 下的内置自造 ARM64 样本，以及预编译 JNI 库。
- 不包含：引擎 C/C++ 源码、`jni_bridge` 实现源码、原 CMake、私有 remote、用户文档或发行 APK。

许可证与分发说明见 `NOTICE`。预编译引擎库 **并非** 由本仓库开源。该 so 已去掉 ELF 调试符号，但这 **不表示** 二进制不可逆向，其中仍可能残留第三方编译时的路径字符串。

## 仪器测试

测试可通过 `am instrument` 在设备上运行。部分测试会从设备公共 Download 目录复制自造的 `libconst_twelve.so`（`Download/ASV-test` 或 `Download/icode-viewer-test`）。测试不读取引擎源码。
