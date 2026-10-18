package dev.mealtrace.app;

import android.content.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Foods {
  final LinkedHashMap<String, JSONObject> foods = new LinkedHashMap<>();
  final HashMap<String, String> names = new HashMap<>();
  final SharedPreferences customPrefs;

  Foods(Context c) throws Exception {
    customPrefs = c.getSharedPreferences("custom_foods", Context.MODE_PRIVATE);
    try (var in = c.getAssets().open("foods.json")) {
      JSONArray a =
          new JSONObject(new String(Io.read(in, 8000000), StandardCharsets.UTF_8)).getJSONArray("foods");
      for (int i = 0; i < a.length(); i++) {
        JSONObject food = a.getJSONObject(i);
        foods.put(food.getString("id"), food);
        index(food);
      }
      JSONArray custom = new JSONArray(customPrefs.getString("items", "[]"));
      for (int i = 0; i < custom.length(); i++) {
        JSONObject food = custom.getJSONObject(i);
        foods.put(food.getString("id"), food);
        index(food);
      }
    }
  }

  String prompt() {
    return "识别照片中看得见的主要食物，最多5项，估计可食部分克数。只输出JSON，格式：{\"food\":"
        + "true,\"name\":\"菜名\",\"items\":[{\"name\":\"食物中文名称\",\"grams\":100}],\"oil\":"
        + "5}。oil是额外烹调油克数。没有食物则输出{\"food\":false}。不要列出照片以外的食物。";
  }

  static String normalized(String name) {
    return name.replace("切片", "")
        .replace("丁", "")
        .replace("块", "")
        .replace("粒", "")
        .replace("片", "")
        .replace("丝", "")
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  void index(JSONObject food) {
    names.put(normalized(food.optString("name")), food.optString("id"));
    names.put(food.optString("id").toLowerCase(Locale.ROOT), food.optString("id"));
    JSONArray aliases = food.optJSONArray("aliases");
    if (aliases != null)
      for (int i = 0; i < aliases.length(); i++)
        names.put(normalized(aliases.optString(i)), food.optString("id"));
  }

  String match(String name) {
    return names.getOrDefault(normalized(name), "unknown");
  }

  List<String> search(String query, String category, boolean all) {
    String q = query.trim().toLowerCase(Locale.ROOT);
    ArrayList<String> result = new ArrayList<>();
    for (var entry : foods.entrySet()) {
      JSONObject f = entry.getValue();
      if (!all && !f.optBoolean("common", true))
        continue;
      if (!category.equals("全部") && !category.equals(f.optString("category")))
        continue;
      String text =
          f.optString("name") + " " + f.optString("description") + " " + f.optJSONArray("aliases");
      if (q.isEmpty() || text.toLowerCase(Locale.ROOT).contains(q))
        result.add(entry.getKey());
    }
    return result;
  }

  JSONObject saveCustom(String name, double kcal, double protein, double carbs, double fat,
      double serving) throws Exception {
    name = name.trim();
    if (name.isBlank() || name.length() > 80 || names.containsKey(normalized(name)))
      throw new IllegalArgumentException("名称为空、过长或已存在；请用品牌或配方名称区分");
    if (!Double.isFinite(kcal) || kcal < 0 || kcal > 1000 || !Double.isFinite(serving)
        || serving < 1 || serving > 2000)
      throw new IllegalArgumentException("请检查每100克能量与默认份量");
    for (double value : new double[] {protein, carbs, fat})
      if (!Double.isFinite(value) || value < 0 || value > 100)
        throw new IllegalArgumentException("三大营养素每100克应在0～100克之间");
    if (protein + carbs + fat > 105)
      throw new IllegalArgumentException("三大营养素合计超过每100克，请核对单位");
    JSONObject f = new JSONObject()
                       .put("id", "custom_" + UUID.randomUUID())
                       .put("name", name)
                       .put("serving", serving)
                       .put("kcal", kcal)
                       .put("protein", protein)
                       .put("carbs", carbs)
                       .put("fat", fat)
                       .put("category", "自定义")
                       .put("common", true)
                       .put("source", "用户填写的营养数据");
    JSONArray saved = new JSONArray(customPrefs.getString("items", "[]"));
    saved.put(f);
    if (!customPrefs.edit().putString("items", saved.toString()).commit())
      throw new java.io.IOException("自定义食物保存失败");
    foods.put(f.getString("id"), f);
    index(f);
    return f;
  }

  JSONObject calculate(JSONObject raw, boolean manual) throws Exception {
    if (!raw.has("food") || !raw.getBoolean("food"))
      return new JSONObject().put("food", false);
    JSONArray input = raw.getJSONArray("items"), items = new JSONArray();
    if (input.length() == 0 || input.length() > 15)
      throw new IllegalArgumentException("无法可靠识别食材，请手动补记");
    double kcal = 0, protein = 0, carbs = 0, fat = 0, grams = 0;
    boolean unknown = false;
    for (int i = 0; i < input.length(); i++) {
      JSONObject v = input.getJSONObject(i),
                 f = foods.get(v.has("id") ? v.optString("id") : match(v.optString("name")));
      double g = v.getDouble("grams");
      if (!Double.isFinite(g) || g <= 0 || g > 2000)
        throw new IllegalArgumentException("食物份量异常，请修正");
      if (f == null) {
        unknown = true;
        items.put(new JSONObject()
                .put("id", "unknown")
                .put("name", v.optString("name", "未匹配食物"))
                .put("grams", g));
        continue;
      }
      JSONObject item = new JSONObject(f.toString()).put("grams", g);
      items.put(item);
      grams += g;
      kcal += f.getDouble("kcal") * g / 100;
      protein += f.getDouble("protein") * g / 100;
      carbs += f.getDouble("carbs") * g / 100;
      fat += f.getDouble("fat") * g / 100;
    }
    double oil = raw.optDouble("oil", 0);
    if (!Double.isFinite(oil) || oil < 0 || oil > 60)
      throw new IllegalArgumentException("用油估计异常，请修正");
    if (oil > 0) {
      JSONObject f = foods.get("oil");
      items.put(new JSONObject(f.toString()).put("grams", oil));
      kcal += f.getDouble("kcal") * oil / 100;
      fat += oil;
    }
    if (kcal > 5000 || grams > 4000)
      throw new IllegalArgumentException("总份量异常，请拆分或手动补记");
    return new JSONObject()
        .put("food", true)
        .put("name", raw.optString("name", "餐食"))
        .put("items", items)
        .put("kcal", kcal)
        .put("low", kcal * (manual ? .9 : .65))
        .put("high", kcal * (manual ? 1.1 : 1.5))
        .put("protein", protein)
        .put("carbs", carbs)
        .put("fat", fat)
        .put("unknown", unknown)
        .put("source", "USDA SR Legacy / 用户填写; 逐项近似参考")
        .put("method", manual ? "用户份量 × 数据库" : "本地视觉估计份量 × 数据库")
        .put("uncertainty",
            "上下界为产品估算范围，不是经过校准的统计置信区间；油、烹饪方式和混合菜仍可能漏估。");
  }
}
