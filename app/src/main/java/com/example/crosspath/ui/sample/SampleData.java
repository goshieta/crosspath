package com.example.crosspath.ui.sample;

import java.util.Arrays;
import java.util.List;

/**
 * 表示確認用の固定データ置き場。仕様: 詳細設計書 v0.8 §11
 *
 * すべての項目に TODO コメントを付し、実データへの置き換えが明示的に追跡できるようにする。
 * 画面間で状態を持ち回らず、各画面はこの固定値を表示するだけ。
 */
public class SampleData {

    /** 自分の個人ID（24bit有効範囲内の例）。TODO(段階2: 登録APIの返値に置換) */
    public static final String MY_USER_ID = "1234567";

    /** 自分の名前。TODO(段階2: 登録APIの返値に置換) */
    public static final String MY_NAME = "山田太郎";

    /** 通知対象者のサンプル。TODO(段階2: Room の WatchTarget に置換) */
    public static final List<SampleWatchTarget> WATCH_TARGETS = Arrays.asList(
            new SampleWatchTarget("7654321", "佐藤花子", true),
            new SampleWatchTarget("1111111", "鈴木一郎", false),
            new SampleWatchTarget("2222222", "田中正義", true),
            new SampleWatchTarget("3333333", "John Smith", false),
            new SampleWatchTarget("9999999", "東京都港区赤坂九丁目七十九番地", true) // 長い名前
    );

    private SampleData() {
        // インスタンス化禁止
    }

    /** 通知対象者サンプルの1行 */
    public static class SampleWatchTarget {
        public final String userId;
        public final String displayName;
        /** 今回のACTIVE期間での受信状態。TODO(段階2: SafetyRecord の照合に置換) */
        public final boolean receivedThisSession;

        public SampleWatchTarget(String userId, String displayName, boolean receivedThisSession) {
            this.userId = userId;
            this.displayName = displayName;
            this.receivedThisSession = receivedThisSession;
        }
    }

}