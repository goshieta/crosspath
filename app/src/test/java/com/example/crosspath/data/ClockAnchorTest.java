package com.example.crosspath.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClockAnchorTest {
    private ClockAnchor anchor(long wall, long elapsed, String boot) {
        ClockAnchor anchor = new ClockAnchor();
        anchor.wall = wall;
        anchor.elapsed = elapsed;
        anchor.bootMarker = boot;
        return anchor;
    }

    @Test public void sameBootIncludesElapsedTimeAndNeverMovesBackWithWallClock() {
        ClockAnchor previous = anchor(10_000, 100, "A");
        ClockAnchor.Observation time = ClockAnchor.observe(previous,
                new SessionClock.Reading(10_010, 200, "A"), 0);
        assertTrue(time.trusted); // Sampling skew tolerance does NOT extend remaining time.
        assertEquals(10_100, time.anchor.wall);
        time = ClockAnchor.observe(time.anchor, new SessionClock.Reading(5_000, 2_200, "A"), 0);
        assertFalse(time.trusted);
        assertEquals(12_100, time.anchor.wall);
    }

    @Test public void forwardJumpIsPersistedAndCannotBeUndone() {
        ClockAnchor.Observation forward = ClockAnchor.observe(anchor(10_000, 100, "A"),
                new SessionClock.Reading(100_000, 200, "A"), 0);
        assertEquals(100_000, forward.anchor.wall);
        ClockAnchor.Observation back = ClockAnchor.observe(forward.anchor,
                new SessionClock.Reading(10_100, 300, "A"), 0);
        assertFalse(back.trusted);
        assertEquals(100_100, back.anchor.wall);
    }

    @Test public void rebootUsesUtcThenAnchorsSubsequentElapsedTime() {
        ClockAnchor.Observation reboot = ClockAnchor.observe(anchor(10_000, 900_000, "A"),
                new SessionClock.Reading(20_000, 10, "B"), 0);
        assertTrue(reboot.trusted);
        assertEquals(20_000, reboot.anchor.wall);
        ClockAnchor.Observation later = ClockAnchor.observe(reboot.anchor,
                new SessionClock.Reading(20_001, 2_010, "B"), 0);
        assertFalse(later.trusted);
        assertEquals(22_000, later.anchor.wall);
    }

    @Test public void rebootRollbackAndUnknownBootAreBlocked() {
        ClockAnchor prior = anchor(20_000, 10_000, "A");
        assertFalse(ClockAnchor.observe(prior,
                new SessionClock.Reading(10_000, 0, "B"), 0).trusted);
        ClockAnchor.Observation unknown = ClockAnchor.observe(prior,
                new SessionClock.Reading(20_001, 20_000, ""), 0);
        assertFalse(unknown.trusted);
        assertEquals("A", unknown.anchor.bootMarker);
        assertEquals(prior.elapsed, unknown.anchor.elapsed);
    }

    @Test public void RegressingElapsedCannotReplaceKnownAnchor() {
        ClockAnchor prior = anchor(20_000, 10_000, "A");
        ClockAnchor.Observation invalid = ClockAnchor.observe(prior,
                new SessionClock.Reading(20_000, 9_000, "A"), 0);
        assertFalse(invalid.trusted);
        assertEquals(10_000, invalid.anchor.elapsed);
        assertFalse(ClockAnchor.observe(invalid.anchor,
                new SessionClock.Reading(20_000, 9_100, "A"), 0).trusted);
    }

    @Test public void overflowSaturatesInsteadOfReopeningExpiredTime() {
        ClockAnchor.Observation time = ClockAnchor.observe(anchor(Long.MAX_VALUE - 2, 0, "A"),
                new SessionClock.Reading(10_000, 10, "A"), 0);
        assertEquals(Long.MAX_VALUE, time.anchor.wall);
        assertFalse(time.trusted);
    }
}
