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
import com.example.crosspath.data.SessionStatus;
import com.example.crosspath.data.WatchStatus;
import com.example.crosspath.ui.data.UiData;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.example.crosspath.ui.theme.StatusBadge;
import com.example.crosspath.ui.theme.ViewAnims;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SC04 緊急時画面。仕様: 詳細設計書 v0.8 §11.5
 *
 * 専用ダークテーマで表示。カウントダウンカード（SessionStatus.remainingMillis のスナップショット）、
 * 通信状態表示、通知対象者の〇／ー一覧、下部タブバー。
 */
public class Sc04EmergencyFragment extends Fragment {

    private TextView countdownText;
    private TextView commStatusText;
    private TextView blockReasonText;
    private TextView noSessionText;
    private RecyclerView safetyList;
    private final java.util.function.Consumer<com.example.crosspath.data.SessionStatus> periodListener = status -> {
        if (getView() == null || !isResumed()) return;
        sessionStatus = status; remainingForDisplay = status.remainingMillis; loadWatchStatuses(); applyViewState();
    };


    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private SessionStatus sessionStatus;
    /** カウントダウン表示用のスナップショット（remainingMillis を元に1秒ずつ減らす）。期限判定には使わない。 */
    private long remainingForDisplay;
    private boolean countdownRunning;
    /** 通知対象者一覧（現在の通信期間の受信状態）。 */
    private List<WatchStatus> watchStatuses;
    /** 通知対象者が1件以上登録されているか（一覧表示の可否は Sc04ViewState が決める）。 */
    private boolean hasTargets;

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || getView() == null) return;
            remainingForDisplay -= 1000;
            if (remainingForDisplay <= 0) {
                // 表示が0になったら checkSession を呼び直し、返ってきた状態で再レンダリング
                stopCountdown();
                UiData.checkSession(status -> {
                    if (!isAdded() || getView() == null) return;
                    // Activity（遷移の唯一の実行者）にも状態を通知する
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).onSessionStatus(status);
                    }
                    if (!ScreenPolicy.isActivePeriod(status.state)) {
                        // 期限終了: checkAndEndExpiredSession 済みの状態なので SC02 へ（§11.5）
                        ((NavHost) requireActivity()).navigatePush(Screen.SC02);
                    } else {
                        // 期間は続いている（時刻のずれ等）→ 残り時間で再開
                        render(status);
                    }
                }, error -> {
                    if (!isAdded() || getView() == null) return;
                    blockReasonText.setText("期限を確認できません。アプリを開き直して再試行してください");
                    blockReasonText.setVisibility(View.VISIBLE);
                });
                return;
            }
            long totalSec = remainingForDisplay / 1000;
            long hours = totalSec / 3600;
            long minutes = (totalSec % 3600) / 60;
            long secs = totalSec % 60;
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
        UiData.addListener(periodListener);
        // 復帰時にも最新データを取り直す
        loadData();
    }

    @Override
    public void onPause() {
        UiData.removeListener(periodListener);
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

    /** セッションと受信状態を UiData 経由で読み込む。checkSession → currentWatchStatuses の順。 */
    private void loadData() {
        if (!isAdded()) return;
        UiData.checkSession(status -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onSessionStatus(status);
            }
            if (!isAdded() || getView() == null) return;
            sessionStatus = status;
            // 先に受信状態を読み込み、hasTargets を反映した状態で表示する
            loadWatchStatuses();
            render(status);
        }, error -> {
            if (!isAdded() || getView() == null) return;
            sessionStatus = null;
            loadWatchStatuses();
            render(null);
        });
    }

    /**
     * セッション状態を保存し、表示内容を更新する。
     * 仕様 §11.5 / §11.8: 通信不能でも72時間は進み、現在の期間がある限り対象者一覧を表示する。
     */
    private void render(SessionStatus status) {
        if (!isAdded() || getView() == null) return;
        sessionStatus = status;
        applyViewState();
    }

    /** Sc04ViewState の値だけを使って View を更新する（表示判断は Sc04ViewState に集約）。 */
    private void applyViewState() {
        if (!isAdded() || getView() == null) return;

        SessionStatus status = sessionStatus;
        SessionStatus.State state = status == null ? null : status.state;
        // SessionStatus は relayEnabled を公開しないが、ACTIVE のとき canCommunicate == relayEnabled。
        // CLOCK_UNCERTAIN では relayEnabled は判定に使われない（Sc04ViewState の規則）。
        boolean relayEnabled = UiData.communicationRunning(status);
        Sc04ViewState vs = Sc04ViewState.of(state, relayEnabled, hasTargets);

        // カウントダウン（§11.10: 秒単位の更新は SC04 表示中のみ。期限判定はデータ層の責務）
        if (vs.countdownRunning && status != null) {
            if (!countdownRunning) {
                remainingForDisplay = status.remainingMillis;
                startCountdown();
            }
        } else {
            stopCountdown();
            countdownText.setText(getString(R.string.sc04_countdown_label,
                    getString(R.string.sc04_countdown_default)));
        }

        // 通信状態（§11.5: 通信が動いていると誤表示しない）
        commStatusText.setText(String.format(Locale.JAPAN,
                getString(R.string.sc04_comm_status),
                getString(vs.commStatusRes)));

        // 通信不能理由（Bluetooth無効・権限不足・時計不確実など）
        if (vs.blockReasonRes != 0) {
            blockReasonText.setText(state == SessionStatus.State.ACTIVE ? UiData.communicationReason(status) : getString(vs.blockReasonRes));
            blockReasonText.setVisibility(View.VISIBLE);
        } else {
            blockReasonText.setVisibility(View.GONE);
        }

        // 「現在の通信期間なし」「通知対象者が登録されていません」
        if (vs.emptyTextRes != 0) {
            noSessionText.setText(getString(vs.emptyTextRes));
            noSessionText.setVisibility(View.VISIBLE);
        } else {
            noSessionText.setVisibility(View.GONE);
        }

        // 〇／ー一覧: 現在の期間があり、対象者が登録されているときだけ表示する。
        // 通信不能（relayEnabled == false）でも期間が続いていれば一覧を出す。
        if (vs.listVisible) {
            safetyList.setAdapter(new SafetyStatusAdapter(watchStatuses));
            safetyList.setVisibility(View.VISIBLE);
        } else {
            safetyList.setVisibility(View.GONE);
        }
    }

    /** 受信状態一覧を UiData 経由で読み込む。表示可否は Sc04ViewState が決める。 */
    private void loadWatchStatuses() {
        if (!isAdded()) return;
        UiData.onResult(UiData.execute(repo -> repo.currentWatchStatuses()), statuses -> {
                if (!isAdded() || getView() == null) return;
                watchStatuses = statuses == null ? null : new ArrayList<>(statuses);
                hasTargets = watchStatuses != null && !watchStatuses.isEmpty();
                applyViewState();
            }, error -> {
                if (!isAdded() || getView() == null) return;
                watchStatuses = null;
                hasTargets = false;
                applyViewState();
            });
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