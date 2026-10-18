package dev.mealtrace.app;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import org.json.*;

/** Network is used only to fetch pinned public model files. No photographs leave the phone. */
public final class ModelDownload extends Service {
  static volatile boolean running;
  static volatile String progress = "";
  volatile boolean cancelled;

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public int onStartCommand(Intent i, int flags, int startId) {
    if (running) return START_NOT_STICKY;
    running = true;
    startForeground(90, Notify.make(this, "models", "正在准备离线识别", "首次下载约 1.95 GB；照片不会上传"));
    new Thread(
            () -> {
              try {
                JSONArray files = ModelFiles.spec(this).getJSONArray("files");
                for (int j = 0; j < files.length(); j++) download(files.getJSONObject(j), j);
                progress = "正在校验模型…";
                MealApp.changed(this);
                ModelFiles.verify(this);
                progress = "离线视觉模型已就绪";
                Notify.post(this, 91, "离线识别已准备好", "之后识别餐食无需联网，也无需账号。");
                Jobs.analysis(this);
              } catch (Exception e) {
                progress = "下载未完成，可点击继续。";
                Notify.post(this, 91, "模型尚未安装完成", "网络中断或文件校验未通过，可继续下载或通过 USB 导入。");
              } finally {
                running = false;
                MealApp.changed(this);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
              }
            },
            "model-download")
        .start();
    return START_NOT_STICKY;
  }

  private void download(JSONObject item, int index) throws Exception {
    File target = new File(ModelFiles.dir(this), item.getString("name"));
    long size = item.getLong("size");
    if (target.length() == size && ModelFiles.sha(target).equals(item.getString("sha256"))) return;
    File partial = new File(ModelFiles.dir(this), item.getString("name") + ".part");
    long offset = partial.length();
    if (offset >= size) {
      partial.delete();
      offset = 0;
    }
    HttpURLConnection conn = (HttpURLConnection) new URL(item.getString("url")).openConnection();
    conn.setConnectTimeout(20000);
    conn.setReadTimeout(30000);
    if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
    int code = conn.getResponseCode();
    if (code != 200 && code != 206) throw new IOException("Download HTTP " + code);
    if (code == 200) offset = 0;
    try (InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(partial, offset > 0)) {
      byte[] buf = new byte[131072];
      int n;
      long done = offset, last = 0;
      while ((n = in.read(buf)) != -1) {
        if (cancelled) throw new InterruptedException();
        out.write(buf, 0, n);
        done += n;
        if (done > size) throw new IOException("Oversized model");
        if (System.currentTimeMillis() - last > 1200) {
          last = System.currentTimeMillis();
          progress = "模型 " + (index + 1) + "/2 · " + (done * 100 / size) + "%";
          getSystemService(NotificationManager.class)
              .notify(90, Notify.make(this, "models", "正在安装本地模型", progress));
          MealApp.changed(this);
        }
      }
    } finally {
      conn.disconnect();
    }
    if (partial.length() != size || !ModelFiles.sha(partial).equals(item.getString("sha256"))) {
      partial.delete();
      throw new IOException("Checksum mismatch");
    }
    if (target.exists() && !target.delete()) throw new IOException("Replace failed");
    if (!partial.renameTo(target)) throw new IOException("Rename failed");
  }

  @Override
  public void onDestroy() {
    cancelled = true;
    super.onDestroy();
  }

  @Override
  public void onTimeout(int startId, int type) {
    cancelled = true;
    stopSelf();
  }
}
