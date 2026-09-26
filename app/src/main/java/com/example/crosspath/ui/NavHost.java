package com.example.crosspath.ui;

import androidx.annotation.NonNull;

/**
 * 画面遷移の入口を Activity（MainActivity）が実装する interface。
 * フラグメントは遷移先を直接 FragmentTransaction で置き換えるのではなく、
 * この interface 経由で Navigator（遷移の一元化）に委譲する。
 * 仕様: 詳細設計書 v0.8 §11.1 / L3（遷移の一元化）。
 */
public interface NavHost {

    /** 階層前進: SC01→SC02, SC02→SC03, SC03→SC04, SC04→SC02。 */
    void navigatePush(@NonNull Screen target);

    /** 階層後退: SC03→SC02。 */
    void navigateBack(@NonNull Screen target);

    /** タブ移動: SC02 / SC04 / SC05 / SC06。 */
    void navigateTab(@NonNull Screen target);
}