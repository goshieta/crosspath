package com.example.crosspath;

import android.os.Bundle;
import android.view.Window;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.FragmentContainerView;

/**
 * 単一 Activity。仕様: 詳細設計書 v0.8 第11章
 *
 * FragmentContainerView を唯一のコンテンツとし、Fragment の replace＋addToBackStack で画面遷移する。
 * ステータスバーのアイコン色は WindowInsetsControllerCompat で画面ごとに切り替える。
 */
public class MainActivity extends AppCompatActivity {

    private FragmentContainerView fragmentContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        fragmentContainer = findViewById(R.id.fragment_container);

        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // TODO(段階2): 初回起動時に本人IDの有無を確認し SC01 か SC02 を表示
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

    public FragmentContainerView getFragmentContainer() {
        return fragmentContainer;
    }
}