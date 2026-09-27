package com.example.crosspath.ui;

import org.junit.Test;

import com.example.crosspath.R;
import com.example.crosspath.data.SessionStatus;
import com.example.crosspath.ui.theme.ScreenThemes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * ScreenPolicy（画面遷移・通信状態の判断）の回帰テスト。JVM のみ、Robolectric 不要。
 * 仕様: 詳細設計書 v0.8 §11.1 / §11.5 / §11.8
 */
public class ScreenPolicyTest {

    @Test
    public void activeWithRelayCommunicating() {
        // ケース1: ACTIVE（relay=true）
        assertSame(ScreenPolicy.CommState.COMMUNICATING,
                ScreenPolicy.commState(SessionStatus.State.ACTIVE, true));
        assertTrue(ScreenPolicy.isActivePeriod(SessionStatus.State.ACTIVE));
        assertTrue(ScreenPolicy.emergencyMode(SessionStatus.State.ACTIVE));
        assertSame(Screen.SC04, ScreenPolicy.screenFor(Screen.SC02, SessionStatus.State.ACTIVE));
        assertSame(Screen.SC04, ScreenPolicy.initialScreen(true, SessionStatus.State.ACTIVE));
    }

    @Test
    public void activeWithoutRelayIsBlockedButStaysOnSc04() {
        // ケース2: ACTIVE・relay=false → 通信不能でも SC02 へ落ちない（72時間は進む）
        assertSame(ScreenPolicy.CommState.BLOCKED,
                ScreenPolicy.commState(SessionStatus.State.ACTIVE, false));
        assertSame(Screen.SC04, ScreenPolicy.screenFor(Screen.SC02, SessionStatus.State.ACTIVE));
        assertSame(Screen.SC04, ScreenPolicy.screenFor(Screen.SC04, SessionStatus.State.ACTIVE));
        assertEquals(R.string.sc04_block_comm, ScreenPolicy.blockReasonRes(
                ScreenPolicy.commState(SessionStatus.State.ACTIVE, false)));
        assertEquals(R.string.sc04_comm_blocked, ScreenPolicy.commStatusRes(
                ScreenPolicy.commState(SessionStatus.State.ACTIVE, false)));
    }

    @Test
    public void clockUncertainStaysOnSc04WithClockBlockReason() {
        // ケース3: CLOCK_UNCERTAIN → 誤遷移しない。home からは SC04 へ
        assertSame(ScreenPolicy.CommState.CLOCK_UNCERTAIN,
                ScreenPolicy.commState(SessionStatus.State.CLOCK_UNCERTAIN, true));
        assertSame(ScreenPolicy.CommState.CLOCK_UNCERTAIN,
                ScreenPolicy.commState(SessionStatus.State.CLOCK_UNCERTAIN, false));
        assertTrue(ScreenPolicy.emergencyMode(SessionStatus.State.CLOCK_UNCERTAIN));
        assertSame(Screen.SC04, ScreenPolicy.screenFor(Screen.SC04, SessionStatus.State.CLOCK_UNCERTAIN));
        assertSame(Screen.SC04, ScreenPolicy.screenFor(Screen.SC02, SessionStatus.State.CLOCK_UNCERTAIN));
        assertSame(Screen.SC04, ScreenPolicy.initialScreen(true, SessionStatus.State.CLOCK_UNCERTAIN));
        assertEquals(R.string.sc04_block_clock_uncertain, ScreenPolicy.blockReasonRes(
                ScreenPolicy.commState(SessionStatus.State.CLOCK_UNCERTAIN, true)));
        assertEquals(R.string.sc04_comm_blocked, ScreenPolicy.commStatusRes(
                ScreenPolicy.commState(SessionStatus.State.CLOCK_UNCERTAIN, true)));
    }

    @Test
    public void noPeriodStatesEndEmergencyAndGoToSc02() {
        // ケース4: NO_SESSION / ENDED / EXPIRED は期間終了
        SessionStatus.State[] ended = {
                SessionStatus.State.NO_SESSION,
                SessionStatus.State.ENDED,
                SessionStatus.State.EXPIRED
        };
        for (SessionStatus.State state : ended) {
            assertSame(ScreenPolicy.CommState.NO_PERIOD, ScreenPolicy.commState(state, true));
            assertSame(ScreenPolicy.CommState.NO_PERIOD, ScreenPolicy.commState(state, false));
            assertFalse(ScreenPolicy.isActivePeriod(state));
            assertFalse("states must not be emergency: " + state, ScreenPolicy.emergencyMode(state));
            assertSame("SC04 must fall to SC02: " + state,
                    Screen.SC02, ScreenPolicy.screenFor(Screen.SC04, state));
            assertSame("registered but no active period -> SC02: " + state,
                    Screen.SC02, ScreenPolicy.initialScreen(true, state));
            assertSame("unregistered -> SC01: " + state,
                    Screen.SC01, ScreenPolicy.initialScreen(false, state));
            assertEquals("no block reason when no period: " + state,
                    0, ScreenPolicy.blockReasonRes(
                            ScreenPolicy.commState(state, false)));
            assertEquals(R.string.sc04_comm_inactive, ScreenPolicy.commStatusRes(
                    ScreenPolicy.commState(state, false)));
        }
    }

