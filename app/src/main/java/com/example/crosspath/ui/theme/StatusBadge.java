package com.example.crosspath.ui.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import com.example.crosspath.R;
import com.google.android.material.color.MaterialColors;

/**
 * 〇／ーバッジの共通表示。仕様: 詳細設計書 v0.8 §11.8 / §11.12
 *
 * 文字「〇」「ー」そのものは変えず、円形バッジの色と読み上げラベルだけを画面テーマから決める。
 * 色は layout 側に直書きせず、その画面のテーマ属性から取得する。
 */
public final class StatusBadge {

    private StatusBadge() {
        // インスタンス化禁止
    }

    /**
     * 一覧1行分の状態セルと読み上げラベルを設定する。
     *
     * @param badge    〇／ーを表示する TextView（{@code Widget.Crosspath.StatusBadge} 適用済み）
     * @param row      行全体（読み上げ用の contentDescription を付ける）
     * @param name     対象者の表示名
     * @param received 今回の期間で受信済みかどうか
     */
    public static void bind(TextView badge, View row, CharSequence name, boolean received) {
        final Context context = badge.getContext();
        badge.setText(received ? R.string.status_received : R.string.status_not_received);

        // 受信済み: primaryContainer / 未受信: surfaceContainerHighest（§11.8 の色だけに頼らない規則は下の desc で担保）
        int background = MaterialColors.getColor(badge, received
                ? com.google.android.material.R.attr.colorPrimaryContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHighest);
        int foreground = MaterialColors.getColor(badge, received
                ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOnSurfaceVariant);
        ViewCompat.setBackgroundTintList(badge, ColorStateList.valueOf(background));
        badge.setTextColor(foreground);

        // バッジ単体ではなく行全体に「名前＋今回の受信状態」を読み上げさせる
        String state = context.getString(received
                ? R.string.status_received_desc
                : R.string.status_not_received_desc);
        row.setContentDescription(context.getString(R.string.item_safety_row_desc, name, state));
        badge.setContentDescription(null);
    }
}
