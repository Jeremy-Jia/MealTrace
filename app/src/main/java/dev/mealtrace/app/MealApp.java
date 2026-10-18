package dev.mealtrace.app;

import android.app.*;
import android.content.*;
import java.util.concurrent.*;

public final class MealApp extends Application {
  static final ExecutorService WORK = Executors.newSingleThreadExecutor();

  @Override
  public void onCreate() {
    super.onCreate();
    Notify.channels(this);
  }

  @Override public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (level >= TRIM_MEMORY_RUNNING_LOW) LocalVision.releaseIdle();
  }

  static void changed(Context c) {
    c.sendBroadcast(new Intent("dev.mealtrace.CHANGED").setPackage(c.getPackageName()));
    MealWidget.update(c);
  }
}
