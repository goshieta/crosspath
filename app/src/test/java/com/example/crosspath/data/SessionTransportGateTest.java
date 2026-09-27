package com.example.crosspath.data;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class SessionTransportGateTest {
    private final AtomicLong wall = new AtomicLong(10_000);
    private final AtomicLong elapsed = new AtomicLong(100);
    private final SessionTransportGate gate = new SessionTransportGate(wall::get, elapsed::get);

    private SessionStatus status(String id) {
        ActiveSession session = new ActiveSession();
        session.sessionId = id;
        session.startedAtWall = 10_000;
        session.endsAtWall = 20_000;
        return SessionStatus.evaluate(session, wall.get());
    }

    @Test public void rejectsBeforeOpenAndAtExactElapsedDeadline() {
        assertFalse(gate.allowsCommunication());
        gate.open(status("A"), elapsed.get());
        assertTrue(gate.allowsCommunication());
        wall.set(19_999); elapsed.set(10_099);
        assertTrue(gate.allowsCommunication());
        elapsed.incrementAndGet();
        assertFalse(gate.allowsCommunication());
    }

    @Test public void repositoryLatencyDoesNotExtendDeadline() {
        SessionStatus status = status("A");
        elapsed.set(10_100);
        gate.open(status, 100);
        assertFalse(gate.allowsCommunication());
    }

    @Test public void wallDeadlineAlsoClosesGateAndCannotReopenByChangingClock() {
        gate.open(status("A"), 100);
        wall.set(20_000);
        assertFalse(gate.allowsCommunication());
        wall.set(10_000);
        assertFalse(gate.allowsCommunication());
    }

    @Test public void rollbackOrElapsedResetClosesGate() {
        gate.open(status("A"), 100);
        wall.set(8_999);
        assertFalse(gate.allowsCommunication());
        wall.set(10_000);
        gate.open(status("A"), 100);
        elapsed.set(99);
        assertFalse(gate.allowsCommunication());
    }

    @Test public void forwardJumpFollowedByRollbackIsDetected() {
        gate.open(status("A"), 100);
        wall.set(18_000);
        assertTrue(gate.allowsCommunication());
        wall.set(16_999);
        assertFalse(gate.allowsCommunication());
    }

    @Test public void oldSessionStopCannotCloseNewSession() {
        gate.open(status("B"), 100);
        gate.stopSession("A");
        assertTrue(gate.allowsCommunication());
        gate.stopSession("B");
        assertFalse(gate.allowsCommunication());
    }

    @Test public void uncertainSessionCannotOpenGate() {
        wall.set(8_000);
        gate.open(status("A"), 100);
        assertFalse(gate.allowsCommunication());
    }

    @Test public void registeredStopHandlerClosesGateUntilRemoved() {
        SessionStopRegistry registry = new SessionStopRegistry();
        SessionStopHandler handler = gate::stopSession;
        registry.add(handler);
        gate.open(status("A"), 100);
        registry.stopSession("A");
        assertFalse(gate.allowsCommunication());
        registry.remove(handler);
        gate.open(status("A"), 100);
        registry.stopSession("A");
        assertTrue(gate.allowsCommunication());
    }
}
