package com.example.crosspath;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;

import com.example.crosspath.data.SessionStatus;
import com.example.crosspath.ui.BottomTabs;
import com.example.crosspath.ui.Screen;
import com.example.crosspath.ui.Sc01RegistrationFragment;
import com.example.crosspath.ui.Sc02HomeFragment;
import com.example.crosspath.ui.Sc04EmergencyFragment;
import com.example.crosspath.ui.data.UiData;
import com.example.crosspath.ui.data.UserProfile;
import com.example.crosspath.ui.theme.NavTransitions;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.google.android.material.color.MaterialColors;

/**
 * 単一 Activity。仕様: 詳細設計書 v0.8 第11章
 *
 * 画面（Fragment）は fragment_container の中だけを入れ替える。下部タブバーは
 * Activity 側（bottom_tabs_container）に置いた「ページを切り替えるコンポーネント」であり、
 * ページ遷移アニメーションの影響を受けない（揺れない）うえ、背景は画面全幅に広がる。
 * タブバーのテーマは表示中の画面のテーマ（緊急時モード時はダーク）に合わせて作り直す。
 */
public class MainActivity extends AppCompatActivity {

    private FragmentContainerView fragmentContainer;
    private FrameLayout tabBarContainer;

    /** 現在タブバーとして表示している View（画面テーマが変わったときだけ作り直す）。 */
    private View tabBar;

    /** タブバーに現在適用しているテーマ（ダークなら true）。 */
    private boolean tabBarIsDark;

    /** 現在表示中の画面（タブの選択状態とコンテンツ余白の計算に使う）。 */
    private Screen currentScreen;

    /** 緊急時モード（SessionStatus.canCommunicate）。MainActivity が唯一の保持者。 */
    private boolean emergencyMode;

