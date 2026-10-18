package dev.mealtrace.app;

import android.app.job.*;
import android.content.*;
import android.provider.MediaStore;

final class Jobs {
  static final int PERIODIC = 100, CONTENT = 101, ANALYZE = 102;

  static void configure(Context c) {
    JobScheduler j = c.getSystemService(JobScheduler.class);
    if (!new Prefs(c).enabled()) {
      j.cancel(PERIODIC);
      j.cancel(CONTENT);
      j.cancel(ANALYZE);
      return;
    }
    ComponentName service = new ComponentName(c, ScanJob.class);
    if (j.getPendingJob(PERIODIC) == null)
      j.schedule(
          new JobInfo.Builder(PERIODIC, service)
              .setPeriodic(15 * 60 * 1000L)
              .setPersisted(true)
              .setRequiresBatteryNotLow(true)
              .build());
    if (j.getPendingJob(CONTENT) == null) content(c);
  }

  static void content(Context c) {
    if (!new Prefs(c).enabled()) return;
    JobInfo info =
        new JobInfo.Builder(CONTENT, new ComponentName(c, ScanJob.class))
            .addTriggerContentUri(
                new JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .addTriggerContentUri(
                new JobInfo.TriggerContentUri(android.net.Uri.parse("content://media/"), 0))
            .setTriggerContentUpdateDelay(10000)
            .setTriggerContentMaxDelay(60000)
            .build();
    c.getSystemService(JobScheduler.class).schedule(info);
  }

  static void analysis(Context c) {
    c.getSystemService(JobScheduler.class)
        .schedule(
            new JobInfo.Builder(ANALYZE, new ComponentName(c, ScanJob.class))
                .setMinimumLatency(1500)
                .setRequiresBatteryNotLow(true)
                .build());
  }
}
