package com.example.crosspath.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;

import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.registration.HttpRegistrationApi;
import com.example.crosspath.registration.PrefsRegistrationStore;
import com.example.crosspath.registration.RegistrationError;
import com.example.crosspath.registration.RegistrationException;
import com.example.crosspath.registration.RegistrationRepository;
import com.example.crosspath.ui.data.UserProfile;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.example.crosspath.ui.theme.ViewAnims;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SC01 初回登録画面。仕様: 詳細設計書 v0.8 §11.2 / crosspath-registration-spec.md §3.9
 *
 * 自分の名前を入力し、サーバー経由で個人IDを取得する初回画面。
 * 登録成功後に個人IDカード・コピー・ホームを表示する。
 * ネットワーク通信は IO スレッドで行い、UI更新はメインスレッドに post する。
 */
public class Sc01RegistrationFragment extends Fragment {

    /** 使い回すスレッドプール（ネットワーク通信用）。 */
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();

    private TextInputLayout nameInputLayout;
    private TextInputEditText nameEditText;
    private MaterialButton registerButton;
    private MaterialCardView idCard;
    private TextView idValueText;
    private TextView resultText;
    private MaterialButton copyButton;
    private MaterialButton homeButton;
    private ProgressBar progressBar;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        progressBar = view.findViewById(R.id.sc01_progress_bar);

        // 初期状態: IDカードと関連ボタンは非表示
        idCard.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        homeButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC01);
        }

        registerButton.setOnClickListener(v -> onRegisterClicked());
        copyButton.setOnClickListener(v -> onCopyClicked());
        homeButton.setOnClickListener(v -> onHomeClicked());
    }

    @Override
    public void onDestroyView() {
        // フラグメント破棄時にこの画面宛の保留中の Runnable を削除する
        // この Fragment の Handler から投稿した保留中の Runnable だけを削除する
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    /**
     * 登録済みかどうかに応じて初期状態を設定する。
     * onResume などから呼ばれ、登録済みなら個人IDカードを表示する。
     */
    public void syncRegistrationState() {
        if (!isAdded()) return;
        int pid = UserProfile.personalId(requireContext());
        if (pid > 0) {
            // 登録済み: IDカードを表示（改めてサーバーに問い合わせない）
            idValueText.setText(String.valueOf(pid));
            resultText.setText(R.string.sc01_result_success);
            resultText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_check_circle_24, 0, 0, 0);
            resultText.setVisibility(View.VISIBLE);
            idCard.setVisibility(View.VISIBLE);
            copyButton.setVisibility(View.VISIBLE);
            homeButton.setVisibility(View.VISIBLE);
            ViewAnims.appearOnce(idCard);
            String name = UserProfile.name(requireContext());
            if (!name.isEmpty()) {
                nameEditText.setText(name);
            }
        }
    }

    private void onRegisterClicked() {
        String name = nameEditText.getText() != null ? nameEditText.getText().toString().trim() : "";

        // 空白のみの名前は登録不可（仕様 §11.2）— TextInputLayout#setError で表示
        if (name.isEmpty()) {
            nameInputLayout.setError(getString(R.string.sc01_error_blank_name));
            return;
        }
        nameInputLayout.setError(null);

        // ボタンを無効化、プログレス表示
        registerButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        resultText.setVisibility(View.VISIBLE);
        resultText.setText(R.string.sc01_registering);
        resultText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
        resultText.setTextColor(MaterialColors.getColor(resultText, android.R.attr.textColorSecondary));

        String finalName = name;
        // IO スレッドから requireContext() を呼ばないよう、メインスレッドで application context を確保する
        final Context appContext = requireContext().getApplicationContext();
        IO_EXECUTOR.submit(() -> {
            try {
                RegistrationRepository repo = new RegistrationRepository(
                        new HttpRegistrationApi(),
                        new PrefsRegistrationStore(appContext)
                );
                int userId = repo.register(finalName);

                mainHandler.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    // 成功: 名前保存、ID表示
                    UserProfile.storeName(appContext, finalName);
                    idValueText.setText(String.valueOf(userId));

                    resultText.setText(R.string.sc01_result_success);
                    resultText.setTextColor(MaterialColors.getColor(resultText,
                            com.google.android.material.R.attr.colorPrimary));
                    TextViewCompat.setCompoundDrawableTintList(resultText, ColorStateList.valueOf(
                            MaterialColors.getColor(resultText, com.google.android.material.R.attr.colorPrimary)));
                    resultText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_check_circle_24, 0, 0, 0);

                    idCard.setVisibility(View.VISIBLE);
                    copyButton.setVisibility(View.VISIBLE);
                    homeButton.setVisibility(View.VISIBLE);
                    ViewAnims.appearOnce(idCard);

                    progressBar.setVisibility(View.GONE);
                    registerButton.setEnabled(true);
                });
            } catch (RegistrationException e) {
                mainHandler.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    showError(e);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    showError(new RegistrationException(
                            RegistrationError.NETWORK,
                            e.getMessage(), null, e));
                });
            }
        });
    }

    /**
     * 登録エラーを UI に表示する。
     * 入力した名前はそのまま残し、ボタンを再有効化して再試行を促す。
     */
    private void showError(RegistrationException e) {
        resultText.setVisibility(View.VISIBLE);
        resultText.setText(getErrorMessageResId(e));
        resultText.setTextColor(MaterialColors.getColor(resultText,
                com.google.android.material.R.attr.colorError));
        resultText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_error_24, 0, 0, 0);

        progressBar.setVisibility(View.GONE);
        registerButton.setEnabled(true);
    }

    /**
     * RegistrationException から表示するエラー文言リソースIDを取得する。
     * HTTPコードの分岐を UI に散らかさない。
     */
    private int getErrorMessageResId(RegistrationException e) {
        switch (e.getRegistrationError()) {
            case NETWORK:
                return R.string.sc01_error_network;
            case CONFLICT:
                return R.string.sc01_error_conflict;
            case RETIRED:
                return R.string.sc01_error_retired;
            case ID_SPACE_EXHAUSTED:
                return R.string.sc01_error_exhausted;
            default:
                return R.string.sc01_error_unexpected;
        }
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
        ((NavHost) requireActivity()).navigatePush(Screen.SC02);
    }
}