    /** 起動時の初期画面選定が未完了であることを示すフラグ。 */
    private boolean pendingInitialNavigation;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiData.init(getApplicationContext());
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        fragmentContainer = findViewById(R.id.fragment_container);
        tabBarContainer = findViewById(R.id.bottom_tabs_container);

        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            insetLeft = systemBars.left;
            insetTop = systemBars.top;
            insetRight = systemBars.right;
            insetBottom = systemBars.bottom;
            updateContentPadding();
            return insets;
        });

        if (savedInstanceState == null) {
            // 初回起動: セッション状態が確定するまで初期画面は決定しない
            pendingInitialNavigation = true;
            UiData.checkSession(this::onSessionStatus, error -> {});
            // 1200ms フォールバック — セッション状態が返らない場合の安全策
            mainHandler.postDelayed(fallbackRunnable, 1200);
        }
    }

    /** フォールバック: セッション状態が返らない場合、未登録なら SC01、登録済みなら SC02 で初期画面を出す。 */
    private final Runnable fallbackRunnable = () -> {
        if (!pendingInitialNavigation) return;
        emergencyMode = false;
        showInitialScreen();
    };

    @Override
    protected void onResume() {
        super.onResume();
        UiData.checkSession(this::onSessionStatus, error -> {});
    }

    /**
     * 緊急時モードの状態を Activity 外部（フラグメント）に公開する。
     */
    public boolean isEmergencyMode() {
        return emergencyMode;
    }

    /**
     * セッション状態が変化したときのコールバック。フラグメントからも呼ばれる。
     * 緊急時モードの変化に応じてテーマ再適用・画面遷移・Fragment 再生成を行う。
     */
    public void onSessionStatus(@Nullable SessionStatus status) {
        boolean active = status != null && status.canCommunicate;

        if (pendingInitialNavigation) {
            emergencyMode = active;
            showInitialScreen();
            return;
        }

        if (active == emergencyMode) return;
        if (currentScreen == null) return;

        boolean wasEmergency = emergencyMode;
        emergencyMode = active;

        applyScreenTheme(currentScreen);

        Screen target = currentScreen;
        if (active && currentScreen == Screen.SC02) {
            target = Screen.SC04;
        } else if (!active && currentScreen == Screen.SC04) {
            target = Screen.SC02;
        }

        if (target != currentScreen) {
            replaceTo(target, true);
        } else if (ScreenThemes.isEmergencyVariant(currentScreen, active)
                != ScreenThemes.isEmergencyVariant(currentScreen, wasEmergency)) {
            recreateCurrentFragment();
        }
    }

    /**
     * 初期画面を決定して表示する。pendingInitialNavigation を解除する。
     */
    private void showInitialScreen() {
        pendingInitialNavigation = false;
        mainHandler.removeCallbacks(fallbackRunnable);

        Fragment initialFragment;
        if (!UserProfile.isRegistered(this)) {
            initialFragment = new Sc01RegistrationFragment();
        } else if (emergencyMode) {
            initialFragment = new Sc04EmergencyFragment();
        } else {
            initialFragment = new Sc02HomeFragment();
        }

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, initialFragment)
                .commit();
    }

    /**
     * 現在の Fragment を新しいテーマで作り直す（SC05/SC06 などテーマが切り替わる画面用）。
     */
    private void recreateCurrentFragment() {
        if (currentScreen == null) return;
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        Fragment next = BottomTabs.newFragmentFor(currentScreen);
        if (current != null && current.getClass() == next.getClass()) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, next)
                    .commit();
        }
    }

    /**
     * 画面遷移（階層遷移アニメーション付き）。
     */
    private void replaceTo(@NonNull Screen target, boolean forward) {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        Fragment next = BottomTabs.newFragmentFor(target);
        if (current != null && current.getClass() == next.getClass()) {
            return; // 二重遷移防止
        }
        if (current != null && currentScreen != null) {
            NavTransitions.hierarchy(current, next, forward);
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, next)
                .commit();
    }

    /**
     * ステータスバーのアイコン色を設定する。
     * 実効テーマがダークのときは明色、それ以外は暗色。
     */
    public void setStatusBarIconStyle(boolean darkTheme) {
        Window window = getWindow();
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(window, window.getDecorView());
        if (darkTheme) {
            controller.setAppearanceLightStatusBars(false);  // 明色アイコン
        } else {
            controller.setAppearanceLightStatusBars(true);   // 暗色アイコン
        }
    }

    /**
     * 画面のテーマに応じて背景色・ステータスバー・下部タブバーを更新する。仕様 §11.13 / §11.14
     *
     * Activity テーマ自体は変更せず、その画面のテーマ Context から色を解決して適用する。
     * 各 Fragment は onViewCreated で自分の画面を渡して呼ぶ（＝画面が切り替わった通知を兼ねる）。
     *
     * @param screen 表示する画面（null の場合は何もしない）
     */
    public void applyScreenTheme(Screen screen) {
        if (screen == null) return;

        currentScreen = screen;

        // 画面のテーマ Context から colorBackground（=colorSurface）を解決
        Context themedContext = new ContextThemeWrapper(this,
                ScreenThemes.themeResFor(screen, emergencyMode));
        TypedValue tv = new TypedValue();
        if (themedContext.getTheme().resolveAttribute(
                android.R.attr.colorBackground, tv, true)) {
            int surfaceColor = tv.data;
            fragmentContainer.setBackgroundColor(surfaceColor);
            getWindow().setBackgroundDrawable(new ColorDrawable(surfaceColor));
        }

        applyTabBar(screen, themedContext);
        updateContentPadding();

        // ステータスバーのアイコン色
        setStatusBarIconStyle(ScreenThemes.isEmergencyVariant(screen, emergencyMode));
    }

    /**
     * 下部タブバーを現在の画面に合わせる。表示しない画面では隠す。
     * タブバーのテーマは画面テーマ（緊急時モードに応じてダーク／ライト）で作るため、
     * 実効テーマが変わったときだけ作り直す。
     */
    private void applyTabBar(@NonNull Screen screen, @NonNull Context themedContext) {
        boolean show = BottomTabs.showsTabs(screen);
        tabBarContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            tabBarContainer.removeAllViews();
            tabBar = null;
            tabBarIsDark = false;
            return;
        }

        boolean effectiveDark = ScreenThemes.isEmergencyVariant(screen, emergencyMode);
        if (tabBar == null || tabBarIsDark != effectiveDark) {
            LayoutInflater inflater = LayoutInflater.from(themedContext);
            tabBarContainer.removeAllViews();
            tabBar = inflater.inflate(R.layout.include_bottom_tabs, tabBarContainer, false);
            tabBarContainer.addView(tabBar);
            tabBarIsDark = effectiveDark;
            // タブバー背景は画面全幅に広がるため、色は画面テーマの surfaceContainer を直接適用する
            tabBarContainer.setBackgroundColor(MaterialColors.getColor(themedContext,
                    com.google.android.material.R.attr.colorSurfaceContainer, 0));
        }

        BottomTabs.bind(tabBar, screen, this::onTabSelected);
    }

    /**
     * コンテンツの余白。タブバーを表示している画面では下余白をタブバーに任せる。
     */
    private void updateContentPadding() {
        boolean showTabs = currentScreen != null && BottomTabs.showsTabs(currentScreen);
        fragmentContainer.setPadding(insetLeft, insetTop, insetRight, showTabs ? 0 : insetBottom);
    }

    /**
     * タブが選ばれたときの画面遷移。タブ間は同階層なので X 軸の遷移を使う。
     */
    private void onTabSelected(@NonNull Screen target) {
        if (target == currentScreen) {
            return;
        }
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        Fragment next = BottomTabs.newFragmentFor(target);
        if (current != null && currentScreen != null) {
            boolean forward = BottomTabs.tabIndex(target) > BottomTabs.tabIndex(currentScreen);
            NavTransitions.tab(current, next, forward);
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, next)
                .commit();
    }
}