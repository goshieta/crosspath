package com.example.crosspath.ui;

import org.junit.Test;

import com.example.crosspath.R;
import com.example.crosspath.data.SessionStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Sc04ViewState（SC04 の表示内容の決定）の回帰テスト。JVM のみ、Robolectric 不要。
 * 仕様: 詳細設計書 v0.8 §11.5 / §11.8
 */
public class Sc04ViewStateTest {

    @Test
    public void activeCommunicatingWithTargets() {
        // ケース7: ACTIVE + relay=true + 対象者あり
        Sc04ViewState view =
                Sc04ViewState.of(SessionStatus.State.ACTIVE, true, true);
        assertTrue(view.countdownRunning);
        assertTrue(view.commOk);
        assertEquals(R.string.sc04_comm_active, view.commStatusRes);
        assertTrue(view.listVisible);
        assertEquals(0, view.emptyTextRes);
        assertEquals(0, view.blockReasonRes);
    }

    @Test
    public void activeBlockedStillShowsListAndCountdown() {
        // ケース8: ACTIVE + relay=false + 対象者あり → 通信不能でも一覧を出し、72時間は進む
        Sc04ViewState view =
                Sc04ViewState.of(SessionStatus.State.ACTIVE, false, true);
        assertFalse(view.commOk);
        assertEquals(R.string.sc04_comm_blocked, view.commStatusRes);
        assertTrue("通信不能でも一覧は表示する", view.listVisible);
        assertEquals(0, view.emptyTextRes);
        assertEquals(R.string.sc04_block_comm, view.blockReasonRes);
        assertTrue("通信不能でも72時間は進む", view.countdownRunning);
    }

    @Test
    public void clockUncertainShowsListWithoutCountdown() {
        // ケース9: CLOCK_UNCERTAIN + 対象者あり → 一覧は出すがカウントダウンは動かさない
        Sc04ViewState view =
                Sc04ViewState.of(SessionStatus.State.CLOCK_UNCERTAIN, true, true);
        assertFalse(view.countdownRunning);
        assertFalse(view.commOk);
        assertEquals(R.string.sc04_comm_blocked, view.commStatusRes);
        assertTrue(view.listVisible);
        assertEquals(0, view.emptyTextRes);
        assertEquals(R.string.sc04_block_clock_uncertain, view.blockReasonRes);
    }

    @Test
    public void activeWithoutTargetsShowsNoTargetsMessage() {
        // ケース10: ACTIVE + 対象者なし
        Sc04ViewState view =
                Sc04ViewState.of(SessionStatus.State.ACTIVE, true, false);
        assertTrue(view.countdownRunning);
        assertFalse(view.listVisible);
        assertEquals(R.string.sc04_no_watch_targets, view.emptyTextRes);
        assertEquals(0, view.blockReasonRes);
    }

    @Test
    public void endedAndNoSessionShowNoSessionMessage() {
        // ケース11: ENDED / NO_SESSION → 現在の通信期間なし
        Sc04ViewState ended =
                Sc04ViewState.of(SessionStatus.State.ENDED, true, true);
        assertFalse(ended.countdownRunning);
        assertFalse(ended.commOk);
        assertFalse(ended.listVisible);
        assertEquals(R.string.state_no_session, ended.emptyTextRes);
        assertEquals(0, ended.blockReasonRes);

        Sc04ViewState noSession =
                Sc04ViewState.of(SessionStatus.State.NO_SESSION, false, false);
        assertFalse(noSession.countdownRunning);
        assertFalse(noSession.commOk);
        assertFalse(noSession.listVisible);
        assertEquals(R.string.state_no_session, noSession.emptyTextRes);
        assertEquals(0, noSession.blockReasonRes);
    }

    @Test
    public void nullStateIsNoSession() {
        // state==null（エラー時）も「期間なし」扱い
        Sc04ViewState view = Sc04ViewState.of(null, true, true);
        assertFalse(view.countdownRunning);
        assertFalse(view.commOk);
        assertFalse(view.listVisible);
        assertEquals(R.string.state_no_session, view.emptyTextRes);
        assertEquals(0, view.blockReasonRes);
    }
}