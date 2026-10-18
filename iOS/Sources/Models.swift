import Foundation
import CryptoKit
import Combine

struct ModelSpec: Decodable {
    struct File: Decodable { var name: String; var size: Int64; var sha256: String; var url: URL }
    var name: String
    var files: [File]
}

@MainActor final class Models: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = Models()
    @Published var ready = false
    @Published var busy = false
    @Published var progress = "正在检查本地模型…"
    @Published var fraction = 0.0
    let spec: ModelSpec
    let directory: URL
    private var verified: Set<String> = []
    private var byteCounts: [String: Int64] = [:]
    private var active: Set<String> = []
    private var finishedBackgroundEvents = false
    var backgroundCompletion: (() -> Void)?
    private func finishBackgroundEventsIfPossible() {
        if finishedBackgroundEvents && active.isEmpty {
            backgroundCompletion?(); backgroundCompletion = nil; finishedBackgroundEvents = false
        }
    }
    lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: "dev.mealtrace.ios.models")
        config.allowsCellularAccess = false
        config.sessionSendsLaunchEvents = true
        config.waitsForConnectivity = true
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()
    override init() {
        spec = try! JSONDecoder().decode(ModelSpec.self, from: Data(contentsOf: Bundle.main.url(forResource: "models", withExtension: "json")!))
        directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Models", isDirectory: true)
        super.init()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var excluded = directory; var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? excluded.setResourceValues(values)
        Task { await check() }
    }
    func path(_ name: String) -> URL { directory.appendingPathComponent(name) }
    nonisolated static func checkFile(_ url: URL, spec: ModelSpec.File) throws {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size == spec.size else { throw MealError.invalid("\(spec.name) 文件大小不符，请重新导入或下载。") }
        let input = try FileHandle(forReadingFrom: url); defer { try? input.close() }
        var hash = SHA256()
        while let block = try input.read(upToCount: 4 * 1024 * 1024), !block.isEmpty { hash.update(data: block) }
        let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
        guard digest == spec.sha256 else { throw MealError.invalid("\(spec.name) SHA-256 校验失败，未启用该文件。") }
    }
    func check() async {
        guard !busy else { return }
        busy = true; ready = false; verified = []
        for entry in spec.files {
            let url = path(entry.name)
            if FileManager.default.fileExists(atPath: url.path) {
                do { try await Task.detached(priority: .utility) { try Self.checkFile(url, spec: entry) }.value; verified.insert(entry.name) }
                catch { progress = error.localizedDescription }
            }
        }
        busy = false; refreshStatus()
        _ = session
        session.getAllTasks { tasks in
            Task { @MainActor in
                for task in tasks { if let name = task.taskDescription { self.active.insert(name) } }
                if !self.active.isEmpty { self.busy = true; self.progress = "模型下载已恢复，等待 Wi-Fi 或继续下载…" }
            }
        }
    }
    private func refreshStatus() {
        ready = verified.count == spec.files.count
        if ready { progress = "2B 离线模型已校验，可在本机识别"; fraction = 1 }
        else if !busy { progress = "需安装约 1.95 GB 模型；识别时照片留在本机" }
    }
    func download() {
        guard !busy else { return }
        let available = (try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? 0
        guard available > 3_000_000_000 else { progress = "请先预留至少 3 GB 可用空间。"; return }
        busy = true; progress = "经 Wi-Fi 从 Hugging Face 下载公开模型…"; fraction = 0
        for entry in spec.files where !verified.contains(entry.name) {
            active.insert(entry.name)
            let task = session.downloadTask(with: entry.url); task.taskDescription = entry.name; task.resume()
        }
        if active.isEmpty { busy = false; refreshStatus() }
    }
    func cancel() {
        session.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
        active = []; busy = false; progress = "已取消下载，可稍后重试。"
    }
    func importFiles(_ urls: [URL]) async {
        guard !busy else { return }
        busy = true; ready = false; progress = "正在校验导入的模型…"
        do {
            for url in urls {
                let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard let entry = spec.files.first(where: { $0.size == size }) else { throw MealError.invalid("不是食迹使用的模型文件，请选择 model.gguf 和 vision.gguf。") }
                let destination = path(entry.name)
                let staging = path(UUID().uuidString + ".import")
                try await Task.detached(priority: .utility) {
                    defer { try? FileManager.default.removeItem(at: staging) }
                    try FileManager.default.copyItem(at: url, to: staging)
                    try Self.checkFile(staging, spec: entry)
                    if FileManager.default.fileExists(atPath: destination.path) { _ = try FileManager.default.replaceItemAt(destination, withItemAt: staging) }
                    else { try FileManager.default.moveItem(at: staging, to: destination) }
                }.value
                verified.insert(entry.name)
            }
            busy = false; refreshStatus()
        } catch { busy = false; refreshStatus(); progress = error.localizedDescription }
    }
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let name = downloadTask.taskDescription,
              let response = downloadTask.response as? HTTPURLResponse, response.statusCode == 200 else { return }
        let staged = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".gguf")
        do {
            try FileManager.default.moveItem(at: location, to: staged)
            Task { @MainActor in
                guard let entry = self.spec.files.first(where: { $0.name == name }) else { try? FileManager.default.removeItem(at: staged); return }
                do {
                    try await Task.detached(priority: .utility) { try Self.checkFile(staged, spec: entry) }.value
                    let destination = self.path(name)
                    if FileManager.default.fileExists(atPath: destination.path) { _ = try FileManager.default.replaceItemAt(destination, withItemAt: staged) }
                    else { try FileManager.default.moveItem(at: staged, to: destination) }
                    self.verified.insert(name); self.active.remove(name)
                    self.busy = !self.active.isEmpty; self.refreshStatus()
                    self.finishBackgroundEventsIfPossible()
                } catch {
                    try? FileManager.default.removeItem(at: staged)
                    self.active.remove(name); self.busy = !self.active.isEmpty; self.progress = error.localizedDescription
                    self.finishBackgroundEventsIfPossible()
                }
            }
        } catch { Task { @MainActor in self.active.remove(name); self.busy = !self.active.isEmpty; self.progress = error.localizedDescription; self.finishBackgroundEventsIfPossible() } }
    }
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let name = downloadTask.taskDescription else { return }
        Task { @MainActor in
            self.byteCounts[name] = totalBytesWritten
            let completed = self.spec.files.filter { self.verified.contains($0.name) }.reduce(Int64(0)) { $0 + $1.size }
            self.fraction = min(1, Double(completed + self.byteCounts.filter { !self.verified.contains($0.key) }.values.reduce(0, +)) / Double(self.spec.files.reduce(Int64(0)) { $0 + $1.size }))
            self.progress = "下载模型 \(Int(self.fraction * 100))% · 照片不会上传"
        }
    }
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let name = task.taskDescription else { return }
        let badStatus = (task.response as? HTTPURLResponse).map { $0.statusCode != 200 } ?? (error == nil)
        if let error { Task { @MainActor in self.active.remove(name); self.busy = !self.active.isEmpty; self.progress = "下载未完成：\(error.localizedDescription)"; self.finishBackgroundEventsIfPossible() } }
        else if badStatus { Task { @MainActor in self.active.remove(name); self.busy = !self.active.isEmpty; self.progress = "模型服务器未返回完整文件，请重试。"; self.finishBackgroundEventsIfPossible() } }
    }
    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in self.finishedBackgroundEvents = true; self.finishBackgroundEventsIfPossible() }
    }
}
