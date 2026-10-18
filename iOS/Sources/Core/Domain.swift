import Foundation

struct Food: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var serving: Double
    var kcal: Double
    var protein: Double
    var fat: Double
    var carbs: Double
    var category: String?
    var common: Bool?
    var aliases: [String]?
    var description: String?
    var source: String?
}

struct Ingredient: Codable, Identifiable, Hashable {
    var id = UUID()
    var foodID: String
    var name: String
    var grams: Double
}

struct Meal: Codable, Identifiable {
    var id = UUID()
    var date = Date()
    var title = "餐食"
    var slot = Meal.slot(at: Date())
    var ingredients: [Ingredient] = []
    var confirmed = false
    var weighed = false
    var eaten = 1.0
    var favorite = false
    var cleanupAllowed = false
    var assetID: String?
    var imageName: String?
    var method = "手动记录"
    static let slots = ["早餐", "午餐", "晚餐", "加餐"]
    static func slot(at date: Date) -> Int {
        switch Calendar.current.component(.hour, from: date) {
        case 5..<10: return 0
        case 10..<15: return 1
        case 17..<22: return 2
        default: return 3
        }
    }
}

struct Profile: Codable {
    var age = 0
    var height = 0.0
    var weight = 0.0
    var male = true
    var activity = 1.2
    var special = false
    var manualTarget = 0.0
    var ready: Bool { (18...85).contains(age) && (130...220).contains(height) && (35...250).contains(weight) }
    var bmi: Double { height > 0 ? weight / pow(height / 100, 2) : 0 }
    var target: Double {
        guard ready, !special, bmi >= 18.5 else { return 0 }
        if (1400...4500).contains(manualTarget) { return manualTarget }
        let maintenance = (10 * weight + 6.25 * height - 5 * Double(age) + (male ? 5 : -161)) * activity
        return (max(1400, maintenance - (bmi >= 24 ? 250 : 0)) / 50).rounded() * 50
    }
}

struct WeightEntry: Codable, Identifiable {
    var id = UUID()
    var date = Date()
    var kg: Double
}

struct Diary: Codable {
    var version = 1
    var profile = Profile()
    var meals: [Meal] = []
    var weights: [WeightEntry] = []
    var seenAssets: Set<String> = []
    var seenHashes: Set<String> = []
    var automatic = false
    var monitoringSince = Date()
    var lastComplete: Date?
    var completeDay: String?
    var lastCleanupReminder: String?
    var customFoods: [Food]?
}

struct Nutrients {
    var kcal = 0.0
    var low = 0.0
    var high = 0.0
    var protein = 0.0
    var carbs = 0.0
    var fat = 0.0
    var unknown = 0
    var pending = 0
    var records = 0
    var slots: Set<Int> = []
}

enum MealError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { if case .invalid(let message) = self { return message }; return nil }
}

