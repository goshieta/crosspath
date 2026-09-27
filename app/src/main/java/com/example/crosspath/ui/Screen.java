package com.example.crosspath.ui;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.example.crosspath.R;

/**
 * 画面ID定義。仕様: 詳細設計書 v0.8 §11.1
 *
 * SC01=初回登録, SC02=ホーム, SC03=市町村選択,
 * SC04=緊急時画面, SC05=通知対象者管理, SC06=通知履歴
 */
public enum Screen {
    SC01(R.id.sc01, R.string.sc01_title, ThemeVariant.LIGHT),
    SC02(R.id.sc02, R.string.sc02_title, ThemeVariant.LIGHT),
    SC03(R.id.sc03, R.string.sc03_title, ThemeVariant.LIGHT),
    SC04(R.id.sc04, R.string.sc04_title, ThemeVariant.DARK),
    SC05(R.id.sc05, R.string.sc05_title, ThemeVariant.LIGHT),
    SC06(R.id.sc06, R.string.sc06_title, ThemeVariant.LIGHT);

    /** FragmentContainerView に割り当てる画面ID（View#setTag 用） */
    public final int screenId;

    /** 画面タイトルの StringRes ID */
    @StringRes
    public final int titleResId;

    /** テーマ種別 */
    @NonNull
    public final ThemeVariant theme;

    Screen(@IdRes int screenId, @StringRes int titleResId, @NonNull ThemeVariant theme) {
        this.screenId = screenId;
        this.titleResId = titleResId;
        this.theme = theme;
    }

    public enum ThemeVariant {
        LIGHT,
        DARK
    }
}