#if DEBUG
import Foundation
import Vision
import UIKit

@MainActor enum Verification {
    static func run() async {
        var report: [String: Any] = ["platform": "iOS", "started": ISO8601DateFormatter().string(from: Date()), "fixture": "Public Nutrition5k sample; no personal photos read"]
        do {
            let models = Models.shared
            while models.busy { try await Task.sleep(nanoseconds: 100_000_000) }
            await models.check()
            guard models.ready else { throw MealError.invalid("模型未通过校验") }
            let image = Bundle.main.url(forResource: "food", withExtension: "jpg")!
            let model = models.path("model.gguf").path, projector = models.path("vision.gguf").path
            let started = Date()
            let raw = try await Task.detached(priority: .userInitiated) {
                var failure: UnsafeMutablePointer<CChar>?
                let result = mealvision_recognize(model, projector, image.path, 1, &failure)
                defer { mealvision_free(result); mealvision_free(failure) }
                guard let result else { throw MealError.invalid(failure.map { String(cString: $0) } ?? "native error") }
                return String(cString: result)
            }.value
            report["seconds"] = Date().timeIntervalSince(started)
            report["output"] = raw
            let parsed = try DiaryStore.shared.catalog.parse(raw, date: Date(), assetID: nil)
            guard let parsed, !parsed.confirmed else { throw MealError.invalid("草稿解析失败") }
            report["draftIngredients"] = parsed.ingredients.count
            report["kcalEstimate"] = DiaryStore.shared.catalog.nutrients(parsed).kcal
            report["unknown"] = DiaryStore.shared.catalog.nutrients(parsed).unknown
            // Exercise disk encoding in a disposable file, never replace the user's diary.
            let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("verify-diary.json")
            try JSONEncoder().encode(Diary(meals: [parsed])).write(to: temporary, options: .atomic)
            let restored = try JSONDecoder().decode(Diary.self, from: Data(contentsOf: temporary))
            guard restored.meals.count == 1, !restored.meals[0].confirmed else { throw MealError.invalid("记录往返验证失败") }
            try FileManager.default.removeItem(at: temporary)
            report["persistenceRoundTrip"] = true
            let sandbox = FileManager.default.temporaryDirectory.appendingPathComponent("verification-" + UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: sandbox) }
            let isolated = DiaryStore(directory: sandbox)
            let custom = Food(id: "custom_verify", name: "验证用包装酸奶", serving: 120, kcal: 80, protein: 5, fat: 2, carbs: 10, category: "自定义", common: true, source: "测试值")
            try isolated.addCustomFood(custom)
            let customMeal = Meal(ingredients: [Ingredient(foodID: custom.id, name: custom.name, grams: 120)], confirmed: true)
            try isolated.update(customMeal)
            let reloaded = DiaryStore(directory: sandbox)
            guard reloaded.diary.customFoods?.count == 1, reloaded.diary.meals.count == 1,
                  abs(reloaded.catalog.nutrients(reloaded.diary.meals[0]).kcal - 96) < 0.01 else { throw MealError.invalid("自定义食物持久化验证失败") }
            try reloaded.remove(customMeal)
            guard reloaded.diary.meals.isEmpty else { throw MealError.invalid("测试记录删除失败") }
            // Force an isolated write failure to verify that a meal is not committed in memory.
            try FileManager.default.removeItem(at: reloaded.file)
            try FileManager.default.createDirectory(at: reloaded.file, withIntermediateDirectories: false)
            var rejected = false
            do { try reloaded.update(customMeal) } catch { rejected = true }
            guard rejected, reloaded.diary.meals.isEmpty else { throw MealError.invalid("写入失败时不应提交记录") }
            report["customFoodPersistence"] = true
            report["failedWriteDoesNotCommit"] = true
            report["catalogCount"] = DiaryStore.shared.catalog.foods.count
            report["curatedChineseCount"] = DiaryStore.shared.catalog.foods.filter { $0.common == true }.count
            let request = VNClassifyImageRequest()
            try VNImageRequestHandler(url: image).perform([request])
            report["visionLabels"] = Array((request.results ?? []).prefix(5)).map { ["label": $0.identifier, "confidence": $0.confidence] as [String: Any] }
            report["success"] = true
        } catch { report["success"] = false; report["error"] = error.localizedDescription }
        report["finished"] = ISO8601DateFormatter().string(from: Date())
        let output = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("verification.json")
        try? JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]).write(to: output, options: .atomic)
        PhotoMonitor.shared.status = (report["success"] as? Bool) == true ? "公开样图实机验证完成；没有修改个人记录。" : "样图验证未通过，详见 verification.json"
    }
}
#endif
