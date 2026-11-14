# ASV 1.0.8

签名正式包（`versionName=1.0.8`，`versionCode=9`），不是把 debug 包改名。应用包名仍为 `com.icode.asmviewer`，仅 `arm64-v8a`。

## 构建与签名

在独立仓库中执行（未设置 `ICODE_*`）：

```bash
./gradlew :app:assembleRelease
```

- `apksigner verify` 通过（APK Signature Scheme v2 / v3）。
- 证书：`CN=ASV, O=ASV, OU=Release`，证书 SHA-256：`df670c4ce3e075aa81405ce9b2b58ad09456641a37c666866a24943c541f271f`。
- `versionName=1.0.8`，清单中无 `android:debuggable`（正式包不可调试）。
- APK SHA-256：`9b1f3343afe22921d1a4271efb38ba84b47df370823a0391846e19a4dcbff634`（51321255 字节）。
- 正式 APK 作为 Release 附件分发，不进入 git。

## 实际验证

本次报告采用 Pixel 6 结果；曾发生误向 moto 发起测试，已中止。未在 API 24 真机上验证，不能声称 API 24 真机通过。

- 1.0.8 界面在 Pixel 6 已安装的 debug 包上完成仪器测试（平展密度、重放主结果、全屏详情，中英文）。正式包与 debug 包签名不同，没有卸载现有 debug 版，也没有改包名来假装覆盖安装通过。
- 独立工程可在不使用引擎源码 / `ICODE_*` 的情况下构建。

## 未覆盖

- 大型控制流图压力测试、取消重放：**无法覆盖**（分析很快完成，未见取消按钮）。不要把这两项写成已通过，也不要编造耗时。

## 使用要点

- 重放只读，默认执行环境，无寄存器编辑。
- 主结果只显示实际变化的寄存器；右侧「详情」进入全屏页，按执行顺序查看每条指令的值变化。
