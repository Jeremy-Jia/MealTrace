import SwiftUI
import PhotosUI
import Photos

@main struct MealTraceApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @Environment(\.scenePhase) private var phase
    @StateObject private var store = DiaryStore.shared
    @StateObject private var monitor = PhotoMonitor.shared
    @StateObject private var models = Models.shared
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).environmentObject(monitor).environmentObject(models)
                .tint(Palette.green)
                .environment(\.locale, Locale(identifier: "zh_Hans_CN"))
                .onChange(of: models.ready) { _, ready in if ready { Task { await monitor.scan() } } }
                .task {
                    #if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("--mealtrace-verify") { await Verification.run() }
                    #endif
                }
                .onChange(of: phase) { _, value in
                    if value == .active {
                        monitor.permission = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                        Task { await monitor.scan() }
                        BackgroundWork.remindCleanupIfNeeded()
                    }
                    if value == .background { BackgroundWork.schedule() }
                }
                .alert("食迹", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) {
                    Button("知道了", role: .cancel) { store.message = nil }
                } message: { Text(store.message ?? "") }
        }
    }
}

enum Palette {
    static let paper = Color(red: 0.97, green: 0.97, blue: 0.94)
    static let green = Color(red: 0.17, green: 0.36, blue: 0.29)
    static let mist = Color(red: 0.89, green: 0.93, blue: 0.87)
    static let ink = Color.primary
}

struct RootView: View {
    var body: some View {
        TabView {
            TodayView().tabItem { Label("今天", systemImage: "sun.max") }
            MealsView().tabItem { Label("餐食", systemImage: "fork.knife") }
            TrendsView().tabItem { Label("趋势", systemImage: "chart.bar.xaxis") }
            SettingsView().tabItem { Label("设置", systemImage: "slider.horizontal.3") }
        }
    }
}

struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 14) { content }
            .padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .background(.background, in: RoundedRectangle(cornerRadius: 24))
    }
}

struct TodayView: View {
    @EnvironmentObject var store: DiaryStore
    @EnvironmentObject var monitor: PhotoMonitor
    @EnvironmentObject var models: Models
    @State private var selected: PhotosPickerItem?
    @State private var editor: Meal?
    private var day: String { Date().formatted(.dateTime.locale(Locale(identifier: "zh_Hans_CN")).month(.wide).day().weekday(.wide)) }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(day).font(.subheadline).foregroundStyle(.secondary)
                            Text("好好吃饭，慢慢记录").font(.title2.bold())
                        }
                        Spacer(); Image(systemName: "leaf.fill").font(.title).foregroundStyle(Palette.green)
                    }.padding(.top, 8)
                    energyCard
                    actions
                    Card {
                        Label(store.advice.0, systemImage: "sparkle").font(.headline)
                        Text(store.advice.1).font(.subheadline).foregroundStyle(.secondary).lineSpacing(4)
                        if !store.today.isEmpty {
                            Button(store.completeSoFar ? "截至现在已记全 ✓" : "截至现在已记全") { store.diary.lastComplete = Date(); store.save() }
                                .buttonStyle(.bordered).disabled(store.total.pending > 0 || store.total.unknown > 0)
                            if Calendar.current.component(.hour, from: Date()) >= 20 {
                                Button(store.completeDay ? "今天已记全 ✓" : "今天已经全部记完") { store.diary.completeDay = Date().dayKey; store.save() }
                                    .font(.subheadline).disabled(store.total.pending > 0 || store.total.unknown > 0)
                            }
                        }
                    }
                    HStack { Text("今日餐食").font(.title3.bold()); Spacer(); Text("\(store.today.count) 条").foregroundStyle(.secondary) }
                    if store.today.isEmpty {
                        Card { Label("第一餐，从一张照片开始", systemImage: "camera").font(.headline); Text("也可以手动补记。没有记录的时段不会当作零摄入。").font(.subheadline).foregroundStyle(.secondary) }
                    }
                    ForEach(store.today) { meal in Button { editor = meal } label: { MealRow(meal: meal) }.buttonStyle(.plain) }
                    Text("照片估算会漏食材、误判份量与用油。上下界为产品估算范围，并非经过校准的置信区间。").font(.caption).foregroundStyle(.secondary)
                }.padding(20)
            }
            .background(Palette.paper.opacity(0.7)).navigationTitle("食迹").navigationBarTitleDisplayMode(.inline)
            .sheet(item: $editor) { MealEditor(meal: $0) }
            .onChange(of: selected) { _, item in
                guard let item else { return }
                Task {
                    do {
                        if let data = try await item.loadTransferable(type: Data.self) {
                            let asset = item.itemIdentifier.flatMap { PHAsset.fetchAssets(withLocalIdentifiers: [$0], options: nil).firstObject }
                            await monitor.imported(data, date: asset?.creationDate ?? Date(), assetID: asset?.localIdentifier)
                        }
                    } catch { store.message = "照片导入失败：\(error.localizedDescription)" }
                    selected = nil
                }
            }
        }
    }
    private var energyCard: some View {
        Card {
            HStack { Text("今日已记录").font(.headline); Spacer(); Text(store.today.isEmpty ? "尚无记录" : (store.total.pending > 0 ? "含待确认估计" : "已确认")).font(.caption).foregroundStyle(.secondary) }
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(store.total.kcal, format: .number.precision(.fractionLength(0))).font(.system(size: 52, weight: .semibold, design: .rounded)).monospacedDigit()
                Text("kcal").foregroundStyle(.secondary)
                Spacer()
            }
            if store.diary.profile.target > 0 {
                ProgressView(value: min(1, store.total.kcal / store.diary.profile.target)).tint(Palette.green)
                Text("能量参考 \(Int(store.diary.profile.target)) kcal · 不是营养充足的判定").font(.caption).foregroundStyle(.secondary)
            } else { Text("设置个人资料后查看能量参考").font(.caption).foregroundStyle(.secondary) }
            HStack {
                nutrient("蛋白质", store.total.protein); Spacer(); nutrient("碳水", store.total.carbs); Spacer(); nutrient("脂肪", store.total.fat)
            }.padding(.top, 4)
            if store.total.pending > 0 || store.total.unknown > 0 { Text("\(store.total.pending) 条待确认 · \(store.total.unknown) 项未匹配食物").font(.caption).foregroundStyle(.orange) }
        }
    }
    private func nutrient(_ name: String, _ value: Double) -> some View {
        VStack(alignment: .leading, spacing: 5) { Text(name).font(.caption).foregroundStyle(.secondary); Text("\(Int(value)) g").font(.headline.monospacedDigit()) }
    }
    private var actions: some View {
        Card {
            HStack(spacing: 12) {
                PhotosPicker(selection: $selected, matching: .images, photoLibrary: .shared()) { Label("选照片", systemImage: "photo.badge.plus").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).disabled(monitor.running || !models.ready)
                Button { editor = Meal() } label: { Label("手动记", systemImage: "square.and.pencil").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
            }
            HStack(alignment: .top) {
                if monitor.running { ProgressView().controlSize(.small) }
                Text(models.ready ? monitor.status : models.progress).font(.caption).foregroundStyle(.secondary)
                if monitor.running { Spacer(); Button("停止") { monitor.cancel() }.font(.caption) }
            }
        }
    }
}

