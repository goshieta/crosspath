package com.example.crosspath.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;

import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.ui.data.UserProfile;
import com.example.crosspath.ui.theme.NavTransitions;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.example.crosspath.ui.theme.ViewAnims;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
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
    private TextView resultText;
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
        resultText = view.findViewById(R.id.sc01_result_text);
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

        // 実データとして名前登録 + 個人IDを表示
        UserProfile.register(requireContext(), name);
        idValueText.setText(String.valueOf(UserProfile.personalId(requireContext())));

        // 登録結果表示 仕様 §11.2(4)（成功アイコン付き）
        resultText.setText(R.string.sc01_result_success);
        TextViewCompat.setCompoundDrawableTintList(resultText, ColorStateList.valueOf(
                MaterialColors.getColor(resultText, com.google.android.material.R.attr.colorPrimary)));
        resultText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_check_circle_24, 0, 0, 0);
        resultText.setVisibility(View.VISIBLE);

        idCard.setVisibility(View.VISIBLE);
        copyButton.setVisibility(View.VISIBLE);
        homeButton.setVisibility(View.VISIBLE);
        // IDカードの出現（登録成直後に1回だけ）
        ViewAnims.appearOnce(idCard);
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
        Sc02HomeFragment target = new Sc02HomeFragment();
        NavTransitions.hierarchy(this, target, true);
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, target)
                .addToBackStack(null)
                .commit();
    }
}