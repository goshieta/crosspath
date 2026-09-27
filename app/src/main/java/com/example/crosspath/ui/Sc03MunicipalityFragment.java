package com.example.crosspath.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.data.KyushuMunicipalities;
import com.example.crosspath.ui.data.UiData;
import com.example.crosspath.ui.data.UserProfile;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

/**
 * SC03 市町村選択画面。仕様: 詳細設計書 v0.8 §11.4
 *
 * 県→市町村の Exposed Dropdown Menu で生存地点を選択し、確定でタイマー・通信を開始する。
 * 確定前はタイマー未開始。
 */
public class Sc03MunicipalityFragment extends Fragment {

    private TextInputLayout prefectureLayout;
    private MaterialAutoCompleteTextView prefectureDropdown;
    private TextInputLayout municipalityLayout;
    private MaterialAutoCompleteTextView municipalityDropdown;
    private Button confirmButton;
    private TextView errorText;

    private List<KyushuMunicipalities.Prefecture> prefectures;
    private int selectedMunicipalityCode = -1;
    private boolean saving;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        boolean emergency = getActivity() instanceof MainActivity
                && ((MainActivity) getActivity()).isEmergencyMode();
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC03, emergency);
        return themedInflater.inflate(R.layout.fragment_sc03_municipality, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefectureLayout = view.findViewById(R.id.sc03_prefecture_layout);
        prefectureDropdown = view.findViewById(R.id.sc03_prefecture_dropdown);
        municipalityLayout = view.findViewById(R.id.sc03_municipality_layout);
        municipalityDropdown = view.findViewById(R.id.sc03_municipality_dropdown);
        confirmButton = view.findViewById(R.id.sc03_button_confirm);
        errorText = view.findViewById(R.id.sc03_error_text);

        // 自治体マスターデータを取得
        prefectures = KyushuMunicipalities.prefectures();

        // 県ドロップダウン設定
        String[] prefectureNames = new String[prefectures.size()];
        for (int i = 0; i < prefectures.size(); i++) {
            prefectureNames[i] = prefectures.get(i).name;
        }
        ArrayAdapter<String> prefectureAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, prefectureNames);
        prefectureDropdown.setAdapter(prefectureAdapter);
        prefectureDropdown.setOnItemClickListener((parent, v, position, id) -> onPrefectureSelected(position));

        // 市町村ドロップダウンは初期状態で無効
        municipalityDropdown.setOnItemClickListener((parent, v, position, id) -> onMunicipalitySelected(position));

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC03);
        }

        confirmButton.setEnabled(false);
        prefectureDropdown.setSaveEnabled(false);
        municipalityDropdown.setSaveEnabled(false);
        if (savedInstanceState != null) {
            int code = savedInstanceState.getInt("municipality", -1);
            for (int i = 0; i < prefectures.size(); i++) {
                for (KyushuMunicipalities.Entry entry : prefectures.get(i).municipalities) {
                    if (entry.code == code) {
                        prefectureDropdown.setText(prefectures.get(i).name, false);
                        onPrefectureSelected(i);
                        municipalityDropdown.setText(entry.name, false);
                        selectedMunicipalityCode = code;
                        confirmButton.setEnabled(true);
                    }
                }
            }
        }
        confirmButton.setOnClickListener(v -> onConfirmClicked());
    }

    private void onPrefectureSelected(int position) {
        if (saving) return;
        // 県を変更したら市町村の選択を解除（仕様 §11.4）
        municipalityDropdown.setText("");
        selectedMunicipalityCode = -1;
        municipalityLayout.setEnabled(true);
        municipalityLayout.setVisibility(View.VISIBLE);

        // 選択された県の市町村リストを設定
        KyushuMunicipalities.Prefecture selected = prefectures.get(position);
        String[] municipalityNames = new String[selected.municipalities.size()];
        for (int i = 0; i < selected.municipalities.size(); i++) {
            municipalityNames[i] = selected.municipalities.get(i).name;
        }
        ArrayAdapter<String> municipalityAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, municipalityNames);
        municipalityDropdown.setAdapter(municipalityAdapter);

        // 市町村未選択なので確定を無効に
        confirmButton.setEnabled(false);
        errorText.setVisibility(View.GONE);
    }

    private void onMunicipalitySelected(int position) {
        if (saving) return;
        // 選択された市町村のコードを保持
        int prefectureIndex = findPrefectureIndex(prefectureDropdown.getText().toString());
        if (prefectureIndex < 0) return;
        KyushuMunicipalities.Prefecture selected = prefectures.get(prefectureIndex);
        KyushuMunicipalities.Entry entry = selected.municipalities.get(position);
        selectedMunicipalityCode = entry.code;

        // 市町村選択後、確定ボタンを有効化
        confirmButton.setEnabled(true);
        errorText.setVisibility(View.GONE);
    }

    private int findPrefectureIndex(String name) {
        for (int i = 0; i < prefectures.size(); i++) {
            if (prefectures.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("municipality", selectedMunicipalityCode);
    }

    private void onConfirmClicked() {
        if (saving || selectedMunicipalityCode < 1) return;
        int userId = UserProfile.personalId(requireContext());
        if (userId == 0) {
            ((NavHost) requireActivity()).navigatePush(Screen.SC01);
            return;
        }
        final int code = selectedMunicipalityCode;
        final com.example.crosspath.data.ConfirmationClock.Token confirmed = UiData.confirmationTime();
        final View owner = requireView();
        saving = true;
        confirmButton.setEnabled(false);
        prefectureLayout.setEnabled(false);
        municipalityLayout.setEnabled(false);
        ((MainActivity) requireActivity()).setConfirmationSaving(true);
        UiData.onResult(UiData.execute(repo -> repo.startSessionAtConfirmation(userId, code, confirmed)), sessionId -> {
            saving = false;
            UiData.refreshTimerState();
            if (!isAdded() || getView() != owner) return;
            ((MainActivity) requireActivity()).setConfirmationSaving(false);
            // Activity resolves the committed period, including a rotation during save.
            UiData.checkSession(status -> {
                if (isAdded() && getView() == owner) ((MainActivity) requireActivity()).onSessionStatus(status);
            }, error -> showFailure(owner));
        }, error -> {
            saving = false;
            showFailure(owner);
        });
    }

    private void showFailure(View owner) {
        if (!isAdded() || getView() != owner) return;
        ((MainActivity) requireActivity()).setConfirmationSaving(false);
        errorText.setText(R.string.sc03_error_start_failed);
        errorText.setVisibility(View.VISIBLE);
        confirmButton.setEnabled(true);
        prefectureLayout.setEnabled(true);
        municipalityLayout.setEnabled(true);
    }
}
