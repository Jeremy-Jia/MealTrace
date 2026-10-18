package dev.mealtrace.app;

import android.app.*;
import android.content.*;

final class Notify {
  static void channels(Context c) {
    NotificationManager n = c.getSystemService(NotificationManager.class);
    n.createNotificationChannel(
        new NotificationChannel("meals", "餐食与汇总", NotificationManager.IMPORTANCE_DEFAULT));
    n.createNotificationChannel(
        new NotificationChannel("models", "本地模型安装", NotificationManager.IMPORTANCE_LOW));
  }

  static Notification make(Context c, String channel, String title, String text) {
    return new Notification.Builder(c, channel)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(new Notification.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(
                c,
                0,
                new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
        .build();
  }

  static boolean post(Context c, int id, String title, String text) {
    try {
      NotificationManager n = c.getSystemService(NotificationManager.class);
      if (!n.areNotificationsEnabled()) return false;
      n.notify(id, make(c, "meals", title, text));
      return true;
    } catch (SecurityException ignored) {
      return false;
    }
  }
}