struct FoodCatalog {
    var foods: [Food]
    var aliases: [String: [String]]
    private var byID: [String: Food]
    private var byName: [String: String]
    init(data: Data, aliases: Data) throws {
        struct List: Decodable { var foods: [Food] }
        foods = try JSONDecoder().decode(List.self, from: data).foods
        self.aliases = try JSONDecoder().decode([String: [String]].self, from: aliases)
        byID = Dictionary(uniqueKeysWithValues: foods.map { ($0.id, $0) })
        byName = [:]
        for food in foods {
            byName[Self.normalize(food.name)] = food.id
            byName[food.id.lowercased()] = food.id
            for name in (self.aliases[food.id] ?? []) + (food.aliases ?? []) { byName[Self.normalize(name)] = food.id }
        }
    }
    func food(_ id: String) -> Food? { byID[id] }
    static func normalize(_ name: String) -> String {
        ["切片", "丁", "块", "粒", "片", "丝"].reduce(name) { $0.replacingOccurrences(of: $1, with: "") }
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
    func match(_ name: String) -> String { byName[Self.normalize(name)] ?? "unknown" }
    func search(_ query: String, category: String, includeAll: Bool) -> [Food] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return foods.filter { food in
            guard includeAll || food.common != false, category == "全部" || food.category == category else { return false }
            return query.isEmpty || ([food.name, food.id, food.description ?? ""] + (food.aliases ?? []) + (aliases[food.id] ?? []))
                .contains { $0.lowercased().contains(query) }
        }
    }
    mutating func addCustom(_ food: Food, restoring: Bool = false) throws {
        guard food.id.hasPrefix("custom_"), !food.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              food.name.count <= 80, byID[food.id] == nil,
              food.kcal.isFinite, (0...1000).contains(food.kcal),
              [food.protein, food.carbs, food.fat].allSatisfy({ $0.isFinite && (0...100).contains($0) }),
              food.protein + food.carbs + food.fat <= 105,
              food.serving.isFinite, (1...2000).contains(food.serving) else { throw MealError.invalid("请检查每 100 克能量、三大营养素与默认份量。") }
        guard restoring || byName[Self.normalize(food.name)] == nil else { throw MealError.invalid("名称已存在，请补上品牌或配方以便区分。") }
        foods.insert(food, at: 0); byID[food.id] = food; byName[Self.normalize(food.name)] = food.id
    }
    func nutrients(_ meal: Meal) -> Nutrients {
        var n = Nutrients()
        n.records = 1
        n.pending = meal.confirmed ? 0 : 1
        n.slots.insert(meal.slot)
        for i in meal.ingredients {
            guard let f = food(i.foodID), i.grams.isFinite, i.grams > 0, i.grams <= 2000 else { n.unknown += 1; continue }
            let scale = i.grams / 100 * meal.eaten
            n.kcal += f.kcal * scale
            n.protein += f.protein * scale
            n.carbs += f.carbs * scale
            n.fat += f.fat * scale
        }
        n.low = n.kcal * (meal.weighed ? 0.9 : 0.65)
        n.high = n.kcal * (meal.weighed ? 1.1 : 1.5)
        return n
    }
    func total(_ meals: [Meal]) -> Nutrients {
        meals.reduce(into: Nutrients()) { result, meal in
            let n = nutrients(meal)
            result.kcal += n.kcal; result.low += n.low; result.high += n.high
            result.protein += n.protein; result.carbs += n.carbs; result.fat += n.fat
            result.unknown += n.unknown; result.pending += n.pending; result.records += 1
            result.slots.formUnion(n.slots)
        }
    }
    func validate(_ meal: Meal) throws {
        guard !meal.ingredients.isEmpty, meal.ingredients.count <= 15,
              meal.eaten.isFinite, meal.eaten > 0, meal.eaten <= 1,
              meal.ingredients.allSatisfy({ $0.grams.isFinite && $0.grams > 0 && $0.grams <= 2000 }),
              meal.ingredients.reduce(0, { $0 + $1.grams }) <= 4000,
              (0..<4).contains(meal.slot), nutrients(meal).kcal <= 5000 else {
            throw MealError.invalid("请检查份量：每项应为 1～2000 克，总量不超过 4000 克。")
        }
        if meal.confirmed && nutrients(meal).unknown > 0 { throw MealError.invalid("请先为未匹配的食物选择食材，再确认。") }
    }
    func parse(_ output: String, date: Date, assetID: String?) throws -> Meal? {
        struct Result: Decodable {
            struct Item: Decodable { var name: String; var grams: Double }
            var food: Bool; var name: String?; var items: [Item]?; var oil: Double?
        }
        guard let start = output.firstIndex(of: "{"), let end = output.lastIndex(of: "}"), start <= end else {
            throw MealError.invalid("模型没有返回完整结果，可重试或手动补记。")
        }
        let raw = try JSONDecoder().decode(Result.self, from: Data(output[start...end].utf8))
        guard raw.food else { return nil }
        guard let items = raw.items, !items.isEmpty, items.count <= 5 else { throw MealError.invalid("食物结果不完整，请手动补记。") }
        var meal = Meal(date: date, title: raw.name ?? "餐食", slot: Meal.slot(at: date), assetID: assetID, method: "本地视觉估计份量 × USDA")
        meal.ingredients = items.map { Ingredient(foodID: match($0.name), name: $0.name, grams: $0.grams) }
        let oil = raw.oil ?? 0
        guard oil.isFinite, oil >= 0, oil <= 60 else { throw MealError.invalid("用油估计异常，请手动修正。") }
        if oil > 0 { meal.ingredients.append(Ingredient(foodID: "oil", name: "额外烹调油", grams: oil)) }
        try validate(meal)
        return meal
    }
}

enum NutritionAdvice {
    static func text(profile p: Profile, total t: Nutrients, complete: Bool, fullDay: Bool, hour: Int) -> (String, String) {
        guard p.ready else { return ("先建立能量参考", "填写年龄、身高、体重和日常活动。仅有照片不能判断全天是否吃够。") }
        guard !p.special, p.bmi >= 18.5 else { return ("按个人专业方案记录", "当前资料不适合自动生成减重或加餐额度。仍可记录饮食和查看趋势。") }
        guard t.records > 0 else { return ("今天还没有记录", "没有记录不代表没有摄入。照常进食，拍照或手动补记。") }
        guard t.pending == 0, t.unknown == 0 else { return ("先核对食物和份量", "照片记录需要确认实际吃下的份量、用油、分餐和重复照片，才能估算加餐空间。") }
        guard complete || fullDay else { return ("还有没有漏记？", "补齐饮料、零食和酱料后，点“截至现在已记全”。当前累计只代表已记录食物。") }
        var reserve = 0.0
        if !fullDay {
            if hour < 10 && !t.slots.contains(0) { reserve += p.target * 0.25 }
            if hour < 15 && !t.slots.contains(1) { reserve += p.target * 0.35 }
            if hour < 22 && !t.slots.contains(2) { reserve += p.target * 0.35 }
        }
        let lo = p.target - t.high - reserve, hi = p.target - t.low - reserve
        if fullDay && t.high < p.target * 0.75 { return ("全天记录可能偏低", "先检查漏记和份量；若确实吃得少，可正常补充一餐。能量达标也不代表营养已经足够。") }
        if lo >= 100 {
            return ("估算仍有加餐空间", "按已确认记录，约还有 \(Int(lo))～\(Int(hi)) kcal。\(reserve > 0 ? "已为后续正餐预留约 \(Int(reserve)) kcal。" : "")饿了可正常加餐，以实际份量为准。")
        }
        return (hi >= 100 ? "份量误差影响加餐判断" : "已接近当前能量参考", "先核对份量和用油。饿了仍可适量进食，无需因照片估算强忍饥饿或补偿性挨饿。单日热量不能判定营养是否充足。")
    }
}

extension Date {
    var dayKey: String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: self)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }
}
