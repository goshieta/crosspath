package com.example.crosspath.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.data.WatchStatus;
import com.example.crosspath.ui.data.UiData;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.example.crosspath.ui.theme.StatusBadge;

import java.util.List;

/**
 * SC06 通知履歴画面。仕様: 詳細設計書 v0.8 §11.7
 *
 * 通知対象者の今回の通信期間での生存情報受信状況を一覧表示。
 * タブバーからホーム（SC02）へ遷移。
 */
public class Sc06NotificationHistoryFragment extends Fragment {

    private RecyclerView safetyList;
    private TextView noSessionText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        boolean emergency = getActivity() instanceof MainActivity
                && ((MainActivity) getActivity()).isEmergencyMode();
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC06, emergency);
        return themedInflater.inflate(R.layout.fragment_sc06_notification_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        safetyList = view.findViewById(R.id.sc06_safety_list);
        noSessionText = view.findViewById(R.id.sc06_no_session_text);

        safetyList.setLayoutManager(new LinearLayoutManager(requireContext()));
        safetyList.setItemAnimator(new DefaultItemAnimator());

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC06);
        }

        loadWatchStatuses();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 復帰時に最新データを取り直す
        loadWatchStatuses();
    }

    /** 受信状態一覧を UiData 経由で読み込み、表示する。表示のたびに checkSession を先に呼ぶ。 */
    private void loadWatchStatuses() {
        if (!isAdded()) return;
        UiData.checkSession(status -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onSessionStatus(status);
            }
        }, error -> {});
        UiData.whenReady(repo ->
            UiData.onResult(repo.currentWatchStatuses(), statuses -> {
                if (!isAdded() || getView() == null) return;
                if (statuses == null || statuses.isEmpty()) {
                    // 通知対象者が未登録
                    noSessionText.setText(R.string.sc04_no_watch_targets);
                    noSessionText.setVisibility(View.VISIBLE);
                    safetyList.setVisibility(View.GONE);
                } else if (allNoActiveSession(statuses)) {
                    // 全要素 NO_ACTIVE_SESSION → 現在の通信期間なし
                    noSessionText.setText(R.string.state_no_session);
                    noSessionText.setVisibility(View.VISIBLE);
                    safetyList.setVisibility(View.GONE);
                } else {
                    noSessionText.setVisibility(View.GONE);
                    SafetyStatusAdapter adapter = new SafetyStatusAdapter(statuses);
                    safetyList.setAdapter(adapter);
                    safetyList.setVisibility(View.VISIBLE);
                }
            }, error -> {
                if (!isAdded() || getView() == null) return;
                noSessionText.setText(R.string.state_no_session);
                noSessionText.setVisibility(View.VISIBLE);
                safetyList.setVisibility(View.GONE);
            })
        );
    }

    private static boolean allNoActiveSession(List<WatchStatus> statuses) {
        for (WatchStatus ws : statuses) {
            if (ws.state != WatchStatus.State.NO_ACTIVE_SESSION) {
                return false;
            }
        }
        return true;
    }

    /**
     * 〇／ー一覧の RecyclerView Adapter（SC04 と同一ロジック）。仕様 §11.8
     */
    private static class SafetyStatusAdapter
            extends RecyclerView.Adapter<SafetyStatusAdapter.ViewHolder> {

        private final List<WatchStatus> items;

        SafetyStatusAdapter(List<WatchStatus> items) {
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
            WatchStatus item = items.get(position);
            holder.nameText.setText(item.displayName);

            // SC04 と同一の〇／ー表示（共通ヘルパ） 仕様 §11.8
            StatusBadge.bind(holder.statusText, holder.itemView,
                    item.displayName, item.state == WatchStatus.State.RECEIVED);
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