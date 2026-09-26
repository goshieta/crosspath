package com.example.crosspath.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Persisted logical UTC lower bound; shared across sessions and history retention. */
@Entity
public final class ClockAnchor {
    @PrimaryKey public int singletonId = 1;
    public long wall;
    public long elapsed;
    @NonNull public String bootMarker = "";

    static final class Observation {
        final ClockAnchor anchor;
        final boolean trusted;

        Observation(ClockAnchor anchor, boolean trusted) {
            this.anchor = anchor;
            this.trusted = trusted;
        }
    }

    static Observation observe(ClockAnchor previous, SessionClock.Reading reading, long minimumWall) {
        long lowerBound = minimumWall;
        boolean trusted = !reading.bootMarker.isEmpty() && reading.elapsed >= 0 && reading.wall >= 0;
        if (previous != null) {
            lowerBound = Math.max(lowerBound, previous.wall);
            if (!reading.bootMarker.isEmpty() && reading.bootMarker.equals(previous.bootMarker)) {
                if (reading.elapsed < previous.elapsed) {
                    trusted = false;
                } else {
                    long delta = reading.elapsed - previous.elapsed;
                    long projected = previous.wall > Long.MAX_VALUE - delta
                            ? Long.MAX_VALUE : previous.wall + delta;
                    lowerBound = Math.max(lowerBound, projected);
                }
            }
        }
        // Allow sub-second sampling skew; even within this tolerance the deadline never extends.
        if (reading.wall < lowerBound && lowerBound - Math.max(0, reading.wall) > 1_000) {
            trusted = false;
        }
        ClockAnchor next = new ClockAnchor();
        next.wall = Math.max(reading.wall, lowerBound);
        next.elapsed = reading.elapsed;
        next.bootMarker = reading.bootMarker;
        if (previous != null && (reading.bootMarker.isEmpty()
                || (reading.bootMarker.equals(previous.bootMarker) && reading.elapsed < previous.elapsed))) {
            // Do not replace a known clock anchor with an unusable sample.
            next.elapsed = previous.elapsed;
            next.bootMarker = previous.bootMarker;
        }
        return new Observation(next, trusted);
    }
}
