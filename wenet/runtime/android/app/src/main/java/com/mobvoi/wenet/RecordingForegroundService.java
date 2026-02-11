package com.mobvoi.wenet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class RecordingForegroundService extends Service {

  public static final String ACTION_START = "com.mobvoi.wenet.ACTION_START_RECORDING";
  public static final String ACTION_STOP = "com.mobvoi.wenet.ACTION_STOP_RECORDING";

  private static final String CHANNEL_ID = "wenet_recording_channel";
  private static final int NOTIFICATION_ID = 1;

  @Override
  public void onCreate() {
    super.onCreate();
    createNotificationChannel();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopForeground(true);
      stopSelf();
      return START_NOT_STICKY;
    }

    startForeground(NOTIFICATION_ID, createNotification());
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
          CHANNEL_ID,
          "Recording",
          NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Shows when audio recording is in progress");
      NotificationManager manager = getSystemService(NotificationManager.class);
      manager.createNotificationChannel(channel);
    }
  }

  private Notification createNotification() {
    Intent notificationIntent = new Intent(this, MainActivity.class);
    notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pendingIntent = PendingIntent.getActivity(
        this, 0, notificationIntent,
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE : 0);

    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("WeNet")
        .setContentText("녹음 중...")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .build();
  }
}
