package com.example.crosspath.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.crosspath.R;
import com.example.crosspath.ui.sample.SampleData;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;

/**
 * SC02 ホーム画面。仕様: 詳細設計書 v0.8 §11.3
 *
 * 生存情報入力・個人ID表示・コピー/共有・通知対象者/通知履歴への入口。
 */
public class Sc02HomeFragment extends Fragment {

    private Button survivalButton;
    private MaterialCardView idCard;
    private TextView idValueText;
    private Button copyButton;
    private Button shareButton;
    private Button watchTargetButton;
    private Button notificationHistoryButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC02);
        return themedInflater.inflate(R.layout.fragment_sc02_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        survivalButton = view.findViewById(R.id.sc02_button_survival);
        idCard = view.findViewById(R.id.sc02_id_card);
        idValueText = view.findViewById(R.id.sc02_id_value);
        copyButton = view.findViewById(R.id.sc02_button_copy);
        shareButton = view.findViewById(R.id.sc02_button_share);
        watchTargetButton = view.findViewById(R.id.sc02_button_watch_target);
        notificationHistoryButton = view.findViewById(R.id.sc02_button_notification_history);

        // 個人IDを設定
        // TODO(段階2: 登録APIの返値に置換)
        idValueText.setText(SampleData.MY_USER_ID);

        survivalButton.setOnClickListener(v -> onSurvivalClicked());
        copyButton.setOnClickListener(v -> onCopyClicked());
        shareButton.setOnClickListener(v -> onShareClicked());
        watchTargetButton.setOnClickListener(v -> onWatchTargetClicked());
        notificationHistoryButton.setOnClickListener(v -> onNotificationHistoryClicked());
    }

    private void onSurvivalClicked() {
        // 仕様 §11.1: SC02 → 生存ボタン → SC03（タイマー未開始）
        // TODO(段階2: ACTIVE 期間有無を確認し、あれば SC04 へ誘導)
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new Sc03MunicipalityFragment())
                .addToBackStack(null)
                .commit();
    }

    private void onCopyClicked() {
        String id = idValueText.getText() != null ? idValueText.getText().toString() : "";
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("個人ID", id);
        clipboard.setPrimaryClip(clip);
        Snackbar.make(requireView(), R.string.sc01_toast_copied, Snackbar.LENGTH_SHORT).show();
    }

    private void onShareClicked() {
        // Android の共有画面 (ACTION_SEND) — OS の UI 機能なので実装してよい
        String id = idValueText.getText() != null ? idValueText.getText().toString() : "";
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, id);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share)));
    }

    private void onWatchTargetClicked() {
        // TODO(段階7): SC05 通知対象者管理画面へ遷移
        Snackbar.make(requireView(), "TODO(段階7): SC05 通知対象者管理画面へ遷移", Snackbar.LENGTH_SHORT).show();
    }

    private void onNotificationHistoryClicked() {
        // TODO(段階8): SC06 通知履歴画面へ遷移
        Snackbar.make(requireView(), "TODO(段階8): SC06 通知履歴画面へ遷移", Snackbar.LENGTH_SHORT).show();
    }
}