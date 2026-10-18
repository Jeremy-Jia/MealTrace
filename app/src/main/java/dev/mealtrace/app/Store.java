package dev.mealtrace.app;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.util.*;
import org.json.*;

final class Store extends SQLiteOpenHelper {
  static Store instance;

  static synchronized Store get(Context c) {
    if (instance == null) instance = new Store(c.getApplicationContext());
    return instance;
  }

  private Store(Context c) {
    super(c, "food.db", null, 1);
  }

  @Override
  public void onCreate(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE meals(id INTEGER PRIMARY KEY,uri TEXT UNIQUE,ts INTEGER NOT NULL,day TEXT NOT"
            + " NULL,slot INTEGER NOT NULL,title TEXT NOT NULL DEFAULT '待识别的餐食',status TEXT NOT"
            + " NULL DEFAULT 'pending',confirmed INTEGER DEFAULT 0,fraction REAL DEFAULT 1,payload"
            + " TEXT DEFAULT '{}',thumb TEXT DEFAULT '',hash TEXT DEFAULT '',favorite INTEGER"
            + " DEFAULT 0,cleanup INTEGER DEFAULT 0,trashed INTEGER DEFAULT 0,error TEXT DEFAULT"
            + " '',attempts INTEGER DEFAULT 0,nexttry INTEGER DEFAULT 0)");
    d.execSQL("CREATE INDEX meals_day ON meals(day,ts)");
    d.execSQL(
        "CREATE TABLE scanned(uri TEXT PRIMARY KEY,ts INTEGER NOT NULL,result TEXT NOT NULL)");
    d.execSQL("CREATE TABLE weights(day TEXT PRIMARY KEY,kg REAL NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase d, int a, int b) {
    throw new IllegalStateException("Unsupported schema migration");
  }

  boolean seen(String uri) {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT 1 FROM scanned WHERE uri=?", new String[] {uri})) {
      return c.moveToFirst();
    }
  }

