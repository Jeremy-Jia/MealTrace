# 食迹 · MealTrace

应用显示名称为 **食迹**，英文名称和项目名为 **MealTrace**。提供 Android 和原生 iPhone 版本，不需要 Root、云服务账号或 API 密钥；照片在设备本地识别，结果先保存为待确认草稿。

| 平台 | 当前版本 | 最低构建目标 | 本地推理 | 源码与构建说明 |
| --- | --- | --- | --- | --- |
| Android | 0.1.2 | Android 11 / API 30，ARM64 | llama.cpp CPU | [app/](app/)，下方构建步骤 |
| iPhone | 0.1.1 | iOS 17 | llama.cpp Metal GPU | [iOS/](iOS/README.md)，SwiftUI / Xcode 工程 |

两个平台共用 Qwen3.5-2B 离线模型、7,793 条 USDA 食物数据和常用中文别名，并支持分类搜索、自定义食物、饮食统计及体重记录。模型权重通过应用下载或用户导入，不随源码仓库分发。iPhone 需要使用自己的 Apple 开发账号签名安装，后台发现照片由 iOS 调度，打开应用时补扫；具体能力与限制见 [iPhone 说明](iOS/README.md)。

**当前均为试用版本。** Android 尚未启用 NPU，iPhone 未接入 Neural Engine。照片估计的份量必须核对，不能把它当作精确摄入测量或营养诊断。当前主要实测 Xiaomi 18 Pro / Android 17 与 iPhone 18 Pro / iOS 27.0.1，较低目标系统尚未完整实测。参见 [Android 实测](docs/ANDROID-0.1.2-VERIFICATION.md)和 [iPhone 实测](iOS/docs/VERIFICATION.md)。

以下使用和构建步骤主要适用于 Android；iPhone 的工程、安装、签名和后台限制请参阅 [iOS/README.md](iOS/README.md)。

## Android 已实现

- 本地库扩展到 7,793 条 USDA 数据，113 条整理了常见中文与别名；分类、别名/英文搜索，以及按包装或配方数据添加的个人自定义食物。自定义支持 kJ/kcal 换算并随记录导出，明确标注为用户填写。
- 本地食物候选筛选、Qwen3.5-2B 视觉草稿、离线营养表计算。
- 系统相机目录新照片监测、内容触发任务、15 分钟周期补扫和开机恢复注册。
- 照片导入与系统分享；餐食份量、实际食用比例、日期和餐次修正；重复与非本人餐食排除。
- 每日累计、7/30 天趋势、体重日志、日周月汇总通知、JSON 导出与桌面小部件。
- 成年人能量参考及有前提的加餐建议；未知食物、未确认份量、漏记与特殊情况不会获得确定加餐额度。
- 月度照片整理：超过 30 天、已确认并逐条允许清理的原图，批量经系统确认移入回收站；收藏保留。
- 本地模型下载、SHA256 校验及模型 ZIP 导入，照片不上传做识别。

## 使用

1. 安装 APK。首次从设置下载约 1.95GB 离线模型，或导入随交付提供的模型 ZIP；连接的测试手机已通过 USB 安装模型时无需重复下载。
2. 在设置填写个人资料。自动目标只是群体公式估计；特殊情况可继续记账而不使用自动减重建议。
3. 为自动记录授予完整照片访问与通知权限，开启新照片监测。在小米上检查后台自启动与应用省电设置。
4. 只监测开启后新加入 `DCIM/Camera/` 的图片；历史照片可以主动导入。未开启自动模式也可手动导入或补记。
5. 对识别草稿核对食材、用油和份量，再选实际吃掉的比例。模型可能漏掉食物，尤其是混合菜、多人餐桌与饮料。
6. 完整度核对与摄入记录分开：截至目前的核对有效 2 小时；当天 20 点后可确认全天。新增与修改记录会使核对失效。
7. 桌面长按添加食迹小部件；关闭应用主界面不影响系统调度，强制停止后需要重新打开。

后台任务会被系统延后，不能承诺拍完立即识别。0.1 不支持无需确认的定时删除；清理需要在月度通知后批量确认。回收站清空前不承诺空间已释放。

## 运行与构建

- Android 11 / API 30 以上，ARM64；当前主要实测 Xiaomi 18 Pro / Android 17。不能据此宣称已覆盖全部机型。
- Java 17、Android SDK 36、Build Tools 36.0.0，Gradle Wrapper 8.11.1、AGP 8.10.1。
- 主 APK 不包含约 2GB 的模型权重。模型清单、来源、大小和哈希位于 `app/src/main/assets/models.json`。
- Git 不跟踪模型权重、SDK 或 Gradle 缓存；源码包含营养数据摘取结果和可验证的 ARM64 推理程序。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
python3 tools/build.py
```

开发 APK 含仅用于本机测试的自检 instrumentation，可在已授权 ADB 的设备执行：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w dev.mealtrace.app/.Verification
adb exec-out run-as dev.mealtrace.app cat files/verification.json
```

自检只使用公开测试图片、应用自己创建的媒体项和隔离的历史测试行，不读取个人相册，不修改用户资料；测试结束清理这些媒体项。发布构建 `python3 tools/build.py --release` 不包含这个自检入口；本地发布包使用开发签名，仅供私人安装，公开分发前应配置自己的长期签名。

需要重建 native runner 时，使用 NDK r30 与 CMake：

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk-r30
python3 tools/build.py --native
```

上游固定于 `547208dca250ceb3e4c8615598d043f2b602a527`，仅加入适用于 Qwen 的关闭思考提示补丁。二进制以 `.so` 文件名放在已安装原生程序目录，本体是 ARM64 PIE 可执行文件。不会运行下载的可执行代码，模型是经哈希校验的数据文件。

## 文档与限制

- [完整调研与决策](docs/RESEARCH.md)：平台限制、竞品、模型、NPU、热量可信度、建议规则与隐私。
- [实测记录](docs/VERIFICATION.md)：通过项目与未验证项目。
- [数据与依赖说明](NOTICE)：模型、推理引擎、ML Kit、USDA 与测试图片来源。

“图片不上传识别”不等于“绝无任何网络活动”：用户可主动下载模型；ML Kit 可能发送性能与使用指标并联系更新服务，官方说明见 [ML Kit 隐私](https://developers.google.com/ml-kit/terms)。数据库与压缩照片在本地私有目录，不自动云备份。卸载会删除本地记录，请先导出。

日、周、月统计只累计记录到的食物，缺失日期不按零摄入处理。首版视觉只生成有限数量的主要食材草稿，混合菜仍可能漏识别；确认前请检查完整食材、用油和份量。能量达标不能证明营养充足；主模型不是经过本项目临床验证的食物称重模型。
