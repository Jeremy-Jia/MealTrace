package dev.mealtrace.app;

import java.time.*;
import java.util.*;

/** Deterministic calculations. The vision model never decides a calorie target. */
public final class Nutrition {
  public static final String[] SLOTS = {"早餐", "午餐", "晚餐", "加餐"};

  public static int slot(long millis) {
    int h = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).getHour();
    return h >= 5 && h < 10 ? 0 : h >= 10 && h < 15 ? 1 : h >= 17 && h < 22 ? 2 : 3;
  }

  public static String date(long millis) {
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString();
  }

  public static boolean valid(double n) {
    return Double.isFinite(n) && n >= 0;
  }

  public static class Profile {
    public int age;
    public double height, weight, activity = 1.2;
    public boolean male = true, special;
    public double manualTarget;

    public boolean ready() {
      return age >= 18
          && age <= 85
          && height >= 130
          && height <= 220
          && weight >= 35
          && weight <= 250;
    }

    public double bmi() {
      return weight / Math.pow(height / 100, 2);
    }

    public double maintenance() {
      return (10 * weight + 6.25 * height - 5 * age + (male ? 5 : -161)) * activity;
    }

    public double target() {
      if (!ready() || special || bmi() < 18.5) return 0;
      if (manualTarget >= 1400 && manualTarget <= 4500) return manualTarget;
      // Conservative product policy; this is not a universal physiological floor.
      return Math.round(Math.max(1400, maintenance() - (bmi() >= 24 ? 250 : 0)) / 50) * 50;
    }
  }

  public static class Total {
    public double kcal, low, high, protein, carbs, fat;
    public int records, unconfirmed, unknown;
    public final boolean[] slots = new boolean[4];

    public void add(
        double k,
        double lo,
        double hi,
        double p,
        double c,
        double f,
        int slot,
        boolean confirmed,
        boolean known) {
      records++;
      if (!confirmed) unconfirmed++;
      if (!known) {
        unknown++;
        return;
      }
      kcal += k;
      low += lo;
      high += hi;
      protein += p;
      carbs += c;
      fat += f;
      if (slot >= 0 && slot < 4) slots[slot] = true;
    }
  }

  public record Advice(String title, String body, Double snackBudget) {}

  public static Advice advice(
      Profile p, Total t, boolean completeSoFar, boolean completeDay, int hour) {
    if (!p.ready())
      return new Advice("先建立你的能量参考", "填写身高、体重、年龄和日常活动，再计算减重期间的参考目标。仅有照片不能判断全天是否吃够" + "。", null);
    if (p.special || p.bmi() < 18.5)
      return new Advice("先按个人专业方案记录", "当前资料不适合自动生成减重或加餐额度。仍可记录饮食、查看趋势，并与营养专业人员商定目标" + "。", null);
    if (t.records == 0) return new Advice("今天还没有饮食记录", "照常拍照，或补记食物。没有记录不代表没有摄入；感到饥饿时正常进食。", null);
    if (t.unknown > 0 || t.unconfirmed > 0)
      return new Advice(
          "先确认份量，再判断加餐",
          "有 "
              + (t.unknown > 0 ? t.unknown + " 条尚无热量的记录，" : "")
              + t.unconfirmed
              + " 条待确认。照片中的食物未必全部由你吃下，先检查多人分餐、剩饭和重复照片。",
          null);
    if (!completeSoFar && !completeDay)
      return new Advice("还需要确认有没有漏记", "当前只累计已记录食物。点“截至现在已记全”并补齐饮料、零食、酱料，才能估算加餐空间。", null);
    double target = p.target();
    double reserve = 0;
    if (!completeDay) {
      if (hour < 10 && !t.slots[0]) reserve += target * .25;
      if (hour < 15 && !t.slots[1]) reserve += target * .35;
      if (hour < 22 && !t.slots[2]) reserve += target * .35;
    }
    double lowBudget = target - t.high - reserve;
    double highBudget = target - t.low - reserve;
    String reservation = reserve > 0 ? "已为尚未记录的后续正餐预留约 " + round(reserve) + " kcal。" : "";
    if (completeDay && t.high < target * .75)
      return new Advice(
          "已记全天能量可能偏低", "记录估计低于个人参考目标，请核对漏记与份量。若确实吃得少，可正常补充一餐；能量达标也不代" + "表营养已经足够。", null);
    if (lowBudget >= 100)
      return new Advice(
          "估算仍有加餐空间",
          "按当前记录和目标，加餐空间约 "
              + round(lowBudget)
              + "～"
              + round(highBudget)
              + " kcal。"
              + reservation
              + "如果饿了，可选一份水果、无糖酸奶或鸡蛋；以实际份量为准。",
          Math.min(250.0, Math.max(0, lowBudget)));
    if (highBudget >= 100)
      return new Advice(
          "加餐空间受估算误差影响",
          "当前份量区间跨过参考预算。" + reservation + "先修正主食、用油和分餐份量；饿了仍可适量进食，无需因照片估算强忍饥饿。",
          null);
    return new Advice(
        "已接近当前能量参考",
        "已确认记录接近或超过估算目标。无需补偿性挨饿；若仍饥饿，适量选择有饱腹感的食物，并观察连续" + "数周的体重与饮食趋势。单日热量不能判定营养是否充足。",
        null);
  }

  public static String round(double n) {
    return String.format(Locale.CHINA, "%.0f", Math.max(0, n));
  }
}
