import SwiftUI
import Photos
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject var store: DiaryStore
    @EnvironmentObject var models: Models
    @EnvironmentObject var monitor: PhotoMonitor
    @State private var profile = false
    @State private var importing = false
    @State private var cleanup = false
    @State private var exported: URL?
    @AppStorage("remindersEnabled") private var reminders = false
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 15) {
                        Image(systemName: "leaf.circle.fill").font(.system(size: 45)).foregroundStyle(Palette.green)
                        VStack(alignment: .leading, spacing: 4) { Text("食迹 · iPhone").font(.title3.bold()); Text("记录每一餐，留在你身边").font(.caption).foregroundStyle(.secondary) }
                    }.padding(.vertical, 5)
                    Button("个人资料与能量参考") { profile = true }
                    if store.diary.profile.target > 0 { LabeledContent("当前能量参考", value: "\(Int(store.diary.profile.target)) kcal") }
                }
                Section {
                    Text("Qwen3.5 · 2B · 完全本地识别").font(.headline)
                    Text(models.progress).font(.subheadline).foregroundStyle(.secondary)
                    if models.busy { ProgressView(value: models.fraction); Button("取消下载") { models.cancel() } }
                    else {
                        if !models.ready { Button("通过 Wi-Fi 下载模型（约 1.95 GB）") { models.download() } }
                        Button("从“文件”导入两个 GGUF 模型") { importing = true }
                        Button("重新校验本地模型") { Task { await models.check() } }
                    }
                    Text("首次下载连接 Hugging Face；识别过程不上传照片，无需服务密钥。使用 llama.cpp 的 Metal 路径，不声称使用 Neural Engine。").font(.caption).foregroundStyle(.secondary)
                } header: { Text("离线模型") } footer: { Text("模型不随应用包内置。也可用 Finder 的文件共享，把 model.gguf 和 vision.gguf 放入食迹的 Models 文件夹，再点重新校验。") }
                Section {
                    Toggle("检测新照片", isOn: Binding(get: { store.diary.automatic }, set: { enabled in Task { await monitor.toggleAutomatic(enabled) } }))
                    LabeledContent("照片权限", value: permissionText)
                    Button("允许访问相册") { Task { await monitor.authorize() } }
                    if monitor.permission == .limited {
                        Button("管理已授权照片") {
                            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                               let controller = scene.windows.first(where: \.isKeyWindow)?.rootViewController {
                                PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: controller)
                            }
                        }
                    }
                    Button("检查最近 7 天未处理照片（每次最多 8 张）") {
                        Task { if monitor.permission == .notDetermined { await monitor.authorize() }; await monitor.scan(recent: true) }
                    }.disabled(!models.ready || monitor.running)
                    Text(monitor.status).font(.caption).foregroundStyle(.secondary)
                    Link("打开系统设置", destination: URL(string: UIApplication.openSettingsURLString)!)
                } header: { Text("照片发现") } footer: {
                    Text("iOS 不提供常驻相册监听服务。应用打开时补扫；后台扫描由系统择机安排，当前仅在充电时请求运行。强制退出、低电量、关闭后台刷新或仅授权部分照片都会限制发现。iCloud 中尚未下载的原图不会自动联网读取。截图会跳过，其他图片会先经过本地食物筛选，可能漏检；手动选照片可直接识别。")
                }
                Section {
                    Toggle("日记、周报与月报提醒", isOn: $reminders)
                        .onChange(of: reminders) { _, enabled in Task { await BackgroundWork.reminders(enabled) } }
                    Button("查看旧食物照片清理清单") { cleanup = true }
                } header: { Text("提醒与清理") } footer: {
                    Text("每天 20:00、周日和每月 1 日提醒回顾记录。清理只纳入超过 30 天、已确认、允许清理且未收藏的原图；最终删除需你和系统确认。相册的收藏会再检查。")
                }
                Section {
                    Button("导出饮食与体重记录 JSON") {
                        do { exported = try store.exportFile() } catch { store.message = error.localizedDescription }
                    }
                    if let exported { ShareLink("分享已导出的记录", item: exported) }
                    NavigationLink("数据与模型说明") { AboutView() }
                    Text("照片缩略图、饮食和个人资料保存在本机，并排除本应用的数据备份。卸载会移除应用内记录，请先导出。相册原图由系统相册管理。").font(.caption).foregroundStyle(.secondary)
                }
            }.navigationTitle("设置")
                .sheet(isPresented: $profile) { ProfileEditor(profile: store.diary.profile) }
                .sheet(isPresented: $cleanup) { CleanupView() }
                .fileImporter(isPresented: $importing, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in
                    switch result {
                    case .success(let urls): Task { await models.importFiles(urls) }
                    case .failure(let error): store.message = error.localizedDescription
                    }
                }
        }
    }
    private var permissionText: String {
        switch monitor.permission {
        case .authorized: return "完整相册"
        case .limited: return "仅已选照片"
        case .denied, .restricted: return "未获允许"
        default: return "尚未申请"
        }
    }
}

