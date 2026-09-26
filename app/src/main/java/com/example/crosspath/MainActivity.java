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
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;

import com.example.crosspath.ui.BottomTabs;
import com.example.crosspath.ui.Screen;
import com.example.crosspath.ui.Sc01RegistrationFragment;
import com.example.crosspath.ui.theme.NavTransitions;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.google.android.material.color.MaterialColors;

/**
 * 単一 Activity。仕様: 詳細設計書 v0.8 第11章
 *
 * 画面（Fragment）は fragment_container の中だけを入れ替える。下部タブバーは
 * Activity 側（bottom_tabs_container）に置いた「ページを切り替えるコンポーネント」であり、
 * ページ遷移アニメーションの影響を受けない（揺れない）うえ、背景は画面全幅に広がる。
 * タブバーのテーマは表示中の画面のテーマ（SC04 のみダーク）に合わせて作り直す。
 */
public class MainActivity extends AppCompatActivity {

    private FragmentContainerView fragmentContainer;
    private FrameLayout tabBarContainer;

    /** 現在タブバーとして表示している View（画面テーマが変わったときだけ作り直す）。 */
    private View tabBar;

    /** タブバーに現在適用しているテーマ。 */
    private Screen.ThemeVariant tabBarTheme;

    /** 現在表示中の画面（タブの選択状態とコンテンツ余白の計算に使う）。 */
    private Screen currentScreen;

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        // 初回起動時は SC01 を表示（TODO(段階2): 本人ID有無により SC01 か SC02 かを判定）
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new Sc01RegistrationFragment())
                    .commit();
        }
    }

    /**
     * ステータスバーのアイコン色を設定する。
     * SC04 (ダークテーマ) では明色、それ以外は暗色。
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
                ScreenThemes.themeResFor(screen));
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
        setStatusBarIconStyle(screen.theme == Screen.ThemeVariant.DARK);
    }

    /**
     * 下部タブバーを現在の画面に合わせる。表示しない画面では隠す。
     * タブバーのテーマは画面テーマ（SC04 のみダーク）で作るため、テーマが変わったときだけ作り直す。
     */
    private void applyTabBar(@NonNull Screen screen, @NonNull Context themedContext) {
        boolean show = BottomTabs.showsTabs(screen);
        tabBarContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            tabBarContainer.removeAllViews();
            tabBar = null;
            tabBarTheme = null;
            return;
        }

        if (tabBar == null || tabBarTheme != screen.theme) {
            LayoutInflater inflater = LayoutInflater.from(themedContext);
            tabBarContainer.removeAllViews();
            tabBar = inflater.inflate(R.layout.include_bottom_tabs, tabBarContainer, false);
            tabBarContainer.addView(tabBar);
            tabBarTheme = screen.theme;
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
