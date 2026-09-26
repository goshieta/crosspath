package com.example.crosspath.ui;

import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.example.crosspath.R;
import com.example.crosspath.ui.sample.SampleData;
import com.google.android.material.navigation.NavigationBarView;

/**
 * 下部タブバー（NavigationBarView）の初期化とビヘイビア。
 * ホーム・通知対象者・通知という 3 タブで画面遷移を提供する。
 */
public final class BottomTabs {

    private static final int TAB_FOCUSED_HOME = R.id.tab_home;
    private static final int TAB_FOCUSED_WATCH_TARGET = R.id.tab_watch_target;
    private static final int TAB_FOCUSED_NOTIFICATIONS = R.id.tab_notifications;

    public static void bind(@NonNull View rootView, @NonNull Fragment fragment, @NonNull Screen current) {
        View tabBarView = rootView.findViewById(R.id.bottom_tabs);
        if (!(tabBarView instanceof NavigationBarView)) {
            // 将来 SC 以外で include されない場合の安全策。
            return;
        }
        NavigationBarView navBar = (NavigationBarView) tabBarView;
        final boolean[] binding = { true };
        navBar.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                if (binding[0]) {
                    // 初期化中の setSelectedItemId による発火。ナビゲーションしない。
                    return true;
                }
                Screen target = screenFor(item.getItemId());
                applySelectionIcons(navBar, item.getItemId());
                if (target == current) {
                    // 現在表示中の画面と同じタブなら遷移しない。
                    return true;
                }
                fragment.getParentFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, newFragmentFor(target))
                        .commit();
                return true;
            }
        });
        navBar.setSelectedItemId(selectedIdFor(current));
        binding[0] = false;
        applySelectionIcons(navBar, selectedIdFor(current));
    }

    private static void applySelectionIcons(NavigationBarView navBar, int selectedId) {
        setIcon(navBar, TAB_FOCUSED_HOME,
                selectedId == TAB_FOCUSED_HOME ? R.drawable.ic_home_fill_24 : R.drawable.ic_home_24);
        setIcon(navBar, TAB_FOCUSED_WATCH_TARGET,
                selectedId == TAB_FOCUSED_WATCH_TARGET ? R.drawable.ic_group_fill_24 : R.drawable.ic_group_24);
        setIcon(navBar, TAB_FOCUSED_NOTIFICATIONS,
                selectedId == TAB_FOCUSED_NOTIFICATIONS ? R.drawable.ic_notifications_fill_24 : R.drawable.ic_notifications_24);
    }

    private static void setIcon(NavigationBarView navBar, int menuItemId, int iconResId) {
        MenuItem item = navBar.getMenu().findItem(menuItemId);
        if (item != null) {
            item.setIcon(iconResId);
        }
    }

    private static Screen screenFor(int menuItemId) {
        if (menuItemId == TAB_FOCUSED_WATCH_TARGET) {
            return Screen.SC05;
        }
        if (menuItemId == TAB_FOCUSED_NOTIFICATIONS) {
            return Screen.SC06;
        }
        // TODO(段階2: ACTIVE 期間の実データ判定に置換)
        return SampleData.HAS_ACTIVE_SESSION ? Screen.SC04 : Screen.SC02;
    }

    private static int selectedIdFor(Screen screen) {
        if (screen == Screen.SC05) {
            return TAB_FOCUSED_WATCH_TARGET;
        }
        if (screen == Screen.SC06) {
            return TAB_FOCUSED_NOTIFICATIONS;
        }
        return TAB_FOCUSED_HOME;
    }

    private static Fragment newFragmentFor(Screen screen) {
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

    private BottomTabs() { }
}