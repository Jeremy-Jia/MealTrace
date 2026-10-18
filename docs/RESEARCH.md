# 食迹：非 Root 自动饮食记录应用调研与实现决策

调研日期：2026-10-18。以 Android、小米、高通、模型作者、营养数据库和研究原文为依据。功能宣传不等于实测准确率；下文明确区分平台能力、实现选择与尚待验证的部分。

## 1. 结论与产品范围

非 Root 普通应用可以实现：监测授权范围内的新相机照片、本地筛选与视觉识别、份量修正、营养计算、日周月累计、桌面组件与通知。用户不需要购买云端服务、注册账号或提供 API 密钥。小米 15/18 的算力不是唯一瓶颈；照片缺失重量、用油与实际食用比例的信息，以及 Android 后台调度，才是产品可靠性的主要约束。

“是否还可以加餐”必须以个人参考目标、记录是否完整、实际摄入比例和后续正餐预留共同计算。单张午餐照片不能用于判断全天是否吃够，也不能证明营养素充足。

首版独立于 Pulse，名为「食迹 / MealTrace」。Android 11+、ARM64、非 Root；当前验证设备实际属性为 Xiaomi 18 Pro / SM8950 / Android 17 / 16GB 内存。不修改系统、不申请无障碍、不使用屏幕录制，不依赖 ADB 才能日常运行。

## 2. 安卓后台：可以恢复监测，不能承诺永不停止

