package dev.mealtrace.app;

import android.app.job.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScanJob extends JobService {
  private final java.util.Map<Integer, AtomicBoolean> stops =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override
  public boolean onStartJob(JobParameters p) {
    AtomicBoolean stop = new AtomicBoolean();
    stops.put(p.getJobId(), stop);
    MealApp.WORK.submit(
        () -> {
          try {
            Scanner.scan(this, stop);
            if (!stop.get()) Scanner.analyze(this, stop);
            if (!stop.get()) {
              Cleanup.remind(this);
              Reports.notifyDue(this);
            }
          } catch (Exception e) {
            new Prefs(this).p.edit().putString("scan_status", "扫描暂未完成，请打开应用重试").apply();
          } finally {
            MealApp.changed(this);
            new android.os.Handler(getMainLooper())
                .post(
                    () -> {
                      stops.remove(p.getJobId());
                      if (!stop.get()) {
                        if (p.getJobId() == Jobs.CONTENT) Jobs.content(this);
                        else jobFinished(p, false);
                      }
                    });
          }
        });
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters p) {
    AtomicBoolean stop = stops.get(p.getJobId());
    if (stop != null) stop.set(true);
    return true;
  }
}
