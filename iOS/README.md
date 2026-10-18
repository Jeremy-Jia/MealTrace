# 食迹 iPhone 版

原生 SwiftUI 应用。与 Android 共用固定版本的 Qwen3.5-2B、7,793 条 USDA 食物数据与保守的营养建议规则。113 条已整理常用中文名称与别名，其他保留英文原名；可按分类、中文别名或英文搜索。iOS 静态链接 llama.cpp / mtmd，使用 Metal GPU 本地推理，不需要云端账号或服务密钥；没有 Neural Engine 加速声明。

## 使用

1. 设置个人资料，下载或导入两个离线模型文件。
2. 选照片识别，或手动添加食物与克数。照片只生成草稿，核对食材、用油、分餐和实际吃下的比例后确认；未匹配食材需要手动选择。
   找不到的品牌或家常菜可点“其他／自定义食物”，填写每100克的能量、蛋白质、碳水和脂肪，保存到个人食物表供以后复用。支持 kJ/kcal 换算；按100毫升标注时需先用实际密度换算。自定义项明确标记为用户填写，随 JSON 导出；未知名称可保留并手动选择近似营养参照。
3. 今天页显示已记录估计和待确认数；趋势页只累计已确认餐食，缺失日期不当作零摄入。可记录体重并导出 JSON。
4. 开启新照片检测后，应用打开时补扫；后台申请在充电时运行，时间由 iOS 决定。可手动检查最近七天，每次最多八张。
5. 本地通知提醒日记、周报和月报；超过 30 天、已确认、允许清理且未收藏的关联原图可列入清单，用户核对并通过系统确认后删除。删除会同步到 iCloud 照片，饮食记录与缩略图仍保留。

照片、饮食和资料不上传。记录与缩略图排除应用备份，卸载前请先导出。导出不包括原图、相册 ID 或扫描哈希。

## iOS 边界

iOS 没有供此类应用使用的常驻相册监听服务，不能承诺每次拍照立即唤醒、开机常驻或准点后台扫描。强制退出、低电量、关闭后台刷新与系统调度都会影响处理。有限相册权限只覆盖已授权图片；自动扫描不联网拉取尚未下载的 iCloud 原图，会跳过截图，Vision 食物筛选也可能漏检。手动选照片可直接识别。照片清理需要用户确认，不能静默每月删除。

首版未提供 WidgetKit 小部件、分享扩展或 Android/iOS 间自动同步。苹果官方说明：[后台策略](https://developer.apple.com/documentation/BackgroundTasks/choosing-background-strategies-for-your-app)、[PhotoKit 变化历史](https://developer.apple.com/videos/play/wwdc2022/10132/)。

## 构建

需要完整 Xcode 与 iOS SDK、CMake、XcodeGen；Ninja 可选。项目最低目标 iOS 17，当前实测设备 iOS 27.0.1，较低系统尚未实测。

```sh
cd iOS
xcodegen generate
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
xcodebuild -project MealTrace.xcodeproj -scheme MealTrace \
  -configuration Debug -destination 'generic/platform=iOS' \
  -derivedDataPath build/DerivedData DEVELOPMENT_TEAM=YOUR_TEAM \
  -allowProvisioningUpdates build
```

或在 Xcode 打开 `MealTrace.xcodeproj`，选择自己的团队和已配对、已开启开发者模式的 iPhone 后运行。签名私钥、账号与描述文件不提交到仓库。

`tools/build_native.py` 拉取固定 llama.cpp 提交 `547208dca250ceb3e4c8615598d043f2b602a527`，仅构建静态库，嵌入 Metal shader 源码。可用 `MEALTRACE_LLAMA_SOURCE` 指定同版本已有检出。免费 Personal Team 签名按实际描述文件到期；到期需连接 Mac 重建安装，不是 App Store 长期分发包。

苹果规定免费 Personal Team 描述文件在签发 7 天后失效。[官方说明](https://developer.apple.com/help/account/basics/about-your-developer-account)。希望约一年有效期，需要加入付费 Apple Developer Program，再以合适的新证书和开发或 Ad Hoc 描述文件重签，最终以实际到期日为准。会员费 99 美元/年，当地价格以注册页面为准：[加入会员](https://developer.apple.com/cn/programs/enroll/)、[描述文件有效期](https://developer.apple.com/documentation/technotes/tn3125-inside-code-signing-provisioning-profiles)。更新尽量保持同一应用标识并覆盖安装，先导出记录，避免卸载清除数据。

## 模型

与 Android 的模型相同，总计 1,949,063,104 字节。`Resources/models.json` 固定下载网址、文件大小和 SHA-256。应用包不内置权重。

- 应用设置经 Wi-Fi 下载公开权重，开始前至少留出 3 GB 空间；background URLSession 负责传输。
- 从“文件”分别导入 `model.gguf` 与 `vision.gguf`；现有 Android 离线模型 ZIP 解压后可复用，应用不直接导入 ZIP。
- 或通过 Finder 文件共享放进食迹 `Models` 文件夹，再点重新校验。

校验失败不会启用。识别过程不联网。通用视觉模型会漏食材、重复识别与误估克数和油；不能宣称热量精度。热量由食材数据库计算，估算范围并非经过校准的置信区间。只有午餐照片不能代表全天摄入或营养是否充足。

## 检查

`zsh tools/check.sh` 在 Mac 运行 37 项营养规则、份量边界、草稿解析、建议前提、别名、分类搜索、自定义与持久化检查，并检查 Info.plist。食物库由仓库根目录 `tools/build_food_catalog.py` 从固定 USDA 存档可重复生成，中文选择与别名在 `tools/food_labels.json` 维护。

Debug 构建以 `--mealtrace-verify` 启动，可用公开 Nutrition5k 样图运行真实模型、解析和临时文件往返，输出 `Documents/verification.json`；不读取私人照片、不修改个人资料或餐食。Release 不含样图或验证入口。

实测与限制见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。许可见 `Resources/NOTICE.txt` 与 `Resources/Licenses/`。
