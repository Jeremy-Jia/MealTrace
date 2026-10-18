package dev.mealtrace.app;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** One private pipe, one request at a time; conversations are cleared by the native worker. */
final class NpuSession implements AutoCloseable {
  private static final String EOF = "\u0000";
  private final Process process;
  private final BufferedWriter input;
  private final File log;
  private final BlockingQueue<String> replies = new ArrayBlockingQueue<>(4);
  final String modelKey;
  private boolean ready;
  private long sequence;
  private volatile boolean closed;

  static String modelKey(Context c) {
    return ModelFiles.model(c).getAbsolutePath() + ":" + ModelFiles.model(c).lastModified()
        + ":" + ModelFiles.vision(c).lastModified();
  }

  NpuSession(Context c) throws IOException {
    modelKey = modelKey(c);
    log = File.createTempFile("npu-", ".log", c.getCacheDir());
    List<String> command = LocalVision.command(c, true);
    command.addAll(Arrays.asList("-ngl", "99", "--device", "HTP0", "--mmproj-device", "HTP0"));
    ProcessBuilder builder = LocalVision.environment(c, new ProcessBuilder(command));
    builder.environment().put("MEALTRACE_WORKER", "1");
    try {
      process = builder.redirectError(log).start();
    } catch (IOException error) {
      log.delete();
      throw error;
    }
    input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    Thread reader = new Thread(() -> {
      try (BufferedReader stream = new BufferedReader(new InputStreamReader(
          process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = stream.readLine()) != null) {
          if (line.length() > 100000) break;
          if (line.startsWith("{") && !replies.offer(line)) break;
        }
      } catch (IOException ignored) {
      } finally {
        replies.offer(EOF);
      }
    }, "mealtrace-npu-output");
    reader.setDaemon(true);
    reader.start();
  }

  private JSONObject await(long deadline, AtomicBoolean stop) throws Exception {
    while (true) {
      InferenceFallback.check(stop);
      if (System.nanoTime() >= deadline) throw new IOException("NPU 识别超时");
      String line = replies.poll(100, TimeUnit.MILLISECONDS);
      if (EOF.equals(line) || closed) throw new IOException("NPU 会话已结束");
      if (line != null) return new JSONObject(line);
      if (!process.isAlive()) throw new IOException("NPU 进程未完成识别");
    }
  }

  JSONObject recognize(File image, String prompt, AtomicBoolean stop) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    if (!ready) {
      JSONObject handshake = await(deadline, stop);
      if (!handshake.optBoolean("ready") || handshake.optInt("protocol") != 1)
        throw new IOException("NPU 协议不匹配");
      if (log.length() > 1000000) throw new IOException("NPU 初始化日志过长");
      String diagnostics;
      try (InputStream stream = new FileInputStream(log)) {
        diagnostics = new String(Io.read(stream, 1000000), StandardCharsets.UTF_8);
      }
      if (!diagnostics.contains("offloaded 25/25 layers")
          || !diagnostics.contains("CLIP using HTP0 backend")
          || !diagnostics.contains("HTP0 new session"))
        throw new IOException("NPU 未完成实际卸载");
      ready = true;
    }
    long id = ++sequence;
    input.write(new JSONObject().put("id", id).put("image", image.getAbsolutePath())
        .put("prompt", prompt).toString());
    input.newLine();
    input.flush();
    JSONObject reply = await(deadline, stop);
    if (reply.optLong("id", -1) != id || reply.has("error")) throw new IOException("NPU 未返回有效结果");
    return reply.getJSONObject("result").put("npu_verified", true).put("session_reused", id > 1);
  }

  boolean alive() { return !closed && process.isAlive(); }

  @Override public void close() {
    closed = true;
    process.destroyForcibly();
    try { input.close(); } catch (IOException ignored) {}
    log.delete();
  }
}
