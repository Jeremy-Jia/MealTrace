# 食迹 · MealTrace 安装包

| 平台 | 版本 | 文件 | 安装要求 |
| --- | --- | --- | --- |
| Android | 0.1.3 / versionCode 5 | [MealTrace-0.1.3-arm64.apk](android/MealTrace-0.1.3-arm64.apk) | Android 11 / API 30 以上、ARM64 |
| iPhone | 0.1.1 / build 2 | [MealTrace-0.1.1-unsigned.ipa](ios/MealTrace-0.1.1-unsigned.ipa) | iOS 17 以上；需要自行签名 |

应用名称为「食迹」。安装包不包含约 1.95GB 的离线模型，首次使用可在应用设置中下载或导入。具体功能、实测范围和限制见[项目说明](../README.md)。

## Android

下载 APK 后允许浏览器或文件管理器安装此来源的应用，即可安装。也可以通过已授权的 USB 调试执行：

```sh
adb install -r MealTrace-0.1.3-arm64.apk
```

这是使用开发签名的试用发布包，不包含开发版自检入口。0.1.3 启用兼容设备的 Hexagon NPU 加速与 CPU 自动回退，支持连续识别时短时复用模型。0.1.2 安装包保留供历史对照。自行构建或更换签名前，请保留自己的签名密钥并先导出饮食记录；签名不一致的安装包不能直接覆盖已有应用。

## iPhone

IPA 由已构建的 Release 应用移除个人开发签名及 `embedded.mobileprovision` 后打包，应用的可执行代码与资源保持不变。公开文件不含测试手机的设备标识或个人开发描述文件。

**此 IPA 未签名，不能直接安装到 iPhone。** 请使用自己的 Apple 开发证书与适用于目标设备的描述文件重新签名，或按 [iPhone 构建说明](../iOS/README.md)在 Xcode 中选择自己的开发团队，构建并安装到手机。签名有效期及设备范围由自己的 Apple 账号和描述文件决定。

## 校验下载

[SHA256SUMS.txt](SHA256SUMS.txt) 记录仓库内安装包的 SHA256。克隆仓库后可执行：

```sh
cd releases
shasum -a 256 -c SHA256SUMS.txt
```

校验结果应为所有列出的文件均 `OK`。这些包不包含照片、饮食记录、模型权重或签名私钥。
