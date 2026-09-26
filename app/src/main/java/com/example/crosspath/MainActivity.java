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
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.FragmentContainerView;

import com.example.crosspath.data.SessionStatus;
import com.example.crosspath.ui.BottomTabs;
import com.example.crosspath.ui.NavHost;
import com.example.crosspath.ui.Navigator;
import com.example.crosspath.ui.Screen;
import com.example.crosspath.ui.ScreenPolicy;
import com.example.crosspath.ui.Sc01RegistrationFragment;
import com.example.crosspath.ui.Sc02HomeFragment;
import com.example.crosspath.ui.Sc04EmergencyFragment;
import com.example.crosspath.ui.data.UiData;
import com.example.crosspath.ui.data.UserProfile;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.google.android.material.color.MaterialColors;

/**
 * 単一 Activity。仕様: 詳細設計書 v0.8 第11章
 *
 * 画面（Fragment）は fragment_container の中だけを入れ替える。下部タブバーは
 * Activity 側（bottom_tabs_container）に置いた「ページを切り替えるコンポーネント」であり、
 * ページ遷移アニメーションの影響を受けない（揺れない）うえ、背景は画面全幅に広がる。
 * タブバーのテーマは表示中の画面のテーマ（緊急時モード時はダーク）に合わせて作り直す。
 *
 * 画面遷移は {@link Navigator} に一元化する。現在画面の唯一の保持者は Navigator であり、
 * この Activity は {@link NavHost} として遷移要求を受け付ける。
 */
public class MainActivity extends AppCompatActivity implements NavHost {

    private FragmentContainerView fragmentContainer;
    private FrameLayout tabBarContainer;

    /** 現在タブバーとして表示している View（画面テーマが変わったときだけ作り直す）。 */
    private View tabBar;

    /** タブバーに現在適用しているテーマ（ダークなら true）。 */
    private boolean tabBarIsDark;

    /** 現在画面の唯一の保持者。画面遷移を一元管理する。 */
    private Navigator navigator;

    /** 緊急時モード（未満了の期間がある）。MainActivity が唯一の保持者。 */
    private boolean emergencyMode;

    /** 起動時の初期画面選定が未完了であることを示すフラグ。 */
    private boolean pendingInitialNavigation;

    /** システムの戻る操作。SC03 のときだけ有効化して SC02 へ戻す。 */
    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            Screen target = ScreenPolicy.backTarget(navigator.current());
            if (target != null) {
                navigator.navigateBack(target);
            }
        }
    };

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

        navigator = new Navigator(getSupportFragmentManager(), R.id.fragment_container, this::applyScreenTheme);
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        backCallback.setEnabled(false);

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

    // ---- NavHost ----

    @Override
    public void navigatePush(@NonNull Screen target) {
        navigator.navigatePush(target);
        updateBackEnabled();
    }

    @Override
    public void navigateBack(@NonNull Screen target) {
        navigator.navigateBack(target);
        updateBackEnabled();
    }

    @Override
    public void navigateTab(@NonNull Screen target) {
        navigator.navigateTab(target);
        updateBackEnabled();
    }

    /** 現在画面に応じて、システム戻るコールバックの有効/無効を更新する。 */
    private void updateBackEnabled() {
        backCallback.setEnabled(ScreenPolicy.backTarget(navigator.current()) != null);
    }

    /**
     * セッション状態が変化したときのコールバック。フラグメントからも呼ばれる。
     * 緊急時モードの変化に応じてテーマ再適用・画面遷移・Fragment 再生成を行う。
     */
    public void onSessionStatus(@Nullable SessionStatus status) {
        SessionStatus.State state = status == null ? null : status.state;
        boolean emergency = ScreenPolicy.emergencyMode(state);

        if (pendingInitialNavigation) {
            emergencyMode = emergency;
            showInitialScreen();
            return;
        }

        Screen current = navigator.current();
        if (current == null) return;

        boolean themeChanged = emergency != emergencyMode;
        emergencyMode = emergency;

        Screen target = ScreenPolicy.screenFor(current, state);
        if (target != current) {
            navigator.navigatePush(target);   // 期限終了なら SC02
        } else if (themeChanged) {
            navigator.reapplyTheme();          // テーマが変わったら再適用（必要なら作り直し）
        }
        updateBackEnabled();
    }

    /**
     * 初期画面を決定して表示する。pendingInitialNavigation を解除する。
     */
    private void showInitialScreen() {
        pendingInitialNavigation = false;
        mainHandler.removeCallbacks(fallbackRunnable);

        SessionStatus last = UiData.lastSessionStatus();
        SessionStatus.State state = last == null ? null : last.state;
        Screen target = ScreenPolicy.initialScreen(UserProfile.isRegistered(this), state);
        navigator.showInitial(target);
        updateBackEnabled();
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
        Screen current = navigator.current();
        boolean showTabs = current != null && BottomTabs.showsTabs(current);
        fragmentContainer.setPadding(insetLeft, insetTop, insetRight, showTabs ? 0 : insetBottom);
    }

    /**
     * タブが選ばれたときの画面遷移。タブ間は同階層なので X 軸の遷移を使う。
     */
    private void onTabSelected(@NonNull Screen target) {
        navigator.navigateTab(target);
        updateBackEnabled();
    }
}