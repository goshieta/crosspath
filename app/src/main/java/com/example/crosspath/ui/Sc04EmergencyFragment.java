package com.example.crosspath.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.example.crosspath.data.ActiveSession;
import com.example.crosspath.data.WatchStatus;
import com.example.crosspath.ui.data.UiData;
import com.example.crosspath.ui.theme.NavTransitions;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.example.crosspath.ui.theme.StatusBadge;
import com.example.crosspath.ui.theme.ViewAnims;

import java.util.List;
import java.util.Locale;

/**
 * SC04 緊急時画面。仕様: 詳細設計書 v0.8 §11.5
 *
 * 専用ダークテーマで表示。カウントダウンカード（ActiveSession から実時間算出）、通信状態表示、
 * 通知対象者の〇／ー一覧、下部タブバー。
 */
public class Sc04EmergencyFragment extends Fragment {

    private TextView countdownText;
    private TextView commStatusText;
    private TextView blockReasonText;
    private TextView noSessionText;
    private RecyclerView safetyList;

    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private ActiveSession activeSession;
    private boolean countdownRunning;

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || getView() == null) return;
            if (activeSession == null || !"ACTIVE".equals(activeSession.state)) {
                stopCountdown();
                return;
            }
            long remaining = activeSession.endsAtWall - System.currentTimeMillis();
            if (remaining <= 0) {
                // 残り0: タイマー状態更新後、SC02 へ Z軸遷移
                stopCountdown();
                UiData.refreshTimerState();
                if (!isAdded()) return;
                Fragment target = new Sc02HomeFragment();
                NavTransitions.hierarchy(Sc04EmergencyFragment.this, target, true);
                requireActivity().getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, target)
                        .commit();
                return;
            }
            long totalSec = remaining / 1000;
            long hours = totalSec / 3600;
            long minutes = (totalSec % 3600) / 60;
            long secs = totalSec % 60;
            // 表示形式は既存 UI に合わせる（例: 72時間00分00秒）
            String timeStr = String.format(Locale.JAPAN, "%d時間%02d分%02d秒", hours, minutes, secs);
            countdownText.setText(getString(R.string.sc04_countdown_label, timeStr));
            countdownHandler.postDelayed(this, 1000);
        }
    };

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

        // RecyclerView 設定
        safetyList.setLayoutManager(new LinearLayoutManager(requireContext()));
        safetyList.setItemAnimator(new DefaultItemAnimator());

        // カウントダウンカードの出現（初回表示のみ。再表示では動かさない）
        if (savedInstanceState == null) {
            ViewAnims.appearOnce(view.findViewById(R.id.sc04_countdown_card));
        }

        // 最新のセッションと受信状態を読み込む
        loadData();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 復帰時にも最新データを取り直す
        loadData();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopCountdown();
    }

    @Override
    public void onDestroyView() {
        stopCountdown();
        super.onDestroyView();
        // 戻る際はステータスバーを暗色に戻す
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setStatusBarIconStyle(false);
        }
    }

    /** セッションと受信状態を UiData 経由で読み込む。 */
    private void loadData() {
        if (!isAdded()) return;
        // セッションの読み込み
        UiData.whenReady(repo ->
            UiData.onResult(repo.currentSession(), session -> {
                if (!isAdded() || getView() == null) return;
                activeSession = session;
                updateSessionUi();
                // セッション取得後、受信状態も読み込む
                loadWatchStatuses();
            }, error -> {
                if (!isAdded() || getView() == null) return;
                activeSession = null;
                updateSessionUi();
                loadWatchStatuses();
            })
        );
    }

    /** 通信状態テキストとカウントダウンの表示をセッション状態に応じて更新する。 */
    private void updateSessionUi() {
        if (!isAdded() || getView() == null) return;
        boolean hasActive = activeSession != null
                && "ACTIVE".equals(activeSession.state)
                && activeSession.endsAtWall > System.currentTimeMillis();

        // 通信状態テキスト
        // TODO(段階5: BLE の実状態に置換)
        commStatusText.setText(String.format(Locale.JAPAN,
                getString(R.string.sc04_comm_status),
                getString(hasActive ? R.string.sc04_comm_active : R.string.sc04_comm_inactive)));

        // カウントダウン
        if (hasActive) {
            countdownText.setText(getString(R.string.sc04_countdown_label,
                    getString(R.string.sc04_countdown_default)));
            startCountdown();
        } else {
            stopCountdown();
            countdownText.setText(getString(R.string.sc04_countdown_label,
                    getString(R.string.sc04_countdown_default)));
        }
    }

    /** 受信状態一覧を UiData 経由で読み込む。 */
    private void loadWatchStatuses() {
        if (!isAdded()) return;
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

    /** カウントダウンを開始する（既に動いていれば二重起動しない）。 */
    private void startCountdown() {
        if (countdownRunning) return;
        countdownRunning = true;
        countdownHandler.post(countdownTick);
    }

    /** カウントダウンを停止する（Handler のコールバックを削除）。 */
    private void stopCountdown() {
        countdownRunning = false;
        countdownHandler.removeCallbacks(countdownTick);
    }

    /**
     * 〇／ー一覧の RecyclerView Adapter。仕様 §11.8
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

            // 〇／ーは文字を変えず、バッジの色と読み上げだけを共通ヘルパで設定する 仕様 §11.8
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