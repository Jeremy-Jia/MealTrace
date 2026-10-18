package dev.mealtrace.app;

import android.content.*;

final class Prefs {
  final SharedPreferences p;
  final Context context;

  Prefs(Context c) {
    context = c.getApplicationContext();
    p = c.getSharedPreferences("mealtrace", Context.MODE_PRIVATE);
  }

  boolean enabled() {
    return p.getBoolean("watch", false);
  }

  long since() {
    return p.getLong("since", System.currentTimeMillis());
  }

  void watch(boolean on) {
    var e = p.edit().putBoolean("watch", on);
    if (!p.contains("since")) e.putLong("since", System.currentTimeMillis());
    e.apply();
  }

  Nutrition.Profile profile() {
    Nutrition.Profile n = new Nutrition.Profile();
    n.age = p.getInt("age", 0);
    n.height = p.getFloat("height", 0);
    n.weight = p.getFloat("weight", 0);
    n.male = p.getBoolean("male", true);
    n.activity = p.getFloat("activity", 1.2f);
    n.special = p.getBoolean("special", false);
    n.manualTarget = p.getFloat("target", 0);
    return n;
  }

  void profile(Nutrition.Profile n) {
    p.edit()
        .putInt("age", n.age)
        .putFloat("height", (float) n.height)
        .putFloat("weight", (float) n.weight)
        .putBoolean("male", n.male)
        .putFloat("activity", (float) n.activity)
        .putBoolean("special", n.special)
        .putFloat("target", (float) n.manualTarget)
        .apply();
  }

  boolean complete(String date, boolean day) {
    if (day) return p.getBoolean(date + ".day", false);
    return p.getBoolean(date + ".now", false)
        && System.currentTimeMillis() - p.getLong(date + ".now.at", 0) < 2 * 60 * 60 * 1000L;
  }

  void complete(String date, boolean day, boolean value) {
    var e = p.edit().putBoolean(date + (day ? ".day" : ".now"), value);
    if (!day) e.putLong(date + ".now.at", System.currentTimeMillis());
    e.apply();
  }

  void invalidate(String date) {
    p.edit().remove(date + ".now").remove(date + ".now.at").remove(date + ".day").apply();
  }
}
