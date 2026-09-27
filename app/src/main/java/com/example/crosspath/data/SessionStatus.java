package com.example.crosspath.data;

import java.util.Objects;

/**
 * Immutable, point-in-time communication gate. Recheck before each send; this is not a lease.
 * All times are persisted UTC epoch milliseconds, except remainingMillis (a duration).
 */
public final class SessionStatus {
    public enum State { NO_SESSION, ACTIVE, EXPIRED, ENDED, CLOCK_UNCERTAIN }

    public final State state;
    /** Null only for NO_SESSION. Keep this token with every queued communication operation. */
    public final String sessionId;
    public final long startedAtWall;
    public final long endsAtWall;
    public final long remainingMillis;
    /** Also respects the persisted relayEnabled flag. */
    public final boolean canCommunicate;

    private SessionStatus(State state, ActiveSession session, long remainingMillis) {
        this.state = state;
        sessionId = session == null ? null : session.sessionId;
        startedAtWall = session == null ? 0 : session.startedAtWall;
        endsAtWall = session == null ? 0 : session.endsAtWall;
        this.remainingMillis = remainingMillis;
        canCommunicate = state == State.ACTIVE && session.relayEnabled;
    }

    /** False for an old connection token even when a new session is active. */
    public boolean canCommunicate(String expectedSessionId) {
        return canCommunicate && Objects.equals(sessionId, expectedSessionId);
    }

    // Package-private: production callers cannot supply a fabricated current time.
    static SessionStatus evaluate(ActiveSession session, long now) {
        return evaluate(session, now, true);
    }

    static SessionStatus evaluate(ActiveSession session, long now, boolean trusted) {
        if (session == null) return new SessionStatus(State.NO_SESSION, null, 0);
        if (!"ACTIVE".equals(session.state)) return new SessionStatus(State.ENDED, session, 0);
        if (now >= session.endsAtWall) return new SessionStatus(State.EXPIRED, session, 0);
        if (!trusted || now < session.startedAtWall || now < session.lastObservedWall) {
            return new SessionStatus(State.CLOCK_UNCERTAIN, session, 0);
        }
        return new SessionStatus(State.ACTIVE, session, session.endsAtWall - now);
    }
}
