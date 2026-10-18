package dev.mealtrace.app;

import android.content.Context;
import java.time.*;

final class Reports {
  static void notifyDue(Context c) {
    Prefs p = new Prefs(c);
    if (!p.enabled()) return;
    LocalDate today = LocalDate.now();
    int hour = LocalTime.now().getHour();
    if (hour >= 20) {
      String key = "daily_report_" + today;
      if (!p.p.getBoolean(key, false)) {
        Nutrition.Total t = Store.get(c).total(today.toString());
        if (t.records > 0) {
          var a =
              Nutrition.advice(
                  p.profile(),
                  t,
                  p.complete(today.toString(), false),
                  p.complete(today.toString(), true),
                  hour);
          if (Notify.post(
              c, 40, "今天的饮食记录 · " + Nutrition.round(t.kcal) + " kcal", a.title() + "。" + a.body()))
            p.p.edit().putBoolean(key, true).apply();
        }
      }
    }
    LocalDate week =
        today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    if (today.getDayOfWeek() == DayOfWeek.SUNDAY && hour >= 20)
      period(c, "week_" + week, week, today, "本周饮食记录");
    if (today.getDayOfMonth() <= 3) {
      YearMonth last = YearMonth.from(today).minusMonths(1);
      period(c, "month_" + last, last.atDay(1), last.atEndOfMonth(), "上月饮食记录");
    }
  }

  static void period(Context c, String key, LocalDate start, LocalDate end, String title) {
    Prefs p = new Prefs(c);
    if (p.p.getBoolean(key, false)) return;
    int days = 0, complete = 0;
    double sum = 0;
    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
      Nutrition.Total t = Store.get(c).total(d.toString());
      if (t.records > 0) {
        days++;
        sum += t.kcal;
      }
      if (p.complete(d.toString(), true) && t.unknown == 0 && t.unconfirmed == 0) complete++;
    }
    if (days > 0
        && Notify.post(
            c,
            41,
            title,
            "记录了 "
                + days
                + " 天，已记录食物合计 "
                + Nutrition.round(sum)
                + " kcal，全天确认 "
                + complete
                + " 天。未记录的日子未按零摄入计算。打开查看趋势与建议。")) p.p.edit().putBoolean(key, true).apply();
  }
}