struct ProfileEditor: View {
    @EnvironmentObject var store: DiaryStore
    @Environment(\.dismiss) var dismiss
    @State var profile: Profile
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section("基础资料") {
                    LabeledContent("年龄（岁）") { TextField("年龄", value: $profile.age, format: .number).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 100) }
                    LabeledContent("身高（cm）") { TextField("身高", value: $profile.height, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100) }
                    LabeledContent("体重（kg）") { TextField("体重", value: $profile.weight, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100) }
                    Picker("公式中的生理性别", selection: $profile.male) { Text("男").tag(true); Text("女").tag(false) }
                    Picker("日常活动", selection: $profile.activity) { Text("以久坐为主").tag(1.2); Text("轻度活动").tag(1.375); Text("中等活动").tag(1.55) }
                    Toggle("孕哺期、疾病治疗或需个体营养方案", isOn: $profile.special)
                }
                Section {
                    LabeledContent("自定参考（kcal）") { TextField("0 表示自动", value: $profile.manualTarget, format: .number).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 100) }
                    Text("填 0 使用自动估算。").font(.caption).foregroundStyle(.secondary)
                    if profile.target > 0 { LabeledContent("能量参考", value: "\(Int(profile.target)) kcal / 日") }
                    Text("按 Mifflin–St Jeor 公式估算，再结合活动。BMI ≥24 时采用保守的 250 kcal 减量；低 BMI、未成年人或特殊情况不自动给减重额度。1400～4500 是当前产品输入范围，并非适用于所有人的生理阈值。目标不等于营养是否充足。").font(.caption).foregroundStyle(.secondary)
                } header: { Text("能量参考") }
                if let error { Text(error).foregroundStyle(.red) }
                Button("保存资料") {
                    guard profile.age > 0, profile.age < 120, (130...220).contains(profile.height), (35...250).contains(profile.weight),
                          profile.manualTarget == 0 || (1400...4500).contains(profile.manualTarget) else { error = "请检查年龄、身高、体重和自定参考值。"; return }
                    store.diary.profile = profile; store.invalidateCompleteness(); store.save(); dismiss()
                }
            }.navigationTitle("个人资料").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("关闭") { dismiss() } }
        }
    }
}

