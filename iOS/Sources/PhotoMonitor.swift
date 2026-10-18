import Photos
import UIKit
import Vision
import CryptoKit
import Combine

@MainActor final class PhotoMonitor: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    static let shared = PhotoMonitor()
    @Published var running = false
    @Published var status = "导入照片，或开启新照片检测"
    @Published var permission = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    private var stop = false
    private let store = DiaryStore.shared
    private let models = Models.shared
    override init() { super.init(); PHPhotoLibrary.shared().register(self) }
    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor in
            self.permission = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            if self.store.diary.automatic { await self.scan() }
        }
    }
    func authorize() async {
        permission = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        if permission == .denied || permission == .restricted { status = "可在系统设置开放相册权限，或用“选照片”手动导入。" }
    }
    func toggleAutomatic(_ enabled: Bool) async {
        if enabled && permission != .authorized && permission != .limited { await authorize() }
        guard !enabled || permission == .authorized || permission == .limited else { return }
        store.diary.automatic = enabled
        if enabled { store.diary.monitoringSince = Date(); status = "已开启；后台时机由 iOS 决定，打开应用会补扫新照片。" }
        store.save(); BackgroundWork.schedule()
    }
    func cancel() { stop = true; mealvision_cancel(); status = "正在停止本地识别…" }
    static func normalized(_ image: UIImage, maxEdge: CGFloat = 640) -> Data? {
        let scale = min(1, maxEdge / max(image.size.width, image.size.height))
        let size = CGSize(width: max(1, image.size.width * scale), height: max(1, image.size.height * scale))
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }.jpegData(compressionQuality: 0.88)
    }
    private func data(for asset: PHAsset) async -> Data? {
        await withCheckedContinuation { continuation in
            let options = PHImageRequestOptions(); options.deliveryMode = .highQualityFormat
            options.resizeMode = .exact; options.isNetworkAccessAllowed = false
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 640, height: 640), contentMode: .aspectFit, options: options) { image, info in
                if (info?[PHImageResultIsDegradedKey] as? Bool) == true { return }
                continuation.resume(returning: image.flatMap { Self.normalized($0) })
            }
        }
    }
    nonisolated private static func candidate(_ data: Data) -> Bool {
        let request = VNClassifyImageRequest()
        do {
            try VNImageRequestHandler(data: data).perform([request])
            let words = ["food", "meal", "dish", "cuisine", "fruit", "vegetable", "bread", "rice", "salad", "meat", "dessert", "drink", "coffee", "pizza", "egg", "soup", "sandwich", "noodle", "cake", "plate", "bowl"]
            return (request.results ?? []).contains { result in result.confidence >= 0.12 && words.contains { result.identifier.lowercased().contains($0) } }
        } catch { return false }
    }
    func scan(recent: Bool = false, limit: Int = 8) async {
        guard !running, models.ready, recent || store.diary.automatic else { return }
        permission = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard permission == .authorized || permission == .limited else { return }
        running = true; stop = false; defer { running = false }
        let options = PHFetchOptions()
        let since = recent ? Calendar.current.date(byAdding: .day, value: -7, to: Date())! : store.diary.monitoringSince
        options.predicate = NSPredicate(format: "mediaType == %d AND creationDate >= %@", PHAssetMediaType.image.rawValue, since as NSDate)
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
        let fetched = PHAsset.fetchAssets(with: options)
        var candidates: [PHAsset] = []
        fetched.enumerateObjects { asset, _, shouldStop in
            if !self.store.diary.seenAssets.contains(asset.localIdentifier), !asset.mediaSubtypes.contains(.photoScreenshot) { candidates.append(asset) }
            if candidates.count >= limit { shouldStop.pointee = true }
        }
        var drafts = 0
        for asset in candidates {
            if stop || Task.isCancelled { break }
            status = "检查新照片…"
            guard let data = await data(for: asset) else { continue }
            let isFood = await Task.detached(priority: .utility) { Self.candidate(data) }.value
            if !isFood { store.diary.seenAssets.insert(asset.localIdentifier); store.save(); continue }
            do {
                if try await recognize(data, date: asset.creationDate ?? Date(), assetID: asset.localIdentifier) { drafts += 1 }
            } catch { status = error.localizedDescription; return }
        }
        if !stop { status = candidates.isEmpty ? "没有未检查的新照片" : "检查完成，新增 \(drafts) 条待确认餐食。食物筛选可能漏检，可手动选照片补记。" }
        BackgroundWork.remindCleanupIfNeeded()
    }
    func imported(_ data: Data, date: Date = Date(), assetID: String? = nil) async {
        guard !running else { return }
        guard models.ready else { status = "请先在设置中下载或导入本地模型。"; return }
        guard data.count <= 100_000_000, let image = UIImage(data: data), let normalized = Self.normalized(image) else { status = "无法读取照片"; return }
        running = true; stop = false; defer { running = false }
        do {
            let found = try await recognize(normalized, date: date, assetID: assetID)
            status = found ? "已加入餐食草稿，请核对食物、用油和实际吃下的份量。" : "未发现食物，或这张照片已经记录。可手动补记。"
        } catch { status = error.localizedDescription }
    }
    private func recognize(_ data: Data, date: Date, assetID: String?) async throws -> Bool {
        var background = UIBackgroundTaskIdentifier.invalid
        if UIApplication.shared.applicationState == .active {
            background = UIApplication.shared.beginBackgroundTask(withName: "完成当前餐食识别") { mealvision_cancel() }
        }
        defer { if background != .invalid { UIApplication.shared.endBackgroundTask(background) } }
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        if store.diary.seenHashes.contains(hash) {
            if let assetID { store.diary.seenAssets.insert(assetID); store.save() }
            return false
        }
        let input = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        try data.write(to: input, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        defer { try? FileManager.default.removeItem(at: input) }
        status = "本机识别中，通常需等待一段时间…"
        let model = models.path("model.gguf").path, vision = models.path("vision.gguf").path
        let output = try await Task.detached(priority: .userInitiated) {
            var error: UnsafeMutablePointer<CChar>?
            let result = mealvision_recognize(model, vision, input.path, 1, &error)
            defer { mealvision_free(error); mealvision_free(result) }
            guard let result else { throw MealError.invalid(error.map { String(cString: $0) } ?? "本地模型未完成识别") }
            return String(cString: result)
        }.value
        guard !stop, !Task.isCancelled else { throw CancellationError() }
        if var meal = try store.catalog.parse(output, date: date, assetID: assetID) {
            let name = meal.id.uuidString + ".jpg"
            try data.write(to: store.imageDirectory.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            meal.imageName = name
            try store.update(meal)
            store.diary.seenHashes.insert(hash)
            if let assetID { store.diary.seenAssets.insert(assetID) }
            store.save(); return true
        }
        store.diary.seenHashes.insert(hash)
        if let assetID { store.diary.seenAssets.insert(assetID) }
        store.save(); return false
    }
    func cleanupCandidates() -> [PHAsset] {
        guard permission == .authorized || permission == .limited else { return [] }
        let cutoff = Date().addingTimeInterval(-30 * 86400)
        let ids = Set(store.diary.meals.filter { $0.confirmed && $0.cleanupAllowed && !$0.favorite && $0.date < cutoff }.compactMap(\.assetID))
        let fetched = PHAsset.fetchAssets(withLocalIdentifiers: Array(ids), options: nil)
        var result: [PHAsset] = []
        fetched.enumerateObjects { asset, _, _ in
            if !asset.isFavorite, asset.creationDate.map({ $0 < cutoff }) == true { result.append(asset) }
        }
        return result
    }
    func cleanup(_ assets: [PHAsset]) async throws {
        let allowed = Set(cleanupCandidates().map(\.localIdentifier))
        let fresh = PHAsset.fetchAssets(withLocalIdentifiers: assets.map(\.localIdentifier).filter { allowed.contains($0) }, options: nil)
        var safe: [PHAsset] = []
        fresh.enumerateObjects { asset, _, _ in if !asset.isFavorite { safe.append(asset) } }
        guard !safe.isEmpty else { throw MealError.invalid("没有符合条件的照片。") }
        try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(safe as NSArray) }
        status = "已请求清理 \(safe.count) 张照片，饮食记录和本地缩略图仍保留。"
    }
}
