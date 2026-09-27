package com.example.crosspath.data;

import java.util.function.LongSupplier;

/** In-memory, fail-closed fragment gate between repository checks. Never extends a period. */
public final class SessionTransportGate {
    private final LongSupplier wallClock;
    private final LongSupplier elapsedClock;
    private String sessionId;
    private long endsAtWall, expiresAtElapsed, sampledWall, sampledElapsed;

    public SessionTransportGate(LongSupplier wallClock, LongSupplier elapsedClock) {
        this.wallClock = wallClock;
        this.elapsedClock = elapsedClock;
    }

    /** Sample elapsed before requesting repository status, so DB latency cannot extend validity. */
    public synchronized void open(SessionStatus status, long requestedAtElapsed) {
        close();
        if (!status.canCommunicate) return;
        sessionId = status.sessionId;
        endsAtWall = status.endsAtWall;
        sampledElapsed = requestedAtElapsed;
        sampledWall = endsAtWall - status.remainingMillis;
        expiresAtElapsed = requestedAtElapsed + status.remainingMillis;
    }

    public synchronized boolean allowsCommunication() {
        if (sessionId == null) return false;
        long elapsed = elapsedClock.getAsLong();
        long wall = wallClock.getAsLong();
        long projected = sampledWall + Math.max(0, elapsed - sampledElapsed);
        if (elapsed < sampledElapsed || elapsed >= expiresAtElapsed || wall >= endsAtWall
                || wall < projected - 1000) {
            close();
            return false;
        }
        // Track forward wall-clock jumps; moving it back later must not restore communication.
        sampledWall = Math.max(projected, wall);
        sampledElapsed = elapsed;
        return true;
    }

    public synchronized void stopSession(String expectedId) {
        if (expectedId != null && expectedId.equals(sessionId)) close();
    }

    public synchronized void close() { sessionId = null; }
}
