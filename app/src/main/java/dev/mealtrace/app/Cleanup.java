package dev.mealtrace.app;

import android.content.*;
import android.net.Uri;
import android.provider.MediaStore;
import java.time.*;
import java.util.*;

final class Cleanup {
  static List<Store.Meal> candidates(Context c) {
    long before =
        LocalDate.now()
            .minusDays(30)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli();
    List<Store.Meal> safe = new ArrayList<>();
    for (Store.Meal m : Store.get(c).cleanup(before)) if (originalEligible(c, m)) safe.add(m);
    return safe;
  }

  static boolean originalEligible(Context c, Store.Meal m) {
    try (var rows =
        c.getContentResolver()
            .query(
                Uri.parse(m.uri),
                new String[] {
                  MediaStore.MediaColumns.IS_FAVORITE, MediaStore.MediaColumns.IS_TRASHED
                },
                null,
                null,
                null)) {
      return rows != null && rows.moveToFirst() && rows.getInt(0) == 0 && rows.getInt(1) == 0;
    } catch (Exception e) {
      return false;
    }
  }

  static void remind(Context c) {
    Prefs p = new Prefs(c);
    String month = YearMonth.now().toString();
    if (month.equals(p.p.getString("cleanup_month", ""))) return;
    List<Store.Meal> list = candidates(c);
    if (list.isEmpty()) return;
    if (Notify.post(c, 30, "这个月的餐食照片可整理了", list.size() + " 张超过 30 天且已确认的照片可移入回收站。缩略图与饮食记录会保留。"))
      p.p.edit().putString("cleanup_month", month).apply();
  }

  static android.app.PendingIntent request(Context c, List<Store.Meal> meals) {
    List<Uri> uris = new ArrayList<>();
    for (Store.Meal m : meals) if (originalEligible(c, m)) uris.add(Uri.parse(m.uri));
    if (uris.isEmpty()) throw new IllegalStateException("原图已收藏、移动或不可访问，未执行清理");
    return MediaStore.createTrashRequest(c.getContentResolver(), uris, true);
  }

  static void reconcile(Context c, List<Store.Meal> meals) {
    for (Store.Meal m : meals) {
      try (var rows =
          c.getContentResolver()
              .query(Uri.parse(m.uri), new String[] {"is_trashed"}, null, null, null)) {
        if (rows == null || !rows.moveToFirst() || rows.getInt(0) == 1) {
          ContentValues v = new ContentValues();
          v.put("trashed", 1);
          Store.get(c).update(m.id, v);
        }
      } catch (Exception ignored) {
      }
    }
    MealApp.changed(c);
  }
}
