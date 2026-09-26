package com.example.crosspath.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.crosspath.R;
import com.example.crosspath.ui.theme.ScreenThemes;

/**
 * SC02 ホーム画面（暫定スタブ → C4 で完全実装）。仕様: 詳細設計書 v0.8 §11.3
 *
 * C3 でのコンパイル通過用の最小実装。C4 で完全なレイアウトと遷移に置き換える。
 */
public class Sc02HomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC02);
        return themedInflater.inflate(R.layout.fragment_sc02_home, container, false);
    }
}