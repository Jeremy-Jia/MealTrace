import Foundation

var checks = 0
func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    checks += 1
    if !condition() { fatalError("FAILED: \(message)") }
}
func rejects(_ action: () throws -> Void, _ message: String) {
    do { try action(); fatalError("FAILED: \(message)") } catch { checks += 1 }
}
let base = URL(fileURLWithPath: CommandLine.arguments[1])
var catalog = try FoodCatalog(data: Data(contentsOf: base.appendingPathComponent("foods.json")), aliases: Data(contentsOf: base.appendingPathComponent("aliases.json")))
check(catalog.foods.count == 7793 && catalog.foods.filter({ $0.common == true }).count == 113, "Full offline USDA library and curated Chinese entries")
check(catalog.match("番茄丁") == "tomato", "Chinese normalization")
check(catalog.match("看不清的混合菜") == "unknown", "Do not invent food matches")
let rice = Ingredient(foodID: "rice", name: "米饭", grams: 150)
var meal = Meal(ingredients: [rice], confirmed: true, weighed: true)
try catalog.validate(meal)
check(abs(catalog.nutrients(meal).kcal - 195) < 0.001, "Nutrients per 100 grams")
meal.eaten = 0.5
check(abs(catalog.nutrients(meal).kcal - 97.5) < 0.001, "Count actual eaten portion")
meal.ingredients[0].grams = .infinity
rejects({ try catalog.validate(meal) }, "Reject infinity")
meal.ingredients[0].grams = -10
rejects({ try catalog.validate(meal) }, "Reject negative portions")
meal.ingredients[0] = Ingredient(foodID: "unknown", name: "混合菜", grams: 100)
rejects({ try catalog.validate(meal) }, "Unknown foods block confirmation")
meal.confirmed = false
try catalog.validate(meal)
check(catalog.nutrients(meal).unknown == 1, "Unknown draft remains visible")
let raw = "{\"food\":true,\"name\":\"午餐\",\"items\":[{\"name\":\"米饭\",\"grams\":150}],\"oil\":5}"
let parsed = try catalog.parse(raw, date: Date(), assetID: nil)!
check(!parsed.confirmed && parsed.ingredients.count == 2, "Vision always creates an unconfirmed draft and includes oil")
check(abs(catalog.nutrients(parsed).kcal - 239.2) < 0.01, "Vision cannot supply its own calories")
let nonfood = try catalog.parse("{\"food\":false}", date: Date(), assetID: nil)
check(nonfood == nil, "Non-food rejected")
rejects({ _ = try catalog.parse(raw.replacingOccurrences(of: "\"oil\":5", with: "\"oil\":80"), date: Date(), assetID: nil) }, "Reject implausible cooking oil")
var profile = Profile(age: 30, height: 175, weight: 80, male: true, activity: 1.2)
check(profile.target > 1400, "Adult energy reference")
profile.special = true
check(profile.target == 0, "No automatic weight loss for special conditions")
profile.special = false; profile.age = 17
check(profile.target == 0, "No automatic weight loss for minors")
profile.age = 30; profile.weight = 45
check(profile.target == 0, "No automatic weight loss for low BMI")
profile.weight = 80; profile.manualTarget = 1850
check(profile.target == 1850, "Respect user target")
let total = catalog.total([parsed])
check(NutritionAdvice.text(profile: profile, total: total, complete: true, fullDay: false, hour: 12).0 == "先核对食物和份量", "Drafts block snack advice")
var confirmed = parsed; confirmed.confirmed = true
check(NutritionAdvice.text(profile: profile, total: catalog.total([confirmed]), complete: false, fullDay: false, hour: 12).0 == "还有没有漏记？", "Missing completeness blocks snack advice")
let reserveAdvice = NutritionAdvice.text(profile: profile, total: catalog.total([confirmed]), complete: true, fullDay: false, hour: 12)
check(reserveAdvice.1.contains("预留"), "Reserve energy for future meals")
let diary = Diary(profile: profile, meals: [parsed])
let restored = try JSONDecoder().decode(Diary.self, from: JSONEncoder().encode(diary))
check(restored.meals.count == 1 && restored.profile.manualTarget == 1850, "Persistence round trip")
let excessive = Meal(ingredients: Array(repeating: rice, count: 16))
rejects({ try catalog.validate(excessive) }, "Bound ingredient count")
check(catalog.match("车厘子") == "cherry", "Fruit alias")
check(catalog.match("奇异果") == "kiwi", "Alternative fruit name")
check(catalog.match("橘子") == "mandarin" && catalog.match("橙子") == "orange", "Do not confuse mandarin and orange")
check(catalog.match("煎蛋") == "egg_fried" && catalog.match("水煮蛋") == "egg", "Distinguish cooking state")
check(catalog.match("脱脂奶") == "skim_milk", "Milk fat variant")
check(catalog.search("车厘子", category: "水果", includeAll: false).contains { $0.id == "cherry" }, "Search aliases within category")
check(catalog.search("cod", category: "水产", includeAll: true).count > 3, "Full English library search")
let custom = Food(id: "custom_test", name: "我的包装酸奶", serving: 120, kcal: 80, protein: 5, fat: 2, carbs: 10, category: "自定义", common: true, source: "用户填写")
try catalog.addCustom(custom)
check(catalog.match("我的包装酸奶") == "custom_test", "Custom food can be matched next time")
check(catalog.search("", category: "自定义", includeAll: false).count == 1, "Custom food is searchable")
check(abs(catalog.nutrients(Meal(ingredients: [Ingredient(foodID: custom.id, name: custom.name, grams: 120)])).kcal - 96) < 0.001, "Custom food nutrients")
var invalidCustom = custom; invalidCustom.id = "custom_invalid"; invalidCustom.name = "无效营养素"; invalidCustom.protein = 200
rejects({ try catalog.addCustom(invalidCustom) }, "Reject invalid custom macros")
var duplicateName = custom; duplicateName.id = "custom_other"
rejects({ try catalog.addCustom(duplicateName) }, "Custom names must be distinguishable")
let customDiary = Diary(meals: [Meal(ingredients: [Ingredient(foodID: custom.id, name: custom.name, grams: 120)])], customFoods: [custom])
let customRestored = try JSONDecoder().decode(Diary.self, from: JSONEncoder().encode(customDiary))
check(customRestored.customFoods?.first?.kcal == 80, "Persist custom nutrition values")
var futureCollision = custom; futureCollision.id = "custom_existing"; futureCollision.name = "苹果"
try catalog.addCustom(futureCollision, restoring: true)
check(catalog.food(futureCollision.id) != nil, "Catalog upgrades preserve previously saved custom names")
print("PASS: \(checks) domain checks")
