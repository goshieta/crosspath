package com.example.crosspath.data;

import org.junit.Test;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class SessionStatusTest {
    private ActiveSession session() {
        ActiveSession session = new ActiveSession();
        session.sessionId = "period";
        session.startedAtWall = 1_000_000;
        session.lastObservedWall = session.startedAtWall;
        session.endsAtWall = session.startedAtWall + TimeUnit.HOURS.toMillis(72);
        return session;
    }

    @Test public void boundariesUsePersistedDeadlineAndNeverReturnNegativeTime() {
        ActiveSession session = session();
        assertEquals(TimeUnit.HOURS.toMillis(72),
                SessionStatus.evaluate(session, session.startedAtWall).remainingMillis);
        SessionStatus before = SessionStatus.evaluate(session, session.endsAtWall - 1);
        assertEquals(SessionStatus.State.ACTIVE, before.state);
        assertEquals(1, before.remainingMillis);
        assertTrue(before.canCommunicate("period"));
        assertFalse(before.canCommunicate("old-period"));
        assertFalse(before.canCommunicate(null));
        for (long now : new long[]{session.endsAtWall, session.endsAtWall + 1, Long.MAX_VALUE}) {
            SessionStatus expired = SessionStatus.evaluate(session, now);
            assertEquals(SessionStatus.State.EXPIRED, expired.state);
            assertFalse(expired.canCommunicate);
            assertEquals(0, expired.remainingMillis);
        }
    }

    @Test public void missingEndedAndClockRollbackAreDistinctAndBlocked() {
        SessionStatus missing = SessionStatus.evaluate(null, 0);
        assertEquals(SessionStatus.State.NO_SESSION, missing.state);
        assertNull(missing.sessionId);
        assertFalse(missing.canCommunicate);
        assertEquals(0, missing.remainingMillis);
        ActiveSession session = session();
        session.lastObservedWall += 100;
        SessionStatus uncertain = SessionStatus.evaluate(session, session.startedAtWall);
        assertEquals(SessionStatus.State.CLOCK_UNCERTAIN, uncertain.state);
        assertFalse(uncertain.canCommunicate);
        assertEquals(0, uncertain.remainingMillis);
        session.state = "ENDED";
        assertEquals(SessionStatus.State.ENDED,
                SessionStatus.evaluate(session, session.startedAtWall - 1).state);
        assertFalse(SessionStatus.evaluate(session, session.endsAtWall + 1).canCommunicate);
    }

    @Test public void snapshotIsImmutableAndRelayFlagDoesNotExtendThePeriod() {
        ActiveSession session = session();
        session.relayEnabled = false;
        SessionStatus status = SessionStatus.evaluate(session, session.endsAtWall - 1);
        assertEquals(SessionStatus.State.ACTIVE, status.state);
        assertFalse(status.canCommunicate);
        assertEquals(1, status.remainingMillis);
        session.endsAtWall++;
        session.sessionId = "replacement";
        assertEquals("period", status.sessionId);
        assertEquals(session.endsAtWall - 1, status.endsAtWall);
    }

    @Test public void clockEvaluationIsNotAPublicApi() throws Exception {
        assertFalse(Modifier.isPublic(SessionStatus.class
                .getDeclaredMethod("evaluate", ActiveSession.class, long.class).getModifiers()));
    }
}
