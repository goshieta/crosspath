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

/**
 * SC06 通知履歴画面。仕様: 詳細設計書 v0.8 §11.7
 *
 * 通知対象者の今回の通信期間での生存情報受信状況を一覧表示。
 * ホームボタンで SC02 へ戻る（TODO: タイマー状態の確認）。
 */
public class Sc06NotificationHistoryFragment extends Fragment {

    private RecyclerView safetyList;
    private TextView noSessionText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC06);
        return themedInflater.inflate(R.layout.fragment_sc06_notification_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        safetyList = view.findViewById(R.id.sc06_safety_list);
        noSessionText = view.findViewById(R.id.sc06_no_session_text);

        safetyList.setLayoutManager(new LinearLayoutManager(requireContext()));

        // TODO(段階2: Room の SafetyRecord 照合に置換)
        if (SampleData.WATCH_TARGETS.isEmpty()) {
            // 通信期間なし
            safetyList.setVisibility(View.GONE);
            noSessionText.setVisibility(View.VISIBLE);
        } else {
            SafetyStatusAdapter adapter = new SafetyStatusAdapter(SampleData.WATCH_TARGETS);
            safetyList.setAdapter(adapter);
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC06);
        }

        view.findViewById(R.id.sc06_button_home).setOnClickListener(v -> onHomeClicked());
    }

    private void onHomeClicked() {
        // 仕様 §11.1: タイマー作動中は SC04、未開始・終了済みは SC02
        // TODO(段階5: タイマー状態を確認し SC02/SC04 を切り替え)
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new Sc02HomeFragment())
                .addToBackStack(null)
                .commit();
    }

    /**
     * 〇／ー一覧の RecyclerView Adapter（SC04 と同一ロジック）。仕様 §11.8
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
                holder.statusText.setText(R.string.status_received);
                holder.statusText.setContentDescription(
                        holder.itemView.getContext().getString(R.string.status_received_desc));
            } else {
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