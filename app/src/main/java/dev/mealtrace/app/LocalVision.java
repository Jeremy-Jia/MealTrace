package dev.mealtrace.app;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class LocalVision {
  static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"food\":{\"type\":\"boolean\"},\"name\":{\"type\":"
          + "\"string\"},\"items\":{\"type\":\"array\",\"maxItems\":5,\"items\":{\"type\":\"object\","
          + "\"properties\":{\"name\":{\"type\":\"string\"},\"grams\":{\"type\":\"number\"}},"
          + "\"required\":[\"name\",\"grams\"],\"additionalProperties\":false}},\"oil\":{\"type\":"
          + "\"number\"}},\"required\":[\"food\"],\"additionalProperties\":false}";
  private static final java.util.concurrent.locks.ReentrantLock LOCK =
      new java.util.concurrent.locks.ReentrantLock();
  private static final ScheduledExecutorService IDLE = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "mealtrace-npu-idle");
    t.setDaemon(true);
    return t;
  });
  private static volatile Process current;
  private static volatile NpuSession session;
  private static Foods cachedFoods;
  private static String cachedCustomFoods;
  private static volatile java.util.concurrent.atomic.AtomicBoolean activeStop;
  private static ScheduledFuture<?> release;

  static void cancel() {
    var stop = activeStop;
    if (stop != null) stop.set(true);
    Process running = current;
    if (running != null) running.destroyForcibly();
    NpuSession npu = session;
    if (npu != null) npu.close();
    cachedFoods = null;
    cachedCustomFoods = null;
  }

  static Foods catalog(Context c) throws Exception {
    String custom = c.getSharedPreferences("custom_foods", Context.MODE_PRIVATE).getString("items", "[]");
    if (cachedFoods == null || !custom.equals(cachedCustomFoods)) {
      cachedFoods = new Foods(c);
      cachedCustomFoods = custom;
    }
    return cachedFoods;
  }

  static void releaseIdle() {
    if (!LOCK.tryLock()) return;
    try { closeSession(); } finally { LOCK.unlock(); }
  }

  private static void closeSession() {
    NpuSession npu = session;
    session = null;
    if (npu != null) npu.close();
  }

  static List<String> command(Context c, boolean npu) {
    return new ArrayList<>(Arrays.asList(
        new File(c.getApplicationInfo().nativeLibraryDir,
            npu ? "libmealvision_npu.so" : "libmealvision.so").getAbsolutePath(),
        "-m", ModelFiles.model(c).getAbsolutePath(), "--mmproj", ModelFiles.vision(c).getAbsolutePath(),
        "--image-max-tokens", "512", "-c", "2048", "-n", "350", "-t", "4", "-tb", "4",
        "--temp", "0", "--no-warmup", "--fit", "off", "--log-verbosity", "4", "-j", SCHEMA));
  }

  static ProcessBuilder environment(Context c, ProcessBuilder builder) {
    String lib = c.getApplicationInfo().nativeLibraryDir;
    builder.environment().put("LD_LIBRARY_PATH", lib + ":/vendor/lib64:/system/lib64");
    builder.environment().put("ADSP_LIBRARY_PATH", lib + ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp");
    builder.environment().put("GGML_HEXAGON_VERBOSE", "0");
    builder.environment().put("GGML_HEXAGON_PROFILE", "0");
    return builder;
  }

  private static String execute(Context c, List<String> command,
      java.util.concurrent.atomic.AtomicBoolean stop, int seconds, int maxBytes) throws Exception {
    File output = File.createTempFile("vision-", ".txt", c.getCacheDir());
    Process process = null;
    try {
      InferenceFallback.check(stop);
      process = environment(c, new ProcessBuilder(command)).redirectError(new File("/dev/null"))
          .redirectOutput(output).start();
      current = process;
      process.getOutputStream().close();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        InferenceFallback.check(stop);
        if (System.nanoTime() >= deadline) throw new IOException("本地识别超时，可稍后重试或手动补记");
      }
      InferenceFallback.check(stop);
      if (process.exitValue() != 0) throw new IOException("本地模型暂未完成识别，请重试或手动补记");
      try (InputStream in = new FileInputStream(output)) {
        return new String(Io.read(in, maxBytes), StandardCharsets.UTF_8);
      }
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      if (current == process) current = null;
      output.delete();
    }
  }

  static JSONObject recognize(Context c, File photo) throws Exception {
    return recognize(c, photo, new java.util.concurrent.atomic.AtomicBoolean());
  }

  static JSONObject recognize(Context c, File photo, java.util.concurrent.atomic.AtomicBoolean stop)
      throws Exception {
    while (!LOCK.tryLock(100, TimeUnit.MILLISECONDS)) InferenceFallback.check(stop);
    long started = System.nanoTime();
    try {
      activeStop = stop;
      InferenceFallback.check(stop);
      if (release != null) release.cancel(false);
      if (!ModelFiles.ready(c)) throw new IOException("本地视觉模型尚未安装");
      Acceleration acceleration = new Acceleration(c);
      boolean npu = acceleration.eligible(() -> new JSONObject(execute(c,
          List.of(new File(c.getApplicationInfo().nativeLibraryDir, "libmealaccelprobe.so").getAbsolutePath()),
          stop, 3, 8192)), stop);
      if (!npu) closeSession();
      JSONObject result = InferenceFallback.run(npu,
          () -> {
            if (session == null || !session.alive() || !session.modelKey.equals(NpuSession.modelKey(c))) {
              closeSession();
              session = new NpuSession(c);
            }
            Foods foods = catalog(c);
            JSONObject raw = session.recognize(photo, foods.prompt(), stop);
            JSONObject calculated = foods.calculate(raw, false);
            calculated.put("model", "Qwen3.5-2B Q4_K_M / Hexagon NPU / offline")
                .put("backend", "npu").put("npu_verified", raw.getBoolean("npu_verified"))
                .put("session_reused", raw.getBoolean("session_reused"));
            acceleration.succeeded(true);
            return calculated;
          },
          () -> {
            Foods foods = new Foods(c);
            List<String> command = command(c, false);
            command.addAll(Arrays.asList("--no-mmproj-offload", "-ngl", "0", "--device", "none",
                "--image", photo.getAbsolutePath(), "-p", foods.prompt()));
            String text = execute(c, command, stop, 150, 100000);
            int first = text.indexOf('{'), last = text.lastIndexOf('}');
            if (first < 0 || last <= first) throw new IOException("未得到完整食物结果，请重试或手动补记");
            JSONObject calculated = foods.calculate(new JSONObject(text.substring(first, last + 1)), false);
            calculated.put("model", "Qwen3.5-2B Q4_K_M / CPU / offline").put("backend", "cpu");
            acceleration.succeeded(false);
            return calculated;
          }, stop, error -> { closeSession(); acceleration.failed(); });
      InferenceFallback.check(stop);
      result.put("inference_seconds", (System.nanoTime() - started) / 1e9);
      return result;
    } catch (Exception error) {
      closeSession();
      throw error;
    } finally {
      activeStop = null;
      if (session != null) release = IDLE.schedule(LocalVision::releaseIdle, 60, TimeUnit.SECONDS);
      LOCK.unlock();
    }
  }
}
