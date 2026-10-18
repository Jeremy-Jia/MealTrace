package dev.mealtrace.app;

import android.content.*;
import android.os.Build;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

final class Acceleration {
  private final SharedPreferences p;
  private final String key = BuildConfig.VERSION_CODE + ":" + Build.FINGERPRINT;
  private static final long RETRY_MS = 24 * 60 * 60 * 1000L;

  Acceleration(Context c) {
    p = c.getSharedPreferences("acceleration", Context.MODE_PRIVATE);
  }

  static boolean compatible(boolean fastCpu, int architecture, boolean unsignedPd) {
    // This APK ships the V81 skeleton. Older DSPs use the generic CPU executable.
    return fastCpu && unsignedPd && architecture >= 81;
  }

  boolean enabled() { return p.getBoolean("enabled", true); }

  void enabled(boolean value) {
    p.edit().putBoolean("enabled", value).remove("failed_at").apply();
    LocalVision.releaseIdle();
  }

  boolean eligible(Callable<JSONObject> probe, AtomicBoolean stop) throws Exception {
    InferenceFallback.check(stop);
    if (!enabled()) return false;
    if (!key.equals(p.getString("key", ""))) {
      p.edit().clear().putBoolean("enabled", true).putString("key", key).commit();
    }
    long age = System.currentTimeMillis() - p.getLong("failed_at", 0);
    if (age >= 0 && age < RETRY_MS) return false;
    if (p.getBoolean("probed", false)) return p.getBoolean("compatible", false);
    boolean capable = false;
    try {
      JSONObject result = probe.call();
      capable = compatible(result.optBoolean("fast_cpu"), result.optInt("hexagon_arch"),
          result.optBoolean("unsigned_pd"));
    } catch (InterruptedException cancelled) {
      throw cancelled;
    } catch (Exception unavailable) {
      InferenceFallback.check(stop);
    }
    p.edit().putBoolean("probed", true).putBoolean("compatible", capable).apply();
    return capable;
  }

  void failed() {
    p.edit().putLong("failed_at", System.currentTimeMillis()).putString("last", "fallback").apply();
  }

  void succeeded(boolean npu) {
    var edit = p.edit();
    if (npu) edit.remove("failed_at").putString("last", "npu");
    else if (!p.getString("last", "").equals("fallback")) edit.putString("last", "cpu");
    edit.apply();
  }

  String description() {
    if (!enabled()) return "已选择 CPU 识别；照片仅在手机中处理。";
    if (!key.equals(p.getString("key", ""))) return "识别时自动检测 NPU，不支持时使用 CPU。";
    switch (p.getString("last", "")) {
      case "npu": return "上次识别使用 NPU 加速；照片仅在手机中处理。";
      case "fallback": return "NPU 暂不可用，已自动使用 CPU；24 小时后重试加速。";
      case "cpu": return "上次识别使用 CPU；支持的设备会自动使用 NPU。";
      default: return "识别时自动检测 NPU，不支持时使用 CPU。";
    }
  }
}
