package dev.mealtrace.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/**
 * Real-device checks use public test imagery and isolated historical rows, never personal photos.
 */
public final class Verification extends Instrumentation {
  final JSONArray checks = new JSONArray();
  String mode = "verify";

  void check(boolean ok, String name) throws Exception {
    if (!ok) throw new AssertionError(name);
    checks.put(name);
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    if (args != null) mode = args.getString("mode", "verify");
    start();
  }

  @Override
  public void onStart() {
    if (mode.equals("catalog")) {
      catalogFixture();
      return;
    }
    if (mode.equals("catalog-ui")) {
      catalogUiFixture();
      return;
    }
    if (!mode.equals("verify")) {
      backgroundFixture();
      return;
    }
    Context c = getTargetContext();
    Bundle out = new Bundle();
    JSONObject report = new JSONObject();
    File fixture = null;
    android.net.Uri ownedPhoto = null;
    ArrayList<Long> ids = new ArrayList<>();
    try {
      Foods foods = new Foods(c);
      check(foods.foods.size() >= 37, "offline nutrient catalog loaded");
      JSONObject raw =
          new JSONObject(
              "{\"food\":true,\"name\":\"测试米饭\",\"items\":[{\"id\":"
                  + "\"rice\",\"grams\":150}],\"oil\":5}");
      JSONObject calculated = foods.calculate(raw, false);
      check(
          Math.abs(calculated.getDouble("kcal") - 239.2) < .01,
          "150g rice + 5g oil = 239.2 kcal from source data");
      check(
          calculated.getDouble("low") < calculated.getDouble("kcal")
              && calculated.getDouble("high") > calculated.getDouble("kcal"),
          "visual estimate preserves uncertainty range");
      check(
          foods
              .calculate(
                  new JSONObject("{\"food\":true,\"items\":[{\"id\":\"unknown\",\"grams\":100}]}"),
                  false)
              .getBoolean("unknown"),
          "unmatched food cannot silently become a known meal");
      boolean rejected = false;
      try {
        foods.calculate(
            new JSONObject("{\"food\":true,\"items\":[{\"id\":\"rice\",\"grams\":-1}]}"), false);
      } catch (Exception e) {
        rejected = true;
      }
      check(rejected, "invalid grams are rejected");
      Store s = Store.get(c);
      long ts =
          java.time.LocalDate.of(2001, 1, 1)
              .atStartOfDay(java.time.ZoneId.systemDefault())
              .toInstant()
              .toEpochMilli();
      ContentValues media = new ContentValues();
      media.put(
          android.provider.MediaStore.Images.Media.DISPLAY_NAME,
          "MealTrace-verification-" + UUID.randomUUID() + ".jpg");
      media.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
      media.put(
          android.provider.MediaStore.Images.Media.RELATIVE_PATH,
          "Pictures/MealTrace-verification/");
      media.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
      ownedPhoto =
          c.getContentResolver()
              .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, media);
      try (InputStream in = getContext().getAssets().open("food.jpg");
          OutputStream stream = c.getContentResolver().openOutputStream(ownedPhoto)) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) stream.write(b, 0, n);
      }
      media.clear();
      media.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
      c.getContentResolver().update(ownedPhoto, media, null, null);
      long id = s.add(ownedPhoto.toString(), ts, "", UUID.randomUUID().toString());
      ids.add(id);
      ContentValues v = new ContentValues();
      v.put("payload", calculated.toString());
      v.put("status", "estimated");
      v.put("confirmed", 1);
      v.put("fraction", .5);
      v.put("cleanup", 1);
      s.update(id, v);
      check(
          Math.abs(s.total("2001-01-01").kcal - calculated.getDouble("kcal") * .5) < .01,
          "actual consumption fraction is reflected in totals");
      check(
          s.cleanup(System.currentTimeMillis()).stream().anyMatch(m -> m.id == id),
          "confirmed opted-in old photo is a cleanup candidate");
      check(
          Cleanup.candidates(c).stream().anyMatch(m -> m.id == id),
          "original media is rechecked before cleanup");
      media.clear();
      media.put(android.provider.MediaStore.Images.Media.IS_FAVORITE, 1);
      c.getContentResolver().update(ownedPhoto, media, null, null);
      check(
          Cleanup.candidates(c).stream().noneMatch(m -> m.id == id),
          "system gallery favorites are excluded from cleanup");
      media.put(android.provider.MediaStore.Images.Media.IS_FAVORITE, 0);
      c.getContentResolver().update(ownedPhoto, media, null, null);
      v = new ContentValues();
      v.put("favorite", 1);
      s.update(id, v);
      check(
          s.cleanup(System.currentTimeMillis()).stream().noneMatch(m -> m.id == id),
          "favorites are excluded from cleanup");
      v = new ContentValues();
      v.put("favorite", 0);
      v.put("confirmed", 0);
      s.update(id, v);
      check(
          s.cleanup(System.currentTimeMillis()).stream().noneMatch(m -> m.id == id),
          "unconfirmed photos are excluded from cleanup");
      JSONObject export = s.export(new Prefs(c));
      check(
          !export.toString().contains("content://media"),
          "JSON export omits original image URIs and photos");
      ModelFiles.verify(c);
      check(ModelFiles.ready(c), "installed offline model SHA256 matches pinned files");
      try (InputStream in = getContext().getAssets().open("food.jpg")) {
        fixture = new File(c.getCacheDir(), "verification-food.jpg");
        try (FileOutputStream f = new FileOutputStream(fixture)) {
          byte[] b = new byte[8192];
          int n;
          while ((n = in.read(b)) != -1) f.write(b, 0, n);
        }
      }
      Bitmap bmp = BitmapFactory.decodeFile(fixture.getAbsolutePath());
      check(Scanner.food(bmp), "bundled image labeler recognizes public food candidate offline");
      bmp.recycle();
      long start = System.nanoTime();
      JSONObject vision = LocalVision.recognize(c, fixture);
      report.put("native_vision_seconds", (System.nanoTime() - start) / 1e9);
      report.put("vision_result", vision);
      check(
          vision.getBoolean("food"),
          "vision model runs inside ordinary app UID and recognizes public meal");
      check(vision.has("items"), "vision result is parsed and passed through database calculation");
      check(!new File("/system/bin/su").exists(), "test phone does not expose system su binary");
      report.put("status", "passed");
      out.putString("result", "passed");
    } catch (Throwable e) {
      try {
        report.put("status", "failed").put("error", e.toString());
      } catch (Exception ignored) {
      }
      out.putString("result", "failed");
      out.putString("error", e.toString());
    } finally {
      for (long id : ids)
        Store.get(c).getWritableDatabase().delete("meals", "id=?", new String[] {"" + id});
      if (fixture != null) fixture.delete();
      if (ownedPhoto != null) c.getContentResolver().delete(ownedPhoto, null, null);
      try {
        report
            .put("checks", checks)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("model", android.os.Build.MODEL)
            .put("uid", android.os.Process.myUid());
        try (var f = new FileOutputStream(new File(c.getFilesDir(), "verification.json"))) {
          f.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
      } catch (Exception ignored) {
      }
      out.putString("report", report.toString());
      finish(
          report.optString("status").equals("passed")
              ? Activity.RESULT_OK
              : Activity.RESULT_CANCELED,
          out);
    }
  }

  void catalogFixture() {
    Context c = getTargetContext();
    String preferenceName = "verification-catalog-" + UUID.randomUUID();
    Context isolated = new ContextWrapper(c) {
      @Override
      public Context getApplicationContext() { return this; }

      @Override
      public SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences(name.equals("custom_foods") ? preferenceName : name, mode);
      }
    };
    Bundle out = new Bundle();
    JSONObject report = new JSONObject();
    Map<String, ?> originalCustom = c.getSharedPreferences("custom_foods", Context.MODE_PRIVATE).getAll();
    Map<String, ?> originalSettings = new Prefs(c).p.getAll();
    try {
      Foods foods = new Foods(isolated);
      check(foods.foods.size() == 7793, "full 7793-entry offline catalog loaded on device");
      check(foods.foods.values().stream().filter(f -> f.optBoolean("common")).count() == 113,
          "113 curated Chinese entries loaded");
      check(foods.match("车厘子").equals("cherry"), "cherry alias matches");
      check(foods.match("奇异果").equals("kiwi"), "kiwi alias matches");
      check(foods.match("番茄丁").equals("tomato"), "ingredient shape normalization matches");
      check(foods.match("橘子").equals("mandarin") && foods.match("橙子").equals("orange"),
          "mandarin and orange are distinguished");
      check(foods.match("煎蛋").equals("egg_fried") && foods.match("水煮蛋").equals("egg"),
          "fried and boiled egg references are distinguished");
      check(foods.match("牛奶").equals("whole_milk") && foods.match("低脂奶").equals("milk")
          && foods.match("脱脂奶").equals("skim_milk"), "milk fat variants are distinguished");
      check(foods.match("看不清的混合菜").equals("unknown"), "unknown foods remain unknown");
      check(foods.search("车厘子", "水果", false).contains("cherry"), "Chinese alias search works");
      check(foods.search("", "水果", false).stream()
          .allMatch(id -> foods.foods.get(id).optString("category").equals("水果")),
          "fruit category excludes other foods");
      check(foods.search("cod", "水产", true).size() > 3, "full English catalog search works");
      JSONObject custom = foods.saveCustom("验证专用包装酸奶", 80, 5, 10, 2, 120);
      String id = custom.getString("id");
      check(foods.search("", "自定义", false).contains(id), "custom food appears in personal category");
      Foods reloaded = new Foods(isolated);
      check(reloaded.match("验证专用包装酸奶").equals(id), "custom food survives catalog reload");
      JSONObject raw = new JSONObject().put("food", true).put("name", "verification")
          .put("items", new JSONArray().put(new JSONObject().put("id", id).put("grams", 120)))
          .put("oil", 0);
      JSONObject result = reloaded.calculate(raw, true);
      check(Math.abs(result.getDouble("kcal") - 96) < .0001,
          "custom per-100g values calculate the expected 96 kcal at 120g");
      check(result.getJSONArray("items").getJSONObject(0).getString("source").contains("用户"),
          "custom food source remains visible in recorded nutrition");
      boolean invalid = false;
      try { reloaded.saveCustom("无效宏量营养素", 80, 200, 10, 2, 100); }
      catch (IllegalArgumentException expected) { invalid = true; }
      check(invalid, "invalid custom macronutrients are rejected");
      invalid = false;
      try { reloaded.saveCustom("验证专用包装酸奶", 80, 5, 10, 2, 120); }
      catch (IllegalArgumentException expected) { invalid = true; }
      check(invalid, "duplicate custom names are rejected");
      invalid = false;
      try { reloaded.saveCustom("无效能量", Double.NaN, 5, 10, 2, 100); }
      catch (IllegalArgumentException expected) { invalid = true; }
      check(invalid, "non-finite custom energy is rejected");
      JSONObject exported = Store.get(c).export(new Prefs(isolated));
      check(exported.getJSONArray("custom_foods").getJSONObject(0).getString("id").equals(id),
          "JSON export includes custom food definitions");
      check(ModelFiles.ready(c), "previously installed 2B offline model remains ready after update");
      check(originalCustom.equals(c.getSharedPreferences("custom_foods", Context.MODE_PRIVATE).getAll()),
          "personal custom food preferences are unchanged");
      check(originalSettings.equals(new Prefs(c).p.getAll()), "personal settings are unchanged");
      report.put("status", "passed");
    } catch (Throwable e) {
      try { report.put("status", "failed").put("error", e.toString()); } catch (Exception ignored) {}
    } finally {
      c.deleteSharedPreferences(preferenceName);
      try {
        report.put("checks", checks).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("uid", android.os.Process.myUid()).put("version", BuildConfig.VERSION_NAME);
        try (var stream = new FileOutputStream(new File(c.getFilesDir(), "catalog-verification.json"))) {
          stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
      } catch (Exception ignored) {}
      out.putString("report", report.toString());
      finish(report.optString("status").equals("passed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, out);
    }
  }

  android.view.accessibility.AccessibilityNodeInfo uiNode(String text) throws Exception {
    getUiAutomation().waitForIdle(100, 4000);
    var root = getUiAutomation().getRootInActiveWindow();
    if (root != null) for (var node : root.findAccessibilityNodeInfosByText(text)) {
      if (node.getText() != null && node.getText().toString().contains(text)) return node;
    }
    throw new AssertionError("Expected app UI: " + text);
  }

  void clickUi(String text) throws Exception {
    var node = uiNode(text);
    while (!node.isClickable() && node.getParent() != null) node = node.getParent();
    if (!node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
      throw new AssertionError("App UI click failed: " + text);
    waitForIdleSync();
  }

  android.view.accessibility.AccessibilityNodeInfo findEdit(android.view.accessibility.AccessibilityNodeInfo root) {
    if (root == null) return null;
    if ("android.widget.EditText".contentEquals(root.getClassName())) return root;
    for (int i = 0; i < root.getChildCount(); i++) {
      var found = findEdit(root.getChild(i));
      if (found != null) return found;
    }
    return null;
  }

  void catalogUiFixture() {
    Context c = getTargetContext();
    JSONObject report = new JSONObject();
    Bundle out = new Bundle();
    try {
      Foods foods = new Foods(c);
      MainActivity activity = (MainActivity) startActivitySync(new Intent(c, MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      runOnMainSync(() -> activity.chooseFood(foods, id -> {}));
      check(uiNode("其他／自定义食物") != null, "custom food entry is visible in the real selector");
      check(uiNode("包含 USDA 全部条目") != null, "full catalog toggle is visible");
      clickUi("全部");
      clickUi("水果");
      check(uiNode("水果") != null, "fruit category can be selected");
      var edit = findEdit(getUiAutomation().getRootInActiveWindow());
      if (edit == null) throw new AssertionError("Search field missing");
      Bundle text = new Bundle();
      text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "车厘子");
      check(edit.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text),
          "Chinese alias can be entered into the real search field");
      waitForIdleSync();
      check(uiNode("樱桃") != null, "real selector displays cherry for the alias query");
      Bitmap screen = getUiAutomation().takeScreenshot();
      if (screen != null) {
        try (var stream = new FileOutputStream(new File(c.getCacheDir(), "catalog-ui.png"))) {
          screen.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        screen.recycle();
      }
      clickUi("其他／自定义食物");
      check(uiNode("自定义食物") != null && uiNode("保存并选用") != null,
          "custom editor and save action are accessible on the device");
      check(uiNode("每100克") != null, "custom editor explains the per-100g units");
      clickUi("取消");
      clickUi("关闭");
      report.put("status", "passed").put("note", "No meal or custom food was saved by UI verification");
    } catch (Throwable e) {
      try { report.put("status", "failed").put("error", e.toString()); } catch (Exception ignored) {}
    } finally {
      try {
        report.put("checks", checks).put("version", BuildConfig.VERSION_NAME);
        try (var stream = new FileOutputStream(new File(c.getFilesDir(), "catalog-ui-verification.json"))) {
          stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
      } catch (Exception ignored) {}
      out.putString("report", report.toString());
      finish(report.optString("status").equals("passed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, out);
    }
  }

  void backgroundFixture() {
    Context c = getTargetContext();
    Bundle out = new Bundle();
    JSONObject report = new JSONObject();
    File marker = new File(c.getFilesDir(), "background-fixture.uri");
    try {
      if (mode.equals("seed")) {
        if (!Scanner.fullPermission(c) || !new Prefs(c).enabled())
          throw new IllegalStateException("Enable photo monitoring in the app first");
        Jobs.configure(c);
        ContentValues v = new ContentValues();
        v.put(
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            "MealTrace-verification-" + UUID.randomUUID() + ".jpg");
        v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera/");
        v.put(android.provider.MediaStore.Images.Media.DATE_TAKEN, 978307200000L);
        v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
        android.net.Uri uri =
            c.getContentResolver()
                .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        try (InputStream in = getContext().getAssets().open("food.jpg");
            OutputStream stream = c.getContentResolver().openOutputStream(uri)) {
          byte[] b = new byte[8192];
          int n;
          while ((n = in.read(b)) != -1) stream.write(b, 0, n);
        }
        try (var f = new FileOutputStream(marker)) {
          f.write(uri.toString().getBytes(StandardCharsets.UTF_8));
        }
        v.clear();
        v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
        c.getContentResolver().update(uri, v, null, null);
        report
            .put("status", "seeded")
            .put(
                "note",
                "Public fixture, historical DATE_TAKEN; no personal photo or nutrition records"
                    + " changed.");
      } else {
        String uri =
            new String(java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
        Store s = Store.get(c);
        var meals = s.query("uri=?", new String[] {uri}, null);
        if (mode.equals("probe")) {
          report.put("status", meals.isEmpty() ? "not_detected" : meals.get(0).status);
          if (!meals.isEmpty()) {
            report.put("nutrition", meals.get(0).data).put("confirmed", meals.get(0).confirmed);
          }
          report.put("last_scan", new Prefs(c).p.getLong("last_scan", 0));
          report.put(
              "jobs",
              c.getSystemService(android.app.job.JobScheduler.class).getAllPendingJobs().size());
        } else if (mode.equals("cleanup")) {
          try (var cursor =
              c.getContentResolver()
                  .query(
                      android.net.Uri.parse(uri),
                      new String[] {"_display_name", "owner_package_name"},
                      null,
                      null,
                      null)) {
            if (cursor != null
                && cursor.moveToFirst()
                && cursor.getString(0).startsWith("MealTrace-verification-")
                && c.getPackageName().equals(cursor.getString(1)))
              c.getContentResolver().delete(android.net.Uri.parse(uri), null, null);
          }
          for (Store.Meal m : meals) {
            if (!m.thumb.isEmpty()) new File(m.thumb).delete();
            s.getWritableDatabase().delete("meals", "id=?", new String[] {"" + m.id});
          }
          s.getWritableDatabase().delete("scanned", "uri=?", new String[] {uri});
          marker.delete();
          MealApp.changed(c);
          c.getSystemService(android.app.NotificationManager.class).cancel(1);
          report.put("status", "cleaned");
        } else throw new IllegalArgumentException("Unknown test mode");
      }
      report.put("uid", android.os.Process.myUid());
      try (var f =
          new FileOutputStream(new File(c.getFilesDir(), "background-" + mode + ".json"))) {
        f.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
      out.putString("report", report.toString());
      finish(Activity.RESULT_OK, out);
    } catch (Throwable e) {
      out.putString("error", e.toString());
      finish(Activity.RESULT_CANCELED, out);
    }
  }
}
