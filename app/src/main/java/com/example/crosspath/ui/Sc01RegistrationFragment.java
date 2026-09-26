package com.example.crosspath.ui;

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
import com.example.crosspath.ui.sample.SampleData;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * SC01 初回登録画面。仕様: 詳細設計書 v0.8 §11.2
 *
 * 自分の名前を入力し、登録して個人IDを取得する初回画面。
 * 登録成功後に個人IDカード・コピー・ホームを表示する。
 */
public class Sc01RegistrationFragment extends Fragment {

    private TextInputLayout nameInputLayout;
    private TextInputEditText nameEditText;
    private Button registerButton;
    private TextView resultText;
    private MaterialCardView idCard;
    private TextView idValueText;
    private Button copyButton;
    private Button homeButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // SC01 はライトテーマ（SC04以外は共通ライト）
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC01);
        return themedInflater.inflate(R.layout.fragment_sc01_registration, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        nameInputLayout = view.findViewById(R.id.sc01_name_input_layout);
        nameEditText = view.findViewById(R.id.sc01_name_edit_text);
        registerButton = view.findViewById(R.id.sc01_button_register);
        resultText = view.findViewById(R.id.sc01_result_text);
        idCard = view.findViewById(R.id.sc01_id_card);
        idValueText = view.findViewById(R.id.sc01_id_value);
        copyButton = view.findViewById(R.id.sc01_button_copy);
        homeButton = view.findViewById(R.id.sc01_button_home);

        // 初期状態: IDカードと関連ボタンは非表示
        idCard.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        homeButton.setVisibility(View.GONE);
        resultText.setVisibility(View.GONE);

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC01);
        }

        registerButton.setOnClickListener(v -> onRegisterClicked());
        copyButton.setOnClickListener(v -> onCopyClicked());
        homeButton.setOnClickListener(v -> onHomeClicked());
    }

    private void onRegisterClicked() {
        String name = nameEditText.getText() != null ? nameEditText.getText().toString().trim() : "";

        // 空白のみの名前は登録不可（仕様 §11.2）
        if (name.isEmpty()) {
            nameInputLayout.setError(getString(R.string.sc01_error_blank_name));
            return;
        }
        nameInputLayout.setError(null);

        // TODO(段階2: 登録APIを呼び出し、成功/失敗を判定する)
        // 暫定: 常に成功として SampleData.MY_USER_ID を表示
        registerButton.setEnabled(false);
        registerButton.setText("");

        // 成功表示
        idValueText.setText(SampleData.MY_USER_ID);
        idCard.setVisibility(View.VISIBLE);
        copyButton.setVisibility(View.VISIBLE);
        homeButton.setVisibility(View.VISIBLE);
        resultText.setText(R.string.sc01_register_success);
        resultText.setVisibility(View.VISIBLE);

        registerButton.setEnabled(true);
        registerButton.setText(R.string.action_register);
    }

    private void onCopyClicked() {
        // クリップボードにコピー（OS の UI 機能なので実装してよい: 仕様 §3）
        String id = idValueText.getText() != null ? idValueText.getText().toString() : "";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("個人ID", id);
            clipboard.setPrimaryClip(clip);
        } else {
            android.text.ClipboardManager clipboard =
                    (android.text.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            clipboard.setText(id);
        }
        Snackbar.make(requireView(), R.string.sc01_toast_copied, Snackbar.LENGTH_SHORT).show();
    }

    private void onHomeClicked() {
        // 仕様 §11.1: SC01 → 登録成功 → SC02
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new Sc02HomeFragment())
                .addToBackStack(null)
                .commit();
    }
}