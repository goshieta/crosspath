package com.example.crosspath.ui;

import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.example.crosspath.R;
import com.example.crosspath.ui.sample.SampleData;
import com.google.android.material.navigation.NavigationBarView;

/**
 * 下部タブバー（ホーム／通知対象者／通知画面）の表示制御。仕様: 詳細設計書 v0.8 §11.1 / §11.13
 *
 * タブバーはページ（Fragment）の中ではなく Activity 側（activity_main.xml の
 * bottom_tabs_container）に置く。ここでは「現在の画面に応じた選択状態とアイコン」と
 * 「タップされたときの遷移先の決定」だけを扱い、実際の画面遷移は呼び出し側（MainActivity）が行う。
 */
public final class BottomTabs {

    /** タブが選ばれたときに呼ばれる（遷移先の解決まで済んだ Screen を渡す）。 */
    public interface OnTabSelected {
        void onTabSelected(@NonNull Screen target);
    }

    private static final int TAB_HOME = R.id.tab_home;
    private static final int TAB_WATCH_TARGET = R.id.tab_watch_target;
    private static final int TAB_NOTIFICATIONS = R.id.tab_notifications;

    /** タブバーを表示する画面かどうか（登録フローの SC01・SC03 では表示しない）。 */
    public static boolean showsTabs(@NonNull Screen screen) {
        return screen == Screen.SC02 || screen == Screen.SC04
                || screen == Screen.SC05 || screen == Screen.SC06;
    }

    /** タブの並び順（タブ間の遷移方向を決めるために使う）。 */
    public static int tabIndex(@NonNull Screen screen) {
        if (screen == Screen.SC05) {
            return 1;
        }
        if (screen == Screen.SC06) {
            return 2;
        }
        return 0;
    }

    /**
     * タブバーの選択状態を現在の画面に合わせ、タップ時の通知先を設定する。
     *
     * @param tabBarRoot inflate 済みのタブバー（include_bottom_tabs.xml のルート）
     * @param current    現在表示中の画面
     * @param listener   タブが選ばれたときの処理（MainActivity が画面遷移を行う）
     */
    public static void bind(@NonNull View tabBarRoot, @NonNull Screen current,
                            @NonNull OnTabSelected listener) {
        NavigationBarView navBar = findNavBar(tabBarRoot);
        if (navBar == null) {
            return;
        }
        final boolean[] binding = {true};
        navBar.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                if (binding[0]) {
                    // 初期化中の setSelectedItemId による発火。遷移しない。
                    return true;
                }
                applySelectionIcons(navBar, item.getItemId());
                Screen target = resolveTarget(item.getItemId());
                if (target != current) {
                    listener.onTabSelected(target);
                }
                return true;
            }
        });
        int selectedId = selectedIdFor(current);
        navBar.setSelectedItemId(selectedId);
        binding[0] = false;
        applySelectionIcons(navBar, selectedId);
    }

    /** メニュー項目に対応する遷移先。ホームタブのみタイマー状態で分岐する（§11.1）。 */
    @NonNull
    public static Screen resolveTarget(int menuItemId) {
        if (menuItemId == TAB_WATCH_TARGET) {
            return Screen.SC05;
        }
        if (menuItemId == TAB_NOTIFICATIONS) {
            return Screen.SC06;
        }
        // TODO(段階5: ACTIVE 期間の実データ判定に置換)
        return SampleData.HAS_ACTIVE_SESSION ? Screen.SC04 : Screen.SC02;
    }

    /** 画面に対応する Fragment を生成する。 */
    @NonNull
    public static Fragment newFragmentFor(@NonNull Screen screen) {
        if (screen == Screen.SC04) {
            return new Sc04EmergencyFragment();
        }
        if (screen == Screen.SC05) {
            return new Sc05WatchTargetFragment();
        }
        if (screen == Screen.SC06) {
            return new Sc06NotificationHistoryFragment();
        }
        // SC02 を含む未知の Screen はホームにフォールバック。
        return new Sc02HomeFragment();
    }

    /** タブバーの実体（NavigationBarView）を探す。 */
    private static NavigationBarView findNavBar(View root) {
        if (root instanceof NavigationBarView) {
            return (NavigationBarView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                NavigationBarView found = findNavBar(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void applySelectionIcons(NavigationBarView navBar, int selectedId) {
        setIcon(navBar, TAB_HOME,
                selectedId == TAB_HOME ? R.drawable.ic_home_fill_24 : R.drawable.ic_home_24);
        setIcon(navBar, TAB_WATCH_TARGET,
                selectedId == TAB_WATCH_TARGET ? R.drawable.ic_group_fill_24 : R.drawable.ic_group_24);
        setIcon(navBar, TAB_NOTIFICATIONS,
                selectedId == TAB_NOTIFICATIONS
                        ? R.drawable.ic_notifications_fill_24 : R.drawable.ic_notifications_24);
    }

    private static void setIcon(NavigationBarView navBar, int menuItemId, int iconResId) {
        MenuItem item = navBar.getMenu().findItem(menuItemId);
        if (item != null) {
            item.setIcon(iconResId);
        }
    }

    private static int selectedIdFor(Screen screen) {
        if (screen == Screen.SC05) {
            return TAB_WATCH_TARGET;
        }
        if (screen == Screen.SC06) {
            return TAB_NOTIFICATIONS;
        }
        return TAB_HOME;
    }

    private BottomTabs() { }
}
