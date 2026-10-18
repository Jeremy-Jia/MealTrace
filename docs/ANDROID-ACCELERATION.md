# Android 本地推理加速

0.1.3 保留通用 ARM64 CPU 程序，增加独立的 Hexagon NPU worker。无需 Root，也不下载或替换手机驱动；APK 只声明可选的 FastRPC 系统原生库，不支持这些库的设备仍可安装。

## 自动选择与回退

1. 一个按 ARMv8-A 编译的小程序读取 `getauxval`，确认 CPU 同时支持 FP16 标量/向量、dotprod、i8mm，再查询系统 FastRPC 的 unsigned PD 和 DSP 架构。缺少任何条件、查询失败或查询超过 3 秒，均选择通用 CPU 程序。
2. 当前仅随包提供 V81 DSP skeleton，架构低于 V81 直接选择 CPU。较新的架构由固定版本上游的 V81 兼容路径尝试执行，不能据此保证所有新芯片均可用。只在已连接的 M610BB / SM8950 / V85 上完成实际 NPU 验证，其他设备需要各自实测。
3. NPU 初始化还必须实际出现 HTP0 会话、25/25 语言层卸载和 HTP0 视觉后端，才接收 NPU 识别结果。少量不支持的算子可由后端分配至 CPU。
4. NPU 进程失败、45 秒超时、协议/JSON/份量校验失败，会关闭该进程，并对当前照片重试通用 CPU（CPU 上限 150 秒）。24 小时内跳过失败的 NPU 路径，避免每张重复失败；应用版本或系统构建变化后重新探测。设置中关闭再开启“优先使用 NPU 加速”也会清除失败冷却。取消请求不会触发 CPU 重试。
5. CPU 程序不包含 Hexagon/OpenCL 后端，也不要求 FP16/dotprod/i8mm，避免不兼容 CPU 在执行加速引擎之前遇到非法指令。

## 连续识别

NPU worker 通过应用私有 stdin/stdout 管道接收图片路径和提示词，没有网络监听端口。首次加载模型，后续连续照片复用权重；每张开始前清空 KV/循环状态、采样器及 JSON grammar、聊天历史和上张图像批次。不复用上一张的识别答案。会话内还缓存已解析的营养表和名称索引，自定义食物改变后重新加载，释放会话时一并丢弃。

空闲 60 秒后终止 worker，Android 发出内存压力或界面隐藏通知时也释放空闲会话。活动识别期间不会仅因界面隐藏而中断。进程被系统回收后，下次重新加载，不能保证每次都是缓存耗时；识别时间和耗电也不能互相替代。

设置会显示上次使用 NPU、CPU 或因 NPU 失败回退 CPU。开关只控制离线引擎选择，不改变后台调度或照片权限。食物草稿仍需核对。

## 重建

普通 APK 构建直接校验并打包仓库已有的二进制，不需要安装 Hexagon SDK。通用 CPU 来源见 `native-build.json`；加速引擎、DSP skeleton、能力探测程序和 worker 源码哈希见 `acceleration-build.json`。

源代码固定在 llama.cpp `547208dca250ceb3e4c8615598d043f2b602a527`。补丁仅包含原有的 Qwen 关闭思考提示和 `tools/native/apply_worker.py` / `worker.inc`。加速引擎使用静态 C++ 运行时、`armv8.2-a+fp16+dotprod+i8mm`、Android API 30、Hexagon ON、OpenCL/Vulkan OFF。DSP 使用 SDK 6.6.0.0 / tools 19.0.07，V81 Release / O2 + LTO。

[上游官方构建说明](https://github.com/ggml-org/llama.cpp/blob/547208dca250ceb3e4c8615598d043f2b602a527/docs/backend/snapdragon/README.md)所用的公开工具链镜像为：

```
ghcr.io/snapdragon-toolchain/arm64-android@sha256:91714433626f0d94a926538a1e46ec43756c5b8e3262b91b95df1e812940aed1
```

在配置好 Linux x86_64 SDK/NDK 的环境中运行（Apple Silicon 可使用相应容器模拟环境）：

```sh
python3 tools/prepare_acceleration.py \
  --ndk /path/to/android-ndk \
  --sdk /opt/hexagon/6.6.0.0
python3 tools/build.py --release
```

脚本在专有的 `.native/` 构建缓存中拉取固定源码、应用补丁并构建 Android worker 和 `htp-v81`。不将 SDK、构建缓存、模型权重或手机的 vendor 驱动加入仓库。若由其他受控环境完成交叉编译，可在本机用 `--runner PATH --skeleton PATH --source PINNED_PATCHED_SOURCE --ndk PATH` 导入；脚本检查固定版本和 worker 源码一致性，并重新生成文件哈希与能力探测程序。

## 验证正式 APK

`src/androidTest` 是单独、同签名的测试 APK，目标为已安装的非 debuggable Release。公开图片只放进测试 APK；正式 APK 不包含测试 instrumentation、图片或测试入口。测试不读取私人照片，不写餐食记录，使用独立的加速和自定义食物偏好文件，并在结束时清理。验证时短暂停止并恢复既有扫描任务；只允许后台运行状态 `last_scan` / `scan_status` 更新，个人设置和自定义食物必须保持不变。

```sh
sh gradlew :app:assembleRelease :app:assembleReleaseAndroidTest
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -t -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w -e expected npu \
  dev.mealtrace.app.test/dev.mealtrace.app.AccelerationVerification
adb uninstall dev.mealtrace.app.test
```

非 NPU 设备可传 `-e expected cpu`。模拟“不支持 NPU”的回退检查使用测试专属的探测缓存；JVM 测试另外验证进程异常只重试一次 CPU、取消不回退、CPU 错误向调用者传递及架构/指令集门槛。实测范围、耗时与限制见 [0.1.3 验证记录](ANDROID-0.1.3-VERIFICATION.md)。
