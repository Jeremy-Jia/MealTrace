package dev.mealtrace.app;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;
import java.time.*;

public final class MealWidget extends AppWidgetProvider {
  @Override
  public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
    render(c, manager, ids);
  }

  static void update(Context c) {
    AppWidgetManager manager = AppWidgetManager.getInstance(c);
    render(c, manager, manager.getAppWidgetIds(new ComponentName(c, MealWidget.class)));
  }

  static void render(Context c, AppWidgetManager manager, int[] ids) {
    String day = LocalDate.now().toString();
    Nutrition.Total t = Store.get(c).total(day);
    Prefs p = new Prefs(c);
    Nutrition.Advice a =
        Nutrition.advice(
            p.profile(),
            t,
            p.complete(day, false),
            p.complete(day, true),
            LocalTime.now().getHour());
    for (int id : ids) {
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
      v.setTextViewText(
          R.id.widget_energy, t.records == 0 ? "尚未记录" : Nutrition.round(t.kcal) + " kcal · 已记录");
      v.setTextViewText(R.id.widget_note, a.title());
      v.setOnClickPendingIntent(
          R.id.widget_root,
          PendingIntent.getActivity(
              c,
              0,
              new Intent(c, MainActivity.class),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
      manager.updateAppWidget(id, v);
    }
  }
}
