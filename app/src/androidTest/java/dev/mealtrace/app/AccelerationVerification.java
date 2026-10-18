package dev.mealtrace.app;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Separate, same-signed test APK exercises the installed release using a public fixture. */
public final class AccelerationVerification extends Instrumentation {
  private String expected;
  private final JSONArray checks = new JSONArray(), trials = new JSONArray();

  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    expected = args == null ? "npu" : args.getString("expected", "npu");
    start();
  }

  private void check(boolean value, String name) throws Exception {
    if (!value) throw new AssertionError(name);
    checks.put(name);
  }

  @Override public void onStart() {
    Context target = getTargetContext();
    String pref = "verification-acceleration-" + UUID.randomUUID();
    Context isolated = new ContextWrapper(target) {
      @Override public Context getApplicationContext() { return this; }
      @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences(name.equals("acceleration") ? pref
            : name.equals("custom_foods") ? pref + "-foods" : name, mode);
      }
    };
    File photo = null;
    JSONObject report = new JSONObject();
    Bundle out = new Bundle();
    Map<String, ?> settings;
    Map<String, ?> custom;
    var scheduler = target.getSystemService(android.app.job.JobScheduler.class);
    List<android.app.job.JobInfo> paused = new ArrayList<>();
    long before = -1;
    try {
      for (var job : scheduler.getAllPendingJobs()) {
        if (job.getService().getClassName().equals(ScanJob.class.getName())) {
          paused.add(job);
          scheduler.cancel(job.getId());
        }
      }
      MealApp.WORK.submit(() -> {}).get(15, java.util.concurrent.TimeUnit.SECONDS);
      settings = new HashMap<>(new Prefs(target).p.getAll());
      custom = new HashMap<>(target.getSharedPreferences("custom_foods", Context.MODE_PRIVATE).getAll());
      check((target.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0,
          "target is the non-debuggable release APK");
      check(ModelFiles.ready(target), "existing offline model remains installed");
      try (var cursor = Store.get(target).getReadableDatabase().rawQuery("SELECT COUNT(*) FROM meals", null)) {
        cursor.moveToFirst(); before = cursor.getLong(0);
      }
      photo = File.createTempFile("public-meal-verification-", ".jpg", target.getCacheDir());
      try (InputStream in = getContext().getAssets().open("food.jpg")) {
        Files.copy(in, photo.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      LocalVision.releaseIdle();
      int count = expected.equals("npu") ? 4 : 1;
      for (int i = 0; i < count; i++) {
        JSONObject result = LocalVision.recognize(isolated, photo);
        check(result.getBoolean("food"), "public meal produces food JSON " + (i + 1));
        check(result.getString("backend").equals(expected), "actual expected backend " + (i + 1));
        if (expected.equals("npu")) {
          check(result.getBoolean("npu_verified"), "HTP session, vision and 25/25 language layers verified " + (i + 1));
          check(result.getBoolean("session_reused") == (i > 0), "model reuse state " + (i + 1));
        }
        trials.put(new JSONObject().put("backend", result.getString("backend"))
            .put("seconds", result.getDouble("inference_seconds"))
            .put("session_reused", result.optBoolean("session_reused"))
            .put("food", result.getBoolean("food")));
      }
      Foods cached = LocalVision.catalog(isolated);
      check(cached == LocalVision.catalog(isolated), "offline catalog is reused within session");
      JSONObject item = cached.saveCustom("验证专用酸奶", 80, 5, 10, 2, 120);
      Foods refreshed = LocalVision.catalog(isolated);
      check(refreshed != cached && refreshed.match("验证专用酸奶").equals(item.getString("id")),
          "custom food edits invalidate the session catalog cache");
      // Simulate the cached hardware result of an unsupported handset, without touching user settings.
      SharedPreferences state = isolated.getSharedPreferences("acceleration", Context.MODE_PRIVATE);
      state.edit().putBoolean("probed", true).putBoolean("compatible", false).commit();
      JSONObject cpu = LocalVision.recognize(isolated, photo);
      check(cpu.getBoolean("food") && cpu.getString("backend").equals("cpu"),
          "unsupported NPU automatically completes recognition on generic CPU");
      trials.put(new JSONObject().put("backend", "cpu-fallback").put("seconds", cpu.getDouble("inference_seconds")));
      state.edit().putBoolean("compatible", true).commit();
      new Acceleration(isolated).failed();
      check(!new Acceleration(isolated).eligible(() -> { throw new AssertionError("probe should not run during cooldown"); },
          new AtomicBoolean()), "failed NPU is skipped during cooldown");
      boolean cancelled = false;
      try { LocalVision.recognize(isolated, photo, new AtomicBoolean(true)); }
      catch (InterruptedException expectedCancellation) { cancelled = true; }
      check(cancelled, "cancelled request does not start a model or CPU retry");
      var afterSettings = new Prefs(target).p.getAll();
      Set<String> changed = new HashSet<>(settings.keySet());
      changed.addAll(afterSettings.keySet());
      changed.removeIf(k -> Objects.equals(settings.get(k), afterSettings.get(k)));
      Set<String> runtime = new HashSet<>(changed);
      runtime.retainAll(Set.of("last_scan", "scan_status"));
      report.put("changed_background_status_keys", new JSONArray(runtime));
      changed.removeAll(runtime);
      report.put("changed_personal_settings_keys", new JSONArray(changed));
      check(changed.isEmpty(), "personal settings unchanged");
      check(custom.equals(target.getSharedPreferences("custom_foods", Context.MODE_PRIVATE).getAll()),
          "personal custom foods unchanged");
      try (var cursor = Store.get(target).getReadableDatabase().rawQuery("SELECT COUNT(*) FROM meals", null)) {
        cursor.moveToFirst(); check(before == cursor.getLong(0), "no meal record added or removed");
      }
      report.put("status", "passed");
    } catch (Throwable error) {
      try { report.put("status", "failed").put("error", error.toString()); } catch (JSONException ignored) {}
    } finally {
      LocalVision.releaseIdle();
      if (photo != null) photo.delete();
      target.deleteSharedPreferences(pref);
      target.deleteSharedPreferences(pref + "-foods");
      if (new Prefs(target).enabled()) for (var job : paused) scheduler.schedule(job);
      try {
        report.put("version", target.getPackageManager().getPackageInfo(target.getPackageName(), 0).versionName)
            .put("uid", android.os.Process.myUid()).put("checks", checks).put("trials", trials);
      } catch (Exception ignored) {}
      out.putString("report", report.toString());
      finish(report.optString("status").equals("passed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, out);
    }
  }
}