  void seen(String uri, long ts, String result) {
    ContentValues v = new ContentValues();
    v.put("uri", uri);
    v.put("ts", ts);
    v.put("result", result);
    getWritableDatabase().insertWithOnConflict("scanned", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  long add(String uri, long ts, String thumb, String hash) {
    ContentValues v = new ContentValues();
    v.put("uri", uri);
    v.put("ts", ts);
    v.put("day", Nutrition.date(ts));
    v.put("slot", Nutrition.slot(ts));
    v.put("thumb", thumb);
    v.put("hash", hash);
    return getWritableDatabase()
        .insertWithOnConflict("meals", null, v, SQLiteDatabase.CONFLICT_IGNORE);
  }

  void update(long id, ContentValues v) {
    getWritableDatabase().update("meals", v, "id=?", new String[] {"" + id});
  }

  static class Meal {
    long id, ts;
    String uri, day, title, status, thumb, hash, error;
    int slot, attempts;
    boolean confirmed, favorite, cleanup, trashed;
    double fraction;
    JSONObject data;

    boolean known() {
      return status.equals("estimated") || status.equals("manual");
    }

    boolean active() {
      return !status.equals("ignored") && !status.equals("duplicate");
    }

    double n(String k) {
      return data.optDouble(k, 0) * fraction;
    }

    String state() {
      return status.equals("ignored")
          ? "非餐食"
          : status.equals("duplicate")
              ? "重复照片"
              : confirmed
                  ? "已确认"
                  : status.equals("estimated")
                      ? "估算 · 待确认"
                      : status.equals("error") ? "识别待重试" : "待识别";
    }
  }

  private Meal read(Cursor c) {
    Meal m = new Meal();
    m.id = c.getLong(c.getColumnIndexOrThrow("id"));
    m.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
    m.uri = str(c, "uri");
    m.day = str(c, "day");
    m.title = str(c, "title");
    m.status = str(c, "status");
    m.thumb = str(c, "thumb");
    m.hash = str(c, "hash");
    m.error = str(c, "error");
    m.slot = c.getInt(c.getColumnIndexOrThrow("slot"));
    m.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
    m.fraction = c.getDouble(c.getColumnIndexOrThrow("fraction"));
    m.confirmed = integer(c, "confirmed");
    m.favorite = integer(c, "favorite");
    m.cleanup = integer(c, "cleanup");
    m.trashed = integer(c, "trashed");
    try {
      m.data = new JSONObject(str(c, "payload"));
    } catch (JSONException e) {
      m.data = new JSONObject();
    }
    return m;
  }

  private static String str(Cursor c, String k) {
    return c.getString(c.getColumnIndexOrThrow(k));
  }

  private static boolean integer(Cursor c, String k) {
    return c.getInt(c.getColumnIndexOrThrow(k)) != 0;
  }

  List<Meal> query(String where, String[] args, String order) {
    ArrayList<Meal> out = new ArrayList<>();
    try (Cursor c = getReadableDatabase().query("meals", null, where, args, null, null, order)) {
      while (c.moveToNext()) out.add(read(c));
    }
    return out;
  }

  List<Meal> day(String day) {
    return query("day=?", new String[] {day}, "ts ASC");
  }

  List<Meal> recent() {
    return query(null, null, "ts DESC LIMIT 60");
  }

  Meal meal(long id) {
    var l = query("id=?", new String[] {"" + id}, null);
    return l.isEmpty() ? null : l.get(0);
  }

  List<Meal> pending() {
    return query(
        "status IN ('pending','error') AND attempts<3 AND nexttry<=?",
        new String[] {"" + System.currentTimeMillis()},
        "ts ASC LIMIT 3");
  }

  Nutrition.Total total(String day) {
    Nutrition.Total t = new Nutrition.Total();
    for (Meal m : day(day))
      if (m.active())
        t.add(
            m.n("kcal"),
            m.n("low"),
            m.n("high"),
            m.n("protein"),
            m.n("carbs"),
            m.n("fat"),
            m.slot,
            m.confirmed,
            m.known());
    return t;
  }

  List<Meal> cleanup(long before) {
    return query(
        "ts<? AND confirmed=1 AND cleanup=1 AND favorite=0 AND trashed=0 AND status IN "
            + "('estimated','manual') AND uri LIKE 'content://media/%'",
        new String[] {"" + before}, "ts ASC");
  }

  void weight(String day, double kg) {
    ContentValues v = new ContentValues();
    v.put("day", day);
    v.put("kg", kg);
    getWritableDatabase().insertWithOnConflict("weights", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  JSONArray weights() throws JSONException {
    JSONArray a = new JSONArray();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT day,kg FROM weights ORDER BY day DESC LIMIT 90", null)) {
      while (c.moveToNext())
        a.put(new JSONObject().put("day", c.getString(0)).put("kg", c.getDouble(1)));
    }
    return a;
  }

  JSONObject export(Prefs prefs) throws JSONException {
    JSONArray a = new JSONArray();
    for (Meal m : query(null, null, "ts ASC"))
      a.put(
          new JSONObject()
              .put("id", m.id)
              .put("time", m.ts)
              .put("day", m.day)
              .put("slot", m.slot)
              .put("title", m.title)
              .put("status", m.status)
              .put("confirmed", m.confirmed)
              .put("fraction", m.fraction)
              .put("favorite", m.favorite)
              .put("allow_cleanup", m.cleanup)
              .put("trashed", m.trashed)
              .put("nutrition", m.data));
    JSONObject coverage = new JSONObject();
    for (var e : prefs.p.getAll().entrySet())
      if (e.getKey().endsWith(".day") || e.getKey().endsWith(".now"))
        coverage.put(e.getKey(), e.getValue());
    return new JSONObject()
        .put("schema", 1)
        .put("app", "MealTrace 0.1.0")
        .put("exported_at", System.currentTimeMillis())
        .put("meals", a)
        .put("coverage", coverage)
        .put("weights", weights())
        .put("note", "照片热量是估算；未记录不代表零摄入。未导出照片和路径。");
  }
}
