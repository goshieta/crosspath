package com.example.crosspath.ui.theme;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;

/**
 * 画面内要素の出現アニメーション（表示時に1回だけ）。仕様 §11.14。
 *
 * alpha 0→1 と translationY 12dp→0 を 250ms、DecelerateInterpolator で合成する。
 * 複数 View は並び順に 40ms ずつ遅延させる。ループ・点滅はしない。
 */
public final class ViewAnims {

    private static final long DURATION_MS = 250L;
    private static final long STAGGER_MS = 40L;

    /**
     * 指定された View 群を 12dp 下からフェードインで出現させる。
     *
     * @param views 出現させる View。（null は無視する）
     */
    public static void appearOnce(@NonNull View... views) {
        long startDelay = 0L;
        for (View view : views) {
            if (view == null) {
                continue;
            }
            float distance = view.getResources().getDisplayMetrics().density * 12f;
            view.setAlpha(0f);
            view.setTranslationY(distance);
            AnimatorSet set = new AnimatorSet();
            set.setInterpolator(new DecelerateInterpolator());
            set.playTogether(
                    ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, distance, 0f));
            set.setDuration(DURATION_MS);
            set.setStartDelay(startDelay);
            set.start();
            startDelay += STAGGER_MS;
        }
    }

    private ViewAnims() { }
}