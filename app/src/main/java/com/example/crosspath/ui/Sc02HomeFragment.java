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

import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.ui.data.UiData;
import com.example.crosspath.ui.data.UserProfile;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.example.crosspath.ui.theme.ViewAnims;
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        boolean emergency = getActivity() instanceof MainActivity
                && ((MainActivity) getActivity()).isEmergencyMode();
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC02, emergency);
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

        // 個人IDを設定（実データ）
        idValueText.setText(String.valueOf(UserProfile.personalId(requireContext())));

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC02);
        }

        survivalButton.setOnClickListener(v -> onSurvivalClicked());
        copyButton.setOnClickListener(v -> onCopyClicked());
        shareButton.setOnClickListener(v -> onShareClicked());

        // 生存ボタン・補足・IDカードの出現（初回表示のみ。再表示では動かさない）
        if (savedInstanceState == null) {
            ViewAnims.appearOnce(survivalButton, view.findViewById(R.id.sc02_survival_hint), idCard);
        }

    }

    public void refreshIdentity() {
        if (getView() != null) idValueText.setText(String.valueOf(UserProfile.personalId(requireContext())));
    }

    private void onSurvivalClicked() {
        if (!survivalButton.isEnabled()) return;
        survivalButton.setEnabled(false);
        final View owner = requireView();
        UiData.checkSession(status -> {
            if (!isAdded() || getView() != owner) return;
            survivalButton.setEnabled(true);
            Screen target = !UserProfile.isRegistered(requireContext()) ? Screen.SC01
                    : ScreenPolicy.isActivePeriod(status.state) ? Screen.SC04 : Screen.SC03;
            ((NavHost) requireActivity()).navigatePush(target);
        }, error -> {
            if (!isAdded() || getView() != owner) return;
            survivalButton.setEnabled(true);
            Snackbar.make(owner, "期間を確認できません。再試行してください", Snackbar.LENGTH_LONG).show();
        });
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
}