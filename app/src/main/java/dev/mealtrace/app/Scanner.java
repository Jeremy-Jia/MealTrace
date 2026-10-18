package dev.mealtrace.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.*;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

final class Scanner {
  static boolean fullPermission(Context c) {
    return c.checkSelfPermission(
            Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_GRANTED;
  }

  static Bitmap bitmap(Context c, Uri uri, int max) throws Exception {
    return ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(c.getContentResolver(), uri),
        (decoder, info, source) -> {
          decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
          int w = info.getSize().getWidth(), h = info.getSize().getHeight();
          double scale = Math.min(1.0, (double) max / Math.max(w, h));
          decoder.setTargetSize(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
        });
  }

  static File save(Context c, Bitmap b) throws Exception {
    File d = new File(c.getNoBackupFilesDir(), "photos");
    d.mkdirs();
    File f = new File(d, UUID.randomUUID() + ".jpg");
    try (var out = new FileOutputStream(f)) {
      b.compress(Bitmap.CompressFormat.JPEG, 85, out);
    }
    return f;
  }

  static boolean food(Bitmap b) throws Exception {
    ImageLabeler labeler =
        ImageLabeling.getClient(
            new ImageLabelerOptions.Builder().setConfidenceThreshold(.3f).build());
    try {
      List<ImageLabel> labels =
          Tasks.await(labeler.process(InputImage.fromBitmap(b, 0)), 20, TimeUnit.SECONDS);
      Set<String> words =
          Set.of(
              "food",
              "dish",
              "meal",
              "cuisine",
              "fruit",
              "vegetable",
              "dessert",
              "snack",
              "baked goods",
              "bread",
              "drink",
              "juice",
              "coffee",
              "meat",
              "seafood",
              "soup",
              "breakfast",
              "lunch",
              "dinner",
              "tableware",
              "rice",
              "noodle",
              "egg",
              "salad",
              "plate");
      for (ImageLabel l : labels)
        if (words.contains(l.getText().toLowerCase(Locale.ROOT))) return true;
      return false;
    } finally {
      labeler.close();
    }
  }

  static long importPhoto(Context c, Uri uri) throws Exception {
    Bitmap b = bitmap(c, uri, 640);
    File f = save(c, b);
    b.recycle();
    String hash = ModelFiles.sha(f);
    Store s = Store.get(c);
    long id =
        s.add("import:" + UUID.randomUUID(), System.currentTimeMillis(), f.getAbsolutePath(), hash);
    new Prefs(c).invalidate(Nutrition.date(System.currentTimeMillis()));
    MealApp.changed(c);
    return id;
  }

  static void scan(Context c, AtomicBoolean stop) throws Exception {
    Prefs p = new Prefs(c);
    if (!p.enabled() || !fullPermission(c)) return;
    Store s = Store.get(c);
    int processed = 0, found = 0;
    String[] columns = {
      "_id", "date_added", "datetaken", "relative_path", "is_pending", "is_trashed"
    };
    String where = "date_added>=? AND is_pending=0 AND is_trashed=0 AND relative_path LIKE ?";
    try (Cursor rows =
        c.getContentResolver()
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                columns,
                where,
                new String[] {"" + (p.since() / 1000 - 5), "DCIM/Camera/%"},
                "date_added ASC")) {
      if (rows != null)
        while (rows.moveToNext() && !stop.get()) {
          String uri =
              ContentUris.withAppendedId(
                      MediaStore.Images.Media.EXTERNAL_CONTENT_URI, rows.getLong(0))
                  .toString();
          if (s.seen(uri)) continue;
          long ts = rows.getLong(2);
          if (ts <= 0) ts = rows.getLong(1) * 1000;
          Bitmap b = null;
          File photo = null;
          try {
            b = bitmap(c, Uri.parse(uri), 640);
            if (!food(b)) {
              s.seen(uri, ts, "not_food_candidate");
              continue;
            }
            photo = save(c, b);
            String hash = ModelFiles.sha(photo);
            List<Store.Meal> duplicates =
                s.query("hash=? AND ABS(ts-?)<900000", new String[] {hash, "" + ts}, null);
            if (!duplicates.isEmpty()) {
              s.seen(uri, ts, "exact_duplicate");
              photo.delete();
              continue;
            }
            long id = s.add(uri, ts, photo.getAbsolutePath(), hash);
            if (id != -1) {
              found++;
              p.invalidate(Nutrition.date(ts));
            } else photo.delete();
            s.seen(uri, ts, "candidate");
          } catch (Exception e) {
            if (photo != null)
              photo.delete(); /* Leave unscanned for a later run if a photo is still being written.
                               */
          } finally {
            if (b != null) b.recycle();
          }
          if (++processed >= 20) break;
        }
    }
    p.p
        .edit()
        .putLong("last_scan", System.currentTimeMillis())
        .putString("scan_status", "最近扫描发现 " + found + " 张餐食候选")
        .apply();
    if (found > 0) Notify.post(c, 1, "发现新的餐食照片", found + " 张照片已加入食迹，将在本地识别。识别后可快捷确认份量。");
  }

  static void analyze(Context c, AtomicBoolean stop) throws Exception {
    if (!ModelFiles.ready(c)) return;
    Store s = Store.get(c);
    for (Store.Meal m : s.pending()) {
      if (stop.get()) return;
      ContentValues v = new ContentValues();
      try {
        JSONObject result = LocalVision.recognize(c, new File(m.thumb), stop);
        if (stop.get()) return;
        if (!result.getBoolean("food")) {
          v.put("status", "ignored");
          v.put("title", "未识别到餐食");
        } else {
          v.put("status", result.optBoolean("unknown") ? "error" : "estimated");
          v.put("title", result.optString("name", "餐食"));
          v.put("payload", result.toString());
          v.put("confirmed", 0);
          v.put("error", result.optBoolean("unknown") ? "有食物未匹配数据库，请手动修正" : "");
        }
        v.put("attempts", result.optBoolean("unknown") ? 3 : 0);
        v.put("nexttry", 0);
      } catch (Exception e) {
        v.put("status", "error");
        v.put("attempts", m.attempts + 1);
        v.put("nexttry", System.currentTimeMillis() + 15 * 60 * 1000L * (m.attempts + 1));
        v.put("error", e.getMessage() == null ? "本地识别暂未完成" : e.getMessage());
      }
      s.update(m.id, v);
      new Prefs(c).invalidate(m.day);
      MealApp.changed(c);
    }
  }
}