struct MealRow: View {
    @EnvironmentObject var store: DiaryStore
    var meal: Meal
    var body: some View {
        HStack(spacing: 14) {
            if let name = meal.imageName, let image = UIImage(contentsOfFile: store.imageDirectory.appendingPathComponent(name).path) {
                Image(uiImage: image).resizable().scaledToFill().frame(width: 60, height: 60).clipShape(RoundedRectangle(cornerRadius: 14))
            } else {
                Image(systemName: "fork.knife").font(.title2).frame(width: 60, height: 60).background(Palette.mist, in: RoundedRectangle(cornerRadius: 14))
            }
            VStack(alignment: .leading, spacing: 5) {
                Text(meal.title).font(.headline).lineLimit(1)
                Text("\(Meal.slots[meal.slot]) · \(meal.date.formatted(date: .omitted, time: .shortened)) · \(meal.confirmed ? "已确认" : "待确认")")
                    .font(.caption).foregroundStyle(meal.confirmed ? Color.secondary : .orange)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 3) {
                Text("\(Int(store.catalog.nutrients(meal).kcal))").font(.headline.monospacedDigit())
                Text("kcal").font(.caption).foregroundStyle(.secondary)
            }
        }.padding(15).background(.background, in: RoundedRectangle(cornerRadius: 20)).contentShape(Rectangle())
    }
}

struct MealsView: View {
    @EnvironmentObject var store: DiaryStore
    @State private var date = Date()
    @State private var editor: Meal?
    var body: some View {
        NavigationStack {
            List {
                DatePicker("记录日期", selection: $date, displayedComponents: .date)
                let meals = store.meals(on: date)
                if meals.isEmpty { Text("这一天没有记录，不代表没有摄入。").foregroundStyle(.secondary) }
                ForEach(meals) { meal in Button { editor = meal } label: { MealRow(meal: meal) }.buttonStyle(.plain).listRowInsets(EdgeInsets()) }
            }.navigationTitle("餐食记录")
                .toolbar { Button { editor = Meal(date: date) } label: { Image(systemName: "plus") }.accessibilityLabel("补记餐食") }
                .sheet(item: $editor) { MealEditor(meal: $0) }
        }
    }
}
