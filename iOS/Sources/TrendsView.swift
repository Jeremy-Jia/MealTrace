import SwiftUI
import Charts

struct TrendsView: View {
    @EnvironmentObject var store: DiaryStore
    @State private var period = 1
    @State private var anchor = Date()
    @State private var weight = 0.0
    private var interval: DateInterval {
        let calendar = Calendar.current
        if period == 0 { let start = calendar.startOfDay(for: anchor); return DateInterval(start: start, end: calendar.date(byAdding: .day, value: 1, to: start)!) }
        return calendar.dateInterval(of: period == 1 ? .weekOfYear : .month, for: anchor)!
    }
    private var meals: [Meal] { store.diary.meals.filter { $0.confirmed && $0.date >= interval.start && $0.date < interval.end } }
    private var days: [Date] { Array(Set(meals.map { Calendar.current.startOfDay(for: $0.date) })).sorted() }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    Picker("统计周期", selection: $period) { Text("每日").tag(0); Text("每周").tag(1); Text("每月").tag(2) }.pickerStyle(.segmented)
                    DatePicker("选择日期", selection: $anchor, displayedComponents: .date)
                    Card {
                        Text("已确认的饮食记录").font(.headline)
                        let total = store.catalog.total(meals)
                        Text("\(Int(total.kcal)) kcal").font(.largeTitle.bold()).monospacedDigit()
                        Text("\(meals.count) 餐 · \(days.count) 个有记录的日期").foregroundStyle(.secondary)
                        if !days.isEmpty { Text("每个记录日平均 \(Int(total.kcal / Double(days.count))) kcal").font(.subheadline) }
                        Text("未记录的日期不按零摄入计算；草稿不计入此处。仅拍午餐时，这些数值不能代表全天摄入。").font(.caption).foregroundStyle(.secondary)
                        if !days.isEmpty {
                            Chart(days, id: \.self) { date in
                                BarMark(x: .value("日期", date, unit: .day), y: .value("千卡", store.catalog.total(meals.filter { $0.date.dayKey == date.dayKey }).kcal))
                                    .foregroundStyle(Palette.green).cornerRadius(4)
                            }.frame(height: 190).chartYAxisLabel("kcal")
                        }
                    }
                    Card {
                        Text("体重趋势").font(.headline)
                        if store.diary.weights.count > 1 {
                            Chart(store.diary.weights.sorted { $0.date < $1.date }) { point in
                                LineMark(x: .value("日期", point.date), y: .value("公斤", point.kg)).foregroundStyle(Palette.green)
                                PointMark(x: .value("日期", point.date), y: .value("公斤", point.kg)).foregroundStyle(Palette.green)
                            }.frame(height: 170).chartYScale(domain: .automatic(includesZero: false))
                        }
                        HStack {
                            TextField("今日体重（kg）", value: $weight, format: .number).keyboardType(.decimalPad)
                            Text("kg").foregroundStyle(.secondary)
                            Button("记录") {
                                guard (35...250).contains(weight) else { store.message = "请填写 35～250 kg 范围内的体重。"; return }
                                store.diary.weights.removeAll { $0.date.dayKey == Date().dayKey }
                                store.diary.weights.append(WeightEntry(kg: weight)); store.save()
                            }
                        }
                        Text("观察连续数周趋势，避免根据一天的体重或照片估算改变进食。").font(.caption).foregroundStyle(.secondary)
                    }
                }.padding(20)
            }.background(Palette.paper.opacity(0.7)).navigationTitle("饮食趋势")
                .onAppear { if weight == 0 { weight = store.diary.weights.last?.kg ?? store.diary.profile.weight } }
        }
    }
}
