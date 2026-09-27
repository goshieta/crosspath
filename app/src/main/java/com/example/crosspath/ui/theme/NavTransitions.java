package com.example.crosspath.ui.theme;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.transition.MaterialSharedAxis;

/**
 * 画面遷移用の共通ヘルパ。仕様 §11.14。
 *
 * 点滅・ループ・常時のアニメーションは使わず、画面遷移に紐づく1回だけの
 * Material SharedAxis 遷移を提供する。遷移は各 Fragment に散らさずこのヘルパに集約する。
 */
public final class NavTransitions {

    /** タブ切り替え（同階層）: X軸のスライド遷移。 */
    public static void tab(@NonNull Fragment from, @NonNull Fragment to, boolean forward) {
        apply(MaterialSharedAxis.X, from, to, forward);
    }

    /** 階層の深い遷移: Z軸（前後）の遷移。pop（戻り）は forward=false で逆再生される。 */
    public static void hierarchy(@NonNull Fragment from, @NonNull Fragment to, boolean forward) {
        apply(MaterialSharedAxis.Z, from, to, forward);
    }

    /**
     * 遷移元の exit と遷移先の enter を同時に設定する。戻り（pop）で逆再生されるよう
     * return / reenter も同じ軸で設定する。
     *
     * @param now    遷移元 Fragment（null 可。初回表示のときは遷移先のみに enter を設定）
     * @param next   遷移先 Fragment
     * @param forward 並び順で前へ進むなら true
     */
    private static void apply(int axis, @Nullable Fragment now, @NonNull Fragment next, boolean forward) {
        next.setEnterTransition(new MaterialSharedAxis(axis, forward));
        next.setReturnTransition(new MaterialSharedAxis(axis, forward));
        if (now != null) {
            now.setExitTransition(new MaterialSharedAxis(axis, forward));
            now.setReenterTransition(new MaterialSharedAxis(axis, forward));
        }
    }

    private NavTransitions() { }
}