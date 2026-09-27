package com.example.crosspath.notification;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.data.NotificationSink;
import com.example.crosspath.data.SafetyRepository;
import java.util.concurrent.CompletableFuture;

public final class NotificationDispatcher implements NotificationSink {
    public static final String ACTION_HISTORY = "com.example.crosspath.SHOW_HISTORY";
    public static final String HISTORY_ID = "history_id";
    public static final String CHANNEL = "safety_receipts";
    private static final String PREFIX = "history:";
    private final Context context;
    private final NotificationManager manager;

    public NotificationDispatcher(Context context) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "生存情報の受信", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override public Delivery post(SafetyRepository.NotificationRequest request) {
        if ((Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                || !manager.areNotificationsEnabled()
                || manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return Delivery.BLOCKED_PERMISSION;
        }
        Intent intent = new Intent(context, MainActivity.class).setAction(ACTION_HISTORY)
                .setData(Uri.parse("crosspath://history/" + request.notificationId))
                .putExtra(HISTORY_ID, request.notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            manager.notify(PREFIX + request.notificationId, 0, new NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notifications_24)
                    .setContentTitle(request.displayName + "さんの情報を受信")
                    .setContentText("登録地点: " + request.municipalityName + "（現在地・安全の保証ではありません）")
                    .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).build());
            return Delivery.POSTED;
        } catch (SecurityException denied) {
            return Delivery.BLOCKED_PERMISSION;
        }
    }

    /** Inspect OS keys, so cancellation also recovers after a crash following history deletion. */
    public CompletableFuture<Void> reconcile(SafetyRepository repository) {
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
        for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
            String tag = item.getTag();
            if (tag == null || !tag.startsWith(PREFIX)) continue;
            long id;
            try { id = Long.parseLong(tag.substring(PREFIX.length())); }
            catch (NumberFormatException invalid) { continue; }
            result = result.thenCompose(ignored -> repository.isHistoryValid(id).thenAccept(valid -> {
                if (!valid) manager.cancel(tag, item.getId());
            }));
        }
        return result;
    }
}
