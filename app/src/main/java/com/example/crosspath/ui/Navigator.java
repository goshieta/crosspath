package com.example.crosspath.ui;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.crosspath.ui.theme.NavTransitions;

/**
 * Fragment の入れ替えを一元管理する遷移コントローラ。仕様: 詳細設計書 v0.8 §11.1 / L3。
 *
 * - 階層は線形フロー（SC01→SC02→SC03→SC04）なので、戻り先を積まない。
 *   そのため {@code addToBackStack()} は一切使わない。
 * - Fragment の入れ替えは {@code replace(containerId, next)} のみ。
 * - {@link #current()}（現在画面）の更新は、実際の Fragment 遷移と同一の swap() 内で行う。
 * - 同じ Fragment クラスが既に表示されている場合は遷移せず、テーマ再適用のみ行う。
 */
public final class Navigator {

    /** 画面テーマを適用する能力（MainActivity が実装）。 */
    public interface ThemeApplier {
        void applyTheme(@NonNull Screen screen);
    }

    private final FragmentManager fragmentManager;
    @IdRes
    private final int containerId;
    private final ThemeApplier applier;

    private Screen current;

    public Navigator(@NonNull FragmentManager fm, @IdRes int containerId,
                     @NonNull ThemeApplier applier) {
        this.fragmentManager = fm;
        this.containerId = containerId;
        this.applier = applier;
    }

    /** 現在表示中の画面。未確定（まだ1度も遷移していない）なら null。 */
    @Nullable
    public void restoreCurrent(Screen screen) { current = screen; }

    public Screen current() {
        return current;
    }

    /**
     * 初期画面をアニメーション無しで表示する。current を更新する。
     * 既に同じ画面が表示されている場合は current 更新とテーマ適用のみ行う。
     */
    public void showInitial(@NonNull Screen target) {
        Fragment existing = fragmentManager.findFragmentById(containerId);
        Fragment next = BottomTabs.newFragmentFor(target);
        current = target;
        applier.applyTheme(target);
        if (existing == null || existing.getClass() != next.getClass()) {
            fragmentManager.beginTransaction()
                    .replace(containerId, next)
                    .commit();
        }
    }

    /** 階層前進（SC01→SC02, SC02→SC03, SC03→SC04, SC04→SC02）。 */
    public void navigatePush(@NonNull Screen target) {
        swap(target, Tran.HIERARCHY, true);
    }

    /** 階層後退（SC03→SC02）。 */
    public void navigateBack(@NonNull Screen target) {
        swap(target, Tran.HIERARCHY, false);
    }

    /** タブ移動（SC02 / SC04 / SC05 / SC06）。 */
    public void navigateTab(@NonNull Screen target) {
        swap(target, Tran.TAB, current != null
                && BottomTabs.tabIndex(target) > BottomTabs.tabIndex(current));
    }

    /**
     * テーマを再適用する。画面は維持したまま、緊急時モードの変化等に応じて
     * テーマ variant を変える必要がある画面は作り直す。同じ画面は遷移しない。
     */
    public void reapplyTheme() {
        if (current == null) return;
        Fragment existing = fragmentManager.findFragmentById(containerId);
        Fragment next = BottomTabs.newFragmentFor(current);
        if (existing != null && existing.getClass() == next.getClass()) {
            // 入力・選択状態を保持してテーマ variant を反映。
            next.setInitialSavedState(fragmentManager.saveFragmentInstanceState(existing));
            fragmentManager.beginTransaction()
                    .replace(containerId, next)
                    .commit();
        }
        applier.applyTheme(current);
    }

    private void swap(@NonNull Screen target, @NonNull Tran tran, boolean forward) {
        // 現在と同一画面なら遷移せずテーマ再適用のみ。
        if (current == target) {
            applier.applyTheme(current);
            return;
        }

        Fragment existing = fragmentManager.findFragmentById(containerId);
        Fragment next = BottomTabs.newFragmentFor(target);

        if (existing != null) {
            if (existing.getClass() == next.getClass()) {
                // 二重遷移防止: 同じクラスなら遷移せず、current 更新とテーマ再適用のみ。
                current = target;
                applier.applyTheme(current);
                return;
            }
            // 実際の遷移と同じ場所で現在画面を更新する。
            if (tran == Tran.TAB) {
                NavTransitions.tab(existing, next, forward);
            } else {
                NavTransitions.hierarchy(existing, next, forward);
            }
        }

        current = target;
        applier.applyTheme(current);
        fragmentManager.beginTransaction()
                .replace(containerId, next)
                .commit();
    }

    private enum Tran {
        HIERARCHY,
        TAB
    }
}