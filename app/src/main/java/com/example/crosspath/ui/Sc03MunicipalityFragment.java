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
import com.example.crosspath.ui.theme.NavTransitions;
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC03);
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

        confirmButton.setOnClickListener(v -> onConfirmClicked());
    }

    private void onPrefectureSelected(int position) {
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

    private void onConfirmClicked() {
        String prefecture = prefectureDropdown.getText() != null
                ? prefectureDropdown.getText().toString() : "";
        String municipality = municipalityDropdown.getText() != null
                ? municipalityDropdown.getText().toString() : "";

        if (prefecture.isEmpty() || municipality.isEmpty()) {
            errorText.setText("県と市町村を選択してください");
            errorText.setVisibility(View.VISIBLE);
            return;
        }

        // 既存の「県と市町村を選択してください」は未選択時のみ
        if (selectedMunicipalityCode < 1) return;

        // 処理中は確定ボタンを無効化
        confirmButton.setEnabled(false);

        UiData.whenReady(repo -> UiData.onResult(
                repo.startSession(UserProfile.personalId(requireContext()), selectedMunicipalityCode),
                sessionId -> {
                    UiData.refreshTimerState();
                    if (!isAdded()) {
                        return;
                    }
                    Sc04EmergencyFragment target = new Sc04EmergencyFragment();
                    NavTransitions.hierarchy(this, target, true);
                    getParentFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, target)
                            .addToBackStack(null)
                            .commit();
                },
                error -> {
                    if (!isAdded()) {
                        return;
                    }
                    errorText.setText(R.string.sc03_error_start_failed);
                    errorText.setVisibility(View.VISIBLE);
                    confirmButton.setEnabled(true);
                }
        ));
    }
}