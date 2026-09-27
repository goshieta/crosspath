package com.example.crosspath.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.crosspath.R;
import com.example.crosspath.data.SessionStatus;

/**
 * 画面遷移・通信状態の判断を集約した純粋関数。仕様: 詳細設計書 v0.8 §11.1 / §11.5
 *
 * 入力は {@link SessionStatus.State} と relayEnabled だけ（SessionStatus 自体は生成できないため）。
 * 緊急時モード＝「未満了の期間がある（ACTIVE または CLOCK_UNCERTAIN）」であり、
 * canCommunicate（通信できるか）とは別の述語。
 */
public final class ScreenPolicy {

    /** 画面に表示する通信状態（§11.5）。 */
    public enum CommState {
        /** 通信できている（ACTIVE かつ relayEnabled）。 */
        COMMUNICATING,
        /** 期間はあるが通信できない（ACTIVE かつ !relayEnabled）。 */
        BLOCKED,
        /** 端末時刻を信頼できない。期間は消えていない。 */
        CLOCK_UNCERTAIN,
        /** 現在の通信期間なし（未開始・終了済み）。 */
        NO_PERIOD
    }

    private ScreenPolicy() {
        // インスタンス化禁止
    }

    /** §11.1「タイマー作動中」: 未満了の期間がある。 */
    public static boolean isActivePeriod(@Nullable SessionStatus.State state) {
        return state == SessionStatus.State.ACTIVE
                || state == SessionStatus.State.CLOCK_UNCERTAIN;
    }

    /** 緊急時モード（SC04 をホームとし、SC05/SC06 もダークで表示する）。 */
    public static boolean emergencyMode(@Nullable SessionStatus.State state) {
        return isActivePeriod(state);
    }

    /**
     * 通信状態。ACTIVE&&relayEnabled=COMMUNICATING / ACTIVE&&!relayEnabled=BLOCKED /
     * CLOCK_UNCERTAIN=CLOCK_UNCERTAIN / それ以外=NO_PERIOD。
     */
    @NonNull
    public static CommState commState(@Nullable SessionStatus.State state, boolean relayEnabled) {
        if (state == SessionStatus.State.ACTIVE) {
            return relayEnabled ? CommState.COMMUNICATING : CommState.BLOCKED;
        }
        if (state == SessionStatus.State.CLOCK_UNCERTAIN) {
            return CommState.CLOCK_UNCERTAIN;
        }
        return CommState.NO_PERIOD;
    }

    /**
     * 状態変化後に表示すべき画面。動かさない場合は current をそのまま返す。
     * 作動中: SC02→SC04（ホームは緊急時画面）／それ以外は current のまま。
     * 非作動: SC04→SC02（期間終了）／それ以外は current のまま。
     */
    @NonNull
    public static Screen screenFor(@NonNull Screen current, @Nullable SessionStatus.State state) {
        if (isActivePeriod(state)) {
            return current == Screen.SC02 ? Screen.SC04 : current;
        }
        return current == Screen.SC04 ? Screen.SC02 : current;
    }

    /** 起動時の初期画面。未登録=SC01／作動中=SC04／それ以外=SC02。state==null は非作動扱い。 */
    @NonNull
    public static Screen initialScreen(boolean registered, @Nullable SessionStatus.State state) {
        if (!registered) {
            return Screen.SC01;
        }
        if (isActivePeriod(state)) {
            return Screen.SC04;
        }
        return Screen.SC02;
    }

    /**
     * システムの戻る操作の移動先。null は「既定動作（アプリ終了）」。
     * SC03→SC02 のみ。SC04 から古い画面へ戻す先は無い（null）。
     */
    @Nullable
    public static Screen backTarget(@NonNull Screen current) {
        if (current == Screen.SC03) {
            return Screen.SC02;
        }
        return null;
    }

    /**
     * SC04 の通信状態表示。COMMUNICATING=R.string.sc04_comm_active／
     * BLOCKED・CLOCK_UNCERTAIN=R.string.sc04_comm_blocked／NO_PERIOD=R.string.sc04_comm_inactive。
     */
    @StringRes
    public static int commStatusRes(@NonNull CommState comm) {
        switch (comm) {
            case COMMUNICATING:
                return R.string.sc04_comm_active;
            case BLOCKED:
            case CLOCK_UNCERTAIN:
                return R.string.sc04_comm_blocked;
            default:
                return R.string.sc04_comm_inactive;
        }
    }

    /**
     * 通信不能理由（§11.5）。BLOCKED=R.string.sc04_block_comm／
     * CLOCK_UNCERTAIN=R.string.sc04_block_clock_uncertain／それ以外=0（非表示）。
     */
    @StringRes
    public static int blockReasonRes(@NonNull CommState comm) {
        switch (comm) {
            case BLOCKED:
                return R.string.sc04_block_comm;
            case CLOCK_UNCERTAIN:
                return R.string.sc04_block_clock_uncertain;
            default:
                return 0;
        }
    }
}