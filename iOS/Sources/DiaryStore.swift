import SwiftUI
import UniformTypeIdentifiers

@MainActor final class DiaryStore: ObservableObject {
    static let shared = DiaryStore()
    @Published var diary = Diary()
    @Published var message: String?
    var catalog: FoodCatalog
    let directory: URL
    private var writingAllowed = true
    var file: URL { directory.appendingPathComponent("diary.json") }
    var imageDirectory: URL { directory.appendingPathComponent("Images", isDirectory: true) }

    init(directory customDirectory: URL? = nil) {
        directory = customDirectory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MealTrace", isDirectory: true)
        do {
            catalog = try FoodCatalog(data: Data(contentsOf: Bundle.main.url(forResource: "foods", withExtension: "json")!),
                                      aliases: Data(contentsOf: Bundle.main.url(forResource: "aliases", withExtension: "json")!))
        } catch { fatalError("Bundled food catalog is invalid: \(error)") }
        do {
            try FileManager.default.createDirectory(at: imageDirectory, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            var excluded = directory
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try excluded.setResourceValues(values)
            if FileManager.default.fileExists(atPath: file.path) {
                let loaded = try JSONDecoder().decode(Diary.self, from: Data(contentsOf: file))
                guard loaded.version == 1 else { throw MealError.invalid("不支持的记录格式版本") }
                var loadedCatalog = catalog
                for food in loaded.customFoods ?? [] { try loadedCatalog.addCustom(food, restoring: true) }
                for meal in loaded.meals { try loadedCatalog.validate(meal) }
                catalog = loadedCatalog; diary = loaded
            }
        } catch {
            let originalError = error.localizedDescription
            if FileManager.default.fileExists(atPath: file.path) {
                let backup = directory.appendingPathComponent("diary-recovery-\(UUID().uuidString).json")
                do { try FileManager.default.copyItem(at: file, to: backup) }
                catch { writingAllowed = false }
            }
            message = writingAllowed ? "无法读取记录，已保留恢复副本：\(originalError)" : "无法读取或备份记录，已暂停写入以保留原文件：\(originalError)"
        }
    }
    private func write(_ next: Diary) throws {
        guard writingAllowed else { throw MealError.invalid("记录文件需要恢复，当前已暂停写入。") }
        try JSONEncoder().encode(next).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func save() {
        do { try write(diary) } catch { message = "记录保存失败：\(error.localizedDescription)" }
    }
    func update(_ meal: Meal) throws {
        try catalog.validate(meal)
        var next = diary
        if let i = next.meals.firstIndex(where: { $0.id == meal.id }) { next.meals[i] = meal }
        else { next.meals.append(meal) }
        next.lastComplete = nil; next.completeDay = nil
        try write(next); diary = next
    }
    func addCustomFood(_ food: Food) throws {
        var nextCatalog = catalog; try nextCatalog.addCustom(food)
        var next = diary
        if next.customFoods == nil { next.customFoods = [] }
        next.customFoods!.append(food)
        try write(next); catalog = nextCatalog; diary = next
    }
    func remove(_ meal: Meal) throws {
        var next = diary
        next.meals.removeAll { $0.id == meal.id }
        next.lastComplete = nil; next.completeDay = nil
        try write(next); diary = next
        if let name = meal.imageName { try? FileManager.default.removeItem(at: imageDirectory.appendingPathComponent(name)) }
    }
    func invalidateCompleteness() { diary.lastComplete = nil; diary.completeDay = nil }
    func meals(on date: Date) -> [Meal] { diary.meals.filter { $0.date.dayKey == date.dayKey }.sorted { $0.date < $1.date } }
    var today: [Meal] { meals(on: Date()) }
    var total: Nutrients { catalog.total(today) }
    var completeSoFar: Bool { diary.lastComplete.map { Date().timeIntervalSince($0) >= 0 && Date().timeIntervalSince($0) < 7200 && $0.dayKey == Date().dayKey } ?? false }
    var completeDay: Bool { diary.completeDay == Date().dayKey && Calendar.current.component(.hour, from: Date()) >= 20 }
    var advice: (String, String) { NutritionAdvice.text(profile: diary.profile, total: total, complete: completeSoFar, fullDay: completeDay, hour: Calendar.current.component(.hour, from: Date())) }
    func exportFile() throws -> URL {
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("食迹记录-\(Date().dayKey).json")
        // Photo-library IDs and hashes are unnecessary for an exported nutrition diary.
        var copy = diary; copy.seenAssets = []; copy.seenHashes = []
        for i in copy.meals.indices { copy.meals[i].assetID = nil; copy.meals[i].imageName = nil }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(copy).write(to: target, options: [.atomic, .completeFileProtection])
        return target
    }
}
