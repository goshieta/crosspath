package com.example.crosspath.ui.theme;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;

import com.example.crosspath.R;
import com.example.crosspath.ui.Screen;

/**
 * 画面別テーマ適用ヘルパ。仕様: 詳細設計書 v0.8 §11.13
 *
 * 72時間タイマー作動中は全画面をダーク表示する。SC04 は常にダーク。
 * ContextThemeWrapper でラップした LayoutInflater を Fragment に提供する。
 */
public class ScreenThemes {

    private ScreenThemes() {
        // インスタンス化禁止
    }

    /**
     * 画面に対応するテーマの StyleRes を返す（緊急時モード非考慮）。
     */
    @StyleRes
    public static int themeResFor(@NonNull Screen screen) {
        return themeResFor(screen, false);
    }

    /**
     * 緊急時モード（タイマー作動中）を考慮したテーマ解決。
     */
    @StyleRes
    public static int themeResFor(@NonNull Screen screen, boolean emergency) {
        if (isEmergencyVariant(screen, emergency)) {
            return R.style.Theme_Survival_EmergencyDark;
        }
        return R.style.Theme_Survival_Light;
    }

    /**
     * その画面が緊急時ダークで表示されるか。
     * タイマー作動中は全画面で true。非作動時も SC04 は専用ダーク表示。
     */
    public static boolean isEmergencyVariant(@NonNull Screen screen, boolean emergency) {
        return emergency || screen == Screen.SC04;
    }

    /**
     * 画面のテーマでラップした LayoutInflater を返す（緊急時モード非考慮）。
     * Fragment はこのインスタンスで inflate することでテーマを画面単位で固定する。
     */
    @NonNull
    public static LayoutInflater themedLayoutInflater(@NonNull LayoutInflater inflater,
                                                       @NonNull Screen screen) {
        return themedLayoutInflater(inflater, screen, false);
    }

    /**
     * 緊急時モードを考慮した LayoutInflater のラップ。
     */
    @NonNull
    public static LayoutInflater themedLayoutInflater(@NonNull LayoutInflater inflater,
                                                       @NonNull Screen screen, boolean emergency) {
        Context themedContext = new ContextThemeWrapper(inflater.getContext(), themeResFor(screen, emergency));
        return inflater.cloneInContext(themedContext);
    }
}