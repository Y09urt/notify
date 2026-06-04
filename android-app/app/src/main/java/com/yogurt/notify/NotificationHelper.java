package com.yogurt.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class NotificationHelper {
    public static final String CHANNEL_ID = "notify_messages";

    private NotificationHelper() {
    }

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "消息通知",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    @SuppressWarnings("deprecation")
    public static void show(Context context, NotifyMessage message) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra("message_id", message.id);
        intent.putExtra("message_title", message.title);
        intent.putExtra("message_body", message.body);
        intent.putExtra("message_data", message.data);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                message.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new android.app.Notification.Builder(context, CHANNEL_ID)
                : new android.app.Notification.Builder(context);

        android.app.Notification notification = builder
                .setContentTitle(message.title)
                .setContentText(message.body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(message.id.hashCode(), notification);
        }
    }
}

