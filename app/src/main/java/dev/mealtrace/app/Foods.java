package dev.mealtrace.app;

import android.content.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Foods {
  final LinkedHashMap<String, JSONObject> foods = new LinkedHashMap<>();

  Foods(Context c) throws Exception {
    try (var in = c.getAssets().open("foods.json")) {
      JSONArray a =
          new JSONObject(new String(Io.read(in), StandardCharsets.UTF_8)).getJSONArray("foods");
      for (int i = 0; i < a.length(); i++)
        foods.put(a.getJSONObject(i).getString("id"), a.getJSONObject(i));
    }
  }

  String prompt() {
    return "识别照片中看得见的主要食物，最多5项，估计可食部分克数。只输出JSON，格式：{\"food\":"
        + "true,\"name\":\"菜名\",\"items\":[{\"name\":\"食物中文名称\",\"grams\":100}],\"oil\":"
        + "5}。oil是额外烹调油克数。没有食物则输出{\"food\":false}。不要列出照片以外的食物。";
  }

  String match(String name) {
    name =
        name.replace("丁", "")
            .replace("切片", "")
            .replace("块", "")
            .replace("粒", "")
            .replace("片", "")
            .replace("丝", "");
    String normalized = name.trim().toLowerCase(Locale.ROOT);
    for (var e : foods.entrySet())
      if (e.getValue().optString("name").equals(name) || e.getKey().equals(normalized))
        return e.getKey();
    String[][] aliases = {
      {"rice", "米饭", "白饭", "白米", "白米饭"},
      {"brown_rice", "糙米", "糙米饭"},
      {"noodles", "面条", "面", "拉面", "面食"},
      {"bread", "面包", "吐司", "全麦面包"},
      {"egg", "鸡蛋", "炒蛋", "煮鸡蛋", "水煮蛋", "炒鸡蛋", "煎蛋"},
      {"milk", "牛奶", "纯牛奶", "奶"},
      {"yogurt", "酸奶", "无糖酸奶"},
      {"tofu", "豆腐", "嫩豆腐", "老豆腐"},
      {"chicken", "鸡肉", "鸡胸肉", "烤鸡肉"},
      {"pork", "猪肉", "猪里脊", "里脊肉"},
      {"beef", "牛肉", "牛排"},
      {"salmon", "三文鱼", "鲑鱼"},
      {"shrimp", "虾", "虾仁"},
      {"banana", "香蕉"},
      {"apple", "苹果"},
      {"orange", "橙子", "橘子", "橘", "柑橘"},
      {"grape", "葡萄"},
      {"strawberry", "草莓"},
      {"watermelon", "西瓜"},
      {"potato", "土豆", "马铃薯"},
      {"sweet_potato", "红薯", "番薯", "地瓜"},
      {"corn", "玉米", "玉米粒"},
      {"broccoli", "西兰花", "绿花菜"},
      {"tomato", "番茄", "西红柿", "小番茄", "圣女果"},
      {"cucumber", "黄瓜"},
      {"spinach", "菠菜"},
      {"cabbage", "白菜", "大白菜"},
      {"mushroom", "蘑菇", "白蘑菇"},
      {"carrot", "胡萝卜"},
      {"avocado", "牛油果", "鳄梨"},
      {"peanut", "花生", "花生米"},
      {"almond", "杏仁"},
      {"soy_milk", "豆浆"},
      {"oil", "油", "食用油", "植物油"},
      {"sugar", "糖", "白糖"},
      {"soy_sauce", "酱油"},
      {"lettuce", "生菜", "莴苣"},
      {"blueberry", "蓝莓"},
      {"bacon", "培根"},
      {"cauliflower", "花菜", "菜花", "花椰菜"},
      {"brussels", "抱子甘蓝", "球芽甘蓝"},
      {"onion", "洋葱", "红洋葱"},
      {"cheese", "奶酪", "芝士"}
    };
    for (String[] a : aliases)
      for (int i = 1; i < a.length; i++) if (a[i].equals(normalized)) return a[0];
    return "unknown";
  }

  JSONObject calculate(JSONObject raw, boolean manual) throws Exception {
    if (!raw.has("food") || !raw.getBoolean("food")) return new JSONObject().put("food", false);
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
        items.put(
            new JSONObject()
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
    if (kcal > 5000 || grams > 4000) throw new IllegalArgumentException("总份量异常，请拆分或手动补记");
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
        .put("source", "USDA SR Legacy; 食材近似匹配")
        .put("method", manual ? "用户份量 × 数据库" : "本地视觉估计份量 × 数据库")
        .put("uncertainty", "上下界为产品估算范围，不是经过校准的统计置信区间；油、烹饪方式和混合菜仍可能漏估。");
  }
}
