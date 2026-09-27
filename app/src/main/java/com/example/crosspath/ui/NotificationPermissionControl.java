package com.example.crosspath.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.example.crosspath.ui.data.UiData;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

/** User-driven permission/retry UI. Permission denial never discards a receipt. */
public final class NotificationPermissionControl {
    private final Fragment fragment;
    private final ActivityResultLauncher<String> permission;
    private MaterialButton button;
    private TextView deliveryStatus;

    public NotificationPermissionControl(Fragment fragment) {
        this.fragment = fragment;
        permission = fragment.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (fragment.getView() == null) return;
            if (granted) retry();
            else Snackbar.make(fragment.requireView(), "通知は許可されていません。受信状態は画面で確認できます", Snackbar.LENGTH_LONG)
                    .setAction("設定", view -> openSettings()).show();
        });
    }
    public void attach(ViewGroup parent) {
        TextView explanation = new TextView(fragment.requireContext());
        explanation.setText("〇は今回の受信、ーは未受信です。現在の安全・現在地を保証しません。通知を許可した後は再試行できます。");
        parent.addView(explanation, 1);
        button = new MaterialButton(fragment.requireContext());
        button.setText("通知の許可・再試行");
        parent.addView(button, 2);
        deliveryStatus = new TextView(fragment.requireContext());
        parent.addView(deliveryStatus, 3);
        button.setOnClickListener(view -> {
            android.app.NotificationManager manager = fragment.requireContext().getSystemService(android.app.NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(fragment.requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else if (!manager.areNotificationsEnabled() ||
                    (manager.getNotificationChannel(com.example.crosspath.notification.NotificationDispatcher.CHANNEL) != null
                    && manager.getNotificationChannel(com.example.crosspath.notification.NotificationDispatcher.CHANNEL).getImportance() == android.app.NotificationManager.IMPORTANCE_NONE)) {
                openSettings();
            } else retry();
        });
        refresh();
    }
    private void openSettings() {
        fragment.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, fragment.requireContext().getPackageName()));
    }
    public void refresh() {
        View owner = fragment.getView();
        if (owner == null || deliveryStatus == null) return;
        UiData.onResult(UiData.execute(repo -> repo.notificationDeliveryStatus()), status -> {
            if (fragment.getView() != owner) return;
            deliveryStatus.setText(fragment.getString(com.example.crosspath.R.string.notification_delivery_counts, status.posted, status.pending, status.blocked));
        }, error -> {
            if (fragment.getView() == owner) deliveryStatus.setText("通知の配信状態を読み込めません");
        });
    }
    private void retry() {
        View owner = fragment.getView();
        if (owner == null) return;
        button.setEnabled(false);
        UiData.onResult(UiData.retryNotifications(), count -> {
            if (fragment.getView() != owner) return;
            button.setEnabled(true);
            refresh();
            Snackbar.make(owner, "現在期間の未発行通知を再確認します", Snackbar.LENGTH_SHORT).show();
        }, error -> {
            if (fragment.getView() != owner) return;
            button.setEnabled(true);
            Snackbar.make(owner, "通知を再試行できません。もう一度お試しください", Snackbar.LENGTH_LONG).show();
        });
    }
}