    @Test
    public void nullStateIsTreatedAsNoPeriod() {
        // state==null は非作動扱い（起動直後のフォールバック等）
        assertFalse(ScreenPolicy.isActivePeriod(null));
        assertFalse(ScreenPolicy.emergencyMode(null));
        assertSame(ScreenPolicy.CommState.NO_PERIOD, ScreenPolicy.commState(null, true));
        assertSame(Screen.SC02, ScreenPolicy.screenFor(Screen.SC04, null));
        assertSame(Screen.SC02, ScreenPolicy.initialScreen(true, null));
        assertSame(Screen.SC01, ScreenPolicy.initialScreen(false, null));
    }

    @Test
    public void backTargetOnlyFromSc03ToSc02() {
        // ケース5: 戻る操作は SC03→SC02 のみ。古い SC04 へ戻る経路が無い
        assertSame(Screen.SC02, ScreenPolicy.backTarget(Screen.SC03));
        assertNull(ScreenPolicy.backTarget(Screen.SC01));
        assertNull(ScreenPolicy.backTarget(Screen.SC02));
        assertNull(ScreenPolicy.backTarget(Screen.SC04));
        assertNull(ScreenPolicy.backTarget(Screen.SC05));
        assertNull(ScreenPolicy.backTarget(Screen.SC06));
    }

    @Test
    public void tabsAndEmergencyThemeStayConsistent() {
        // ケース6: タブ整合（タブ遷移後もテーマが一致）
        assertTrue(BottomTabs.showsTabs(Screen.SC02));
        assertTrue(BottomTabs.showsTabs(Screen.SC04));
        assertTrue(BottomTabs.showsTabs(Screen.SC05));
        assertTrue(BottomTabs.showsTabs(Screen.SC06));
        assertFalse(BottomTabs.showsTabs(Screen.SC01));
        assertFalse(BottomTabs.showsTabs(Screen.SC03));

        assertTrue("CLOCK_UNCERTAIN 中は SC05 も緊急時ダーク",
                ScreenThemes.isEmergencyVariant(
                        Screen.SC05, ScreenPolicy.emergencyMode(SessionStatus.State.CLOCK_UNCERTAIN)));
        assertFalse("期間終了中は SC05 はライト",
                ScreenThemes.isEmergencyVariant(
                        Screen.SC05, ScreenPolicy.emergencyMode(SessionStatus.State.ENDED)));
    }

    @Test
    public void initialScreenResolution() {
        // 起動時: 未登録=SC01／作動中=SC04／それ以外=SC02
        assertSame(Screen.SC01, ScreenPolicy.initialScreen(false, null));
        assertSame(Screen.SC01, ScreenPolicy.initialScreen(false, SessionStatus.State.ACTIVE));
        assertSame(Screen.SC04, ScreenPolicy.initialScreen(true, SessionStatus.State.ACTIVE));
        assertSame(Screen.SC02, ScreenPolicy.initialScreen(true, SessionStatus.State.ENDED));
        assertSame(Screen.SC02, ScreenPolicy.initialScreen(true, null));
    }

    @Test
    public void screenForKeepsCurrentWhenNoChangeNeeded() {
        // 作動中: SC02 以外は current のまま。非作動: SC04 以外は current のまま。
        assertSame(Screen.SC03, ScreenPolicy.screenFor(Screen.SC03, SessionStatus.State.ACTIVE));
        assertSame(Screen.SC05, ScreenPolicy.screenFor(Screen.SC05, SessionStatus.State.CLOCK_UNCERTAIN));
        assertSame(Screen.SC02, ScreenPolicy.screenFor(Screen.SC02, SessionStatus.State.EXPIRED));
        assertSame(Screen.SC05, ScreenPolicy.screenFor(Screen.SC05, SessionStatus.State.ENDED));
        assertSame(Screen.SC04, ScreenPolicy.screenFor(Screen.SC04, SessionStatus.State.ACTIVE));
    }
}