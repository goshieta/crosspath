package com.example.crosspath.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.ui.sample.SampleData;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.google.android.material.button.MaterialButton;
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
    private MaterialButton registerButton;
    private MaterialCardView idCard;
    private TextView idValueText;
    private MaterialButton copyButton;
    private MaterialButton homeButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC01);
        return themedInflater.inflate(R.layout.fragment_sc01_registration, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        nameInputLayout = view.findViewById(R.id.sc01_name_input_layout);
        nameEditText = view.findViewById(R.id.sc01_name_edit_text);
        registerButton = view.findViewById(R.id.sc01_button_register);
        idValueText = view.findViewById(R.id.sc01_id_value);
        idCard = view.findViewById(R.id.sc01_id_card);
        copyButton = view.findViewById(R.id.sc01_button_copy);
        homeButton = view.findViewById(R.id.sc01_button_home);

        // 初期状態: IDカードと関連ボタンは非表示
        idCard.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        homeButton.setVisibility(View.GONE);

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC01);
        }

        registerButton.setOnClickListener(v -> onRegisterClicked());
        copyButton.setOnClickListener(v -> onCopyClicked());
        homeButton.setOnClickListener(v -> onHomeClicked());
    }

    private void onRegisterClicked() {
        String name = nameEditText.getText() != null ? nameEditText.getText().toString().trim() : "";

        // 空白のみの名前は登録不可（仕様 §11.2）— TextInputLayout#setError で表示
        if (name.isEmpty()) {
            nameInputLayout.setError(getString(R.string.sc01_error_blank_name));
            return;
        }
        nameInputLayout.setError(null);

        // TODO(段階2: 登録APIを呼び出し、成功/失敗を判定する)
        // 暫定: 常に成功として SampleData.MY_USER_ID を表示
        idValueText.setText(SampleData.MY_USER_ID);
        idCard.setVisibility(View.VISIBLE);
        copyButton.setVisibility(View.VISIBLE);
        homeButton.setVisibility(View.VISIBLE);
    }

    private void onCopyClicked() {
        String id = idValueText.getText() != null ? idValueText.getText().toString() : "";
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("個人ID", id);
        clipboard.setPrimaryClip(clip);
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