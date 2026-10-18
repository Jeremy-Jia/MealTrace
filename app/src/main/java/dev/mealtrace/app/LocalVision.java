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
  private static volatile Process current;

  static synchronized void cancel() {
    if (current != null) current.destroyForcibly();
  }

  static JSONObject recognize(Context c, File photo) throws Exception {
    return recognize(c, photo, new java.util.concurrent.atomic.AtomicBoolean());
  }

  static JSONObject recognize(Context c, File photo, java.util.concurrent.atomic.AtomicBoolean stop)
      throws Exception {
    if (!ModelFiles.ready(c)) throw new IOException("本地视觉模型尚未安装");
    File executable = new File(c.getApplicationInfo().nativeLibraryDir, "libmealvision.so");
    if (!executable.canExecute()) throw new IOException("本机暂不支持此 ARM64 推理程序");
    Foods f = new Foods(c);
    File output = File.createTempFile("vision-", ".txt", c.getCacheDir());
    List<String> command =
        List.of(
            executable.getAbsolutePath(),
            "-m",
            ModelFiles.model(c).getAbsolutePath(),
            "--mmproj",
            ModelFiles.vision(c).getAbsolutePath(),
            "--image",
            photo.getAbsolutePath(),
            "--image-max-tokens",
            "512",
            "-c",
            "2048",
            "-n",
            "350",
            "-t",
            "4",
            "-tb",
            "4",
            "--temp",
            "0",
            "--no-mmproj-offload",
            "-j",
            SCHEMA,
            "-p",
            f.prompt());
    Process process = null;
    try {
      process =
          new ProcessBuilder(command)
              .redirectError(new File("/dev/null"))
              .redirectOutput(output)
              .start();
      current = process;
      process.getOutputStream().close();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
      while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
        if (stop.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (System.nanoTime() > deadline) throw new IOException("本地识别超时，可稍后重试或手动补记");
      }
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      if (process.exitValue() != 0) throw new IOException("本地模型暂未完成识别，请重试或手动补记");
      if (output.length() > 100000) throw new IOException("识别结果过长");
      String text =
          new String(java.nio.file.Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
      int start = text.indexOf('{'), end = text.lastIndexOf('}');
      if (start < 0 || end <= start) throw new IOException("未得到完整食物结果，请重试或手动补记");
      JSONObject result = f.calculate(new JSONObject(text.substring(start, end + 1)), false);
      result.put("model", "Qwen3.5-2B Q4_K_M / CPU / offline");
      return result;
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      current = null;
      output.delete();
    }
  }
}
