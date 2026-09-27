package com.example.crosspath.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.crosspath.R;
import com.example.crosspath.data.SessionStatus;

/**
 * SC04 の「何を表示するか」だけを決める値オブジェクト（render() から View 操作を除いたもの）。
 * 仕様: 詳細設計書 v0.8 §11.5 / §11.8
 *
 * 「通信不能でも一覧を出す」「『現在の通信期間なし』と『通信期間中だが通信できていない』を区別する」を満たす。
 */
public final class Sc04ViewState {

    /** state == ACTIVE のときだけ true。 */
    public final boolean countdownRunning;

    /** CommState.COMMUNICATING のときだけ true。 */
    public final boolean commOk;

    /** ScreenPolicy.commStatusRes(comm) の結果。 */
    @StringRes
    public final int commStatusRes;

    /** 通信不能理由。0 = 非表示。 */
    @StringRes
    public final int blockReasonRes;

    /** 期間作動中 && 対象者あり。 */
    public final boolean listVisible;

    /** 「現在の通信期間なし」等の代替文言。0 = 非表示。 */
    @StringRes
    public final int emptyTextRes;

    private Sc04ViewState(boolean countdownRunning, boolean commOk,
                          @StringRes int commStatusRes, @StringRes int blockReasonRes,
                          boolean listVisible, @StringRes int emptyTextRes) {
        this.countdownRunning = countdownRunning;
        this.commOk = commOk;
        this.commStatusRes = commStatusRes;
        this.blockReasonRes = blockReasonRes;
        this.listVisible = listVisible;
        this.emptyTextRes = emptyTextRes;
    }

    /**
     * 表示内容を決定する。規則（この順で評価）:
     * <ol>
     * <li>comm = ScreenPolicy.commState(state, relayEnabled), countdownRunning = (state == ACTIVE)</li>
     * <li>!isActivePeriod(state) → listVisible=false, emptyTextRes=R.string.state_no_session</li>
     * <li>isActivePeriod && !hasTargets → listVisible=false, emptyTextRes=R.string.sc04_no_watch_targets</li>
     * <li>isActivePeriod && hasTargets → listVisible=true, emptyTextRes=0</li>
     * <li>blockReasonRes = ScreenPolicy.blockReasonRes(comm)</li>
     * </ol>
     */
    @NonNull
    public static Sc04ViewState of(@Nullable SessionStatus.State state, boolean relayEnabled,
                                   boolean hasTargets) {
        ScreenPolicy.CommState comm = ScreenPolicy.commState(state, relayEnabled);
        boolean countdownRunning = state == SessionStatus.State.ACTIVE;

        boolean listVisible;
        int emptyTextRes;
        if (!ScreenPolicy.isActivePeriod(state)) {
            // 通信期間が未開始または終了済み（§11.8）
            listVisible = false;
            emptyTextRes = R.string.state_no_session;
        } else if (!hasTargets) {
            listVisible = false;
            emptyTextRes = R.string.sc04_no_watch_targets;
        } else {
            listVisible = true;
            emptyTextRes = 0;
        }

        int blockReasonRes = ScreenPolicy.blockReasonRes(comm);
        int commStatusRes = ScreenPolicy.commStatusRes(comm);
        return new Sc04ViewState(countdownRunning, comm == ScreenPolicy.CommState.COMMUNICATING,
                commStatusRes, blockReasonRes, listVisible, emptyTextRes);
    }
}