package com.example.crosspath.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.ui.sample.SampleData;
import com.example.crosspath.ui.theme.ScreenThemes;

import java.util.List;
import java.util.Locale;

/**
 * SC04 緊急時画面。仕様: 詳細設計書 v0.8 §11.5
 *
 * 専用ダークテーマで表示。カウントダウンカード（固定文字列）、通信状態表示、
 * 通知対象者の〇／ー一覧、下部にSC05/SC06への遷移ボタン。
 */
public class Sc04EmergencyFragment extends Fragment {

    private TextView countdownText;
    private TextView countdownLabel;
    private TextView commStatusText;
    private RecyclerView safetyList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // SC04 は専用ダークテーマ
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC04);
        return themedInflater.inflate(R.layout.fragment_sc04_emergency, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        countdownText = view.findViewById(R.id.sc04_countdown_text);
        countdownLabel = view.findViewById(R.id.sc04_countdown_label);
        commStatusText = view.findViewById(R.id.sc04_comm_status_text);
        safetyList = view.findViewById(R.id.sc04_safety_list);

        // ステータスバーを明色に（SC04ダークテーマ）
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setStatusBarIconStyle(true);
        }

        // カウントダウン固定文字列（仕様: タイマーは作らない）
        // TODO(段階5: ExpiryManager の残り時間表示に置換)
        countdownText.setText(R.string.sc04_countdown_default);
        countdownLabel.setText(getString(R.string.sc04_countdown_label,
                getString(R.string.sc04_countdown_default)));

        // 通信状態表示（固定ダミー）
        // TODO(段階5: BLE の実状態に置換)
        commStatusText.setText(String.format(Locale.JAPAN,
                getString(R.string.sc04_comm_status),
                SampleData.DUMMY_COMM_STATE));

        // RecyclerView 設定
        safetyList.setLayoutManager(new LinearLayoutManager(requireContext()));
        SafetyStatusAdapter adapter = new SafetyStatusAdapter(SampleData.WATCH_TARGETS);
        safetyList.setAdapter(adapter);

        // 下部ボタン
        // 仕様 §11.1: SC04 → SC05/SC06
        view.findViewById(R.id.sc04_button_watch_target)
                .setOnClickListener(v -> {
                    getParentFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, new Sc05WatchTargetFragment())
                            .addToBackStack(null)
                            .commit();
                });
        // TODO(段階8): SC06 通知履歴画面へ遷移
        view.findViewById(R.id.sc04_button_notification_history)
                .setOnClickListener(v -> {
                    // TODO(段階8): SC06 へ遷移
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 戻る際はステータスバーを暗色に戻す
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setStatusBarIconStyle(false);
        }
    }

    /**
     * 〇／ー一覧の RecyclerView Adapter。仕様 §11.8
     */
    private static class SafetyStatusAdapter
            extends RecyclerView.Adapter<SafetyStatusAdapter.ViewHolder> {

        private final List<SampleData.SampleWatchTarget> items;

        SafetyStatusAdapter(List<SampleData.SampleWatchTarget> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View itemView = inflater.inflate(R.layout.item_safety_status, parent, false);
            return new ViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SampleData.SampleWatchTarget item = items.get(position);
            holder.nameText.setText(item.displayName);

            if (item.receivedThisSession) {
                // 今回の期間で受信済み → 〇
                holder.statusText.setText(R.string.status_received);
                holder.statusText.setContentDescription(
                        holder.itemView.getContext().getString(R.string.status_received_desc));
            } else {
                // 今回未受信 → ー
                holder.statusText.setText(R.string.status_not_received);
                holder.statusText.setContentDescription(
                        holder.itemView.getContext().getString(R.string.status_not_received_desc));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView nameText;
            final TextView statusText;

            ViewHolder(View itemView) {
                super(itemView);
                nameText = itemView.findViewById(R.id.item_target_name);
                statusText = itemView.findViewById(R.id.item_target_status);
            }
        }
    }
}