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
import com.example.crosspath.ui.sample.SampleData;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.example.crosspath.ui.theme.StatusBadge;
import com.example.crosspath.ui.theme.ViewAnims;

import java.util.List;
import java.util.Locale;

/**
 * SC04 緊急時画面。仕様: 詳細設計書 v0.8 §11.5
 *
 * 専用ダークテーマで表示。カウントダウンカード（固定文字列）、通信状態表示、
 * 通知対象者の〇／ー一覧、下部タブバー。
 */
public class Sc04EmergencyFragment extends Fragment {

    private TextView countdownText;
    private TextView commStatusText;
    private TextView blockReasonText;
    private TextView noSessionText;
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
        commStatusText = view.findViewById(R.id.sc04_comm_status_text);
        blockReasonText = view.findViewById(R.id.sc04_block_reason);
        noSessionText = view.findViewById(R.id.sc04_no_session_text);
        safetyList = view.findViewById(R.id.sc04_safety_list);

        // 通信不能理由は初期非表示（§11.5: BLE無効・権限不足・保存失敗のときだけ表示）
        // TODO(段階5: BLE の実状態に応じて理由文言を設定・表示する)
        blockReasonText.setVisibility(View.GONE);

        // SC04 のダークテーマを画面全体へ適用
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC04);
        }

        // カウントダウン固定文字列（仕様: タイマーは作らない、1組で表示）
        // TODO(段階5: ExpiryManager の残り時間表示に置換)
        countdownText.setText(getString(R.string.sc04_countdown_label,
                getString(R.string.sc04_countdown_default)));

        // 通信状態表示（固定ダミー）
        // TODO(段階5: BLE の実状態に置換)
        commStatusText.setText(String.format(Locale.JAPAN,
                getString(R.string.sc04_comm_status),
                SampleData.DUMMY_COMM_STATE));

        // RecyclerView 設定
        safetyList.setLayoutManager(new LinearLayoutManager(requireContext()));
        // 削除時に1回だけ動くアイテムアニメーターを明示
        safetyList.setItemAnimator(new DefaultItemAnimator());

        // カウントダウンカードの出現（初回表示のみ。再表示では動かさない）
        if (savedInstanceState == null) {
            ViewAnims.appearOnce(view.findViewById(R.id.sc04_countdown_card));
        }

        // TODO(段階2: Room の SafetyRecord 照合に置換)
        if (SampleData.HAS_ACTIVE_SESSION) {
            SafetyStatusAdapter adapter = new SafetyStatusAdapter(SampleData.WATCH_TARGETS);
            safetyList.setAdapter(adapter);
            safetyList.setVisibility(View.VISIBLE);
            noSessionText.setVisibility(View.GONE);
        } else {
            // 通信期間なし（仕様 §11.8: 記号はすべてー、別途表示）
            safetyList.setVisibility(View.GONE);
            noSessionText.setVisibility(View.VISIBLE);
        }

        BottomTabs.bind(view, this, Screen.SC04);
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

            // 〇／ーは文字を変えず、バッジの色と読み上げだけを共通ヘルパで設定する 仕様 §11.8
            StatusBadge.bind(holder.statusText, holder.itemView,
                    item.displayName, item.receivedThisSession);
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