struct CleanupView: View {
    @EnvironmentObject var monitor: PhotoMonitor
    @EnvironmentObject var store: DiaryStore
    @Environment(\.dismiss) var dismiss
    @State private var assets: [PHAsset] = []
    @State private var selected: Set<String> = []
    @State private var confirm = false
    @State private var busy = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("饮食记录和应用内缩略图会保留。删除会影响系统相册；如启用 iCloud 照片，也会同步到其他设备。请先核对清单，系统还会再次确认。").font(.subheadline)
                    if assets.isEmpty { Text("暂无符合条件的旧照片。只处理已确认、允许清理、超过 30 天且未收藏的餐食照片。").foregroundStyle(.secondary) }
                }
                ForEach(assets, id: \.localIdentifier) { asset in
                    Button {
                        if selected.contains(asset.localIdentifier) { selected.remove(asset.localIdentifier) } else { selected.insert(asset.localIdentifier) }
                    } label: {
                        HStack {
                            AssetThumbnail(asset: asset)
                            VStack(alignment: .leading) {
                                Text(store.diary.meals.first { $0.assetID == asset.localIdentifier }?.title ?? "食物照片")
                                Text((asset.creationDate ?? Date()).formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(); Image(systemName: selected.contains(asset.localIdentifier) ? "checkmark.circle.fill" : "circle")
                        }
                    }
                }
                if !assets.isEmpty {
                    Button("全选清单") { selected = Set(assets.map(\.localIdentifier)) }
                    Button("清理所选 \(selected.count) 张照片", role: .destructive) { confirm = true }.disabled(selected.isEmpty || busy)
                }
            }.navigationTitle("核对照片清单").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("完成") { dismiss() } }
                .task { assets = monitor.cleanupCandidates() }
                .confirmationDialog("确定清理所选相册原图？iCloud 照片也会同步删除。", isPresented: $confirm, titleVisibility: .visible) {
                    Button("继续到系统确认", role: .destructive) {
                        busy = true
                        Task {
                            do { try await monitor.cleanup(assets.filter { selected.contains($0.localIdentifier) }); assets = monitor.cleanupCandidates(); selected = [] }
                            catch { store.message = "未完成清理：\(error.localizedDescription)" }
                            busy = false
                        }
                    }
                }
        }
    }
}

struct AssetThumbnail: View {
    let asset: PHAsset
    @State private var image: UIImage?
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { Image(systemName: "photo").frame(maxWidth: .infinity, maxHeight: .infinity).background(Palette.mist) }
        }.frame(width: 50, height: 50).clipShape(RoundedRectangle(cornerRadius: 10))
            .task {
                PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 100, height: 100), contentMode: .aspectFill, options: nil) { value, _ in
                    Task { @MainActor in image = value }
                }
            }
    }
}

struct AboutView: View {
    var body: some View {
        List {
            Section("识别与误差") {
                Text("Qwen3.5-2B Q4_K_M 与 F16 视觉投影，llama.cpp 原生库在设备运行。Metal 是 GPU 路径；没有接入 Apple Neural Engine。")
                Text("通用视觉模型会漏检、重复食材或错误估算份量，不能替代称重。所有识别结果先保存为草稿。能量与宏量营养来自 7,793 条 USDA 数据（113 条有常用中文与别名），未匹配食物不允许直接确认。")
                Text("仅拍一餐无法评估全天营养。估算建议需要完整资料、已确认餐食和近期的记全确认；该确认两小时后失效，修改记录也会失效。")
            }
            Section("来源与许可") {
                Link("Qwen3.5 模型 · Apache 2.0", destination: URL(string: "https://huggingface.co/Qwen/Qwen3.5-2B")!)
                Link("固定版本的量化权重", destination: URL(string: "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/tree/f6d5376be1edb4d416d56da11e5397a961aca8ae")!)
                Link("llama.cpp · MIT", destination: URL(string: "https://github.com/ggml-org/llama.cpp/tree/547208dca250ceb3e4c8615598d043f2b602a527")!)
                Link("USDA FoodData Central SR Legacy", destination: URL(string: "https://fdc.nal.usda.gov/download-datasets/")!)
                Text("食品数据为每 100 克可食部分近似值；烹调方法和食材差异会改变数值。许可文本随应用放在 Licenses 资源目录。")
            }
            Section("iPhone 后台限制") {
                Text("PhotoKit 变化回调只在进程有运行机会时收到。后台任务由 iOS 调度；这里的充电扫描与打开时补扫不能保证每次拍照后立即处理。相册有限权限仅覆盖你授权的照片。")
                Link("Apple 后台任务说明", destination: URL(string: "https://developer.apple.com/documentation/BackgroundTasks/choosing-background-strategies-for-your-app")!)
            }
            Text("食迹 iOS 0.1.1 · 无账号 · 无广告 · 无照片上传").font(.caption).foregroundStyle(.secondary)
        }.navigationTitle("关于食迹")
    }
}
