package dev.mealtrace.app;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;

final class ModelFiles {
  static File dir(Context c) {
    File f = new File(c.getNoBackupFilesDir(), "models");
    f.mkdirs();
    return f;
  }

  static File model(Context c) {
    return new File(dir(c), "model.gguf");
  }

  static File vision(Context c) {
    return new File(dir(c), "vision.gguf");
  }

  static JSONObject spec(Context c) throws Exception {
    try (var in = c.getAssets().open("models.json")) {
      return new JSONObject(new String(Io.read(in), StandardCharsets.UTF_8));
    }
  }

  static boolean ready(Context c) {
    return model(c).length() == 1280835840L
        && vision(c).length() == 668227264L
        && new File(dir(c), "verified.2b.v1").isFile();
  }

  static String sha(File f) throws Exception {
    MessageDigest d = MessageDigest.getInstance("SHA-256");
    try (var in = new FileInputStream(f)) {
      byte[] buf = new byte[262144];
      int n;
      while ((n = in.read(buf)) != -1) d.update(buf, 0, n);
    }
    StringBuilder s = new StringBuilder();
    for (byte b : d.digest()) s.append(String.format("%02x", b & 255));
    return s.toString();
  }

  static void verify(Context c) throws Exception {
    JSONArray a = spec(c).getJSONArray("files");
    for (int i = 0; i < a.length(); i++) {
      JSONObject item = a.getJSONObject(i);
      File f = new File(dir(c), item.getString("name"));
      if (f.length() != item.getLong("size") || !sha(f).equals(item.getString("sha256")))
        throw new IOException("模型校验失败：" + f.getName());
    }
    try (var out = new FileOutputStream(new File(dir(c), "verified.2b.v1"))) {
      out.write("Qwen3.5-2B pinned v1".getBytes(StandardCharsets.UTF_8));
    }
  }

  static void importZip(Context c, android.net.Uri uri) throws Exception {
    if (ModelDownload.running) throw new IOException("请等待当前模型下载完成");
    new File(dir(c), "verified.2b.v1").delete();
    JSONArray expected = spec(c).getJSONArray("files");
    java.util.Map<String, JSONObject> entries = new java.util.HashMap<>();
    for (int i = 0; i < expected.length(); i++)
      entries.put(expected.getJSONObject(i).getString("name"), expected.getJSONObject(i));
    java.util.Set<String> found = new java.util.HashSet<>();
    try (var zip = new java.util.zip.ZipInputStream(c.getContentResolver().openInputStream(uri))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        JSONObject item = entries.get(entry.getName());
        if (entry.isDirectory() || item == null) continue;
        if (!found.add(entry.getName())) throw new IOException("模型包中存在重复文件");
        File partial = new File(dir(c), entry.getName() + ".import");
        long total = 0;
        try (var out = new FileOutputStream(partial)) {
          byte[] b = new byte[262144];
          int n;
          while ((n = zip.read(b)) != -1) {
            total += n;
            if (total > item.getLong("size")) throw new IOException("模型文件过大");
            out.write(b, 0, n);
          }
        }
        if (total != item.getLong("size") || !sha(partial).equals(item.getString("sha256"))) {
          partial.delete();
          throw new IOException("模型文件校验未通过");
        }
        File target = new File(dir(c), entry.getName());
        if (target.exists() && !target.delete()) throw new IOException("无法替换旧模型");
        if (!partial.renameTo(target)) throw new IOException("无法保存模型");
      }
    }
    if (found.size() != 2) throw new IOException("需要包含 model.gguf 和 vision.gguf 的完整模型包");
    verify(c);
  }
}
