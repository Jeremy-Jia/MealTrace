import SwiftUI

struct MealEditor: View {
    @EnvironmentObject var store: DiaryStore
    @Environment(\.dismiss) var dismiss
    @State var meal: Meal
    @State private var reviewed = false
    @State private var foodPicker = false
    @State private var replacing: Int?
    @State private var error: String?
    @State private var deleting = false
    private var existing: Bool { store.diary.meals.contains { $0.id == meal.id } }
    var body: some View {
        NavigationStack {
            Form {
                if let name = meal.imageName, let image = UIImage(contentsOfFile: store.imageDirectory.appendingPathComponent(name).path) {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 230).frame(maxWidth: .infinity).listRowInsets(EdgeInsets())
                }
                Section("这一餐") {
                    TextField("餐食名称", text: $meal.title)
                    DatePicker("时间", selection: $meal.date, in: ...Date())
                    Picker("餐次", selection: $meal.slot) { ForEach(0..<4) { Text(Meal.slots[$0]).tag($0) } }
                    Picker("实际吃下", selection: $meal.eaten) {
                        Text("全部").tag(1.0); Text("四分之三").tag(0.75); Text("一半").tag(0.5); Text("四分之一").tag(0.25)
                    }
                }
                Section {
                    ForEach(meal.ingredients.indices, id: \.self) { i in
                        HStack {
                            Button { replacing = i; foodPicker = true } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(meal.ingredients[i].name)
                                    if meal.ingredients[i].foodID == "unknown" { Text("未匹配，点此选择食材").font(.caption).foregroundStyle(.orange) }
                                    else if let food = store.catalog.food(meal.ingredients[i].foodID), food.name != meal.ingredients[i].name {
                                        Text("营养参照：\(food.name)").font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            Spacer()
                            TextField("克数", value: $meal.ingredients[i].grams, format: .number.precision(.fractionLength(0...1)))
                                .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 75)
                            Text("克").foregroundStyle(.secondary)
                        }
                    }.onDelete { meal.ingredients.remove(atOffsets: $0) }
                    Button { replacing = nil; foodPicker = true } label: { Label("添加食物或烹调油", systemImage: "plus.circle") }
                    Toggle("份量来自称重", isOn: $meal.weighed)
                } header: { Text("食材与份量") } footer: { Text("左滑移除食材；点名称可替换。额外烹调油请单独加入。数据库是食材近似值，混合菜仍可能误估。") }
                Section("估算") {
                    let total = store.catalog.nutrients(meal)
                    LabeledContent("按实际吃下的份量", value: "\(Int(total.kcal)) kcal")
                    LabeledContent("估算范围", value: "\(Int(total.low))～\(Int(total.high)) kcal")
                    Text("范围不是经验证的置信区间。照片中的份量、烹调方式和不可见食材会影响结果。").font(.caption).foregroundStyle(.secondary)
                }
                Section {
                    Toggle("收藏这餐", isOn: $meal.favorite)
                    if meal.assetID != nil {
                        Toggle("允许列入 30 天后清理清单", isOn: $meal.cleanupAllowed)
                        Text("只列入已确认且未收藏的旧照片，清理前仍需你核对并通过系统确认。").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section {
                    Toggle("已核对食材、用油、分餐与实际份量", isOn: $reviewed)
                    Button(meal.confirmed ? "保存并保持确认" : "确认这餐已吃下") { commit(confirmed: true) }
                        .disabled(!reviewed || store.catalog.nutrients(meal).unknown > 0 || meal.ingredients.isEmpty)
                    Button("保存为待确认草稿") { commit(confirmed: false) }.disabled(meal.ingredients.isEmpty)
                }
                if existing { Section { Button("删除这条记录", role: .destructive) { deleting = true } } }
            }
            .navigationTitle(existing ? "核对餐食" : "补记一餐").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
            .sheet(isPresented: $foodPicker) { FoodPicker(initialName: replacing.map { meal.ingredients[$0].name } ?? "") { food in
                let original = replacing.map { meal.ingredients[$0] }
                let item = Ingredient(foodID: food.id, name: original?.foodID == "unknown" ? original!.name : food.name, grams: original?.grams ?? food.serving)
                if let i = replacing { meal.ingredients[i] = item } else { meal.ingredients.append(item) }
                reviewed = false
            } }
            .alert("请检查记录", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) { Button("知道了", role: .cancel) {} } message: { Text(error ?? "") }
            .confirmationDialog("删除饮食记录？不会删除相册原图。", isPresented: $deleting, titleVisibility: .visible) {
                Button("删除记录", role: .destructive) {
                    do { try store.remove(meal); dismiss() } catch { self.error = error.localizedDescription }
                }
            }
            .onChange(of: meal.ingredients) { _, _ in reviewed = false }
            .onChange(of: meal.eaten) { _, _ in reviewed = false }
        }
    }
    private func commit(confirmed: Bool) {
        do {
            meal.confirmed = confirmed
            if meal.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { meal.title = Meal.slots[meal.slot] }
            try store.update(meal); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

struct FoodPicker: View {
    @EnvironmentObject var store: DiaryStore
    @Environment(\.dismiss) var dismiss
    @State private var search = ""
    @State private var category = "全部"
    @State private var includeAll = false
    @State private var custom = false
    var initialName = ""
    var choose: (Food) -> Void
    private var results: [Food] { store.catalog.search(search, category: category, includeAll: includeAll) }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("分类", selection: $category) {
                        ForEach(["全部", "水果", "肉类", "蛋类", "奶类", "水产", "主食", "蔬菜", "豆类", "坚果种子", "饮品", "调味", "油脂", "自定义"], id: \.self) { Text($0) }
                    }
                    Toggle("包含 USDA 全部条目（英文原名）", isOn: $includeAll)
                    Button { custom = true } label: { Label("其他／自定义食物", systemImage: "plus.circle") }
                    Text("可搜索中文别名。找不到时可按包装营养表自定义，或明确选择近似食材作参考。生熟、品牌与配方仍会影响结果。").font(.caption).foregroundStyle(.secondary)
                }
                Section("\(results.count) 个匹配 · 每次显示前 150 个") {
                    ForEach(Array(results.prefix(150))) { food in
                        Button { choose(food); dismiss() } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                HStack { Text(food.name); Spacer(); Text("\(Int(food.kcal)) kcal").font(.caption).foregroundStyle(.secondary) }
                                Text("每100克 · \(food.category ?? "食材") · \(food.source ?? "USDA 食材参考")").font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                    if results.isEmpty { Text("未找到匹配，可以添加自定义食物。").foregroundStyle(.secondary) }
                }
            }.navigationTitle("选择食材").searchable(text: $search, prompt: "名称、别名或英文关键词")
                .toolbar { Button("关闭") { dismiss() } }
                .sheet(isPresented: $custom) { CustomFoodEditor(name: search.isEmpty ? initialName : search) { food in choose(food); dismiss() } }
        }
    }
}

struct CustomFoodEditor: View {
    @EnvironmentObject var store: DiaryStore
    @Environment(\.dismiss) var dismiss
    @State var name: String
    @State private var energy = ""
    @State private var protein = ""
    @State private var carbs = ""
    @State private var fat = ""
    @State private var kilojoules = false
    @State private var serving = 100.0
    @State private var error: String?
    var choose: (Food) -> Void
    var body: some View {
        NavigationStack {
            Form {
                Section("食物名称") { TextField("品牌、菜名或自制配方", text: $name) }
                Section {
                    Picker("包装能量单位", selection: $kilojoules) { Text("kcal 千卡").tag(false); Text("kJ 千焦").tag(true) }
                    field("能量（\(kilojoules ? "kJ" : "kcal")）", text: $energy)
                    field("蛋白质（g）", text: $protein)
                    field("碳水化合物（g）", text: $carbs)
                    field("脂肪（g）", text: $fat)
                    LabeledContent("默认一份（g）") { TextField("100", value: $serving, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100) }
                } header: { Text("全部按每 100 克填写") } footer: {
                    Text("从包装、可靠食物数据或已知配方填写；不要把一整包的能量直接填入每100克。若标签按每100毫升，请先按该食物实际密度换算为每100克。kJ 会除以4.184换算为 kcal。")
                }
                if let error { Text(error).foregroundStyle(.red) }
                Button("保存到我的食物并选用") {
                    do {
                        guard let e = number(energy), let p = number(protein), let c = number(carbs), let f = number(fat) else { throw MealError.invalid("请填写能量、蛋白质、碳水和脂肪；没有的项目可填 0。") }
                        let food = Food(id: "custom_" + UUID().uuidString, name: name.trimmingCharacters(in: .whitespacesAndNewlines), serving: serving,
                            kcal: kilojoules ? e / 4.184 : e, protein: p, fat: f, carbs: c, category: "自定义", common: true, source: "用户填写的营养数据")
                        try store.addCustomFood(food); choose(food); dismiss()
                    } catch { self.error = error.localizedDescription }
                }
            }.navigationTitle("自定义食物").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("取消") { dismiss() } }
        }
    }
    private func field(_ label: String, text: Binding<String>) -> some View {
        LabeledContent(label) { TextField("必填", text: text).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 110) }
    }
    private func number(_ text: String) -> Double? { Double(text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")) }
}
