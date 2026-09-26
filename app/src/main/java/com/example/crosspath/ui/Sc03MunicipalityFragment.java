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
import com.example.crosspath.ui.sample.SampleData;
import com.example.crosspath.ui.theme.NavTransitions;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

/**
 * SC03 市町村選択画面。仕様: 詳細設計書 v0.8 §11.4
 *
 * 県→市町村の Exposed Dropdown Menu で生存地点を選択し、確定でタイマー・通信を開始する。
 * 確定前はタイマー未開始。確定→SC04 は TODO（C6 で実装）。
 */
public class Sc03MunicipalityFragment extends Fragment {

    private TextInputLayout prefectureLayout;
    private MaterialAutoCompleteTextView prefectureDropdown;
    private TextInputLayout municipalityLayout;
    private MaterialAutoCompleteTextView municipalityDropdown;
    private Button confirmButton;
    private TextView errorText;

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

        // 県ドロップダウン設定
        String[] prefectureNames = new String[SampleData.PREFECTURES.size()];
        for (int i = 0; i < SampleData.PREFECTURES.size(); i++) {
            prefectureNames[i] = SampleData.PREFECTURES.get(i).name;
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
        municipalityLayout.setEnabled(true);
        municipalityLayout.setVisibility(View.VISIBLE);

        // 選択された県の市町村リストを設定
        SampleData.SamplePrefecture selected = SampleData.PREFECTURES.get(position);
        String[] municipalityNames = selected.municipalities.toArray(new String[0]);
        ArrayAdapter<String> municipalityAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, municipalityNames);
        municipalityDropdown.setAdapter(municipalityAdapter);

        // 市町村未選択なので確定を無効に
        confirmButton.setEnabled(false);
        errorText.setVisibility(View.GONE);
    }

    private void onMunicipalitySelected(int position) {
        // 市町村選択後、確定ボタンを有効化
        confirmButton.setEnabled(true);
        errorText.setVisibility(View.GONE);
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

        // 仕様 §11.1: 市町村確定 → タイマー・通信開始 → SC04
        // TODO(段階6): タイマー・通信開始処理
        Sc04EmergencyFragment target = new Sc04EmergencyFragment();
        NavTransitions.hierarchy(this, target, true);
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, target)
                .addToBackStack(null)
                .commit();
    }
    // 注: SC03 から確定前の戻り（→SC02）はシステムの戻る（back stack の pop）で行われ、
    // SC02→SC03 時に設定した return/reenter 遷移が Z 軸の逆方向で自動再生される。
}