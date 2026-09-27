package com.example.crosspath;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
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
    private boolean relayVisible, relayRequested, permissionAsked;
    private SessionStatus relaySession;
    private final androidx.activity.result.ActivityResultLauncher<String[]> relayPermissions = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(), result -> {
                relayRequested = false;
                if (com.example.crosspath.service.RelayForegroundService.hasPermissions(this)) ensureRelay();
                else relayBlocked("付近のデバイス権限が必要です。「通信を再開」から設定できます");
            });

    private void relayBlocked(String message) {
        ((CrosspathApplication) getApplication()).relayStatus.setValue(new com.example.crosspath.service.RelayStatus(
                com.example.crosspath.service.RelayStatus.State.BLOCKED, message, ""));
    }
    private void ensureRelay() {
        if (!relayVisible || relaySession == null || !relaySession.canCommunicate || relayRequested
                || ((CrosspathApplication) getApplication()).bleDebugActive) return;
        boolean notificationMissing = android.os.Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("relay-ui", MODE_PRIVATE).getBoolean("notificationAsked", false);
        if (!permissionAsked && (notificationMissing || !com.example.crosspath.service.RelayForegroundService.hasPermissions(this))) {
            permissionAsked = true;
            java.util.ArrayList<String> requested = new java.util.ArrayList<>();
            if (!com.example.crosspath.service.RelayForegroundService.hasPermissions(this))
                java.util.Collections.addAll(requested, com.example.crosspath.service.RelayForegroundService.BLE_PERMISSIONS);
            if (notificationMissing) {
                requested.add(android.Manifest.permission.POST_NOTIFICATIONS);
                getSharedPreferences("relay-ui", MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            }
            relayPermissions.launch(requested.toArray(new String[0]));
            return;
        }
        if (!com.example.crosspath.service.RelayForegroundService.hasPermissions(this)) {
            relayBlocked("付近のデバイス権限を許可してください");
            return;
        }
        String reason = com.example.crosspath.service.RelayForegroundService.unavailableReason(this);
        if (reason != null) { relayBlocked(reason); return; }
        try {
            androidx.core.content.ContextCompat.startForegroundService(this,
                    new android.content.Intent(this, com.example.crosspath.service.RelayForegroundService.class));
            relayRequested = true;
        } catch (RuntimeException failure) { relayBlocked("通信を開始できません。「通信を再開」を押してください"); }
    }
    public void retryRelay() {
        relayRequested = false;
        if (!com.example.crosspath.service.RelayForegroundService.hasPermissions(this) && permissionAsked) {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        UiData.checkSession(this::onSessionStatus, error -> relayBlocked("登録状態を確認できません"));
    }

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
    private boolean confirmationSaving;
    private boolean notificationTap;
    private boolean resumed;
    private boolean restoredScreenPending;
    private final java.util.function.Consumer<SessionStatus> periodListener = this::onSessionStatus;
    public void setConfirmationSaving(boolean value) { confirmationSaving = value; }


    /** システムの戻る操作。SC03 のときだけ有効化して SC02 へ戻す。 */
    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (confirmationSaving) return;
            Screen target = ScreenPolicy.backTarget(navigator.current());
            if (target != null) {
                navigator.navigateBack(target);
            }
        }
    };

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        emergencyMode = savedInstanceState != null && savedInstanceState.getBoolean("emergency");
        super.onCreate(savedInstanceState);
        UiData.init(getApplicationContext());
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        // BLE検証画面（BleDebugActivity）へのUI導線は設けない。debugビルド限定の画面で、
        // PCから adb shell am start -n com.example.crosspath.debug/com.example.crosspath.BleDebugActivity
        // で起動する（画面側で bleDebugActive の設定と中継サービスの停止を行う）。

        fragmentContainer = findViewById(R.id.fragment_container);
        tabBarContainer = findViewById(R.id.bottom_tabs_container);

        navigator = new Navigator(getSupportFragmentManager(), R.id.fragment_container, this::applyScreenTheme);
        if (savedInstanceState != null && savedInstanceState.getString("screen") != null) {
            navigator.restoreCurrent(Screen.valueOf(savedInstanceState.getString("screen")));
        }
        notificationTap = com.example.crosspath.notification.NotificationDispatcher.ACTION_HISTORY.equals(getIntent().getAction());
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

        pendingInitialNavigation = navigator.current() == null;
        restoredScreenPending = !pendingInitialNavigation;
        fragmentContainer.setVisibility(View.INVISIBLE);
        tabBarContainer.setVisibility(View.GONE);
        applyScreenTheme(navigator.current());
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        if (navigator.current() != null) state.putString("screen", navigator.current().name());
        state.putBoolean("emergency", emergencyMode);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        relayVisible = true;
        relayRequested = false;
        resumed = true;
        UiData.addListener(periodListener);
        UiData.init(getApplicationContext());
        UiData.checkSession(this::onSessionStatus, error -> {
            if (!resumed || isFinishing()) return;
            com.google.android.material.snackbar.Snackbar.make(fragmentContainer,
                    "保存状態を読み込めません。再試行してください", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                    .setAction("再試行", view -> { UiData.init(getApplicationContext()); UiData.checkSession(this::onSessionStatus, ignored -> {}); }).show();
        });
        UiData.maintain();
    }

    @Override protected void onPause() {
        relayVisible = false;
        resumed = false;
        UiData.removeListener(periodListener);
        super.onPause();
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        notificationTap = com.example.crosspath.notification.NotificationDispatcher.ACTION_HISTORY.equals(intent.getAction());
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
        if (!resumed || getSupportFragmentManager().isStateSaved()) return;
        navigator.navigatePush(target);
        updateBackEnabled();
    }

    @Override
    public void navigateBack(@NonNull Screen target) {
        if (!resumed || getSupportFragmentManager().isStateSaved()) return;
        navigator.navigateBack(target);
        updateBackEnabled();
    }

    @Override
    public void navigateTab(@NonNull Screen target) {
        if (!resumed || getSupportFragmentManager().isStateSaved()) return;
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
        relaySession = status;
        if (status != null && status.canCommunicate) ensureRelay();
        else if (status != null) {
            relayRequested = false;
            stopService(new android.content.Intent(this, com.example.crosspath.service.RelayForegroundService.class));
        }
        if (!resumed || getSupportFragmentManager().isStateSaved() || status == null) return;
        fragmentContainer.setVisibility(View.VISIBLE);
        if (restoredScreenPending) {
            restoredScreenPending = false;
            androidx.fragment.app.Fragment restored = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (restored instanceof Sc02HomeFragment) ((Sc02HomeFragment) restored).refreshIdentity();
            applyScreenTheme(navigator.current());
        }
        SessionStatus.State state = status.state;
        boolean emergency = ScreenPolicy.emergencyMode(state);

        if (pendingInitialNavigation) {
            emergencyMode = emergency;
            showInitialScreen();
            return;
        }

        if (notificationTap && UserProfile.isRegistered(this)) {
            notificationTap = false;
            getIntent().setAction(null);
            navigator.navigateTab(Screen.SC06);
        }
        Screen current = navigator.current();
        if (current == null) return;

        boolean themeChanged = emergency != emergencyMode;
        emergencyMode = emergency;

        Screen target = current == Screen.SC03 && ScreenPolicy.isActivePeriod(state)
                ? Screen.SC04 : ScreenPolicy.screenFor(current, state);
        if (target == Screen.SC04) confirmationSaving = false;
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


        SessionStatus last = UiData.lastSessionStatus();
        SessionStatus.State state = last == null ? null : last.state;
        Screen target = ScreenPolicy.initialScreen(UserProfile.isRegistered(this), state);
        if (notificationTap && UserProfile.isRegistered(this)) {
            target = Screen.SC06;
            notificationTap = false;
            getIntent().setAction(null);
        }
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
        if (screen == null || fragmentContainer == null || tabBarContainer == null || navigator == null) return;

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
        UiData.checkSession(status -> {
            if (!resumed || getSupportFragmentManager().isStateSaved()) return;
            Screen resolved = target == Screen.SC02 || target == Screen.SC04
                    ? ScreenPolicy.initialScreen(UserProfile.isRegistered(this), status.state) : target;
            navigator.navigateTab(resolved);
            updateBackEnabled();
        }, error -> com.google.android.material.snackbar.Snackbar.make(fragmentContainer,
                "期間を確認できません。再試行してください", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show());
    }
}
