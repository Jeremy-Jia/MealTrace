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
