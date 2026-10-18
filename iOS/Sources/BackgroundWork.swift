import BackgroundTasks
import UserNotifications
import UIKit

@MainActor enum BackgroundWork {
    static let identifier = "dev.mealtrace.ios.scan"
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            Task { @MainActor in
                guard let task = task as? BGProcessingTask else { task.setTaskCompleted(success: false); return }
                let work = Task {
                    await Models.shared.check()
                    await PhotoMonitor.shared.scan(limit: 2)
                    task.setTaskCompleted(success: !Task.isCancelled)
                    schedule()
                }
                task.expirationHandler = { work.cancel(); mealvision_cancel() }
            }
        }
    }
    static func schedule() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        guard DiaryStore.shared.diary.automatic else { return }
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date().addingTimeInterval(15 * 60)
        request.requiresNetworkConnectivity = false
        // Heavy local inference should run while charging during system-selected background windows.
        request.requiresExternalPower = true
        do { try BGTaskScheduler.shared.submit(request) }
        catch { PhotoMonitor.shared.status = "后台调度暂不可用，打开应用仍会补扫新照片。" }
    }
    static func reminders(_ enabled: Bool) async {
        let center = UNUserNotificationCenter.current()
        let ids = ["mealtrace.daily", "mealtrace.weekly", "mealtrace.monthly"]
        center.removePendingNotificationRequests(withIdentifiers: ids)
        guard enabled else { return }
        guard (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) == true else { DiaryStore.shared.message = "通知未获允许，可在系统设置中开启。"; return }
        var daily = DateComponents(); daily.hour = 20; daily.minute = 0
        var weekly = daily; weekly.weekday = 1
        var monthly = daily; monthly.day = 1
        for (id, date, title, body) in [
            (ids[0], daily, "今天的饮食，记全了吗？", "打开食迹核对餐食、饮料和份量，查看今日统计。"),
            (ids[1], weekly, "查看本周饮食趋势", "统计只覆盖已记录日期，缺失记录不会按零摄入计算。"),
            (ids[2], monthly, "查看月报与照片清理", "回顾本月饮食；符合条件的旧食物照片，可核对后清理。")
        ] {
            let content = UNMutableNotificationContent(); content.title = title; content.body = body; content.sound = .default
            try? await center.add(UNNotificationRequest(identifier: id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: date, repeats: true)))
        }
    }
    static func remindCleanupIfNeeded() {
        let month = String(Date().dayKey.prefix(7))
        guard DiaryStore.shared.diary.lastCleanupReminder != month, !PhotoMonitor.shared.cleanupCandidates().isEmpty else { return }
        DiaryStore.shared.diary.lastCleanupReminder = month; DiaryStore.shared.save()
        let content = UNMutableNotificationContent(); content.title = "旧食物照片可清理"; content.body = "打开设置核对清单并确认删除；不会静默删除相册照片。"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "mealtrace.cleanup", content: content, trigger: nil))
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BackgroundWork.register(); return true
    }
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        Models.shared.backgroundCompletion = completionHandler
        _ = Models.shared.session
    }
}
