# ASV

ASV is a phone-first Android app for viewing ARM64 ELF / `.so` assembly, a
linear listing, a control-flow graph, and a read-only replay of changed
registers. Replay uses a default execution environment (the same kind of
seeding used for indirect-branch resolution). There is no register-edit UI.

This repository is the **application** project: Kotlin, resources, Gradle, and
a **prebuilt** `libicode_jni.so`. It does **not** contain the analysis engine
source, headers, CMake, or static libraries (`.a`).

Version: **1.0.8-debug** (versionCode 9). ABI: `arm64-v8a` only.

## Build

Requirements:

- JDK 17
- Android SDK (compile/target SDK 35, min SDK 24)
- Official Gradle wrapper in this repo (Gradle 8.11.1)
- Network access for Google / Maven Central dependencies on first build

You do **not** need the engine source tree, private engine-library environment variables, or CMake/NDK compilation of native code.

```bash
# In a local.properties file (not committed), set:
#   sdk.dir=/path/to/Android/sdk

./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Do not commit APKs.

The native library is already under
`app/src/main/jniLibs/arm64-v8a/libicode_jni.so` (plus `libc++_shared.so`).
The Kotlin side loads it with `System.loadLibrary("icode_jni")`.

## What is in the APK, and what is not

- Shipped here: ASV UI source, unit/instrumentation tests, a small built-in
  self-made ARM64 sample under `app/src/main/assets/samples/`, and the
  prebuilt JNI library.
- Not shipped: engine C/C++ sources, `jni_bridge` implementation sources,
  original CMake, private remotes, user documents, or release APKs.

See `NOTICE` for license and distribution notes. The prebuilt engine library
is **not** open-sourced by this repository. The binary was stripped of ELF
debug symbols; that does **not** mean it cannot be reverse engineered, and
some third-party path strings may remain inside it.

## Instrumentation tests

Tests run on a device or emulator with `am instrument` (this project is not
set up to require a specific host machine). Some tests copy a self-made
`libconst_twelve.so` from public Download storage on the test device
(`Download/ASV-test` or `Download/icode-viewer-test`). They do not read
engine source.
