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
 * SC04 のみ Theme.Survival.EmergencyDark、それ以外は Theme.Survival.Light を返す。
 * ContextThemeWrapper でラップした LayoutInflater を Fragment に提供する。
 */
public class ScreenThemes {

    private ScreenThemes() {
        // インスタンス化禁止
    }

    /**
     * 画面に対応するテーマの StyleRes を返す。
     */
    @StyleRes
    public static int themeResFor(@NonNull Screen screen) {
        if (screen.theme == Screen.ThemeVariant.DARK) {
            return R.style.Theme_Survival_EmergencyDark;
        }
        return R.style.Theme_Survival_Light;
    }

    /**
     * 画面のテーマでラップした LayoutInflater を返す。
     * Fragment はこのインスタンスで inflate することでテーマを画面単位で固定する。
     */
    @NonNull
    public static LayoutInflater themedLayoutInflater(@NonNull LayoutInflater inflater,
                                                       @NonNull Screen screen) {
        Context themedContext = new ContextThemeWrapper(inflater.getContext(), themeResFor(screen));
        return inflater.cloneInContext(themedContext);
    }
}