采用两条互补路径：MediaStore 内容变化触发 JobScheduler；另一个持久化周期任务负责补扫。内容触发任务执行结束后重新注册，开机广播补回非持久的监听。Android 官方说明，内容触发与周期、持久化选项不能写在同一个任务中，因此本项目将它们分开。[JobInfo.Builder 文档](https://developer.android.com/reference/android/app/job/JobInfo.Builder)

周期任务按系统调度执行，可能受 Doze、配额、省电和电量影响；15 分钟不是最大处理延迟。应用前台使用 ContentObserver 提高响应速度；后台靠内容任务与补扫恢复。用户明确“强制停止”后，Android 会维持停止状态，直到用户重新与应用交互；不能用保活技巧绕过。[后台任务](https://developer.android.com/develop/background-work/background-tasks)、[Android 停止状态](https://developer.android.com/about/versions/15/behavior-changes-all)

小米公开说明，普通应用的后台自启动默认可能受限，需要用户自行允许。首版提供跳转和说明；菜单位置依 HyperOS 版本变化。不能在当前手机上验证一次就宣称所有小米固件都永久保活。[小米自启动管理说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624)

首版只扫描开启监测后新加入 `DCIM/Camera/` 的照片，不回溯整本历史相册。扫描使用 URI 去重与重叠查询；同一份压缩图片的哈希在 15 分钟内完全相同则跳过。不同角度、连拍、餐前餐后及多人聚餐仍需要用户合并判断，不能保证自动去重。首版提供“重复照片”“不是我的餐食”。大批图片按批次处理，避免无限占用后台。

## 3. 照片权限与删除

要自动发现未来新照片，需要完整照片访问。Android 14 的“仅选择部分照片”不会自动覆盖以后拍摄的所有新图；不授予完整权限时仍支持选择照片或系统分享。[部分照片访问](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)

普通应用清理系统相机创建的图片，应走 `MediaStore.createTrashRequest()`，经系统确认后移入回收站。Android 12 的 `MANAGE_MEDIA` 特殊访问可以免逐次确认，但仍需系统授权，具体后台请求流程与 OEM 支持不能只靠理论推断。[共享媒体访问与管理](https://developer.android.com/training/data-storage/shared/media)

**首版实际交付：每月自动整理并通知，用户批量确认后移入回收站；不宣称已经做到无交互定时删除。** 只处理超过 30 天、已确认、逐条允许清理且未收藏的原图。手动导入图片不自动追踪和清理原图。保留应用内压缩照片与营养记录。回收站未彻底清空时不承诺已释放空间；云相册删除同步需要按用户的相册设置验证。第二阶段才评估有媒体管理授权时的全自动模式。

若将来发布 Google Play，持续读取相册需通过照片权限用途审核；个人侧载与商店分发要求不同。不能因为当前是私人安装，就推断以后一定能通过商店审核。[Google Play 照片权限政策](https://support.google.com/googleplay/android-developer/answer/9888170)

## 4. 已有产品的做法与空缺

| 产品 | 官方可确认的能力 | 对本项目的启发 |
|---|---|---|
| MyFitnessPal Meal Scan | 扫描后建议食物，用户选择并调整份数；功能与平台、语言、订阅有关 | 保留快速纠错，不把视觉结果视作最终摄入 |
| Cronometer Photo Logging | 图片识别后匹配营养数据库，用户检查食材和份量，也提示隐藏油脂和调料 | 模型负责识别，营养计算与来源独立保存 |
| Cal AI | 官网介绍拍照、条码、食物搜索和目标追踪 | 有拍照入口不等于已证明后台相册自动监测或热量精度 |

来源：[MyFitnessPal 操作说明](https://support.myfitnesspal.com/hc/en-us/articles/360045761612-How-to-use-the-Meal-Scanner)、[Cronometer 图片记录](https://cronometer.com/blog/photo-logging/)、[Cal AI 官网](https://www.calai.app/)。以上是官方功能信息，不是独立横向准确率评测。本项目的差异点是照常使用系统相机、授权后自动发现、本地推理，以及显式处理漏记和照片清理。

## 5. 端侧模型、CPU/GPU/NPU

骁龙 8 Elite 与后续平台有 Hexagon NPU；NPU 存在不意味着任意 GGUF 模型会自动在 NPU 上运行。高通列出的路径包括 LiteRT 委托、ONNX 执行提供程序和 QNN/QAIRT。高效部署还涉及具体 SoC、量化、算子和上下文二进制匹配。[高通 8 Elite 公告](https://www.qualcomm.com/news/releases/2024/10/qualcomm-unveils-snapdragon-8-elite-with-the-world-s-fastest-mob)、[AI Hub FAQ](https://dev.aihub.qualcomm.com/docs/hub/faq.html)

| 路线 | 优点 | 局限与首版决定 |
|---|---|---|
| 通用轻量图片标签 | 小、快，可在本机初筛“可能是餐食” | 不推断重量和营养；作为候选筛选器使用 |
| 食物专用分类器 | 种类固定、推理便宜 | 混合菜与域外食物受限；Google AIY 食物模型还明确不适合预测配料和营养，未用来计算热量 |
| 小型视觉语言模型 | 可输出中文食材、解释画面 | 会幻觉，重量不能视为测量；只生成待确认草稿 |
| QNN/QAIRT NPU 路线 | 有望降低功耗和处理时间 | 需要单独完成芯片匹配、图像编码器与语言模型部署，首版未验证 |
| 远程视觉 API | 部署轻、模型能力选择多 | 需要联网、服务及费用，也涉及照片外发；用户不需要此方案，首版没有云端识别代码 |

轻量初筛使用随应用捆绑的 ML Kit Image Labeling，不需要 Google Play 首次下载识别模型。其通用标签适合筛选，不足以计算热量。[ML Kit Android 部署](https://developers.google.com/ml-kit/vision/image-labeling/android)、[Google AIY 模型卡](https://www.kaggle.com/models/google/aiy/tfLite/vision-classifier-food-v1/1)

视觉推理基于 Qwen3.5-2B（Apache 2.0）和 llama.cpp。模型采用固定修订的 Unsloth Q4_K_M 语言权重与 F16 视觉投影器，合计 **1,949,063,104 字节**。推理时只访问手机私有文件；无服务账号。llama.cpp 的 Android NDK 和多模态部署已有官方实现支持，但上游 CLI 仍为实验性代码，本应用因此加入超时、取消、结构校验和错误回退。[Qwen 模型卡](https://huggingface.co/Qwen/Qwen3.5-2B)、[量化发布](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF)、[llama.cpp Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)、[多模态说明](https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md)

试验中，0.8B 模型能快速生成简单食物回答，但在完整餐食结构化任务上出现重复、列举过多食材和输出截断，因此未选择为首版最终模型。**当前应用视觉主模型使用 ARM64 CPU，未声称使用 NPU。** NPU 是后续优化项，需要以真实后端日志、耗时与耗电实测证明，不能用 CPU 推理成功冒充。

## 6. 热量和份量的可信边界

照片不能提供可靠的三维尺度、隐藏油糖、烹调失水、食材遮挡和实际吃掉的比例。Nutrition5k 研究专门包含多视角、深度、重量和成分信息；研究数据上的结果不能直接移用为随机中餐照片的准确率。[Nutrition5k 原始研究](https://arxiv.org/abs/2103.03375)

首版流程：本地模型给食物名称和估计克数 → 精确名称/有限别名匹配本地食物表 → 程序以每 100g 数据计算 → 保留来源与范围 → 用户修正摄入比例。未匹配的食物标为需补记，不凭空生成营养值。通用“肉”“蔬菜”“沙拉”不强行映射到某种已知食物。

本地表从 USDA FoodData Central SR Legacy 官方 CSV 摘取，保留 FDC ID、英文原记录、生熟状态、每 100g 热量与宏量营养素。首版是有限的常见食材目录，并非完整中餐食物库。中餐配方、品牌、食材品种与生熟选择需要继续扩充和校对。数据库可离线使用，模型不生成或修改营养密度。[USDA 下载](https://fdc.nal.usda.gov/download-datasets/)、[数据字段](https://fdc.nal.usda.gov/portal-data/external/dataDictionary)

视觉热量上下界暂按估计值的 0.65～1.5 倍，用户手工指定重量按 0.9～1.1 倍表达基础数据差异。**这只是产品设计的估算范围，不是实测误差、保证准确率或统计置信区间。** 自动识别结果即使名称正确也必须保留“待确认”。每日累计相加这些范围，不假设独立随机误差，不因多天累计就声称误差自动抵消。

## 7. 减重与加餐建议规则

静息能量参考采用 Mifflin–St Jeor 公式，再乘自选活动系数。公式是群体预测，并不是实测代谢；活动系数和默认小幅缺口是产品的保守起点，不保证每个人都适用。[Mifflin 原研究](https://pubmed.ncbi.nlm.nih.gov/2305711/)

首版仅为资料完整的成年人生成自动目标；孕哺期、需个体指导的疾病/用药/饮食问题或偏低 BMI 等情况只提供记录。默认 BMI ≥24 时在维持估计上减 250 kcal；正常范围优先维持。1400 kcal 是本产品限制，并不是普遍安全下限，也不是 NICE 指南给出的统一目标。低能量饮食需要专业支持，不能为了更快减重不断压低推荐。[NICE 饮食管理](https://www.nice.org.uk/guidance/ng246/chapter/Physical-activity-and-diet)、[NIDDK 适用范围](https://www.niddk.nih.gov/health-information/weight-management/body-weight-planner)

确定性建议顺序：

1. 资料不足或特殊情况：不给自动加餐额度。
2. 有未知、未确认份量：先修正；不把草稿当成实际摄入。
3. 缺少完整度确认：只展示已记录量，不推断剩余额度。
4. “截至目前已记全”有效 2 小时；新记录/修改会失效。全天确认当天 20 点后开放。
5. 根据时间和餐次，为尚未记录的后续正餐保留一部分参考能量。
6. 用摄入范围上界保守计算加餐空间，展示不确定性。充分记录且全天能量明显偏低时提醒正常补充饮食。
7. 超过目标也不建议补偿性禁食；饥饿、活动、个人情况仍重要。

少量样本不能判断营养缺乏。首版不根据照片诊断疾病、承诺减重速度或推算药物/胰岛素剂量。后续体重变化需看连续数周的趋势，并结合记录完整度，不用一次称重自动削减食物。CDC 将饮食、活动、睡眠和压力共同列为体重管理因素。[CDC 体重管理](https://www.cdc.gov/healthy-weight-growth/losing-weight/)

## 8. 统计、通知和空间管理

每条记录保存日期、餐次、食物、来源、食用比例、确认状态和图片清理意愿。界面提供每日记录、7/30 天图表、体重日志与 JSON 导出；后台任务负责晚间日汇总、周日周报和月初上月汇总。通知随系统调度，未承诺在精确分钟发出。

日报明确写“已记录食物”，周月报显示有记录天数和全天确认天数；漏记日不补零，不用“已记录平均量”冒充全天真实平均摄入。记录被修改后重新累计。

首版仍需继续完善：更广的中文食物表；包装食品标签/条码；不同角度与餐前餐后合并；多时段提醒；可撤销的批量编辑；真正的相册媒体管理授权与自动清理；长期后台耗电和 NPU 优化。

## 9. 隐私与本地存储

Qwen 推理进程读取应用私有的压缩 JPEG 与模型文件，不含网络客户端。应用网络功能用于用户主动下载固定模型。模型包可通过 USB 或本地文件安装。照片解码重编码后不携带原始 EXIF。数据库、压缩照片与模型保存在应用私有目录，关闭自动云备份；系统卸载会删除这些本地数据。导出由用户选择位置，JSON 不包含原图、原图 URI 或模型。

ML Kit 官方说明图片输入与识别输出在本机处理，不发到 Google；其 SDK 可能发送 API 性能与使用指标并联系更新服务。因此“照片不上传”准确，“应用绝无任何联网/遥测”则不应声称。应用内隐私说明披露这一点。[ML Kit 条款与隐私](https://developers.google.com/ml-kit/terms)

## 10. 验收标准与后续调研

验收至少覆盖：普通应用 UID 内本地推理；模型哈希匹配；图片筛选；未匹配食物阻断强建议；份量修正和范围重算；完整度过期与失效；无记录不按零摄入；相册只扫描授权目录；进程退出后的任务；收藏和未确认图片不清理；JSON 不导出原图；小米省电设置与长时间锁屏后的补扫。

需积累真实、经用户同意的单盘/多菜/中餐/饮料/包装/非食物照片，配合称重和实际食用比例，分别评估漏检、误检、名称匹配、份量绝对误差和人工修正次数。公开样例只能证明链路运行，不能证明准确率。正式日用前，应至少进行数日平行人工记录，观察漏记、功耗和建议合理性。

本次实际通过与未验证的项目，统一记录在随安装包交付的 `VERIFICATION.md`，不将计划项目写为已完成。
